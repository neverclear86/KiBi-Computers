package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.ProgramCache
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
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
import pro.ikili.kibicomputers.core.persist.MachineSnapshot
import pro.ikili.kibicomputers.core.persist.MachineSnapshotSource
import pro.ikili.kibicomputers.core.persist.RestoreSource
import pro.ikili.kibicomputers.core.persist.SnapshotSection
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.runtime.validation.ModuleLimits
import java.nio.file.Path
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * 復元の前段が、モジュールの読み込みを起動用のスレッドを持たずに待つこと（#577）。
 *
 * 保存と復元を別々のスケジューラ（別々の `ProgramCache`）で行い、復元する側ではモジュールを読み込み直させる。
 * 読み込み用のスレッド（1本）は、別の持ち主の読み込みをゲートで止めて塞ぐ
 */
class RestoreModuleWaitTest {
    @TempDir
    lateinit var dir: Path

    private val limit = Duration.ofSeconds(120)
    private val owner = MachineOwner("minecraft:overworld", 0, 64, 0)

    /** 読み込みを止める持ち主（どのマシンの ID とも重ならない） */
    private val holder = Long.MIN_VALUE

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/$name")) { "$name がリソースにない" }.use { it.readBytes() }

    private class Finished {
        val outcomes: MutableList<Triple<Machine, RestoreOutcome, ColdBootCause?>> = Collections.synchronizedList(ArrayList())
        val listener = RestoreListener { m, outcome, cause -> outcomes += Triple(m, outcome, cause) }
    }

    /** 起動用のスレッドも読み込み用のスレッドも1本のスケジューラ */
    private fun restoringHarness(modules: MemoryModuleStore, moduleLimits: ModuleLimits = ModuleLimits.DEFAULT): Harness = Harness(
        SchedulerConfig.builder().maxConcurrentMachines(1).maxBlockingProcesses(64).compilerThreads(1).build(),
        execRuntime = EndiveRuntime(),
        moduleLimits = moduleLimits,
        moduleStore = modules,
    )

