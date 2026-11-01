package pro.ikili.kibicomputers.core.kernel

import pro.ikili.kibicomputers.core.runtime.WasmMemory
import pro.ikili.kibicomputers.core.runtime.WasmMemoryBudget
import pro.ikili.kibicomputers.core.runtime.WasmTableBudget
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * マシン全体（RAM）の使用量（#39・#53）。実行時だけのもので、保存しない。
 *
 * 数えるのは、プロセスの線形メモリ（ページ単位）と、テーブル（#361）や `/tmp` などメモリ上のファイルシステムの中身（バイト単位。[tryReserveBytes]）。
 * 合計はバイトで数え、上限は RAM のページ数 × 64 KiB。
 *
 * `memory.grow` はプロセスのスレッドから、カーネルロックを持たずに呼ばれるので、ロックではなく CAS で数える。
 */
internal class MemoryAccount(
    /** RAM のページ数 */
    val limitPages: Int,
) {
    /** RAM の大きさ（バイト） */
    val limitBytes: Long = limitPages.toLong() * WasmMemory.PAGE_SIZE

    private val usedBytesCounter = AtomicLong()
    private val linearPages = AtomicInteger()

    /** 今使っているバイト数（線形メモリ・テーブル・ファイルシステムの合計） */
    val usedBytes: Long get() = usedBytesCounter.get()

    /** 線形メモリとして今使っているページ数（テーブルとファイルシステムの分は入らない） */
    val usedPages: Int get() = linearPages.get()

    /** 線形メモリを [pages] ページ確保できれば数えて `true` */
    fun tryReserve(pages: Int): Boolean {
        if (!tryReserveBytes(pages.toLong() * WasmMemory.PAGE_SIZE)) return false
        linearPages.addAndGet(pages)
        return true
    }

    fun release(pages: Int) {
        val after = linearPages.addAndGet(-pages)
        check(after >= 0) { "RAM の線形メモリの使用量が負になった: $after" }
        releaseBytes(pages.toLong() * WasmMemory.PAGE_SIZE)
    }

    /** [bytes] バイト（0 以上）を確保できれば数えて `true`（テーブル、メモリ上のファイルシステムの中身など） */
    fun tryReserveBytes(bytes: Long): Boolean {
        require(bytes >= 0) { "バイト数は 0 以上: $bytes" }
        while (true) {
            val current = usedBytesCounter.get()
            if (bytes > limitBytes - current) return false
            if (usedBytesCounter.compareAndSet(current, current + bytes)) return true
        }
    }

    /** [tryReserveBytes] で確保した [bytes] バイトを返す */
    fun releaseBytes(bytes: Long) {
        require(bytes >= 0) { "バイト数は 0 以上: $bytes" }
        val after = usedBytesCounter.addAndGet(-bytes)
        check(after >= 0) { "RAM の使用量が負になった: $after" }
    }

    /** 1 プロセスの使用量。上限 [limitPages] と、マシン全体の両方に収まるときだけ確保する */
    fun process(limitPages: Int): ProcessMemory = ProcessMemory(this, limitPages)
}

/**
 * 1 プロセスのメモリの使用量（#39・#361）。プロセスのすべてのインスタンスで共有する。
 *
 * 線形メモリ（ページ）とテーブル（バイト。要素数 × 要素1つの大きさ）を合わせたバイト数を、
 * プロセスの上限 [limitPages] × 64 KiB と、マシン全体の RAM（[MemoryAccount]）の両方に収まるときだけ確保する。
 */
internal class ProcessMemory(private val machine: MemoryAccount, val limitPages: Int) : WasmMemoryBudget, WasmTableBudget {
    /** プロセスの上限（バイト） */
    private val limitBytes: Long = limitPages.toLong() * WasmMemory.PAGE_SIZE

    private val usedBytesCounter = AtomicLong()
    private val used = AtomicInteger()

    /** 線形メモリとして今使っているページ数（テーブルの分は入らない） */
    val usedPages: Int get() = used.get()

    /** 今使っているバイト数（線形メモリとテーブルの合計） */
    val usedBytes: Long get() = usedBytesCounter.get()

    override fun tryReserve(pages: Int): Boolean {
        require(pages > 0) { "ページ数は正: $pages" }
        val bytes = pages.toLong() * WasmMemory.PAGE_SIZE
        if (!tryAdd(usedBytesCounter, bytes, limitBytes)) return false
        if (!machine.tryReserve(pages)) {
            usedBytesCounter.addAndGet(-bytes)
            return false
        }
        used.addAndGet(pages)
        return true
    }

    override fun release(pages: Int) {
        require(pages > 0) { "ページ数は正: $pages" }
        val after = used.addAndGet(-pages)
        check(after >= 0) { "プロセスのメモリの使用量が負になった: $after" }
        subtract(pages.toLong() * WasmMemory.PAGE_SIZE)
        machine.release(pages)
    }

    override fun tryReserveBytes(bytes: Long): Boolean {
        require(bytes > 0) { "バイト数は正: $bytes" }
        if (!tryAdd(usedBytesCounter, bytes, limitBytes)) return false
        if (!machine.tryReserveBytes(bytes)) {
            usedBytesCounter.addAndGet(-bytes)
            return false
        }
        return true
    }

    override fun releaseBytes(bytes: Long) {
        require(bytes > 0) { "バイト数は正: $bytes" }
        subtract(bytes)
        machine.releaseBytes(bytes)
    }

    private fun subtract(bytes: Long) {
        val after = usedBytesCounter.addAndGet(-bytes)
        check(after >= 0) { "プロセスのメモリの使用量が負になった: $after バイト" }
    }
}

/** [counter] に [delta] を足して [limit] 以下に収まれば足して `true`。収まらなければ何もせず `false` */
private fun tryAdd(counter: AtomicLong, delta: Long, limit: Long): Boolean {
    while (true) {
        val current = counter.get()
        if (delta > limit - current) return false
        if (counter.compareAndSet(current, current + delta)) return true
    }
}
