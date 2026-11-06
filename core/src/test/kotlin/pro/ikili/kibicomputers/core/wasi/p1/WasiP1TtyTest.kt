package pro.ikili.kibicomputers.core.wasi.p1

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.TtySignalHandler
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
import pro.ikili.kibicomputers.core.kernel.tty.TtySignal
import pro.ikili.kibicomputers.core.kernel.withLockValue
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.time.Duration
import java.util.Collections
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * 独自拡張 `kibi:io/tty`（#427）の p1 の import を、kibi_tty.c（実際の Endive）とカーネルで確かめる。
 * 標準入力と標準エラーはコンソールの TTY、標準出力は TTY でないストリーム（出力をそのまま読むため）
 */
class WasiP1TtyTest {
    private val bytes: ByteArray by lazy {
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/kibi-tty-wasi.wasm")) { "kibi-tty-wasi.wasm がリソースにない" }
            .use { it.readBytes() }
    }

    /** 待ち方: 待機点型（巻き戻し）とブロッキング型 */
    enum class Mode { UNWIND, BLOCK }

    private val asyncProgram: Program by lazy { Program.loadMetered(EndiveRuntime(), bytes, null, WasiP1.WAIT_IMPORTS) }
    private val plainProgram: Program by lazy { Program.load(EndiveRuntime(), bytes) }

