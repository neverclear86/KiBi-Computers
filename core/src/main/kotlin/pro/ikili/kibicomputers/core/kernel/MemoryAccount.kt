package pro.ikili.kibicomputers.core.kernel

import pro.ikili.kibicomputers.core.runtime.WasmMemoryBudget
import java.util.concurrent.atomic.AtomicInteger

/**
 * マシン全体（RAM）の線形メモリの使用量（#39。ページ数）。実行時だけのもので、保存しない。
 *
 * `memory.grow` はプロセスのスレッドから、カーネルロックを持たずに呼ばれるので、ロックではなく CAS で数える。
 */
internal class MemoryAccount(
    /** RAM のページ数 */
    val limitPages: Int,
) {
    private val used = AtomicInteger()

    /** 今使っているページ数 */
    val usedPages: Int get() = used.get()

    /** [pages] ページを確保できれば数えて `true` */
    fun tryReserve(pages: Int): Boolean = tryAdd(used, pages, limitPages)

    fun release(pages: Int) {
        val after = used.addAndGet(-pages)
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
