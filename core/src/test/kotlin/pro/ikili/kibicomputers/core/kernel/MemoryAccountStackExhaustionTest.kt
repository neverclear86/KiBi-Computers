package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.process.StackHeadroom
import pro.ikili.kibicomputers.core.runtime.endive.WrittenPagesMemory
import run.endive.wasm.types.MemoryLimits
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * スタックの底の近くで、メモリの勘定（プロセス → マシン → サーバー全体の別々の CAS）に入ったとき、勘定がずれないことを確かめる（#856）。
 *
 * 本番のカーネルのプロセスと同じく、サーバー全体の [MemoryAccount] → マシンの [MemoryAccount] → [ProcessMemory] →
 * [WrittenPagesMemory] を組み、256 KiB のスタックのスレッドで 1 段ずつ深くしながら、**底に近い深さのすべて**で 1 回ずつ確保を呼ぶ。
 * 尽きる位置を乱数や JIT の温まり方に頼って当てるのではなく、深さを 1 段ずつ全部試すので、確かめがあるか・足りるかは、
 * JIT の状態にかかわらず決まる。深さごとに、確保が通ったか（[COMPLETED]）、スタックが足りずに `StackOverflowError` で断られたか（[REFUSED]）を記録し、
 * 最後に浅いところで、数えた分をまとめて返し（[WrittenPagesMemory.releaseAll] など。インスタンスを閉じるときと同じ）、
 * プロセス・マシン・サーバー全体の使用量が 0 に戻ることを確かめる。次の 2 つを見る。
 *
 * - 入口の確かめがあること: 確保の直前に、同じ深さで [StackHeadroom.MEMORY_ACCOUNT_FRAMES] 段の確かめが通らなかった深さ
 *   （確保の後でも通らなかった深さ。JIT でフレームが変わった場合を除く）では、確保が必ず断られる
 * - 確かめた余裕で足りること: 確かめが通った深さでは、確保が最後まで終わるか、何も数えずに断られる。途中で尽きて数えた分が残れば、ずれとして見つかる
 *
 * ホストの関数の本体から入る道（[hostBody]）は、包みの確かめ（[StackHeadroom.HOST_CALL_FRAMES]）が通った後、本体が深くなってから確保に入る。
 * 包みの確かめを通ったのに本体の途中で断られる深さがあること（ADR 0043）と、その深さでも勘定がずれないことを確かめる。
 *
 * ここは勘定の組だけを直接組み、Kotlin の再帰から呼ぶ（深さごとに、確かめが通らない深さで必ず断られることまで見るため）。
 * カーネルのプロセス（`ProcessRunner`・`ctx.hostFunction` の包み・`finishLocked`）と本物の Endive（ROM の区画の写し、`commitRange`、
 * `BudgetedTable` の `table.grow`）を通し、プロセスだけが終わること・ほかの台が動き続けることは [MemoryAccountStackExhaustionKernelTest] で確かめる
 */
class MemoryAccountStackExhaustionTest {
    private val stackBytes = 256L * 1024

    /** 確保の呼び方 */
    private enum class Op {
        /** 新しい 4 KiB の区画に 1 バイト書く（`WrittenPagesMemory.commit` → `ProcessMemory.tryReserveBytes`） */
        WRITE,

        /** 1 ページ増やす（`WrittenPagesMemory.grow` → 端の区画と区画の表を 1 回で数える） */
        GROW,

        /** [ProcessMemory.tryReserveBytes] を直接呼び、数えられたら呼び手の記録に足す（`table.grow` を数える `BudgetedTable` と同じ形） */
        RESERVE_BYTES,

        /** [ProcessMemory.tryReserveBytes] の 2 引数の形（`kibi:computer/memory` の `commit`） */
        RESERVE_KEEP,

        /** [ProcessMemory.tryReserve]（線形メモリのページ。`BudgetedMemory` の `memory.grow`） */
        RESERVE_PAGES,
    }

    /** 深さ 1 つ分の試験の組（深さごとに作る。底に近いところで確保を作らないよう、前もって用意する） */
    private class Fixture(op: Op) {
        val server = MemoryAccount(Long.MAX_VALUE / 4)
        val machine = MemoryAccount(1L shl 40, server)
        val process: ProcessMemory = machine.process(Int.MAX_VALUE / 2)
        val memory: WrittenPagesMemory? = when (op) {
            Op.WRITE -> WrittenPagesMemory(MemoryLimits(8, 8), process)
            Op.GROW -> WrittenPagesMemory(MemoryLimits(1, 4), process)
            else -> null
        }
        var status = NOT_RUN
        var lackedBefore = false
        var lackedAfter = false
        var reservedBytes = 0L
        var reservedPages = 0
    }

