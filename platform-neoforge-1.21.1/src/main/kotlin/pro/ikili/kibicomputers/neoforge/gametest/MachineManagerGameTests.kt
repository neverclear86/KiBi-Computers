package pro.ikili.kibicomputers.neoforge.gametest

import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.kernel.MachineConfig
import pro.ikili.kibicomputers.core.kernel.MachineHost
import pro.ikili.kibicomputers.core.kernel.MachineState
import pro.ikili.kibicomputers.core.machine.MachineSlot
import pro.ikili.kibicomputers.neoforge.machine.MachineKey
import pro.ikili.kibicomputers.neoforge.machine.ServerMachines

/** サーバー側のマシン管理（#120）の GameTest。 */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object MachineManagerGameTests {
    private val POS = BlockPos(1, 1, 1)
    private val HOST = MachineHost { _, _, _ -> }

    /** サーバーが動いている間はマシン管理があり、毎 tick 進む。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun managerTicksWithServer(helper: GameTestHelper) {
        val manager = ServerMachines.current
        helper.assertTrue(manager != null, "machine manager should exist while the server runs")
        val start = manager!!.ticks
        helper.runAfterDelay(3) {
            helper.assertTrue(manager.ticks >= start + 3, "manager should tick every server tick (${manager.ticks - start} ticks)")
            helper.succeed()
        }
    }

    /** ブロックエンティティの読み込み・アンロード・破壊に合わせて、マシンが一覧に入り、外れる。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun slotFollowsBlockEntityLifecycle(helper: GameTestHelper) {
        val manager = ServerMachines.current!!
        val absolute = helper.absolutePos(POS)
        val key = MachineKey.of(helper.level, absolute.mutable())
        helper.assertTrue(key == MachineKey(helper.level.dimension(), absolute), "key should not depend on mutable positions")
        val slot = MachineSlot<MachineKey>()

        val loaded = slot.load(manager, key, HOST, MachineConfig.DEFAULT)
        helper.assertTrue(loaded != null && manager[key] === loaded, "loaded machine should be listed")
        helper.assertTrue(loaded!!.state == MachineState.STOPPED, "new machine should not run yet")
        helper.assertTrue(slot.chunkUnloaded(), "chunk unload should release the machine")
        helper.assertTrue(!slot.removed(), "setRemoved after chunk unload should do nothing")
        helper.assertTrue(manager[key] == null, "unloaded machine should not be listed")

        slot.load(manager, key, HOST, MachineConfig.DEFAULT)
        helper.assertTrue(slot.removed(), "removal should release the machine")
        helper.assertTrue(manager[key] == null, "removed machine should not be listed")
        helper.succeed()
    }
}
