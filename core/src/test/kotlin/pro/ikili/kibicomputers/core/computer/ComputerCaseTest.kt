package pro.ikili.kibicomputers.core.computer

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import pro.ikili.kibicomputers.core.kernel.BootConfig
import pro.ikili.kibicomputers.core.kernel.BootFailure
import pro.ikili.kibicomputers.core.kernel.DiskSnapshot
import pro.ikili.kibicomputers.core.kernel.ResolvedDisk
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.MachineConfig
import pro.ikili.kibicomputers.core.kernel.MachineEnvironment
import pro.ikili.kibicomputers.core.kernel.MachineState
import pro.ikili.kibicomputers.core.kernel.SchedulerConfig
import pro.ikili.kibicomputers.core.machine.MachineManager
import pro.ikili.kibicomputers.core.persist.ColdBootCause
import pro.ikili.kibicomputers.core.persist.MachineIdIndex
import pro.ikili.kibicomputers.core.persist.MachineOwner
import pro.ikili.kibicomputers.core.persist.MachineSaveStore
import pro.ikili.kibicomputers.core.persist.RestoreListener
import pro.ikili.kibicomputers.core.persist.RestoreOutcome
import pro.ikili.kibicomputers.core.persist.RestoreSource
import pro.ikili.kibicomputers.core.kernel.MachineHost
import pro.ikili.kibicomputers.core.kernel.boot.BootVolume
import pro.ikili.kibicomputers.core.kernel.boot.BootVolumeOpener
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** ケースのティアと電源・状態（#121） */
class ComputerCaseTest {
    private val manager = MachineManager<String>(SchedulerConfig.builder().maxConcurrentMachines(1).build())
    private val config = MachineConfig.DEFAULT

    @AfterEach
    fun tearDown() {
        manager.shutdown(10_000)
    }

    /** 挿さっているスロットの集まり */
    private class Parts(vararg slots: Int) : InstalledParts {
        val installed = slots.toMutableSet()

        override fun isInstalled(slot: Int): Boolean = slot in installed
    }

    /** 挿さっているスロットと、ディスクのスロットの識別子 */
    private class DiskParts(vararg slots: Int) : InstalledParts {
        val installed = slots.toMutableSet()
        val ids = HashMap<Int, String>()

        override fun isInstalled(slot: Int): Boolean = slot in installed

        override fun diskIdAt(slot: Int): String? = if (slot in installed) ids[slot] else null
    }

    /** [tier] の起動に要るスロットを1つずつ挿し、最初のディスクのスロットに識別子 [id] のディスクを挿したもの */
    private fun withDisk(tier: CaseTier, id: String = "disk-a") =
        DiskParts(*PartKind.entries.filter { it.required }.map { tier.firstSlot(it) }.toIntArray()).also {
            it.ids[tier.firstSlot(PartKind.STORAGE)] = id
        }

    /** [tier] の起動に要るスロットを1つずつ挿したもの */
    private fun required(tier: CaseTier) =
        Parts(*PartKind.entries.filter { it.required }.map { tier.firstSlot(it) }.toIntArray())

    @Test
    fun `ティアごとのスロットの数と並び`() {
        assertEquals(listOf(5, 8, 11), CaseTier.entries.map { it.slotCount })
        for (tier in CaseTier.entries) {
            assertEquals(1, tier.slotCount(PartKind.CPU))
            assertEquals(1, tier.slotCount(PartKind.BOOT))
            assertEquals(PartKind.entries.sumOf { tier.slotCount(it) }, tier.slotCount)
            // 種類の順にまとまっている
            assertEquals(tier.slots.sortedBy { it.ordinal }, tier.slots)
            assertEquals(PartKind.CPU, tier.kindOf(0))
            assertSame(tier, CaseTier.fromLevel(tier.level))
        }
        assertEquals(3, CaseTier.SUPERIOR.slotCount(PartKind.MEMORY))
        assertEquals(1, CaseTier.ADVANCED.firstSlot(PartKind.MEMORY))
        assertEquals(3, CaseTier.ADVANCED.firstSlot(PartKind.STORAGE))
        assertThrows<IllegalArgumentException> { CaseTier.fromLevel(4) }
        assertThrows<IndexOutOfBoundsException> { CaseTier.BASIC.kindOf(5) }
    }

