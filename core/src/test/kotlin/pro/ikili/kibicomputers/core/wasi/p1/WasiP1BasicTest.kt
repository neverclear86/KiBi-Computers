package pro.ikili.kibicomputers.core.wasi.p1

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.EntropySource
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.MachineClock
import pro.ikili.kibicomputers.core.kernel.MachineEnvironment
import pro.ikili.kibicomputers.core.kernel.TestDriver
import pro.ikili.kibicomputers.core.kernel.eventually
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.stateOf
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.withLockValue
import pro.ikili.kibicomputers.core.runtime.FakeModule
import pro.ikili.kibicomputers.core.runtime.WasmFunctionType
import pro.ikili.kibicomputers.core.runtime.WasmMemory
import java.time.Duration
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** WASI p1 の args・environ・clock・random・sched_yield・proc_exit（#69）。ダミーのモジュールから import を直接呼ぶ */
class WasiP1BasicTest {
    private val limit = Duration.ofSeconds(30)

    /** 手で進める時計 */
    private class ManualClock : MachineClock {
        val realtime = AtomicLong(1_700_000_000_000_000_000L)
        val monotonic = AtomicLong(-5_000_000_000L)

        override fun realtimeNanos(): Long = realtime.get()

        override fun monotonicNanos(): Long = monotonic.get()

        override fun worldTicks(): Long = 0
    }

    private fun forwarding(vararg names: String): FakeModule {
        val b = FakeModule.builder().fuelMetered().memory(1)
        for (name in names) {
            val type = WasiP1.typeOf(name)!!
            b.import(P1.MODULE, name, type)
            b.export(name, type) { c, args -> c.callImport(P1.MODULE, name, *args) }
        }
        return b.build()
    }

    private fun interface Call {
        operator fun invoke(name: String, vararg args: Long): Int
    }

    private fun runToEnd(h: Harness, m: Machine, module: FakeModule, options: SpawnOptions = SpawnOptions(), body: (WasmMemory, Call) -> Unit) {
        val errors = Collections.synchronizedList(ArrayList<Throwable>())
        val driver = TestDriver(ProcessKind.BLOCKING, onConfigure = { ctx, b -> WasiP1.register(ctx, b, module, true) }) { ctx ->
            try {
                body(ctx.instance.memory!!, Call { name, args -> ctx.instance.call(name, *args)[0].toInt() })
            } catch (t: Throwable) {
                errors += t
            }
            StepResult.Exit(0)
        }
        val pid = m.spawn(h.program(module), driver, options)
        eventually(10_000) {
            h.scheduler.tick()
            m.stateOf(pid).isTerminal
        }
        errors.firstOrNull()?.let { throw it }
        assertEquals(ProcessState.EXITED, m.stateOf(pid))
    }

    private fun WasmMemory.cString(at: Int): String {
        var end = at
        while (readU8(end) != 0) end++
        return String(read(at, end - at), Charsets.UTF_8)
    }

