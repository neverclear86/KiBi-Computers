package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessRuntime
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.StackHeadroom
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.process.crashReason
import pro.ikili.kibicomputers.core.runtime.FakeContext
import pro.ikili.kibicomputers.core.runtime.FakeModule
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * ゲストの深い再帰でスタックが尽きる位置が、カーネルのロック（マシンのロック・CPU・許可）の途中に落ちても、
 * ロック・CPU・許可が壊れないことを確かめる（#828）。
 *
 * スタックを 256 KiB に縮めたカーネルのスレッドで、1 段ごとにホストの関数（`ctx.hostFunction` の包み）か燃料切れの処理を呼ぶ再帰を、
 * 尽きる位置をずらしながら多数回動かす。毎回の後に、ほかのスレッド（テストのスレッド）からマシンのロックが取れること、
 * 同じ台と別の台のプロセスが進み続けることを確かめ、最後に止めて CPU と許可が元に戻ったことを確かめる。
 * 同じワーカー・同じ種類の専用スレッドで何度もスタックを使い切らせる（2 回目以降に JVM ごと落ちないことも見る）
 */
class StackExhaustionLockTest {
    private val limit = Duration.ofSeconds(120)

    /** 1 段ごとの呼び方 */
    private enum class Pattern { HOST_EVERY_FRAME, FUEL_EVERY_FRAME, MIXED }

    /** 再帰で尽きた位置（SOE の一番上のフレーム）がロックの中だった回数を数えるための記録 */
    private class Tally {
        val crashed = AtomicInteger()
        val inLocks = AtomicInteger()
        val inProbe = AtomicInteger()
    }

    /** 尽きる位置をずらすための詰め物（[pad] 段だけ積んでから [body] を呼ぶ） */
    private fun padThen(pad: Int, body: () -> Long): Long = if (pad <= 0) body() else padThen(pad - 1, body) + 1

    private fun recurse(ctx: FakeContext, pattern: Pattern, n: Int): Long {
        when (pattern) {
            Pattern.HOST_EVERY_FRAME -> ctx.callImport("env", "h")
            Pattern.FUEL_EVERY_FRAME -> ctx.callFuelHandler(0)
            Pattern.MIXED -> if (n % 3 == 0) ctx.callFuelHandler(0) else ctx.callImport("env", "h")
        }
        return recurse(ctx, pattern, n + 1) + n
    }

    private fun victim(pattern: Pattern, pad: Int): FakeModule = FakeModule.builder()
        .fuelMetered()
        .import("env", "h", VOID)
        .export("run", VOID) { ctx, _ ->
            padThen(pad) { recurse(ctx, pattern, 0) }
            LongArray(0)
        }
        .build()

    private fun sweep(kind: ProcessKind, rounds: Int) = timeout(limit) {
        val config = SchedulerConfig.builder().maxConcurrentMachines(2).maxBlockingProcesses(64).threadStackBytes(256L * 1024).build()
        val h = Harness(config)
        val tally = Tally()
        try {
            val m = h.machine(fuelPerTick = 50_000_000, fuelSlice = 10_000)
            val other = h.machine(fuelPerTick = 50_000_000, fuelSlice = 10_000)
            val sameProgress = AtomicInteger()
            val otherProgress = AtomicInteger()
            m.spawnTest(h.program(spinForever(sameProgress)), runDriver(kind))
            other.spawnTest(h.program(spinForever(otherProgress)), runDriver(kind))
            h.tickUntilTrue("spin が動かない") { sameProgress.get() > 0 && otherProgress.get() > 0 }
            val permits = config.maxConcurrentMachines
            val patterns = Pattern.values()
            for (i in 0 until rounds) {
                val pattern = patterns[i % patterns.size]
                // 尽きる位置を 1 段ずつずらす（詰め物の段数を変える）
                val pad = (i / patterns.size * 7) % 97
                val driver = runDriver(kind) { ctx, b -> b.host(ctx) { _, _, _ -> LongArray(0) } }
                val rt = m.spawnRt(h.program(victim(pattern, pad)), driver)
                h.tickUntilTrue("深い再帰が終わらない（$i 回目、$pattern、pad $pad）") { rt.process.state.isTerminal || m.state == MachineState.CRASHED }
                assertEquals(MachineState.RUNNING, m.state, "マシンが壊れた（$i 回目）: ${rt.lastError}")
                assertEquals(ProcessState.CRASHED, rt.process.state, "${rt.lastError}")
                assertEquals("call stack exhausted", crashReason(rt.lastError!!), "${rt.lastError}")
                record(tally, rt)
                // ほかのスレッドから、マシンのロックが取れる（持ったまま死んだスレッドがいない）
                assertTrue(m.lock.tryLock(5, TimeUnit.SECONDS), "マシンのロックが取れない（$i 回目、$pattern、pad $pad）: ${m.lock}")
                m.lock.unlock()
                // 同じ台と別の台のプロセスが進み続ける（CPU・許可が渡り続ける）
                val same = sameProgress.get()
                val otherBefore = otherProgress.get()
                eventuallyDescribe(10_000, { "同じ台のプロセスが止まった（$i 回目、$pattern、pad $pad）: ${state(h, m, rt)}" }) {
                    h.scheduler.tick()
                    sameProgress.get() > same + 2
                }
                h.tickUntilTrue("別の台のプロセスが止まった（$i 回目、$pattern、pad $pad）") { otherProgress.get() > otherBefore + 2 }
            }
            m.stop()
            other.stop()
            h.tickUntilTrue("止まらない") { m.state == MachineState.STOPPED && other.state == MachineState.STOPPED }
            // CPU は誰も持っておらず、待ち手もいない。許可はすべて戻った
            eventually { m.cpu.currentOwner == null && m.cpu.waiting == 0 }
            eventually(message = "許可が戻らない: ${h.scheduler.permits.availablePermits()}") { h.scheduler.permits.availablePermits() == permits }
            assertEquals(0, h.scheduler.permits.waiting)
            assertTrue(!m.lock.isLocked && !m.lock.hasQueuedThreads(), "${m.lock}")
        } finally {
            println("#828 $kind: CRASHED ${tally.crashed.get()} 回、尽きた位置がロックの中 ${tally.inLocks.get()} 回、入口の確かめ ${tally.inProbe.get()} 回")
            h.close()
        }
        // 尽きた位置が JDK のロックの中だったことはない（入口の余裕の確かめが、ロックを取る前に止める）
        assertEquals(0, tally.inLocks.get(), "スタックが JDK のロックの中で尽きた")
    }

