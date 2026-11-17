package pro.ikili.kibicomputers.core.computer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import pro.ikili.kibicomputers.api.device.Device
import pro.ikili.kibicomputers.api.device.DeviceMethods
import pro.ikili.kibicomputers.api.device.DeviceValue
import pro.ikili.kibicomputers.core.device.CallError
import pro.ikili.kibicomputers.core.device.CallResult
import pro.ikili.kibicomputers.core.device.PendingDeviceCall
import pro.ikili.kibicomputers.core.kernel.BootConfig
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.MachineConfig
import pro.ikili.kibicomputers.core.kernel.MachineEnvironment
import pro.ikili.kibicomputers.core.kernel.MachineState
import pro.ikili.kibicomputers.core.kernel.MemoryModuleStore
import pro.ikili.kibicomputers.core.kernel.SchedulerConfig
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
import pro.ikili.kibicomputers.core.machine.MachineErrorListener
import pro.ikili.kibicomputers.core.machine.MachineManager
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.core.persist.ColdBootCause
import pro.ikili.kibicomputers.core.persist.MachineIdIndex
import pro.ikili.kibicomputers.core.persist.MachineOwner
import pro.ikili.kibicomputers.core.persist.MachineSaveStore
import pro.ikili.kibicomputers.core.persist.RestoreListener
import pro.ikili.kibicomputers.core.persist.RestoreOutcome
import pro.ikili.kibicomputers.core.persist.RestoreSource
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.nio.file.Path
import java.time.Duration
import java.util.Collections
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.concurrent.withLock
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * [ComputerCase.startRestore] の [RestoreReady]（接続層のデバイスのつなぎ）が投げても、後の手順（配り直し・冷えた起動・`finished`・
 * 同期の起動）が走り、例外は [Machine] が「復元の受け手」として1回だけログに出すこと（#677）
 */
class ComputerCaseRestoreStepsTest {
    @TempDir
    lateinit var dir: Path

    private val limit = Duration.ofSeconds(120)
    private val config = MachineConfig.DEFAULT
    private val owner = MachineOwner("o", 0, 0, 0)

    private class Parts(vararg slots: Int) : InstalledParts {
        val installed = slots.toMutableSet()

        override fun isInstalled(slot: Int): Boolean = slot in installed
    }

    private fun required(tier: CaseTier) =
        Parts(*PartKind.entries.filter { it.required }.map { tier.firstSlot(it) }.toIntArray())

    /** `kibicomputers.machine` のログを拾う（HostFailureLogTest と同じやり方） */
    private class Captured : AutoCloseable {
        // JUL のロガーは弱参照で持たれるので、ここで持ったままにする
        private val logger: Logger = Logger.getLogger("kibicomputers.machine")
        private val oldLevel: Level? = logger.level
        val records: MutableList<LogRecord> = Collections.synchronizedList(ArrayList())
        private val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                records += record
            }

            override fun flush() = Unit

