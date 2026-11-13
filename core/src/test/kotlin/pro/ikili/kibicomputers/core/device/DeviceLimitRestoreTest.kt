package pro.ikili.kibicomputers.core.device

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pro.ikili.kibicomputers.api.device.Device
import pro.ikili.kibicomputers.api.device.DeviceMethods
import pro.ikili.kibicomputers.api.device.DeviceValue
import pro.ikili.kibicomputers.core.kernel.BootConfig
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.MemoryModuleStore
import pro.ikili.kibicomputers.core.kernel.boot.BootVolume
import pro.ikili.kibicomputers.core.kernel.boot.BootVolumeOpener
import pro.ikili.kibicomputers.core.kernel.eventually
import pro.ikili.kibicomputers.core.kernel.fd.FdTable
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.stateOf
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.core.persist.MachineIdIndex
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
 * 接続数の上限は保存しない（#66、ADR 0066）。保存したときは2つつないでいたマシンを、上限1のマシンに実際に保存から戻し、
 * 戻した後にデバイスをつないで [pro.ikili.kibicomputers.core.kernel.Machine.redispatchRestoredDeviceCalls] を通す
 * （[pro.ikili.kibicomputers.core.computer.ComputerCase.startRestore] と同じ順）。
 * つなげなかったデバイスへのハンドルは残り、積んだ呼び出しは `detached` で終わる。つないだデバイスへの呼び出しは、いつもどおり実行される
 */
class DeviceLimitRestoreTest {
    @TempDir
    lateinit var dir: Path

    private val limit = Duration.ofSeconds(120)
    private val owner = MachineOwner("minecraft:overworld", 0, 64, 0)
    private val a = "00000000-0000-4000-8000-000000000601"
    private val b = "00000000-0000-4000-8000-000000000602"

    private class TestDevice : Device {
        override val kind = "test:device"
        override val methods: DeviceMethods = DeviceMethods.builder().direct("d") { _, _ -> arrayOf(DeviceValue.of(42L)) }.build()
    }

    /** 書いたときの、b へのハンドルと、a・b への積んだ呼び出しのオブジェクト ID */
    private class Saved(val identity: pro.ikili.kibicomputers.core.persist.MachineIdentity, val handleOfB: Int, val callToA: Int, val callToB: Int)