    @Test
    fun `引数と環境変数をポインタの並びとNULで終わる文字列で書く`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            val module = forwarding("args_sizes_get", "args_get", "environ_sizes_get", "environ_get")
            val opts = SpawnOptions(args = listOf("prog", "é"), env = linkedMapOf("A" to "1", "B" to ""))
            runToEnd(h, m, module, opts) { mem, call ->
                assertEquals(0, call("args_sizes_get", 100, 104))
                assertEquals(2, mem.readI32(100))
                assertEquals(5 + 3, mem.readI32(104))
                assertEquals(0, call("args_get", 200, 300))
                assertEquals(300, mem.readI32(200))
                assertEquals(305, mem.readI32(204))
                assertEquals("prog", mem.cString(300))
                assertEquals("é", mem.cString(305))

                assertEquals(0, call("environ_sizes_get", 100, 104))
                assertEquals(2, mem.readI32(100))
                assertEquals(4 + 3, mem.readI32(104))
                assertEquals(0, call("environ_get", 400, 500))
                assertEquals("A=1", mem.cString(mem.readI32(400)))
                assertEquals("B=", mem.cString(mem.readI32(404)))

                // 範囲外は書かずに FAULT
                assertEquals(Errno.FAULT.code, call("args_get", 200, 65536 - 4))
                assertEquals(Errno.FAULT.code, call("args_sizes_get", 65536 - 2, 104))
            }
        }
    }

    @Test
    fun `時計は環境の時計から読み、単調時計はマシンを作ったときが0`() = timeout(limit) {
        Harness().use { h ->
            val clock = ManualClock()
            val m = h.machine(fuelPerTick = 1_000_000, environment = MachineEnvironment.builder().clock(clock).build())
            clock.monotonic.addAndGet(3_000)
            val module = forwarding("clock_time_get", "clock_res_get")
            runToEnd(h, m, module) { mem, call ->
                assertEquals(0, call("clock_time_get", P1.CLOCK_REALTIME.toLong(), 1, 100))
                assertEquals(clock.realtime.get(), mem.readI64(100))
                assertEquals(0, call("clock_time_get", P1.CLOCK_MONOTONIC.toLong(), 1, 100))
                assertEquals(3_000L, mem.readI64(100))
                assertEquals(0, call("clock_time_get", P1.CLOCK_PROCESS_CPUTIME.toLong(), 1, 100))
                assertEquals(0L, mem.readI64(100))
                clock.monotonic.addAndGet(500)
                assertEquals(0, call("clock_time_get", P1.CLOCK_THREAD_CPUTIME.toLong(), 1, 100))
                assertEquals(500L, mem.readI64(100))
                assertEquals(0, call("clock_res_get", P1.CLOCK_MONOTONIC.toLong(), 200))
                assertEquals(WasiP1Basic.CLOCK_RESOLUTION_NANOS, mem.readI64(200))

                assertEquals(Errno.INVAL.code, call("clock_time_get", 4, 1, 100))
                assertEquals(Errno.INVAL.code, call("clock_res_get", -1, 200))
                assertEquals(Errno.FAULT.code, call("clock_time_get", P1.CLOCK_REALTIME.toLong(), 1, 65536 - 4))
            }
        }
    }

    @Test
    fun `乱数は環境の乱数の元で埋め、範囲外はFAULT`() = timeout(limit) {
        Harness().use { h ->
            val counter = AtomicLong()
            val entropy = EntropySource { bytes -> for (i in bytes.indices) bytes[i] = (counter.incrementAndGet() and 0x7f).toByte() }
            val m = h.machine(fuelPerTick = 1_000_000, environment = MachineEnvironment.builder().entropy(entropy).build())
            val module = forwarding("random_get", "sched_yield")
            runToEnd(h, m, module) { mem, call ->
                assertEquals(0, call("random_get", 100, 4))
                assertArrayEquals(byteArrayOf(1, 2, 3, 4), mem.read(100, 4))
                assertEquals(0, call("random_get", 100, 0))
                // 一度に埋める大きさ（64KiB）と同じ大きさも埋められる
                assertEquals(0, call("random_get", 0, 65536))
                assertEquals(Errno.FAULT.code, call("random_get", 65536 - 2, 4))
                assertEquals(Errno.FAULT.code, call("random_get", 0, -1))
                assertEquals(0, call("sched_yield"))
            }
        }
    }

    @Test
    fun `proc_exitは呼び出しを抜けて終了コードになる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            val module = FakeModule.builder().fuelMetered().memory(1)
                .import(P1.MODULE, "proc_exit", WasiP1.typeOf("proc_exit")!!)
                .export(WasiP1Driver.START, WasmFunctionType.of(emptyList(), emptyList())) { c, _ ->
                    c.callImport(P1.MODULE, "proc_exit", 7)
                    throw AssertionError("proc_exit から戻った")
                }
                .build()
            val program = h.program(module)
            val pid = m.spawn(program, WasiP1Driver(program.module), SpawnOptions())
            eventually(10_000) {
                h.scheduler.tick()
                m.stateOf(pid).isTerminal
            }
            assertEquals(ProcessState.EXITED, m.stateOf(pid))
            assertEquals(7, m.lock.withLockValue { m.kernel.processes[pid]!!.exitCode })
        }
    }
}
