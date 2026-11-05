package pro.ikili.kibicomputers.core.wasi.p1

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.eventually
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.io.PipeEnd
import pro.ikili.kibicomputers.core.kernel.io.ReadResult
import pro.ikili.kibicomputers.core.kernel.io.WriteResult
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessRuntime
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.rt
import pro.ikili.kibicomputers.core.kernel.stateOf
import pro.ikili.kibicomputers.core.kernel.withLockValue
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** 独自拡張 `kibi:io/fd`・`kibi:io/pipe`（#75）の p1 の import を、kibi_io.c（実際の Endive）とカーネルで確かめる */
class WasiP1KibiIoTest {
    private val bytes: ByteArray by lazy {
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/kibi-io-wasi.wasm")) { "kibi-io-wasi.wasm がリソースにない" }
            .use { it.readBytes() }
    }

    /** 待ち方: 待機点型（巻き戻し）とブロッキング型 */
    enum class Mode { UNWIND, BLOCK }

    private val asyncProgram: Program by lazy { Program.loadMetered(EndiveRuntime(), bytes, null, WasiP1.WAIT_IMPORTS) }
    private val plainProgram: Program by lazy { Program.load(EndiveRuntime(), bytes) }

    private inner class Run(val h: Harness, val m: Machine, mode: Mode, args: List<String>) {
        private val output: Int = m.lock.withLockValue { m.kernel.objects.add(ByteStream(64 * 1024)) }
        private val text = StringBuilder()
        val pid: Int
        val rt: ProcessRuntime

        init {
            val program = if (mode == Mode.UNWIND) asyncProgram else plainProgram
            val options = SpawnOptions(
                allowUnmetered = !program.module.isFuelMetered,
                args = listOf("kibi-io") + args,
                stdio = StdioSpec(StdioSource.Closed, StdioSource.Object(output, HandleRights.WRITE), StdioSource.Closed),
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

        fun finish(): String {
            waitUntil("終わらない: $text") { m.stateOf(pid).isTerminal }
            val out = drain()
            assertEquals(ProcessState.EXITED, m.stateOf(pid), "$out ${rt.lastError}")
            assertEquals(0, m.lock.withLockValue { m.kernel.processes[pid]!!.exitCode }, out)
            return out
        }
    }

    private fun run(mode: Mode, vararg args: String, block: Run.() -> Unit) = timeout(Duration.ofSeconds(90)) {
        Harness().use { h -> Run(h, h.machine(fuelPerTick = 10_000_000), mode, args.toList()).block() }
    }

    private fun assertLine(line: String, out: String) = assertTrue("$line\n" in out, out)

    @ParameterizedTest
    @EnumSource(Mode::class)
    fun `createして書いたものを読み、dupした fdを閉じても元のfdが使える`(mode: Mode) = run(mode, "rw") {
        val out = finish()
        // stdin が閉じているので、両端は 0 と 2
        assertLine("ends 0 2", out)
        assertLine("read 5 hello", out)
        // 向きの違う使い方は EBADF
        assertLine("wrong -1 1 -1 1", out)
        assertLine("dup 3", out)
        assertLine("after close dup 5 again", out)
    }

    @ParameterizedTest
    @EnumSource(Mode::class)
    fun `NONBLOCKを付けた書く端をいっぱいまで書くとEAGAIN`(mode: Mode) = run(mode, "nonblock") {
        assertLine("full 4096 1", finish())
    }

    @ParameterizedTest
    @EnumSource(Mode::class)
    fun `書く端を閉じると残りの後にEOF、読む端を閉じると書く側はEPIPE`(mode: Mode) = run(mode, "eof") {
        val out = finish()
        assertLine("eof 3 0 abc", out)
        assertLine("epipe -1 1", out)
    }

    @ParameterizedTest
    @EnumSource(Mode::class)
    fun `dup-toで標準出力をパイプに向けると、printfがパイプに入る`(mode: Mode) = run(mode, "redirect") {
        val out = finish()
        assertLine("from pipe 11 redirected", out)
        assertTrue(out.lines().none { it == "redirected" }, "標準出力に出てしまった: $out")
    }

    @ParameterizedTest
    @EnumSource(Mode::class)
    fun `空の読む端をpollで待ち、ホストがパイプに書くと起きる`(mode: Mode) = run(mode, "poll") {
        waitUntil("作らない: ${drain()}") { "ends " in drain() || m.stateOf(pid).isTerminal }
        waitUntil("poll で待たない") { m.stateOf(pid) == ProcessState.WAITING_EVENT || m.stateOf(pid).isTerminal }
        val readFd = Regex("""ends (\d+) """).find(drain())!!.groupValues[1].toInt()
        val written = m.lock.withLockValue {
            val entry = checkNotNull(m.kernel.processes[pid]!!.handles[readFd])
            val end = m.kernel.objects[entry.objectId] as PipeEnd
            val b = "ping".toByteArray()
            (m.kernel.objects[end.streamId] as ByteStream).write(b, 0, b.size, m.wakeSink)
        }
        assertEquals(WriteResult.Bytes(4), written)
        assertLine("woke 4 ping", finish())
    }

    @ParameterizedTest
    @EnumSource(Mode::class)
    fun `不正なfdはbad-descriptor、表が満杯ならmfile`(mode: Mode) = run(mode, "errors") {
        val out = finish()
        // bad-descriptor は -8、mfile は -33（-1 - 判別子）
        assertLine("badf -8 -8 -8 -8 -8", out)
        assertLine("same 0 -8", out)
        assertLine("kept -8 1", out)
        assertLine("mfile -33 -33 -33 0 ends 3 4", out)
    }
}
