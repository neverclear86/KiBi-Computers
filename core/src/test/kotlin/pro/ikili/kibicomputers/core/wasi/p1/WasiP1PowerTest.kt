package pro.ikili.kibicomputers.core.wasi.p1

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import pro.ikili.kibicomputers.core.kernel.BootConfig
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.MachineHost
import pro.ikili.kibicomputers.core.kernel.MachineState
import pro.ikili.kibicomputers.core.kernel.VOID
import pro.ikili.kibicomputers.core.kernel.boot.BootVolume
import pro.ikili.kibicomputers.core.kernel.boot.BootVolumeOpener
import pro.ikili.kibicomputers.core.kernel.eventually
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.io.ReadResult
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.ProcessRuntime
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.rt
import pro.ikili.kibicomputers.core.kernel.spawnRt
import pro.ikili.kibicomputers.core.kernel.stateOf
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.kernel.withLockValue
import pro.ikili.kibicomputers.core.runtime.FakeModule
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.wasi.kibi.KibiErrors
import java.time.Duration
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * 独自拡張 `kibi:computer/power`（#306。ADR 0306）の p1 の import を、kibi_power.c（実際の Endive）とカーネルで確かめる。
 * 再起動を受け付けるのは起動を通ったマシンだけなので、偽の init で起動したマシンの下（init の子）でプログラムを動かす
 */
class WasiP1PowerTest {
    private val limit = Duration.ofSeconds(60)

    private val bytes: ByteArray by lazy {
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/kibi-power-wasi.wasm")) { "kibi-power-wasi.wasm がリソースにない" }
            .use { it.readBytes() }
    }

    /** 待ち方: 待機点型（巻き戻し）・ブロッキング型 */
    enum class Mode { UNWIND, BLOCK }

    private val asyncProgram: Program by lazy { Program.loadMetered(EndiveRuntime(), bytes, null, WasiP1.WAIT_IMPORTS) }
    private val plainProgram: Program by lazy { Program.load(EndiveRuntime(), bytes) }

    private fun program(mode: Mode): Program = if (mode == Mode.UNWIND) asyncProgram else plainProgram

    /** 通知を記録する接続層 */
    private class Recorder : MachineHost {
        val log: MutableList<String> = Collections.synchronizedList(ArrayList())
        val console = StringBuffer()
        val reboots = AtomicInteger()

        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) {
            log += "state:$state"
        }