    @Test
    fun `足りない部品は必須の種類だけを順に返す`() {
        val tier = CaseTier.ADVANCED
        assertEquals(listOf(PartKind.CPU, PartKind.MEMORY, PartKind.STORAGE, PartKind.BOOT), tier.missingRequired(Parts()))
        assertEquals(emptyList<PartKind>(), tier.missingRequired(required(tier)))
        // 2つ目のメモリ・ディスクのスロットでもよい。拡張カードはなくてよい
        val parts = Parts(0, 2, 4, 5)
        assertEquals(emptyList<PartKind>(), tier.missingRequired(parts))
        parts.installed.remove(5)
        assertEquals(listOf(PartKind.BOOT), tier.missingRequired(parts))
    }

    @Test
    fun `部品がそろえば電源でマシンが作られ、もう一度押すと止まる`() {
        val case = ComputerCase<String>(CaseTier.BASIC)
        assertEquals(ComputerStatus.OFF, case.status)
        assertEquals(ComputerStatus.RUNNING, case.pressPowerButton(manager, "a", required(case.tier), true, config))
        val machine = case.machine
        assertNotNull(machine)
        assertSame(machine, manager["a"])
        assertNull(case.error)
        // 動いている間の turnOn は何もしない
        assertEquals(ComputerStatus.RUNNING, case.turnOn(manager, "a", required(case.tier), true, config))
        assertSame(machine, manager["a"])

        assertEquals(ComputerStatus.OFF, case.pressPowerButton(manager, "a", required(case.tier), true, config))
        assertNull(case.machine)
        assertNull(manager["a"])
    }

    @Test
    fun `部品が足りなければエラーで、マシンを作らない`() {
        val case = ComputerCase<String>(CaseTier.BASIC)
        val parts = required(case.tier)
        parts.installed.remove(0)
        assertEquals(ComputerStatus.ERROR, case.turnOn(manager, "a", parts, true, config))
        assertEquals(ComputerError.MISSING_PARTS, case.error)
        assertEquals(listOf(PartKind.CPU), case.missingParts)
        assertEquals(0, manager.size)
        // 挿してからもう一度押すと動く
        parts.installed.add(0)
        assertEquals(ComputerStatus.RUNNING, case.pressPowerButton(manager, "a", parts, true, config))
        assertTrue(case.missingParts.isEmpty())
    }

    @Test
    fun `動いている間に必須の部品を抜くと止まる。拡張カードは止めない`() {
        val case = ComputerCase<String>(CaseTier.BASIC)
        val parts = required(case.tier)
        parts.installed.add(case.tier.firstSlot(PartKind.EXPANSION))
        case.turnOn(manager, "a", parts, true, config)
        parts.installed.remove(case.tier.firstSlot(PartKind.EXPANSION))
        assertFalse(case.partsChanged(parts, DiskSnapshot.EMPTY))
        assertEquals(ComputerStatus.RUNNING, case.status)

        parts.installed.remove(case.tier.firstSlot(PartKind.MEMORY))
        assertTrue(case.partsChanged(parts, DiskSnapshot.EMPTY))
        assertEquals(ComputerStatus.ERROR, case.status)
        assertEquals(listOf(PartKind.MEMORY), case.missingParts)
        assertNull(manager["a"])
        // 止まっている間は何もしない
        assertFalse(case.partsChanged(Parts(), DiskSnapshot.EMPTY))
        assertEquals(listOf(PartKind.MEMORY), case.missingParts)
    }

    @Test
    fun `電力がなければ起動せず、尽きたら止まる`() {
        val case = ComputerCase<String>(CaseTier.BASIC)
        assertEquals(ComputerStatus.NO_POWER, case.turnOn(manager, "a", required(case.tier), false, config))
        assertEquals(0, manager.size)
        assertFalse(case.powerLost())

        case.turnOn(manager, "a", required(case.tier), true, config)
        assertTrue(case.powerLost())
        assertEquals(ComputerStatus.NO_POWER, case.status)
        assertNull(manager["a"])
    }