    private class Run(val op: Op, val hostBody: Boolean, val pool: Array<Fixture>) {
        var next = 0
    }

    /** [frames] 段の確かめが通らなければ `true`（何も触らない） */
    private fun lacks(frames: Int): Boolean = try {
        StackHeadroom.ensure(frames)
        false
    } catch (_: StackOverflowError) {
        true
    }

    /** 1 段ずつ深くする。底に近づいたら（[WINDOW_FRAMES] 段の確かめが通らなくなったら）、深さごとに確保を 1 回呼ぶ */
    private fun descend(run: Run, n: Int): Int {
        if (run.next < run.pool.size && lacks(WINDOW_FRAMES)) {
            if (run.hostBody) {
                // ホストの関数の包み: 確かめが通らなければ、本体に入らずにプロセスが終わる（確保は呼ばれない）
                if (!lacks(StackHeadroom.HOST_CALL_FRAMES)) body(run, run.pool[run.next++], BODY_FRAMES)
            } else {
                operate(run, run.pool[run.next++])
            }
        }
        return descend(run, n + 1) + 1
    }

    /** 包みを通った後、本体が [k] 段深くなってから確保に入る */
    private fun body(run: Run, f: Fixture, k: Int): Int {
        if (k == 0) {
            operate(run, f)
            return 0
        }
        return body(run, f, k - 1) + 1
    }

    private fun operate(run: Run, f: Fixture) {
        f.lackedBefore = lacks(StackHeadroom.MEMORY_ACCOUNT_FRAMES)
        try {
            when (run.op) {
                Op.WRITE -> f.memory!!.writeByte(0, 1)
                Op.GROW -> f.memory!!.grow(1)
                Op.RESERVE_BYTES -> if (f.process.tryReserveBytes(RESERVE_BYTES)) f.reservedBytes += RESERVE_BYTES
                Op.RESERVE_KEEP -> if (f.process.tryReserveBytes(RESERVE_BYTES, 0)) f.reservedBytes += RESERVE_BYTES
                Op.RESERVE_PAGES -> if (f.process.tryReserve(1)) f.reservedPages += 1
            }
            f.status = COMPLETED
        } catch (_: StackOverflowError) {
            f.status = REFUSED
        }
        f.lackedAfter = lacks(StackHeadroom.MEMORY_ACCOUNT_FRAMES)
    }

    private class Result(val used: List<Fixture>, val drifts: List<String>) {
        val completed = used.count { it.status == COMPLETED }
        val refused = used.count { it.status == REFUSED }

        /** 確保の前後で確かめが通らなかった（フレームが変わっていない）深さの数と、そのうち確保が断られなかった深さ */
        val lacking = used.filter { it.lackedBefore && it.lackedAfter }
        val lackingButCompleted = lacking.count { it.status != REFUSED }
    }

    private fun sweep(op: Op, hostBody: Boolean): Result = timeout(Duration.ofSeconds(120)) {
        val pool = Array(POOL_SIZE) { Fixture(op) }
        val run = Run(op, hostBody, pool)
        val failure = AtomicReference<Throwable>()
        val thread = Thread(null, {
            try {
                descend(run, 0)
            } catch (t: StackOverflowError) {
                // 底まで来た（ここは確かめの対象でない）
            } catch (t: Throwable) {
                failure.set(t)
            }
        }, "memory-account-sweep", stackBytes)
        thread.start()
        thread.join()
        failure.get()?.let { throw AssertionError("試験の再帰が StackOverflowError 以外で終わった", it) }
        val used = pool.take(run.next)
        val drifts = ArrayList<String>()
        for ((i, f) in used.withIndex()) {
            // インスタンスを閉じたとき（finishLocked）と同じく、数えた分をまとめて返す
            f.memory?.releaseAll()
            if (f.reservedBytes > 0) f.process.releaseBytes(f.reservedBytes)
            if (f.reservedPages > 0) f.process.release(f.reservedPages)
            if (f.process.usedBytes != 0L || f.machine.usedBytes != 0L || f.server.usedBytes != 0L ||
                f.process.usedPages != 0 || f.machine.usedPages != 0 || f.server.usedPages != 0
            ) {
                drifts += "$i 番目（${if (f.status == REFUSED) "断られた" else "通った"}）: プロセス ${f.process.usedBytes}、マシン ${f.machine.usedBytes}、" +
                    "サーバー ${f.server.usedBytes}"
            }
        }
        val result = Result(used, drifts)
        println(
            "#856 $op${if (hostBody) "（ホストの関数の本体）" else ""}: 深さ ${used.size}、通った ${result.completed}、断られた ${result.refused}、" +
                "確かめが通らない深さ ${result.lacking.size}、うち断られなかった ${result.lackingButCompleted}、ずれた ${drifts.size}",
        )
        drifts.take(10).forEach { println("#856 $op: $it") }
        result
    }

