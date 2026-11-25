package pro.ikili.kibicomputers.core.kernel

import pro.ikili.kibicomputers.core.kernel.process.StackHeadroom
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
 *
 * 段（プロセス → マシン → サーバー全体）ごとに別の CAS なので、段の途中でスタックが尽きると、片方だけ数えたまま抜けてずれる（#856）。
 * ゲストのどの深さからでも入る確保（初めて書いた区画、`memory.grow`、`table.grow`）は [ProcessMemory] の入口でスタックの余裕を確かめ、
 * ここ（と呼び手の後処理）はその余裕の中で動く。返す側では確かめない（確かめで投げると、返すこと自体が飛ばされる）
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
    fun tryReserve(pages: Int): Boolean = reserve(pages) == null

    /** [tryReserve] と同じだが、断った段を返す（数えたら `null`。[reserveBytes]） */
    fun reserve(pages: Int): MemoryRefusal? {
        val refusal = reserveBytes(pages.toLong() * WasmMemory.PAGE_SIZE, 0)
        if (refusal == null) linearPages.addAndGet(pages)
        return refusal
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
    fun tryReserveBytes(bytes: Long, keepBytes: Long): Boolean = reserveBytes(bytes, keepBytes) == null

    /**
     * [tryReserveBytes] と同じだが、断った段を返す（#48）。数えたら `null`、自分の勘定で断ったら [MemoryRefusal.MACHINE]、
     * 親（サーバー全体）で断ったら [MemoryRefusal.SERVER]。自分と親のそれぞれを1回の CAS で数え、親で断られたら自分の分を戻す
     */
    fun reserveBytes(bytes: Long, keepBytes: Long): MemoryRefusal? {
        require(bytes >= 0) { "バイト数は 0 以上: $bytes" }
        if (!tryAdd(usedBytesCounter, bytes, limitBytes, keepBytes)) return MemoryRefusal.MACHINE
        if (parent != null && !parent.tryReserveBytes(bytes)) {
            usedBytesCounter.addAndGet(-bytes)
            return MemoryRefusal.SERVER
        }
        return null
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
 *
 * 確保（[tryReserve]・[tryReserveBytes]）は、ゲストの命令（初めて書いた区画・`memory.grow`・`table.grow`）から、ホストの関数の包みを
 * 通らずにどの深さからでも呼ばれる（ホストの関数の本体から入る道もある。下）。そこで先頭で [StackHeadroom.MEMORY_ACCOUNT_FRAMES] 段の余裕を確かめ、足りなければ何も数えずに
 * `StackOverflowError` を投げる（プロセスだけが `call stack exhausted` で終わる。#856）。通れば、3 段の CAS と断られたときの戻し、
 * 呼び手の後処理（区画の確保・写し、Endive の `grow`、数えた分の記録）は、確かめた余裕の中で動く。返す側では確かめない。
 *
 * ホストの関数の本体から入る道（本体が新しい区画へ書く `memory.write*`・`commit`（`WrittenPagesMemory.commitRange`）、
 * `ctx.instantiate` が作るメモリ・テーブルの最初の分（`createMemory`・`createTable`）。どれもカーネルロックの外）では、
 * 包みの確かめを通った後の本体の深さから、ここでもう 1 回確かめる。本体の途中（カーネルの状態を変えた後）で
 * `StackOverflowError` になる深さがあるが、何も数える前に投げるので勘定はずれず、プロセスだけが終わる（ADR 0043）。
 * コンポーネントの core インスタンスの固定費（`ProcessRunner.reserveCoreInstanceOverhead`。カーネルロックの中）は、
 * プロセスの始まり（`load` → `loadComponent` の `configure`。step・専用スレッドの浅いところ）からだけ来て、ホストの関数の本体からは入らない。
 * `/tmp`（`TmpFileSystem`）はここを通らずに [MemoryAccount] を直接呼ぶので、自分の変える操作の入口で
 * [StackHeadroom.TMPFS_FRAMES] 段を確かめる（#883。`StackHeadroom` の KDoc）
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
    val refusedReservation: Boolean get() = lastRefusal != null

    /**
     * 最後に確保を断った段（#48）。一度も断っていなければ `null`。コンソールに出すメモリ不足の理由を、断った段に合わせるのに使う
     * （サーバー全体で断ったときは、マシンの RAM の上限を書かない）。
     * 書くのは確保を断ったときだけ（断るたびに上書きする）。プロセスはマシンの勘定を共有するので、マシンの [MemoryAccount] ではなくここに置く
     */
    @Volatile
    var lastRefusal: MemoryRefusal? = null
        private set

    /** 確保を断った回数（#48）。インスタンスを作る間に断ったかを、前後の値を比べて見分ける。断ったときだけ書く */
    private val refusals = AtomicInteger()

    /** 確保を断った回数（[refusals]） */
    val refusalCount: Int get() = refusals.get()

    override fun tryReserve(pages: Int): Boolean {
        require(pages > 0) { "ページ数は正: $pages" }
        // 段の途中で尽きないよう、何も数えないうちに確かめる（#856。足りなければ何も触らずに StackOverflowError）
        StackHeadroom.ensure(StackHeadroom.MEMORY_ACCOUNT_FRAMES)
        val bytes = pages.toLong() * WasmMemory.PAGE_SIZE
        if (!tryAdd(usedBytesCounter, bytes, limitBytes)) return refused(MemoryRefusal.PROCESS)
        val refusal = machine.reserve(pages)
        if (refusal != null) {
            usedBytesCounter.addAndGet(-bytes)
            return refused(refusal)
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
        // 段の途中で尽きないよう、何も数えないうちに確かめる（#856。足りなければ何も触らずに StackOverflowError）
        StackHeadroom.ensure(StackHeadroom.MEMORY_ACCOUNT_FRAMES)
        if (!tryAdd(usedBytesCounter, bytes, limitBytes, keepBytes)) return refused(MemoryRefusal.PROCESS)
        val refusal = machine.reserveBytes(bytes, keepBytes)
        if (refusal != null) {
            usedBytesCounter.addAndGet(-bytes)
            return refused(refusal)
        }
        return true
    }

    private fun refused(refusal: MemoryRefusal): Boolean {
        lastRefusal = refusal
        refusals.incrementAndGet()
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

/** 確保を断った段（#48。ADR 0048）。プロセス → マシン → サーバー全体の順に数え、最初に収まらなかった段 */
internal enum class MemoryRefusal {
    /** プロセスの上限（`maxProcessMemoryBytes`） */
    PROCESS,

    /** マシンの RAM */
    MACHINE,

    /** サーバー全体の勘定（`SchedulerConfig.totalMemoryBytes`。#362） */
    SERVER,
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
