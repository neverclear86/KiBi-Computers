package pro.ikili.kibicomputers.core.wasi.p1

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.eventually
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.io.ReadResult
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.KillSignal
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.ProcessDriver
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.stateOf
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.kernel.withLockValue
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmModule
import pro.ikili.kibicomputers.core.runtime.WasmRuntime
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * 独自拡張 `kibi:process/process`（#74）の p1 の import を、kibi_process.c（実際の Endive）とカーネルで確かめる。
 * 親はテストが直接作り、子は親が `/bin/kp` を spawn して作る（`Machine.exec` の読み込み）
 */
class WasiP1KibiProcessTest {
    private val bytes: ByteArray by lazy {
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/kibi-process-wasi.wasm")) { "kibi-process-wasi.wasm がリソースにない" }
            .use { it.readBytes() }
    }

    /** 待ち方: 待機点型（巻き戻し）・ブロッキング型・待たない（`AGAIN`） */
    enum class Mode { UNWIND, BLOCK, NONE }

    private val asyncProgram: Program by lazy { Program.loadMetered(EndiveRuntime(), bytes, null, WasiP1.WAIT_IMPORTS) }
    private val plainProgram: Program by lazy { Program.load(EndiveRuntime(), bytes) }

    /** 待たない設定（[P1Wait.NONE]）で p1 を登録するドライバー */
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

    /** 読み込みを [gate] が開くまで止めるランタイム（子の読み込みを確実に待たせる） */
    private class GatedRuntime(private val inner: WasmRuntime) : WasmRuntime {
        val gate = CountDownLatch(1)
        val loads = AtomicInteger()
        override val name: String get() = "gated-" + inner.name

        override fun load(bytes: ByteArray): WasmModule {
            loads.incrementAndGet()
            gate.await()
            return inner.load(bytes)
        }
    }

