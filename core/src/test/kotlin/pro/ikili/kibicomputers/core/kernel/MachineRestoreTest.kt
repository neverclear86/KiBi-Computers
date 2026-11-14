package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pro.ikili.kibicomputers.core.kernel.boot.BootVolume
import pro.ikili.kibicomputers.core.kernel.boot.BootVolumeOpener
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.tty.TtySize
import pro.ikili.kibicomputers.core.kernel.vfs.FileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.core.persist.ColdBootCause
import pro.ikili.kibicomputers.core.persist.MachineIdIndex
import pro.ikili.kibicomputers.core.persist.MachineIdentity
import pro.ikili.kibicomputers.core.persist.MachineOwner
import pro.ikili.kibicomputers.core.persist.MachineSaveStore
import pro.ikili.kibicomputers.core.persist.RestoreListener
import pro.ikili.kibicomputers.core.persist.RestoreOutcome
import pro.ikili.kibicomputers.core.persist.RestoreRequest
import pro.ikili.kibicomputers.core.persist.RestoreSource
import pro.ikili.kibicomputers.core.persist.SaveReadiness
import pro.ikili.kibicomputers.core.runtime.WasmInstance
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmInstanceState
import pro.ikili.kibicomputers.core.runtime.WasmLinkException
import pro.ikili.kibicomputers.core.runtime.WasmModule
import pro.ikili.kibicomputers.core.runtime.WasmRuntime
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.nio.file.Path
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * 保存データからの復元（#85、ADR 0085）: `prompt-wasi.wasm` が入力を待っているところを書き、新しいマシンに戻して続きを動かす。
 * 戻せないときの冷えた起動・取り消し・表に入れる途中の失敗・止めている間の振る舞いも確かめる
 */
class MachineRestoreTest {
    @TempDir
    lateinit var dir: Path

    private val limit = Duration.ofSeconds(120)
    private val owner = MachineOwner("minecraft:overworld", 0, 64, 0)

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/$name")) { "$name がリソースにない" }.use { it.readBytes() }

    /** 終わりの知らせの記録 */
    private class Finished {
        val outcomes: MutableList<Triple<Machine, RestoreOutcome, ColdBootCause?>> = Collections.synchronizedList(ArrayList())

        @Volatile
        var onFinished: ((Machine, RestoreOutcome) -> Unit)? = null

        /** 受け手の中で失敗した確かめ（受け手の例外はマシンが捨てるので、ここに取っておく） */
        val errors: MutableList<Throwable> = Collections.synchronizedList(ArrayList())

        val listener = RestoreListener { m, outcome, cause ->
            try {
                onFinished?.invoke(m, outcome)
            } catch (t: Throwable) {
                errors += t
            }
            outcomes += Triple(m, outcome, cause)
        }

        /** 受け手の中の確かめが通った */
        fun assertNoErrors() {
            errors.firstOrNull()?.let { throw AssertionError("受け手の中の確かめが落ちた", it) }
        }
    }

    /** 書いた世代と、標準入出力のオブジェクトの ID */
    private class Saved(val identity: MachineIdentity, val input: Int, val output: Int, val pid: Int)

