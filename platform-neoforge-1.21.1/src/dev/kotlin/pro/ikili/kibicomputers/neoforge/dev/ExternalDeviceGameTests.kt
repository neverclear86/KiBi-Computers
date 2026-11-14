package pro.ikili.kibicomputers.neoforge.dev

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.GlobalPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.api.device.Device
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.computer.CaseTier
import pro.ikili.kibicomputers.core.computer.ComputerStatus
import pro.ikili.kibicomputers.core.computer.PartKind
import pro.ikili.kibicomputers.core.device.wiring.GuardedDevice
import pro.ikili.kibicomputers.neoforge.computer.CapabilityNeighbors
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
import pro.ikili.kibicomputers.neoforge.computer.DeviceAttachments
import pro.ikili.kibicomputers.neoforge.computer.DeviceWiring
import pro.ikili.kibicomputers.neoforge.computer.ProvisionalBoot
import pro.ikili.kibicomputers.neoforge.gametest.SmokeGameTests
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks

/**
 * 他 MOD のデバイスの能力（`KibiCapabilities.DEVICE`。#153、ADR 0153）の GameTest。他 MOD 役は [TestDevices]（樽とロードストーン）。
 * 能力の見張り・同一性での比べ・実体の排他・address の置き場所・例外の受け方・知らせの連鎖が止まること
 */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object ExternalDeviceGameTests {
    private val CENTER = BlockPos(1, 1, 1)

    private fun placeCase(helper: GameTestHelper, pos: BlockPos): CaseBlockEntity {
        helper.setBlock(pos, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get())
        val case = helper.getBlockEntity<CaseBlockEntity>(pos)
        case.fallbackBootDisk = ProvisionalBoot.TEST_BOOT_DISK
        for (kind in PartKind.entries.filter { it.required && it != PartKind.BOOT }) {
            case.parts.setStackInSlot(case.tier.firstSlot(kind), ItemStack(Items.IRON_INGOT))
        }
        return case
    }

    private fun powerOn(helper: GameTestHelper, case: CaseBlockEntity) {
        helper.assertTrue(case.pressPowerButton() == ComputerStatus.RUNNING, "case should be running")
    }

    /** [case] のマシンにつないでいる他 MOD のデバイス（生のもの。つないだ順） */
    private fun foreign(case: CaseBlockEntity): List<Device> {
        val devices = case.computer.machine?.devices ?: return emptyList()
        return devices.list().mapNotNull { (devices.deviceAt(it.address) as? GuardedDevice)?.inner }
    }

    private fun addressOf(case: CaseBlockEntity, device: Device): String? {
        val devices = case.computer.machine?.devices ?: return null
        return devices.list().firstOrNull { (devices.deviceAt(it.address) as? GuardedDevice)?.inner === device }?.address
    }

    private fun invalidate(helper: GameTestHelper, pos: BlockPos) = helper.level.invalidateCapabilities(helper.absolutePos(pos))

    private fun cleanUp(helper: GameTestHelper, cases: List<CaseBlockEntity>, vararg plain: BlockPos) {
        for (c in cases) {
            if (c.computer.status == ComputerStatus.RUNNING) c.pressPowerButton()
            helper.setBlock(helper.relativePos(c.blockPos), Blocks.AIR)
        }
        for (p in plain) {
            helper.setBlock(p, Blocks.AIR)
            TestDevices.forget(helper.absolutePos(p))
        }
    }

    /**
     * ブロックエンティティのある他 MOD のブロック（樽）: 電源を入れるとつながり、address はデータ添付に書かれ、
     * ブロックエンティティの保存と読み直しで残る。壊すと外れる（NeoForge がブロックエンティティの破壊で無効化する）
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun barrelDeviceAttachesKeepsAddressAndDetaches(helper: GameTestHelper) {
        val case = placeCase(helper, CENTER)
        val barrelPos = CENTER.east()
        helper.setBlock(barrelPos, Blocks.BARREL)
        val barrel = helper.getBlockEntity<BlockEntity>(barrelPos)
        val reactor = TestDevices.barrel(barrel)
        powerOn(helper, case)
        helper.assertTrue(foreign(case) == listOf(reactor), "the barrel's device should be attached: ${foreign(case)}")
        val address = addressOf(case, reactor)
        helper.assertTrue(address != null && address == barrel.getExistingDataOrNull(DeviceAttachments.DEVICE_ADDRESS), "the address should be in the attachment")
        val machine = case.computer.machine!!
        helper.assertTrue(machine.devices.list().any { it.kind == TestReactor.KIND }, "the kind should be the author's")
        // 保存して別のブロックエンティティに読み直しても、address は同じ
        val registries = helper.level.registryAccess()
        val tag = barrel.saveWithoutMetadata(registries)
        helper.assertTrue(tag.contains("neoforge:attachments"), "the address should be saved in the block entity: $tag")
        val copyPos = BlockPos(2, 2, 2)
        helper.setBlock(copyPos, Blocks.BARREL)
        val copy = helper.getBlockEntity<BlockEntity>(copyPos)
        copy.loadWithComponents(tag, registries)
        helper.assertTrue(copy.getExistingDataOrNull(DeviceAttachments.DEVICE_ADDRESS) == address, "the address should survive save and load")
        helper.setBlock(copyPos, Blocks.AIR)
        // 壊す
        helper.setBlock(barrelPos, Blocks.AIR)
        helper.succeedWhen {
            helper.assertTrue(foreign(case).isEmpty(), "the broken barrel should be detached")
            helper.assertTrue(reactor.log == listOf("attached", "detached"), "attach and detach should alternate: ${reactor.log}")
            helper.assertTrue(DeviceWiring.externalSlots.holderAt(DeviceWiring.keyOf(helper.level, helper.absolutePos(barrelPos))) == null, "the adapter should be gone")
            cleanUp(helper, listOf(case))
        }
    }

    /** 動いている間に置いた樽は、tick の終わりにつながる（NeoForge がブロックエンティティを置いたときに無効化する） */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun barrelPlacedWhileRunningAttaches(helper: GameTestHelper) {
        val case = placeCase(helper, CENTER)
        powerOn(helper, case)
        val barrelPos = CENTER.south()
        helper.setBlock(barrelPos, Blocks.BARREL)
        val reactor = TestDevices.barrel(helper.getBlockEntity<BlockEntity>(barrelPos))
        helper.succeedWhen {
            helper.assertTrue(foreign(case) == listOf(reactor), "the placed barrel should be attached: ${foreign(case)}")
            cleanUp(helper, listOf(case))
            helper.setBlock(barrelPos, Blocks.AIR)
        }
    }

    /** ブロックエンティティのないブロック（ロードストーン）の address は、ディメンションと座標から決まる（保存しない） */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun plainBlockDeviceUsesCoordinateAddress(helper: GameTestHelper) {
        val case = placeCase(helper, CENTER)
        val pos = CENTER.north()
        helper.setBlock(pos, Blocks.LODESTONE)
        val abs = helper.absolutePos(pos)
        TestDevices.forget(abs)
        val reactor = TestDevices.plainAt(abs)
        powerOn(helper, case)
        val expected = CapabilityNeighbors.coordinateAddress(GlobalPos.of(helper.level.dimension(), abs))
        helper.assertTrue(addressOf(case, reactor) == expected, "the address should come from the coordinates: ${addressOf(case, reactor)}")
        // 普通のブロックを壊したら、作者が無効化する
        helper.setBlock(pos, Blocks.AIR)
        invalidate(helper, pos)
        helper.succeedWhen {
            helper.assertTrue(foreign(case).isEmpty(), "the removed block should be detached")
            cleanUp(helper, listOf(case), pos)
        }
    }

    /** 同じ実体は1台のマシンにだけつながる。持っているケースの電源を切ると、隣のもう1台がつなぐ */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun foreignDeviceIsExclusiveAndHandedOver(helper: GameTestHelper) {
        val pos = CENTER
        helper.setBlock(pos, Blocks.LODESTONE)
        val abs = helper.absolutePos(pos)
        TestDevices.forget(abs)
        val reactor = TestDevices.plainAt(abs)
        val a = placeCase(helper, CENTER.west())
        val b = placeCase(helper, CENTER.east())
        powerOn(helper, a)
        powerOn(helper, b)
        helper.assertTrue(foreign(a) == listOf(reactor), "the first case should own the device")
        helper.assertTrue(foreign(b).isEmpty(), "the second case should not take it")
        a.pressPowerButton()
        helper.succeedWhen {
            helper.assertTrue(foreign(b) == listOf(reactor), "the second case should take the freed device")
            helper.assertTrue(reactor.log == listOf("attached", "detached", "attached"), "attach and detach should alternate: ${reactor.log}")
            cleanUp(helper, listOf(a, b), pos)
        }
    }

    /**
     * 実体を入れ替えて無効化すると、前の実体を外し（onDetached）、新しい実体をつなぐ（onAttached）。新しいメソッドが呼べる。
     * address と種類は変わらない
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun replacingTheInstanceReattaches(helper: GameTestHelper) {
        val case = placeCase(helper, CENTER)
        val pos = CENTER.above()
        helper.setBlock(pos, Blocks.LODESTONE)
        val abs = helper.absolutePos(pos)
        val first = TestReactor(TestReactor.KIND, "get-heat")
        val second = TestReactor(TestReactor.KIND, "get-power")
        var current: Device = first
        TestDevices.behave(abs) { current }
        powerOn(helper, case)
        val address = addressOf(case, first)
        helper.assertTrue(address != null, "the first instance should be attached")
        current = second
        invalidate(helper, pos)
        helper.succeedWhen {
            helper.assertTrue(foreign(case) == listOf(second), "the new instance should be attached: ${foreign(case)}")
            helper.assertTrue(first.log == listOf("attached", "detached") && second.log == listOf("attached"), "detach before attach")
            val devices = case.computer.machine!!.devices
            helper.assertTrue(addressOf(case, second) == address, "the address should stay")
            helper.assertTrue(devices.deviceAt(address!!)!!.methods.names.contains("get-power"), "the new methods should be callable")
            cleanUp(helper, listOf(case), pos)
        }
    }

    /** 同じ実体のまま無効化しても、つなぎ直さない（onAttached が増えない） */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun sameInstanceInvalidationKeepsTheConnection(helper: GameTestHelper) {
        val case = placeCase(helper, CENTER)
        val pos = CENTER.below()
        helper.setBlock(pos, Blocks.LODESTONE)
        val abs = helper.absolutePos(pos)
        TestDevices.forget(abs)
        val reactor = TestDevices.plainAt(abs)
        powerOn(helper, case)
        val before = case.wiring.rewires
        helper.runAfterDelay(1) { invalidate(helper, pos) }
        helper.runAfterDelay(3) { invalidate(helper, pos) }
        helper.runAfterDelay(6) {
            helper.assertTrue(case.wiring.rewires > before, "the invalidations should be processed")
            helper.assertTrue(reactor.attachCalls == 1 && reactor.log == listOf("attached"), "the same instance should not reattach: ${reactor.log}")
            helper.assertTrue(foreign(case) == listOf(reactor), "the device should stay attached")
            cleanUp(helper, listOf(case), pos)
            helper.succeed()
        }
    }

    /**
     * 毎回新しい実体を返す provider: 知らせがなければつなぎ直さない。知らせ1回につき、つなぎ直しは結線の数ほどで止まる
     * （2台が隣り合う形）
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 200)
    fun freshInstanceProviderSettles(helper: GameTestHelper) {
        val pos = CENTER
        helper.setBlock(pos, Blocks.LODESTONE)
        val abs = helper.absolutePos(pos)
        TestDevices.behave(abs) { TestReactor(TestReactor.KIND) }
        val a = placeCase(helper, CENTER.west())
        val b = placeCase(helper, CENTER.east())
        powerOn(helper, a)
        powerOn(helper, b)
        val guarded = DeviceWiring.guardedFailures
        var settled = 0
        helper.runAfterDelay(3) { settled = a.wiring.rewires + b.wiring.rewires }
        helper.runAfterDelay(10) {
            helper.assertTrue(a.wiring.rewires + b.wiring.rewires == settled, "no notification, no rewire")
            invalidate(helper, pos)
        }
        helper.runAfterDelay(20) {
            val after = a.wiring.rewires + b.wiring.rewires
            helper.assertTrue(after > settled && after - settled <= 6, "one notification should rewire a few times: ${after - settled}")
            settled = after
            helper.assertTrue(foreign(a).size + foreign(b).size == 1, "exactly one case should hold the device")
        }
        helper.runAfterDelay(30) {
            helper.assertTrue(a.wiring.rewires + b.wiring.rewires == settled, "the rewires should stop")
            helper.assertTrue(DeviceWiring.guardedFailures == guarded, "nothing should reach the flush guard")
            cleanUp(helper, listOf(a, b), pos)
            helper.succeed()
        }
    }

    /**
     * 無効化の後に provider が投げても、そのデバイスを外すだけで、ほかのデバイスはつながったまま、新しくもつながる。
     * flush の守りまでは届かない
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun throwingProviderDoesNotStopOtherDevices(helper: GameTestHelper) {
        val case = placeCase(helper, CENTER)
        val pos = CENTER.north()
        helper.setBlock(pos, Blocks.LODESTONE)
        val abs = helper.absolutePos(pos)
        TestDevices.forget(abs)
        val plain = TestDevices.plainAt(abs)
        val westPos = CENTER.west()
        helper.setBlock(westPos, Blocks.BARREL)
        val west = TestDevices.barrel(helper.getBlockEntity<BlockEntity>(westPos))
        powerOn(helper, case)
        helper.assertTrue(foreign(case).toSet() == setOf(plain, west), "both should be attached: ${foreign(case)}")
        val guarded = DeviceWiring.guardedFailures
        TestDevices.behave(abs) { throw IllegalStateException("provider boom") }
        invalidate(helper, pos)
        val eastPos = CENTER.east()
        helper.setBlock(eastPos, Blocks.BARREL)
        val east = TestDevices.barrel(helper.getBlockEntity<BlockEntity>(eastPos))
        helper.succeedWhen {
            helper.assertTrue(foreign(case).toSet() == setOf(west, east), "the others should stay and the new one attach: ${foreign(case)}")
            helper.assertTrue(case.wiring.capabilityFailures > 0, "the provider's exception should be caught")
            helper.assertTrue(DeviceWiring.guardedFailures == guarded, "nothing should reach the flush guard")
            helper.assertTrue(case.computer.status == ComputerStatus.RUNNING, "the machine should keep running")
            cleanUp(helper, listOf(case), pos)
            helper.setBlock(westPos, Blocks.AIR)
            helper.setBlock(eastPos, Blocks.AIR)
        }
    }

    /** いつも投げるデバイス（[kind] が不正、または onAttached が毎回投げる）に2台が隣り合っても、つなぎ直しは止まる */
    private fun alwaysFailingSettles(helper: GameTestHelper, reactor: TestReactor, expectedAttachCalls: Int) {
        val pos = CENTER
        helper.setBlock(pos, Blocks.LODESTONE)
        val abs = helper.absolutePos(pos)
        TestDevices.behave(abs) { reactor }
        val a = placeCase(helper, CENTER.west())
        val b = placeCase(helper, CENTER.east())
        val guarded = DeviceWiring.guardedFailures
        powerOn(helper, a)
        powerOn(helper, b)
        var settled = 0
        helper.runAfterDelay(5) { settled = a.wiring.rewires + b.wiring.rewires }
        helper.runAfterDelay(15) {
            helper.assertTrue(a.wiring.rewires + b.wiring.rewires == settled, "the rewires should stop")
            helper.assertTrue(foreign(a).isEmpty() && foreign(b).isEmpty(), "the failing device should not be attached")
            helper.assertTrue(a.wiring.failedCandidates == 1 && b.wiring.failedCandidates == 1, "each case should remember the failure once")
            helper.assertTrue(reactor.attachCalls == expectedAttachCalls, "each case should try once: ${reactor.attachCalls}")
            helper.assertTrue(DeviceWiring.guardedFailures == guarded, "nothing should reach the flush guard")
            cleanUp(helper, listOf(a, b), pos)
            helper.succeed()
        }
    }

    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun invalidKindBetweenTwoCasesSettles(helper: GameTestHelper) = alwaysFailingSettles(helper, TestReactor("MyMod:Reactor"), 0)

    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun alwaysThrowingAttachBetweenTwoCasesSettles(helper: GameTestHelper) =
        alwaysFailingSettles(helper, TestReactor(TestReactor.KIND).apply { attachFailures = -1 }, 2)

    /**
     * 1回目だけ onAttached で投げる: 投げたケースは同じ世代では試さず、隣のケースがつなぐ。隣が手放しても試さない。
     * 電源を入れ直すとつなぐ
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 200)
    fun firstAttachFailureHandsOverAndRetriesAfterPowerCycle(helper: GameTestHelper) {
        val pos = CENTER
        helper.setBlock(pos, Blocks.LODESTONE)
        val abs = helper.absolutePos(pos)
        val reactor = TestReactor(TestReactor.KIND).apply { attachFailures = 1 }
        TestDevices.behave(abs) { reactor }
        val a = placeCase(helper, CENTER.west())
        val b = placeCase(helper, CENTER.east())
        powerOn(helper, a)
        helper.assertTrue(foreign(a).isEmpty() && reactor.attachCalls == 1, "the first attach should fail")
        powerOn(helper, b)
        helper.assertTrue(foreign(b) == listOf(reactor), "the other case should attach it")
        b.pressPowerButton()
        helper.runAfterDelay(5) {
            helper.assertTrue(foreign(a).isEmpty(), "the failed case should not retry the same registration")
            helper.assertTrue(reactor.attachCalls == 2, "no more tries: ${reactor.attachCalls}")
            a.pressPowerButton()
            powerOn(helper, a)
            helper.assertTrue(foreign(a) == listOf(reactor), "a power cycle should retry")
            helper.assertTrue(reactor.log == listOf("attached", "detached", "attached"), "attach and detach should alternate: ${reactor.log}")
            cleanUp(helper, listOf(a, b), pos)
            helper.succeed()
        }
    }

    /**
     * 面ごとの出し分け: 西の面（ケース a）にだけ出すブロックを、東のケース b はつながない。a の最初のつなぎが失敗して持ち主がいない間も、
     * 電源を入れ直しても、b は拾わない（位置のアダプターは1つでも、つなげるのは出した面のケースだけ）
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun onlyTheOfferingFaceCanConnect(helper: GameTestHelper) {
        val pos = CENTER
        helper.setBlock(pos, Blocks.LODESTONE)
        val abs = helper.absolutePos(pos)
        val reactor = TestReactor(TestReactor.KIND).apply { attachFailures = 1 }
        TestDevices.behave(abs) { side -> if (side == Direction.WEST) reactor else null }
        val a = placeCase(helper, CENTER.west())
        val b = placeCase(helper, CENTER.east())
        powerOn(helper, a)
        helper.assertTrue(foreign(a).isEmpty() && reactor.attachCalls == 1, "a's first attach should fail")
        powerOn(helper, b)
        helper.assertTrue(b.wiring.capabilityAt(Direction.WEST) == null, "b's face should not see the device")
        helper.runAfterDelay(5) {
            helper.assertTrue(foreign(b).isEmpty(), "b should not take a device it is not offered: ${foreign(b)}")
            helper.assertTrue(reactor.attachCalls == 1, "nobody should try again: ${reactor.attachCalls}")
            cleanUp(helper, listOf(a, b), pos)
            helper.succeed()
        }
    }

    /**
     * 面ごとの出し分けの変更: a がつないだ後で、a の面だけ出すのをやめると a は外れ、出している b がつなぐ
     * （位置のアダプターは、b が出している間は残る）
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun withdrawingOneFaceDetachesThatCaseAndHandsOver(helper: GameTestHelper) {
        val pos = CENTER
        helper.setBlock(pos, Blocks.LODESTONE)
        val abs = helper.absolutePos(pos)
        val reactor = TestReactor(TestReactor.KIND)
        TestDevices.behave(abs) { reactor }
        val a = placeCase(helper, CENTER.west())
        val b = placeCase(helper, CENTER.east())
        powerOn(helper, a)
        powerOn(helper, b)
        helper.assertTrue(foreign(a) == listOf(reactor) && foreign(b).isEmpty(), "a should own the device")
        TestDevices.behave(abs) { side -> if (side == Direction.EAST) reactor else null }
        invalidate(helper, pos)
        helper.succeedWhen {
            helper.assertTrue(foreign(a).isEmpty(), "a withdrew its face, so it should detach: ${foreign(a)}")
            helper.assertTrue(foreign(b) == listOf(reactor), "b, which is offered the device, should take it: ${foreign(b)}")
            helper.assertTrue(reactor.log == listOf("attached", "detached", "attached"), "attach and detach should alternate: ${reactor.log}")
            cleanUp(helper, listOf(a, b), pos)
        }
    }
}
