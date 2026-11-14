package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.runtime.FakeModule
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
            // tick ごとの内訳は見ない（ブロッキング型は、起こされたスレッドが CPU の待ち行列に着くまでの間「待つ者」に数えないので、
            // 起き上がりが遅れた tick は片方に偏りうる）。5 tick の合計で、どちらも独り占めされずに進んだことを見る
            val da = p1.get() - a0
            val db = p2.get() - b0
            assertTrue(da >= 1000 && db >= 1000, "偏っている: $da / $db")
            assertTrue(h.scheduler.fuelYields.get() - yields0 >= 6, "譲った回数: ${h.scheduler.fuelYields.get() - yields0}")
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
            val start = progress.get()
            h.scheduler.pauseAll(5_000).use {
                assertTrue(progress.get() - start <= 20 + 1, "止まるまでに渡した燃料はスライスまで: ${progress.get() - start}")
                assertEquals(0, h.scheduler.activeProcesses)
            }
            h.scheduler.tick()
            eventually { progress.get() > start + 21 }
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
