package pro.ikili.kibicomputers.core.wasi.p1

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.event.EventValue
import pro.ikili.kibicomputers.core.kernel.event.KernelEvent
import pro.ikili.kibicomputers.core.kernel.eventually
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.io.ReadResult
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.ProcessDriver
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessRuntime
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.rt
import pro.ikili.kibicomputers.core.kernel.stateOf
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.kernel.withLockValue
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** 独自拡張 `kibi:kernel/events`（#73）の p1 の import を、kibi_events.c（実際の Endive）とカーネルで確かめる */
class WasiP1KibiTest {
    private val bytes: ByteArray by lazy {
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/kibi-events-wasi.wasm")) { "kibi-events-wasi.wasm がリソースにない" }
            .use { it.readBytes() }
    }

    /** 待ち方 */
    private enum class Mode { UNWIND, BLOCK, NONE }

    private val asyncProgram: Program by lazy { Program.loadMetered(EndiveRuntime(), bytes, null, WasiP1.WAIT_IMPORTS) }
    private val plainProgram: Program by lazy { Program.load(EndiveRuntime(), bytes) }

    /** 待たない設定（[P1Wait.NONE]）で `_start` を呼ぶドライバー */
    private class NoWaitDriver(private val program: Program) : ProcessDriver {
        override val kind: ProcessKind = ProcessKind.BLOCKING

        override fun configure(ctx: ProcessContext, builder: WasmInstanceConfig.Builder) {
            WasiP1.register(ctx, builder, program.module, blocking = false)
        }

        override fun start(ctx: ProcessContext): StepResult = try {
            ctx.instance.call(WasiP1Driver.START)
            StepResult.Exit(0)
        } catch (e: WasiP1Exit) {
            StepResult.Exit(e.code)
        }

        override fun resume(ctx: ProcessContext, ready: List<WaitKey>): StepResult = error("resume しない")
    }

