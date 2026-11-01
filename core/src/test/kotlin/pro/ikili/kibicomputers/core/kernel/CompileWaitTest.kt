package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.boot.BootVolume
import pro.ikili.kibicomputers.core.kernel.boot.BootVolumeOpener
import pro.ikili.kibicomputers.core.kernel.process.KillSignal
import pro.ikili.kibicomputers.core.kernel.process.ProcessDriverFactory
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.ProgramCache
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.FakeModule
import java.time.Duration
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** 実行ファイルの読み込み（コンパイル）を待つ起動・プロセスと、停止・取り消し（#344） */
class CompileWaitTest {
    private val limit = Duration.ofSeconds(30)

    /** 読み込むスレッドが1本のスケジューラ。[Harness.runtime] の読み込みは [gate] が開くまで止まる */
    private class Env(val h: Harness) {
        companion object {
            const val PREFIX: String = "fake-compile-"
        }

        val gate = CountDownLatch(1)

        /** 読み込みに入ったバイト列 */
        val loading: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
        private var counter = 0

        init {
            h.runtime.beforeLoad = { bytes ->
                // exec で読み込むものだけ止める（Harness.program はテストのスレッドで読み込む）
                val text = String(bytes)
                if (text.startsWith(PREFIX)) {
                    loading += text
                    gate.await()
                }
            }
        }

        /** [module] を実行ファイルのバイト列にする（[register] が `false` なら読み込みは失敗する） */
        fun exe(module: FakeModule = emptyModule(), register: Boolean = true): ByteArray =
            "$PREFIX${counter++}".toByteArray().also { if (register) h.runtime.define(it, module) }

        fun tickUntil(message: String = "条件がそろわない", condition: () -> Boolean) = eventually(10_000, message) {
            h.scheduler.tick()
            Thread.sleep(1)
            condition()
        }
    }

    private fun env(maxConcurrentMachines: Int = 4, block: Env.() -> Unit) = timeout(limit) {
        val config = SchedulerConfig.builder().maxConcurrentMachines(maxConcurrentMachines).maxBlockingProcesses(64).compilerThreads(1).build()
        Harness(config).use { h ->
            val env = Env(h)
            try {
                env.block()
            } finally {
                env.gate.countDown()
            }
        }
    }

    private fun Machine.mountRoot(vararg files: Pair<String, ByteArray>) = lock.withLockValue {
        vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
        vfs.createDirectory(VfsPath.parse("/bin"))
        for ((path, bytes) in files) {
            val f = vfs.open(VfsPath.parse(path), OpenOptions(read = true, write = true, create = true))
            vfs.write(f, bytes, 0, bytes.size)
        }
    }

    /** `/sbin/init` に [bytes] を置いたディスク */
    private fun initDisk(bytes: ByteArray): TestMemoryFileSystem = TestMemoryFileSystem().also { fs ->
        fs.createDirectory(VfsPath.parse("/sbin"))
        val node = fs.createFile(VfsPath.parse(BootConfig.INIT_PATH))
        fs.write(node, 0, bytes, 0, bytes.size)
    }

    private val exitDrivers = ProcessDriverFactory { TestDriver(ProcessKind.BLOCKING) { StepResult.Exit(0) } }

    /** プロセスから [path] を exec し、結果（pid か例外）を [out] に入れて終わるドライバー */
    private fun execer(path: String, out: AtomicReference<Any>, kind: ProcessKind = ProcessKind.BLOCKING) = TestDriver(kind) { ctx ->
        out.set(
            try {
                ctx.exec(path, exitDrivers)
            } catch (e: FsException) {
                e
            },
        )
        StepResult.Exit(0)
    }

    @Test
    fun `読み込み待ちの起動とプロセスがあってもスケジューラの停止で全部戻る`() = env {
        val failures = Collections.synchronizedList(ArrayList<BootFailure>())
        val host = object : MachineHost {
            override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) {}

            override fun onBootFailed(machine: Machine, failure: BootFailure, detail: String) {
                failures += failure
            }
        }
        // 別々の init を持つ 3 台の起動（読み込むスレッドは 1 本なので、2 つは待ち行列に残る）
        val disks = (0 until 3).associate { "d$it" to initDisk(exe()) }
        h.scheduler.hooks.bootVolumes = BootVolumeOpener { id -> disks[id]?.let { BootVolume(it, readOnly = false) } }
        val booting = disks.keys.map { id -> h.machine(host = host).also { assertTrue(it.boot(BootConfig.builder().bootDisks(listOf(id)).build())) } }
        // プロセスからの exec（ブロッキング型と、スレッドを持たない待機点型）
        val m = h.machine(fuelPerTick = 1_000_000)
        val blockingExe = exe()
        val waitPointExe = exe()
        m.mountRoot("/bin/a" to blockingExe, "/bin/b" to waitPointExe)
        val outA = AtomicReference<Any>()
        val outB = AtomicReference<Any>()
        val a = m.spawnTest(h.program(emptyModule()), execer("/bin/a", outA))
        val b = m.spawnTest(h.program(emptyModule()), execer("/bin/b", outB, ProcessKind.WAIT_POINT))
        tickUntil("読み込みを待たない") {
            h.scheduler.programs.loadingCount == 5 && m.stateOf(a) == ProcessState.WAITING_HOST && m.stateOf(b) == ProcessState.WAITING_HOST
        }
        assertEquals(1, loading.size, "読み込むスレッドは 1 本")
        assertTrue(booting.all { it.isBooting })