    @Test
    fun `上限1のマシンに戻すと、つなげなかったデバイスのハンドルは残り、積んだ呼び出しはdetachedで終わる`() = timeout(limit) {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
            h.scheduler.hooks.bootVolumes = BootVolumeOpener { id -> if (id == "root") BootVolume(TestMemoryFileSystem(), readOnly = false) else null }
            // 保存するマシン: 上限は既定（16）で、a・b の2つをつないでいる
            val saved = run {
                val m = h.machine(fuelPerTick = 10_000_000)
                val bytes = requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/prompt-wasi.wasm")) { "prompt-wasi.wasm がない" }.use { it.readBytes() }
                m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "disk:root")
                m.vfs.createDirectory(VfsPath.parse("/bin"))
                val f = m.vfs.open(VfsPath.parse("/bin/prompt"), OpenOptions(read = true, write = true, create = true, truncate = true))
                m.vfs.write(f, bytes, 0, bytes.size)
                val input = m.lock.withLock { ByteStream(4096).also { m.kernel.objects.add(it) }.id }
                val output = m.lock.withLock { ByteStream(64 * 1024).also { m.kernel.objects.add(it) }.id }
                val stdio = StdioSpec(StdioSource.Object(input, HandleRights.READ), StdioSource.Object(output, HandleRights.WRITE), StdioSource.Closed)
                val pid = m.exec("/bin/prompt", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("prompt"), stdio = stdio))
                m.lock.withLock { m.kernel.initPid = pid }
                eventually(60_000, "入力を待たない") {
                    h.scheduler.tick()
                    m.stateOf(pid) == ProcessState.WAITING_EVENT && m.isAtWaitPoint
                }
                assertTrue(m.devices.attach(a, TestDevice()))
                assertTrue(m.devices.attach(b, TestDevice()))
                // b へのハンドルと、a・b への未実行の呼び出しを、プロセスの fd 表に入れる
                val (handleOfB, callToA, callToB) = m.lock.withLock {
                    val handle = DeviceHandle(b, "test:device")
                    FdTable(m.kernel.processes[pid]!!, m.kernel.objects, m.wakeSink).open(handle, HandleRights.READ)
                    val (_, toA) = m.openPendingDeviceCallLocked(pid, PendingDeviceCall.State.Queued(a, "d", emptyList()))
                    val (_, toB) = m.openPendingDeviceCallLocked(pid, PendingDeviceCall.State.Queued(b, "d", emptyList()))
                    Triple(handle.id, toA.id, toB.id)
                }
                val identity = store.assignNewId(owner)!!
                val written = store.write(identity, owner, m.captureSnapshot(CapturePolicy.SKIP_IF_TRANSIENT)!!).identity
                m.close()
                Saved(written, handleOfB, callToA, callToB)
            }

            // 戻す先: 上限は1
            val m = h.machine(fuelPerTick = 10_000_000, maxDevices = 1)
            val outcomes = Collections.synchronizedList(ArrayList<RestoreOutcome>())
            val errors = Collections.synchronizedList(ArrayList<Throwable>())
            val attached = Collections.synchronizedList(ArrayList<Boolean>())
            val dispatched = Collections.synchronizedList(ArrayList<Int>())
            val listener = RestoreListener { machine, outcome, _ ->
                try {
                    if (outcome == RestoreOutcome.RESTORED) {
                        // ComputerCase.startRestore と同じ順: デバイスをつないでから、未実行の呼び出しを回し直す
                        attached += machine.devices.attach(a, TestDevice())
                        attached += machine.devices.attach(b, TestDevice())
                        dispatched += machine.redispatchRestoredDeviceCalls()
                    }
                } catch (t: Throwable) {
                    errors += t
                }
                outcomes += outcome
            }
            val boot = BootConfig.builder().addBootDisk("none").build()
            assertTrue(m.restore(RestoreRequest(RestoreSource(store, saved.identity.id, saved.identity.generation), boot, listener)))
            eventually(60_000, "復元が終わらない") {
                h.scheduler.tick()
                outcomes.isNotEmpty()
            }
            errors.firstOrNull()?.let { throw AssertionError("復元の知らせの中で失敗した", it) }
            assertEquals(listOf(RestoreOutcome.RESTORED), outcomes.toList())
            assertEquals(1, m.devices.limit, "上限は保存せず、戻した先の設定で決まる")
            assertEquals(listOf(true, false), attached.toList(), "a だけつながり、b は断られる")
            assertEquals(2, dispatched.single(), "未実行の2つを回し直す")

            // b へのハンドルは戻る。b は外れているので、address で引いても出ない
            val handle = m.lock.withLock { m.kernel.objects[saved.handleOfB] as? DeviceHandle }
            assertNotNull(handle)
            assertEquals(b, handle!!.address)
            assertNull(m.devices.deviceAt(b))
            assertNull(m.devices.dispatcher.resolve(b, "test:device"))

            // b への呼び出しは detached、a への呼び出しはいつもどおり実行される
            eventually(10_000, "回し直した呼び出しが終わらない") {
                h.scheduler.tick()
                m.lock.withLock { (m.kernel.objects[saved.callToA] as PendingDeviceCall).result != null && (m.kernel.objects[saved.callToB] as PendingDeviceCall).result != null }
            }
            assertEquals(CallResult.Values(listOf(DeviceValue.of(42L))), m.lock.withLock { (m.kernel.objects[saved.callToA] as PendingDeviceCall).result })
            assertEquals(CallResult.Error(CallError.Detached), m.lock.withLock { (m.kernel.objects[saved.callToB] as PendingDeviceCall).result })
            assertFalse(m.devices.size > 1)
            m.close()
        }
    }
}
