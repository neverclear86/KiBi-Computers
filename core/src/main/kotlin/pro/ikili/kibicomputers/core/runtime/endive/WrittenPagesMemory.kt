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
 * 初めて書き込んだ 4 KiB の区画の数で RAM を数える、共有しない線形メモリ（#407。#370 で決定）。
 *
 * [LazyMemory] と同じく、区画の表を引いて読み書きし、読み取り専用の区画への書き込みの例外で差し替える
 * （読み書きの速い道に分岐を足さない）。違いは、区画が 4 KiB であることと、区画が3種類あること。
 *
 * - ゼロの区画: すべてのメモリで共有する読み取り専用の 4 KiB（[ZERO_CHUNK]）。確保しない。
 * - ROM の区画: インスタンスを作るとき（能動データセグメント）に書いた中身。読み取り専用で、**数えない**。
 * - 書き換えた区画: 初めて書いたときに確保し（ROM なら中身を写す）、[budget] で 4 KiB を数える。
 *   断られたら [RamExhausted] でトラップにする。
 *
 * 数えた分は、インスタンスを閉じるまで返さない（解放しても減らない。使った量の最高値になる）。閉じたら [releaseAll] でまとめて返す。
 *
 * `memory.grow` は、増やした大きさを RAM として数えず、アドレス空間の上限（[maximumPages]）だけを見る。
 * ただし、増やした所の最初と最後の 4 KiB だけは grow のときに数える（dlmalloc などのアロケーターが増やした直後に書く所。#370 の PoC）。
 * 数えられなければ grow を -1 にして [onGrowRefused] を呼ぶ（アロケーターは NULL を返して優しく失敗できる）。
 *
 * 区画の表は `pages()` に合わせて伸ばす（上限の分を先に作らない）。共有メモリ（threads）には使わない。
 */