    private inner class Run(val h: Harness, val m: Machine, mode: Mode, args: List<String>) {
        private val output: Int = m.lock.withLockValue { m.kernel.objects.add(ByteStream(64 * 1024)) }
        private val text = StringBuilder()
        val pid: Int
        val rt: ProcessRuntime

        init {
            val program = if (mode == Mode.UNWIND) asyncProgram else plainProgram
            val options = SpawnOptions(
                allowUnmetered = !program.module.isFuelMetered,
                args = listOf("kibi-events") + args,
                stdio = StdioSpec(StdioSource.Closed, StdioSource.Object(output, HandleRights.WRITE), StdioSource.Closed),
            )
            val driver = if (mode == Mode.NONE) NoWaitDriver(program) else WasiP1Driver(program.module)
            pid = m.spawn(program, driver, options)
            rt = m.rt(pid)
            if (mode == Mode.UNWIND) assertEquals(ProcessKind.WAIT_POINT, rt.driver.kind)
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
    }

    private fun run(mode: Mode, vararg args: String, fuelPerTick: Long = 10_000_000, block: Run.() -> Unit) = timeout(Duration.ofSeconds(90)) {
        Harness().use { h -> Run(h, h.machine(fuelPerTick = fuelPerTick), mode, args.toList()).block() }
    }

    private val event = KernelEvent(
        "test",
        "dev",
        listOf(EventValue.Integer(-5), EventValue.Float(1.5), EventValue.Boolean(true), EventValue.Text("hé"), EventValue.Bytes(byteArrayOf(1, 2))),
    )

    private fun Run.waitAndPost() {
        waitUntil("開かない: ${drain()}") { "opened\n" in drain() || m.stateOf(pid).isTerminal }
        waitUntil("poll で待たない") { m.stateOf(pid) == ProcessState.WAITING_EVENT || m.stateOf(pid).isTerminal }
        // 受け取らない種類は届かない
        assertEquals(0, m.postEvent(KernelEvent("ignored")))
        assertEquals(1, m.postEvent(event))
    }

    private fun assertWaitOutput(out: String) {
        assertTrue("batch 1 dropped 0\nevent test [dev] integer:-5 float:1.5 boolean:1 text:68c3a9 bytes:0102\n" in out, out)
        // 配列・kind・source・値の配列・text・bytes
        assertTrue("realloc calls 6\n" in out, out)
        assertTrue("after close 1 7\n" in out, out)
    }

    @Test
    fun `待機点型は開いたキューをpoll_oneoffで巻き戻して待ち、取り出して閉じる`() = run(Mode.UNWIND, "wait") {
        waitAndPost()
        val out = finish()
        assertEquals(ProcessState.EXITED, m.stateOf(pid), "$out ${rt.lastError}")
        assertWaitOutput(out)
    }

    @Test
    fun `fd_closeでも閉じられる`() = run(Mode.UNWIND, "wait", "close") {
        waitAndPost()
        val out = finish()
        assertEquals(ProcessState.EXITED, m.stateOf(pid), "$out ${rt.lastError}")
        assertWaitOutput(out)
    }

    @Test
    fun `ブロッキング型でも開いたキューをpoll_oneoffで待って取り出す`() = run(Mode.BLOCK, "wait") {
        waitAndPost()
        val out = finish()
        assertEquals(ProcessState.EXITED, m.stateOf(pid), "$out ${rt.lastError}")
        assertWaitOutput(out)
    }

    @Test
    fun `不正なkindsとcapacityはinvalidかfaultを返し、プロセスは続く`() = run(Mode.UNWIND, "invalid") {
        val out = finish()
        assertEquals(ProcessState.EXITED, m.stateOf(pid), "$out ${rt.lastError}")
        assertEquals(0, m.lock.withLockValue { m.kernel.processes[pid]!!.exitCode })
        // invalid は -28、fault は -21（-1 - 判別子）
        assertTrue("inval -28 -28 -28 -28 -28 -28 -21 -21 -28 -28 -28 -28\n" in out, out)
        assertTrue("ok 1 1\n" in out, out)
        assertTrue("badf 1 7\n" in out, out)
    }

    @Test
    fun `maxは0だけがinvalid`() = run(Mode.UNWIND, "max") {
        val out = finish()
        assertEquals(ProcessState.EXITED, m.stateOf(pid), "$out ${rt.lastError}")
        assertTrue("max err:27 ok:0 ok:0 ok:0\n" in out, out)
    }

    @Test
    fun `cabi_reallocの中で燃料が切れても、巻き戻さずに燃料を待って続ける`() = run(Mode.UNWIND, "spin", fuelPerTick = 2_000_000) {
        waitAndPost()
        val before = h.scheduler.fuelBlockingWaits.get()
        val out = finish()
        assertEquals(ProcessState.EXITED, m.stateOf(pid), "$out ${rt.lastError}")
        assertWaitOutput(out)
        assertTrue(h.scheduler.fuelBlockingWaits.get() > before, "cabi_realloc の中で燃料を待っていない")
    }

    private fun assertTrapsInRealloc(mode: Mode, what: String, name: String) = run(mode, what) {
        val out = finish()
        assertEquals(ProcessState.CRASHED, m.stateOf(pid), out)
        assertTrue("opened\n" in out, out)
        assertFalse("returned" in out, out)
        assertFalse("x" in out, "fd_write は書かない: $out")
        val e = rt.lastError
        assertTrue(e is WasmTrapException && "止まりうる import $name を cabi_realloc の中で呼んだ" in e.message!!, "$e")
    }

    @Test
    fun `cabi_reallocの中でpoll_oneoffを呼ぶと、待機点型ではトラップ`() = assertTrapsInRealloc(Mode.UNWIND, "realloc-poll", "poll_oneoff")

    @Test
    fun `cabi_reallocの中でpoll_oneoffを呼ぶと、ブロッキング型でもトラップ`() = assertTrapsInRealloc(Mode.BLOCK, "realloc-poll", "poll_oneoff")

    @Test
    fun `cabi_reallocの中でpoll_oneoffを呼ぶと、待たない設定でもトラップ`() = assertTrapsInRealloc(Mode.NONE, "realloc-poll", "poll_oneoff")

    @Test
    fun `cabi_reallocの中でfd_writeを呼ぶと、待機点型ではトラップ`() = assertTrapsInRealloc(Mode.UNWIND, "realloc-write", "fd_write")

    @Test
    fun `cabi_reallocの中でfd_writeを呼ぶと、ブロッキング型でもトラップ`() = assertTrapsInRealloc(Mode.BLOCK, "realloc-write", "fd_write")

    @Test
    fun `cabi_reallocの中でfd_writeを呼ぶと、待たない設定でもトラップ`() = assertTrapsInRealloc(Mode.NONE, "realloc-write", "fd_write")
}
