package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.api.device.Device
import pro.ikili.kibicomputers.api.device.DeviceContext
import pro.ikili.kibicomputers.api.device.DeviceException
import pro.ikili.kibicomputers.api.device.DeviceMethods
import pro.ikili.kibicomputers.api.device.DeviceValue
import pro.ikili.kibicomputers.core.kernel.event.EventLimits
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.KillSignal
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.vfs.ImageFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import pro.ikili.kibicomputers.core.testing.bodyTimeout as timeout

/**
 * デバイス操作のコマンド `lsdev`・`devcall`（#108。ADR 0108）を、標準 OS のイメージを根にしたマシンで確かめる
 */
class DeviceCommandsTest {
    private val limit = Duration.ofSeconds(180)

    private companion object {
        const val A = "00000000-0000-4000-8000-0000000001a1"
        const val B = "00000000-0000-4000-8000-0000000001b2"
        const val S = "00000000-0000-4000-8000-0000000001c3"
        const val C = "00000000-0000-4000-8000-0000000001d4"
    }

    private class ConsoleHost : MachineHost {
        private val out = ByteArrayOutputStream()

        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit

        override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
            synchronized(out) { out.write(bytes, 0, bytes.size) }
        }

        fun text(): String = synchronized(out) { out.toString(Charsets.UTF_8).replace("\r\n", "\n") }
    }

    /** テスト用のデバイス。直接のメソッドとメインスレッドのメソッドを持つ */
    private class TestDev(override val kind: String = "test:dev") : Device {
        val mainCalls = AtomicInteger()

        @Volatile
        var context: DeviceContext? = null

        override fun onAttached(context: DeviceContext) {
            this.context = context
        }

        override val methods: DeviceMethods = DeviceMethods.builder()
            .direct("echo") { _, args -> Array(args.size()) { args.get(it) } }
            .direct("types") { _, args -> Array(args.size()) { DeviceValue.of(args.get(it).type.name.lowercase()) } }
            .direct("get-size") { _, _ -> arrayOf(DeviceValue.of(80L), DeviceValue.of(25L)) }
            .direct("needs-int") { _, args -> arrayOf(DeviceValue.of(args.getLong(0))) }
            .direct("fail") { _, _ -> throw DeviceException("boom") }
            .direct("ctrl-fail") { _, _ -> throw DeviceException("bad\u001b[31m\nx") }
            .direct("ctrl-text") { _, _ -> arrayOf(DeviceValue.of("a\u001bb\nc")) }
            .direct("emit") { _, _ ->
                context!!.postEvent("device-attached", DeviceValue.of(C), DeviceValue.of("test:fake"))
                emptyArray()
            }
            .mainThread("main-size") { _, _ ->
                mainCalls.incrementAndGet()
                arrayOf(DeviceValue.of(80L), DeviceValue.of(25L))
            }
            .build()
    }

    private val image: ImageFileSystem by lazy { requireNotNull(ImageFileSystem.bundled("os")) { "os.zip がリソースにない" } }

    private class Result(val console: String, val state: ProcessState, val exitCode: Int?, val kind: ProcessKind)

    /** 標準 OS のイメージを `/` にしたマシンに [devices] をつなぎ、[args] を動かして終わるまで待つ */
    private fun run(args: List<String>, devices: Map<String, Device>, path: String = "/bin/${args[0]}"): Result = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val host = ConsoleHost()
            val m = h.machine(fuelPerTick = 10_000_000, host = host, memoryBytes = 8L * 1024 * 1024)
            m.vfs.mount(VfsPath.ROOT, image, readOnly = true, source = "disk:image:os")
            for ((address, device) in devices) assertTrue(m.devices.attach(address, device))
            val pid = m.exec(
                path,
                KernelDrivers.DEFAULT,
                SpawnOptions(args = args, env = mapOf("PATH" to "/bin"), cwd = "/", fds = SpawnOptions.ROOT_PREOPEN),
            )
            val kind = m.rt(pid).driver.kind
            eventuallyDescribe(150_000, { "終わらない: ${host.text()}" }) {
                h.scheduler.tick()
                Thread.sleep(1)
                m.stateOf(pid).isTerminal
            }
            val exitCode = m.lock.withLockValue { m.kernel.processes[pid]!!.exitCode }
            repeat(5) { h.scheduler.tick() }
            Result(host.text(), m.stateOf(pid), exitCode, kind)
        }
    }

    /** `/bin/sh -c [script]` の出力 */
    private fun sh(script: String, devices: Map<String, Device>): String {
        val r = run(listOf("sh", "-c", script.trimIndent()), devices)
        assertEquals(ProcessState.EXITED, r.state, r.console)
        return r.console
    }

    private fun devices(): Map<String, Device> = linkedMapOf(A to TestDev(), B to TestDev(), S to TestDev("test:screen"))

    @Test
    fun `devcallはメインスレッドのメソッドを待機点型で呼び、結果を1行に1つ書く`() {
        val screen = TestDev("test:screen")
        val r = run(listOf("devcall", "screen", "main-size"), mapOf(S to screen))
        assertEquals(ProcessState.EXITED, r.state, r.console)
        assertEquals(0, r.exitCode, r.console)
        assertEquals("80\n25\n", r.console)
        assertEquals(ProcessKind.WAIT_POINT, r.kind)
        assertEquals(1, screen.mainCalls.get())
    }

    @Test
    fun `devcallの値の往復`() {
        val out = sh(
            """
            devcall $A echo none true false 42 -7 +3 1.5 2.0 -0.0 hello s:42 i:5 f:3 b:true x:00ff7f x: "a b" 9223372036854775807 -9223372036854775808
            echo ---
            devcall $A types none 1 1.0 true s x:00 s:1 1e3 f:inf 1.2.3 minecraft:stone
            echo ---
            devcall $A echo f:inf f:-inf f:nan 1e20 1e-7 0.00001 123456789012345678.0
            """,
            devices(),
        )
        assertEquals(
            """
            none
            true
            false
            42
            -7
            3
            1.5
            2.0
            -0.0
            hello
            42
            5
            3.0
            true
            x:00ff7f
            x:
            a b
            9223372036854775807
            -9223372036854775808
            ---
            none
            integer
            float
            boolean
            text
            bytes
            text
            float
            float
            text
            text
            ---
            f:inf
            f:-inf
            f:nan
            1e20
            1e-7
            0.00001
            1.2345678901234568e17
            """.trimIndent() + "\n",
            out,
        )
    }

    @Test
    fun `devcallのデバイスの指定と失敗`() {
        val long65 = "a".repeat(65)
        val long64 = "a".repeat(64)
        val out = sh(
            """
            devcall screen get-size; echo ${'$'}?
            devcall ${S.uppercase()} get-size
            devcall 00000000-0000-4000-8000-0000000001c get-size
            devcall test:screen main-size
            devcall dev echo 1; echo ${'$'}?
            devcall 00000000-0000-4000-8000-000000000999 echo; echo ${'$'}?
            devcall nothing echo; echo ${'$'}?
            devcall $A nothing; echo ${'$'}?
            devcall $A fail; echo ${'$'}?
            devcall $A needs-int x; echo ${'$'}?
            devcall $A ctrl-fail; echo ${'$'}?
            devcall $A ctrl-text
            devcall $A $long65; echo ${'$'}?
            devcall $A $long64; echo ${'$'}?
            devcall $A echo 9223372036854775808; echo ${'$'}?
            devcall $A echo -9223372036854775809; echo ${'$'}?
            devcall $A echo 1 i:x; echo ${'$'}?
            devcall $A echo x:0; echo ${'$'}?
            devcall $A echo x:zz; echo ${'$'}?
            devcall $A echo b:yes; echo ${'$'}?
            devcall $A echo f:abc; echo ${'$'}?
            devcall $A echo ${(1..32).joinToString(" ")} | wc -l
            devcall $A echo ${(1..33).joinToString(" ")}; echo ${'$'}?
            devcall $A; echo ${'$'}?
            devcall -x; echo ${'$'}?
            """,
            devices(),
        )
        assertEquals(
            """
            80
            25
            0
            80
            25
            80
            25
            80
            25
            devcall: dev: ambiguous device
            1
            devcall: 00000000-0000-4000-8000-000000000999: no such device
            1
            devcall: nothing: no such device
            1
            devcall: nothing: no such method
            1
            devcall: fail: boom
            1
            devcall: needs-int: argument #1: integer expected, got text
            1
            devcall: ctrl-fail: bad?[31m?x
            1
            a?b?c
            devcall: $long65: method name too long
            1
            devcall: $long64: no such method
            1
            devcall: argument 1: integer out of range
            2
            devcall: argument 1: integer out of range
            2
            devcall: argument 2: invalid integer
            2
            devcall: argument 1: invalid hex bytes
            2
            devcall: argument 1: invalid hex bytes
            2
            devcall: argument 1: invalid boolean (true or false)
            2
            devcall: argument 1: invalid number
            2
            32
            devcall: too many arguments (max 32)
            1
            devcall: missing operand
            usage: devcall device method [arg ...]
            2
            devcall: unknown option -x
            usage: devcall device method [arg ...]
            2
            """.trimIndent() + "\n",
            out,
        )
    }

    @Test
    fun `lsdevは一覧とメソッドを書き、指定で絞る`() {
        val out = sh(
            """
            lsdev
            echo ---
            lsdev -l screen
            echo ---
            lsdev ${B.uppercase()} nothing; echo ${'$'}?
            echo ---
            lsdev dev | wc -l
            lsdev -x; echo ${'$'}?
            lsdev -w x; echo ${'$'}?
            """,
            devices(),
        )
        assertEquals(
            """
            $A test:dev
            $B test:dev
            $S test:screen
            ---
            $S test:screen
              echo
              types
              get-size
              needs-int
              fail
              ctrl-fail
              ctrl-text
              emit
              main-size (main)
            ---
            lsdev: nothing: no such device
            $B test:dev
            1
            ---
            2
            lsdev: unknown option -x
            usage: lsdev [-l] [device ...] | lsdev -w
            2
            lsdev: -w takes no other options or operands
            usage: lsdev [-l] [device ...] | lsdev -w
            2
            """.trimIndent() + "\n",
            out,
        )
    }

    @Test
    fun `lsdev -wは接続と切断を書き、デバイスが出した同じ名前のイベントは無視する`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val host = ConsoleHost()
            val m = h.machine(fuelPerTick = 10_000_000, host = host, memoryBytes = 8L * 1024 * 1024)
            m.vfs.mount(VfsPath.ROOT, image, readOnly = true, source = "disk:image:os")
            val a = TestDev()
            assertTrue(m.devices.attach(A, a))
            val pid = m.exec("/bin/lsdev", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("lsdev", "-w"), cwd = "/", fds = SpawnOptions.ROOT_PREOPEN))
            assertEquals(ProcessKind.WAIT_POINT, m.rt(pid).driver.kind)

            fun waitFor(expected: String) = eventuallyDescribe(60_000, { "出ない: [$expected] in ${host.text()}" }) {
                h.scheduler.tick()
                Thread.sleep(1)
                host.text() == expected
            }
            waitFor("$A test:dev\n")
            // 待っている（イベントを待つ間は動かない）ことを確かめてから変える
            eventually(30_000, "待たない") {
                h.scheduler.tick()
                Thread.sleep(1)
                m.stateOf(pid) == ProcessState.WAITING_EVENT
            }
            assertTrue(m.devices.attach(C, TestDev("test:screen")))
            waitFor("$A test:dev\n+ $C test:screen\n")
            // デバイスが出した device-attached（source が address）は書かない
            a.context!!.postEvent("device-attached", DeviceValue.of(B), DeviceValue.of("test:fake"))
            assertTrue(m.devices.detach(A))
            waitFor("$A test:dev\n+ $C test:screen\n- $A test:dev\n")
            m.lock.withLockValue { m.killLocked(pid, KillSignal.INTERRUPT) }
            eventually(30_000, "終わらない") {
                h.scheduler.tick()
                Thread.sleep(1)
                m.stateOf(pid).isTerminal
            }
            assertEquals("$A test:dev\n+ $C test:screen\n- $A test:dev\n", host.text())
        }
    }

    @Test
    fun `lsdev -wはキューがあふれたら一覧を読み直して、捨てた分の差を書く`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val host = ConsoleHost()
            val m = h.machine(fuelPerTick = 10_000_000, host = host, memoryBytes = 8L * 1024 * 1024, maxDevices = 100)
            m.vfs.mount(VfsPath.ROOT, image, readOnly = true, source = "disk:image:os")
            assertTrue(m.devices.attach(A, TestDev()))
            val pid = m.exec("/bin/lsdev", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("lsdev", "-w"), cwd = "/", fds = SpawnOptions.ROOT_PREOPEN))
            eventuallyDescribe(60_000, { "書かない: ${host.text()}" }) {
                h.scheduler.tick()
                Thread.sleep(1)
                host.text() == "$A test:dev\n" && m.stateOf(pid) == ProcessState.WAITING_EVENT
            }
            // tick を進めない間に、キューの容量（64）を超えて接続し、A も外す（あふれた分は捨てられ、切断も捨てられる）
            val count = EventLimits.DEFAULT_CAPACITY + 6
            val added = (1..count).map { "00000000-0000-4000-8000-%012x".format(0x1000 + it) }
            for (address in added) assertTrue(m.devices.attach(address, TestDev("test:bulk")))
            assertTrue(m.devices.detach(A))
            val expected = listOf("$A test:dev", "- $A test:dev") + added.map { "+ $it test:bulk" }
            eventuallyDescribe(60_000, { "読み直さない: ${host.text()}" }) {
                h.scheduler.tick()
                Thread.sleep(1)
                host.text().lines().filter { it.isNotEmpty() }.size >= expected.size
            }
            repeat(20) { h.scheduler.tick() }
            val lines = host.text().lines().filter { it.isNotEmpty() }
            // 捨てた接続も切断も、差として 1 回ずつ書く
            assertEquals(expected.sorted(), lines.sorted(), host.text())
            m.lock.withLockValue { m.killLocked(pid, KillSignal.INTERRUPT) }
            eventually(30_000, "終わらない") {
                h.scheduler.tick()
                Thread.sleep(1)
                m.stateOf(pid).isTerminal
            }
        }
    }
}
