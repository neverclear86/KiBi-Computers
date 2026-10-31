package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.runtime.FakeModule
import pro.ikili.kibicomputers.core.runtime.WasmInstance
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/** 「tick ごとに配る」素直な実装（参照の実装） */
private class ReferenceFuel(val perTick: Long) {
    var fuel = 0L

    fun tick() {
        fuel = minOf(fuel, 0) + perTick
    }

    fun take(): Long {
        if (fuel <= 0) return 0
        val g = fuel
        fuel = 0
        return g
    }

    fun giveBack(k: Long, r: Long) {
        fuel += if (k == 0L) r else minOf(r, 0)
    }
}

class FuelAccountTest {
    private val perTick = 100L

    private fun account(): Pair<KernelState, FuelAccount> {
        val state = KernelState(8)
        return state to FuelAccount(state, perTick, 0)
    }

    @Test
    fun `遅延補充はtickごとに配るのと同じ値になる`() {
        for (start in listOf(perTick, 0L, -perTick / 2, -3 * perTick, -1L)) {
            for (k in listOf(0L, 1L, 2L, 3L, 5L, 1_000_000_000L)) {
                var expected = start
                repeat(minOf(k, 100).toInt()) { expected = minOf(expected, 0) + perTick }
                assertEquals(expected, FuelAccount.refilled(start, k, perTick), "fuel=$start k=$k")
            }
        }
    }

    @Test
    fun `CPUを持ったままtickが進んでから返す`() {
        for (k in listOf(0L, 1L, 2L, 3L, 5L)) {
            for (r in listOf(40L, 0L, -30L, -250L)) {
                for (j in listOf(0L, 1L, 3L)) {
                    val (state, acc) = account()
                    acc.refill(1)
                    assertEquals(perTick, acc.take(1))
                    assertEquals(0, state.fuel)
                    acc.giveBack(1 + k, r)
                    val expected = if (k == 0L) r else perTick + minOf(r, 0)
                    assertEquals(expected, state.fuel, "k=$k r=$r")
                    // 続けて j tick 後に取ったときの grant
                    val ref = ReferenceFuel(perTick).apply { fuel = expected }
                    repeat(j.toInt()) { ref.tick() }
                    assertEquals(ref.take(), acc.take(1 + k + j), "k=$k r=$r j=$j")
                }
            }
        }
    }

    @Test
    fun `燃料切れの処理のreleaseCpu(remaining)でも同じ`() {
        for (k in listOf(0L, 1L, 2L, 5L)) {
            for (r in listOf(-5L, -250L)) {
                val (state, acc) = account()
                acc.take(1)
                acc.giveBack(1 + k, r)
                assertEquals(if (k == 0L) r else perTick + r, state.fuel)
            }
        }
    }

    @Test
    fun `例外で抜けたときはCPUを持っているときだけ補充し、何も書き戻さない`() {
        val (state, acc) = account()
        acc.take(3)
        acc.onException(5, holdsCpu = false)
        assertEquals(0, state.fuel)
        assertEquals(3, acc.lastRefillTick)
        acc.onException(5, holdsCpu = true)
        assertEquals(perTick, state.fuel)
        assertEquals(5, acc.lastRefillTick)
    }