    @Test
    fun `マシン管理がない・閉じているときは作れないエラー`() {
        val case = ComputerCase<String>(CaseTier.BASIC)
        assertEquals(ComputerStatus.ERROR, case.turnOn(null, "a", required(case.tier), true, config))
        assertEquals(ComputerError.UNAVAILABLE, case.error)
        manager.close()
        assertEquals(ComputerStatus.ERROR, case.turnOn(manager, "a", required(case.tier), true, config))
        assertEquals(ComputerError.UNAVAILABLE, case.error)
    }

    @Test
    fun `マシンがCRASHEDになるとエラーにして外す`() {
        val case = ComputerCase<String>(CaseTier.BASIC)
        case.turnOn(manager, "a", required(case.tier), true, config)
        val machine = case.machine!!
        // CRASHED でない通知は無視する
        case.onMachineStateChanged(machine, MachineState.STOPPING)
        assertEquals(ComputerStatus.RUNNING, case.status)
        case.onMachineStateChanged(machine, MachineState.CRASHED)
        assertEquals(ComputerStatus.ERROR, case.status)
        assertEquals(ComputerError.CRASHED, case.error)
        assertNull(manager["a"])
        // もう持っていないマシンの通知は無視する
        case.turnOn(manager, "a", required(case.tier), true, config)
        case.onMachineStateChanged(machine, MachineState.CRASHED)
        assertEquals(ComputerStatus.RUNNING, case.status)
    }

    @Test
    fun `アンロード・破壊でマシンを外してOFFにする`() {
        val case = ComputerCase<String>(CaseTier.BASIC)
        case.turnOn(manager, "a", required(case.tier), true, config)
        case.chunkUnloaded()
        assertEquals(ComputerStatus.OFF, case.status)
        assertNull(manager["a"])

        case.turnOn(manager, "a", required(case.tier), true, config)
        case.removed()
        assertEquals(ComputerStatus.OFF, case.status)
        assertNull(manager["a"])
    }

    /** 識別子 [ids] のディスクの表（中身は [dir] の下） */
    private fun snapshotOf(vararg ids: String): DiskSnapshot =
        DiskSnapshot.of(ids.associateWith { ResolvedDisk(dir.resolve(it), 1L shl 20, 64) })

    /** ディスク [ids] を挿して電源を入れ、init（kibi-power-wasi の hold）を disk-a から起動して動くまで待つ */
    private fun bootedCase(case: ComputerCase<String>, parts: InstalledParts, vararg ids: String, mode: String = "hold"): Machine {
        powerInitDisk("disk-a", mode)
        val env = MachineEnvironment.builder().disks(snapshotOf(*ids)).build()
        assertEquals(ComputerStatus.RUNNING, case.turnOn(manager, "a", parts, true, config, env))
        val machine = case.machine!!
        assertTrue(machine.boot(BootConfig.builder().addBootDisk("disk-a").build()))
        if (mode == "hold") tickUntil("起動しない") { machine.state == MachineState.RUNNING && !machine.isBooting }
        return machine
    }

    @Test
    fun `電源を入れたときのディスクの表がマシンに入り、挿し抜きで変わる`() {
        val case = ComputerCase<String>(CaseTier.ADVANCED)
        val parts = withDisk(case.tier)
        val env = MachineEnvironment.builder().disks(snapshotOf("disk-a", "disk-b")).build()
        assertEquals(ComputerStatus.RUNNING, case.turnOn(manager, "a", parts, true, config, env))
        val machine = case.machine!!
        assertEquals(setOf("disk-a", "disk-b"), machine.insertedDiskIds)
        // 起動していない（何もマウントしていない）マシンでは、どれを抜いても止めない
        assertFalse(case.partsChanged(parts, snapshotOf("disk-a")))
        assertEquals(setOf("disk-a"), machine.insertedDiskIds)
        assertFalse(case.partsChanged(parts, snapshotOf("disk-a", "disk-new")))
        assertEquals(setOf("disk-a", "disk-new"), machine.insertedDiskIds)
        assertEquals(ComputerStatus.RUNNING, case.status)
        assertSame(machine, case.machine)
    }

