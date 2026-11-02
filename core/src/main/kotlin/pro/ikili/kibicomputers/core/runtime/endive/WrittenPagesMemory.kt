package pro.ikili.kibicomputers.core.runtime.endive

import pro.ikili.kibicomputers.core.runtime.WasmTableBudget
import run.endive.runtime.ConstantEvaluators
import run.endive.runtime.Instance
import run.endive.runtime.WasmRuntimeException
import run.endive.wasm.UninstantiableException
import run.endive.wasm.WasmEngineException
import run.endive.wasm.types.ActiveDataSegment
import run.endive.wasm.types.DataSegment
import run.endive.wasm.types.MemoryLimits
import run.endive.wasm.types.PassiveDataSegment
import java.nio.BufferOverflowException
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ReadOnlyBufferException
import run.endive.runtime.Memory as EndiveMemory

/**
 * 書き換えた 4 KiB の数で RAM を数える線形メモリ（#370 の PoC。既定では使わない）。
 *
 * [LazyMemory] と同じく、ページの表を引いて読み書きし、読み取り専用のページへの書き込みの例外で差し替える
 * （読み書きの速い道に分岐を足さない）。違いは、ページが 4 KiB であることと、ページが3種類あること。
 *
 * - ゼロのページ: すべてのメモリで共有する読み取り専用の 4 KiB（[ZERO_CHUNK]）。
 * - ROM のページ: インスタンスを作るとき（能動データセグメント）に書いた中身。読み取り専用で、**数えない**。
 * - 書き換えたページ: 初めて書いたときに確保し（ROM なら中身を写す）、[budget] で 4 KiB を数える。
 *   断られたら [RamExhausted] でトラップにする。
 *
 * 数えた分は、インスタンスを閉じるまで返さない（解放しても減らない。使った量の最高値になる）。閉じたら [releaseAll] でまとめて返す。
 * `memory.grow` の扱いは2通り（[countGrownPages]）。
 * - `false`（書き換えた量だけ）: 増やした大きさは RAM として数えず、アドレス空間の上限（[maximumPages]）だけを見る。
 *   ただし、増やした所の最初と最後の 4 KiB だけは先に数える（dlmalloc などのアロケーターが増やした直後に書く所。
 *   数えられなければ grow が -1 になり、アロケーターは NULL を返して優しく失敗できる）。
 * - `true`（最初の大きさは書き換えた量、増やした分はページ数）: 増やしたページ（64 KiB）をそのまま数え、数えられなければ -1。
 *   増やした所への書き込みは数え直さない。
 * ページの表は `pages()` に合わせて伸ばす（上限の分を先に作らない）。共有メモリ（threads）には使わない。
 */
