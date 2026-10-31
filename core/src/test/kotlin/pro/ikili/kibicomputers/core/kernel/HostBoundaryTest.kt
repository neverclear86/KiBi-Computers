package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import java.nio.file.Paths
import java.time.Duration
import java.util.Collections
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** カーネルと接続層の境界（#44）: 環境、通知（状態・コンソール・ビープ）、メインスレッドでのホスト呼び出し */
class HostBoundaryTest {
    private val limit = Duration.ofSeconds(30)

    /** 通知を起きた順に文字列で記録する */
    private class RecordingHost : MachineHost {
        val log: MutableList<String> = Collections.synchronizedList(ArrayList())
        val threads: MutableSet<Thread> = Collections.synchronizedSet(HashSet())
        val console = java.io.ByteArrayOutputStream()

        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) {
            threads += Thread.currentThread()
            log += "state:$state"
        }

        override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
            threads += Thread.currentThread()
            log += "console:${String(bytes)}"
            synchronized(console) { console.write(bytes, 0, bytes.size) }
        }

        override fun onBeep(machine: Machine, frequencyHz: Int, durationMillis: Int) {
            threads += Thread.currentThread()
            log += "beep:$frequencyHz/$durationMillis"
        }
    }

    /** tick を呼ばずに動けるよう、マシンに燃料を入れておく */
    private fun Machine.giveFuel() = lock.withLockValue { kernel.fuel = config.fuelPerTick }

    private fun Harness.tickUntil(message: String = "条件がそろわない", condition: () -> Boolean) = eventually(10_000, message) {
        scheduler.tick()
        Thread.sleep(1)
        condition()
    }

    @Test
    fun `環境はプロセスから見え、既定はDEFAULT`() = timeout(limit) {
        Harness().use { h ->
            assertSame(MachineEnvironment.DEFAULT, h.machine().environment)
            val clock = object : MachineClock {
                override fun realtimeNanos(): Long = 1
                override fun monotonicNanos(): Long = 2
                override fun worldTicks(): Long = 3
            }
            val env = MachineEnvironment.builder()
                .clock(clock)
                .entropy { it.fill(7) }
                .disks { id -> if (id == "a") Paths.get("disk-a") else null }
                .storageDirectory(Paths.get("save"))
                .build()
            val m = h.machine(environment = env)
            val seen = AtomicReference<MachineEnvironment>()
            val pid = m.spawnTest(h.program(hostCallModule()), runDriver(configure = { ctx, b -> b.host(ctx) { c, _, _ -> seen.set(c.environment); LongArray(0) } }))
            h.tickUntil { m.stateOf(pid) == ProcessState.EXITED }
            assertSame(env, seen.get())
            assertEquals(3, env.clock.worldTicks())
            assertEquals(Paths.get("disk-a"), env.disks.resolve("a"))
            assertEquals(null, env.disks.resolve("b"))
            assertEquals(Paths.get("save"), env.storageDirectory)
            val bytes = ByteArray(4).also { env.entropy.fill(it) }
            assertArrayEquals(byteArrayOf(7, 7, 7, 7), bytes)
        }
    }

    @Test
    fun `既定の時計と乱数`() {
        val clock = MachineClock.SYSTEM
        val wall = System.currentTimeMillis() * 1_000_000
        assertTrue(Math.abs(clock.realtimeNanos() - wall) < 60_000_000_000L)
        val a = clock.monotonicNanos()
        assertTrue(clock.monotonicNanos() >= a)
        assertEquals(0, clock.worldTicks())
        val bytes = ByteArray(32)
        EntropySource.SECURE.fill(bytes)
        assertTrue(bytes.any { it != 0.toByte() })
        assertEquals(null, DiskResolver.NONE.resolve("x"))
        assertEquals(null, MachineEnvironment.DEFAULT.storageDirectory)
    }

    @Test
    fun `コンソールとビープはtickの中でメインスレッドから、起きた順に配られ、続いたコンソールはまとまる`() = timeout(limit) {
        Harness().use { h ->
            val host = RecordingHost()
            val m = h.machine(host = host)
            m.giveFuel()
            val pid = m.spawnTest(
                h.program(hostCallModule()),
                runDriver(configure = { ctx, b ->
                    b.host(ctx) { c, _, _ ->
                        c.writeConsole("he".toByteArray())
                        c.writeConsole("xllox".toByteArray(), 1, 3)
                        c.beep(440, 100)
                        c.writeConsole("!".toByteArray())
                        c.beep(1, 1_000_000)
                        LongArray(0)
                    }
                }),
            )
            eventually { m.stateOf(pid) == ProcessState.EXITED }
            // tick を呼ぶまで何も配られない
            assertTrue(host.log.isEmpty(), host.log.toString())
            h.scheduler.tick()
            assertEquals(
                listOf(
                    "state:RUNNING",
                    "console:hello",
                    "beep:440/100",
                    "console:!",
                    "beep:${Machine.MIN_BEEP_FREQUENCY_HZ}/${Machine.MAX_BEEP_DURATION_MILLIS}",
                    "state:STOPPED",
                ),
                host.log.toList(),
            )
            assertEquals(setOf(Thread.currentThread()), host.threads.toSet())
        }
    }

    @Test
    fun `配っていない出力が上限を超えた分は捨てる`() = timeout(limit) {
        Harness().use { h ->
            val host = RecordingHost()
            val m = h.machine(host = host)
            val max = Machine.MAX_PENDING_CONSOLE_BYTES.toInt()
            assertTrue(m.writeConsole(ByteArray(max - 10) { 'a'.code.toByte() }))
            assertFalse(m.writeConsole(ByteArray(30) { 'b'.code.toByte() }))
            assertEquals(20, m.droppedConsoleBytes.get())
            assertFalse(m.writeConsole(byteArrayOf(1)))
            repeat(Machine.MAX_PENDING_BEEPS) { assertTrue(m.beep(440, 10)) }
            assertFalse(m.beep(440, 10))
            assertEquals(1, m.droppedBeeps.get())
            h.scheduler.tick()
            assertEquals(max, synchronized(host.console) { host.console.size() })
            assertEquals(Machine.MAX_PENDING_BEEPS, host.log.count { it.startsWith("beep:") })
            // 配った後はまた積める
            assertTrue(m.writeConsole(ByteArray(max)))
            assertTrue(m.beep(440, 10))
        }
    }

    @Test
    fun `接続層の例外は後の通知とほかのマシンに伝わらない`() = timeout(limit) {
        Harness().use { h ->
            val log = Collections.synchronizedList(ArrayList<String>())
            val bad = h.machine(host = object : MachineHost {
                override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) {
                    log += "bad-state"
                }

                override fun onConsoleOutput(machine: Machine, bytes: ByteArray): Unit = throw IllegalStateException("接続層のバグ")

                override fun onBeep(machine: Machine, frequencyHz: Int, durationMillis: Int) {
                    log += "bad-beep"
                }
            })
            val good = h.machine(host = object : MachineHost {
                override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit

                override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
                    log += "good-console"
                }
            })
            bad.writeConsole("x".toByteArray())
            bad.beep(440, 1)
            good.writeConsole("y".toByteArray())
            good.beep(440, 1) // 既定の実装は何もしない
            h.scheduler.tick()
            assertEquals(listOf("bad-beep", "good-console"), log.toList())
        }
    }

    @Test
    fun `ホスト呼び出しはtickの中でメインスレッドで実行され、結果と例外がプロセスに返る`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val ranOn = AtomicReference<Thread>()
            val result = AtomicReference<Any>()
            val pid = m.spawnTest(
                h.program(hostCallModule()),
                runDriver(configure = { ctx, b ->
                    b.host(ctx) { c, _, _ ->
                        val v = c.callOnMainThread { ranOn.set(Thread.currentThread()); 42 }
                        val e = runCatching { c.callOnMainThread<Int> { throw IllegalArgumentException("ワールドで失敗") } }.exceptionOrNull()
                        result.set(listOf(v, e?.javaClass, e?.message))
                        LongArray(0)
                    }
                }),
            )
            h.tickUntil { m.stateOf(pid) == ProcessState.EXITED }
            assertSame(Thread.currentThread(), ranOn.get())
            assertEquals(listOf(42, IllegalArgumentException::class.java, "ワールドで失敗"), result.get())
            assertEquals(0, m.pendingHostCalls)
        }
    }

    @Test
    fun `1 tickに実行するホスト呼び出しは上限まで、残りは出した順に次のtickへ`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(maxHostCallsPerTick = 2)
            val order = Collections.synchronizedList(ArrayList<Int>())
            val futures = (1..5).map { i -> m.submitHostCall { order += i; i * 10 } }
            assertEquals(5, m.pendingHostCalls)
            h.scheduler.tick()
            assertEquals(listOf(1, 2), order.toList())
            h.scheduler.tick()
            assertEquals(listOf(1, 2, 3, 4), order.toList())
            h.scheduler.tick()
            assertEquals(listOf(1, 2, 3, 4, 5), order.toList())
            assertEquals(listOf(10, 20, 30, 40, 50), futures.map { it.get() })
            // 空になったマシンは次の tick で見ない
            val before = h.scheduler.tickVisitedMachines.get()
            h.scheduler.tick()
            assertEquals(before, h.scheduler.tickVisitedMachines.get())
            // 空になった後にまた出せる
            val again = m.submitHostCall { "again" }
            h.scheduler.tick()
            assertEquals("again", again.get())
        }
    }

    @Test
    fun `マシンを止めると実行していないホスト呼び出しは実行せずに終え、止めている途中は受け付けない`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val waiting = m.spawnTest(h.program(spinForever()), runDriver())
            eventually { m.state == MachineState.RUNNING }
            var ran = false
            val pending = m.submitHostCall { ran = true }
            // STOPPING の間に出された呼び出し（stop() の中のフックで、同じスレッドから出す）
            val during = AtomicReference<java.util.concurrent.CompletableFuture<Unit>>()
            h.scheduler.hooks.stopBeforeInterrupt = { machine, _ -> during.compareAndSet(null, machine.submitHostCall { ran = true }) }
            m.stop()
            for (f in listOf(pending, during.get())) {
                val e = runCatching { f.get() }.exceptionOrNull()
                assertTrue(e is ExecutionException && e.cause is ProcessKilledException, e.toString())
            }
            h.scheduler.tick()
            assertFalse(ran)
            assertTrue(m.awaitTermination(5_000))
            assertEquals(ProcessState.KILLED, m.stateOf(waiting))
        }
    }

    @Test
    fun `ホスト呼び出しを待っているプロセスはstopでKILLEDになる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            m.giveFuel()
            val pid = m.spawnTest(
                h.program(hostCallModule()),
                runDriver(configure = { ctx, b -> b.host(ctx) { c, _, _ -> c.callOnMainThread { Unit }; LongArray(0) } }),
            )
            eventually { m.stateOf(pid) == ProcessState.WAITING_HOST }
            m.stop()
            assertTrue(m.awaitTermination(5_000))
            assertEquals(ProcessState.KILLED, m.stateOf(pid))
            assertEquals(0, m.pendingHostCalls)
            assertEquals(h.scheduler.config.maxConcurrentMachines, h.scheduler.permits.availablePermits())
        }
    }

    @Test
    fun `デバイスが変わったことはどのスレッドからでも知らせられる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            assertEquals(0, m.devicesGeneration)
            val threads = List(4) { Thread { repeat(100) { m.notifyDevicesChanged() } } }
            threads.forEach { it.start() }
            threads.forEach { it.join() }
            assertEquals(400, m.devicesGeneration)
        }
    }
}
