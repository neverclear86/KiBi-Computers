package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import pro.ikili.kibicomputers.core.kernel.boot.BootVolume
import pro.ikili.kibicomputers.core.kernel.boot.BootVolumeOpener
import pro.ikili.kibicomputers.core.kernel.event.EventFilter
import pro.ikili.kibicomputers.core.kernel.fd.FdTable
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.time.Timer
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
import kotlin.concurrent.withLock
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * コンフィグでマシンごとの上限を下げた後に、下げる前に保存した台を戻す（#719、ADR 0719）。上限は保存しないので、今の設定で戻す。
 *
 * - キュー・タイマーの数を下げた: 上限を超えたまま戻り、新しく作る・動かすものだけを断る
 * - RAM を下げて写しが収まらない: 壊れずに冷えた起動になり、理由は [ColdBootCause.OVER_LIMIT]
 */
class LimitLoweredRestoreTest {
    @TempDir
    lateinit var dir: Path

    private val limit = Duration.ofSeconds(120)
    private val owner = MachineOwner("minecraft:overworld", 0, 64, 0)

    /** 最後に保存した台の RAM の使用量（バイト） */
    private var lastUsedBytes = 0L

    /**
     * 既定の上限の台で、キューを2つ・動いているタイマーを2つ持つ待機中の状態を保存する。[extraProcesses] があれば、
     * 入力を待つプロセスをその数だけ足す（RAM の使用量を増やすため）
     */
    private fun save(h: Harness, store: MachineSaveStore, extraProcesses: Int = 0): MachineIdentity {
        val m = h.machine(fuelPerTick = 10_000_000)
        val bytes = requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/prompt-wasi.wasm")) { "prompt-wasi.wasm がない" }.use { it.readBytes() }
        m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "disk:root")
        m.vfs.createDirectory(VfsPath.parse("/bin"))
        val f = m.vfs.open(VfsPath.parse("/bin/prompt"), OpenOptions(read = true, write = true, create = true, truncate = true))
        m.vfs.write(f, bytes, 0, bytes.size)
        fun spawn(): Int {
            val input = m.lock.withLock { ByteStream(4096).also { m.kernel.objects.add(it) }.id }
            val output = m.lock.withLock { ByteStream(64 * 1024).also { m.kernel.objects.add(it) }.id }
            val stdio = StdioSpec(StdioSource.Object(input, HandleRights.READ), StdioSource.Object(output, HandleRights.WRITE), StdioSource.Closed)
            return m.exec("/bin/prompt", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("prompt"), stdio = stdio))
        }
        val pid = spawn()
        val pids = listOf(pid) + List(extraProcesses) { spawn() }
        m.lock.withLock { m.kernel.initPid = pid }
        eventually(60_000, "入力を待たない") {
            h.scheduler.tick()
            pids.all { m.stateOf(it) == ProcessState.WAITING_EVENT } && m.isAtWaitPoint
        }
        lastUsedBytes = m.memoryAccount.usedBytes
        m.lock.withLock {
            repeat(2) { m.openEventQueueLocked(pid, EventFilter.ALL, 4) }
            val fds = FdTable(m.kernel.processes[pid]!!, m.kernel.objects, m.wakeSink)
            repeat(2) {
                val timer = Timer()
                fds.open(timer, HandleRights.READ)
                m.armTimerLocked(timer, m.monotonicNanosLocked() + 3_600_000_000_000L, 0)
            }
            assertEquals(2, m.kernel.events.queueCount(m.kernel.objects))
            assertEquals(2, m.timers.scheduledCount)
        }
        val identity = store.assignNewId(owner)!!
        val written = store.write(identity, owner, m.captureSnapshot(CapturePolicy.SKIP_IF_TRANSIENT)!!).identity
        m.close()
        return written
    }

    /** [config] の台に [saved] を戻し、結果と理由を返す */
    private fun restore(h: Harness, store: MachineSaveStore, saved: MachineIdentity, config: MachineConfig): Pair<Machine, Pair<RestoreOutcome, ColdBootCause?>> {
        val m = h.scheduler.createMachine({ _, _, _ -> }, config)
        val outcomes = Collections.synchronizedList(ArrayList<Pair<RestoreOutcome, ColdBootCause?>>())
        val listener = RestoreListener { _, outcome, cause -> outcomes += outcome to cause }
        val boot = BootConfig.builder().addBootDisk("none").build()
        assertTrue(m.restore(RestoreRequest(RestoreSource(store, saved.id, saved.generation), boot, listener)))
        eventually(60_000, "復元が終わらない") {
            h.scheduler.tick()
            outcomes.isNotEmpty()
        }
        return m to outcomes.single()
    }

    private fun harness(): Harness = Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).also { h ->
        h.scheduler.hooks.bootVolumes = BootVolumeOpener { id -> if (id == "root") BootVolume(TestMemoryFileSystem(), readOnly = false) else null }
    }

    @Test
    fun `キューとタイマーの上限を下げても戻り、新しく作るものだけを断る`() = timeout(limit) {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        harness().use { h ->
            val saved = save(h, store)
            val lowered = MachineConfig.builder().fuelPerTick(10_000_000).maxBlockingProcesses(64).maxEventQueues(1).maxArmedTimers(1).build()
            val (m, result) = restore(h, store, saved, lowered)
            assertEquals(RestoreOutcome.RESTORED to null, result)
            m.lock.withLock {
                assertEquals(1, m.kernel.events.maxQueues, "戻した台のキューの上限は今の設定")
                assertEquals(2, m.kernel.events.queueCount(m.kernel.objects), "上限を超えたまま戻る")
                assertEquals(2, m.timers.scheduledCount, "動いていたタイマーは上限を超えたまま戻る")
                val pid = m.kernel.initPid
                assertThrows<ResourceLimitException> { m.openEventQueueLocked(pid, EventFilter.ALL, 4) }
                val timer = Timer()
                FdTable(m.kernel.processes[pid]!!, m.kernel.objects, m.wakeSink).open(timer, HandleRights.READ)
                assertThrows<ResourceLimitException> { m.armTimerLocked(timer, m.monotonicNanosLocked() + 1_000_000_000L, 0) }
            }
            m.close()
        }
    }

    @Test
    fun `RAMを下げて写しが収まらなければ、上限の理由で冷えた起動になる`() = timeout(limit) {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        harness().use { h ->
            // RAM は書き込んだ区画だけを数えるので、プロセスを足して 1 ページ（64 KiB）より確かに多く使わせる
            val saved = save(h, store, extraProcesses = 7)
            val page = 64L * 1024
            assertTrue(lastUsedBytes > 2 * page, "保存した台の RAM の使用量 $lastUsedBytes が少なすぎる")
            val lowered = MachineConfig.builder().fuelPerTick(10_000_000).maxBlockingProcesses(64).memoryBytes(page).build()
            val (m, result) = restore(h, store, saved, lowered)
            assertEquals(RestoreOutcome.COLD_BOOT to ColdBootCause.OVER_LIMIT, result)
            m.close()
        }
    }
}