    private inner class Run(val h: Harness, val m: Machine, mode: Mode, args: List<String>) {
        private val output: Int
        private val text = StringBuilder()
        val pid: Int
        val driver: WasiP1Driver?

        init {
            m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
            m.vfs.createDirectory(VfsPath.parse("/bin"))
            m.vfs.createDirectory(VfsPath.parse("/home"))
            for ((path, b) in listOf("/bin/kp" to bytes, "/bin/text" to "not wasm".toByteArray())) {
                val f = m.vfs.open(VfsPath.parse(path), OpenOptions(read = true, write = true, create = true))
                m.vfs.write(f, b, 0, b.size)
            }
            output = m.lock.withLockValue { m.kernel.objects.add(ByteStream(64 * 1024)) }
            val program = if (mode == Mode.UNWIND) asyncProgram else plainProgram
            val out = StdioSource.Object(output, HandleRights.WRITE)
            val options = SpawnOptions(
                allowUnmetered = !program.module.isFuelMetered,
                args = listOf("kp") + args,
                stdio = StdioSpec(StdioSource.Closed, out, out),
            )
            driver = if (mode == Mode.NONE) null else WasiP1Driver(program.module)
            pid = m.spawn(program, driver ?: NoWaitDriver(program), options)
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

        fun state(): ProcessState = m.stateOf(pid)

        fun finish(expected: ProcessState = ProcessState.EXITED): String {
            waitUntil("終わらない: $text") { state().isTerminal }
            val out = drain()
            assertEquals(expected, state(), out)
            if (expected == ProcessState.EXITED) assertEquals(0, m.lock.withLockValue { m.kernel.processes[pid]!!.exitCode }, out)
            return out
        }

        /** 子（親以外）がすべて表から消える */
        fun waitNoChildren() = waitUntil("子が残っている: ${m.lock.withLockValue { m.kernel.processes.all().map { it.pid to it.state } }}") {
            m.lock.withLockValue { m.kernel.processes.all().none { it.pid != pid } }
        }
    }

    private fun harness(mode: Mode, runtime: WasmRuntime = EndiveRuntime()) = Harness(execRuntime = runtime, asyncify = mode == Mode.UNWIND)

    private fun run(mode: Mode, vararg args: String, block: Run.() -> Unit) = timeout(Duration.ofSeconds(120)) {
        harness(mode).use { h -> Run(h, h.machine(fuelPerTick = 10_000_000), mode, args.toList()).block() }
    }

    private fun assertLine(line: String, out: String) = assertTrue("$line\n" in out, out)

    /** C の側の `-1 - error-code`（= -p1 の errno） */
    private fun err(e: Errno): String = "${-e.code}"

    @ParameterizedTest
    @EnumSource(Mode::class, names = ["UNWIND", "BLOCK"])
    fun `子を作りpollで終わりを待つと、statusは終了コードを返し刈り取った後も同じ`(mode: Mode) = run(mode, "run", "exit", "7") {
        val out = finish()
        assertTrue(Regex("""spawned \d+\n""").containsMatchIn(out), out)
        assertLine("status1 exited 7", out)
        assertLine("status2 exited 7", out)
        assertLine("pid-after 1", out)
        waitNoChildren()
    }

    @ParameterizedTest
    @EnumSource(Mode::class, names = ["UNWIND", "BLOCK"])
    fun `終了コードは32ビットのまま、トラップはcrashed`(mode: Mode) {
        run(mode, "run", "exit", "4000000000") { assertLine("status1 exited 4000000000", finish()) }
        run(mode, "run", "trap") { assertLine("status1 crashed", finish()) }
    }

    @ParameterizedTest
    @ValueSource(ints = [2, 3])
    fun `handleでkillするとkilledとシグナル、刈り取った後のkillも成功`(sig: Int) = run(Mode.UNWIND, "kill", "$sig") {
        val out = finish()
        assertLine("before none", out)
        assertLine("kill 0", out)
        assertLine("status killed $sig", out)
        assertLine("kill-after 0", out)
    }

    @ParameterizedTest
    @EnumSource(Mode::class, names = ["UNWIND", "BLOCK"])
    fun `interruptを無視する子はinterruptで止まらず、killで止まる`(mode: Mode) = run(mode, "ignore") {
        val out = finish()
        assertLine("int 0", out)
        assertLine("after-int none", out)
        assertLine("kill 0", out)
        assertLine("status killed 3", out)
    }

    @ParameterizedTest
    @ValueSource(strings = ["drop", "close", "renumber", "dupto"])
    fun `親がハンドルを閉じると子は親なしになる`(how: String) = run(Mode.UNWIND, "close", how) {
        val out = finish()
        assertLine("closed", out)
        assertLine("child said orphaned", out)
        // 親なしで終わった子は、ほかのゾンビと同じく親なしのゾンビとして残る（誰も刈り取らない）
        val child = Regex("""child (\d+)
""").find(out)!!.groupValues[1].toInt()
        waitUntil("子が終わらない") { m.stateOf(child).isTerminal }
        m.lock.withLockValue {
            assertEquals(0, m.kernel.processes[child]!!.parentPid)
            assertTrue(child in m.kernel.processes.orphanZombies)
        }
    }

    @Test
    fun `spawn・kill-pid・set-signal-ignored・set-cwdの誤り`() = run(Mode.UNWIND, "errors") {
        val out = finish()
        assertLine("noent ${err(Errno.NOENT)}", out)
        assertLine("noexec ${err(Errno.NOEXEC)}", out)
        assertLine("utf8 ${err(Errno.INVAL)}", out)
        assertLine("args-count ${err(Errno.TOOBIG)}", out)
        assertLine("args-fault ${err(Errno.FAULT)}", out)
        assertLine("env-disc ${err(Errno.INVAL)}", out)
        assertLine("fds-dup ${err(Errno.INVAL)}", out)
        assertLine("fds-tag ${err(Errno.INVAL)}", out)
        assertLine("fds-badf ${err(Errno.BADF)}", out)
        assertLine("env-name ${err(Errno.INVAL)}", out)
        assertLine("cwd-noent ${err(Errno.NOENT)}", out)
        assertLine("kill-srch ${err(Errno.SRCH)}", out)
        assertLine("kill-zero ${err(Errno.SRCH)}", out)
        assertLine("kill-sig ${err(Errno.INVAL)}", out)
        assertLine("ignore-kill ${err(Errno.INVAL)}", out)
        assertLine("ignore-quit 0", out)
        assertLine("status-badf err ${err(Errno.BADF)}", out)
        assertLine("kill-badf ${err(Errno.BADF)}", out)
        assertLine("setcwd-noent ${err(Errno.NOENT)}", out)
        assertLine("setcwd-notdir ${err(Errno.NOTDIR)}", out)
        // 失敗した spawn は子を作らない
        waitNoChildren()
    }

    @ParameterizedTest
    @EnumSource(Mode::class, names = ["UNWIND", "BLOCK"])
    fun `fd表が満杯ならmfileで、子を作らない`(mode: Mode) = run(mode, "mfile") {
        assertLine("mfile ${err(Errno.MFILE)}", finish())
        waitNoChildren()
    }

    @Test
    fun `set-cwdとget-cwd、子のcwdと相対パスのspawn`() = run(Mode.UNWIND, "cwd") {
        val out = finish()
        assertEquals(
            listOf("cwd /", "set 0", "cwd /home", "child cwd /home", "child cwd /bin", "set-rel 0", "cwd /bin"),
            out.lines().filter { it.isNotEmpty() },
        )
    }

    @Test
    fun `cabi_reallocの中からspawnするとトラップ`() = run(Mode.UNWIND, "realloc-spawn") {
        finish(ProcessState.CRASHED)
        waitNoChildren()
    }

    @Test
    fun `待たない設定ではspawnはagain`() = run(Mode.NONE, "nowait") {
        assertLine("nowait ${err(Errno.AGAIN)}", finish())
        waitNoChildren()
    }

    @Test
    fun `self-pidとparent-pid`() = run(Mode.UNWIND, "self") {
        assertLine("self $pid parent 0", finish())
    }

    /** 子の読み込みを止めたまま、親が spawn で待つところまで進める */
    private fun gated(mode: Mode, block: Run.(GatedRuntime) -> Unit) = timeout(Duration.ofSeconds(120)) {
        val runtime = GatedRuntime(EndiveRuntime())
        harness(mode, runtime).use { h ->
            try {
                val r = Run(h, h.machine(fuelPerTick = 10_000_000), mode, listOf("run", "exit", "5"))
                r.waitUntil("読み込みに入らない") { runtime.loads.get() > 0 }
                r.waitUntil("spawn で待たない: ${r.state()}") { r.state() == ProcessState.WAITING_EVENT || r.state() == ProcessState.WAITING_HOST }
                r.block(runtime)
            } finally {
                runtime.gate.countDown()
            }
        }
    }

    @Test
    fun `待機点型は読み込みを巻き戻して待ち、開くと子を作る`() = gated(Mode.UNWIND) { runtime ->
        assertEquals(ProcessKind.WAIT_POINT, driver!!.kind)
        // 巻き戻している（スレッドを持たない）。子はまだない
        assertTrue(driver!!.kibiProcess!!.hasPendingSpawnForTest)
        assertEquals(ProcessState.WAITING_EVENT, state())
        // 読み込みの待ちは実行時だけの状態で、保存される AsyncWait には入れない
        assertEquals(null, m.lock.withLockValue { m.kernel.processes[pid]!!.asyncWait })
        assertEquals(1, m.lock.withLockValue { m.kernel.processes.size })
        runtime.gate.countDown()
        val out = finish()
        assertLine("status1 exited 5", out)
        assertFalse(driver.kibiProcess!!.hasPendingSpawnForTest)
        waitNoChildren()
    }

    @ParameterizedTest
    @EnumSource(Mode::class, names = ["UNWIND", "BLOCK"])
    fun `spawnで待っている親をkillすると、読み込みが終わっても子を作らない`(mode: Mode) = gated(mode) { runtime ->
        m.lock.withLockValue { m.killLocked(pid, KillSignal.KILL) }
        waitUntil("止まらない") { state().isTerminal }
        assertEquals(ProcessState.KILLED, state())
        runtime.gate.countDown()
        // 読み込みが終わるまで回しても、子は現れない
        repeat(50) {
            h.scheduler.tick()
            Thread.sleep(5)
        }
        waitNoChildren()
    }

    @Test
    fun `巻き戻している間の読み込みを忘れても、巻き直しで読み込み直して子は1つだけ`() = gated(Mode.UNWIND) { runtime ->
        driver!!.kibiProcess!!.forgetPendingSpawnForTest()
        runtime.gate.countDown()
        val out = finish()
        assertLine("status1 exited 5", out)
        assertEquals(1, Regex("spawned ").findAll(out).count(), out)
        waitNoChildren()
    }
}