            override fun close() = Unit
        }

        init {
            logger.level = Level.ALL
            logger.addHandler(handler)
        }

        /** マシン [m] の「復元の受け手」の WARNING */
        fun restoreListenerWarnings(m: Machine): List<LogRecord> =
            records.toList().filter { it.level == Level.WARNING && it.message.startsWith("マシン ${m.id} の") && it.message.contains("復元の受け手") }

        override fun close() {
            logger.removeHandler(handler)
            logger.level = oldLevel
        }
    }

    /** [manager] を tick して [condition] を待つ */
    private fun tickUntil(manager: MachineManager<String>, message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError(message)
            manager.tick()
            Thread.sleep(1)
        }
    }

    /** /sbin/init を kibi-power-wasi（#306）の hold にしたディスク [id] を起動に使わせる（ComputerCaseTest と同じ） */
    private fun holdInitDisk(manager: MachineManager<String>, id: String) {
        val wasm = requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/kibi-power-wasi.wasm")) { "kibi-power-wasi.wasm がない" }
            .use { it.readBytes() }
        val fs = TestMemoryFileSystem()
        fs.createDirectory(VfsPath.parse("/sbin"))
        for ((path, bytes) in listOf(BootConfig.INIT_PATH to wasm, "/kpw-mode" to "hold".toByteArray())) {
            fs.createFile(VfsPath.parse(path)).also { fs.write(it, 0, bytes, 0, bytes.size) }
        }
        manager.scheduler.hooks.bootVolumes = BootVolumeOpener { disk -> if (disk == id) BootVolume(fs, readOnly = false) else null }
    }

    private fun newManager(): MachineManager<String> =
        MachineManager(SchedulerConfig.builder().maxConcurrentMachines(1).build(), MachineErrorListener { _, _ -> })

    /** 世代のない ID で復元を始める（戻せずに冷えた起動になる） */
    private fun startColdBootRestore(
        manager: MachineManager<String>,
        case: ComputerCase<String>,
        onReady: RestoreReady,
        finished: RestoreListener,
    ): Machine {
        holdInitDisk(manager, "disk-a")
        val bootable = BootConfig.builder().addBootDisk("disk-a").build()
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        val identity = store.assignNewId(owner)!!
        case.loadPoweredOn(true)
        assertTrue(case.beginRestore())
        case.startRestore(
            manager, "a", required(case.tier), true, config, MachineEnvironment.DEFAULT, bootable,
            RestoreSource(store, identity.id, 3), onReady, finished,
        )
        return case.machine!!
    }

    @Test
    fun `冷えた起動でonReadyが投げても、起動して保存の待ちに残らず、ログは受け手の種類で1件だけ出る`() = timeout(limit) {
        val manager = newManager()
        try {
            Captured().use { logs ->
                val case = ComputerCase<String>(CaseTier.BASIC)
                val finished = ArrayList<Pair<RestoreOutcome, ColdBootCause?>>()
                val machine = startColdBootRestore(
                    manager, case, RestoreReady { throw IllegalStateException("つなぎのバグ") },
                    RestoreListener { _, outcome, cause -> finished += outcome to cause },
                )
                tickUntil(manager, "知らせが来ない") { finished.isNotEmpty() }
                assertEquals(listOf(RestoreOutcome.COLD_BOOT to ColdBootCause.NO_GENERATION), finished)
                assertFalse(machine.coldBootAfterRestore(), "ケースがもう冷えた起動を始めた")
                tickUntil(manager, "起動しない: ${machine.state}") { machine.state == MachineState.RUNNING && !machine.isBooting }
                assertSame(machine, case.machine)
                assertFalse(case.isRestoring)
                assertTrue(case.poweredOn)
                assertEquals(ComputerStatus.RUNNING, case.status)
                // 冷えた起動待ちが残らないので、保存の待ちは READY に向かわない台として居座らず、そろえても電源を切らない
                assertFalse(case.isSettlingForSave, "保存の判定: ${case.saveReadiness()}")
                assertFalse(case.settleStoppedMachine())
                assertTrue(case.poweredOn)
                // 捨てずに投げ直すので Machine が受け手の種類で出し、ケースは別に出さない
                val warnings = logs.restoreListenerWarnings(machine)
                assertEquals(1, warnings.size, warnings.map { it.message }.toString())
                assertTrue(warnings.single().message.contains("つなぎのバグ"))
                case.turnOff()
            }
        } finally {
            manager.shutdown(10_000)
        }
    }

    @Test
    fun `onReadyとfinishedの両方が投げても、冷えた起動とfinishedは走り、例外は1つにまとめて出る`() = timeout(limit) {
        val manager = newManager()
        try {
            Captured().use { logs ->
                val case = ComputerCase<String>(CaseTier.BASIC)
                val finished = ArrayList<RestoreOutcome>()
                val machine = startColdBootRestore(
                    manager, case, RestoreReady { throw IllegalStateException("つなぎのバグ") },
                    RestoreListener { _, outcome, _ ->
                        finished += outcome
                        throw IllegalArgumentException("数え口のバグ")
                    },
                )
                tickUntil(manager, "知らせが来ない") { finished.isNotEmpty() }
                assertEquals(listOf(RestoreOutcome.COLD_BOOT), finished)
                assertFalse(machine.coldBootAfterRestore(), "ケースがもう冷えた起動を始めた")
                tickUntil(manager, "起動しない: ${machine.state}") { machine.state == MachineState.RUNNING && !machine.isBooting }
                val warning = logs.restoreListenerWarnings(machine).single()
                assertTrue(warning.message.contains("つなぎのバグ"), "最初の例外を投げ直す")
                val thrown = requireNotNull(warning.thrown) { "スケジューラで最初の1回はスタックトレースを付ける" }
                assertTrue(thrown.suppressed.any { it.message == "数え口のバグ" }, "後の例外は suppressed に残る")
                case.turnOff()
            }
        } finally {
            manager.shutdown(10_000)
        }
    }

    @Test
    fun `onReadyが致命的なエラーを投げたら、後の手順は走らせずにtickの外へ届く`() = timeout(limit) {
        // 致命的なエラーを投げた後のスケジューラは、ほかのテストと共有しない
        val manager = newManager()
        try {
            val case = ComputerCase<String>(CaseTier.BASIC)
            val finished = ArrayList<RestoreOutcome>()
            val machine = startColdBootRestore(
                manager, case, RestoreReady { throw OutOfMemoryError("つなぎ") },
                RestoreListener { _, outcome, _ -> finished += outcome },
            )
            var thrown: Throwable? = null
            tickUntil(manager, "致命的なエラーが tick の外へ届かない") {
                try {
                    manager.tick()
                } catch (e: OutOfMemoryError) {
                    thrown = e
                }
                thrown != null
            }
            assertEquals("つなぎ", thrown!!.message)
            assertTrue(finished.isEmpty(), "finished は呼ばない")
            assertFalse(machine.isBooting, "冷えた起動を始めない")
            assertFalse(machine.isRestoring, "復元の印は Machine が閉じる")
            case.turnOff()
        } finally {
            manager.shutdown(10_000)
        }
    }

    @Test
    fun `同期の起動でもonReadyが投げたら、起動してから呼び手へ投げる`() = timeout(limit) {
        val manager = newManager()
        try {
            val case = ComputerCase<String>(CaseTier.BASIC)
            case.loadPoweredOn(true)
            assertTrue(case.beginRestore())
            val boot = BootConfig.builder().addBootDisk("none").build()
            val e = assertThrows<IllegalStateException> {
                case.startRestore(
                    manager, "a", required(case.tier), true, config, MachineEnvironment.DEFAULT, boot, null,
                    RestoreReady { throw IllegalStateException("つなぎのバグ") },
                )
            }
            assertEquals("つなぎのバグ", e.message)
            val machine = case.machine!!
            assertTrue(machine.lastBootConfig === boot, "起動を受け付けた")
            assertTrue(case.poweredOn)
            assertFalse(case.isRestorePending)
            case.turnOff()
        } finally {
            manager.shutdown(10_000)
        }
    }

    private class TestDevice : Device {
        override val kind = "test:device"
        override val methods: DeviceMethods = DeviceMethods.builder().direct("d") { _, _ -> arrayOf(DeviceValue.of(42L)) }.build()
    }

    @Test
    fun `戻せたときにonReadyが投げても、積んだ呼び出しを配り直してdetachedで終え、finishedを呼ぶ`() = timeout(limit) {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        val address = "00000000-0000-4000-8000-000000000677"
        Captured().use { logs ->
            Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
                h.scheduler.hooks.bootVolumes = BootVolumeOpener { id -> if (id == "root") BootVolume(TestMemoryFileSystem(), readOnly = false) else null }
                // 保存するマシン: デバイスをつなぎ、まだ実行していない呼び出しを1つ積む（DeviceLimitRestoreTest と同じ手順）
                val (identity, callId) = run {
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
                    assertTrue(m.devices.attach(address, TestDevice()))
                    val call = m.lock.withLock { m.openPendingDeviceCallLocked(pid, PendingDeviceCall.State.Queued(address, "d", emptyList())).second.id }
                    val assigned = store.assignNewId(MachineOwner("minecraft:overworld", 0, 64, 0))!!
                    val written = store.write(assigned, MachineOwner("minecraft:overworld", 0, 64, 0), m.captureSnapshot(CapturePolicy.SKIP_IF_TRANSIENT)!!).identity
                    m.close()
                    written to call
                }

                // ケースで戻す。onReady はデバイスをつながずに投げる
                val manager = MachineManager<String>(h.scheduler, MachineErrorListener { _, _ -> })
                val case = ComputerCase<String>(CaseTier.BASIC)
                case.loadPoweredOn(true)
                assertTrue(case.beginRestore())
                val finished = ArrayList<RestoreOutcome>()
                case.startRestore(
                    manager, "a", required(case.tier), true, config, MachineEnvironment.DEFAULT, BootConfig.builder().addBootDisk("none").build(),
                    RestoreSource(store, identity.id, identity.generation),
                    RestoreReady { throw IllegalStateException("つなぎのバグ") },
                    RestoreListener { _, outcome, _ -> finished += outcome },
                )
                val machine = case.machine!!
                tickUntil(manager, "知らせが来ない") { finished.isNotEmpty() }
                assertEquals(listOf(RestoreOutcome.RESTORED), finished)
                assertFalse(case.isRestoring)
                assertTrue(case.poweredOn)
                // 配り直したので、つながっていないデバイスへの呼び出しは Queued のまま残らず detached で終わる
                tickUntil(manager, "配り直した呼び出しが終わらない") {
                    machine.lock.withLock { (machine.kernel.objects[callId] as PendingDeviceCall).result != null }
                }
                assertEquals(CallResult.Error(CallError.Detached), machine.lock.withLock { (machine.kernel.objects[callId] as PendingDeviceCall).result })
                val warnings = logs.restoreListenerWarnings(machine)
                assertEquals(1, warnings.size, warnings.map { it.message }.toString())
                case.turnOff()
            }
        }
    }
}