    /**
     * 別のスケジューラで `prompt` を init として動かし、入力を待ったところで書く（[count] 台）。
     * モジュールは [modules] に置かれる（復元する側と共有する）。[second] なら、元のハッシュだけが違う `prompt` をもう1つ動かす
     * （別々のモジュールが2つある写し）
     */
    private fun save(store: MachineSaveStore, modules: MemoryModuleStore, count: Int = 1, second: Boolean = false): List<MachineIdentity> =
        Harness(execRuntime = EndiveRuntime(), moduleStore = modules).use { h ->
            val bytes = resource("prompt-wasi.wasm")
            val commands = listOf("prompt" to bytes) +
                if (second) listOf("prompt2" to SnapshotDecoderModuleSourcesTest.withCustomSection(bytes)) else emptyList()
            List(count) {
                val m = h.machine(fuelPerTick = 10_000_000)
                m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "disk:root")
                m.vfs.createDirectory(VfsPath.parse("/bin"))
                val pids = commands.map { (name, b) ->
                    val f = m.vfs.open(VfsPath.parse("/bin/$name"), OpenOptions(read = true, write = true, create = true, truncate = true))
                    m.vfs.write(f, b, 0, b.size)
                    val (input, output) = m.lock.withLockValue {
                        ByteStream(4096).also { m.kernel.objects.add(it) }.id to ByteStream(64 * 1024).also { m.kernel.objects.add(it) }.id
                    }
                    val stdio = StdioSpec(StdioSource.Object(input, HandleRights.READ), StdioSource.Object(output, HandleRights.WRITE), StdioSource.Closed)
                    m.exec("/bin/$name", KernelDrivers.DEFAULT, SpawnOptions(args = listOf(name), stdio = stdio))
                }
                m.lock.withLockValue { m.kernel.initPid = pids.first() }
                eventually(60_000, "入力を待たない") {
                    h.scheduler.tick()
                    pids.all { m.stateOf(it) == ProcessState.WAITING_EVENT } && m.isAtWaitPoint
                }
                val identity = store.assignNewId(owner)!!
                val written = store.write(identity, owner, m.captureSnapshot(CapturePolicy.SKIP_IF_TRANSIENT)!!).identity
                m.close()
                written
            }
        }

    /** [identity] の世代の節を [transform] で替えた世代を書く（CRC の合う、壊れた中身の世代を作る） */
    private fun rewrite(store: MachineSaveStore, identity: MachineIdentity, transform: (MachineSnapshot) -> List<SnapshotSection>): MachineIdentity {
        val sections = transform(store.readGeneration(identity.id, identity.generation).snapshot)
        return store.write(identity, owner, MachineSnapshotSource { id, generation, modVersion -> MachineSnapshot(id, generation, modVersion, sections) }).identity
    }

    /** `KERNEL` 節だけを壊す（`PROCESS_ABI` 節は正しいので、先読みは通り、復号で UNREADABLE になる） */
    private fun breakKernel(snapshot: MachineSnapshot): List<SnapshotSection> = snapshot.sections.map {
        if (it.kind == SnapshotSection.KERNEL) SnapshotSection(it.kind, it.version, byteArrayOf(1, 2, 3)) else it
    }

    /** 前段の取り消しの登録が残っていない（終わった前段の `Program`・写しを捕まえていない。#587） */
    private fun assertNoCancelRegistration(m: Machine) {
        assertEquals(0, m.restoreCancelSignal.numberOfDependents, "復元の取り消しの登録が残っている")
    }

    /** 復元を始め、知らせが1つ来るまで回す */
    private fun restoreAndAwait(h: Harness, identity: MachineIdentity, store: MachineSaveStore): Pair<Machine, Triple<Machine, RestoreOutcome, ColdBootCause?>> {
        val finished = Finished()
        val m = h.machine(fuelPerTick = 10_000_000)
        assertTrue(m.restore(request(identity, store, finished)))
        eventually(60_000, "復元が終わらない") {
            h.scheduler.tick()
            finished.outcomes.isNotEmpty()
        }
        return m to finished.outcomes.single()
    }

    private fun request(identity: MachineIdentity, store: MachineSaveStore, finished: Finished): RestoreRequest =
        RestoreRequest(RestoreSource(store, identity.id, identity.generation), BootConfig.builder().addBootDisk("none").build(), finished.listener)

    /** 読み込み用のスレッドを [gate] が開くまで塞ぐ（塞いだのを待ってから返す） */
    private fun blockCompiler(h: Harness, gate: CountDownLatch) {
        val started = CountDownLatch(1)
        h.scheduler.programs.load(holder, "held".toByteArray(), true, emptySet()) {
            started.countDown()
            gate.await()
            throw WasmLoadException("held")
        }
        assertTrue(started.await(10, TimeUnit.SECONDS), "読み込み用のスレッドを塞げない")
    }

    /** 戻すマシンの根（`disk:root`）を新しいディスクで開き、`d` は [init] を置いたディスクにする */
    private fun volumes(h: Harness, init: TestMemoryFileSystem? = null) {
        h.scheduler.hooks.bootVolumes = BootVolumeOpener { id ->
            when (id) {
                "root" -> BootVolume(TestMemoryFileSystem(), readOnly = false)
                "d" -> init?.let { BootVolume(it, readOnly = false) }
                else -> null
            }
        }
    }

    /** `/sbin/init` に [bytes] を置いたディスク */
    private fun initDisk(bytes: ByteArray): TestMemoryFileSystem = TestMemoryFileSystem().also { fs ->
        fs.createDirectory(VfsPath.parse("/sbin"))
        val node = fs.createFile(VfsPath.parse(BootConfig.INIT_PATH))
        fs.write(node, 0, bytes, 0, bytes.size)
    }

    private fun Harness.ranOnce(m: Machine): Boolean = events.any { it.first == m.id && it.second == MachineState.RUNNING }

    /** 復元がすべてモジュールの読み込みを待っていて、起動用のスレッドを1本も使っていない */
    private fun Harness.restoresParked(loading: Int): Boolean =
        scheduler.programs.loadingCount == loading && scheduler.bootExecutor.activeCount == 0 && scheduler.bootExecutor.queue.isEmpty()

    @Test
    fun `起動用のスレッドが1本でも、読み込みを待つ復元の間に別の台の冷えた起動が進む`() = timeout(limit) {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        val modules = MemoryModuleStore()
        val saved = save(store, modules, count = 2)
        restoringHarness(modules).use { h ->
            val gate = CountDownLatch(1)
            try {
                volumes(h, initDisk(resource("hello-wasi.wasm")))
                val boot = BootConfig.builder().bootDisks(listOf("d")).build()
                // 冷えた起動の init は先に一度読み込ませておく（読み込み用のスレッドを塞いだ後も、共有済みなのですぐ使える）
                val warm = h.machine(fuelPerTick = 10_000_000)
                assertTrue(warm.boot(boot))
                eventually(60_000, "先の起動が終わらない") {
                    h.scheduler.tick()
                    h.ranOnce(warm) && warm.state == MachineState.STOPPED
                }
                blockCompiler(h, gate)

                val finished = Finished()
                val restoring = saved.map { id -> h.machine(fuelPerTick = 10_000_000).also { assertTrue(it.restore(request(id, store, finished))) } }
                // 2 台とも prompt の読み込み（相乗りで1つ）を待ち、起動用のスレッドは空いている
                eventually(10_000, "復元が読み込みを待たない") { h.restoresParked(loading = 2) }
                assertTrue(restoring.all { it.isRestoring })

                val cold = h.machine(fuelPerTick = 10_000_000)
                assertTrue(cold.boot(boot))
                eventually(60_000, "復元が読み込みを待つ間に、冷えた起動が進まない") {
                    h.scheduler.tick()
                    h.ranOnce(cold)
                }
                assertTrue(restoring.all { it.isRestoring }, "読み込みはまだ終わっていない")
                assertTrue(finished.outcomes.isEmpty())

                gate.countDown()
                eventually(60_000, "復元が終わらない") {
                    h.scheduler.tick()
                    finished.outcomes.size == 2
                }
                assertTrue(finished.outcomes.all { it.second == RestoreOutcome.RESTORED }, finished.outcomes.toString())
                assertTrue(restoring.all { it.state == MachineState.RUNNING })
                restoring.forEach { assertNoCancelRegistration(it) }
            } finally {
                gate.countDown()
            }
        }
    }

    @Test
    fun `読み込みが混んでいたら、スレッドを持たずにやり直して戻す`() = timeout(limit) {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        val modules = MemoryModuleStore()
        val saved = save(store, modules).single()
        restoringHarness(modules).use { h ->
            val gate = CountDownLatch(1)
            try {
                volumes(h)
                val m = h.machine(fuelPerTick = 10_000_000)
                val programs = h.scheduler.programs
                // この台の読み込みが上限まで残っている（1つ目が読み込み用のスレッドを塞ぎ、残りは待ち行列）
                val max = ProgramCache.DEFAULT_MAX_PENDING_PER_OWNER
                val started = CountDownLatch(1)
                repeat(max) { i ->
                    programs.load(m.id, "held-$i".toByteArray(), true, emptySet()) {
                        started.countDown()
                        gate.await()
                        throw WasmLoadException("held")
                    }
                }
                assertTrue(started.await(10, TimeUnit.SECONDS))
                assertEquals(max, programs.pendingOf(m.id))

                val finished = Finished()
                assertTrue(m.restore(request(saved, store, finished)))
                // やり直しの合間は、起動用のスレッドを持たない
                eventually(10_000, "やり直しの合間に起動用のスレッドが空かない") {
                    h.scheduler.bootExecutor.activeCount == 0 && h.scheduler.bootExecutor.queue.isEmpty()
                }
                Thread.sleep(200)
                h.scheduler.tick()
                assertTrue(m.isRestoring, "混んでいる間は冷えた起動に落とさない")
                assertTrue(finished.outcomes.isEmpty())
                assertEquals(max, programs.pendingOf(m.id), "混んでいる間は読み込みを積まない")

                gate.countDown()
                eventually(60_000, "復元が終わらない") {
                    h.scheduler.tick()
                    finished.outcomes.isNotEmpty()
                }
                val (_, outcome, cause) = finished.outcomes.single()
                assertEquals(RestoreOutcome.RESTORED, outcome, cause.toString())
                assertEquals(MachineState.RUNNING, m.state)
                assertNoCancelRegistration(m)
            } finally {
                gate.countDown()
            }
        }
    }

    @Test
    fun `読み込みを待つ間にstopすると、読み込みを取り消してCANCELLEDが1回だけ届く`() = timeout(limit) {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        val modules = MemoryModuleStore()
        val saved = save(store, modules).single()
        restoringHarness(modules).use { h ->
            val gate = CountDownLatch(1)
            try {
                volumes(h)
                blockCompiler(h, gate)
                val m = h.machine(fuelPerTick = 10_000_000)
                val finished = Finished()
                assertTrue(m.restore(request(saved, store, finished)))
                eventually(10_000, "復元が読み込みを待たない") { h.restoresParked(loading = 2) && h.scheduler.programs.pendingOf(m.id) == 1 }

                m.stop()
                eventually(10_000, "知らせが来ない") {
                    h.scheduler.tick()
                    finished.outcomes.isNotEmpty()
                }
                // 始まっていない読み込みは取り消した
                assertEquals(0, h.scheduler.programs.pendingOf(m.id))
                assertEquals(1, h.scheduler.programs.loadingCount)

                gate.countDown()
                eventually(10_000) { h.scheduler.programs.loadingCount == 0 && h.scheduler.bootExecutor.activeCount == 0 }
                repeat(5) { h.scheduler.tick() }
                val (machine, outcome, cause) = finished.outcomes.single()
                assertTrue(machine === m)
                assertEquals(RestoreOutcome.CANCELLED, outcome)
                assertNull(cause)
                assertEquals(MachineState.STOPPED, m.state)
                assertEquals(0, m.lock.withLockValue { m.kernel.processes.size }, "後から表に入らない")
                assertNoCancelRegistration(m)
            } finally {
                gate.countDown()
            }
        }
    }

    @Test
    fun `読み込みを待つ復元が残っていても、スケジューラの停止で全部終わる`() = timeout(limit) {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        val modules = MemoryModuleStore()
        val saved = save(store, modules, count = 2)
        restoringHarness(modules).use { h ->
            val gate = CountDownLatch(1)
            try {
                volumes(h)
                blockCompiler(h, gate)
                val finished = Finished()
                val restoring = saved.map { id -> h.machine(fuelPerTick = 10_000_000).also { assertTrue(it.restore(request(id, store, finished))) } }
                eventually(10_000, "復元が読み込みを待たない") { h.restoresParked(loading = 2) }

                val started = System.nanoTime()
                h.scheduler.close()
                assertTrue(h.scheduler.awaitTermination(5_000), "読み込みを待つ復元が残っている")
                assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(5))
                assertTrue(restoring.none { it.isRestoring })
                assertTrue(restoring.all { it.lock.withLockValue { it.kernel.processes.size } == 0 })
            } finally {
                gate.countDown()
            }
        }
    }

    @Test
    fun `置き場にないモジュールで冷えた起動にしても、取り消しの登録が残らない`() = timeout(limit) {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        val saved = save(store, MemoryModuleStore()).single()
        // 戻す側の置き場は空
        restoringHarness(MemoryModuleStore()).use { h ->
            volumes(h)
            val (m, finished) = restoreAndAwait(h, saved, store)
            assertEquals(RestoreOutcome.COLD_BOOT, finished.second)
            assertEquals(ColdBootCause.MODULE_MISSING, finished.third)
            assertNoCancelRegistration(m)
        }
    }

    @Test
    fun `KERNEL節が壊れていれば、置き場にないモジュールよりUNREADABLEが先に出る`() = timeout(limit) {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        val broken = rewrite(store, save(store, MemoryModuleStore()).single(), ::breakKernel)
        assertFalse(store.readGeneration(broken.id, broken.generation).snapshot.requiresColdBoot, "KILLED 節ではなく復号で断る写し")
        restoringHarness(MemoryModuleStore()).use { h ->
            volumes(h)
            val (m, finished) = restoreAndAwait(h, broken, store)
            assertEquals(RestoreOutcome.COLD_BOOT, finished.second)
            assertEquals(ColdBootCause.UNREADABLE, finished.third)
            assertNoCancelRegistration(m)
        }
    }

    @Test
    fun `KERNEL節が壊れていれば、読み込めないモジュールよりUNREADABLEが先に出る`() = timeout(limit) {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        val modules = MemoryModuleStore()
        val saved = save(store, modules).single()
        // 壊れていなければ MODULE_MISMATCH（戻す側のサーバーは読み込みの上限が厳しい）
        restoringHarness(modules, ModuleLimits(maxBytes = 1)).use { h ->
            volumes(h)
            val (m, finished) = restoreAndAwait(h, saved, store)
            assertEquals(ColdBootCause.MODULE_MISMATCH, finished.third)
            assertNoCancelRegistration(m)
        }
        val broken = rewrite(store, saved, ::breakKernel)
        assertFalse(store.readGeneration(broken.id, broken.generation).snapshot.requiresColdBoot)
        restoringHarness(modules, ModuleLimits(maxBytes = 1)).use { h ->
            volumes(h)
            val (m, finished) = restoreAndAwait(h, broken, store)
            assertEquals(RestoreOutcome.COLD_BOOT, finished.second)
            assertEquals(ColdBootCause.UNREADABLE, finished.third)
            assertNoCancelRegistration(m)
        }
    }

    @Test
    fun `別々のモジュールが2つある写しは、順に1つずつ読み込んで戻す`() = timeout(limit) {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        val modules = MemoryModuleStore()
        val saved = save(store, modules, second = true).single()
        restoringHarness(modules).use { h ->
            val gate = CountDownLatch(1)
            try {
                volumes(h)
                blockCompiler(h, gate)
                val m = h.machine(fuelPerTick = 10_000_000)
                val finished = Finished()
                assertTrue(m.restore(request(saved, store, finished)))
                eventually(10_000, "復元が読み込みを待たない") { h.restoresParked(loading = 2) }
                // 並べて頼まない
                assertEquals(1, h.scheduler.programs.pendingOf(m.id))

                gate.countDown()
                eventually(60_000, "復元が終わらない") {
                    assertTrue(h.scheduler.programs.pendingOf(m.id) <= 1, "モジュールを並べて読み込んだ")
                    h.scheduler.tick()
                    finished.outcomes.isNotEmpty()
                }
                val (_, outcome, cause) = finished.outcomes.single()
                assertEquals(RestoreOutcome.RESTORED, outcome, cause.toString())
                assertEquals(MachineState.RUNNING, m.state)
                val hashes = m.lock.withLockValue { m.kernel.processes.all().filter { !it.state.isTerminal }.map { it.moduleHash }.toSet() }
                assertEquals(2, hashes.size, "2つのモジュールのプロセスが戻っていない")
                assertNoCancelRegistration(m)
            } finally {
                gate.countDown()
            }
        }
    }

    @Test
    fun `読み込みが混んでやり直しを待つ間にstopすると、CANCELLEDが1回だけ届き、読み込みを積まない`() = timeout(limit) {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        val modules = MemoryModuleStore()
        val saved = save(store, modules).single()
        restoringHarness(modules).use { h ->
            val gate = CountDownLatch(1)
            try {
                volumes(h)
                val m = h.machine(fuelPerTick = 10_000_000)
                val programs = h.scheduler.programs
                val max = ProgramCache.DEFAULT_MAX_PENDING_PER_OWNER
                val started = CountDownLatch(1)
                repeat(max) { i ->
                    programs.load(m.id, "held-$i".toByteArray(), true, emptySet()) {
                        started.countDown()
                        gate.await()
                        throw WasmLoadException("held")
                    }
                }
                assertTrue(started.await(10, TimeUnit.SECONDS))

                val finished = Finished()
                assertTrue(m.restore(request(saved, store, finished)))
                eventually(10_000, "やり直しの合間に起動用のスレッドが空かない") {
                    h.scheduler.bootExecutor.activeCount == 0 && h.scheduler.bootExecutor.queue.isEmpty()
                }
                Thread.sleep(100)
                assertTrue(m.isRestoring)

                // 起動用のスレッド（1本）を塞いで、やり直しのタイマーが積む続きを待ち行列に溜めてから取り消す。
                // 続きが取り消しの後に動く順を作る（ゲートが閉じている間は上限で断られて数が変わらないので、
                // 数ではなく、置き場を読んだ回数で「読み込みを頼まない」を確かめる）
                val blockerStarted = CountDownLatch(1)
                val blocker = CountDownLatch(1)
                h.scheduler.bootExecutor.execute {
                    blockerStarted.countDown()
                    blocker.await()
                }
                try {
                    assertTrue(blockerStarted.await(10, TimeUnit.SECONDS))
                    eventually(10_000, "やり直しの続きが待ち行列に溜まらない") { h.scheduler.bootExecutor.queue.isNotEmpty() }
                    m.stop()
                    val getsAfterCancel = modules.gets.get()
                    // 溜まった続きを動かす（取り消し済みなので、読み込みを頼まない）
                    blocker.countDown()
                    eventually(10_000, "溜まった続きが動かない") {
                        h.scheduler.bootExecutor.activeCount == 0 && h.scheduler.bootExecutor.queue.isEmpty()
                    }
                    // タイマーが残っていても、頼まず、積まない
                    Thread.sleep(200)
                    assertEquals(getsAfterCancel, modules.gets.get(), "取り消した後に読み込みを頼んだ")
                } finally {
                    blocker.countDown()
                }
                eventually(10_000, "知らせが来ない") {
                    h.scheduler.tick()
                    finished.outcomes.isNotEmpty()
                }
                assertEquals(max, programs.pendingOf(m.id), "取り消した後に読み込みを積んだ")
                gate.countDown()
                eventually(10_000) { programs.loadingCount == 0 && h.scheduler.bootExecutor.activeCount == 0 && h.scheduler.bootExecutor.queue.isEmpty() }
                repeat(5) { h.scheduler.tick() }
                val (machine, outcome, cause) = finished.outcomes.single()
                assertTrue(machine === m)
                assertEquals(RestoreOutcome.CANCELLED, outcome)
                assertNull(cause)
                assertEquals(0, m.lock.withLockValue { m.kernel.processes.size })
                assertNoCancelRegistration(m)
            } finally {
                gate.countDown()
            }
        }
    }
}
