package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
import pro.ikili.kibicomputers.core.kernel.vfs.FileType
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.OpenFile
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
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** 起動シーケンス（/sbin/init）と停止・再起動（#49） */
class BootTest {
    private val limit = Duration.ofSeconds(30)

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

        override fun open(diskId: String): BootVolume? {
            if (diskId == "throws") throw IllegalStateException("開けない")
            return map[diskId]?.let { BootVolume(it, readOnly = false) }
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
        tickUntil { m.state == MachineState.RUNNING && host.console.contains("booting") }
        assertFalse(m.isBooting)
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
        assertEquals(listOf("/" to "disk:b", "/tmp" to "tmp"), mounts())
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
        assertEquals(listOf("/" to "disk:d", "/tmp" to "tmp"), mounts(), "前の起動のマウントは残らない")
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
    fun `プロセスから停止と再起動を求められる`() = env {
        disks.withInit("d", exe(foreverInit()))
        assertTrue(m.boot(bootDisks("d")))
        tickUntil { m.state == MachineState.RUNNING }
        val never = m.lock.withLockValue { m.kernel.objects.add(ByteStream(1)) }
        val asker = { reboot: Boolean ->
            TestDriver(ProcessKind.BLOCKING) { ctx ->
                if (reboot) assertTrue(ctx.requestReboot()) else ctx.requestShutdown()
                ctx.block(listOf(WaitKey(never, WaitCondition.READABLE)))
                StepResult.Exit(0)
            }
        }
        m.spawn(h.program(emptyModule()), asker(true), SpawnOptions(), parentPid = 1)
        tickUntil { host.reboots.get() == 1 && m.state == MachineState.RUNNING }

        m.spawn(h.program(emptyModule()), asker(false), SpawnOptions(), parentPid = 1)
        tickUntil { m.state == MachineState.STOPPED }
        assertTrue(m.awaitTermination(5_000))
        tickFor(50)
        assertEquals(1, host.reboots.get())
        assertFalse(host.console.contains("init exited"), host.console.toString())
        assertEquals(ProcessState.KILLED, m.stateOf(1))
    }

    @Test
    fun `tmpは起動ごとに空でマウントし、中身はRAMに数え、再起動で返す`() = env {
        disks.withInit("d", exe(foreverInit()))
        assertTrue(m.boot(bootDisks("d")))
        tickUntil { m.state == MachineState.RUNNING }
        val tmpFile = VfsPath.parse("/tmp/a.txt")
        m.lock.withLockValue {
            val f = m.vfs.open(tmpFile, OpenOptions(read = true, write = true, create = true))
            val bytes = "hello".toByteArray()
            assertEquals(bytes.size, m.vfs.write(f, bytes, 0, bytes.size))
        }
        assertTrue(m.memoryAccount.usedBytes >= TmpFileSystem.CHUNK_BYTES, "/tmp の中身を RAM に数える")

        assertTrue(m.reboot())
        tickUntil { host.reboots.get() == 1 && m.state == MachineState.RUNNING }
        m.lock.withLockValue {
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
        assertEquals(listOf("/" to "disk:d"), mounts())
        assertEquals("kibi: /tmp not mounted (NOTDIR)\nkibi: booting from disk d\n", host.console.toString())
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
        tickUntil { m.state == MachineState.RUNNING }
        val firstInit = process(1)!!
        assertEquals(1, initPid())
        assertTrue(m.reboot())
        assertFalse(m.isAtWaitPoint, "再起動を求めてから止まるまでは待機点にしない")
        tickUntil { host.reboots.get() == 1 && m.state == MachineState.RUNNING && !m.isBooting }
        assertTrue(process(1) !== firstInit)
        assertEquals(1, initPid(), "次の起動の init の pid")
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
