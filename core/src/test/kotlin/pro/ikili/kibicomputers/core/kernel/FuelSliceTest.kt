package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.process.KillSignal
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.runtime.FakeModule
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * マシンの中の燃料の配り方（#47、ADR 0047）: 1回に渡すのはスライスまでで、使い切ったら CPU を待つプロセスに譲り、
 * 待つ者がいなければその場で次を足す。
 */
class FuelSliceTest {
    private val limit = Duration.ofSeconds(60)

    /** 1 燃料ごとに [micros] µs かけて計算し、[progress] を数え続けるモジュール（スレッドの起き上がりより長くスライスがかかるように） */
    private fun slowSpin(progress: AtomicInteger, micros: Long = 20): FakeModule = FakeModule.builder()
        .fuelMetered()
        .export("run", VOID) { ctx, _ ->
            while (true) {
                val until = System.nanoTime() + micros * 1_000
                while (System.nanoTime() < until) Thread.onSpinWait()
                ctx.step(1)
                progress.incrementAndGet()
            }
            @Suppress("UNREACHABLE_CODE")
            LongArray(0)
        }
        .build()

    @Test
    fun `既定のスライスは、fuelPerTickの4分の1と下限の大きいほうで、fuelPerTickを超えない`() {
        assertEquals(250_000, MachineConfig.builder().fuelPerTick(1_000_000).build().fuelSlice)
        assertEquals(1_000_000, MachineConfig.builder().fuelPerTick(4_000_000).build().fuelSlice)
        assertEquals(250_000, MachineConfig.builder().fuelPerTick(400_000).build().fuelSlice)
        assertEquals(100_000, MachineConfig.builder().fuelPerTick(100_000).build().fuelSlice)
        assertEquals(100, MachineConfig.builder().fuelPerTick(100).fuelSlice(1_000).build().fuelSlice)
        assertEquals(250_000, MachineConfig.DEFAULT.fuelSlice)
    }

