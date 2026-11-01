package pro.ikili.kibicomputers.core.runtime.endive

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
 * 書き込まれたページだけ JVM のメモリを確保する、共有しない線形メモリ（#334）。
 *
 * Endive の `ByteBufferMemory` は、インスタンスを作るときと `grow` のときにページ（64 KiB）をすべて確保する。
 * wasi-libc のプログラムは最初のメモリにスタックとヒープの分を宣言するので、使っていないページでもヒープを使う。
 * ここでは、範囲内でまだ書き込まれていないページは、すべてのメモリで共有する読み取り専用のゼロのページ（[ZERO_PAGE]）を指しておき、
 * 初めて書き込んだときに、そのページだけを確保して差し替える。
 *
 * - 読み書きの速い道は `ByteBufferMemory` と同じ（ページの表を引いて、そのページを読み書きする）。
 *   ゼロのページへの書き込みは [ReadOnlyBufferException] になるので、そこでページを確保してやり直す（ページごとに1回だけ）。
 * - 範囲外（`pages()` より先）のページは表が `null` なので、`NullPointerException` か配列の範囲外になり、
 *   Endive と同じメッセージ（`out of bounds memory access`）の [WasmRuntimeException] にする。
 * - 確保したページは、インスタンスが閉じるまで返さない（`memory.discard` はまだない）。
 * - 共有メモリ（threads）には使わない。共有メモリは今までどおり `ByteBufferMemory` で作る。
 *
 * RAM の勘定（#39）は、確保した量ではなく宣言した量（`pages()`）で数える。確保した量は [committedPages] で見られる。
 */
internal class LazyMemory(private val limits: MemoryLimits) : EndiveMemory {
    init {
        require(!limits.shared()) { "共有メモリには使わない" }
    }

    private val maxPages: Int = minOf(limits.maximumPages(), EndiveMemory.RUNTIME_MAX_PAGES)

    /** ページの表。範囲内でまだ書き込まれていないページは [ZERO_PAGE]、範囲外は `null` */
    private val pages: Array<ByteBuffer?> = arrayOfNulls(maxPages)

    /** 今の大きさ（ページ数） */
    @Volatile
    private var nPages: Int = limits.initialPages()

    /** JVM のメモリを確保したページ数 */
    @Volatile
    var committedPages: Int = 0
        private set

    private var dataSegments: Array<DataSegment>? = null

    init {
        for (i in 0 until nPages) pages[i] = ZERO_PAGE
    }

    override fun pages(): Int = nPages

    @Synchronized
    override fun grow(size: Int): Int {
        val prev = nPages
        val next = prev + size
        if (next > maximumPages() || next < prev) return -1
        for (i in prev until next) pages[i] = ZERO_PAGE
        nPages = next
        return prev
    }

    override fun initialPages(): Int = limits.initialPages()

    override fun maximumPages(): Int = maxPages

    override fun shared(): Boolean = false