    @Test
    fun `素直な繰り返しの実装と10000手比べる`() {
        val random = Random(43)
        val (state, acc) = account()
        val ref = ReferenceFuel(perTick)
        var now = 0L

        class Sim {
            var holds = false
            var hand = 0L
            var grantTick = 0L
        }
        val procs = List(3) { Sim() }
        var heldOverTwoTicks = 0
        var nonHolderExceptions = 0
        repeat(10_000) { step ->
            val holder = procs.firstOrNull { it.holds }
            when (random.nextInt(8)) {
                0, 1 -> {
                    now++
                    ref.tick()
                }
                2 -> if (holder == null) {
                    val p = procs[random.nextInt(procs.size)]
                    val g = acc.take(now)
                    assertEquals(ref.take(), g, "手 $step: grant")
                    if (g > 0) {
                        p.holds = true
                        p.hand = g
                        p.grantTick = now
                    }
                }
                3 -> holder?.let { it.hand -= random.nextLong(0, 300) }
                4 -> holder?.let {
                    if (now - it.grantTick >= 2) heldOverTwoTicks++
                    acc.giveBack(now, it.hand)
                    ref.giveBack(now - it.grantTick, it.hand)
                    it.holds = false
                }
                5 -> holder?.let {
                    val remaining = -random.nextLong(1, 300)
                    if (now - it.grantTick >= 2) heldOverTwoTicks++
                    acc.giveBack(now, remaining)
                    ref.giveBack(now - it.grantTick, remaining)
                    it.holds = false
                }
                6 -> holder?.let {
                    acc.onException(now, holdsCpu = true)
                    it.holds = false
                }
                7 -> {
                    // CPU を持たないプロセスが例外で抜ける（別のプロセスが CPU を持っていることもある）
                    val p = procs.firstOrNull { !it.holds } ?: return@repeat
                    val fuelBefore = state.fuel
                    val tickBefore = acc.lastRefillTick
                    acc.onException(now, holdsCpu = p.holds)
                    assertEquals(fuelBefore, state.fuel, "手 $step: CPU を持たない例外で fuel が変わった")
                    assertEquals(tickBefore, acc.lastRefillTick)
                    if (holder != null) nonHolderExceptions++
                }
            }
            // CPU を持っていれば、持ったまま tick を2つ以上進めることがある
            if (holder != null && random.nextInt(20) == 0) {
                now += 2
                ref.tick()
                ref.tick()
            }
            assertEquals(ref.fuel, acc.estimate(now), "手 $step: 見積もり")
        }
        assertTrue(heldOverTwoTicks > 50, "CPU を持ったまま 2 tick 以上: $heldOverTwoTicks")
        assertTrue(nonHolderExceptions > 50, "別のプロセスが CPU を持つ間の例外: $nonHolderExceptions")
    }

    // ---- プロセスを動かして確かめる ----

    private fun stream(machine: Machine): ByteStream = machine.lock.withLockValue { ByteStream(16).also { machine.kernel.objects.add(it) } }

    private fun write(machine: Machine, s: ByteStream) = machine.lock.withLockValue { s.write(byteArrayOf(1), 0, 1, machine.registry) }