        override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
            console.append(String(bytes, Charsets.UTF_8).replace("\r\n", "\n"))
        }

        override fun onRebootRequested(machine: Machine) {
            reboots.incrementAndGet()
            log += "reboot"
            super.onRebootRequested(machine)
        }

        fun states(): List<String> = synchronized(log) { log.filter { it.startsWith("state:") } }
    }

    private inner class Env(val h: Harness, val m: Machine, val host: Recorder) {
        fun tickUntil(message: String = "条件がそろわない", condition: () -> Boolean) = eventually(limit.toMillis(), message) {
            h.scheduler.tick()
            condition()
        }

        fun tickFor(millis: Long) {
            val end = System.currentTimeMillis() + millis
            while (System.currentTimeMillis() < end) {
                h.scheduler.tick()
                Thread.sleep(1)
            }
        }

        /** 偽の init で起動し、init が動き出すまで待つ */
        fun boot() {
            val init = "fake-init".toByteArray()
            h.runtime.define(init, foreverInit())
            val disk = TestMemoryFileSystem()
            disk.createDirectory(VfsPath.parse("/sbin"))
            val node = disk.createFile(VfsPath.parse(BootConfig.INIT_PATH))
            disk.write(node, 0, init, 0, init.size)
            h.scheduler.hooks.bootVolumes = BootVolumeOpener { id -> if (id == "d") BootVolume(disk, readOnly = false) else null }
            assertTrue(m.boot(BootConfig.builder().bootDisks(listOf("d")).build()))
            tickUntil("init が動き出さない") {
                m.state == MachineState.RUNNING && !m.isBooting &&
                    m.processState(1).let { it != null && it != ProcessState.CREATED && !it.isTerminal }
            }
        }

        /**
         * init の子として `kpw <arg>` を動かす（出力はコンソール）。戻り値はそのプロセスの実行時の部分
         * （init が止まると表から消えるので、終わった状態と最後の例外は [ProcessRuntime] から見る）
         */
        fun spawnUnderInit(mode: Mode, arg: String): ProcessRuntime {
            val p = program(mode)
            val options = SpawnOptions(
                allowUnmetered = !p.module.isFuelMetered,
                args = listOf("kpw", arg),
                stdio = StdioSpec(StdioSource.Closed, StdioSource.Console, StdioSource.Console),
            )
            return m.spawnRt(p, WasiP1Driver(p.module), options, parentPid = 1)
        }
    }

    /** `_start` が終わらない init */
    private fun foreverInit(): FakeModule = FakeModule.builder()
        .fuelMetered()
        .export("_start", VOID) { ctx, _ ->
            while (true) {
                ctx.step(1)
                Thread.sleep(1)
            }
            @Suppress("UNREACHABLE_CODE")
            LongArray(0)
        }
        .build()

    private fun env(block: Env.() -> Unit) = timeout(limit) {
        Harness().use { h ->
            val host = Recorder()
            val m = h.machine(host = host)
            Env(h, m, host).block()
            m.stop()
            assertTrue(m.awaitTermination(10_000))
        }
    }

    private val notSupported = KibiErrors.code(Errno.NOTSUP)

    @ParameterizedTest
    @EnumSource(Mode::class)
    fun `shutdownはマシンを止め、呼んだプロセスはKILLEDで戻らない`(mode: Mode) = env {
        boot()
        val rt = spawnUnderInit(mode, "shutdown")
        tickUntil("止まらない: ${host.console}") { m.state == MachineState.STOPPED }
        assertTrue(m.awaitTermination(10_000))
        tickFor(50)
        assertEquals(ProcessState.KILLED, rt.process.state)
        assertFalse(rt.lastError is WasmTrapException, "${rt.lastError}")
        assertEquals(0, host.reboots.get(), "再起動しない")
        assertEquals(MachineState.STOPPED, m.state)
        assertTrue("before\n" in host.console, host.console.toString())
        assertFalse("after" in host.console, "戻らない: ${host.console}")
        assertFalse("init exited" in host.console, host.console.toString())
        assertEquals(listOf("state:RUNNING", "state:STOPPING", "state:STOPPED"), host.states())
    }

    @ParameterizedTest
    @EnumSource(Mode::class)
    fun `rebootは受け付けたら戻らず、すべて止めてから起動し直す`(mode: Mode) = env {
        boot()
        val rt = spawnUnderInit(mode, "reboot")
        tickUntil("再起動しない: ${host.log}") { host.reboots.get() == 1 && m.state == MachineState.RUNNING && !m.isBooting }
        tickFor(50)
        assertEquals(1, host.reboots.get())
        assertEquals(listOf("state:RUNNING", "state:STOPPING", "state:STOPPED", "state:RUNNING"), host.states())
        assertEquals(ProcessState.KILLED, rt.process.state)
        assertTrue(m.lock.withLockValue { m.kernel.initPid } == 1, "init の pid は 1")
        assertTrue("before\n" in host.console, host.console.toString())
        assertFalse("refused" in host.console || "after" in host.console, "戻らない: ${host.console}")
        assertFalse("init exited" in host.console, host.console.toString())
    }

    @ParameterizedTest
    @EnumSource(Mode::class)
    fun `書き先が線形メモリの外でも、受け付ければ再起動する`(mode: Mode) = env {
        boot()
        val rt = spawnUnderInit(mode, "reboot-oob")
        tickUntil("再起動しない: ${host.log}") { host.reboots.get() == 1 && m.state == MachineState.RUNNING && !m.isBooting }
        assertEquals(ProcessState.KILLED, rt.process.state)
        assertFalse("refused" in host.console, host.console.toString())
    }

    @ParameterizedTest
    @EnumSource(Mode::class)
    fun `起動を通らずに作ったマシンではnot-supportedが返り、プロセスは続く`(mode: Mode) = env {
        val run = Unbooted(this, mode, "reboot")
        run.waitTerminal()
        assertEquals(ProcessState.EXITED, m.stateOf(run.pid), run.text())
        assertEquals("before\nrefused 1 $notSupported\nafter\n", run.text())
        assertEquals(0, host.reboots.get())
    }

    @ParameterizedTest
    @EnumSource(Mode::class)
    fun `断られたときに書き先が線形メモリの外ならトラップ`(mode: Mode) = env {
        val run = Unbooted(this, mode, "reboot-oob")
        run.waitTerminal()
        assertEquals(ProcessState.CRASHED, m.stateOf(run.pid), run.text())
        val e = m.rt(run.pid).lastError
        assertTrue(e is WasmTrapException, "$e")
        assertFalse("refused" in run.text(), run.text())
        assertEquals(0, host.reboots.get())
    }

    @ParameterizedTest
    @EnumSource(Mode::class)
    fun `起動を通らずに作ったマシンでもshutdownは受け付ける`(mode: Mode) = env {
        val run = Unbooted(this, mode, "shutdown")
        run.waitTerminal()
        assertEquals(ProcessState.KILLED, m.stateOf(run.pid), run.text())
        assertEquals("before\n", run.text())
        tickUntil { m.state == MachineState.STOPPED }
    }

    @ParameterizedTest
    @EnumSource(Mode::class)
    fun `cabi_reallocの中でshutdownするとKILLEDで終わり、CRASHEDにならない`(mode: Mode) = env {
        boot()
        val rt = spawnUnderInit(mode, "realloc-shutdown")
        tickUntil("止まらない: ${host.console}") { m.state == MachineState.STOPPED }
        assertTrue(m.awaitTermination(10_000))
        tickFor(50)
        assertEquals(ProcessState.KILLED, rt.process.state)
        assertFalse(rt.lastError is WasmTrapException, "${rt.lastError}")
        assertFalse("after" in host.console, host.console.toString())
        assertEquals(listOf("state:RUNNING", "state:STOPPING", "state:STOPPED"), host.states())
    }

    @ParameterizedTest
    @EnumSource(Mode::class)
    fun `cabi_reallocの中でrebootするとKILLEDで終わり、再起動する`(mode: Mode) = env {
        boot()
        val rt = spawnUnderInit(mode, "realloc-reboot")
        tickUntil("再起動しない: ${host.log}") { host.reboots.get() == 1 && m.state == MachineState.RUNNING && !m.isBooting }
        tickFor(50)
        assertEquals(ProcessState.KILLED, rt.process.state)
        assertFalse(rt.lastError is WasmTrapException, "${rt.lastError}")
        assertFalse("after" in host.console, host.console.toString())
        assertEquals(listOf("state:RUNNING", "state:STOPPING", "state:STOPPED", "state:RUNNING"), host.states())
    }

    /** 起動を通らずに作ったマシン（[Env.m]）で、直接 `kpw <arg>` を動かす（出力はバイト列のストリーム） */
    private inner class Unbooted(private val env: Env, mode: Mode, arg: String) {
        private val output: Int
        private val text = StringBuilder()
        val pid: Int

        init {
            val m = env.m
            m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
            output = m.lock.withLockValue { m.kernel.objects.add(ByteStream(64 * 1024)) }
            val p = program(mode)
            val out = StdioSource.Object(output, HandleRights.WRITE)
            val options = SpawnOptions(
                allowUnmetered = !p.module.isFuelMetered,
                args = listOf("kpw", arg),
                stdio = StdioSpec(StdioSource.Closed, out, out),
            )
            pid = m.spawn(p, WasiP1Driver(p.module), options)
        }

        fun text(): String = env.m.lock.withLockValue {
            val s = env.m.kernel.objects[output] as ByteStream
            val buf = ByteArray(s.available)
            if (buf.isNotEmpty()) s.read(buf, 0, buf.size, env.m.wakeSink) as? ReadResult.Bytes
            text.append(String(buf, Charsets.UTF_8))
            text.toString()
        }

        fun waitTerminal() = env.tickUntil("終わらない") {
            text()
            env.m.stateOf(pid).isTerminal
        }
    }
}