    // 共有しないメモリでは、wait・notify は使われない（Endive の検証が shared でないメモリへの atomic.wait を断る）

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
    }

    override fun initPassiveSegment(segmentId: Int, dest: Int, offset: Int, size: Int) {
        write(dest, dataSegments!![segmentId].bytes(), offset, size)
    }

    override fun drop(segment: Int) {
        dataSegments!![segment] = PassiveDataSegment.EMPTY
    }

    private fun sizeInBytes(): Int = EndiveMemory.PAGE_SIZE * nPages

    /** [index] のページを書き込めるようにして返す。範囲外なら範囲外の例外 */
    private fun writablePage(index: Int, addr: Int, size: Int): ByteBuffer {
        val page = if (index in 0 until maxPages) pages[index] else null
        if (page == null) throw outOfBounds(addr, size)
        if (page !== ZERO_PAGE) return page
        return commit(index)
    }

    @Synchronized
    private fun commit(index: Int): ByteBuffer {
        val page = pages[index] ?: throw outOfBounds(index shl PAGE_SHIFT, 1)
        if (page !== ZERO_PAGE) return page
        val fresh = ByteBuffer.allocate(EndiveMemory.PAGE_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        pages[index] = fresh
        committedPages++
        return fresh
    }

    private fun outOfBounds(addr: Int, size: Int): WasmRuntimeException =
        WasmRuntimeException("out of bounds memory access: attempted to access address: $addr but limit is: ${sizeInBytes()} and size: $size")

    /** 読み書きの例外を、範囲外の例外に変える（ゼロのページへの書き込みは呼ぶ側で扱う） */
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

    // --- 読み込み（ゼロのページもそのまま読める） ---

    override fun read(addr: Int): Byte = try {
        pages[addr ushr PAGE_SHIFT]!!.get(addr and PAGE_MASK)
    } catch (e: RuntimeException) {
        throw translate(e, addr, 1)
    }

    override fun readBytes(addr: Int, len: Int): ByteArray {
        checkBounds(addr, len, sizeInBytes()) { WasmRuntimeException(it) }
        val result = ByteArray(len)
        var a = addr
        var dst = 0
        var remaining = len
        while (remaining > 0) {
            val off = a and PAGE_MASK
            val chunk = minOf(remaining, EndiveMemory.PAGE_SIZE - off)
            val page = pages[a ushr PAGE_SHIFT]!!
            if (page !== ZERO_PAGE) page.get(off, result, dst, chunk)
            a += chunk
            dst += chunk
            remaining -= chunk
        }
        return result
    }

    override fun readInt(addr: Int): Int {
        val off = addr and PAGE_MASK
        if (off + 4 > EndiveMemory.PAGE_SIZE) {
            return (read(addr).toInt() and 0xff) or
                ((read(addr + 1).toInt() and 0xff) shl 8) or
                ((read(addr + 2).toInt() and 0xff) shl 16) or
                ((read(addr + 3).toInt() and 0xff) shl 24)
        }
        return try {
            pages[addr ushr PAGE_SHIFT]!!.getInt(off)
        } catch (e: RuntimeException) {
            throw translate(e, addr, 4)
        }
    }

    override fun readLong(addr: Int): Long {
        val off = addr and PAGE_MASK
        if (off + 8 > EndiveMemory.PAGE_SIZE) {
            return (readInt(addr).toLong() and 0xffffffffL) or (readInt(addr + 4).toLong() shl 32)
        }
        return try {
            pages[addr ushr PAGE_SHIFT]!!.getLong(off)
        } catch (e: RuntimeException) {
            throw translate(e, addr, 8)
        }
    }

    override fun readShort(addr: Int): Short {
        val off = addr and PAGE_MASK
        if (off + 2 > EndiveMemory.PAGE_SIZE) {
            return ((read(addr).toInt() and 0xff) or ((read(addr + 1).toInt() and 0xff) shl 8)).toShort()
        }
        return try {
            pages[addr ushr PAGE_SHIFT]!!.getShort(off)
        } catch (e: RuntimeException) {
            throw translate(e, addr, 2)
        }
    }

    override fun readU16(addr: Int): Long = readShort(addr).toLong() and 0xffffL

    override fun readF32(addr: Int): Long = readInt(addr).toLong()

    override fun readFloat(addr: Int): Float = java.lang.Float.intBitsToFloat(readInt(addr))

    override fun readF64(addr: Int): Long = readLong(addr)

    override fun readDouble(addr: Int): Double = java.lang.Double.longBitsToDouble(readLong(addr))

    // --- 書き込み（ゼロのページなら確保してからやり直す） ---

    override fun writeByte(addr: Int, data: Byte) {
        try {
            pages[addr ushr PAGE_SHIFT]!!.put(addr and PAGE_MASK, data)
        } catch (e: ReadOnlyBufferException) {
            writablePage(addr ushr PAGE_SHIFT, addr, 1).put(addr and PAGE_MASK, data)
        } catch (e: RuntimeException) {
            throw translate(e, addr, 1)
        }
    }

    override fun writeI32(addr: Int, data: Int) {
        val off = addr and PAGE_MASK
        if (off + 4 > EndiveMemory.PAGE_SIZE) {
            checkBounds(addr, 4, sizeInBytes()) { WasmRuntimeException(it) }
            for (i in 0 until 4) writeByte(addr + i, (data ushr (8 * i)).toByte())
            return
        }
        try {
            pages[addr ushr PAGE_SHIFT]!!.putInt(off, data)
        } catch (e: ReadOnlyBufferException) {
            writablePage(addr ushr PAGE_SHIFT, addr, 4).putInt(off, data)
        } catch (e: RuntimeException) {
            throw translate(e, addr, 4)
        }
    }

    override fun writeLong(addr: Int, data: Long) {
        val off = addr and PAGE_MASK
        if (off + 8 > EndiveMemory.PAGE_SIZE) {
            checkBounds(addr, 8, sizeInBytes()) { WasmRuntimeException(it) }
            for (i in 0 until 8) writeByte(addr + i, (data ushr (8 * i)).toByte())
            return
        }
        try {
            pages[addr ushr PAGE_SHIFT]!!.putLong(off, data)
        } catch (e: ReadOnlyBufferException) {
            writablePage(addr ushr PAGE_SHIFT, addr, 8).putLong(off, data)
        } catch (e: RuntimeException) {
            throw translate(e, addr, 8)
        }
    }

    override fun writeShort(addr: Int, data: Short) {
        val off = addr and PAGE_MASK
        if (off + 2 > EndiveMemory.PAGE_SIZE) {
            checkBounds(addr, 2, sizeInBytes()) { WasmRuntimeException(it) }
            writeByte(addr, data.toByte())
            writeByte(addr + 1, (data.toInt() ushr 8).toByte())
            return
        }
        try {
            pages[addr ushr PAGE_SHIFT]!!.putShort(off, data)
        } catch (e: ReadOnlyBufferException) {
            writablePage(addr ushr PAGE_SHIFT, addr, 2).putShort(off, data)
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
            val off = a and PAGE_MASK
            val chunk = minOf(remaining, EndiveMemory.PAGE_SIZE - off)
            val index = a ushr PAGE_SHIFT
            val page = pages[index]!!
            // ゼロのページにゼロだけを書くなら、確保しない（データセグメントの .bss など）
            if (page !== ZERO_PAGE || !isZero(data, src, chunk)) {
                writablePage(index, a, chunk).put(off, data, src, chunk)
            }
            a += chunk
            src += chunk
            remaining -= chunk
        }
    }

    override fun zero() = fill(0, 0, sizeInBytes())

    override fun fill(value: Byte, fromIndex: Int, toIndex: Int) {
        var a = fromIndex
        var remaining = toIndex - fromIndex
        checkBounds(a, remaining, sizeInBytes()) { WasmRuntimeException(it) }
        while (remaining > 0) {
            val off = a and PAGE_MASK
            val chunk = minOf(remaining, EndiveMemory.PAGE_SIZE - off)
            val index = a ushr PAGE_SHIFT
            val page = pages[index]!!
            if (page !== ZERO_PAGE || value != 0.toByte()) {
                java.util.Arrays.fill(writablePage(index, a, chunk).array(), off, off + chunk, value)
            }
            a += chunk
            remaining -= chunk
        }
    }

    override fun copy(dest: Int, src: Int, size: Int) {
        val limit = sizeInBytes()
        checkBounds(dest, size, limit) { WasmRuntimeException(it) }
        checkBounds(src, size, limit) { WasmRuntimeException(it) }
        if (dest > src && dest < src + size) copyBackward(dest, src, size) else copyForward(dest, src, size)
    }

    /** [src] のページの [srcOff] から [chunk] バイトを、[dest] のページの [destOff] に写す */
    private fun copyChunk(dest: Int, destOff: Int, src: Int, srcOff: Int, chunk: Int) {
        val srcPage = pages[src ushr PAGE_SHIFT]!!
        val destIndex = dest ushr PAGE_SHIFT
        if (srcPage === ZERO_PAGE) {
            // ゼロを写す。写し先もゼロのページなら何もしない
            val destPage = pages[destIndex]!!
            if (destPage !== ZERO_PAGE) java.util.Arrays.fill(destPage.array(), destOff, destOff + chunk, 0)
            return
        }
        // 写し元を先に引いてから、写し先を確保する（同じページのときも、確保済みなので同じものになる）
        System.arraycopy(srcPage.array(), srcOff, writablePage(destIndex, dest, chunk).array(), destOff, chunk)
    }

    private fun copyForward(dest0: Int, src0: Int, size0: Int) {
        var dest = dest0
        var src = src0
        var size = size0
        while (size > 0) {
            val destOff = dest and PAGE_MASK
            val srcOff = src and PAGE_MASK
            val chunk = minOf(size, EndiveMemory.PAGE_SIZE - maxOf(destOff, srcOff))
            copyChunk(dest, destOff, src, srcOff, chunk)
            dest += chunk
            src += chunk
            size -= chunk
        }
    }

    private fun copyBackward(dest0: Int, src0: Int, size0: Int) {
        var dest = dest0 + size0
        var src = src0 + size0
        var size = size0
        while (size > 0) {
            val destIn = dest and PAGE_MASK
            val srcIn = src and PAGE_MASK
            val destAvail = if (destIn == 0) EndiveMemory.PAGE_SIZE else destIn
            val srcAvail = if (srcIn == 0) EndiveMemory.PAGE_SIZE else srcIn
            val chunk = minOf(size, minOf(destAvail, srcAvail))
            dest -= chunk
            src -= chunk
            copyChunk(dest, dest and PAGE_MASK, src, src and PAGE_MASK, chunk)
            size -= chunk
        }
    }

    private companion object {
        const val PAGE_SHIFT = 16
        const val PAGE_MASK = EndiveMemory.PAGE_SIZE - 1

        /** すべての [LazyMemory] で共有する、読み取り専用のゼロのページ。位置を動かす操作は使わない */
        val ZERO_PAGE: ByteBuffer = ByteBuffer.allocate(EndiveMemory.PAGE_SIZE).asReadOnlyBuffer()

        fun isZero(data: ByteArray, from: Int, length: Int): Boolean {
            for (i in from until from + length) if (data[i] != 0.toByte()) return false
            return true
        }
    }
}