    @Test
    fun `CPUを取ると手持ちが渡り、返すと残りが書き戻る`() {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 100)
            val s = stream(m)
            val seen = AtomicReference<List<Long>>()
            val again = AtomicLong(-1)
            val driver = runDriver(configure = { ctx, b ->
                b.host(ctx) { c, caller, _ ->
                    seen.set(listOf(caller.fuel, c.withKernelLock { c.kernel.fuel }))
                    c.chargeFuel(30)
                    c.block(listOf(WaitKey(s.id, WaitCondition.READABLE)))
                    again.set(caller.fuel)
                    LongArray(0)
                }
            })
            val pid = m.spawnTest(h.program(hostCallModule()), driver)
            h.scheduler.tick()
            eventually { m.stateOf(pid) == ProcessState.WAITING_EVENT }
            assertEquals(listOf(100L, 0L), seen.get())
            m.lock.withLockValue {
                assertEquals(70, m.kernel.fuel)
                assertEquals(0, m.rt(pid).fuelHolder!!.fuel, "待っている間の手持ちは 0")
            }
            write(m, s)
            eventually { m.stateOf(pid).isTerminal }
            assertEquals(70, again.get())
            assertEquals(70, m.lock.withLockValue { m.kernel.fuel })
        }
    }

    @Test
    fun `例外で抜けたときはグローバルを信じない`() {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 100)
            val trapping = FakeModule.builder().fuelMetered().export("run", VOID) { ctx, _ ->
                ctx.spinLocal(10, trapAtEnd = true)
                LongArray(0)
            }.build()
            val a = m.spawnTest(h.program(trapping), runDriver())
            h.scheduler.tick()
            eventually { m.stateOf(a).isTerminal }
            assertState(ProcessState.CRASHED, m, a)
            assertEquals(0, m.lock.withLockValue { m.kernel.fuel }, "古いグローバル（100）を足さない")
            val grant = AtomicLong(-1)
            val b = m.spawnTest(h.program(hostCallModule()), runDriver(configure = { ctx, builder -> builder.host(ctx) { _, caller, _ -> grant.set(caller.fuel); LongArray(0) } }))
            Thread.sleep(50)
            assertEquals(-1, grant.get(), "同じ tick の中では燃料を受け取れない")
            h.scheduler.tick()
            eventually { m.stateOf(b).isTerminal }
            assertEquals(100, grant.get())
        }
    }

    @Test
    fun `燃料切れの処理ではグローバルを読まない`() {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 100)
            val result = AtomicLong()
            val global = AtomicLong()
            val module = FakeModule.builder().fuelMetered().export("run", VOID) { ctx, _ ->
                ctx.self.fuel = 100 // 古い正の値
                result.set(ctx.callFuelHandler(-5))
                global.set(ctx.self.fuel)
                LongArray(0)
            }.build()
            val pid = m.spawnTest(h.program(module), runDriver())
            h.scheduler.tick()
            eventually { m.stateOf(pid) == ProcessState.WAITING_FUEL }
            assertEquals(-5, m.lock.withLockValue { m.kernel.fuel }, "-5 だけが書き戻る")
            h.scheduler.tick()
            eventually { m.stateOf(pid).isTerminal }
            assertEquals(95, result.get())
            assertEquals(100, global.get(), "グローバルは書き換えない")
        }
    }

    @Test
    fun `CPUを持たないプロセスの例外はマシンの燃料に触らない`() {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 100)
            val s = stream(m)
            val permitsBefore = h.scheduler.permits.availablePermits()
            // A: block で CPU を返して待つ
            val a = m.spawnTest(
                h.program(hostCallModule()),
                runDriver(configure = { ctx, b ->
                    b.host(ctx) { c, _, _ ->
                        c.block(listOf(WaitKey(s.id, WaitCondition.READABLE)))
                        LongArray(0)
                    }
                }),
            )
            h.scheduler.tick()
            eventually { m.stateOf(a) == ProcessState.WAITING_EVENT }
            h.scheduler.tick()
            h.scheduler.tick()
            val t = h.scheduler.now
            val inHost = CountDownLatch(1)
            val release = CountDownLatch(1)
            val b = m.spawnTest(
                h.program(hostCallModule()),
                runDriver(configure = { ctx, builder ->
                    builder.host(ctx) { c, _, _ ->
                        inHost.countDown()
                        release.await()
                        c.chargeFuel(70)
                        LongArray(0)
                    }
                }),
            )
            assertTrue(inHost.await(5, TimeUnit.SECONDS))
            m.lock.withLockValue {
                assertEquals(0, m.kernel.fuel)
                assertEquals(t, m.fuelAccount.lastRefillTick)
            }
            h.scheduler.tick()
            // A だけを止める（1プロセスの kill は #46。ここでは印と割り込みでまねる）
            m.lock.withLockValue {
                val rt = m.rt(a)
                rt.killed = true
                rt.thread!!.interrupt()
            }
            eventually { m.stateOf(a).isTerminal }
            assertState(ProcessState.KILLED, m, a)
            m.lock.withLockValue {
                assertEquals(0, m.kernel.fuel, "補充されない")
                assertEquals(t, m.fuelAccount.lastRefillTick)
            }
            assertEquals(permitsBefore - 1, h.scheduler.permits.availablePermits(), "A は許可を返さない（持っていない）")
            release.countDown()
            eventually { m.stateOf(b).isTerminal }
            assertEquals(100, m.lock.withLockValue { m.kernel.fuel }, "100 + min(30, 0)")
            assertEquals(permitsBefore, h.scheduler.permits.availablePermits())
        }
    }

    @Test
    fun `手持ちを持つインスタンスの差し替えとinstantiateでの受け渡し`() {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 100)
            val second = h.program(FakeModule.builder().fuelMetered().start { ctx, _ -> ctx.step(30); LongArray(0) }.build())
            val record = AtomicReference<List<Long>>()
            val driver = runDriver(configure = { ctx, b ->
                b.host(ctx) { c, a, _ ->
                    val bInst = c.instantiate(second.module) { }
                    val afterInstantiate = listOf(a.fuel, bInst.fuel)
                    c.switchFuelHolder(bInst)
                    val afterSwitch = listOf(a.fuel, bInst.fuel)
                    c.chargeFuel(20)
                    c.switchFuelHolder(a)
                    assertSame(a, c.fuelHolder)
                    record.set(afterInstantiate + afterSwitch + listOf(a.fuel, bInst.fuel))
                    LongArray(0)
                }
            })
            val pid = m.spawnTest(h.program(hostCallModule()), driver)
            h.scheduler.tick()
            eventually { m.stateOf(pid).isTerminal }
            assertEquals(listOf(70L, 0L, 0L, 70L, 50L, 0L), record.get())
            assertEquals(50, m.lock.withLockValue { m.kernel.fuel })
        }
    }

    @Test
    fun `作っている途中は仮のholderから引く`() {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 100)
            val module = FakeModule.builder().fuelMetered().import("env", "h", VOID)
                .start { ctx, _ ->
                    ctx.step(5)
                    ctx.callImport("env", "h")
                }.build()
            val after = AtomicLong(-1)
            val driver = TestDriver(ProcessKind.BLOCKING, onConfigure = { ctx, b -> b.host(ctx) { c, _, _ -> c.chargeFuel(20); LongArray(0) } }) { ctx ->
                after.set(ctx.fuelHolder.fuel)
                assertSame(ctx.instance, ctx.fuelHolder)
                StepResult.Exit(0)
            }
            val pid = m.spawnTest(h.program(module), driver)
            h.scheduler.tick()
            eventually { m.stateOf(pid).isTerminal }
            assertState(ProcessState.EXITED, m, pid)
            assertEquals(75, after.get())
        }
    }

    @Test
    fun `start関数の中のblockでは仮のholderが0になり、待機点ではない`() {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 100)
            val s = stream(m)
            val module = FakeModule.builder().fuelMetered().import("env", "h", VOID)
                .start { ctx, _ -> ctx.callImport("env", "h") }.build()
            val driver = TestDriver(ProcessKind.WAIT_POINT, onConfigure = { ctx, b ->
                b.host(ctx) { c, _, _ ->
                    c.block(listOf(WaitKey(s.id, WaitCondition.READABLE)))
                    LongArray(0)
                }
            }) { StepResult.Exit(0) }
            val pid = m.spawnTest(h.program(module), driver)
            h.scheduler.tick()
            eventually { m.stateOf(pid) == ProcessState.WAITING_EVENT }
            m.lock.withLockValue {
                val provisional = m.rt(pid).loading!!.provisional!!
                assertEquals(0, provisional.fuel)
                assertEquals(100, m.kernel.fuel)
            }
            assertFalse(m.isAtWaitPoint)
            write(m, s)
            eventually { m.stateOf(pid).isTerminal }
            assertState(ProcessState.EXITED, m, pid)
        }
    }

    @Test
    fun `start関数の中で燃料が尽きても仮のholderで次のtickに続く`() {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 100)
            val second = h.program(FakeModule.builder().fuelMetered().start { ctx, _ -> ctx.step(150); LongArray(0) }.build())
            val holderDuring = AtomicLong(Long.MIN_VALUE)
            val driver = runDriver(configure = { ctx, b ->
                b.host(ctx) { c, a, _ ->
                    c.instantiate(second.module) { }
                    holderDuring.set(a.fuel)
                    LongArray(0)
                }
            })
            val pid = m.spawnTest(h.program(hostCallModule()), driver)
            h.scheduler.tick()
            eventually { m.stateOf(pid) == ProcessState.WAITING_FUEL }
            m.lock.withLockValue { assertEquals(0, m.rt(pid).fuelHolder!!.fuel, "元の holder は書き換えない") }
            h.scheduler.tick()
            eventually { m.stateOf(pid).isTerminal }
            assertState(ProcessState.EXITED, m, pid)
            assertEquals(50, holderDuring.get())
        }
    }

    @Test
    fun `照合の不整合ではマシンがCRASHED`() {
        for (duringLoading in listOf(true, false)) {
            Harness().use { h ->
                val m = h.machine(fuelPerTick = 100)
                val stranger: WasmInstance = emptyModule().instantiate(WasmInstanceConfig.EMPTY)
                val module = if (duringLoading) {
                    FakeModule.builder().fuelMetered().import("env", "h", VOID).start { ctx, _ -> ctx.callImport("env", "h") }.build()
                } else {
                    hostCallModule()
                }
                val driver = runDriver(configure = { ctx, b ->
                    val other = ctx.hostFunction { _, _, _ -> LongArray(0) }
                    b.host(ctx) { _, _, _ -> other.call(stranger, LongArray(0)) }
                })
                val pid = m.spawnTest(h.program(module), driver)
                h.scheduler.tick()
                eventually { m.stateOf(pid).isTerminal }
                assertEquals(MachineState.CRASHED, m.state)
            }
        }
    }

    @Test
    fun `instantiateの中のトラップではCPUと許可を返さず、燃料を捨てる`() {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 100)
            val trapping = h.program(FakeModule.builder().fuelMetered().start { ctx, _ -> ctx.trap(pro.ikili.kibicomputers.core.runtime.WasmTrapKind.UNREACHABLE) }.build())
            val observed = AtomicReference<List<Any>>()
            val permits = h.scheduler.permits
            val before = permits.availablePermits()
            val driver = runDriver(configure = { ctx, b ->
                b.host(ctx) { c, a, _ ->
                    assertThrows<WasmTrapException> { c.instantiate(trapping.module) { } }
                    val rt = m.rt(c.pid)
                    observed.set(listOf(rt.holdsCpu, rt.holdsPermit, permits.availablePermits(), a.fuel, c.withKernelLock { c.kernel.fuel }))
                    // 捕まえずに投げる
                    c.instantiate(trapping.module) { }
                    LongArray(0)
                }
            })
            val pid = m.spawnTest(h.program(hostCallModule()), driver)
            h.scheduler.tick()
            eventually { m.stateOf(pid).isTerminal }
            assertEquals(listOf<Any>(true, true, before - 1, 0L, 0L), observed.get())
            assertState(ProcessState.CRASHED, m, pid)
            assertEquals(before, permits.availablePermits(), "許可は1回だけ返る")
        }
    }

    @Test
    fun `作っている途中の入れ子のinstantiateとswitchFuelHolderはIllegalStateException`() {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 100)
            val inner = h.program(emptyModule())
            val errors = AtomicReference<List<Throwable?>>()
            val module = FakeModule.builder().fuelMetered().import("env", "h", VOID).start { ctx, _ -> ctx.callImport("env", "h") }.build()
            val driver = runDriver(configure = { ctx, b ->
                b.host(ctx) { c, caller, _ ->
                    val e1 = runCatching { c.instantiate(inner.module) { } }.exceptionOrNull()
                    val e2 = runCatching { c.switchFuelHolder(caller) }.exceptionOrNull()
                    errors.set(listOf(e1, e2))
                    LongArray(0)
                }
            })
            val pid = m.spawnTest(h.program(module), TestDriver(ProcessKind.BLOCKING, onConfigure = { c, b -> driver.configure(c, b) }) { StepResult.Exit(0) })
            h.scheduler.tick()
            eventually { m.stateOf(pid).isTerminal }
            val (e1, e2) = errors.get()
            assertTrue(e1 is IllegalStateException, "$e1")
            assertTrue(e2 is IllegalStateException, "$e2")
        }
    }
}