    private fun state(h: Harness, m: Machine, rt: ProcessRuntime): String {
        var t: Throwable? = rt.lastError
        while (t != null && t !is StackOverflowError) t = t.cause
        val frames = t?.stackTrace?.take(8)?.joinToString(" <- ")
        return "CPU ${m.cpu.currentOwner?.pid} 待ち ${m.cpu.waiting}、許可 ${h.scheduler.permits.availablePermits()} 待ち ${h.scheduler.permits.waiting}、" +
            "lock ${m.lock}、SOE: $frames"
    }

    private fun record(tally: Tally, rt: ProcessRuntime) {
        tally.crashed.incrementAndGet()
        var t: Throwable? = rt.lastError
        while (t != null && t !is StackOverflowError) t = t.cause
        val top = t?.stackTrace?.firstOrNull() ?: return
        if (top.className.startsWith("java.util.concurrent.locks.")) tally.inLocks.incrementAndGet()
        if (top.className == StackHeadroom::class.java.name) tally.inProbe.incrementAndGet()
    }

    @Test
    fun `BLOCKINGで尽きる位置をずらして深い再帰を繰り返しても、ロック・CPU・許可は壊れない`() = sweep(ProcessKind.BLOCKING, rounds = 120)

    @Test
    fun `WAIT_POINTで尽きる位置をずらして深い再帰を繰り返しても、ロック・CPU・許可は壊れない`() = sweep(ProcessKind.WAIT_POINT, rounds = 120)

    /**
     * 安全網: 入口の確かめが漏れてロックが残ったときのまね。ドライバーがマシンのロックを取ったまま返ると、step・専用スレッドの終わりで
     * 放され、マシンが不整合で CRASHED になる（サーバーの tick がそのマシンのロックで止まらない）。ほかの台は動き続ける
     */
    private fun leakedLockIsRecovered(kind: ProcessKind) = timeout(Duration.ofSeconds(30)) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            val other = h.machine(fuelPerTick = 1_000_000)
            val progress = AtomicInteger()
            other.spawnTest(h.program(spinForever(progress)), runDriver())
            val leaker = TestDriver(kind) { _ ->
                m.lock.lock()
                StepResult.Exit(0)
            }
            m.spawnTest(h.program(emptyModule()), leaker)
            h.tickUntilTrue("マシンが CRASHED にならない") { m.state == MachineState.CRASHED }
            assertTrue(m.lock.tryLock(5, TimeUnit.SECONDS), "マシンのロックが放されていない: ${m.lock}")
            m.lock.unlock()
            val before = progress.get()
            h.tickUntilTrue("ほかの台が止まった") { progress.get() > before + 2 }
            assertEquals(MachineState.RUNNING, other.state)
            other.stop()
        }
    }

    @Test
    fun `BLOCKINGの専用スレッドがマシンのロックを持ったまま終わると、放してマシンをCRASHEDにする`() = leakedLockIsRecovered(ProcessKind.BLOCKING)

    @Test
    fun `WAIT_POINTのstepがマシンのロックを持ったまま終わると、放してマシンをCRASHEDにする`() = leakedLockIsRecovered(ProcessKind.WAIT_POINT)
}