        val started = System.nanoTime()
        h.scheduler.close()
        assertTrue(h.scheduler.awaitTermination(5_000), "読み込みを待つものが残っている")
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(5))
        eventually { booting.none { it.isBooting } }
        assertEquals(0, h.scheduler.programs.loadingCount)
        // 止めた起動は失敗を知らせない。プロセスは止められて終わる
        assertTrue(failures.isEmpty(), failures.toString())
        assertTrue(booting.all { it.state == MachineState.STOPPED })
        assertEquals(ProcessState.KILLED, m.stateOf(a))
        assertEquals(ProcessState.KILLED, m.stateOf(b))
        assertEquals(1, loading.size, "待ち行列の読み込みは始めない")
    }

    @Test
    fun `起動の途中のstopで、initの読み込みを待たずに起動をやめる`() = env {
        val disk = initDisk(exe())
        h.scheduler.hooks.bootVolumes = BootVolumeOpener { id -> if (id == "d") BootVolume(disk, readOnly = false) else null }
        val m = h.machine()
        assertTrue(m.boot(BootConfig.builder().bootDisks(listOf("d")).build()))
        eventually { loading.isNotEmpty() }
        m.stop()
        eventually { !m.isBooting }
        assertEquals(MachineState.STOPPED, m.state)
        // 読み込みは続き、終われば共有される
        assertEquals(1, h.scheduler.programs.loadingCount)
        gate.countDown()
        eventually { h.scheduler.programs.loadingCount == 0 }
        assertEquals(1L, h.scheduler.programs.loads.get())
    }

    @Test
    fun `プロセスはexecの読み込みを待つ間CPUと許可を返し、ほかのプロセスが動く`() = env(maxConcurrentMachines = 1) {
        val m = h.machine(fuelPerTick = 1_000_000)
        m.mountRoot("/bin/x" to exe())
        val out = AtomicReference<Any>()
        val pid = m.spawnTest(h.program(emptyModule()), execer("/bin/x", out))
        tickUntil { m.stateOf(pid) == ProcessState.WAITING_HOST }
        assertEquals(1, h.scheduler.permits.availablePermits(), "許可を返していない")
        // 同じマシンのほかのプロセスが CPU を使える
        val progress = AtomicInteger()
        val other = m.spawnTest(h.program(spinForever(progress)), runDriver())
        tickUntil("ほかのプロセスが動かない") { progress.get() > 100 }
        m.lock.withLockValue { m.killLocked(other, KillSignal.KILL) }
        tickUntil { m.stateOf(other).isTerminal }
        gate.countDown()
        tickUntil { m.stateOf(pid).isTerminal }
        assertEquals(ProcessState.EXITED, m.stateOf(pid))
        val child = out.get() as Int
        tickUntil { m.stateOf(child).isTerminal }
        assertEquals(ProcessState.EXITED, m.stateOf(child))
        m.stop()
        assertTrue(m.awaitTermination(5_000))
    }

    @Test
    fun `execの読み込みが待った後に失敗したらNOEXEC`() = env {
        val m = h.machine(fuelPerTick = 1_000_000)
        m.mountRoot("/bin/bad" to exe(register = false))
        val out = AtomicReference<Any>()
        val pid = m.spawnTest(h.program(emptyModule()), execer("/bin/bad", out))
        tickUntil { m.stateOf(pid) == ProcessState.WAITING_HOST }
        gate.countDown()
        tickUntil { m.stateOf(pid).isTerminal }
        assertEquals(ProcessState.EXITED, m.stateOf(pid))
        assertEquals(Errno.NOEXEC, (out.get() as FsException).errno)
        m.stop()
        assertTrue(m.awaitTermination(5_000))
    }

    @Test
    fun `execの読み込みを待つ間にkillされたら止まる`() = env {
        val m = h.machine(fuelPerTick = 1_000_000)
        m.mountRoot("/bin/x" to exe(), "/bin/y" to exe())
        val outA = AtomicReference<Any>()
        val outB = AtomicReference<Any>()
        val a = m.spawnTest(h.program(emptyModule()), execer("/bin/x", outA))
        val b = m.spawnTest(h.program(emptyModule()), execer("/bin/y", outB, ProcessKind.WAIT_POINT))
        tickUntil { m.stateOf(a) == ProcessState.WAITING_HOST && m.stateOf(b) == ProcessState.WAITING_HOST }
        m.lock.withLockValue {
            m.killLocked(a, KillSignal.KILL)
            m.killLocked(b, KillSignal.KILL)
        }
        tickUntil("止まらない") { m.stateOf(a).isTerminal && m.stateOf(b).isTerminal }
        assertFalse(m.stateOf(a) == ProcessState.EXITED)
        assertFalse(m.stateOf(b) == ProcessState.EXITED)
        assertEquals(null, outA.get())
        assertEquals(null, outB.get())
        // 読み込みは続き、終われば共有される（子は作らない）
        gate.countDown()
        eventually { h.scheduler.programs.loadingCount == 0 }
        m.stop()
        assertTrue(m.awaitTermination(5_000))
    }

    @Test
    fun `1台の読み込みが上限に達したらexecはAGAINになり、別の台の読み込みは受け付ける`() = env {
        val limit = ProgramCache.DEFAULT_MAX_PENDING_PER_OWNER
        val m = h.machine(fuelPerTick = 1_000_000)
        val files = (0..limit).map { "/bin/p$it" to exe() }
        m.mountRoot(*files.toTypedArray())
        val outs = files.map { AtomicReference<Any>() }
        val pids = files.indices.take(limit).map { i -> m.spawnTest(h.program(emptyModule()), execer(files[i].first, outs[i])) }
        tickUntil { h.scheduler.programs.loadingCount == limit && pids.all { m.stateOf(it) == ProcessState.WAITING_HOST } }
        // 上限を超えた分は待たずに AGAIN
        val over = m.spawnTest(h.program(emptyModule()), execer(files[limit].first, outs[limit]))
        tickUntil { m.stateOf(over).isTerminal }
        assertEquals(Errno.AGAIN, (outs[limit].get() as FsException).errno)
        // 別の台は受け付ける
        val other = h.machine(fuelPerTick = 1_000_000)
        other.mountRoot("/bin/q" to exe())
        val otherOut = AtomicReference<Any>()
        val otherPid = other.spawnTest(h.program(emptyModule()), execer("/bin/q", otherOut))
        tickUntil { other.stateOf(otherPid) == ProcessState.WAITING_HOST }
        assertEquals(limit + 1, h.scheduler.programs.loadingCount)
        gate.countDown()
        tickUntil { pids.all { m.stateOf(it).isTerminal } && other.stateOf(otherPid).isTerminal }
        assertTrue(outs.take(limit).all { it.get() is Int }, outs.toString())
        assertTrue(otherOut.get() is Int)
        m.stop()
        other.stop()
        assertTrue(m.awaitTermination(5_000))
        assertTrue(other.awaitTermination(5_000))
    }

    @Test
    fun `execの読み込みを待つプロセスが止められたら、始まっていない読み込みは取り消す`() = env {
        val m = h.machine(fuelPerTick = 1_000_000)
        m.mountRoot("/bin/x" to exe(), "/bin/y" to exe())
        val outA = AtomicReference<Any>()
        val outB = AtomicReference<Any>()
        val a = m.spawnTest(h.program(emptyModule()), execer("/bin/x", outA))
        tickUntil { loading.size == 1 }
        val b = m.spawnTest(h.program(emptyModule()), execer("/bin/y", outB))
        tickUntil { m.stateOf(b) == ProcessState.WAITING_HOST && h.scheduler.programs.loadingCount == 2 }
        m.lock.withLockValue { m.killLocked(b, KillSignal.KILL) }
        tickUntil("止まらない") { m.stateOf(b).isTerminal }
        // 待ち行列にあった /bin/y の読み込みは外れる
        eventually { h.scheduler.programs.loadingCount == 1 }
        gate.countDown()
        tickUntil { m.stateOf(a).isTerminal }
        eventually { h.scheduler.programs.loadingCount == 0 }
        assertEquals(1L, h.scheduler.programs.loads.get())
        assertEquals(1, loading.size)
        m.stop()
        assertTrue(m.awaitTermination(5_000))
    }
}
