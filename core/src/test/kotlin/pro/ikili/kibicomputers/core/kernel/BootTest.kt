package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import pro.ikili.kibicomputers.core.kernel.boot.BootVolume
import pro.ikili.kibicomputers.core.kernel.boot.BootVolumeOpener
import pro.ikili.kibicomputers.core.kernel.fd.STDERR
import pro.ikili.kibicomputers.core.kernel.fd.STDIN
import pro.ikili.kibicomputers.core.kernel.fd.STDOUT
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.process.KillResult
import pro.ikili.kibicomputers.core.kernel.process.KillSignal
import pro.ikili.kibicomputers.core.kernel.process.Process
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FileType
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.OpenFile
import pro.ikili.kibicomputers.core.kernel.vfs.HostDiskLeases
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.TmpFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.runtime.FakeModule
import pro.ikili.kibicomputers.core.runtime.WasmFunctionType
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind
import pro.ikili.kibicomputers.core.runtime.WasmValueType
import pro.ikili.kibicomputers.core.wasi.p1.P1
import pro.ikili.kibicomputers.core.wasi.p1.WasiP1Kibi
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** 起動シーケンス（/sbin/init）と停止・再起動（#49） */
class BootTest {
    private val limit = Duration.ofSeconds(30)

    private companion object {
        /** ロックなしの読み手が終わるのを待つ上限。普通はすぐ終わるので、混んだ CI でも足りる長さにする */
        const val READER_WAIT_MILLIS = 10_000L
    }

    /** 通知を起きた順に記録する接続層 */
    private class Recorder : MachineHost {
        val log: MutableList<String> = Collections.synchronizedList(ArrayList())
        val console = StringBuffer()
        val reboots = AtomicInteger()
        val failures: MutableList<BootFailure> = Collections.synchronizedList(ArrayList())

        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) {
            log += "state:$state"
        }