    private fun check(op: Op) {
        val r = sweep(op, hostBody = false)
        assertEquals(emptyList<String>(), r.drifts, "勘定がずれた")
        // 試験が底をまたいでいること（どちらかが 0 だと、確かめられていない）
        assertTrue(r.completed > 0, "確保が通った深さがない（試験の窓が底から遠い）")
        assertTrue(r.refused > 0, "確保が断られた深さがない（試験の窓が底まで届かない）")
        assertTrue(r.lacking.isNotEmpty(), "確かめが通らない深さがない（試験の窓が底まで届かない）")
        // 入口の確かめがあるなら、通らない深さでは必ず断られる（なければ、通って数える）
        assertEquals(0, r.lackingButCompleted, "確かめが通らない深さで、確保が数えられた（入口の確かめがない）")
    }

    @Test
    fun `底に近い深さのすべてで、新しい区画へ書いても、プロセス・マシン・サーバー全体の勘定はずれない`() = check(Op.WRITE)

    @Test
    fun `底に近い深さのすべてで、memory_growしても、プロセス・マシン・サーバー全体の勘定はずれない`() = check(Op.GROW)

    @Test
    fun `底に近い深さのすべてで、勘定を直接数えても、プロセス・マシン・サーバー全体の勘定はずれない`() = check(Op.RESERVE_BYTES)

    @Test
    fun `底に近い深さのすべてで、keepBytes付きで数えても、プロセス・マシン・サーバー全体の勘定はずれない`() = check(Op.RESERVE_KEEP)

    @Test
    fun `底に近い深さのすべてで、ページを数えても、プロセス・マシン・サーバー全体の勘定はずれない`() = check(Op.RESERVE_PAGES)

    /**
     * ホストの関数の本体（包みの確かめを通った後）から入る道（`memory.write*` が新しい区画に書く、`ctx.instantiate` が作るメモリ・テーブルの最初の分）のまね。
     * 包みは [lacks] で真似るだけで、カーネルのプロセスを通した確かめは [MemoryAccountStackExhaustionKernelTest]（`ctx.instantiate` の道は [InstantiateStackExhaustionTest]・[InstantiateStackExhaustionKernelTest]。#884）。
     * 本体が深くなってから確保に入るので、包みの確かめは通ったのに、本体の途中で確保が断られる深さがある（ADR 0043）。
     * そこでは何も数えずに `StackOverflowError` になり、勘定はずれない（本体がその前に変えたカーネルの状態は、プロセスの終わりで片付く）
     */
    @Test
    fun `ホストの関数の本体から入る確保が、包みの確かめを通った後に断られても、勘定はずれない`() {
        for (op in listOf(Op.WRITE, Op.RESERVE_BYTES)) {
            val r = sweep(op, hostBody = true)
            assertEquals(emptyList<String>(), r.drifts, "$op: 勘定がずれた")
            assertTrue(r.completed > 0, "$op: 本体からの確保が通った深さがない")
            assertTrue(r.refused > 0, "$op: 包みを通った後に、本体からの確保が断られた深さがない（本体の途中で尽きる窓が確かめられていない）")
        }
    }

    private companion object {
        const val NOT_RUN = 0
        const val COMPLETED = 1
        const val REFUSED = 2

        /** 底に近い深さを選ぶための確かめの段数（確保の確かめ [StackHeadroom.MEMORY_ACCOUNT_FRAMES] の何倍も大きくして、窓に底を含める） */
        const val WINDOW_FRAMES = 256

        /** ホストの関数の本体が、包みの後に深くなる段数 */
        const val BODY_FRAMES = 150

        /** 窓に入る深さの数の上限（1 段 30 バイト程度でも、窓の分が入る） */
        const val POOL_SIZE = 6000

        const val RESERVE_BYTES = 64L
    }
}