    private inner class Run(val h: Harness, val m: Machine, val mode: Mode, args: List<String>) {
        private val output: Int = m.lock.withLockValue { m.kernel.objects.add(ByteStream(64 * 1024)) }
        private val text = StringBuilder()
        val signals: MutableList<Pair<TtySignal, Int>> = Collections.synchronizedList(ArrayList())
        val pid: Int
        val rt: ProcessRuntime

        init {
            m.ttySignalHandler = TtySignalHandler { _, s, fg -> signals += s to fg }
            val program = if (mode == Mode.UNWIND) asyncProgram else plainProgram
            val options = SpawnOptions(
                allowUnmetered = !program.module.isFuelMetered,
                args = listOf("kibi-tty") + args,
                stdio = StdioSpec(StdioSource.Console, StdioSource.Object(output, HandleRights.WRITE), StdioSource.Console),
            )
            pid = m.spawn(program, WasiP1Driver(program.module), options)
            rt = m.rt(pid)
            assertEquals(if (mode == Mode.UNWIND) ProcessKind.WAIT_POINT else ProcessKind.BLOCKING, rt.driver.kind)
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

        /** [line] が出て、プロセスが入力などを待つ（または終わる）まで進める */
        fun awaitLine(line: String) {
            waitUntil("出ない: $line / ${drain()}") { "$line\n" in drain() || m.stateOf(pid).isTerminal }
            assertTrue("$line\n" in drain(), drain())
        }

        fun awaitWaiting() = waitUntil("待たない: ${drain()}") { m.stateOf(pid) == ProcessState.WAITING_EVENT || m.stateOf(pid).isTerminal }

        /** 待機点型なら、スレッドを持たずに待機点で待っていること */
        fun assertThreadless() {
            if (mode != Mode.UNWIND) return
            eventually(5_000, "スレッドが外れない") { rt.thread == null }
            assertTrue(h.scheduler.dedicatedThreads.isEmpty(), "専用スレッドがある")
            assertEquals(0, h.scheduler.blockingProcesses, "ブロッキング型の枠を使っている")
            assertTrue(m.isAtWaitPoint, "待機点でない")
        }

        fun offer(vararg b: Int) = assertEquals(b.size, m.offerConsoleInput(ByteArray(b.size) { b[it].toByte() }))

        fun finish(): String {
            waitUntil("終わらない: $text") { m.stateOf(pid).isTerminal }
            val out = drain()
            assertEquals(ProcessState.EXITED, m.stateOf(pid), "$out ${rt.lastError}")
            assertEquals(0, m.lock.withLockValue { m.kernel.processes[pid]!!.exitCode }, out)
            return out
        }
    }

    private fun run(mode: Mode, vararg args: String, setup: (Machine) -> Unit = {}, block: Run.() -> Unit) = timeout(Duration.ofSeconds(90)) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 10_000_000)
            setup(m)
            Run(h, m, mode, args.toList()).block()
        }
    }

    private fun assertLine(line: String, out: String) = assertTrue("$line\n" in out, out)

    @ParameterizedTest
    @EnumSource(Mode::class)
    fun `rawモードではLFを待たずに1バイトずつ読め、Ctrl+CもDELもCRもそのまま届く`(mode: Mode) = run(mode, "raw") {
        awaitLine("raw 0")
        for ((b, line) in listOf('a'.code to "got 1 61", 0x03 to "got 1 03", 0x7f to "got 1 7f", '\r'.code to "got 1 0d")) {
            awaitWaiting()
            assertThreadless()
            offer(b)
            awaitLine(line)
        }
        awaitWaiting()
        offer('q'.code)
        val out = finish()
        assertLine("got 1 71", out)
        assertLine("cooked 31", out)
        // raw では Ctrl+C はシグナルにならない
        assertEquals(emptyList<Pair<TtySignal, Int>>(), signals.toList())
    }

    @ParameterizedTest
    @EnumSource(Mode::class)
    fun `canonicalのままなら、行が確定するまで読めない`(mode: Mode) = run(mode, "cooked") {
        awaitLine("cooked 31")
        awaitWaiting()
        assertThreadless()
        offer('a'.code)
        repeat(20) { h.scheduler.tick() }
        assertFalse("got" in drain(), drain())
        assertEquals(ProcessState.WAITING_EVENT, m.stateOf(pid))
        // CR は LF になって行が確定する
        offer('\r'.code)
        assertLine("got 2 61 0a", finish())
    }

    @ParameterizedTest
    @EnumSource(Mode::class)
    fun `値の読み出しと設定`(mode: Mode) = run(mode, "values", setup = { it.resizeConsole(100, 30) }) {
        val out = finish()
        assertLine("is-tty 1 0 1 0 0", out)
        assertLine("mode 31 31", out)
        assertLine("set 0 29 29", out)
        assertLine("restore 0 31", out)
        assertLine("size 100 30 1 1", out)
        assertLine("fg 0 0 5", out)
        assertLine("fg max 0 2147483647", out)
        assertLine("fg none 0 0", out)
    }

    @ParameterizedTest
    @EnumSource(Mode::class)
    fun `不正なfdはbad-descriptor、TTYでないfdはno-tty、2の31乗以上のpidはinvalid、modeの上位ビットは読まない`(mode: Mode) = run(mode, "errors") {
        val out = finish()
        // bad-descriptor は -8、invalid は -28、no-tty は -59（-1 - 判別子）
        assertLine("badf -8 -8 -8 -8 -8", out)
        assertLine("notty -59 -59 -59 -59", out)
        assertLine("size err 58 1", out)
        assertLine("size err 7 1", out)
        assertLine("inval -28 -28 -28 0", out)
        assertLine("high 0 0 0 31", out)
    }

    @ParameterizedTest
    @EnumSource(Mode::class)
    fun `tty-resizeのキューをpoll_oneoffで待ち、大きさが変わると起きる`(mode: Mode) = run(mode, "resize", setup = { it.resizeConsole(100, 30) }) {
        awaitLine("opened")
        assertLine("size 100 30 1 1", drain())
        awaitWaiting()
        assertThreadless()
        // 同じ大きさでは起きない
        m.resizeConsole(100, 30)
        repeat(20) { h.scheduler.tick() }
        assertEquals(ProcessState.WAITING_EVENT, m.stateOf(pid))
        m.resizeConsole(120, 40)
        val out = finish()
        assertLine("batch 1 dropped 0", out)
        assertLine("event tty-resize [console] 120 40 2", out)
        assertLine("size 120 40 2 1", out)
    }
}