internal class WrittenPagesMemory(
    private val limits: MemoryLimits,
    private val budget: WasmTableBudget?,
    private val onGrowRefused: Runnable? = null,
) : EndiveMemory {
    init {
        require(!limits.shared()) { "共有メモリには使わない" }
    }

    private val maxPages: Int = minOf(limits.maximumPages(), EndiveMemory.RUNTIME_MAX_PAGES)

    /** 4 KiB の区画の表。範囲内でまだ書いていない区画は [ZERO_CHUNK]、範囲外は表の外 */
    @Volatile
    private var chunks: Array<ByteBuffer?> = Array(limits.initialPages() * CHUNKS_PER_PAGE) { ZERO_CHUNK }

    /** 今の大きさ（64 KiB のページ数） */
    @Volatile
    private var nPages: Int = limits.initialPages()

    /** 書き換えた（数えた）4 KiB の区画の数 */
    @Volatile
    var writtenChunks: Int = 0
        private set

    /** RAM として数えたバイト数（書き換えた区画の数 × 4 KiB） */
    val countedBytes: Long get() = writtenChunks.toLong() * CHUNK_SIZE

    /** ROM の区画（データセグメントで書いた 4 KiB）の数 */
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
        // 増やした所の最初と最後の 4 KiB は、先に数えて確保する（アロケーターは増やした直後にそこへ管理の情報を書く）。
        // 数えられなければ増やさない（-1。アロケーターは NULL を返せる。トラップより優しい失敗）
        val headChunk = prev * CHUNKS_PER_PAGE
        val tailChunk = next * CHUNKS_PER_PAGE - 1
        val edges = if (headChunk == tailChunk) 1 else 2
        if (!released && budget != null && !budget.tryReserveBytes(edges.toLong() * CHUNK_SIZE)) {
            onGrowRefused?.run()
            return -1
        }
        val old = chunks
        val fresh = arrayOfNulls<ByteBuffer>(next * CHUNKS_PER_PAGE)
        System.arraycopy(old, 0, fresh, 0, old.size)
        java.util.Arrays.fill(fresh, old.size, fresh.size, ZERO_CHUNK)
        fresh[headChunk] = newChunk()
        fresh[tailChunk] = newChunk()
        if (!released) writtenChunks += edges
        chunks = fresh
        nPages = next
        return prev
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
        // 書いた区画を読み取り専用にする（読み取り専用の複製はバイト順を引き継がないので、付け直す）
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

    /** [index] の区画を書き込めるようにして返す。範囲外なら範囲外の例外 */
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
            // インスタンスを作っている途中（データセグメント）: 数えない ROM の区画に書く
            return rom.getOrPut(index) { newChunk() }
        }
        val counted = !released
        if (counted && budget != null && !budget.tryReserveBytes(CHUNK_SIZE.toLong())) {
            throw RamExhausted(index shl CHUNK_SHIFT)
        }
        val fresh = newChunk()
        if (chunk !== ZERO_CHUNK) chunk.get(0, fresh.array(), 0, CHUNK_SIZE)
        table[index] = fresh
        if (counted) writtenChunks++
        return fresh
    }

    /**
     * [address] から [length] バイト（どちらも符号なし 32 ビット）にかかる区画のうち、まだ数えていないもの（ゼロ・ROM の区画）を、
     * 今のうちに書き換えた区画として数えて確保する（`kibi:memory` の `commit`。#408）。ROM の区画は中身を写す。
     *
     * 数える区画がなければ `true`（[keepBytes] を見ない）。数えた後の残りが [keepBytes]（符号なし 64 ビット）未満になるなら、
     * 何も数えず・確保せずに `false`（区画は読み取り専用のまま。後で書けば普通に数える）。
     * 範囲が外なら範囲外の例外（[length] が 0 なら、[address] が大きさ以下で範囲内）。
     * 閉じた後と、[budget] がないときは、数えずに確保だけして `true`。
     * 初めて書いたときの数え方（`commit(index)`）と同じロックで行う
     */
    @Synchronized
    fun commitRange(address: Int, length: Int, keepBytes: Long): Boolean {
        val start = address.toUInt().toLong()
        val len = length.toUInt().toLong()
        val size = EndiveMemory.PAGE_SIZE.toLong() * nPages
        if (start + len > size) {
            throw WasmRuntimeException("out of bounds memory access: attempted to access address: ${address.toUInt()} but limit is: $size and size: ${length.toUInt()}")
        }
        if (len == 0L) return true
        val first = (start ushr CHUNK_SHIFT).toInt()
        val last = ((start + len - 1) ushr CHUNK_SHIFT).toInt()
        val table = chunks
        var fresh = 0
        for (i in first..last) if (table[i]!!.isReadOnly) fresh++
        if (fresh == 0) return true
        val counted = !released
        if (counted && budget != null && !budget.tryReserveBytes(fresh.toLong() * CHUNK_SIZE, keepBytes)) return false
        for (i in first..last) {
            val chunk = table[i]!!
            if (!chunk.isReadOnly) continue
            val copy = newChunk()
            if (chunk !== ZERO_CHUNK) chunk.get(0, copy.array(), 0, CHUNK_SIZE)
            table[i] = copy
        }
        if (counted) writtenChunks += fresh
        return true
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

    // --- 読み込み（ゼロ・ROM の区画もそのまま読める） ---

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

    // --- 書き込み（読み取り専用の区画なら、確保してからやり直す） ---

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
            // ゼロの区画にゼロだけを書くなら、確保しない（データセグメントの .bss など）。
            // ただしインスタンスを作っている途中で、前のデータセグメントが書いた ROM の区画なら書く
            // （表はまだ ZERO_CHUNK のまま。重なるセグメントは後のものが勝つ。#413）
            if (chunks[index] !== ZERO_CHUNK || !isZero(data, src, n) || romWriting?.containsKey(index) == true) {
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

    /** [src] の区画の [srcOff] から [n] バイトを、[dest] の区画の [destOff] に写す */
    private fun copyChunk(dest: Int, destOff: Int, src: Int, srcOff: Int, n: Int) {
        val destIndex = dest ushr CHUNK_SHIFT
        val srcIndex = src ushr CHUNK_SHIFT
        if (chunks[srcIndex] === ZERO_CHUNK) {
            // ゼロを写す。写し先もゼロの区画なら何もしない
            if (chunks[destIndex] === ZERO_CHUNK) return
            java.util.Arrays.fill(writableChunk(destIndex, dest, n).array(), destOff, destOff + n, 0)
            return
        }
        // 写し先を先に書き込めるようにしてから、写し元を引き直す（同じ区画の ROM なら、写した後の区画を使う）
        val destArray = writableChunk(destIndex, dest, n).array()
        val srcChunk = chunks[srcIndex]!!
        if (srcChunk.isReadOnly) {
            // ROM の区画（読み取り専用は array() を使えない）。写し先とは別の配列
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

        /** すべての [WrittenPagesMemory] で共有する、読み取り専用のゼロの区画。位置を動かす操作は使わない */
        private val ZERO_CHUNK: ByteBuffer = ByteBuffer.allocate(CHUNK_SIZE).asReadOnlyBuffer()

        private fun newChunk(): ByteBuffer = ByteBuffer.allocate(CHUNK_SIZE).order(ByteOrder.LITTLE_ENDIAN)

        private fun isZero(data: ByteArray, from: Int, length: Int): Boolean {
            for (i in from until from + length) if (data[i] != 0.toByte()) return false
            return true
        }
    }
}

/**
 * 書き換えた区画が RAM の上限を超えた（#407）。Endive のトラップとしてプロセスを止める。
 * ランタイムの外へは [pro.ikili.kibicomputers.core.runtime.WasmTrapKind.OUT_OF_MEMORY] のトラップにして出す
 */
internal class RamExhausted(address: Int) :
    WasmRuntimeException("out of memory: RAM の上限を超えて書き込んだ（アドレス ${address.toUInt()} の 4 KiB の区画）")
