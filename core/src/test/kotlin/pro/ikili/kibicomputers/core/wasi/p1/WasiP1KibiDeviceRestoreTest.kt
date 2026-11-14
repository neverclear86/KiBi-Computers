package pro.ikili.kibicomputers.core.wasi.p1

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import pro.ikili.kibicomputers.api.device.Device
import pro.ikili.kibicomputers.api.device.DeviceMethods
import pro.ikili.kibicomputers.api.device.DeviceValue
import pro.ikili.kibicomputers.core.device.PendingDeviceCall
import pro.ikili.kibicomputers.core.kernel.BootConfig
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.HostCall
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.MemoryModuleStore
import pro.ikili.kibicomputers.core.persist.QuiesceHandle
import pro.ikili.kibicomputers.core.kernel.boot.BootVolume
import pro.ikili.kibicomputers.core.kernel.boot.BootVolumeOpener
import pro.ikili.kibicomputers.core.kernel.eventually
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
import pro.ikili.kibicomputers.core.kernel.withLockValue
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.core.persist.ColdBootReason
import pro.ikili.kibicomputers.core.persist.MachineIdIndex
import pro.ikili.kibicomputers.core.persist.MachineIdentity
import pro.ikili.kibicomputers.core.persist.MachineOwner
import pro.ikili.kibicomputers.core.persist.MachineSaveStore
import pro.ikili.kibicomputers.core.persist.MachineSnapshot
import pro.ikili.kibicomputers.core.persist.RestoreListener
import pro.ikili.kibicomputers.core.persist.RestoreOutcome
import pro.ikili.kibicomputers.core.persist.RestoreRequest
import pro.ikili.kibicomputers.core.persist.RestoreSource
import pro.ikili.kibicomputers.core.persist.SnapshotSection
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.nio.ByteBuffer
import java.nio.file.Path
import java.time.Duration
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * 待機点型の p1 の `invoke`（#77、ADR 0077）が、メインスレッドのメソッドの結果を待っている間に保存して戻す。
 * 待つ間の状態は fd 表の `pending-call` だけなので、まだ実行していない（Queued）ものは戻した後に1回だけ実行し、
 * 実行中（後で返すと決めた）のものは `failed("interrupted")`、終わっていた（Done）ものはその結果を受け取る。どれもメソッドは1回だけ走る
 */
class WasiP1KibiDeviceRestoreTest {
    @TempDir
    lateinit var dir: Path

    private val owner = MachineOwner("minecraft:overworld", 0, 64, 0)
    private val address = "00000000-0000-4000-8000-0000000000b7"

    /** 保存するときの、待っている呼び出しの進み具合 */
    enum class Stage { QUEUED, RUNNING, DONE }

    /** メインスレッドのメソッド main-echo（その場で返す）と defer（後で返すと決めて、返さない）を持つデバイス。呼ばれた回数は [calls] */
    private class Dev(private val calls: AtomicInteger) : Device {
        override val kind = "test:dev"
        override val methods: DeviceMethods = DeviceMethods.builder()
            .mainThread("main-echo") { _, _ ->
                calls.incrementAndGet()
                arrayOf(DeviceValue.of(11L))
            }
            .mainThread("defer") { ctx, _ ->
                calls.incrementAndGet()
                ctx.defer()
                emptyArray()
            }
            .build()
    }

    private class Saved(val identity: MachineIdentity, val output: Int, val pid: Int, val callId: Int)

    private fun pendingCall(m: Machine, pid: Int): PendingDeviceCall? = m.lock.withLockValue {
        m.kernel.processes[pid]?.handles?.entries()?.values?.firstNotNullOfOrNull { m.kernel.objects[it.objectId] as? PendingDeviceCall }
    }