    @Test
    fun `動いている間に根のディスクを抜くと止まり、足りない部品はSTORAGE`() {
        val case = ComputerCase<String>(CaseTier.BASIC)
        val parts = withDisk(case.tier)
        bootedCase(case, parts, "disk-a")
        parts.installed.remove(case.tier.firstSlot(PartKind.STORAGE))
        assertTrue(case.partsChanged(parts, DiskSnapshot.EMPTY))
        assertEquals(ComputerStatus.ERROR, case.status)
        assertEquals(ComputerError.MISSING_PARTS, case.error)
        assertEquals(listOf(PartKind.STORAGE), case.missingParts)
        assertNull(case.machine)
        assertNull(manager["a"])
    }

    @Test
    fun `根でないディスクを抜いても、ディスクを足しても止めない`() {
        val case = ComputerCase<String>(CaseTier.ADVANCED)
        val parts = withDisk(case.tier)
        val machine = bootedCase(case, parts, "disk-a", "disk-b")
        assertFalse(case.partsChanged(parts, snapshotOf("disk-a")))
        assertFalse(case.partsChanged(parts, snapshotOf("disk-a", "disk-new")))
        assertEquals(ComputerStatus.RUNNING, case.status)
        assertSame(machine, case.machine)
        assertEquals(MachineState.RUNNING, machine.state)
    }

    @Test
    fun `ゲストが止めた後は電源が切れていて、根のディスクを抜いても何も起きない`() {
        val case = ComputerCase<String>(CaseTier.BASIC)
        val parts = withDisk(case.tier)
        bootedCase(case, parts, "disk-a", mode = "shutdown")
        tickUntil("止まらない") { case.status == ComputerStatus.OFF }
        assertNull(case.machine)
        assertFalse(case.partsChanged(parts, DiskSnapshot.EMPTY))
        assertEquals(ComputerStatus.OFF, case.status)
        assertEquals(emptyList<PartKind>(), case.missingParts)
    }

    @Test
    fun `CPUと根のディスクが同時に抜けると種類の順に並ぶ`() {
        val case = ComputerCase<String>(CaseTier.ADVANCED)
        val parts = withDisk(case.tier)
        val first = case.tier.firstSlot(PartKind.STORAGE)
        // 2つ目のディスクのスロットにも部品があり、STORAGE 自体は足りている
        parts.installed.add(first + 1)
        bootedCase(case, parts, "disk-a")
        parts.installed.remove(first)
        parts.installed.remove(case.tier.firstSlot(PartKind.CPU))
        assertTrue(case.partsChanged(parts, DiskSnapshot.EMPTY))
        assertEquals(listOf(PartKind.CPU, PartKind.STORAGE), case.missingParts)
    }

    @Test
    fun `ディスクの表が変わらなければ、部品だけを見る`() {
        val case = ComputerCase<String>(CaseTier.ADVANCED)
        val parts = required(case.tier)
        case.turnOn(manager, "a", parts, true, config)
        parts.installed.remove(case.tier.firstSlot(PartKind.STORAGE))
        parts.installed.add(case.tier.firstSlot(PartKind.STORAGE) + 1)
        assertFalse(case.partsChanged(parts, DiskSnapshot.EMPTY))
        assertEquals(ComputerStatus.RUNNING, case.status)
    }

    // ---- 電源の状態と復元（#85） ----

    @TempDir
    lateinit var dir: Path

    private val boot = BootConfig.builder().addBootDisk("none").build()

