package pro.ikili.kibicomputers.core.kernel.io

import pro.ikili.kibicomputers.core.kernel.obj.KernelObject
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.kernel.wait.WakeSink

/** [ByteStream.read] の結果 */
internal sealed interface ReadResult {
    /** [count] バイト読めた */
    data class Bytes(val count: Int) : ReadResult

    /** 今は読めるバイトがない（書く側はまだ開いている） */
    data object WouldBlock : ReadResult

    /** 書く側が閉じ、残りもない */
    data object Eof : ReadResult
}

/** [ByteStream.write] の結果 */
internal sealed interface WriteResult {
    /** [count] バイト書けた */
    data class Bytes(val count: Int) : WriteResult

    /** 今は空きがない */
    data object WouldBlock : WriteResult

    /** 読む側（または書く側）が閉じた */
    data object Closed : WriteResult
}

/**
 * 容量つきのバイトのバッファ。パイプ（#59）、TTY（#60）、p2 の stream、p3 の `stream<u8>` はこれを使う。
 *
 * 状態（バッファ、閉じた印）はどれも書き出せる値。バッファは最初の書き込みで小さく確保し、[capacity] まで倍に広げ、
 * 読み切って空になったら手放す。状態を変える操作は [WakeSink] を受け取り、そろったかもしれない相手側の [WaitKey] を知らせる。
 * カーネルロックの中で使う。
 */
internal class ByteStream(val capacity: Int) : KernelObject() {
    init {
        require(capacity > 0) { "容量は 1 以上" }
    }

    private var buffer: ByteArray? = null
    private var head = 0

    /** 読めるバイトの数 */
    var available: Int = 0
        private set

    /** 読む側が閉じた */
    var readClosed: Boolean = false
        private set

    /** 書く側が閉じた */
    var writeClosed: Boolean = false
        private set

    /** 確保しているバッファの大きさ（テスト用） */
    internal val allocatedBytes: Int get() = buffer?.size ?: 0

    override fun canWait(condition: WaitCondition): Boolean = condition == WaitCondition.READABLE || condition == WaitCondition.WRITABLE

    override fun isReady(condition: WaitCondition): Boolean = when (condition) {
        WaitCondition.READABLE -> available > 0 || writeClosed
        WaitCondition.WRITABLE -> available < capacity || readClosed
    }

    /** 最大 [len] バイトを [dst] の [off] に読む。読めたら `(id, WRITABLE)` を知らせる */
    fun read(dst: ByteArray, off: Int, len: Int, sink: WakeSink): ReadResult {
        require(off >= 0 && len >= 0 && off + len <= dst.size) { "dst の範囲外" }
        if (available == 0) return if (writeClosed) ReadResult.Eof else ReadResult.WouldBlock
        if (len == 0) return ReadResult.Bytes(0)
        val n = minOf(len, available)
        System.arraycopy(buffer!!, head, dst, off, n)
        head += n
        available -= n
        if (available == 0) {
            buffer = null
            head = 0
        }
        sink.wake(WaitKey(id, WaitCondition.WRITABLE))
        return ReadResult.Bytes(n)
    }

    /** [src] の [off] から最大 [len] バイトを書く。書けたら `(id, READABLE)` を知らせる */
    fun write(src: ByteArray, off: Int, len: Int, sink: WakeSink): WriteResult {
        require(off >= 0 && len >= 0 && off + len <= src.size) { "src の範囲外" }
        if (readClosed || writeClosed) return WriteResult.Closed
        val space = capacity - available
        if (space == 0) return WriteResult.WouldBlock
        if (len == 0) return WriteResult.Bytes(0)
        val n = minOf(len, space)
        ensureRoom(n)
        System.arraycopy(src, off, buffer!!, head + available, n)
        available += n
        sink.wake(WaitKey(id, WaitCondition.READABLE))
        return WriteResult.Bytes(n)
    }

    /** 読む側を閉じる。書く側に `(id, WRITABLE)` を知らせる */
    fun closeRead(sink: WakeSink) {
        if (readClosed) return
        readClosed = true
        buffer = null
        head = 0
        available = 0
        sink.wake(WaitKey(id, WaitCondition.WRITABLE))
    }

    /** 書く側を閉じる。読む側に `(id, READABLE)` を知らせる */
    fun closeWrite(sink: WakeSink) {
        if (writeClosed) return
        writeClosed = true
        sink.wake(WaitKey(id, WaitCondition.READABLE))
    }

    override fun onLastClose(sink: WakeSink) {
        readClosed = true
        writeClosed = true
        buffer = null
        head = 0
        available = 0
    }

    /** あと [n] バイト書ける場所を作る（詰める、または倍に広げる。[capacity] を超えない） */
    private fun ensureRoom(n: Int) {
        val needed = available + n
        val current = buffer
        if (current != null && head + needed <= current.size) return
        if (current != null && needed <= current.size) {
            System.arraycopy(current, head, current, 0, available)
            head = 0
            return
        }
        var size = (current?.size ?: INITIAL_BUFFER).coerceAtMost(capacity)
        while (size < needed) size = (size.toLong() * 2).coerceAtMost(capacity.toLong()).toInt()
        val next = ByteArray(size)
        if (current != null) System.arraycopy(current, head, next, 0, available)
        buffer = next
        head = 0
    }

    private companion object {
        /** 最初に確保する大きさ（仮の値） */
        const val INITIAL_BUFFER = 256
    }
}