    /**
     * `prompt` を init として動かし、入力を待ったところで置き場に書き、マシンを閉じる。根は `disk:root`（[root] を開く口を [h] に入れる）
     */
    private fun saveWaitingPrompt(h: Harness, store: MachineSaveStore, root: TestMemoryFileSystem, tmpBytes: Int = 0): Saved {
        // 戻すマシンには、新しい空のファイルシステムを渡す（閉じたマシンの根は使わない）
        h.scheduler.hooks.bootVolumes = BootVolumeOpener { id -> if (id == "root") BootVolume(TestMemoryFileSystem(), readOnly = false) else null }
        val m = h.machine(fuelPerTick = 10_000_000)
        val bytes = resource("prompt-wasi.wasm")
        m.vfs.mount(VfsPath.ROOT, root, readOnly = false, source = "disk:root")
        m.vfs.createDirectory(VfsPath.parse("/bin"))
        val f = m.vfs.open(VfsPath.parse("/bin/prompt"), OpenOptions(read = true, write = true, create = true, truncate = true))
        m.vfs.write(f, bytes, 0, bytes.size)
        if (tmpBytes > 0) {
            // /tmp の中身も写す（戻すときに RAM の勘定に数え直す）
            m.vfs.createDirectory(VfsPath.parse("/tmp"))
            assertNull(m.mountTmp())
            val t = m.vfs.open(VfsPath.parse("/tmp/x"), OpenOptions(read = false, write = true, create = true))
            assertEquals(tmpBytes, m.vfs.write(t, ByteArray(tmpBytes), 0, tmpBytes))
        }
        val (input, output) = m.lock.withLockValue {
            ByteStream(4096).also { m.kernel.objects.add(it) }.id to ByteStream(64 * 1024).also { m.kernel.objects.add(it) }.id
        }
        val stdio = StdioSpec(StdioSource.Object(input, HandleRights.READ), StdioSource.Object(output, HandleRights.WRITE), StdioSource.Closed)
        val pid = m.exec("/bin/prompt", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("prompt"), stdio = stdio))
        m.lock.withLockValue { m.kernel.initPid = pid }
        eventually(60_000, "入力を待たない") {
            h.scheduler.tick()
            m.stateOf(pid) == ProcessState.WAITING_EVENT && m.isAtWaitPoint
        }
        val identity = store.assignNewId(owner)!!
        val written = store.write(identity, owner, m.captureSnapshot(CapturePolicy.SKIP_IF_TRANSIENT)!!).identity
        m.close()
        return Saved(written, input, output, pid)
    }

    /** [saveWaitingPrompt] の `prompt` に、その子の `prompt` を足した2つが、入力を待ったところで保存する。子の pid も返す */
    private fun saveWaitingPromptWithChild(h: Harness, store: MachineSaveStore): Pair<Saved, Int> {
        h.scheduler.hooks.bootVolumes = BootVolumeOpener { id -> if (id == "root") BootVolume(TestMemoryFileSystem(), readOnly = false) else null }
        val m = h.machine(fuelPerTick = 10_000_000)
        val bytes = resource("prompt-wasi.wasm")
        m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "disk:root")
        m.vfs.createDirectory(VfsPath.parse("/bin"))
        val f = m.vfs.open(VfsPath.parse("/bin/prompt"), OpenOptions(read = true, write = true, create = true, truncate = true))
        m.vfs.write(f, bytes, 0, bytes.size)
        fun spawn(): Triple<Int, Int, Int> {
            val (input, output) = m.lock.withLockValue {
                ByteStream(4096).also { m.kernel.objects.add(it) }.id to ByteStream(64 * 1024).also { m.kernel.objects.add(it) }.id
            }
            val stdio = StdioSpec(StdioSource.Object(input, HandleRights.READ), StdioSource.Object(output, HandleRights.WRITE), StdioSource.Closed)
            return Triple(m.exec("/bin/prompt", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("prompt"), stdio = stdio)), input, output)
        }
        val (pid, input, output) = spawn()
        val (childPid, _, _) = spawn()
        m.lock.withLockValue {
            m.kernel.initPid = pid
            // 2つ目を1つ目の子にする（表の親子の印だけ。子のハンドルは持たせない）
            m.kernel.processes[childPid]!!.parentPid = pid
        }
        eventually(60_000, "入力を待たない") {
            h.scheduler.tick()
            m.stateOf(pid) == ProcessState.WAITING_EVENT && m.stateOf(childPid) == ProcessState.WAITING_EVENT && m.isAtWaitPoint
        }
        val identity = store.assignNewId(owner)!!
        val written = store.write(identity, owner, m.captureSnapshot(CapturePolicy.SKIP_IF_TRANSIENT)!!).identity
        m.close()
        return Saved(written, input, output, pid) to childPid
    }

    private fun output(m: Machine, id: Int): String = m.lock.withLockValue {
        (m.kernel.objects[id] as ByteStream).availableBytes().toString(Charsets.UTF_8)
    }

    private fun bootConfig(): BootConfig = BootConfig.builder().addBootDisk("none").build()

    /** 閉じた（[FileSystem.retire]）回数を数えるディスク（復元で開き直すディスクの後始末を確かめる） */
    private class CountingDisk(private val inner: TestMemoryFileSystem = TestMemoryFileSystem()) : FileSystem by inner {
        val retired = AtomicInteger()

        override fun retire() {
            retired.incrementAndGet()
            inner.retire()
        }
    }

    /** 戻すマシンが開く根のディスクを [disk] にする */
    private fun openRootAs(h: Harness, disk: FileSystem) {
        h.scheduler.hooks.bootVolumes = BootVolumeOpener { id -> if (id == "root") BootVolume(disk, readOnly = false) else null }
    }

    /** 作り直し（instantiateForRestore）だけ [WasmLinkException] で断るランタイム（RAM の不足でない失敗） */
    private class LinkFailingRestoreRuntime(private val inner: WasmRuntime) : WasmRuntime by inner {
        override fun load(bytes: ByteArray): WasmModule {
            val module = inner.load(bytes)
            return object : WasmModule by module {
                override fun instantiateForRestore(config: WasmInstanceConfig, state: WasmInstanceState): WasmInstance =
                    throw WasmLinkException("テスト: 状態が合わない")
            }
        }
    }

    private fun request(saved: Saved, store: MachineSaveStore, finished: Finished): RestoreRequest =
        RestoreRequest(RestoreSource(store, saved.identity.id, saved.identity.generation), bootConfig(), finished.listener)

    @Test
    fun `入力を待っていたp1のプロセスを戻し、続きの入力に答える`() = timeout(limit) {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
            val saved = saveWaitingPrompt(h, store, TestMemoryFileSystem())
            val m = h.machine(fuelPerTick = 10_000_000)
            val finished = Finished()
            // 知らせが返るまでは止めたまま（プロセスに step を割り当てない）
            finished.onFinished = { machine, _ ->
                assertTrue(machine.isRestoring)
                assertTrue(machine.isQuiesced)
            }
            assertTrue(m.restore(RestoreRequest(RestoreSource(store, saved.identity.id, saved.identity.generation), bootConfig(), finished.listener)))
            assertTrue(m.isRestoring)
            assertFalse(m.isAtWaitPoint, "復元の途中は待機点にしない")
            assertNull(m.captureCopy(CapturePolicy.KILL_TRANSIENT), "復元の途中は写さない")
            assertFalse(m.boot(bootConfig()), "復元の途中は起動しない")
            eventually(60_000, "復元が終わらない") {
                h.scheduler.tick()
                finished.outcomes.isNotEmpty()
            }
            finished.assertNoErrors()
            val (machine, outcome, cause) = finished.outcomes.single()
            assertTrue(machine === m)
            assertEquals(RestoreOutcome.RESTORED, outcome)
            assertNull(cause)
            assertEquals(MachineState.RUNNING, m.state)
            assertFalse(m.isRestoring)
            assertFalse(m.isQuiesced)
            assertEquals(ProcessState.WAITING_EVENT, m.stateOf(saved.pid))
            val before = output(m, saved.output)
            m.lock.withLockValue {
                val line = "hello\n".toByteArray()
                (m.kernel.objects[saved.input] as ByteStream).write(line, 0, line.size, m.wakeSink)
            }
            eventually(60_000, "戻したプロセスが答えない") {
                h.scheduler.tick()
                output(m, saved.output).length > before.length && output(m, saved.output).contains("hello")
            }
            m.lock.withLockValue { (m.kernel.objects[saved.input] as ByteStream).closeWrite(m.wakeSink) }
            eventually(60_000, "終わらない") {
                h.scheduler.tick()
                m.state == MachineState.STOPPED
            }
            m.close()
        }
    }

    @Test
    fun `後段の後も、知らせ（RESTORED）が返るまでは保存しない`() = timeout(limit) {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
            val saved = saveWaitingPrompt(h, store, TestMemoryFileSystem())
            val m = h.machine(fuelPerTick = 10_000_000)
            val finished = Finished()
            // 受け手は tick のスレッドで動く: 待たずに1回だけ確かめる（#589。restoringValue は下りていて、restoreHandle はまだ開いている）
            finished.onFinished = { machine, outcome ->
                assertEquals(RestoreOutcome.RESTORED, outcome)
                assertTrue(machine.isRestoring)
                assertEquals(SaveReadiness.TRANSIENT, machine.saveReadiness(), "知らせの中は TRANSIENT")
                assertFalse(machine.isAtWaitPoint, "知らせの中は待機点にしない")
                assertNull(machine.captureCopy(CapturePolicy.SKIP_IF_TRANSIENT), "知らせの中は写さない（SKIP_IF_TRANSIENT）")
                assertNull(machine.captureCopy(CapturePolicy.KILL_TRANSIENT), "知らせの中は写さない（KILL_TRANSIENT）")
                assertNull(machine.captureSnapshot(CapturePolicy.KILL_TRANSIENT), "知らせの中は取り出さない")
                val q = machine.quiesce()
                try {
                    assertEquals(SaveReadiness.TRANSIENT, q.readiness, "知らせの中で取った quiesce も TRANSIENT")
                } finally {
                    q.close()
                }
            }
            assertTrue(m.restore(request(saved, store, finished)))
            eventually(60_000, "復元が終わらない") {
                h.scheduler.tick()
                finished.outcomes.isNotEmpty()
            }
            finished.assertNoErrors()
            assertEquals(RestoreOutcome.RESTORED, finished.outcomes.single().second)
            assertFalse(m.isRestoring)
            // 知らせが返った後は、待機点に戻れば写せる
            eventually(60_000, "知らせの後も写せない") {
                h.scheduler.tick()
                m.saveReadiness() == SaveReadiness.READY && m.captureCopy(CapturePolicy.SKIP_IF_TRANSIENT) != null
            }
            assertEquals(ProcessState.WAITING_EVENT, m.stateOf(saved.pid))
            m.close()
        }
    }

    @Test
    fun `後段の後、知らせを配る前にstopしても、TRANSIENTのまま残らない`() = timeout(limit) {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
            val saved = saveWaitingPrompt(h, store, TestMemoryFileSystem())
            val m = h.machine(fuelPerTick = 10_000_000)
            val finished = Finished()
            // 配るときには stopLocked が restoreHandle を下ろし終えている（#589。知らせは別の handle を閉じない）
            finished.onFinished = { machine, outcome ->
                assertEquals(RestoreOutcome.RESTORED, outcome, "戻した後に止めた RESTORED は替えない")
                assertFalse(machine.isRestoring, "知らせの中でも、止めた後は復元の途中でない")
            }
            assertTrue(m.restore(request(saved, store, finished)))
            // tick を回さない（知らせを配らない）まま、後段が RESTORED を積むのを待つ
            eventually(60_000, "後段が終わらない") { m.state == MachineState.RUNNING }
            assertTrue(m.isRestoring, "知らせを配る前は restoreHandle が開いている")
            assertEquals(SaveReadiness.TRANSIENT, m.saveReadiness())
            m.stop()
            assertFalse(m.isRestoring, "stop が restoreHandle を下ろす")
            eventually(10_000, "知らせが来ない") {
                h.scheduler.tick()
                finished.outcomes.isNotEmpty()
            }
            repeat(3) { h.scheduler.tick() }
            finished.assertNoErrors()
            assertEquals(listOf(RestoreOutcome.RESTORED), finished.outcomes.map { it.second })
            assertFalse(m.isRestoring)
            assertFalse(m.isQuiesced)
            eventually(10_000, "止めた後も TRANSIENT のまま") {
                h.scheduler.tick()
                m.saveReadiness() != SaveReadiness.TRANSIENT
            }
            m.close()
        }
    }

    @Test
    fun `受け手がない復元も、知らせの後は写せる`() = timeout(limit) {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
            val saved = saveWaitingPrompt(h, store, TestMemoryFileSystem())
            val m = h.machine(fuelPerTick = 10_000_000)
            assertTrue(m.restore(RestoreRequest(RestoreSource(store, saved.identity.id, saved.identity.generation), bootConfig(), null)))
            assertNull(m.captureCopy(CapturePolicy.KILL_TRANSIENT), "復元の途中は写さない")
            eventually(60_000, "復元が終わらない") {
                h.scheduler.tick()
                !m.isRestoring
            }
            assertEquals(MachineState.RUNNING, m.state)
            eventually(60_000, "知らせの後も写せない") {
                h.scheduler.tick()
                m.saveReadiness() == SaveReadiness.READY && m.captureCopy(CapturePolicy.SKIP_IF_TRANSIENT) != null
            }
            m.close()
        }
    }

    @Test
    fun `戻せる世代がなければ冷えた起動待ちになり、受け手が始める`() = timeout(limit) {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        Harness().use { h ->
            val identity = store.assignNewId(owner)!!
            val m = h.machine()
            val finished = Finished()
            finished.onFinished = { machine, outcome ->
                assertEquals(RestoreOutcome.COLD_BOOT, outcome)
                // 受け手が画面をつなぐ: 戻す途中ではないので、大きさが入る（冷えた起動でも残る）
                machine.resizeConsole(40, 12)
                assertFalse(machine.boot(bootConfig()), "冷えた起動待ちは boot を断る")
                assertTrue(machine.coldBootAfterRestore())
                assertFalse(machine.coldBootAfterRestore(), "2回目は何もしない")
            }
            assertTrue(m.restore(RestoreRequest(RestoreSource(store, identity.id, 5), bootConfig(), finished.listener)))
            eventually(10_000, "知らせが来ない") {
                h.scheduler.tick()
                finished.outcomes.isNotEmpty()
            }
            finished.assertNoErrors()
            assertEquals(ColdBootCause.NO_GENERATION, finished.outcomes.single().third)
            eventually(10_000, "起動の手順が終わらない") {
                h.scheduler.tick()
                !m.isBooting
            }
            assertFalse(m.isRestoring)
            assertEquals(TtySize(40, 12), m.consoleSize, "冷えた起動の前に渡した画面の大きさが残る")
            m.close()
        }
    }

    @Test
    fun `受け手がなければ冷えた起動を自分で始める`() = timeout(limit) {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        Harness().use { h ->
            val identity = store.assignNewId(owner)!!
            val m = h.machine()
            assertTrue(m.restore(RestoreRequest(RestoreSource(store, identity.id, 1), bootConfig())))
            eventually(10_000, "冷えた起動を始めない") {
                h.scheduler.tick()
                !m.isRestoring && !m.isBooting && m.lastBootConfig != null
            }
            assertFalse(m.isQuiesced)
            m.close()
        }
    }

    @Test
    fun `表に入れる前にstopすると取り消し、マシンは止まったまま`() = timeout(limit) {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
            val saved = saveWaitingPrompt(h, store, TestMemoryFileSystem())
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            h.scheduler.hooks.beforeRestoreInstall = {
                entered.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
            val m = h.machine(fuelPerTick = 10_000_000)
            val finished = Finished()
            assertTrue(m.restore(RestoreRequest(RestoreSource(store, saved.identity.id, saved.identity.generation), bootConfig(), finished.listener)))
            assertTrue(entered.await(60, TimeUnit.SECONDS))
            // 戻す途中は、コンソールの入力・大きさを受け付けず、コンソールも作らない
            m.resizeConsole(10, 5)
            assertEquals(TtySize(80, 25), m.consoleSize)
            assertEquals(0, m.offerConsoleInput("a".toByteArray()))
            assertEquals(0, m.lock.withLockValue { m.kernel.consoleTtyId })
            m.stop()
            assertFalse(m.isRestoring)
            release.countDown()
            eventually(10_000, "取り消しの知らせが来ない") {
                h.scheduler.tick()
                finished.outcomes.isNotEmpty()
            }
            finished.assertNoErrors()
            assertEquals(RestoreOutcome.CANCELLED, finished.outcomes.single().second)
            // 後段は入れない
            Thread.sleep(100)
            h.scheduler.tick()
            assertEquals(MachineState.STOPPED, m.state)
            assertEquals(0, m.lock.withLockValue { m.kernel.processes.size })
            assertFalse(m.isQuiesced)
            assertEquals(1, finished.outcomes.size)
            m.close()
        }
    }

    @Test
    fun `表に入れる途中で失敗したらCRASHEDにし、知らせは送らず、作ったものを返す`() = timeout(limit) {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
            val saved = saveWaitingPrompt(h, store, TestMemoryFileSystem(), tmpBytes = 10_000)
            val disk = CountingDisk()
            openRootAs(h, disk)
            val base = h.scheduler.memoryAccount.usedBytes
            var duringInstall = -1L
            // つないだ後（マウント・/tmp を表に入れ、プロセスはまだ runtimes に入れていない）で落とす
            h.scheduler.hooks.duringRestoreInstall = { m ->
                duringInstall = m.memoryAccount.usedBytes
                throw IllegalStateException("テストの失敗")
            }
            val m = h.machine(fuelPerTick = 10_000_000)
            val finished = Finished()
            assertTrue(m.restore(request(saved, store, finished)))
            eventually(60_000, "CRASHED にならない") {
                h.scheduler.tick()
                m.state == MachineState.CRASHED
            }
            h.awaitRestoreCleanup(m)
            assertTrue(finished.outcomes.isEmpty(), "後段の失敗で知らせを送った: ${finished.outcomes}")
            assertFalse(m.isRestoring)
            assertFalse(m.isQuiesced)
            assertEquals(1, h.events.count { it.first == m.id && it.second == MachineState.CRASHED })
            // インスタンス・/tmp の RAM はサーバー全体の勘定にも返り、ディスクは閉じる（#576。起動用スレッドで返す）
            assertTrue(duringInstall >= 10_000, "戻す途中で RAM を数えていない（$duringInstall）")
            eventually(10_000, "RAM が返らない") { h.scheduler.memoryAccount.usedBytes == base }
            assertEquals(0L, m.memoryAccount.usedBytes)
            eventually(10_000, "ディスクを閉じない") { disk.retired.get() >= 1 }
            // runtimes に入れる前の表のプロセスも終わった状態になり、awaitTermination が返る（#671）。表は巻き戻さない
            assertTrue(m.awaitTermination(5_000), "表のプロセスが終わっていない")
            m.lock.withLockValue {
                assertEquals(0, m.kernel.processes.liveCount())
                assertTrue(m.kernel.processes.size > 0, "表を巻き戻した")
                assertTrue(m.kernel.processes.all().all { it.state.isTerminal })
                assertTrue(m.kernel.processes.all().any { it.state == ProcessState.KILLED })
            }
            m.close()
        }
    }

    @Test
    fun `採用済みの親と未採用の子が混ざる位置で後段が失敗しても、未採用の子は表に残ったままKILLEDになり、awaitTerminationが返る`() = timeout(limit) {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
            // 親（init）と、その子の2つが、入力待ちで保存される
            val (saved, childPid) = saveWaitingPromptWithChild(h, store)
            val parentPid = saved.pid
            val disk = CountingDisk()
            openRootAs(h, disk)
            var adopted = -1
            // 1つ目を runtimes に入れた直後（1つ目のループの途中）で落とす。2つ目は runtimes に入らない
            h.scheduler.hooks.afterRestoreAdopt = { _, pid ->
                if (adopted < 0) {
                    adopted = pid
                    throw IllegalStateException("テストの失敗")
                }
            }
            val m = h.machine(fuelPerTick = 10_000_000)
            val finished = Finished()
            assertTrue(m.restore(request(saved, store, finished)))
            eventually(60_000, "CRASHED にならない") {
                h.scheduler.tick()
                m.state == MachineState.CRASHED
            }
            h.awaitRestoreCleanup(m)
            // 先に入れたのは親（pid の小さい方）で、子は runtimes に入っていない
            assertEquals(parentPid, adopted, "親が先に runtimes に入っていない")
            assertTrue(finished.outcomes.isEmpty(), "後段の失敗で知らせを送った: ${finished.outcomes}")
            assertFalse(m.isRestoring)
            assertFalse(m.isQuiesced, "復元の quiesce が閉じていない")
            assertEquals(1, h.events.count { it.first == m.id && it.second == MachineState.CRASHED })
            // stopLocked が最後まで進み、表のプロセスがすべて終わっている
            assertTrue(m.awaitTermination(5_000), "表のプロセスが終わっていない")
            m.lock.withLockValue {
                assertNotNull(m.runtimes[parentPid], "親が runtimes にない")
                assertNull(m.runtimes[childPid], "子が runtimes に入っている")
                assertEquals(0, m.kernel.processes.liveCount())
                // 表は巻き戻さない: 採用済みの親の終わりが、未採用の子を刈り取っていない
                val child = m.kernel.processes[childPid]
                assertNotNull(child, "未採用の子が表から外れた")
                assertEquals(ProcessState.KILLED, child!!.state)
                assertEquals(0, child.parentPid, "親なしに付け替わっていない")
                assertNotNull(m.kernel.objects[child.objectId], "未採用の子の ProcessObject の参照が返った")
                assertTrue(m.kernel.processes.all().all { it.state.isTerminal })
            }
            m.close()
        }
    }

    @Test
    fun `表に何も入れる前に後段が失敗しても、作ったものを返す`() = timeout(limit) {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
            val saved = saveWaitingPrompt(h, store, TestMemoryFileSystem(), tmpBytes = 10_000)
            val disk = CountingDisk()
            openRootAs(h, disk)
            val base = h.scheduler.memoryAccount.usedBytes
            var beforeInstall = -1L
            // 表を空でなくして、後段の最初の確かめで落とす
            h.scheduler.hooks.beforeRestoreInstall = { m ->
                beforeInstall = m.memoryAccount.usedBytes
                m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "disk:dummy")
            }
            val m = h.machine(fuelPerTick = 10_000_000)
            val finished = Finished()
            assertTrue(m.restore(request(saved, store, finished)))
            eventually(60_000, "CRASHED にならない") {
                h.scheduler.tick()
                m.state == MachineState.CRASHED
            }
            h.awaitRestoreCleanup(m)
            assertTrue(finished.outcomes.isEmpty(), "後段の失敗で知らせを送った: ${finished.outcomes}")
            assertFalse(m.isColdBootPending, "後段の失敗は冷えた起動にしない")
            assertFalse(m.isRestoring)
            assertFalse(m.isQuiesced)
            assertEquals(1, h.events.count { it.first == m.id && it.second == MachineState.CRASHED })
            assertTrue(beforeInstall >= 10_000, "戻す途中で RAM を数えていない（$beforeInstall）")
            eventually(10_000, "RAM が返らない") { h.scheduler.memoryAccount.usedBytes == base }
            eventually(10_000, "ディスクを閉じない") { disk.retired.get() >= 1 }
            m.close()
        }
    }

    @Test
    fun `冷えた起動待ちで知らせを配る前にstopすると、CANCELLEDが1回だけ届く`() = timeout(limit) {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        Harness().use { h ->
            val identity = store.assignNewId(owner)!!
            val m = h.machine()
            val finished = Finished()
            assertTrue(m.restore(RestoreRequest(RestoreSource(store, identity.id, 5), bootConfig(), finished.listener)))
            // tick を回さない（知らせを配らない）まま、前段が冷えた起動待ちに切り替えるのを待つ
            eventually(10_000, "冷えた起動待ちにならない") { m.isColdBootPending }
            m.stop()
            repeat(5) { h.scheduler.tick() }
            finished.assertNoErrors()
            val (machine, outcome, cause) = finished.outcomes.single()
            assertTrue(machine === m)
            assertEquals(RestoreOutcome.CANCELLED, outcome)
            assertNull(cause)
            assertFalse(m.isBooting, "冷えた起動を始めない")
            assertFalse(m.isRestoring)
            assertFalse(m.isQuiesced)
            assertFalse(m.isColdBootPending)
            assertEquals(MachineState.STOPPED, m.state)
            m.close()
        }
    }

    @Test
    fun `取り消した後のrestoreは断り、古い前段のものは返す`() = timeout(limit) {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
            val saved = saveWaitingPrompt(h, store, TestMemoryFileSystem(), tmpBytes = 10_000)
            val base = h.scheduler.memoryAccount.usedBytes
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            var beforeInstall = -1L
            h.scheduler.hooks.beforeRestoreInstall = { m ->
                beforeInstall = m.memoryAccount.usedBytes
                entered.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
            val m = h.machine(fuelPerTick = 10_000_000)
            val finished = Finished()
            assertTrue(m.restore(request(saved, store, finished)))
            assertTrue(entered.await(60, TimeUnit.SECONDS))
            m.stop()
            // 古い前段はまだ後段の手前で止まっている: 受け付け直すと、古い prepared を表に入れてしまう
            assertFalse(m.restore(request(saved, store, finished)), "取り消したマシンの restore は断る")
            release.countDown()
            eventually(10_000, "RAM が返らない") {
                h.scheduler.tick()
                h.scheduler.memoryAccount.usedBytes == base
            }
            repeat(3) { h.scheduler.tick() }
            finished.assertNoErrors()
            assertTrue(beforeInstall >= 10_000, "戻す途中で RAM を数えていない（$beforeInstall）")
            assertEquals(listOf(RestoreOutcome.CANCELLED), finished.outcomes.map { it.second })
            assertEquals(MachineState.STOPPED, m.state)
            assertEquals(0, m.lock.withLockValue { m.kernel.processes.size })
            assertTrue(m.lock.withLockValue { m.kernel.mounts.entries.isEmpty() })
            assertFalse(m.isQuiesced)
            m.close()
        }
    }

    @Test
    fun `サーバー全体のRAMが足りずにインスタンスを作り直せなければNO_MEMORY`() = timeout(limit) {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        val config = SchedulerConfig.builder().maxConcurrentMachines(4).maxBlockingProcesses(64).totalMemoryBytes(256L * 1024 * 1024).build()
        Harness(config, execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
            val saved = saveWaitingPrompt(h, store, TestMemoryFileSystem())
            val total = h.scheduler.memoryAccount
            val filler = total.limitBytes - total.usedBytes
            assertTrue(total.tryReserveBytes(filler), "サーバー全体の RAM を埋める")
            val m = h.machine(fuelPerTick = 10_000_000)
            val finished = Finished()
            assertTrue(m.restore(request(saved, store, finished)))
            eventually(60_000, "知らせが来ない") {
                h.scheduler.tick()
                finished.outcomes.isNotEmpty()
            }
            finished.assertNoErrors()
            assertEquals(RestoreOutcome.COLD_BOOT, finished.outcomes.single().second)
            assertEquals(ColdBootCause.NO_MEMORY, finished.outcomes.single().third)
            m.close()
            total.releaseBytes(filler)
        }
    }

    @Test
    fun `RAMの不足でなくインスタンスを作り直せなければRESTORE_FAILED`() = timeout(limit) {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        Harness(execRuntime = LinkFailingRestoreRuntime(EndiveRuntime()), moduleStore = MemoryModuleStore()).use { h ->
            val saved = saveWaitingPrompt(h, store, TestMemoryFileSystem())
            val m = h.machine(fuelPerTick = 10_000_000)
            val finished = Finished()
            assertTrue(m.restore(request(saved, store, finished)))
            eventually(60_000, "知らせが来ない") {
                h.scheduler.tick()
                finished.outcomes.isNotEmpty()
            }
            finished.assertNoErrors()
            assertEquals(RestoreOutcome.COLD_BOOT, finished.outcomes.single().second)
            assertEquals(ColdBootCause.RESTORE_FAILED, finished.outcomes.single().third)
            m.close()
        }
    }

    @Test
    fun `モジュールが置き場になければ冷えた起動にする`() = timeout(limit) {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        val modules = MemoryModuleStore()
        Harness(execRuntime = EndiveRuntime(), moduleStore = modules).use { h ->
            val saved = saveWaitingPrompt(h, store, TestMemoryFileSystem())
            modules.remove(pro.ikili.kibicomputers.core.persist.ModuleStore.sha256(resource("prompt-wasi.wasm")))
            val m = h.machine(fuelPerTick = 10_000_000)
            val finished = Finished()
            assertTrue(m.restore(RestoreRequest(RestoreSource(store, saved.identity.id, saved.identity.generation), bootConfig(), finished.listener)))
            eventually(60_000, "知らせが来ない") {
                h.scheduler.tick()
                finished.outcomes.isNotEmpty()
            }
            finished.assertNoErrors()
            assertEquals(RestoreOutcome.COLD_BOOT, finished.outcomes.single().second)
            assertEquals(ColdBootCause.MODULE_MISSING, finished.outcomes.single().third)
            assertEquals(0, m.lock.withLockValue { m.kernel.processes.size }, "表は空のまま")
            m.close()
        }
    }

    @Test
    fun `戻したプロセスは知らせが返るまで動かない`() = timeout(limit) {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
            val saved = saveWaitingPrompt(h, store, TestMemoryFileSystem())
            val m = h.machine(fuelPerTick = 10_000_000)
            val finished = Finished()
            var stepsBefore = -1L
            var initial = ""
            finished.onFinished = { machine, _ ->
                initial = output(machine, saved.output)
                // 入力を入れても、止めている間は step に入らない（起こされても燃料・CPU の割り当てを待つ）
                machine.lock.withLockValue {
                    val line = "x\n".toByteArray()
                    (machine.kernel.objects[saved.input] as ByteStream).write(line, 0, line.size, machine.wakeSink)
                }
                Thread.sleep(200)
                stepsBefore = output(machine, saved.output).length.toLong()
                assertEquals(initial, output(machine, saved.output), "止めている間に動いた")
                assertNotNull(machine.runtime(saved.pid))
            }
            assertTrue(m.restore(RestoreRequest(RestoreSource(store, saved.identity.id, saved.identity.generation), bootConfig(), finished.listener)))
            eventually(60_000, "復元が終わらない") {
                h.scheduler.tick()
                finished.outcomes.isNotEmpty()
            }
            finished.assertNoErrors()
            val atFinish = stepsBefore
            eventually(60_000, "止めるのをやめた後に動かない") {
                h.scheduler.tick()
                output(m, saved.output).length > atFinish
            }
            m.close()
        }
    }

    /** 戻したマシンで電源の求めを確かめる接続層（#306）。再起動の求めでは、既定の実装（`boot(lastBootConfig)`）に任せる */
    private class PowerHost : MachineHost {
        val reboots = AtomicInteger()
        val failures: MutableList<BootFailure> = Collections.synchronizedList(ArrayList())

        /** 再起動の求めを受けたときの [Machine.lastBootConfig] */
        @Volatile
        var configAtReboot: BootConfig? = null

        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) {}

        override fun onBootFailed(machine: Machine, failure: BootFailure, detail: String) {
            failures += failure
        }

        override fun onRebootRequested(machine: Machine) {
            configAtReboot = machine.lastBootConfig
            reboots.incrementAndGet()
            super.onRebootRequested(machine)
        }
    }

    /**
     * `prompt` を init として書いた世代を、起動ディスク `boot`（`/sbin/init` に `prompt`）を指す [BootConfig] で戻し、RESTORED まで進める。
     * 戻した init の子として、電源を求める [driver] を動かす
     */
    private fun restoreThenAsk(h: Harness, store: MachineSaveStore, host: PowerHost, driver: TestDriver): Triple<Machine, Saved, BootConfig> {
        val saved = saveWaitingPrompt(h, store, TestMemoryFileSystem())
        val bootDisk = TestMemoryFileSystem().also { fs ->
            fs.createDirectory(VfsPath.parse("/sbin"))
            val bytes = resource("prompt-wasi.wasm")
            fs.write(fs.createFile(VfsPath.parse(BootConfig.INIT_PATH)), 0, bytes, 0, bytes.size)
        }
        h.scheduler.hooks.bootVolumes = BootVolumeOpener { id ->
            when (id) {
                "root" -> BootVolume(TestMemoryFileSystem(), readOnly = false)
                "boot" -> BootVolume(bootDisk, readOnly = true)
                else -> null
            }
        }
        val config = BootConfig.builder().addBootDisk("boot").build()
        val m = h.machine(fuelPerTick = 10_000_000, host = host)
        val finished = Finished()
        assertTrue(m.restore(RestoreRequest(RestoreSource(store, saved.identity.id, saved.identity.generation), config, finished.listener)))
        eventually(60_000, "復元が終わらない") {
            h.scheduler.tick()
            finished.outcomes.isNotEmpty()
        }
        assertEquals(RestoreOutcome.RESTORED, finished.outcomes.single().second)
        assertEquals(MachineState.RUNNING, m.state)
        assertTrue(m.lastBootConfig === config, "復元の求めの BootConfig が最後の BootConfig になる")
        // 戻した init（saved.pid）の子にする。子がすぐ終わって刈り取られても取り逃さないよう、spawnRt で取る
        m.spawnRt(h.program(emptyModule()), driver, SpawnOptions(), parentPid = saved.pid)
        return Triple(m, saved, config)
    }

    @Test
    fun `戻したマシンでもプロセスの再起動の求めを受け付け、戻した表を片付けて最後のBootConfigで起動し直す`() = timeout(limit) {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
            val host = PowerHost()
            val returned = AtomicInteger()
            val asker = TestDriver(ProcessKind.BLOCKING) { ctx ->
                ctx.requestReboot()
                returned.incrementAndGet()
                StepResult.Exit(0)
            }
            val (m, _, config) = restoreThenAsk(h, store, host, asker)
            eventually(60_000, "再起動して init が入力を待たない") {
                h.scheduler.tick()
                host.reboots.get() == 1 && m.state == MachineState.RUNNING && !m.isBooting && !m.isRebootPending &&
                    m.lock.withLockValue {
                        val init = m.kernel.processes[m.kernel.initPid]
                        init != null && init.state == ProcessState.WAITING_EVENT
                    }
            }
            assertEquals(0, returned.get(), "受け付けたら戻らない")
            assertTrue(host.configAtReboot === config)
            assertEquals(emptyList<BootFailure>(), host.failures.toList())
            m.lock.withLockValue {
                // 戻した表（prompt と電源を求めた子）は片付き、起動し直した init だけが残る（オブジェクトの ID は使い回されうるので見ない）
                assertEquals(listOf(m.kernel.initPid), m.kernel.processes.all().map { it.pid })
            }
            m.close()
        }
    }

    @Test
    fun `戻したマシンでプロセスが停止を求めると、再起動せずに止まる`() = timeout(limit) {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
            val host = PowerHost()
            val asker = TestDriver(ProcessKind.BLOCKING) { ctx -> ctx.requestShutdown() }
            val (m, saved, _) = restoreThenAsk(h, store, host, asker)
            eventually(60_000, "止まらない") {
                h.scheduler.tick()
                m.state == MachineState.STOPPED
            }
            assertTrue(m.awaitTermination(5_000))
            repeat(20) {
                h.scheduler.tick()
                Thread.sleep(5)
            }
            assertEquals(0, host.reboots.get())
            assertEquals(MachineState.STOPPED, m.state)
            assertEquals(ProcessState.KILLED, m.stateOf(saved.pid))
            m.close()
        }
    }
}
