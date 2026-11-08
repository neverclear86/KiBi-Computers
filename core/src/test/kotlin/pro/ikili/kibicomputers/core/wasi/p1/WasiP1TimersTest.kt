package pro.ikili.kibicomputers.core.wasi.p1

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.MachineClock
import pro.ikili.kibicomputers.core.kernel.MachineEnvironment
import pro.ikili.kibicomputers.core.kernel.eventually
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.io.ReadResult
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessRuntime
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.rt
import pro.ikili.kibicomputers.core.kernel.stateOf
import pro.ikili.kibicomputers.core.kernel.withLockValue
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * 独自拡張 `kibi:kernel/timers`・`kibi:computer/clock`（#426）の p1 の import を、kibi_timers.c（実際の Endive）とカーネルで確かめる。
 * どれも待機点型（待機点への変換をかけたもの）とブロッキング型の両方で動かす
 */
class WasiP1TimersTest {
    private val bytes: ByteArray by lazy {
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/kibi-timers-wasi.wasm")) { "kibi-timers-wasi.wasm がリソースにない" }
            .use { it.readBytes() }
    }

    private val asyncProgram: Program by lazy { Program.loadMetered(EndiveRuntime(), bytes, null, WasiP1.WAIT_IMPORTS) }
    private val plainProgram: Program by lazy { Program.load(EndiveRuntime(), bytes) }

    /** JVM の時計で、ゲーム内時間だけ決まった値を返す */
    private val clock = object : MachineClock {
        override fun realtimeNanos(): Long = MachineClock.SYSTEM.realtimeNanos()

        override fun monotonicNanos(): Long = MachineClock.SYSTEM.monotonicNanos()

        override fun worldTicks(): Long = WORLD_TICKS
    }

    private inner class Run(val h: Harness, val m: Machine, async: Boolean, args: List<String>) {
        private val output: Int = m.lock.withLockValue { m.kernel.objects.add(ByteStream(64 * 1024)) }
        private val text = StringBuilder()
        val pid: Int
        val rt: ProcessRuntime

        init {
            val program = if (async) asyncProgram else plainProgram
            val options = SpawnOptions(
                allowUnmetered = !program.module.isFuelMetered,
                args = listOf("kibi-timers") + args,
                stdio = StdioSpec(StdioSource.Closed, StdioSource.Object(output, HandleRights.WRITE), StdioSource.Closed),
            )
            pid = m.spawn(program, WasiP1Driver(program.module), options)
            rt = m.rt(pid)
            assertEquals(if (async) ProcessKind.WAIT_POINT else ProcessKind.BLOCKING, rt.driver.kind)
        }

        fun drain(): String = m.lock.withLockValue {
            val s = m.kernel.objects[output] as ByteStream
            val buf = ByteArray(s.available)
            if (buf.isNotEmpty()) s.read(buf, 0, buf.size, m.wakeSink) as? ReadResult.Bytes
            text.append(String(buf, Charsets.UTF_8))
            text.toString()
        }

        fun waitUntil(message: String, condition: () -> Boolean) = eventually(60_000, message) {
            h.scheduler.tick()
            drain()
            condition()
        }

        fun finish(): String {
            waitUntil("終わらない: $text") { m.stateOf(pid).isTerminal }
            return drain()
        }

        /** 正常に終わるまで動かして出力を返す */
        fun exited(): String {
            val out = finish()
            assertEquals(ProcessState.EXITED, m.stateOf(pid), "$out ${rt.lastError}")
            assertEquals(0, m.lock.withLockValue { m.kernel.processes[pid]!!.exitCode }, out)
            return out
        }
    }

    private fun run(async: Boolean, vararg args: String, block: Run.() -> Unit) = timeout(Duration.ofSeconds(90)) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 10_000_000, environment = MachineEnvironment.builder().clock(clock).build())
            Run(h, m, async, args.toList()).block()
        }
    }

    private fun both(vararg args: String, block: Run.() -> Unit) {
        run(true, *args, block = block)
        run(false, *args, block = block)
    }

    @Test
    fun `set-afterのタイマーをpoll_oneoffのFD_READで待ち、readで回数を取り出す`() = both("once", "100") {
        val out = exited()
        assertTrue("idle 0\n" in out, out)
        assertTrue("once 1 0 late 1\n" in out, out)
    }

    @Test
    fun `待機点型はタイマーを待つ間スレッドを持たない`() = run(true, "once", "1500") {
        waitUntil("待たない: ${drain()}") { "armed\n" in drain() && m.stateOf(pid) == ProcessState.WAITING_EVENT || m.stateOf(pid).isTerminal }
        assertEquals(ProcessState.WAITING_EVENT, m.stateOf(pid), drain())
        eventually(5_000, "スレッドが外れない") { rt.thread == null }
        assertTrue(h.scheduler.dedicatedThreads.isEmpty(), "専用スレッドがある")
        val out = exited()
        assertTrue("once 1 0 late 1\n" in out, out)
    }

    @Test
    fun `繰り返しのタイマーを数え、cancelで止める`() = both("repeat") {
        val out = exited()
        assertTrue("repeat 1\ncancel 0\nafter cancel 0\n" in out, out)
    }

    @Test
    fun `set-atは単調時計（clock_time_getのMONOTONIC）の時刻で鳴る`() = both("at") {
        val out = exited()
        assertTrue("at 1 late 1\n" in out, out)
        assertTrue("past 1\n" in out, out)
    }

    @Test
    fun `キューを渡して作ったタイマーはtagと回数のtimerイベントを届ける`() = both("queue") {
        val out = exited()
        assertTrue("event timer [] integer:-42 integer:1\n" in out, out)
        assertTrue("timer read 0\n" in out, out)
    }

    @Test
    fun `不正な入力はerror-codeを返し、プロセスは続く`() = both("invalid") {
        val out = exited()
        // invalid は -28、bad-descriptor は -8（-1 - 判別子）
        assertTrue("inval create -28 -8 -8 -28\n" in out, out)
        // 2^63 以上の値は頭打ちにして受け付ける
        assertTrue("inval interval -28 -28 -28 0 0 0 0\n" in out, out)
        assertTrue("inval self -8 -8 -8 -8 -8\n" in out, out)
        assertTrue("cancel 0 0\n" in out, out)
    }

    @Test
    fun `判別子が0ならキューとtagの値は読まない`() = both("nodisc") {
        assertTrue("nodisc 1 1\n" in exited())
    }

    @Test
    fun `resource-dropとfd_closeで閉じ、閉じた後はbad-descriptor`() = both("drop") {
        val out = exited()
        assertTrue("drop -8 0 -8\n" in out, out)
        // 閉じたタイマーの予定は残らない
        assertEquals(0, m.lock.withLockValue { m.timers.scheduledCount })
    }

    @Test
    fun `タイマーでないハンドルのresource-dropはトラップ`() {
        for (async in listOf(true, false)) {
            run(async, "drop-bad") {
                val out = finish()
                assertEquals(ProcessState.CRASHED, m.stateOf(pid), out)
                assertTrue("before\n" in out, out)
                assertFalse("returned" in out, out)
                val e = rt.lastError
                assertTrue(e is WasmTrapException && "[resource-drop]timer" in e.message!!, "$e")
            }
        }
    }

    @Test
    fun `uptime-ticksはtickとともに進み、world-ticksは環境のゲーム内時間を返す`() = both("clock") {
        val out = exited()
        assertTrue("clock 1 world $WORLD_TICKS\n" in out, out)
    }

    private companion object {
        const val WORLD_TICKS: Long = 24_000L * 3 + 6_000
    }
}
