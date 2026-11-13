package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
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
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.core.persist.MachineIdIndex
import pro.ikili.kibicomputers.core.persist.MachineOwner
import pro.ikili.kibicomputers.core.persist.MachineSaveStore
import pro.ikili.kibicomputers.core.persist.RestoreListener
import pro.ikili.kibicomputers.core.persist.RestoreRequest
import pro.ikili.kibicomputers.core.persist.RestoreSource
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** 復元で戻した init の、再起動の借りを減らす「動いた tick 数」の数え始め（#635、ADR 0635） */
class RebootBackoffRestoreTest {
    @TempDir
    lateinit var dir: Path

    private val limit = Duration.ofSeconds(120)
    private val owner = MachineOwner("minecraft:overworld", 0, 64, 0)
    private val backoff = RebootBackoff(freeReboots = 1, baseDelayTicks = 3, maxDelayTicks = 1000, decayTicks = 1000, capLevels = 4)

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/$name")) { "$name がリソースにない" }.use { it.readBytes() }

    private fun bootConfig(): BootConfig = BootConfig.builder().addBootDisk("none").build()

    @Test
    fun `復元で戻したinitは、戻したtickから動いたと数える`() = timeout(limit) {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore(), rebootBackoff = backoff).use { h ->
            // 入力を待つ init を動かして、待機点で書く
            h.scheduler.hooks.bootVolumes = BootVolumeOpener { id -> if (id == "root") BootVolume(TestMemoryFileSystem(), readOnly = false) else null }
            val first = h.machine(fuelPerTick = 10_000_000)
            first.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "disk:root")
            first.vfs.createDirectory(VfsPath.parse("/bin"))
            val bytes = resource("prompt-wasi.wasm")
            val file = first.vfs.open(VfsPath.parse("/bin/prompt"), OpenOptions(read = true, write = true, create = true, truncate = true))
            first.vfs.write(file, bytes, 0, bytes.size)
            val (input, output) = first.lock.withLockValue {
                ByteStream(4096).also { first.kernel.objects.add(it) }.id to ByteStream(64 * 1024).also { first.kernel.objects.add(it) }.id
            }
            val stdio = StdioSpec(StdioSource.Object(input, HandleRights.READ), StdioSource.Object(output, HandleRights.WRITE), StdioSource.Closed)
            val pid = first.exec("/bin/prompt", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("prompt"), stdio = stdio))
            first.lock.withLockValue { first.kernel.initPid = pid }
            eventually(60_000, "入力を待たない") {
                h.scheduler.tick()
                first.stateOf(pid) == ProcessState.WAITING_EVENT && first.isAtWaitPoint
            }
            val identity = store.assignNewId(owner)!!
            val saved = store.write(identity, owner, first.captureSnapshot(CapturePolicy.SKIP_IF_TRANSIENT)!!).identity
            first.close()

            // 戻す。onRebootRequested は何もしない（起動し直さない）
            val m = h.machine(fuelPerTick = 10_000_000, host = object : MachineHost {
                override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) {}

                override fun onRebootRequested(machine: Machine) {}
            })
            val restored = AtomicBoolean()
            val listener = RestoreListener { _, _, _ -> restored.set(true) }
            assertTrue(m.restore(RestoreRequest(RestoreSource(store, saved.id, saved.generation), bootConfig(), listener)))
            eventually(60_000, "復元が終わらない") {
                h.scheduler.tick()
                restored.get() || m.state == MachineState.CRASHED
            }
            assertEquals(MachineState.RUNNING, m.state)
            assertTrue(m.initStartedTick >= 0, "戻した init は、戻した tick から動いたと数える: ${m.initStartedTick}")
            assertTrue(m.initStartedTick <= h.scheduler.now)

            // 借りのある状態から、戻して 300 tick 動いて再起動する。借りは、動いた分だけ減ってから 1 回分増える
            m.rebootDebt = 2 * backoff.decayTicks
            repeat(300) { h.scheduler.tick() }
            val ran = h.scheduler.now - m.initStartedTick
            assertTrue(ran in 300 until backoff.decayTicks, "戻してから動いた tick 数: $ran")
            val posted = CountDownLatch(1)
            h.scheduler.hooks.duringRebootPost = {
                h.scheduler.hooks.duringRebootPost = null
                posted.countDown()
            }
            assertTrue(m.reboot())
            eventually(60_000, "再起動の求めを積まない") {
                h.scheduler.tick()
                posted.await(1, TimeUnit.MILLISECONDS)
            }
            assertEquals(3 * backoff.decayTicks - ran, m.rebootDebt, "動いた分だけ借りが減る")
            m.close()
        }
    }
}