internal class WrittenPagesMemory(
    private val limits: MemoryLimits,
    private val budget: WasmTableBudget?,
    private val countGrownPages: Boolean = false,
) : EndiveMemory {
    init {
        require(!limits.shared()) { "共有メモリには使わない" }
    }

    private val maxPages: Int = minOf(limits.maximumPages(), EndiveMemory.RUNTIME_MAX_PAGES)

    /** 4 KiB のページの表。範囲内でまだ書いていないページは [ZERO_CHUNK]、範囲外は表の外 */
    @Volatile
    private var chunks: Array<ByteBuffer?> = Array(limits.initialPages() * CHUNKS_PER_PAGE) { ZERO_CHUNK }

    /** 今の大きさ（64 KiB のページ数） */
    @Volatile
    private var nPages: Int = limits.initialPages()

    /** 書き換えた（数えた）4 KiB の数 */
    @Volatile
    var writtenChunks: Int = 0
        private set

    /** [countGrownPages] のとき、grow で数えたページ数 */
    @Volatile
    var grownPages: Int = 0
        private set

    /** 最初の大きさの 4 KiB の数（[countGrownPages] のとき、これより先は grow で数えてある） */
    private val initialChunks: Int = limits.initialPages() * CHUNKS_PER_PAGE

    /** RAM として数えたバイト数（書き換えた 4 KiB と、grow で数えたページ） */
    val countedBytes: Long get() = writtenChunks.toLong() * CHUNK_SIZE + grownPages.toLong() * EndiveMemory.PAGE_SIZE

    /** ROM のページ（データセグメントで書いた 4 KiB）の数 */
    @Volatile
    var romChunks: Int = 0
        private set

    /** 閉じた（[releaseAll] した）。以後は数えない */
    private var released = false

    /** インスタンスを作っている途中に、データセグメントを書き込む先（作り終えたら読み取り専用にする） */
    private var romWriting: HashMap<Int, ByteBuffer>? = null

    private var dataSegments: Array<DataSegment>? = null

    override fun pages(): Int = nPages

    @Synchronized
    override fun grow(size: Int): Int {
        val prev = nPages
        val next = prev + size
        if (next > maximumPages() || next < prev) return -1
        if (size == 0) return prev
        if (countGrownPages) {
            if (!released && budget != null && !budget.tryReserveBytes(size.toLong() * EndiveMemory.PAGE_SIZE)) return -1
            extendTable(next)
            if (!released) grownPages += size
            return prev
        }
        // 増やした所の最初と最後の 4 KiB は、先に数えて確保する（アロケーターは増やした直後にそこへ管理の情報を書く）。
        // 数えられなければ増やさない（-1。アロケーターは NULL を返せる。トラップより優しい失敗）
        val headChunk = prev * CHUNKS_PER_PAGE
        val tailChunk = next * CHUNKS_PER_PAGE - 1
        val edges = if (headChunk == tailChunk) 1L else 2L
        if (!released && budget != null && !budget.tryReserveBytes(edges * CHUNK_SIZE)) return -1
        val old = chunks
        val fresh = arrayOfNulls<ByteBuffer>(next * CHUNKS_PER_PAGE)
        System.arraycopy(old, 0, fresh, 0, old.size)
        java.util.Arrays.fill(fresh, old.size, fresh.size, ZERO_CHUNK)
        fresh[headChunk] = ByteBuffer.allocate(CHUNK_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        fresh[tailChunk] = ByteBuffer.allocate(CHUNK_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        if (!released) writtenChunks += edges.toInt()
        chunks = fresh
        nPages = next
        return prev
    }

    /** ページの表を [next] ページ分に伸ばす（増やした所はゼロのページ） */
    private fun extendTable(next: Int) {
        val old = chunks
        val fresh = arrayOfNulls<ByteBuffer>(next * CHUNKS_PER_PAGE)
        System.arraycopy(old, 0, fresh, 0, old.size)
        java.util.Arrays.fill(fresh, old.size, fresh.size, ZERO_CHUNK)
        chunks = fresh
        nPages = next
    }

    override fun initialPages(): Int = limits.initialPages()

    override fun maximumPages(): Int = maxPages

    override fun shared(): Boolean = false

    @Suppress("OVERRIDE_DEPRECATION")
    override fun lock(address: Int): Any = this

    @Suppress("OVERRIDE_DEPRECATION")
    override fun waitOn(address: Int, expected: Int, timeout: Long): Int = throw WasmEngineException("Attempt to wait on a non-shared memory, not supported.")

    @Suppress("OVERRIDE_DEPRECATION")
    override fun waitOn(address: Int, expected: Long, timeout: Long): Int = throw WasmEngineException("Attempt to wait on a non-shared memory, not supported.")

    @Suppress("OVERRIDE_DEPRECATION")
    override fun notify(address: Int, maxThreads: Int): Int = 0

    override fun initialize(instance: Instance, dataSegments: Array<DataSegment>?) = initialize(instance, dataSegments, 0)

    override fun initialize(instance: Instance, dataSegments: Array<DataSegment>?, memoryIndex: Int) {
        this.dataSegments = dataSegments
        if (dataSegments == null) return
        val rom = HashMap<Int, ByteBuffer>()
        romWriting = rom
        try {
            for (s in dataSegments) {
                when (s) {
                    is ActiveDataSegment -> {
                        if (s.index() != memoryIndex.toLong()) continue
                        val data = s.bytes()
                        val offset = ConstantEvaluators.computeConstantValue(instance, s.offsetInstructions())[0].toInt()
                        checkBounds(offset, data.size, sizeInBytes()) { UninstantiableException(it) }
                        write(offset, data, 0, data.size)
                    }
                    is PassiveDataSegment -> Unit
                    else -> throw WasmEngineException("Data segment should be active or passive: $s")
                }
            }
        } finally {
            romWriting = null
        }
        // 書いたページを読み取り専用にする（読み取り専用の複製はバイト順を引き継がないので、付け直す）
        val table = chunks
        for ((index, buf) in rom) table[index] = buf.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
        romChunks = rom.size
    }

    override fun initPassiveSegment(segmentId: Int, dest: Int, offset: Int, size: Int) {
        write(dest, dataSegments!![segmentId].bytes(), offset, size)
    }

    override fun drop(segment: Int) {
        dataSegments!![segment] = PassiveDataSegment.EMPTY
    }

    private fun sizeInBytes(): Int = EndiveMemory.PAGE_SIZE * nPages

    /** [index] の 4 KiB を書き込めるようにして返す。範囲外なら範囲外の例外 */
    private fun writableChunk(index: Int, addr: Int, size: Int): ByteBuffer {
        val table = chunks
        val chunk = if (index in table.indices) table[index] else null
        if (chunk == null) throw outOfBounds(addr, size)
        if (!chunk.isReadOnly) return chunk
        return commit(index)
    }

    @Synchronized
    private fun commit(index: Int): ByteBuffer {
        val table = chunks
        val chunk = table.getOrNull(index) ?: throw outOfBounds(index shl CHUNK_SHIFT, 1)
        if (!chunk.isReadOnly) return chunk
        val rom = romWriting
        if (rom != null) {
            // インスタンスを作っている途中（データセグメント）: 数えない ROM のページに書く
            return rom.getOrPut(index) { ByteBuffer.allocate(CHUNK_SIZE).order(ByteOrder.LITTLE_ENDIAN) }
        }
        // grow で数えたページの中なら、数え直さない
        val counted = !released && !(countGrownPages && index >= initialChunks)
        if (counted && budget != null && !budget.tryReserveBytes(CHUNK_SIZE.toLong())) {
            throw RamExhausted(index shl CHUNK_SHIFT)
        }
        val fresh = ByteBuffer.allocate(CHUNK_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        if (chunk !== ZERO_CHUNK) chunk.get(0, fresh.array(), 0, CHUNK_SIZE)
        table[index] = fresh
        if (counted) writtenChunks++
        return fresh
    }

    /**
     * [addr] から [len] バイトの 4 KiB を、今のうちに書き込めるようにして数える（#370 の PoC の `ram_commit`）。
     * 範囲の中に数えられない 4 KiB があれば `false`（それまでに数えた分はそのまま）。
     * 範囲のうちメモリの外の分は数えない（[addr] がメモリの外なら `false`）
     */
    fun commitRange(addr: Int, len: Int): Boolean {
        if (len <= 0) return true
        if (addr < 0 || addr >= sizeInBytes()) return false
        val end = minOf(addr.toLong() + len, sizeInBytes().toLong())
        var index = addr ushr CHUNK_SHIFT
        val last = ((end - 1) ushr CHUNK_SHIFT).toInt()
        while (index <= last) {
            try {
                writableChunk(index, index shl CHUNK_SHIFT, 1)
            } catch (e: RamExhausted) {
                return false
            }
            index++
        }
        return true
    }

    /** [addr] から [len] バイトを書いたら、新しく数える 4 KiB のバイト数（範囲のうちメモリの外の分は入れない） */
    fun pendingBytes(addr: Int, len: Int): Long {
        if (len <= 0 || addr < 0 || addr >= sizeInBytes()) return 0
        val end = minOf(addr.toLong() + len, sizeInBytes().toLong())
        val table = chunks
        var n = 0L
        for (index in (addr ushr CHUNK_SHIFT)..((end - 1) ushr CHUNK_SHIFT).toInt()) {
            if (table[index]!!.isReadOnly && !(countGrownPages && index >= initialChunks)) n++
        }
        return n * CHUNK_SIZE
    }

    /** 数えた分をすべて返す。以後は数えない（閉じたインスタンスのメモリ）。2回呼んでもよい */
    @Synchronized
    fun releaseAll() {
        if (released) return
        released = true
        val bytes = countedBytes
        if (bytes > 0 && budget != null) budget.releaseBytes(bytes)
    }

    private fun outOfBounds(addr: Int, size: Int): WasmRuntimeException =
        WasmRuntimeException("out of bounds memory access: attempted to access address: $addr but limit is: ${sizeInBytes()} and size: $size")

    private fun translate(e: RuntimeException, addr: Int, size: Int): RuntimeException = when (e) {
        is IndexOutOfBoundsException, is BufferOverflowException, is BufferUnderflowException,
        is IllegalArgumentException, is NullPointerException, is NegativeArraySizeException,
        -> outOfBounds(addr, size)
        else -> e
    }

    private inline fun checkBounds(addr: Int, size: Int, limit: Int, error: (String) -> WasmEngineException) {
        if (addr < 0 || size < 0 || addr > limit || (size > 0 && addr.toLong() + size > limit)) {
            throw error("out of bounds memory access: attempted to access address: $addr but limit is: $limit and size: $size")
        }
    }

    // --- 読み込み（ゼロ・ROM のページもそのまま読める） ---

    override fun read(addr: Int): Byte = try {
        chunks[addr ushr CHUNK_SHIFT]!!.get(addr and CHUNK_MASK)
    } catch (e: RuntimeException) {
        throw translate(e, addr, 1)
    }

    override fun readBytes(addr: Int, len: Int): ByteArray {
        checkBounds(addr, len, sizeInBytes()) { WasmRuntimeException(it) }
        val result = ByteArray(len)
        val table = chunks
        var a = addr
        var dst = 0
        var remaining = len
        while (remaining > 0) {
            val off = a and CHUNK_MASK
            val n = minOf(remaining, CHUNK_SIZE - off)
            val chunk = table[a ushr CHUNK_SHIFT]!!
            if (chunk !== ZERO_CHUNK) chunk.get(off, result, dst, n)
            a += n
            dst += n
            remaining -= n
        }
        return result
    }

    override fun readInt(addr: Int): Int {
        val off = addr and CHUNK_MASK
        if (off + 4 > CHUNK_SIZE) {
            return (read(addr).toInt() and 0xff) or
                ((read(addr + 1).toInt() and 0xff) shl 8) or
                ((read(addr + 2).toInt() and 0xff) shl 16) or
                ((read(addr + 3).toInt() and 0xff) shl 24)
        }
        return try {
            chunks[addr ushr CHUNK_SHIFT]!!.getInt(off)
        } catch (e: RuntimeException) {
            throw translate(e, addr, 4)
        }
    }

    override fun readLong(addr: Int): Long {
        val off = addr and CHUNK_MASK
        if (off + 8 > CHUNK_SIZE) {
            return (readInt(addr).toLong() and 0xffffffffL) or (readInt(addr + 4).toLong() shl 32)
        }
        return try {
            chunks[addr ushr CHUNK_SHIFT]!!.getLong(off)
        } catch (e: RuntimeException) {
            throw translate(e, addr, 8)
        }
    }

    override fun readShort(addr: Int): Short {
        val off = addr and CHUNK_MASK
        if (off + 2 > CHUNK_SIZE) {
            return ((read(addr).toInt() and 0xff) or ((read(addr + 1).toInt() and 0xff) shl 8)).toShort()
        }
        return try {
            chunks[addr ushr CHUNK_SHIFT]!!.getShort(off)
        } catch (e: RuntimeException) {
            throw translate(e, addr, 2)
        }
    }

    override fun readU16(addr: Int): Long = readShort(addr).toLong() and 0xffffL

    override fun readF32(addr: Int): Long = readInt(addr).toLong()

    override fun readFloat(addr: Int): Float = java.lang.Float.intBitsToFloat(readInt(addr))

    override fun readF64(addr: Int): Long = readLong(addr)

    override fun readDouble(addr: Int): Double = java.lang.Double.longBitsToDouble(readLong(addr))

    // --- 書き込み（読み取り専用のページなら、確保してからやり直す） ---

    override fun writeByte(addr: Int, data: Byte) {
        try {
            chunks[addr ushr CHUNK_SHIFT]!!.put(addr and CHUNK_MASK, data)
        } catch (e: ReadOnlyBufferException) {
            writableChunk(addr ushr CHUNK_SHIFT, addr, 1).put(addr and CHUNK_MASK, data)
        } catch (e: RuntimeException) {
            throw translate(e, addr, 1)
        }
    }

    override fun writeI32(addr: Int, data: Int) {
        val off = addr and CHUNK_MASK
        if (off + 4 > CHUNK_SIZE) {
            checkBounds(addr, 4, sizeInBytes()) { WasmRuntimeException(it) }
            for (i in 0 until 4) writeByte(addr + i, (data ushr (8 * i)).toByte())
            return
        }
        try {
            chunks[addr ushr CHUNK_SHIFT]!!.putInt(off, data)
        } catch (e: ReadOnlyBufferException) {
            writableChunk(addr ushr CHUNK_SHIFT, addr, 4).putInt(off, data)
        } catch (e: RuntimeException) {
            throw translate(e, addr, 4)
        }
    }

    override fun writeLong(addr: Int, data: Long) {
        val off = addr and CHUNK_MASK
        if (off + 8 > CHUNK_SIZE) {
            checkBounds(addr, 8, sizeInBytes()) { WasmRuntimeException(it) }
            for (i in 0 until 8) writeByte(addr + i, (data ushr (8 * i)).toByte())
            return
        }
        try {
            chunks[addr ushr CHUNK_SHIFT]!!.putLong(off, data)
        } catch (e: ReadOnlyBufferException) {
            writableChunk(addr ushr CHUNK_SHIFT, addr, 8).putLong(off, data)
        } catch (e: RuntimeException) {
            throw translate(e, addr, 8)
        }
    }

    override fun writeShort(addr: Int, data: Short) {
        val off = addr and CHUNK_MASK
        if (off + 2 > CHUNK_SIZE) {
            checkBounds(addr, 2, sizeInBytes()) { WasmRuntimeException(it) }
            writeByte(addr, data.toByte())
            writeByte(addr + 1, (data.toInt() ushr 8).toByte())
            return
        }
        try {
            chunks[addr ushr CHUNK_SHIFT]!!.putShort(off, data)
        } catch (e: ReadOnlyBufferException) {
            writableChunk(addr ushr CHUNK_SHIFT, addr, 2).putShort(off, data)
        } catch (e: RuntimeException) {
            throw translate(e, addr, 2)
        }
    }

    override fun writeF32(addr: Int, data: Float) = writeI32(addr, java.lang.Float.floatToRawIntBits(data))

    override fun writeF64(addr: Int, data: Double) = writeLong(addr, java.lang.Double.doubleToRawLongBits(data))

    override fun write(addr: Int, data: ByteArray, offset: Int, size: Int) {
        checkBounds(offset, size, data.size) { WasmRuntimeException(it) }
        checkBounds(addr, size, sizeInBytes()) { WasmRuntimeException(it) }
        var a = addr
        var src = offset
        var remaining = size
        while (remaining > 0) {
            val off = a and CHUNK_MASK
            val n = minOf(remaining, CHUNK_SIZE - off)
            val index = a ushr CHUNK_SHIFT
            // ゼロのページにゼロだけを書くなら、確保しない（データセグメントの .bss など）
            if (chunks[index] !== ZERO_CHUNK || !isZero(data, src, n)) {
                writableChunk(index, a, n).put(off, data, src, n)
            }
            a += n
            src += n
            remaining -= n
        }
    }

    override fun zero() = fill(0, 0, sizeInBytes())

    override fun fill(value: Byte, fromIndex: Int, toIndex: Int) {
        var a = fromIndex
        var remaining = toIndex - fromIndex
        checkBounds(a, remaining, sizeInBytes()) { WasmRuntimeException(it) }
        while (remaining > 0) {
            val off = a and CHUNK_MASK
            val n = minOf(remaining, CHUNK_SIZE - off)
            val index = a ushr CHUNK_SHIFT
            if (chunks[index] !== ZERO_CHUNK || value != 0.toByte()) {
                java.util.Arrays.fill(writableChunk(index, a, n).array(), off, off + n, value)
            }
            a += n
            remaining -= n
        }
    }

    override fun copy(dest: Int, src: Int, size: Int) {
        val limit = sizeInBytes()
        checkBounds(dest, size, limit) { WasmRuntimeException(it) }
        checkBounds(src, size, limit) { WasmRuntimeException(it) }
        if (dest > src && dest < src + size) copyBackward(dest, src, size) else copyForward(dest, src, size)
    }

    /** [src] のページの [srcOff] から [n] バイトを、[dest] のページの [destOff] に写す */
    private fun copyChunk(dest: Int, destOff: Int, src: Int, srcOff: Int, n: Int) {
        val destIndex = dest ushr CHUNK_SHIFT
        val srcIndex = src ushr CHUNK_SHIFT
        if (chunks[srcIndex] === ZERO_CHUNK) {
            // ゼロを写す。写し先もゼロのページなら何もしない
            if (chunks[destIndex] === ZERO_CHUNK) return
            java.util.Arrays.fill(writableChunk(destIndex, dest, n).array(), destOff, destOff + n, 0)
            return
        }
        // 写し先を先に書き込めるようにしてから、写し元を引き直す（同じページの ROM なら、写した後のページを使う）
        val destArray = writableChunk(destIndex, dest, n).array()
        val srcChunk = chunks[srcIndex]!!
        if (srcChunk.isReadOnly) {
            // ROM のページ（読み取り専用は array() を使えない）。写し先とは別の配列
            srcChunk.get(srcOff, destArray, destOff, n)
        } else {
            System.arraycopy(srcChunk.array(), srcOff, destArray, destOff, n)
        }
    }

    private fun copyForward(dest0: Int, src0: Int, size0: Int) {
        var dest = dest0
        var src = src0
        var size = size0
        while (size > 0) {
            val destOff = dest and CHUNK_MASK
            val srcOff = src and CHUNK_MASK
            val n = minOf(size, CHUNK_SIZE - maxOf(destOff, srcOff))
            copyChunk(dest, destOff, src, srcOff, n)
            dest += n
            src += n
            size -= n
        }
    }

    private fun copyBackward(dest0: Int, src0: Int, size0: Int) {
        var dest = dest0 + size0
        var src = src0 + size0
        var size = size0
        while (size > 0) {
            val destIn = dest and CHUNK_MASK
            val srcIn = src and CHUNK_MASK
            val destAvail = if (destIn == 0) CHUNK_SIZE else destIn
            val srcAvail = if (srcIn == 0) CHUNK_SIZE else srcIn
            val n = minOf(size, minOf(destAvail, srcAvail))
            dest -= n
            src -= n
            copyChunk(dest, dest and CHUNK_MASK, src, src and CHUNK_MASK, n)
            size -= n
        }
    }

    internal companion object {
        /** 数える単位（4 KiB） */
        const val CHUNK_SIZE: Int = 4096
        private const val CHUNK_SHIFT = 12
        private const val CHUNK_MASK = CHUNK_SIZE - 1
        private const val CHUNKS_PER_PAGE = EndiveMemory.PAGE_SIZE / CHUNK_SIZE

        /** PoC の間のアドレス空間の上限（仮に 1,024 ページ = 64 MiB）。RAM はこれとは別に、書き換えた量で数える */
        const val POC_MAX_PAGES: Int = 1024

        /** すべての [WrittenPagesMemory] で共有する、読み取り専用のゼロのページ。位置を動かす操作は使わない */
        private val ZERO_CHUNK: ByteBuffer = ByteBuffer.allocate(CHUNK_SIZE).asReadOnlyBuffer()

        private fun isZero(data: ByteArray, from: Int, length: Int): Boolean {
            for (i in from until from + length) if (data[i] != 0.toByte()) return false
            return true
        }
    }
}

/** 書き換えた量が RAM の上限を超えた（#370 の PoC）。Endive のトラップとして、プロセスを止める */
internal class RamExhausted(address: Int) :
    WasmRuntimeException("RAM が足りない（書き込みが上限を超えた。アドレス ${address.toUInt()} の 4 KiB）")