    @Test
    fun `プロセスが1つだけなら、CPUを返さずにその場で足し、1tickにfuelPerTickまで使う`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1000, fuelSlice = 100)
            val progress = AtomicInteger()
            m.spawnTest(h.program(spinForever(progress)), runDriver())
            val yields0 = h.scheduler.fuelYields.get()
            val blocking0 = h.scheduler.fuelBlockingWaits.get()
            for (k in 1..3) {
                h.scheduler.tick()
                eventually { progress.get() >= 1000 * k - 1 }
                Thread.sleep(30)
                assertTrue(progress.get() <= 1000 * k + 1, "tick $k: ${progress.get()}")
            }
            assertEquals(yields0, h.scheduler.fuelYields.get(), "譲る相手がいないのに譲った")
            // 1 tick に 9 回ずつその場で足し、tick の終わりにだけ燃料を待つ
            assertTrue(h.scheduler.fuelSliceRenewals.get() >= 27, "その場で足した回数: ${h.scheduler.fuelSliceRenewals.get()}")
            assertTrue(h.scheduler.fuelBlockingWaits.get() - blocking0 in 2L..4L, "燃料を待った回数: ${h.scheduler.fuelBlockingWaits.get() - blocking0}")
            m.stop()
        }
    }

    @Test
    fun `計算し続ける2つのプロセスは、同じtickの中でスライスずつ交代して、同じくらい進む`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1000, fuelSlice = 100)
            val p1 = AtomicInteger()
            val p2 = AtomicInteger()
            m.spawnTest(h.program(slowSpin(p1)), runDriver())
            m.spawnTest(h.program(slowSpin(p2)), runDriver())
            // 1 tick 目は、2つとも動き始めるところ
            h.scheduler.tick()
            eventually(10_000) { p1.get() + p2.get() >= 999 }
            val yields0 = h.scheduler.fuelYields.get()
            val a0 = p1.get()
            val b0 = p2.get()
            for (k in 2..6) {
                val before = p1.get() + p2.get()
                h.scheduler.tick()
                eventually(10_000) { p1.get() + p2.get() >= 1000 * k - 2 }
                Thread.sleep(20)
                val used = p1.get() + p2.get() - before
                assertTrue(used <= 1000 + 2, "tick $k: 1 tick に配る燃料を超えた: $used")
            }
            // tick ごとの内訳は見ない（tick の頭の入り方でぶれうる）。5 tick の合計で、どちらも独り占めされずに進んだことを見る。
            // 起こされたスレッドは CPU の位置を予約するので、着くまでの間も「待つ者」に数え、もう片方はスライスの切れ目で譲る（#742）
            val da = p1.get() - a0
            val db = p2.get() - b0
            assertTrue(da >= 1000 && db >= 1000, "偏っている: $da / $db")
            assertTrue(h.scheduler.fuelYields.get() - yields0 >= 6, "譲った回数: ${h.scheduler.fuelYields.get() - yields0}")
            m.stop()
        }
    }

    @Test
    fun `スライスがfuelPerTickと同じ台で、計算し続けるブロッキング型がいても、後から起きたブロッキング型は数tickのうちに進む`() = timeout(limit) {
        Harness().use { h ->
            // 1 tick が 1 スライス（#742）。計算し続ける側（A）が tick の頭で先に CPU を取り、ほかの待ち手（B）が CPU の待ち行列に着く前に
            // スライスを使い切ったとする。そのあと CPU を取り直して燃料がないと分かった A が、燃料待ちの列の前に入ると、次の tick も A から起こされ、
            // B は飢える。A はその tick の最後のスライスを使ったので、列の後ろに置く。起こす側は B の CPU の位置も予約する（ADR 0047）
            val m = h.machine(fuelPerTick = 1000)
            assertEquals(1000, m.config.fuelSlice)
            val pa = AtomicInteger()
            val pb = AtomicInteger()
            val a = m.spawnTest(h.program(spinForever(pa)), runDriver())
            h.scheduler.tick()
            eventually(10_000) { m.stateOf(a) == ProcessState.WAITING_FUEL && m.fuelWaiterCount == 1 }
            // B が最初に起こされたときだけ、A がその tick のスライスを使い切るまで、CPU を取りに行かせない
            // （スレッドの起き上がりが遅れた形を、CPU の数によらずに作る）。A は使い切った後、CPU を取り直して燃料待ちの列に戻るか、
            // B が予約した CPU の位置の後ろで CPU を待つ。B が先に起こされて、もう CPU を渡されていれば（A が列の後ろにいた）止めない
            val held = AtomicBoolean()
            val aBefore = AtomicInteger()
            h.scheduler.hooks.afterFuelWait = { _, pid ->
                if (pid != a && held.compareAndSet(false, true) && m.cpu.currentOwner?.pid != pid) {
                    eventually(10_000, "A がスライスを使い切らない") {
                        pa.get() >= aBefore.get() + 999 && (m.fuelWaiterCount == 1 || m.cpu.waiting >= 1)
                    }
                }
            }
            val b = m.spawnTest(h.program(spinForever(pb)), runDriver())
            eventually(10_000) { m.fuelWaiterCount == 2 }
            val ticks = 8
            var aTicks = 0
            var bTicks = 0
            for (k in 1..ticks) {
                val a0 = pa.get()
                val b0 = pb.get()
                aBefore.set(a0)
                val woken0 = h.scheduler.dispatcher.woken.get()
                h.scheduler.tick()
                eventuallyDescribe(10_000, {
                    "tick $k: 燃料を使い切らない（A ${pa.get() - a0}、B ${pb.get() - b0}、燃料待ち ${m.fuelWaiterCount}、" +
                        "CPU の待ち ${m.cpu.waiting}、起こした数 ${h.scheduler.dispatcher.woken.get() - woken0}、${m.stateOf(a)} / ${m.stateOf(b)}）"
                }) {
                    h.scheduler.dispatcher.woken.get() - woken0 >= 2 && m.fuelWaiterCount == 2 &&
                        pa.get() + pb.get() >= a0 + b0 + 999
                }
                if (pa.get() > a0) aTicks++
                if (pb.get() > b0) bTicks++
            }
            assertTrue(held.get(), "B が起こされていない")
            // 厳密な交互は見ない（CPU に着く順は、起こされたスレッドの起き上がりの競争で決まる）。数 tick のうちに両方が進むことを見る
            assertTrue(bTicks >= ticks / 2 - 1, "後から起きた側が飢えている: A $aTicks tick / B $bTicks tick")
            assertTrue(aTicks >= 1, "計算し続ける側が飢えている: A $aTicks tick / B $bTicks tick")
            assertTrue(m.stateOf(b) == ProcessState.WAITING_FUEL)
            m.stop()
        }
    }

    @Test
    fun `燃料待ちから起こされてCPUの位置を予約したまま止められても、CPUと許可を漏らさない`() = timeout(limit) {
        Harness().use { h ->
            // 起こす側は、スレッドを持つ待ち手の CPU の位置を先に予約する（#742、#737）。起き上がったスレッドが CPU に着く前に止められたら、
            // 予約を外す（もう渡されていれば次へ渡す）。外さないと、CPU がその待ち手のものになったままで、ほかのプロセスが動けない
            val m = h.machine(fuelPerTick = 1000)
            val permitsBefore = h.scheduler.permits.availablePermits()
            val pa = AtomicInteger()
            val a = m.spawnTest(h.program(spinForever(pa)), runDriver())
            h.scheduler.tick()
            eventually(10_000) { m.stateOf(a) == ProcessState.WAITING_FUEL && m.fuelWaiterCount == 1 }
            val parked = CountDownLatch(1)
            val never = CountDownLatch(1)
            h.scheduler.hooks.afterFuelWait = { _, pid ->
                if (pid != a) {
                    parked.countDown()
                    never.await()
                }
            }
            val b = m.spawnTest(h.program(spinForever()), runDriver())
            eventually(10_000) { m.fuelWaiterCount == 2 }
            h.scheduler.tick()
            assertTrue(parked.await(10, TimeUnit.SECONDS), "B が起こされない")
            // A がその tick を使い切り、B の予約の後ろで CPU を待つか、燃料待ちに戻るまで待ってから止める
            eventually(10_000) { m.fuelWaiterCount == 1 || m.cpu.waiting >= 1 }
            val bRt = m.rt(b)
            m.lock.withLockValue { m.killLocked(b, KillSignal.KILL) }
            eventually(10_000, "B が終わらない") { m.stateOf(b).isTerminal }
            eventually(10_000, "予約が残っている") { !m.cpu.hasReservation(bRt) }
            // A は止まらずに、次の tick も進む
            repeat(3) { k ->
                val a0 = pa.get()
                h.scheduler.tick()
                eventually(10_000, "tick $k: A が進まない（CPU が漏れた）") { pa.get() >= a0 + 999 && m.fuelWaiterCount == 1 }
            }
            m.stop()
            eventually(10_000) { m.cpu.currentOwner == null && h.scheduler.permits.availablePermits() == permitsBefore }
        }
    }

    /**
     * 起こされて CPU の待ち行列の位置を予約した（まだ渡っていない）スレッドを、CPU に着く前に止める。CPU は別のプロセス C が持っている。
     * [swallowInterrupt] なら、割り込みを燃料待ちの後のフックで握って（印だけ戻して）、`cpu.acquire` に割り込みを持ったまま入らせる
     * （acquireCpu の最初の catch）。そうでなければ、フックの中で割り込まれる（燃料待ちの後の catch）。どちらも予約を外す
     */
    private fun killReservedBehindOwner(swallowInterrupt: Boolean) = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1000)
            val permitsBefore = h.scheduler.permits.availablePermits()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder = FakeModule.builder()
                .fuelMetered()
                .export("run", VOID) { ctx, _ ->
                    ctx.step(1)
                    entered.countDown()
                    release.await()
                    LongArray(0)
                }
                .build()
            val c = m.spawnTest(h.program(holder), runDriver())
            eventually(10_000) { m.fuelWaiterCount == 1 }
            val parked = CountDownLatch(1)
            val never = CountDownLatch(1)
            h.scheduler.hooks.afterFuelWait = { _, pid ->
                if (pid != c) {
                    parked.countDown()
                    try {
                        never.await()
                    } catch (e: InterruptedException) {
                        if (swallowInterrupt) Thread.currentThread().interrupt() else throw e
                    }
                }
            }
            val b = m.spawnTest(h.program(spinForever()), runDriver())
            eventually(10_000) { m.fuelWaiterCount == 2 }
            // 起こす順は C、B。C は渡され、B は C の後ろに予約が入る
            h.scheduler.tick()
            assertTrue(entered.await(10, TimeUnit.SECONDS), "C が動かない")
            assertTrue(parked.await(10, TimeUnit.SECONDS), "B が起こされない")
            val bRt = m.rt(b)
            assertTrue(m.cpu.hasReservation(bRt))
            assertEquals(1, m.cpu.waiting)
            assertEquals(c, m.cpu.currentOwner?.pid)
            m.lock.withLockValue { m.killLocked(b, KillSignal.KILL) }
            eventually(10_000, "B が終わらない") { m.stateOf(b).isTerminal }
            eventually(10_000, "予約が残っている") { !m.cpu.hasReservation(bRt) }
            assertEquals(0, m.cpu.waiting, "止めた B が待ち行列に残っている")
            assertEquals(c, m.cpu.currentOwner?.pid, "CPU の持ち主が変わった")
            release.countDown()
            eventually(10_000, "C が終わらない") { m.stateOf(c).isTerminal }
            eventually(10_000) { m.cpu.currentOwner == null && h.scheduler.permits.availablePermits() == permitsBefore }
            m.stop()
        }
    }

    @Test
    fun `まだ渡していない予約のスレッドを止めると、予約を外し、CPUを持つ側はそのまま`() = killReservedBehindOwner(swallowInterrupt = false)

    @Test
    fun `予約を受け取る前に割り込みを持ったままCPUを取りに行ったスレッドも、予約を外す`() = killReservedBehindOwner(swallowInterrupt = true)

    @Test
    fun `燃料待ちを抜けた後、終わる前に起こされても、止められたスレッドの予約は入らない`() = timeout(limit) {
        Harness().use { h ->
            // 止められた待ち手は、燃料待ちを例外で抜けた後、終わる（カーネルロックを取り直す）まで燃料待ちの列に残る。その間に起こされても、
            // CPU の位置を予約してはいけない（予約しても、そのスレッドは CPU に着かず、取り消す者もいない。#742）
            val m = h.machine(fuelPerTick = 1000)
            val permitsBefore = h.scheduler.permits.availablePermits()
            val caught = CountDownLatch(1)
            val proceed = CountDownLatch(1)
            val victim = FakeModule.builder()
                .fuelMetered()
                .export("run", VOID) { ctx, _ ->
                    try {
                        while (true) ctx.step(1)
                    } catch (e: Throwable) {
                        caught.countDown()
                        proceed.await()
                        throw e
                    }
                    @Suppress("UNREACHABLE_CODE")
                    LongArray(0)
                }
                .build()
            val a = m.spawnTest(h.program(victim), runDriver())
            // 1 tick 目で A が燃料を使い切り、モジュールの中の ctx.step で燃料待ちになる
            h.scheduler.tick()
            eventually(10_000) { m.stateOf(a) == ProcessState.WAITING_FUEL && m.fuelWaiterCount == 1 }
            val pb = AtomicInteger()
            val b = m.spawnTest(h.program(spinForever(pb)), runDriver())
            eventually(10_000) { m.fuelWaiterCount == 2 }
            val aRt = m.rt(a)
            m.lock.withLockValue { m.killLocked(a, KillSignal.KILL) }
            assertTrue(caught.await(10, TimeUnit.SECONDS), "A が止められた例外を受けない")
            assertFalse(m.stateOf(a).isTerminal, "A がもう終わっている（競争の窓を作れない）")
            val b0 = pb.get()
            h.scheduler.tick()
            // 起こす順は A、B。A に予約が入ると、CPU が A のものになり、B が動けない
            eventually(10_000, "B が進まない（CPU が終わった A のものになった）") { pb.get() >= b0 + 999 }
            assertFalse(m.cpu.hasReservation(aRt), "止められた A に予約が入った")
            proceed.countDown()
            eventually(10_000, "A が終わらない") { m.stateOf(a).isTerminal }
            assertFalse(m.cpu.hasReservation(aRt))
            repeat(2) {
                val before = pb.get()
                h.scheduler.tick()
                eventually(10_000, "次の tick で B が進まない") { pb.get() >= before + 999 }
            }
            m.stop()
            eventually(10_000) { m.cpu.currentOwner == null && h.scheduler.permits.availablePermits() == permitsBefore }
        }
    }

    @Test
    fun `譲った後にCPUを取り直して燃料が残っていなければ、WAITING_FUELにして燃料を待ったものとして数える`() = timeout(limit) {
        Harness().use { h ->
            // ブロッキング型 2 本・1 tick に 4 スライス。最後のスライスを取れなかった側は、譲った後に CPU を取り直して燃料がないと分かり、
            // スレッドを持ったまま次の tick を待つ。そのときも RUNNING のままにせず、WAITING_FUEL にして fuelBlockingWaits に数える
            val m = h.machine(fuelPerTick = 400, fuelSlice = 100)
            val p1 = AtomicInteger()
            val p2 = AtomicInteger()
            val a = m.spawnTest(h.program(slowSpin(p1)), runDriver())
            val b = m.spawnTest(h.program(slowSpin(p2)), runDriver())
            // 2 本とも燃料待ちに着いてから tick を進める（作ったばかりのスレッドが動き出す前に、もう片方がその tick を使い切りうる）
            eventually(10_000) { m.fuelWaiterCount == 2 }
            h.scheduler.tick()
            eventually(10_000) { p1.get() > 0 && p2.get() > 0 }
            for (k in 1..4) {
                val blocking0 = h.scheduler.fuelBlockingWaits.get()
                val progress0 = p1.get() + p2.get()
                h.scheduler.tick()
                // その tick の燃料を使い切ると、燃料を待ったものとして数えられ、2 本とも燃料待ちの列で WAITING_FUEL になる
                // （起こされたスレッドの起き上がりが遅れると、その tick を片方が使い切り、遅れた側は前の tick の WAITING_FUEL のまま待ち直すので、
                // 数える回数は tick ごとに 1 か 2）
                eventuallyDescribe(10_000, {
                    "tick $k: 2 本とも WAITING_FUEL にならない（${m.stateOf(a)} / ${m.stateOf(b)}、" +
                        "燃料を待った回数: ${h.scheduler.fuelBlockingWaits.get() - blocking0}）"
                }) {
                    p1.get() + p2.get() > progress0 && h.scheduler.fuelBlockingWaits.get() - blocking0 >= 1 &&
                        m.fuelWaiterCount == 2 && m.lock.withLockValue { m.kernel.fuel <= 0 } &&
                        m.stateOf(a) == ProcessState.WAITING_FUEL && m.stateOf(b) == ProcessState.WAITING_FUEL
                }
            }
            m.stop()
        }
    }

    @Test
    fun `計算し続けるプロセスがいても、イベントで起きたプロセスは同じtickの中で動く`() = timeout(limit) {
        Harness().use { h ->
            // 1 tick の燃料は 1,000,000（計算するプロセスの 1 燃料が 50 µs なので、使い切るのに 50 秒かかる）。スライスは 100（5 ms）。
            // 起こされたプロセスが動くまでに使われる燃料は 1 tick の燃料よりずっと少ないはずで、テストのスレッドが止まっても tick をまたがない
            val m = h.machine(fuelPerTick = 1_000_000, fuelSlice = 100)
            val s = m.lock.withLockValue { ByteStream(16).also { m.kernel.objects.add(it) } }
            val woke = CountDownLatch(1)
            val waiterReady = CountDownLatch(1)
            m.spawnTest(
                h.program(hostCallModule()),
                runDriver(configure = { ctx, b ->
                    b.host(ctx) { c, _, _ ->
                        waiterReady.countDown()
                        c.block(listOf(WaitKey(s.id, WaitCondition.READABLE)))
                        woke.countDown()
                        LongArray(0)
                    }
                }),
            )
            h.scheduler.tick()
            assertTrue(waiterReady.await(10, TimeUnit.SECONDS))
            val spins = List(3) { AtomicInteger() }
            spins.forEach { m.spawnTest(h.program(slowSpin(it, micros = 50)), runDriver()) }
            h.scheduler.tick()
            eventually(10_000) { spins.all { it.get() > 0 } }
            val total = spins.sumOf { it.get() }
            m.lock.withLockValue { s.write(byteArrayOf(1), 0, 1, m.registry) }
            // tick を進めずに動く（スライスを使い切るたびに、CPU の待ち行列の後ろへ回る）
            assertTrue(woke.await(10, TimeUnit.SECONDS), "起こされたプロセスが同じ tick の中で動かない")
            val after = spins.sumOf { it.get() }
            assertTrue(after - total < 100_000, "1 tick の燃料のほとんどを使い切ってから動いた: ${after - total}")
            m.stop()
        }
    }

    @Test
    fun `手持ちを渡した後にtickが進んだら、待つ者がいなくても次の切れ目で許可を返して取り直す`() = timeout(limit) {
        Harness().use { h ->
            // 1 tick の燃料は大きく、tick の燃料は使い切らない。スライスだけが切れる
            val m = h.machine(fuelPerTick = 1_000_000, fuelSlice = 100)
            val permitsBefore = h.scheduler.permits.availablePermits()
            val progress = AtomicInteger()
            m.spawnTest(h.program(slowSpin(progress, micros = 20)), runDriver())
            h.scheduler.tick()
            eventually { progress.get() > 300 }
            val permits0 = h.scheduler.permitAcquisitions.get()
            // 同じ tick の中は、許可を持ったまま足し続ける
            val start = progress.get()
            eventually { progress.get() > start + 300 }
            assertEquals(permits0, h.scheduler.permitAcquisitions.get(), "同じ tick の中で許可を取り直した")
            assertTrue(h.scheduler.fuelSliceRenewals.get() > 0)
            // tick が進んだら、次のスライスの切れ目で許可と CPU を返して並び直す（公平な許可の列に戻る）。
            // 許可を持ち続けると、ほかの台が許可を取れない
            val yields0 = h.scheduler.fuelYields.get()
            repeat(3) {
                val before = h.scheduler.permitAcquisitions.get()
                h.scheduler.tick()
                eventually(10_000, "tick が進んでも許可を返さない") { h.scheduler.permitAcquisitions.get() > before }
            }
            assertTrue(h.scheduler.fuelYields.get() - yields0 >= 3, "譲った回数: ${h.scheduler.fuelYields.get() - yields0}")
            m.stop()
            eventually(10_000) { h.scheduler.permits.availablePermits() == permitsBefore }
        }
    }

    @Test
    fun `一時停止はスライスの単位で効く`() = timeout(limit) {
        Harness().use { h ->
            val progress = AtomicInteger()
            val m = h.machine(fuelPerTick = 1000, fuelSlice = 20)
            m.spawnTest(h.program(spinForever(progress, sleepMillis = 1)), runDriver())
            h.scheduler.tick()
            eventually { progress.get() > 0 }
            // 進み具合は、一時停止の印が立った後に読む（読んでから印が立つまでの間にテストのスレッドが止まると、
            // プロセスはその間もその場でスライスを足して進むので、実時間に左右される）。印が立った後に読めば、
            // テストのスレッドが止まっても、読んだ後に進む分は減るだけ
            val pausing = CompletableFuture.supplyAsync { h.scheduler.pauseAll(5_000) }
            eventually(10_000, "一時停止の印が立たない") { h.scheduler.isPaused }
            val start = progress.get()
            pausing.get(10, TimeUnit.SECONDS).use {
                assertTrue(progress.get() - start <= 20 + 1, "止まるまでに渡した燃料はスライスまで: ${progress.get() - start}")
                assertEquals(0, h.scheduler.activeProcesses)
            }
            val resumed = progress.get()
            h.scheduler.tick()
            eventually { progress.get() > resumed + 21 }
            m.stop()
        }
    }

    @Test
    fun `スライスを使い切って譲ったプロセスも、止めるとすぐ止まる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000, fuelSlice = 50)
            val p = List(2) { AtomicInteger() }
            val pids = p.map { m.spawnTest(h.program(slowSpin(it, micros = 5)), runDriver()) }
            h.scheduler.tick()
            eventually(10_000) { p.all { it.get() > 200 } }
            assertTrue(h.scheduler.fuelYields.get() > 0)
            m.stop()
            eventually(10_000) { pids.all { m.stateOf(it).isTerminal } }
            m.lock.withLockValue { assertTrue(m.kernel.fuel <= 1_000_000, "燃料の上限を超えた: ${m.kernel.fuel}") }
            assertEquals(0, h.scheduler.activeProcesses)
        }
    }

    @Test
    fun `待機点型とブロッキング型が混ざっても、交代して進み、許可を1回だけ返す`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1000, fuelSlice = 100)
            val permitsBefore = h.scheduler.permits.availablePermits()
            val blocking = AtomicInteger()
            val point = AtomicInteger()
            m.spawnTest(h.program(slowSpin(blocking)), runDriver())
            // 待機点型だが燃料切れを待ち点にしないモジュール: スレッドを持ったまま譲る
            m.spawnTest(
                h.program(slowSpin(point)),
                TestDriver(ProcessKind.WAIT_POINT) { ctx ->
                    ctx.instance.call("run")
                    StepResult.Exit(0)
                },
            )
            repeat(4) {
                h.scheduler.tick()
                Thread.sleep(40)
            }
            eventually(10_000) { blocking.get() > 300 && point.get() > 300 }
            m.stop()
            eventually(10_000) { h.scheduler.permits.availablePermits() == permitsBefore }
        }
    }
}