        override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
            val text = String(bytes, Charsets.UTF_8).replace("\r\n", "\n")
            console.append(text)
            log += "console:$text"
        }

        override fun onBootFailed(machine: Machine, failure: BootFailure, detail: String) {
            failures += failure
            log += "bootFailed:$failure"
        }

        override fun onRebootRequested(machine: Machine) {
            reboots.incrementAndGet()
            log += "reboot"
            super.onRebootRequested(machine)
        }

        fun states(): List<String> = synchronized(log) { log.filter { it.startsWith("state:") } }
    }

    /** ディスクの識別子 → メモリのファイルシステム */
    private class Disks : BootVolumeOpener {
        val map = HashMap<String, TestMemoryFileSystem>()

        /** 読み取り専用で開くディスク（同梱イメージの代わり） */
        val readOnly = HashSet<String>()

        override fun open(diskId: String): BootVolume? {
            if (diskId == "throws") throw IllegalStateException("開けない")
            return map[diskId]?.let { BootVolume(it, readOnly = diskId in readOnly) }
        }

        fun empty(id: String): TestMemoryFileSystem = TestMemoryFileSystem().also { map[id] = it }

        /** `/sbin/init` に [bytes] を置いたディスク */
        fun withInit(id: String, bytes: ByteArray): TestMemoryFileSystem = empty(id).also { fs ->
            fs.createDirectory(VfsPath.parse("/sbin"))
            val node = fs.createFile(VfsPath.parse(BootConfig.INIT_PATH))
            fs.write(node, 0, bytes, 0, bytes.size)
        }
    }

    private class Env(val h: Harness, val m: Machine, val host: Recorder, val disks: Disks) {
        private var counter = 0

        /** [module] を実行ファイルのバイト列にする */
        fun exe(module: FakeModule): ByteArray = "fake-init-${counter++}".toByteArray().also { h.runtime.define(it, module) }

        fun tickUntil(message: String = "条件がそろわない", condition: () -> Boolean) = eventually(10_000, message) {
            h.scheduler.tick()
            Thread.sleep(1)
            condition()
        }

        /**
         * init が CPU を取って動き出す（pid 1 が CREATED を抜ける）まで tick を回す。
         * RUNNING が見えても、init の専用スレッドはまだ `register()` の前のことがある。その間に reboot や写しの kill が入ると、
         * register が KILLED にして unregister（beforeUnregister のフック）を通らないので、場面を作れない（#542、#624）。
         * CREATED を抜けていれば、register を通って runBlocking の try/finally の中にいる（tick で燃料を配らないと抜けない）
         */
        fun tickUntilInitStarted() = tickUntil("init が CPU を取らない") {
            m.state == MachineState.RUNNING && !m.isBooting &&
                process(1)?.state.let { it != null && it != ProcessState.CREATED && !it.isTerminal }
        }

        /** しばらく tick を回す */
        fun tickFor(millis: Long) {
            val end = System.currentTimeMillis() + millis
            while (System.currentTimeMillis() < end) {
                h.scheduler.tick()
                Thread.sleep(1)
            }
        }

        fun process(pid: Int): Process? = m.lock.withLockValue { m.kernel.processes[pid] }

        fun pids(): List<Int> = m.lock.withLockValue { m.kernel.processes.all().map { it.pid } }

        fun mounts(): List<Pair<String, String>> = m.lock.withLockValue { m.kernel.mounts.entries.map { it.point.toString() to it.source } }

        fun bootDisks(vararg ids: String): BootConfig = BootConfig.builder().bootDisks(ids.toList()).build()

        fun withHome(home: String?, vararg ids: String): BootConfig = BootConfig.builder().bootDisks(ids.toList()).homeDisk(home).build()
    }

    private fun env(block: Env.() -> Unit) = timeout(limit) {
        Harness().use { h ->
            val host = Recorder()
            val disks = Disks()
            h.scheduler.hooks.bootVolumes = disks
            val m = h.machine(host = host)
            Env(h, m, host, disks).block()
            m.stop()
            assertTrue(m.awaitTermination(5_000))
        }
    }

    private val procExitType = WasmFunctionType.of(listOf(WasmValueType.I32), emptyList())

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

    /** `proc_exit(code)` で終わる init（[code] が `null` なら `_start` から戻る） */
    private fun exitingInit(code: Int?): FakeModule = FakeModule.builder()
        .fuelMetered()
        .import(P1.MODULE, "proc_exit", procExitType)
        .export("_start", VOID) { ctx, _ ->
            ctx.step(1)
            if (code != null) ctx.callImport(P1.MODULE, "proc_exit", code.toLong())
            LongArray(0)
        }
        .build()

    private fun trappingInit(): FakeModule = FakeModule.builder()
        .fuelMetered()
        .export("_start", VOID) { ctx, _ -> ctx.trap(WasmTrapKind.UNREACHABLE) }
        .build()

    @Test
    fun `initのある最初のディスクを根にしてpid1のinitを動かす`() = env {
        disks.empty("a")
        disks.withInit("b", exe(foreverInit()))
        disks.withInit("c", exe(foreverInit()))
        assertTrue(m.boot(bootDisks("throws", "missing", "a", "b", "c")))
        // 状態の後に印が下りるので、ロックなしで読むときは両方がそろうまで待つ（#625）
        tickUntil { m.state == MachineState.RUNNING && !m.isBooting && host.console.contains("booting") }
        val init = process(1)!!
        assertEquals(0, init.parentPid)
        assertEquals(listOf("/sbin/init"), init.args)
        assertEquals(emptyMap<String, String>(), init.env)
        assertEquals("/", init.cwd.toString())
        assertEquals(ProcessKind.BLOCKING, init.kind)
        val console = m.lock.withLockValue { m.kernel.consoleTtyId }
        for (fd in listOf(STDIN, STDOUT, STDERR)) assertEquals(console, init.handles[fd]!!.objectId, "fd $fd はコンソール")
        // fd 3 は preopen の `/`（#71）
        m.lock.withLockValue {
            val root = m.kernel.objects[init.handles[3]!!.objectId] as OpenFile
            assertTrue(root.isDirectory)
            assertEquals(VfsPath.ROOT, root.path)
            assertEquals(1, m.kernel.objects.refCount(root.id))
        }
        assertEquals(setOf(0, 1, 2, 3), init.handles.entries().keys)
        assertEquals(listOf("/" to "disk:b", "/tmp" to "tmp", "/dev" to "dev"), mounts())
        assertEquals("kibi: booting from disk b\n", host.console.toString())
        assertFalse(m.boot(bootDisks("b")), "動いている間は起動しない")

        m.stop()
        tickUntil { m.state == MachineState.STOPPED }
        tickFor(50)
        assertEquals(listOf("state:RUNNING", "state:STOPPING", "state:STOPPED"), host.states())
        assertFalse(host.console.contains("init"), "stop() ではメッセージを出さない: ${host.console}")
        assertEquals(listOf("throws", "missing", "a", "b", "c"), m.lastBootConfig!!.bootDisks)
    }

    @Test
    fun `起動に失敗するとメッセージとonBootFailedで知らせ、STOPPEDのまま`() = env {
        disks.empty("empty")
        disks.withInit("garbage", "not-a-registered-module".toByteArray())
        disks.withInit("library", exe(FakeModule.builder().fuelMetered().export("run", VOID) { _, _ -> LongArray(0) }.build()))
        disks.withInit("unmetered", exe(FakeModule.builder().export("_start", VOID) { _, _ -> LongArray(0) }.build()))
        disks.empty("dir").also { fs ->
            fs.createDirectory(VfsPath.parse("/sbin"))
            fs.createDirectory(VfsPath.parse("/sbin/init"))
        }
        disks.withInit("good", exe(foreverInit()))
        val cases = listOf(
            BootConfig.builder().firmwarePresent(false).addBootDisk("good").build() to
                (BootFailure.NO_FIRMWARE to "kibi: no boot firmware"),
            bootDisks() to (BootFailure.NO_BOOT_DISK to "kibi: no bootable disk"),
            bootDisks("missing", "missing2") to (BootFailure.NO_BOOT_DISK to "kibi: no bootable disk"),
            bootDisks("missing", "empty", "dir") to (BootFailure.NO_INIT to "kibi: /sbin/init not found"),
            bootDisks("garbage") to (BootFailure.INIT_NOT_EXECUTABLE to "kibi: cannot start /sbin/init: NOEXEC"),
            bootDisks("library") to (BootFailure.INIT_NOT_EXECUTABLE to "kibi: cannot start /sbin/init: NOEXEC"),
            bootDisks("unmetered") to (BootFailure.INIT_START_FAILED to "kibi: cannot start /sbin/init: invalid"),
        )
        for ((config, expected) in cases) {
            val (failure, message) = expected
            host.log.clear()
            host.console.setLength(0)
            assertTrue(m.boot(config), "$config")
            tickUntil("$config で失敗が届かない") { host.log.any { it.startsWith("bootFailed:") } }
            assertFalse(m.isBooting)
            val consoleAt = host.log.indexOfFirst { it.startsWith("console:") }
            val failedAt = host.log.indexOf("bootFailed:$failure")
            assertTrue(consoleAt in 0 until failedAt, "コンソール → onBootFailed の順: ${host.log}")
            assertTrue(host.console.contains("$message\n"), "$config: ${host.console}")
            if (failure == BootFailure.INIT_NOT_EXECUTABLE) {
                // 実行できない理由を次の行に出す（#367）
                val last = host.console.toString().trimEnd('\n').substringAfterLast('\n')
                assertTrue(last.startsWith("kibi: ") && last != message, "$config: ${host.console}")
            } else {
                assertTrue(host.console.endsWith("$message\n"), "$config: ${host.console}")
            }
            assertEquals(MachineState.STOPPED, m.state)
            assertEquals(emptyList<String>(), host.states(), "$config: 状態は変わらない")
            assertEquals(emptyList<Pair<String, String>>(), mounts(), "$config: マウントを残さない")
            assertEquals(emptyList<Int>(), pids())
        }
    }

    @Test
    fun `initが終わるとメッセージを出し、ほかのプロセスを止めてSTOPPEDになる`() = env {
        val cases = listOf(
            exitingInit(null) to "kibi: init exited with code 0",
            exitingInit(3) to "kibi: init exited with code 3",
            trappingInit() to "kibi: init crashed: unreachable executed",
        )
        for ((i, case) in cases.withIndex()) {
            val (module, message) = case
            disks.withInit("d$i", exe(module))
            host.console.setLength(0)
            host.log.clear()
            assertTrue(m.boot(bootDisks("d$i")))
            tickUntil("$message が出ない") { host.console.contains(message) && m.state == MachineState.STOPPED }
            assertTrue(m.awaitTermination(5_000))
            assertEquals("kibi: booting from disk d$i\n$message\n", host.console.toString())
            assertEquals(listOf("state:RUNNING", "state:STOPPED"), host.states())
            assertEquals(listOf(1), pids(), "前の起動のゾンビは残らず、pid は 1 から")
        }
    }

    @Test
    fun `initが終わると残りのプロセスはKILLEDになる`() = env {
        val go = CountDownLatch(1)
        disks.withInit("d", exe(
            FakeModule.builder()
                .fuelMetered()
                .export("_start", VOID) { ctx, _ ->
                    while (!go.await(1, TimeUnit.MILLISECONDS)) ctx.step(1)
                    LongArray(0)
                }
                .build(),
        ))
        assertTrue(m.boot(bootDisks("d")))
        tickUntil { m.state == MachineState.RUNNING }
        val child = m.spawnTest(h.program(spinForever()), runDriver(), parentPid = 1)
        tickUntil { m.stateOf(child) == ProcessState.RUNNING || m.stateOf(child) == ProcessState.WAITING_FUEL }
        go.countDown()
        tickUntil { host.states().contains("state:STOPPED") }
        assertEquals(ProcessState.EXITED, m.stateOf(1))
        assertEquals(ProcessState.KILLED, m.stateOf(child))
        assertTrue(host.console.contains("kibi: init exited with code 0\n"), host.console.toString())
        assertEquals(listOf("state:RUNNING", "state:STOPPING", "state:STOPPED"), host.states())
    }

    @Test
    fun `rebootはすべて止まってからonRebootRequestedを1回呼び、既定の実装でもう一度起動する`() = env {
        disks.withInit("d", exe(foreverInit()))
        assertFalse(m.reboot(), "一度も起動していなければ受け付けない")
        assertTrue(m.boot(bootDisks("d")))
        tickUntil { m.state == MachineState.RUNNING }
        val child = m.spawnTest(h.program(spinForever()), runDriver(), parentPid = 1)
        assertEquals(2, child)
        val firstInit = process(1)!!
        val oldObjects = listOf(firstInit.objectId, process(child)!!.objectId)

        assertTrue(m.reboot())
        tickUntil { host.reboots.get() == 1 && m.state == MachineState.RUNNING }
        tickFor(50)
        assertEquals(1, host.reboots.get())
        assertEquals(listOf("state:RUNNING", "state:STOPPING", "state:STOPPED", "state:RUNNING"), host.states())
        val rebootAt = host.log.indexOf("reboot")
        assertTrue(rebootAt > host.log.indexOf("state:STOPPED"), "止まってから再起動: ${host.log}")
        assertTrue(process(1) !== firstInit, "init を作り直す")
        assertEquals(listOf(1), pids(), "前の起動のゾンビは残らない")
        m.lock.withLockValue {
            for (id in oldObjects) assertNull(m.kernel.objects[id], "前の起動のゾンビの ProcessObject は残らない（#46）")
        }
        assertEquals(listOf("/" to "disk:d", "/tmp" to "tmp", "/dev" to "dev"), mounts(), "前の起動のマウントは残らない")
        assertFalse(host.console.contains("init exited"), host.console.toString())
        assertEquals(2, Regex("booting from disk d").findAll(host.console).count())
    }

    @Test
    fun `initにはシグナルを送れず、Ctrl+Cでも止まらない`() = env {
        disks.withInit("d", exe(foreverInit()))
        assertTrue(m.boot(bootDisks("d")))
        tickUntil { m.state == MachineState.RUNNING }
        for (signal in KillSignal.entries) {
            assertEquals(KillResult.NOT_PERMITTED, m.lock.withLockValue { m.killLocked(1, signal) })
        }
        m.lock.withLockValue { m.consoleTtyLocked().foregroundPid = 1 }
        m.offerConsoleInput(byteArrayOf(0x03, 0x1C))
        tickFor(30)
        assertEquals(MachineState.RUNNING, m.state)
        assertTrue(!m.stateOf(1).isTerminal, "init は動き続ける")
        m.stop()
        assertTrue(m.awaitTermination(5_000))
    }

    @Test
    fun `rebootの後にstopすると再起動しない`() = env {
        disks.withInit("d", exe(foreverInit()))
        assertTrue(m.boot(bootDisks("d")))
        tickUntil { m.state == MachineState.RUNNING }
        assertTrue(m.reboot())
        m.stop()
        tickUntil { m.state == MachineState.STOPPED }
        assertTrue(m.awaitTermination(5_000))
        tickFor(100)
        assertEquals(0, host.reboots.get())
        assertEquals(MachineState.STOPPED, m.state)
    }

    @Test
    fun `再起動の途中は止まっていない扱いで、ロックを持たれていても読み手は待たない`() = env {
        // #626: reboot() を受け付けてから起動し直すまで（STOPPING → STOPPED で求めを待つ → 通知を積む）も、画面をつなぎ直してよい
        disks.withInit("d", exe(foreverInit()))
        assertTrue(m.boot(bootDisks("d")))
        tickUntilInitStarted()
        assertTrue(m.isRunningBootingOrRebooting)
        // init の専用スレッドを、プロセスが終わった後・登録を外す前で止める（STOPPED で求めの印だけが立った場面を作る）
        val atGate = CountDownLatch(1)
        val gate = CountDownLatch(1)
        h.scheduler.hooks.beforeUnregister = { machine, pid ->
            if (machine === m && pid == 1 && atGate.count > 0) {
                atGate.countDown()
                gate.awaitKeepingInterrupt()
            }
        }
        try {
            assertTrue(m.reboot())
            assertFalse(m.isRunningOrBooting, "isRunningOrBooting は再起動の途中を含まない")
            assertTrue(m.isRunningBootingOrRebooting, "reboot を受け付けた直後")
            assertTrue(atGate.await(5, TimeUnit.SECONDS), "init のスレッドが登録を外す前に来ない")
            h.scheduler.hooks.beforeUnregister = null
            tickFor(50)
            assertEquals(MachineState.STOPPED, m.state)
            assertFalse(m.isBooting)
            assertEquals(0, host.reboots.get())
            assertTrue(m.isRunningBootingOrRebooting, "止まり切って、求めを積む前")
            // カーネルロックを持っている間に、別のスレッド（メインスレッドの代わり）に読ませる。ロックを取らないので待たない
            val value = AtomicReference<Boolean?>()
            val stuck = AtomicBoolean()
            m.lock.withLockValue {
                val reader = Thread { value.set(m.isRunningBootingOrRebooting) }.apply { isDaemon = true }
                reader.start()
                reader.join(READER_WAIT_MILLIS)
                stuck.set(reader.isAlive)
            }
            assertFalse(stuck.get(), "読み手が終わらない（ロックを取る読み方になっていないか）")
            assertEquals(true, value.get())
        } finally {
            h.scheduler.hooks.beforeUnregister = null
            gate.countDown()
        }
        tickUntil { host.reboots.get() == 1 && m.state == MachineState.RUNNING && !m.isBooting }
        assertTrue(m.isRunningBootingOrRebooting, "起動し直した後")
    }

    @Test
    fun `再起動の通知を積んで配る前も止まっていない扱いで、stopで取り消すと止まった扱い`() = env {
        disks.withInit("d", exe(exitingInit(0)))
        assertTrue(m.boot(bootDisks("d")))
        tickUntil { host.console.contains("init exited") && m.state == MachineState.STOPPED }
        assertTrue(m.awaitTermination(5_000))
        assertFalse(m.isRunningBootingOrRebooting, "ゲスト（init）が止めたマシン")
        // STOPPED でプロセスもスレッドもないので、すぐ通知を積む（配るのは次の tick）
        assertTrue(m.reboot())
        assertEquals(MachineState.STOPPED, m.state)
        assertTrue(m.isRebootPending)
        assertTrue(m.isRunningBootingOrRebooting, "通知を積んで、配る前")
        m.stop()
        assertFalse(m.isRunningBootingOrRebooting, "stop で取り消した再起動は含まない")
        tickFor(100)
        assertEquals(0, host.reboots.get())
        assertFalse(m.isRunningBootingOrRebooting)
    }

    @Test
    fun `再起動の通知を積む区間の途中でも、ロックなしの読み手には再起動の途中に見える`() = env {
        // #626: 通知の印を立ててから求めの印を下ろす（順番を戻すと、間で両方偽に見える窓ができる）。区間の途中で読ませる
        disks.withInit("d", exe(exitingInit(0)))
        assertTrue(m.boot(bootDisks("d")))
        tickUntil { host.console.contains("init exited") && m.state == MachineState.STOPPED }
        assertTrue(m.awaitTermination(5_000))
        val seen = ArrayList<Boolean>()
        val stuck = AtomicBoolean()
        h.scheduler.hooks.duringRebootPost = { machine ->
            h.scheduler.hooks.duringRebootPost = null
            // カーネルロックを持たない別のスレッド（メインスレッドの代わり）に読ませる。上限の付け方は duringInitSpawn のテストと同じ
            val value = AtomicReference<Boolean?>()
            val reader = Thread { value.set(machine.isRunningBootingOrRebooting) }.apply { isDaemon = true }
            reader.start()
            reader.join(READER_WAIT_MILLIS)
            if (reader.isAlive) stuck.set(true) else value.get()?.let { synchronized(seen) { seen += it } }
        }
        // STOPPED でプロセスもスレッドもないので、reboot の中で通知を積む（フックはそこで呼ばれる）
        assertTrue(m.reboot())
        assertFalse(stuck.get(), "読み手が終わらない（ロックを取る読み方になっていないか）")
        assertEquals(listOf(true), synchronized(seen) { seen.toList() }, "通知を積む区間の途中で、再起動の途中でないように見えた")
        assertTrue(m.isRebootPending)
        assertTrue(m.isRunningBootingOrRebooting, "積んだ後")
    }

    @Test
    fun `再起動の途中でもCRASHEDなら止まった扱い`() = env {
        // #535: reboot の後にマシンの不整合で止まっても、印は下りない（stopLocked は印を触らない）。CRASHED にはつながない
        disks.withInit("d", exe(exitingInit(0)))
        assertTrue(m.boot(bootDisks("d")))
        tickUntil { host.console.contains("init exited") && m.state == MachineState.STOPPED }
        assertTrue(m.awaitTermination(5_000))
        assertTrue(m.reboot())
        m.lock.withLockValue { m.stopLocked(IllegalStateException("テストの不整合")) }
        assertEquals(MachineState.CRASHED, m.state)
        assertTrue(m.isRebootPending, "印は残っている")
        assertFalse(m.isRunningBootingOrRebooting)
        tickFor(100)
        assertEquals(MachineState.CRASHED, m.state)
        assertFalse(m.isRunningBootingOrRebooting)
    }

    @Test
    fun `initを作る区間の途中でも、ロックなしの読み手には止まって見えない`() = env {
        // #625: 起動中の印を下ろしてから RUNNING にするまでの間に、ロックを取らずに読むと「STOPPED で起動中でもない」に見えた
        disks.withInit("d", exe(foreverInit()))
        val seen = ArrayList<Boolean>()
        val stuck = AtomicBoolean()
        val hookDone = CountDownLatch(1)
        h.scheduler.hooks.duringInitSpawn = { machine ->
            h.scheduler.hooks.duringInitSpawn = null
            try {
                // カーネルロックを持たない別のスレッド（メインスレッドの代わり）に読ませる
                val value = AtomicReference<Boolean?>()
                val reader = Thread { value.set(machine.isRunningOrBooting) }.apply { isDaemon = true }
                reader.start()
                // ロックを取らない読み方なら、すぐ終わる。ロックを取る読み方に変わるとここでは終わらないので、デッドロックしないよう
                // 待つ時間に上限を付ける。上限は、CI が混んでいてもスレッドが動き出せるだけ長く取る（短いと、競合と関係なく落ちる）
                reader.join(READER_WAIT_MILLIS)
                if (reader.isAlive) stuck.set(true) else value.get()?.let { synchronized(seen) { seen += it } }
            } finally {
                hookDone.countDown()
            }
        }
        assertTrue(m.boot(bootDisks("d")))
        // フックが終わるまで、読み手を待つ上限より長く待つ。RUNNING を待つ締め切り（tickUntil）はフックより先に数え始めるので、
        // 先にこちらを待たないと、読み手が終わらないときに汎用の失敗で落ち、原因が分からない
        eventually(READER_WAIT_MILLIS + 10_000, "initを作る区間のフックまで進まない") {
            h.scheduler.tick()
            Thread.sleep(1)
            hookDone.count == 0L
        }
        assertFalse(stuck.get(), "読み手が終わらない（ロックを取る読み方になっていないか）")
        tickUntil { m.state == MachineState.RUNNING }
        assertTrue(m.isRunningOrBooting)
        assertEquals(listOf(true), synchronized(seen) { seen.toList() }, "区間の途中で、止まっていて起動中でもないように見えた")
    }

    @Test
    fun `initを表に足した後で例外が出ると、起動中の印を立てたままINIT_START_FAILEDで知らせる`() = env {
        // #625: 印は RUNNING にした後に下ろすので、その前の失敗では印が立っている。endBoot が印を下ろし、コンソールとホストに知らせる
        disks.withInit("d", exe(foreverInit()))
        val booting = AtomicReference<Boolean?>()
        h.scheduler.hooks.duringInitSpawn = { machine ->
            h.scheduler.hooks.duringInitSpawn = null
            booting.set(machine.isBooting)
            throw IllegalStateException("テストの失敗")
        }
        assertTrue(m.boot(bootDisks("d")))
        tickUntil("失敗が届かない") { host.log.any { it.startsWith("bootFailed:") } }
        assertEquals(true, booting.get(), "例外の時点では、起動中の印が立っている")
        assertFalse(m.isBooting)
        assertFalse(m.isRunningOrBooting)
        assertEquals(MachineState.STOPPED, m.state)
        assertTrue(host.log.contains("bootFailed:${BootFailure.INIT_START_FAILED}"), "${host.log}")
        assertTrue(host.console.contains("kibi: cannot start /sbin/init: machine not ready\n"), "${host.console}")
        assertEquals(emptyList<String>(), host.states(), "RUNNING にならない")
        // #629: 表に足した分は巻き戻され、マウントも外れ、待つ側も終わる
        assertEquals(emptyList<Int>(), pids())
        assertEquals(emptyList<Pair<String, String>>(), mounts())
        assertEquals(0, m.lock.withLockValue { m.kernel.initPid })
        assertEquals(0, h.scheduler.blockingProcesses)
        assertTrue(m.lock.withLockValue { m.runtimes.isEmpty() })
        assertTrue(m.awaitTermination(5_000))
    }

    @Test
    fun `initの作成を巻き戻した後も、もう一度起動するとpid1のinitが動く`() = env {
        disks.withInit("d", exe(foreverInit()))
        h.scheduler.hooks.duringInitSpawn = { _ ->
            h.scheduler.hooks.duringInitSpawn = null
            throw RuntimeException("テストの失敗")
        }
        assertTrue(m.boot(bootDisks("d")))
        tickUntil("失敗が届かない") { host.log.any { it.startsWith("bootFailed:") } }
        assertEquals(MachineState.STOPPED, m.state)
        assertEquals(emptyList<Int>(), pids())
        assertTrue(m.boot(bootDisks("d")))
        tickUntilInitStarted()
        assertEquals(listOf(1), pids(), "init の pid は次の起動で 1 に戻る")
        assertEquals(1, m.lock.withLockValue { m.kernel.initPid })
    }

    @Test
    fun `起動の途中のstopではinitを作らず、何も知らせない`() = env {
        disks.withInit("d", exe(foreverInit()))
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        h.scheduler.hooks.beforeInitExec = { _ ->
            reached.countDown()
            release.await()
        }
        assertTrue(m.boot(bootDisks("d")))
        assertTrue(reached.await(5, TimeUnit.SECONDS))
        assertTrue(m.isBooting)
        assertFalse(m.boot(bootDisks("d")), "起動の途中は受け付けない")
        m.stop()
        release.countDown()
        eventually { !m.isBooting }
        tickFor(50)
        assertEquals(emptyList<Int>(), pids())
        assertEquals(emptyList<Pair<String, String>>(), mounts())
        assertEquals(emptyList<String>(), host.states())
        assertTrue(host.failures.isEmpty())
        assertEquals(MachineState.STOPPED, m.state)
    }

    @Test
    fun `起動の途中のrebootは取り消してから起動し直す`() = env {
        disks.withInit("d", exe(foreverInit()))
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        h.scheduler.hooks.beforeInitExec = { _ ->
            h.scheduler.hooks.beforeInitExec = null
            reached.countDown()
            release.await()
        }
        assertTrue(m.boot(bootDisks("d")))
        assertTrue(reached.await(5, TimeUnit.SECONDS))
        assertTrue(m.reboot())
        release.countDown()
        tickUntil { host.reboots.get() == 1 && host.states().isNotEmpty() }
        assertEquals(listOf("state:RUNNING"), host.states())
        assertEquals(listOf(1), pids())
    }

    @Test
    fun `起動の途中のrebootでも、取り消して通知を積み、起動し直すまで止まっていない扱い`() = env {
        // #626: 起動の途中の reboot は、求めの印が立った後で endBoot が起動中の印を下ろし、maybePostRebootLocked が通知を積む。
        // その受け渡しの間も、ロックなしの読み手に「止まっていて再起動の途中でもない」に見えない
        disks.withInit("d", exe(foreverInit()))
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        h.scheduler.hooks.beforeInitExec = { _ ->
            h.scheduler.hooks.beforeInitExec = null
            reached.countDown()
            release.await()
        }
        assertTrue(m.boot(bootDisks("d")))
        assertTrue(reached.await(5, TimeUnit.SECONDS))
        assertTrue(m.isBooting)
        assertTrue(m.reboot())
        assertTrue(m.isBooting, "起動の手順はまだ終わっていない")
        assertEquals(MachineState.STOPPED, m.state)
        assertTrue(m.isRunningBootingOrRebooting, "起動の途中で reboot を受け付けた直後")
        // 起動の手順を進めて取り消させる間、別のスレッド（メインスレッドの代わり）に読み続けさせる。通知は tick で配るので、
        // 起動中の印が下りて通知を積むまでの間は、tick を回さない（配って起動し直す区間は、この読み手の対象にしない）
        val seenFalse = AtomicBoolean()
        val reads = AtomicInteger()
        val stopReader = AtomicBoolean()
        val reader = Thread {
            while (!stopReader.get()) {
                if (!m.isRunningBootingOrRebooting) seenFalse.set(true)
                reads.incrementAndGet()
            }
        }.apply { isDaemon = true }
        reader.start()
        try {
            eventually { reads.get() > 0 }
            release.countDown()
            eventually { !m.isBooting && m.isRebootPending }
        } finally {
            stopReader.set(true)
            reader.join(READER_WAIT_MILLIS)
        }
        assertFalse(reader.isAlive, "読み手が終わらない")
        assertFalse(seenFalse.get(), "起動の取り消しから通知を積むまでの間に、再起動の途中でないように見えた")
        assertEquals(MachineState.STOPPED, m.state)
        assertEquals(0, host.reboots.get(), "通知はまだ配っていない")
        assertTrue(m.isRunningBootingOrRebooting, "取り消して通知を積み、配る前")
        tickUntil { host.reboots.get() == 1 && m.state == MachineState.RUNNING && !m.isBooting }
        assertTrue(m.isRunningBootingOrRebooting, "起動し直した後")
    }

    @Test
    fun `CRASHEDと閉じたスケジューラでは起動しない`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            m.lock.withLockValue { m.inconsistencyLocked("テスト") }
            assertFalse(m.boot(BootConfig.builder().build()))
            assertFalse(m.reboot())
            val other = h.machine()
            h.scheduler.close()
            assertFalse(other.boot(BootConfig.builder().build()))
            assertNull(other.lastBootConfig)
        }
    }

    @Test
    fun `プロセスから停止と再起動を求められ、受け付けると呼んだプロセスはKILLEDで戻らない`() = env {
        disks.withInit("d", exe(foreverInit()))
        assertTrue(m.boot(bootDisks("d")))
        tickUntil { m.state == MachineState.RUNNING }
        val returned = AtomicBoolean()
        val asker = { reboot: Boolean ->
            TestDriver(ProcessKind.BLOCKING) { ctx ->
                if (reboot) ctx.requestReboot() else ctx.requestShutdown()
                returned.set(true)
                StepResult.Exit(0)
            }
        }
        // init が止まると子は表から消えるので、終わった状態は実行時の部分の Process から見る。
        // 子が spawn から戻る前に終わって刈り取られうるので、実行時の部分は spawn と同じロックの中で取る（spawnRt）
        val first = m.spawnRt(h.program(emptyModule()), asker(true), SpawnOptions(), parentPid = 1)
        tickUntil { host.reboots.get() == 1 && m.state == MachineState.RUNNING }
        assertEquals(ProcessState.KILLED, first.process.state)

        val second = m.spawnRt(h.program(emptyModule()), asker(false), SpawnOptions(), parentPid = 1)
        tickUntil { m.state == MachineState.STOPPED }
        assertTrue(m.awaitTermination(5_000))
        tickFor(50)
        assertFalse(returned.get(), "受け付けたら戻らない")
        assertEquals(1, host.reboots.get())
        assertFalse(host.console.contains("init exited"), host.console.toString())
        assertEquals(ProcessState.KILLED, m.stateOf(1))
        assertEquals(ProcessState.KILLED, second.process.state)
    }

    @Test
    fun `起動を通らずに作ったマシンではプロセスの再起動の求めを断り、プロセスは続く`() = env {
        val result = AtomicReference<Boolean?>()
        val pid = m.spawn(
            h.program(emptyModule()),
            TestDriver(ProcessKind.BLOCKING) { ctx ->
                result.set(ctx.requestReboot())
                StepResult.Exit(7)
            },
            SpawnOptions(),
        )
        tickUntil { m.stateOf(pid).isTerminal }
        assertEquals(false, result.get())
        assertEquals(ProcessState.EXITED, m.stateOf(pid))
        assertEquals(0, host.reboots.get())
    }

    @Test
    fun `確かめてから再起動を求めるまでの間にstopが入ると、再起動しない`() = env {
        disks.withInit("d", exe(foreverInit()))
        assertTrue(m.boot(bootDisks("d")))
        tickUntil { m.state == MachineState.RUNNING }
        val returned = AtomicBoolean()
        val asker = TestDriver(ProcessKind.BLOCKING) { ctx ->
            ctx.requestReboot()
            returned.set(true)
            StepResult.Exit(0)
        }
        // ホスト関数の包みの確認を過ぎた後、電源の求めの前に、プレイヤーが止める。
        // BLOCKING は spawn の中で専用スレッドが始まるので、フックは spawn の前に入れる（init は電源を求めないので pid で絞らない）
        h.scheduler.hooks.beforePowerRequest = { machine, _ ->
            h.scheduler.hooks.beforePowerRequest = null
            machine.stop()
        }
        val rt = m.spawnRt(h.program(emptyModule()), asker, SpawnOptions(), parentPid = 1)
        tickUntil { m.state == MachineState.STOPPED }
        assertTrue(m.awaitTermination(5_000))
        tickFor(100)
        assertEquals(0, host.reboots.get(), "止めたマシンは再起動しない: ${host.log}")
        assertEquals(MachineState.STOPPED, m.state)
        assertFalse(m.isRebootPending)
        assertFalse(returned.get(), "止められたプロセスには戻らない")
        assertEquals(ProcessState.KILLED, rt.process.state)
    }

    @Test
    fun `initが自分で停止・再起動を求めてもinit exitedは出ない`() = env {
        val asks = AtomicInteger()
        // p1 の import（kibi:computer/power）から求める。再起動は受け付ければ書き先に書かないので、0 を渡す
        val selfPowerInit = FakeModule.builder()
            .fuelMetered()
            .import(WasiP1Kibi.POWER, "shutdown", VOID)
            .import(WasiP1Kibi.POWER, "reboot", WasmFunctionType.of(listOf(WasmValueType.I32), emptyList()))
            .export("_start", VOID) { ctx, _ ->
                ctx.step(1)
                // 1回目の起動では再起動、2回目では停止
                if (asks.getAndIncrement() == 0) ctx.callImport(WasiP1Kibi.POWER, "reboot", 0L) else ctx.callImport(WasiP1Kibi.POWER, "shutdown")
                LongArray(0)
            }
            .build()
        disks.withInit("d", exe(selfPowerInit))
        assertTrue(m.boot(bootDisks("d")))
        tickUntil { host.reboots.get() == 1 && asks.get() == 2 && m.state == MachineState.STOPPED && !m.isRebootPending && !m.isBooting }
        assertTrue(m.awaitTermination(5_000))
        tickFor(50)
        assertEquals(1, host.reboots.get())
        assertEquals(listOf("state:RUNNING", "state:STOPPING", "state:STOPPED", "state:RUNNING", "state:STOPPING", "state:STOPPED"), host.states())
        assertFalse(host.console.contains("init exited"), host.console.toString())
        assertEquals(ProcessState.KILLED, m.stateOf(1))
    }

    @Test
    fun `tmpは起動ごとに空でマウントし、中身はRAMに数え、再起動で返す`() = env {
        disks.withInit("d", exe(foreverInit()))
        assertTrue(m.boot(bootDisks("d")))
        tickUntil { m.state == MachineState.RUNNING }
        val tmpFile = VfsPath.parse("/tmp/a.txt")
        run {
            val f = m.vfs.open(tmpFile, OpenOptions(read = true, write = true, create = true))
            val bytes = "hello".toByteArray()
            assertEquals(bytes.size, m.vfs.write(f, bytes, 0, bytes.size))
        }
        assertTrue(m.memoryAccount.usedBytes >= TmpFileSystem.CHUNK_BYTES, "/tmp の中身を RAM に数える")

        assertTrue(m.reboot())
        tickUntil { host.reboots.get() == 1 && m.state == MachineState.RUNNING }
        run {
            assertThrows<FsException> { m.vfs.stat(tmpFile) }
            assertEquals(FileType.DIRECTORY, m.vfs.stat(VfsPath.parse("/tmp")).type)
        }
        assertEquals(0L, m.memoryAccount.usedBytes, "前の起動の /tmp の分は返す")
    }

    @Test
    fun `根のtmpがファイルならマウントせずに起動を続ける`() = env {
        val disk = disks.withInit("d", exe(foreverInit()))
        disk.createFile(VfsPath.parse("/tmp"))
        assertTrue(m.boot(bootDisks("d")))
        tickUntil { m.state == MachineState.RUNNING && host.console.contains("booting") }
        assertEquals(listOf("/" to "disk:d", "/dev" to "dev"), mounts())
        assertEquals("kibi: /tmp not mounted (NOTDIR)\nkibi: booting from disk d\n", host.console.toString())
    }

    @Test
    fun `根のdevがファイルならマウントせずに起動を続ける`() = env {
        val disk = disks.withInit("d", exe(foreverInit()))
        disk.createFile(VfsPath.parse("/dev"))
        assertTrue(m.boot(bootDisks("d")))
        tickUntil { m.state == MachineState.RUNNING && host.console.contains("booting") }
        assertEquals(listOf("/" to "disk:d", "/tmp" to "tmp"), mounts())
        assertEquals("kibi: /dev not mounted (NOTDIR)\nkibi: booting from disk d\n", host.console.toString())
    }

    @Test
    fun `devにnull・zero・ttyが見える`() = env {
        disks.withInit("d", exe(foreverInit()))
        assertTrue(m.boot(bootDisks("d")))
        tickUntil { m.state == MachineState.RUNNING && host.console.contains("booting") }
        for (name in listOf("null", "zero", "tty")) {
            assertEquals(FileType.CHARACTER_DEVICE, m.vfs.stat(VfsPath.parse("/dev/$name")).type, name)
        }
    }

    @Test
    fun `根が読み取り専用ならhomeDiskを書き込める形でhomeにマウントし、再起動の後も中身が残る`() = env {
        val home = disks.empty("hdd")
        disks.withInit("img", exe(foreverInit()))
        disks.readOnly += "img"
        assertTrue(m.boot(withHome("hdd", "hdd", "img")))
        tickUntil { m.state == MachineState.RUNNING && host.console.contains("booting") }
        assertEquals(listOf("/" to "disk:img", "/tmp" to "tmp", "/dev" to "dev", "/home" to "disk:hdd"), mounts())
        assertEquals("kibi: booting from disk img\n", host.console.toString())
        run {
            val f = m.vfs.open(VfsPath.parse("/home/a"), OpenOptions(read = true, write = true, create = true))
            assertEquals(2, m.vfs.write(f, "hi".toByteArray(), 0, 2))
            m.vfs.close(f)
        }
        assertEquals(FileType.REGULAR_FILE, home.stat(VfsPath.parse("/a")).type, "ディスクに書かれる")

        assertTrue(m.reboot())
        tickUntil { host.reboots.get() == 1 && m.state == MachineState.RUNNING }
        assertEquals(listOf("/" to "disk:img", "/tmp" to "tmp", "/dev" to "dev", "/home" to "disk:hdd"), mounts())
        assertEquals(FileType.REGULAR_FILE, m.vfs.stat(VfsPath.parse("/home/a")).type, "再起動の後も残る")
    }

    @Test
    fun `根が書き込めるディスクならhomeDiskをマウントしない`() = env {
        disks.empty("hdd")
        disks.withInit("d", exe(foreverInit()))
        assertTrue(m.boot(withHome("hdd", "d")))
        tickUntil { m.state == MachineState.RUNNING && host.console.contains("booting") }
        assertEquals(listOf("/" to "disk:d", "/tmp" to "tmp", "/dev" to "dev"), mounts())
    }

    @Test
    fun `homeDiskが根と同じなら二重にマウントしない`() = env {
        disks.withInit("img", exe(foreverInit()))
        disks.readOnly += "img"
        assertTrue(m.boot(withHome("img", "img")))
        tickUntil { m.state == MachineState.RUNNING && host.console.contains("booting") }
        assertEquals(listOf("/" to "disk:img", "/tmp" to "tmp", "/dev" to "dev"), mounts())
    }

    @Test
    fun `homeDiskを開けない・読み取り専用・homeがファイルならマウントせずに起動を続ける`() = env {
        val img = disks.withInit("img", exe(foreverInit()))
        disks.readOnly += "img"
        disks.empty("ro")
        disks.readOnly += "ro"
        disks.empty("hdd")
        for ((home, reason) in listOf("missing" to "cannot open", "throws" to "cannot open", "ro" to "read-only")) {
            host.console.setLength(0)
            assertTrue(m.boot(withHome(home, "img")))
            tickUntil { m.state == MachineState.RUNNING && host.console.contains("booting") }
            assertEquals(listOf("/" to "disk:img", "/tmp" to "tmp", "/dev" to "dev"), mounts(), home)
            assertEquals("kibi: /home not mounted ($reason)\nkibi: booting from disk img\n", host.console.toString())
            m.stop()
            tickUntil { m.state == MachineState.STOPPED && !m.isBooting }
            tickFor(20)
        }
        img.createFile(VfsPath.parse("/home"))
        host.console.setLength(0)
        assertTrue(m.boot(withHome("hdd", "img")))
        tickUntil { m.state == MachineState.RUNNING && host.console.contains("booting") }
        assertEquals(listOf("/" to "disk:img", "/tmp" to "tmp", "/dev" to "dev"), mounts())
        assertEquals("kibi: /home not mounted (NOTDIR)\nkibi: booting from disk img\n", host.console.toString())
    }

    @Test
    fun `ホストのディレクトリのディスクから起動し、止めたらディスクの操作を断って閉じる`(@TempDir dir: Path) = env {
        val diskDir = Files.createDirectories(dir.resolve("d1/sbin")).parent
        Files.write(diskDir.resolve("sbin/init"), exe(foreverInit()))
        Files.createDirectories(dir.resolve("empty"))
        val leases = HostDiskLeases.size
        h.scheduler.hooks.bootVolumes = BootVolumeOpener.forDisks({ id -> dir.resolve(id).takeIf { id == "d1" || id == "empty" }?.let { ResolvedDisk(it, 1L shl 20, 1024) } }, m.hostFiles)
        assertTrue(m.boot(bootDisks("missing", "empty", "d1")))
        tickUntil { m.state == MachineState.RUNNING && host.console.contains("booting") }
        assertEquals(listOf("/" to "disk:d1", "/tmp" to "tmp", "/dev" to "dev"), mounts())
        assertEquals(leases + 1, HostDiskLeases.size, "選ばなかった候補（empty）のリースは返す")
        // テストのスレッド（カーネルロックの外）から読み書きすると、すぐホストに見える
        val f = m.vfs.open(VfsPath.parse("/hello.txt"), OpenOptions(read = true, write = true, create = true))
        assertEquals(1, m.hostFiles.inUse, "開いている間だけホストのファイルを持つ")
        m.vfs.write(f, "hi".toByteArray(), 0, 2)
        m.vfs.close(f)
        assertEquals(0, m.hostFiles.inUse)
        assertEquals("hi", Files.readString(diskDir.resolve("hello.txt")))
        m.stop()
        assertTrue(m.awaitTermination(5_000))
        val e = assertThrows<FsException> { m.vfs.stat(VfsPath.parse("/hello.txt")) }
        assertEquals(Errno.IO, e.errno, "止めたら古いカーネルからの操作は断る")
        tickUntil("止めたディスクを閉じない") { HostDiskLeases.size == leases }
    }

    @Test
    fun `取り消された古い起動が確かめた後にディスクを開いても、新しいマシンのディスクを締め出さない`(@TempDir dir: Path) = env {
        // #588: A は取り消しを確かめた直後（開く前）で止まり、その間に A を止めて B が同じディスクで起動する。後から A が開いても、B の世代は進めない
        val diskDir = Files.createDirectories(dir.resolve("d1/sbin")).parent
        Files.write(diskDir.resolve("sbin/init"), exe(foreverInit()))
        val leases = HostDiskLeases.size
        val real = BootVolumeOpener.forDisks({ id -> if (id == "d1") ResolvedDisk(diskDir, 1L shl 20, 1024) else null }, m.hostFiles)
        val aEntered = CountDownLatch(1)
        val aRelease = CountDownLatch(1)
        val calls = AtomicInteger()
        h.scheduler.hooks.bootVolumes = object : BootVolumeOpener {
            override fun open(diskId: String): BootVolume? = open(diskId) { false }

            override fun open(diskId: String, cancelled: () -> Boolean): BootVolume? {
                if (calls.getAndIncrement() == 0) {
                    aEntered.countDown()
                    aRelease.await()
                }
                return real.open(diskId, cancelled)
            }
        }
        assertTrue(m.boot(bootDisks("d1")))
        assertTrue(aEntered.await(5, TimeUnit.SECONDS))
        m.stop()
        val bHost = Recorder()
        val b = h.machine(host = bHost)
        try {
            assertTrue(b.boot(bootDisks("d1")))
            tickUntil { b.state == MachineState.RUNNING && bHost.console.contains("booting") }
            aRelease.countDown()
            tickUntil("A の起動の手順が終わらない") { !m.isBooting }
            assertTrue(m.awaitTermination(5_000))
            assertEquals(MachineState.STOPPED, m.state)
            assertTrue(host.failures.isEmpty(), "取り消された起動は失敗を知らせない: ${host.failures}")
            assertEquals(1, HostDiskLeases.usersOf(diskDir.toRealPath()), "A が断った分のリースは返す（持つのは B だけ）")
            // B のディスクは今も使える（直す前は、A が世代を進めて IO になった）
            assertEquals(FileType.REGULAR_FILE, b.vfs.stat(VfsPath.parse("/sbin/init")).type)
            val f = b.vfs.open(VfsPath.parse("/hello.txt"), OpenOptions(read = true, write = true, create = true))
            b.vfs.write(f, "hi".toByteArray(), 0, 2)
            b.vfs.close(f)
            assertEquals("hi", Files.readString(diskDir.resolve("hello.txt")))
        } finally {
            aRelease.countDown()
            b.stop()
            assertTrue(b.awaitTermination(5_000))
        }
        tickUntil("止めたディスクを閉じない") { HostDiskLeases.size == leases }
    }

    @Test
    fun `取り消された古い起動の、home を開く前の窓でも新しいマシンのディスクを締め出さない`(@TempDir dir: Path) = env {
        // #588: mountHome も「確かめる → 開く」の形。読み取り専用の根（メモリ上）から起動し、A は home を開く直前で止まる
        disks.withInit("ro", exe(foreverInit()))
        disks.readOnly += "ro"
        val homeDir = Files.createDirectories(dir.resolve("home"))
        val leases = HostDiskLeases.size
        val real = BootVolumeOpener.forDisks({ id -> if (id == "h") ResolvedDisk(homeDir, 1L shl 20, 1024) else null }, m.hostFiles)
        val aEntered = CountDownLatch(1)
        val aRelease = CountDownLatch(1)
        val homeCalls = AtomicInteger()
        h.scheduler.hooks.bootVolumes = object : BootVolumeOpener {
            override fun open(diskId: String): BootVolume? = open(diskId) { false }

            override fun open(diskId: String, cancelled: () -> Boolean): BootVolume? {
                if (diskId != "h") return disks.open(diskId)
                if (homeCalls.getAndIncrement() == 0) {
                    aEntered.countDown()
                    aRelease.await()
                }
                return real.open(diskId, cancelled)
            }
        }
        assertTrue(m.boot(withHome("h", "ro")))
        assertTrue(aEntered.await(5, TimeUnit.SECONDS))
        m.stop()
        val bHost = Recorder()
        val b = h.machine(host = bHost)
        try {
            assertTrue(b.boot(withHome("h", "ro")))
            tickUntil { b.state == MachineState.RUNNING && bHost.console.contains("booting") }
            aRelease.countDown()
            tickUntil("A の起動の手順が終わらない") { !m.isBooting }
            assertTrue(m.awaitTermination(5_000))
            assertFalse(host.console.contains("/home not mounted"), "取り消された起動は home の失敗を書かない")
            assertEquals(1, HostDiskLeases.usersOf(homeDir.toRealPath()), "A が断った分のリースは返す（持つのは B だけ）")
            val f = b.vfs.open(VfsPath.parse("/home/hello.txt"), OpenOptions(read = true, write = true, create = true))
            b.vfs.write(f, "hi".toByteArray(), 0, 2)
            b.vfs.close(f)
            assertEquals("hi", Files.readString(homeDir.resolve("hello.txt")))
        } finally {
            aRelease.countDown()
            b.stop()
            assertTrue(b.awaitTermination(5_000))
        }
        tickUntil("止めたディスクを閉じない") { HostDiskLeases.size == leases }
    }

    @Test
    fun `ResolvedDiskの容量がディスクのFSの割り当てに効き、超える書き込みはNOSPC`(@TempDir dir: Path) = env {
        val diskDir = Files.createDirectories(dir.resolve("d1/sbin")).parent
        val init = exe(foreverInit())
        Files.write(diskDir.resolve("sbin/init"), init)
        // sbin・sbin/init・hello.txt の 3 項目分と init の中身、あと 100 バイトだけ使える
        val capacity = init.size + 3 * 256L + 100
        h.scheduler.hooks.bootVolumes = BootVolumeOpener.forDisks({ id -> if (id == "d1") ResolvedDisk(diskDir, capacity, 16) else null }, m.hostFiles)
        assertTrue(m.boot(bootDisks("d1")))
        tickUntil { m.state == MachineState.RUNNING && host.console.contains("booting") }
        val f = m.vfs.open(VfsPath.parse("/hello.txt"), OpenOptions(read = true, write = true, create = true))
        try {
            m.vfs.write(f, ByteArray(100), 0, 100)
            val e = assertThrows<FsException> { m.vfs.write(f, ByteArray(1), 0, 1) }
            assertEquals(Errno.NOSPC, e.errno)
        } finally {
            m.vfs.close(f)
        }
        m.stop()
        assertTrue(m.awaitTermination(5_000))
    }

    private fun Env.initPid(): Int = m.lock.withLockValue { m.kernel.initPid }

    @Test
    fun `起動の途中は待機点にせず、作ったinitのpidをKernelStateに置く`() = env {
        disks.withInit("d", exe(foreverInit()))
        assertTrue(m.isAtWaitPoint, "起動していない止まったマシンは待機点")
        assertEquals(0, initPid())
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        h.scheduler.hooks.beforeInitExec = { _ ->
            reached.countDown()
            release.await()
        }
        assertTrue(m.boot(bootDisks("d")))
        assertTrue(reached.await(5, TimeUnit.SECONDS))
        // プロセスは0個で、マウントの途中だが、待機点ではない
        assertEquals(emptyList<Int>(), pids())
        assertFalse(m.isAtWaitPoint, "起動の途中は待機点にしない")
        assertEquals(0, initPid())
        release.countDown()
        tickUntil { m.state == MachineState.RUNNING && !m.isBooting }
        assertEquals(1, initPid())

        m.stop()
        tickUntil { m.state == MachineState.STOPPED }
        assertTrue(m.awaitTermination(5_000))
        assertTrue(m.isAtWaitPoint, "stop() で止まったマシンは待機点")
    }

    @Test
    fun `起動の途中の写しは、KILL_TRANSIENTならBOOTINGの理由でプロセスなし、SKIP_IF_TRANSIENTなら写さない`() = env {
        disks.withInit("d", exe(foreverInit()))
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        h.scheduler.hooks.beforeInitExec = { _ ->
            reached.countDown()
            release.await()
        }
        assertTrue(m.boot(bootDisks("d")))
        assertTrue(reached.await(5, TimeUnit.SECONDS))
        assertEquals(pro.ikili.kibicomputers.core.persist.SaveReadiness.TRANSIENT, m.saveReadiness())
        assertNull(m.captureSnapshot(pro.ikili.kibicomputers.core.persist.CapturePolicy.SKIP_IF_TRANSIENT))
        val copy = m.captureCopy(pro.ikili.kibicomputers.core.persist.CapturePolicy.KILL_TRANSIENT)!!
        assertEquals(pro.ikili.kibicomputers.core.persist.ColdBootReason.BOOTING, copy.coldBootReason)
        assertEquals(0, copy.processes.size)
        assertEquals(emptyList<Int>(), copy.killedPids)
        release.countDown()
        // 起動が終わった直後の init は、まだ CPU を取っていない（CREATED）ことがある。CREATED は待機点（_start からやり直せる）なので
        // 写しの中で殺さない。CPU を取って動き出す（CREATED でなくなる）まで待つ（#542）
        tickUntilInitStarted()
        // 計算し続ける init は待機点に来ないので、写しの中で殺し、理由は INIT_KILLED
        val running = m.captureCopy(pro.ikili.kibicomputers.core.persist.CapturePolicy.KILL_TRANSIENT)!!
        assertEquals(pro.ikili.kibicomputers.core.persist.ColdBootReason.INIT_KILLED, running.coldBootReason)
        assertEquals(listOf(1), running.killedPids)
        assertEquals(1, initPid(), "元の init は動いたまま")
        m.stop()
        tickUntil { m.state == MachineState.STOPPED }
        assertTrue(m.awaitTermination(5_000))
    }

    @Test
    fun `再起動の求めを配る前の写しは、REBOOT_PENDINGの理由`() = env {
        disks.withInit("d", exe(exitingInit(0)))
        assertTrue(m.boot(bootDisks("d")))
        tickUntil { host.console.contains("init exited") && m.state == MachineState.STOPPED }
        assertTrue(m.awaitTermination(5_000))
        assertTrue(m.reboot())
        val copy = m.captureCopy(pro.ikili.kibicomputers.core.persist.CapturePolicy.KILL_TRANSIENT)!!
        assertEquals(pro.ikili.kibicomputers.core.persist.ColdBootReason.REBOOT_PENDING, copy.coldBootReason)
        assertEquals(emptyList<Int>(), copy.killedPids)
        tickUntil { host.reboots.get() == 1 && Regex("init exited").findAll(host.console).count() == 2 && m.state == MachineState.STOPPED }
        assertTrue(m.awaitTermination(5_000))
    }

    @Test
    fun `initが終わるとinitPidを0に戻し、止まったマシンは待機点`() = env {
        disks.withInit("d", exe(exitingInit(0)))
        assertTrue(m.boot(bootDisks("d")))
        tickUntil { host.console.contains("init exited") && m.state == MachineState.STOPPED }
        assertTrue(m.awaitTermination(5_000))
        assertEquals(0, initPid())
        assertTrue(m.isAtWaitPoint)
    }

    @Test
    fun `再起動の求めから再起動するまでは待機点にしない`() = env {
        disks.withInit("d", exe(exitingInit(0)))
        assertTrue(m.boot(bootDisks("d")))
        tickUntil { host.console.contains("init exited") && m.state == MachineState.STOPPED }
        assertTrue(m.awaitTermination(5_000))
        assertTrue(m.isAtWaitPoint)
        // STOPPED でプロセスもスレッドもないので、すぐ通知を積む（配るのは次の tick）
        assertTrue(m.reboot())
        assertEquals(MachineState.STOPPED, m.state)
        assertFalse(m.isAtWaitPoint, "通知を配るまでは待機点にしない（ここで保存すると再起動が消える）")
        tickUntil { host.reboots.get() == 1 && Regex("init exited").findAll(host.console).count() == 2 && m.state == MachineState.STOPPED }
        assertTrue(m.awaitTermination(5_000))
        assertTrue(m.isAtWaitPoint, "再起動が済んで止まったマシンは待機点")
        assertEquals(0, initPid())
    }

    @Test
    fun `動いているinitの再起動を待つ間は待機点にせず、次の起動のinitのpidを置く`() = env {
        disks.withInit("d", exe(foreverInit()))
        assertTrue(m.boot(bootDisks("d")))
        // 「動いている init」を再起動するので、init が CREATED を抜けるまで待つ（#624）
        tickUntilInitStarted()
        val firstInit = process(1)!!
        assertEquals(1, initPid())
        assertTrue(m.reboot())
        assertFalse(m.isAtWaitPoint, "再起動を求めてから止まるまでは待機点にしない")
        tickUntil { host.reboots.get() == 1 && m.state == MachineState.RUNNING && !m.isBooting }
        assertTrue(process(1) !== firstInit)
        assertEquals(1, initPid(), "次の起動の init の pid")
    }

    @Test
    fun `再起動を待つ間はプロセスがすべて終わってもスレッドの登録が残るうちは待機点にしない`() = env {
        disks.withInit("d", exe(foreverInit()))
        assertTrue(m.boot(bootDisks("d")))
        // reboot が init の register() より先だと、register が KILLED にして unregister を通らず、フックが来ない。
        // init が CREATED を抜ける（register を通った）まで待つ（#624）
        tickUntilInitStarted()
        val firstInit = process(1)!!
        // init の専用スレッドを、プロセスが終わった後・登録を外す前で止める（通知を積む前の場面を作る）
        val atGate = CountDownLatch(1)
        val gate = CountDownLatch(1)
        h.scheduler.hooks.beforeUnregister = { machine, pid ->
            if (machine === m && pid == 1 && atGate.count > 0) {
                atGate.countDown()
                gate.awaitKeepingInterrupt()
            }
        }
        try {
            assertTrue(m.reboot())
            assertTrue(atGate.await(5, TimeUnit.SECONDS), "init のスレッドが登録を外す前に来ない")
            h.scheduler.hooks.beforeUnregister = null
            tickFor(50)
            m.lock.withLockValue {
                assertTrue(firstInit.state.isTerminal, "init は終わっている")
                assertEquals(0, m.kernel.processes.liveCount(), "動いているプロセスはない")
                assertTrue(m.kernel.processes.all().all { it.state.isTerminal }, "どのプロセスも待機点の扱い")
            }
            assertEquals(MachineState.STOPPED, m.state)
            assertFalse(m.isBooting)
            assertEquals(0, host.reboots.get(), "スレッドの登録が残るので、再起動の求めはまだ積まない")
            assertFalse(m.isAtWaitPoint, "再起動の求めを積む前は待機点にしない")
        } finally {
            h.scheduler.hooks.beforeUnregister = null
            gate.countDown()
        }
        tickUntil { host.reboots.get() == 1 && m.state == MachineState.RUNNING && !m.isBooting }
        assertTrue(process(1) !== firstInit)
    }

    @Test
    fun `initかどうかはKernelStateのinitPidで決める`() = env {
        disks.withInit("d", exe(foreverInit()))
        assertTrue(m.boot(bootDisks("d")))
        tickUntil { m.state == MachineState.RUNNING }
        assertEquals(KillResult.NOT_PERMITTED, m.lock.withLockValue { m.killLocked(1, KillSignal.KILL) })
        m.lock.withLockValue { m.kernel.initPid = 0 }
        assertEquals(KillResult.DELIVERED, m.lock.withLockValue { m.killLocked(1, KillSignal.KILL) })
        tickUntil { m.stateOf(1).isTerminal }
        assertFalse(host.console.contains("init was killed"), "init とみなさないので、init の終わりとして扱わない: ${host.console}")
    }
}
