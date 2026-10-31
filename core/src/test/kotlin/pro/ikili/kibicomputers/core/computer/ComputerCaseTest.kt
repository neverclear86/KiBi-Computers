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
import pro.ikili.kibicomputers.core.kernel.MachineConfig
import pro.ikili.kibicomputers.core.kernel.MachineState
import pro.ikili.kibicomputers.core.kernel.SchedulerConfig
import pro.ikili.kibicomputers.core.machine.MachineManager

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
        assertFalse(case.partsChanged(parts))
        assertEquals(ComputerStatus.RUNNING, case.status)

        parts.installed.remove(case.tier.firstSlot(PartKind.MEMORY))
        assertTrue(case.partsChanged(parts))
        assertEquals(ComputerStatus.ERROR, case.status)
        assertEquals(listOf(PartKind.MEMORY), case.missingParts)
        assertNull(manager["a"])
        // 止まっている間は何もしない
        assertFalse(case.partsChanged(Parts()))
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
        case.onMachineStateChanged(machine, MachineState.STOPPED)
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
}