    /** [condition] がそろうまで manager を tick する */
    private fun tickUntil(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError(message)
            manager.tick()
            Thread.sleep(1)
        }
    }

    @Test
    fun `電源の状態は表のとおりに変わり、変わったら知らせる`() {
        var changes = 0
        val case = ComputerCase<String>(CaseTier.BASIC, null) { changes++ }
        val parts = required(case.tier)
        case.turnOn(manager, "a", parts, true, config)
        assertTrue(case.poweredOn)
        assertEquals(1, changes)
        // アンロードでは変えない
        case.chunkUnloaded()
        assertTrue(case.poweredOn)
        assertEquals(1, changes)
        case.turnOn(manager, "a", parts, true, config)
        case.turnOff()
        assertFalse(case.poweredOn)
        assertEquals(2, changes)
        // 部品を抜いた・電力が尽きた・CRASHED・ゲストが止めた
        case.turnOn(manager, "a", parts, true, config)
        parts.installed.remove(case.tier.firstSlot(PartKind.CPU))
        assertTrue(case.partsChanged(parts, DiskSnapshot.EMPTY))
        assertFalse(case.poweredOn)
        parts.installed.add(case.tier.firstSlot(PartKind.CPU))
        case.turnOn(manager, "a", parts, true, config)
        assertTrue(case.powerLost())
        assertFalse(case.poweredOn)
        case.turnOn(manager, "a", parts, true, config)
        case.onMachineStateChanged(case.machine!!, MachineState.CRASHED)
        assertFalse(case.poweredOn)
        case.turnOn(manager, "a", parts, true, config)
        case.onMachineStateChanged(case.machine!!, MachineState.STOPPED)
        assertFalse(case.poweredOn)
        // #640: ゲストが止めたら、電源ボタンで切ったときと同じく OFF にしてマシンを外す
        assertEquals(ComputerStatus.OFF, case.status)
        assertNull(case.machine)
        assertNull(manager["a"])
        // #641: 起動に失敗した
        case.turnOn(manager, "a", parts, true, config)
        assertTrue(case.poweredOn)
        case.host.onBootFailed(case.machine!!, BootFailure.NO_BOOT_DISK, "")
        assertFalse(case.poweredOn)
        assertEquals(ComputerStatus.ERROR, case.status)
        assertEquals(ComputerError.BOOT_FAILED, case.error)
        // 部品が足りなければ入らない。セーブデータから読むときは知らせない
        case.turnOff()
        val before = changes
        val other = ComputerCase<String>(CaseTier.BASIC, null) { changes++ }
        other.loadPoweredOn(true)
        assertTrue(other.poweredOn)
        assertEquals(before, changes)
        other.turnOn(manager, "b", Parts(), true, config)
        assertFalse(other.poweredOn)
        assertEquals(before + 1, changes)
    }

    @Test
    fun `電源が入っていたら復元の順番待ちになり、IDがなければつないでから冷えた起動する`() {
        val case = ComputerCase<String>(CaseTier.BASIC)
        assertFalse(case.beginRestore(), "電源が切れていれば待たない")
        case.loadPoweredOn(true)
        assertTrue(case.beginRestore())
        assertTrue(case.isRestorePending)
        assertEquals(ComputerStatus.RUNNING, case.status)
        assertNull(case.machine)
        val ready = ArrayList<Machine>()
        case.startRestore(manager, "a", required(case.tier), true, config, MachineEnvironment.DEFAULT, boot, null, RestoreReady { ready += it })
        assertFalse(case.isRestorePending)
        val machine = case.machine!!
        assertEquals(listOf(machine), ready)
        assertTrue(machine.lastBootConfig === boot, "起動を受け付けた")
        // 2回目は何もしない
        case.startRestore(manager, "a", required(case.tier), true, config, MachineEnvironment.DEFAULT, boot, null, RestoreReady { ready += it })
        assertEquals(1, ready.size)
    }

    @Test
    fun `復元で部品が足りなければエラーにして電源を切る`() {
        val case = ComputerCase<String>(CaseTier.BASIC)
        case.loadPoweredOn(true)
        case.beginRestore()
        case.startRestore(manager, "a", Parts(), true, config, MachineEnvironment.DEFAULT, boot, null, RestoreReady { error("呼ばない") })
        assertEquals(ComputerStatus.ERROR, case.status)
        assertFalse(case.poweredOn)
        assertNull(case.machine)
    }

    @Test
    fun `戻せなければ知らせの後につないで冷えた起動する`() {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        val identity = store.assignNewId(MachineOwner("o", 0, 0, 0))!!
        val case = ComputerCase<String>(CaseTier.BASIC)
        case.loadPoweredOn(true)
        case.beginRestore()
        val ready = ArrayList<Machine>()
        val finished = ArrayList<Pair<RestoreOutcome, ColdBootCause?>>()
        case.startRestore(
            manager, "a", required(case.tier), true, config, MachineEnvironment.DEFAULT, boot,
            RestoreSource(store, identity.id, 3), RestoreReady { m -> ready += m },
            RestoreListener { _, outcome, cause -> finished += outcome to cause },
        )
        val machine = case.machine!!
        assertTrue(case.isRestoring)
        assertFalse(machine.boot(boot), "復元の途中は起動しない")
        tickUntil("知らせが来ない") { finished.isNotEmpty() }
        assertEquals(listOf(RestoreOutcome.COLD_BOOT to ColdBootCause.NO_GENERATION), finished)
        assertEquals(listOf(machine), ready)
        assertFalse(case.isRestoring)
        assertTrue(case.poweredOn)
        assertFalse(machine.coldBootAfterRestore(), "ケースがもう始めた")
    }

    @Test
    fun `知らせが届く前に電源を切ったら、古いマシンにはつながない`() {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        val identity = store.assignNewId(MachineOwner("o", 0, 0, 0))!!
        val case = ComputerCase<String>(CaseTier.BASIC)
        case.loadPoweredOn(true)
        case.beginRestore()
        val ready = ArrayList<Machine>()
        val finished = ArrayList<RestoreOutcome>()
        case.startRestore(
            manager, "a", required(case.tier), true, config, MachineEnvironment.DEFAULT, boot,
            RestoreSource(store, identity.id, 3), RestoreReady { ready += it },
            RestoreListener { _, outcome, _ -> finished += outcome },
        )
        val machine = case.machine!!
        // 前段が終わって知らせを積んだ後でも、取り消しでも、配る前に切ったマシンにはつながない
        Thread.sleep(50)
        case.turnOff()
        tickUntil("知らせが来ない") { finished.isNotEmpty() }
        repeat(3) { manager.tick() }
        // 前段が先に終わっても（冷えた起動待ち）、取り消しが先でも、届くのは取り消しの1回だけ（#576）
        assertEquals(listOf(RestoreOutcome.CANCELLED), finished)
        assertTrue(ready.isEmpty(), "今のマシンでないのでつながない")
        assertFalse(machine.isBooting, "冷えた起動を始めない")
        assertFalse(machine.isRestoring)
        assertFalse(case.poweredOn)
    }

    @Test
    fun `復元の順番待ちの間に電源を切ると、始めない`() {
        val case = ComputerCase<String>(CaseTier.BASIC)
        case.loadPoweredOn(true)
        case.beginRestore()
        case.turnOff()
        assertFalse(case.isRestorePending)
        case.startRestore(manager, "a", required(case.tier), true, config, MachineEnvironment.DEFAULT, boot, null, RestoreReady { error("呼ばない") })
        assertNull(case.machine)
        assertEquals(ComputerStatus.OFF, case.status)
    }

    /**
     * /sbin/init を kibi-power-wasi（#306）にし、/kpw-mode に [mode] を置いたディスク [id] を起動に使わせる。
     * 戻り値は、/kpw-mode の今の中身を読む関数
     */
    private fun powerInitDisk(id: String, mode: String): () -> String {
        val wasm = requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/kibi-power-wasi.wasm")) { "kibi-power-wasi.wasm がない" }
            .use { it.readBytes() }
        val fs = TestMemoryFileSystem()
        fs.createDirectory(VfsPath.parse("/sbin"))
        val nodes = listOf(BootConfig.INIT_PATH to wasm, "/kpw-mode" to mode.toByteArray()).map { (path, bytes) ->
            fs.createFile(VfsPath.parse(path)).also { fs.write(it, 0, bytes, 0, bytes.size) }
        }
        manager.scheduler.hooks.bootVolumes = BootVolumeOpener { disk -> if (disk == id) BootVolume(fs, readOnly = false) else null }
        return {
            val buf = ByteArray(32)
            String(buf, 0, fs.read(nodes[1], 0, buf, 0, buf.size).coerceAtLeast(0))
        }
    }

    /** 状態の変化を記録する（ケースの events） */
    private class States : MachineHost {
        val states: MutableList<MachineState> = Collections.synchronizedList(ArrayList())
        val reboots = AtomicInteger()

        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) {
            states += state
        }

        override fun onRebootRequested(machine: Machine) {
            reboots.incrementAndGet()
            super.onRebootRequested(machine)
        }
    }

    /** 起動の失敗の中継を記録する（ケースの events）。届いたときのケースのマシンも覚える */
    private class BootFailures(private val case: () -> ComputerCase<String>) : MachineHost {
        val failures: MutableList<BootFailure> = Collections.synchronizedList(ArrayList())
        val machineAtFailure: MutableList<Machine?> = Collections.synchronizedList(ArrayList())
        val statusAtFailure: MutableList<ComputerStatus> = Collections.synchronizedList(ArrayList())

        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit

        override fun onBootFailed(machine: Machine, failure: BootFailure, detail: String) {
            machineAtFailure += case().machine
            statusAtFailure += case().status
            failures += failure
        }
    }

    @Test
    fun `起動に失敗したらエラーにしてマシンを外し、電源ボタン1回でまた起動する`() {
        lateinit var case: ComputerCase<String>
        val events = BootFailures { case }
        case = ComputerCase(CaseTier.BASIC, events)
        assertEquals(ComputerStatus.RUNNING, case.turnOn(manager, "a", required(case.tier), true, config))
        val machine = case.machine!!
        // 接続層と同じく、電源を入れた後に起動する（起動部品がないので失敗する）
        assertTrue(machine.boot(boot))
        tickUntil("起動の失敗が届かない: ${case.status}") { events.failures.isNotEmpty() }
        assertEquals(ComputerStatus.ERROR, case.status)
        assertEquals(ComputerError.BOOT_FAILED, case.error)
        assertTrue(case.missingParts.isEmpty())
        assertFalse(case.poweredOn)
        assertNull(case.machine)
        assertNull(manager["a"])
        // ケースの処理の後に中継する（接続層は「マシンが外れた」を見て結線を外す）
        assertEquals(listOf<Machine?>(null), events.machineAtFailure.toList())
        assertEquals(listOf(ComputerStatus.ERROR), events.statusAtFailure.toList())
        // 電源ボタンの1回目で入る（空振りしない）
        assertEquals(ComputerStatus.RUNNING, case.pressPowerButton(manager, "a", required(case.tier), true, config))
        assertTrue(case.poweredOn)
        assertNull(case.error)
        val next = case.machine!!
        assertTrue(next !== machine)
        assertSame(next, manager["a"])
    }

    @Test
    fun `古いマシンや動いているマシンの起動の失敗は無視する`() {
        powerInitDisk("disk-a", "hold")
        val case = ComputerCase<String>(CaseTier.BASIC)
        case.turnOn(manager, "a", withDisk(case.tier), true, config)
        val old = case.machine!!
        case.turnOff()
        case.turnOn(manager, "a", withDisk(case.tier), true, config)
        case.host.onBootFailed(old, BootFailure.NO_BOOT_DISK, "")
        assertEquals(ComputerStatus.RUNNING, case.status)
        assertTrue(case.poweredOn)
        // 知らせが届くまでに起動し直して動いていたら、その起動を巻き込まない
        val machine = case.machine!!
        assertTrue(machine.boot(BootConfig.builder().addBootDisk("disk-a").build()))
        tickUntil("動かない: ${machine.state}") { machine.state == MachineState.RUNNING && !machine.isBooting }
        case.host.onBootFailed(machine, BootFailure.NO_BOOT_DISK, "")
        assertEquals(ComputerStatus.RUNNING, case.status)
        assertSame(machine, case.machine)
        assertTrue(case.poweredOn)
    }

    @Test
    fun `再起動の後に起動に失敗してもエラーにして外す`() {
        powerInitDisk("disk-a", "reboot-once")
        // 1回目の起動の後はディスクを開けなくする（再起動の起動は NO_BOOT_DISK で失敗する）
        val opener = manager.scheduler.hooks.bootVolumes!!
        val opened = AtomicInteger()
        manager.scheduler.hooks.bootVolumes = BootVolumeOpener { disk -> if (opened.getAndIncrement() == 0) opener.open(disk) else null }
        lateinit var case: ComputerCase<String>
        val events = BootFailures { case }
        case = ComputerCase(CaseTier.BASIC, events)
        case.turnOn(manager, "a", withDisk(case.tier), true, config)
        val machine = case.machine!!
        assertTrue(machine.boot(BootConfig.builder().addBootDisk("disk-a").build()))
        tickUntil("起動の失敗が届かない: ${case.status}") { events.failures.isNotEmpty() }
        assertEquals(listOf(BootFailure.NO_BOOT_DISK), events.failures.toList())
        assertEquals(ComputerStatus.ERROR, case.status)
        assertEquals(ComputerError.BOOT_FAILED, case.error)
        assertFalse(case.poweredOn)
        assertNull(case.machine)
        assertNull(manager["a"])
    }

    @Test
    fun `ゲストがkibi_computer_powerで止めたら電源が切れる`() {
        powerInitDisk("disk-a", "shutdown")
        val events = States()
        val case = ComputerCase<String>(CaseTier.BASIC, events)
        assertEquals(ComputerStatus.RUNNING, case.turnOn(manager, "a", withDisk(case.tier), true, config))
        assertTrue(case.poweredOn)
        val machine = case.machine!!
        // 接続層と同じく、電源を入れた後に起動する
        assertTrue(machine.boot(BootConfig.builder().addBootDisk("disk-a").build()))
        tickUntil("止まらない: ${events.states}") { machine.state == MachineState.STOPPED && !case.poweredOn }
        assertEquals(0, events.reboots.get())
        assertEquals(listOf(MachineState.RUNNING, MachineState.STOPPING, MachineState.STOPPED), events.states.toList())
        // #640: 状態の表示も OFF になり、マシンは外れる
        assertEquals(ComputerStatus.OFF, case.status)
        assertNull(case.machine)
        assertNull(manager["a"])
        // 電源ボタンを1回押せば、また起動できる
        assertEquals(ComputerStatus.RUNNING, case.turnOn(manager, "a", withDisk(case.tier), true, config))
        assertTrue(case.poweredOn)
        val next = case.machine!!
        assertTrue(next !== machine)
        assertSame(next, manager["a"])
    }

    @Test
    fun `initが自分で終わったら電源が切れて表示もOFFになる`() {
        // 知らないモードでは init が 2 で終わる
        powerInitDisk("disk-a", "exit")
        val events = States()
        val case = ComputerCase<String>(CaseTier.BASIC, events)
        assertEquals(ComputerStatus.RUNNING, case.turnOn(manager, "a", withDisk(case.tier), true, config))
        val machine = case.machine!!
        assertTrue(machine.boot(BootConfig.builder().addBootDisk("disk-a").build()))
        tickUntil("止まらない: ${events.states}") { case.status == ComputerStatus.OFF }
        assertFalse(case.poweredOn)
        assertNull(case.machine)
        assertNull(manager["a"])
        assertEquals(0, events.reboots.get())
    }

    @Test
    fun `ゲストがkibi_computer_powerで再起動しても電源は切れない`() {
        val modeFile = powerInitDisk("disk-a", "reboot-once")
        val events = States()
        val case = ComputerCase<String>(CaseTier.BASIC, events)
        assertEquals(ComputerStatus.RUNNING, case.turnOn(manager, "a", withDisk(case.tier), true, config))
        val machine = case.machine!!
        assertTrue(machine.boot(BootConfig.builder().addBootDisk("disk-a").build()))
        tickUntil("再起動しない: ${events.states}") {
            events.reboots.get() == 1 && events.states.size >= 4 && !machine.isBooting && !machine.isRebootPending
        }
        assertTrue(case.poweredOn, "再起動の途中の STOPPED では電源を切らない")
        assertEquals(ComputerStatus.RUNNING, case.status, "再起動では表示も変えない")
        assertSame(machine, case.machine)
        assertEquals(listOf(MachineState.RUNNING, MachineState.STOPPING, MachineState.STOPPED, MachineState.RUNNING), events.states.toList())
        // 2回目の起動の init は hold で眠り続ける
        assertEquals("hold", modeFile())
    }
}
