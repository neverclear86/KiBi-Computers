package pro.ikili.kibicomputers.core.kernel

import pro.ikili.kibicomputers.core.runtime.WasmMemory
import pro.ikili.kibicomputers.core.runtime.WasmMemoryBudget
import pro.ikili.kibicomputers.core.runtime.WasmTableBudget
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * RAM の使用量（#39・#53・#362・#407）。実行時だけのもので、保存しない。
 *
 * 数えるのは、プロセスの線形メモリ（カーネルのプロセスは初めて書き込んだ 4 KiB の区画。バイト単位。#407）と、テーブル（#361）や `/tmp` などメモリ上のファイルシステムの中身（バイト単位。[tryReserveBytes]）。
 * 合計はバイトで数え、上限は [limitBytes]。
 *
 * マシンごとの勘定（上限は RAM のページ数 × 64 KiB）は、親としてサーバー全体の勘定（[MachineScheduler] に1つ。#362）を持つ。
 * 確保は自分と親の両方に収まるときだけ数え、親で断られたら自分の分を戻す。返すときは両方から引く。
 *
 * `memory.grow` はプロセスのスレッドから、カーネルロックを持たずに呼ばれるので、ロックではなく CAS で数える。
 */
internal class MemoryAccount(
    /** 上限（バイト。0 以上） */
    val limitBytes: Long,
    /** 親の勘定（サーバー全体）。`null` なら親を持たない */
    private val parent: MemoryAccount? = null,
) {
    /** マシンの RAM の勘定（上限は [limitPages] ページ） */
    constructor(limitPages: Int, parent: MemoryAccount? = null) : this(limitPages.toLong() * WasmMemory.PAGE_SIZE, parent)

    init {
        require(limitBytes >= 0) { "上限は 0 以上: $limitBytes" }
    }

    /** 上限のページ数（32 ビットのメモリの上限で切る） */
    val limitPages: Int = minOf(limitBytes / WasmMemory.PAGE_SIZE, WasmMemory.MAX_PAGES.toLong()).toInt()

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

    /** [bytes] バイト（0 以上）を、自分と親の両方に収まれば数えて `true`（テーブル、メモリ上のファイルシステムの中身など） */
    fun tryReserveBytes(bytes: Long): Boolean = tryReserveBytes(bytes, 0)

    /**
     * [tryReserveBytes] と同じだが、自分の勘定では、数えた後の残りが [keepBytes]（符号なし 64 ビット。負の値は 2^63 以上）
     * 以上になるときだけ数える（`kibi:computer/memory` の `commit`。#408）。親（サーバー全体）の勘定には [keepBytes] をかけない
     */
    fun tryReserveBytes(bytes: Long, keepBytes: Long): Boolean {
        require(bytes >= 0) { "バイト数は 0 以上: $bytes" }
        if (!tryAdd(usedBytesCounter, bytes, limitBytes, keepBytes)) return false
        if (parent != null && !parent.tryReserveBytes(bytes)) {
            usedBytesCounter.addAndGet(-bytes)
            return false
        }
        return true
    }

    /** [tryReserveBytes] で確保した [bytes] バイトを返す（親からも引く） */
    fun releaseBytes(bytes: Long) {
        require(bytes >= 0) { "バイト数は 0 以上: $bytes" }
        val after = usedBytesCounter.addAndGet(-bytes)
        check(after >= 0) { "RAM の使用量が負になった: $after" }
        parent?.releaseBytes(bytes)
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
    val limitBytes: Long = limitPages.toLong() * WasmMemory.PAGE_SIZE

    private val usedBytesCounter = AtomicLong()
    private val used = AtomicInteger()

    /** 線形メモリとして今使っているページ数（テーブルの分は入らない） */
    val usedPages: Int get() = used.get()

    /** 今使っているバイト数（線形メモリとテーブルの合計） */
    val usedBytes: Long get() = usedBytesCounter.get()

    /**
     * 確保を一度でも断ったか（プロセスの上限・マシンの RAM・サーバー全体のどれか）。インスタンスを作り直せなかった理由が
     * RAM の不足か（`ColdBootCause.NO_MEMORY`）、それ以外か（`RESTORE_FAILED`）を分けるのに使う（#576）
     */
    @Volatile
    var refusedReservation: Boolean = false
        private set

    override fun tryReserve(pages: Int): Boolean {
        require(pages > 0) { "ページ数は正: $pages" }
        val bytes = pages.toLong() * WasmMemory.PAGE_SIZE
        if (!tryAdd(usedBytesCounter, bytes, limitBytes)) return refused()
        if (!machine.tryReserve(pages)) {
            usedBytesCounter.addAndGet(-bytes)
            return refused()
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

    override fun tryReserveBytes(bytes: Long): Boolean = tryReserveBytes(bytes, 0)

    /** プロセスとマシンのそれぞれで、数えた後の残りが [keepBytes] 以上になるときだけ数える（#408。サーバー全体には [keepBytes] をかけない） */
    override fun tryReserveBytes(bytes: Long, keepBytes: Long): Boolean {
        require(bytes > 0) { "バイト数は正: $bytes" }
        if (!tryAdd(usedBytesCounter, bytes, limitBytes, keepBytes)) return refused()
        if (!machine.tryReserveBytes(bytes, keepBytes)) {
            usedBytesCounter.addAndGet(-bytes)
            return refused()
        }
        return true
    }

    private fun refused(): Boolean {
        refusedReservation = true
        return false
    }

    override fun releaseBytes(bytes: Long) {
        require(bytes > 0) { "バイト数は正: $bytes" }
        subtract(bytes)
        machine.releaseBytes(bytes)
    }

    /**
     * 残りの RAM（バイト。`kibi:computer/memory` の `remaining`。#408）。プロセスの上限とマシンの RAM のそれぞれの残りのうち、小さい方（0 未満にしない）。
     * サーバー全体の勘定（#362）は入れない
     */
    val remainingBytes: Long get() = maxOf(0L, minOf(limitBytes - usedBytes, machine.limitBytes - machine.usedBytes))

    /** 数えられる上限（バイト）。プロセスの上限とマシンの RAM のうち小さい方（コンソールに出すメモリ不足の理由に使う。#407） */
    val effectiveLimitBytes: Long get() = minOf(limitBytes, machine.limitBytes)

    private fun subtract(bytes: Long) {
        val after = usedBytesCounter.addAndGet(-bytes)
        check(after >= 0) { "プロセスのメモリの使用量が負になった: $after バイト" }
    }
}

/**
 * [counter] に [delta] を足して、[limit] まで [keep] 以上残れば足して `true`。残らなければ何もせず `false`。
 * [keep] は符号なし 64 ビット（負の値は 2^63 以上なので、どの上限より大きい）
 */
private fun tryAdd(counter: AtomicLong, delta: Long, limit: Long, keep: Long = 0): Boolean {
    if (keep < 0 || keep > limit) return false
    val cap = limit - keep
    while (true) {
        val current = counter.get()
        if (delta > cap - current) return false
        if (counter.compareAndSet(current, current + delta)) return true
    }
}
