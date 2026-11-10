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
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.tty.TtySize
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
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.nio.file.Path
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    private fun saveWaitingPrompt(h: Harness, store: MachineSaveStore, root: TestMemoryFileSystem): Saved {
        // 戻すマシンには、新しい空のファイルシステムを渡す（閉じたマシンの根は使わない）
        h.scheduler.hooks.bootVolumes = BootVolumeOpener { id -> if (id == "root") BootVolume(TestMemoryFileSystem(), readOnly = false) else null }
        val m = h.machine(fuelPerTick = 10_000_000)
        val bytes = resource("prompt-wasi.wasm")
        m.vfs.mount(VfsPath.ROOT, root, readOnly = false, source = "disk:root")
        m.vfs.createDirectory(VfsPath.parse("/bin"))
        val f = m.vfs.open(VfsPath.parse("/bin/prompt"), OpenOptions(read = true, write = true, create = true, truncate = true))
        m.vfs.write(f, bytes, 0, bytes.size)
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

    private fun output(m: Machine, id: Int): String = m.lock.withLockValue {
        (m.kernel.objects[id] as ByteStream).availableBytes().toString(Charsets.UTF_8)
    }

    private fun bootConfig(): BootConfig = BootConfig.builder().addBootDisk("none").build()

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
    fun `表に入れる途中で失敗したらCRASHEDにし、知らせは送らない`() = timeout(limit) {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
            val saved = saveWaitingPrompt(h, store, TestMemoryFileSystem())
            h.scheduler.hooks.duringRestoreInstall = { throw IllegalStateException("テストの失敗") }
            val m = h.machine(fuelPerTick = 10_000_000)
            val finished = Finished()
            assertTrue(m.restore(RestoreRequest(RestoreSource(store, saved.identity.id, saved.identity.generation), bootConfig(), finished.listener)))
            eventually(60_000, "CRASHED にならない") {
                h.scheduler.tick()
                m.state == MachineState.CRASHED
            }
            repeat(3) { h.scheduler.tick() }
            assertTrue(finished.outcomes.isEmpty())
            assertFalse(m.isRestoring)
            assertFalse(m.isQuiesced)
            assertEquals(1, h.events.count { it.first == m.id && it.second == MachineState.CRASHED })
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
}