    private fun save(h: Harness, store: MachineSaveStore, stage: Stage, calls: AtomicInteger): Saved {
        h.scheduler.hooks.bootVolumes = BootVolumeOpener { id -> if (id == "root") BootVolume(TestMemoryFileSystem(), readOnly = false) else null }
        // 1 tick に1つだけホストの呼び出しを実行し、先に積んだ空の呼び出しの後ろに invoke の呼び出しを並ばせる（Queued のまま保存するため）
        val m = h.machine(fuelPerTick = 10_000_000, maxHostCallsPerTick = 1)
        val bytes = requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/kibi-device-wasi.wasm")).use { it.readBytes() }
        m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "disk:root")
        m.vfs.createDirectory(VfsPath.parse("/bin"))
        val f = m.vfs.open(VfsPath.parse("/bin/kd"), OpenOptions(read = true, write = true, create = true, truncate = true))
        m.vfs.write(f, bytes, 0, bytes.size)
        assertTrue(m.devices.attach(address, Dev(calls)))
        val output = m.lock.withLockValue { ByteStream(64 * 1024).also { m.kernel.objects.add(it) }.id }
        if (stage != Stage.RUNNING) repeat(BLOCKERS) { m.submitHostCall(0, HostCall { }) }
        val method = if (stage == Stage.RUNNING) "defer" else "main-echo"
        val stdio = StdioSpec(StdioSource.Closed, StdioSource.Object(output, HandleRights.WRITE), StdioSource.Closed)
        val pid = m.exec("/bin/kd", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("kd", "once-fd", address, method), stdio = stdio))
        m.lock.withLockValue { m.kernel.initPid = pid }
        val want = if (stage == Stage.RUNNING) PendingDeviceCall.State.Running::class else PendingDeviceCall.State.Queued::class
        eventually(60_000, "invoke で待たない") {
            h.scheduler.tick()
            m.stateOf(pid) == ProcessState.WAITING_EVENT && want.isInstance(pendingCall(m, pid)?.state) && m.isAtWaitPoint
        }
        var hold: QuiesceHandle? = null
        if (stage == Stage.DONE) {
            // 起こされても動かないようにしてから、呼び出しを終わらせる（RUNNABLE のまま保存する）
            val q = m.quiesce()
            hold = q
            eventually(60_000, "呼び出しが終わらない") {
                h.scheduler.tick()
                pendingCall(m, pid)?.state is PendingDeviceCall.State.Done && m.isAtWaitPoint
            }
            assertEquals(ProcessState.RUNNABLE, m.stateOf(pid))
        }
        val call = pendingCall(m, pid)
        assertNotNull(call)
        val identity = store.assignNewId(owner)!!
        val snapshot = m.captureSnapshot(CapturePolicy.SKIP_IF_TRANSIENT)
        assertNotNull(snapshot, "待っている間は保存できる")
        assertSectionLayout(snapshot!!.build(identity.id, identity.generation, "test"))
        val written = store.write(identity, owner, snapshot).identity
        hold?.close()
        m.close()
        return Saved(written, output, pid, call!!.id)
    }

    /**
     * invoke で待つマシンの保存データの節の形と版が、この機能の前と変わらない（#77 は保存形式を変えない。ADR 0077）:
     * `KERNEL`（版 1）の後に、プロセス1つ分の `PROCESS_ABI`（版 1。pid・p1 の層・層の版 2）だけが並ぶ。
     * 待っている `pending-call` は、これまでの fd 表とオブジェクト表の形のまま `KERNEL` 節に入る
     */
    private fun assertSectionLayout(snapshot: MachineSnapshot) {
        // 版は保存済みのデータが読めるかどうかの境目なので、定数の名前ではなく数そのものを固定する
        assertEquals(1, SnapshotSection.KERNEL_VERSION)
        assertEquals(1, SnapshotSection.PROCESS_ABI_VERSION)
        assertEquals(2, SnapshotSection.P1_LAYER_VERSION)
        val sections = snapshot.sections
        assertEquals(listOf(SnapshotSection.KERNEL, SnapshotSection.PROCESS_ABI), sections.map { it.kind }, sections.toString())
        assertEquals(listOf(1, 1), sections.map { it.version }, sections.toString())
        val abi = ByteBuffer.wrap(sections[1].payload)
        assertEquals(1, abi.getInt(), "pid")
        assertEquals(SnapshotSection.ABI_LAYER_P1, abi.getShort().toInt() and 0xffff, "ABI 層")
        assertEquals(SnapshotSection.P1_LAYER_VERSION, abi.getShort().toInt() and 0xffff, "層の版")
        assertEquals(emptyList<Int>(), snapshot.killedPids)
        assertEquals(ColdBootReason.NONE.code, snapshot.coldBootCode)
    }

    @ParameterizedTest
    @EnumSource(Stage::class)
    fun `invokeで待っている間に保存して戻しても、メソッドは1回だけ走り、実行中のものはinterrupted`(stage: Stage) = timeout(Duration.ofSeconds(120)) {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        val calls = AtomicInteger()
        Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
            val saved = save(h, store, stage, calls)
            val before = calls.get()
            assertEquals(if (stage == Stage.QUEUED) 0 else 1, before, "保存する前に走った回数")

            val m = h.machine(fuelPerTick = 10_000_000)
            val outcomes = Collections.synchronizedList(ArrayList<RestoreOutcome>())
            val errors = Collections.synchronizedList(ArrayList<Throwable>())
            val listener = RestoreListener { machine, outcome, _ ->
                try {
                    if (outcome == RestoreOutcome.RESTORED) {
                        // ComputerCase.startRestore と同じ順: デバイスをつないでから、まだ実行していない呼び出しを回し直す
                        machine.devices.attach(address, Dev(calls))
                        machine.redispatchRestoredDeviceCalls()
                    }
                } catch (t: Throwable) {
                    errors += t
                }
                outcomes += outcome
            }
            val boot = BootConfig.builder().addBootDisk("none").build()
            assertTrue(m.restore(RestoreRequest(RestoreSource(store, saved.identity.id, saved.identity.generation), boot, listener)))
            fun output(): String = m.lock.withLockValue {
                (m.kernel.objects[saved.output] as ByteStream).availableBytes().toString(Charsets.UTF_8)
            }
            eventually(60_000, "戻したプロセスが結果を書かない") {
                h.scheduler.tick()
                outcomes.isNotEmpty() && FD_AFTER.containsMatchIn(output())
            }
            errors.firstOrNull()?.let { throw AssertionError("復元の知らせの中で失敗した", it) }
            assertEquals(listOf(RestoreOutcome.RESTORED), outcomes.toList())
            val out = output()
            val expected = when (stage) {
                Stage.RUNNING -> "once err failed interrupted"
                else -> "once ok 1 i:11"
            }
            assertTrue("calling\n$expected\n" in out, out)
            // invoke の前後で、いちばん小さい空き fd が同じ: 待つ間に持った pending-call の fd が、復元の後に fd 表に残らない。
            // 復元では実行時の欄（pendingInvoke）がないので、起こした key から引いた fd を閉じる道を通る
            val fdBefore = Regex("fd-before (\\d+)\n").find(out)?.groupValues?.get(1)
            val fdAfter = FD_AFTER.find(out)?.groupValues?.get(1)
            assertNotNull(fdBefore, out)
            assertEquals(fdBefore, fdAfter, out)
            assertEquals(1, calls.get(), "メソッドは保存の前後を通して1回だけ走る")
            m.close()
        }
    }

    private companion object {
        /** 先に積む空のホストの呼び出しの数 */
        const val BLOCKERS = 200

        val FD_AFTER = Regex("fd-after (\\d+)\n")
    }
}
