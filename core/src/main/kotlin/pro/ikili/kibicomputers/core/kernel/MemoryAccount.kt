package pro.ikili.kibicomputers.core.kernel

import pro.ikili.kibicomputers.core.runtime.WasmMemory
import pro.ikili.kibicomputers.core.runtime.WasmMemoryBudget
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * マシン全体（RAM）の使用量（#39・#53）。実行時だけのもので、保存しない。
 *
 * 数えるのは、プロセスの線形メモリ（ページ単位）と、`/tmp` などメモリ上のファイルシステムの中身（バイト単位。[tryReserveBytes]）。
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

    /** 今使っているバイト数（線形メモリとファイルシステムの合計） */
    val usedBytes: Long get() = usedBytesCounter.get()

    /** 線形メモリとして今使っているページ数（ファイルシステムの分は入らない） */
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

    /** [bytes] バイト（0 以上）を確保できれば数えて `true`（メモリ上のファイルシステムの中身など） */
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

/** 1 プロセスの線形メモリの使用量（#39）。プロセスのすべてのインスタンスで共有する。 */
internal class ProcessMemory(private val machine: MemoryAccount, val limitPages: Int) : WasmMemoryBudget {
    private val used = AtomicInteger()

    /** 今使っているページ数 */
    val usedPages: Int get() = used.get()

    override fun tryReserve(pages: Int): Boolean {
        require(pages > 0) { "ページ数は正: $pages" }
        if (!tryAdd(used, pages, limitPages)) return false
        if (!machine.tryReserve(pages)) {
            used.addAndGet(-pages)
            return false
        }
        return true
    }

    override fun release(pages: Int) {
        require(pages > 0) { "ページ数は正: $pages" }
        val after = used.addAndGet(-pages)
        check(after >= 0) { "プロセスのメモリの使用量が負になった: $after" }
        machine.release(pages)
    }
}

/** [counter] に [delta] を足して [limit] 以下に収まれば足して `true`。収まらなければ何もせず `false` */
private fun tryAdd(counter: AtomicInteger, delta: Int, limit: Int): Boolean {
    while (true) {
        val current = counter.get()
        if (current.toLong() + delta > limit) return false
        if (counter.compareAndSet(current, current + delta)) return true
    }
}
