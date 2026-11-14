package pro.ikili.kibicomputers.neoforge.gametest

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.computer.CaseTier
import pro.ikili.kibicomputers.core.computer.ComputerStatus
import pro.ikili.kibicomputers.core.computer.PartKind
import pro.ikili.kibicomputers.core.device.DeviceInfo
import pro.ikili.kibicomputers.core.device.DeviceKind
import pro.ikili.kibicomputers.core.display.ScreenTier
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.MachineConfig
import pro.ikili.kibicomputers.core.kernel.MachineState
import pro.ikili.kibicomputers.core.machine.MachineReleaseReason
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
import pro.ikili.kibicomputers.neoforge.computer.CaseWiring
import pro.ikili.kibicomputers.neoforge.computer.ProvisionalBoot
import pro.ikili.kibicomputers.neoforge.display.ScreenBlock
import pro.ikili.kibicomputers.neoforge.display.ScreenBlockEntity
import pro.ikili.kibicomputers.neoforge.input.KeyboardBlockEntity
import pro.ikili.kibicomputers.neoforge.input.KeyboardInput
import pro.ikili.kibicomputers.neoforge.input.KeyboardSessions
import pro.ikili.kibicomputers.neoforge.machine.MachineKey
import pro.ikili.kibicomputers.neoforge.machine.ServerMachines
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks
import java.util.UUID

/**
 * 隣接デバイスの検出と接続（#129、ADR 0129）の GameTest: 登録表から引き直すこと、排他と空いたら次へ、address の振り直し、
 * 入力 GUI に出す画面。起動を待たない（電源を入れた直後から、デバイスはつながる）
 */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object DeviceWiringGameTests {
    private val CASE = BlockPos(1, 1, 1)
    private val SCREEN = BlockPos(1, 2, 1)

    // 画面の正面の前（ケースの隣ではない。画面を通してつながる）
    private val KEYBOARD = BlockPos(1, 2, 2)

    private fun placeCase(helper: GameTestHelper, pos: BlockPos): CaseBlockEntity {
        helper.setBlock(pos, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get())
        val case = helper.getBlockEntity<CaseBlockEntity>(pos)
        case.fallbackBootDisk = ProvisionalBoot.TEST_BOOT_DISK
        for (kind in PartKind.entries.filter { it.required && it != PartKind.BOOT }) {
            case.parts.setStackInSlot(case.tier.firstSlot(kind), ItemStack(Items.IRON_INGOT))
        }
        return case
    }

    private fun placeScreen(helper: GameTestHelper, pos: BlockPos): ScreenBlockEntity {
        helper.setBlock(
            pos,
            KiBiBlocks.SCREENS.getValue(ScreenTier.MONOCHROME).get().defaultBlockState().setValue(ScreenBlock.FACING, Direction.SOUTH),
        )
        return helper.getBlockEntity(pos)
    }

    private fun placeKeyboard(helper: GameTestHelper, pos: BlockPos): KeyboardBlockEntity {
        helper.setBlock(pos, KiBiBlocks.KEYBOARD.get())
        return helper.getBlockEntity(pos)
    }

    private fun cleanUp(helper: GameTestHelper, vararg cases: CaseBlockEntity) {
        for (c in cases) {
            if (c.computer.status == ComputerStatus.RUNNING) c.pressPowerButton()
            helper.setBlock(helper.relativePos(c.blockPos), Blocks.AIR)
        }
    }

    /**
     * 画面のチャンクだけがアンロードされたら、マシンは止めずに画面（と、画面を通したキーボード）だけを外す。
     * 読み込み直すと、新しい世代の登録として tick の終わりにつなぎ直す（#129。前は隣のブロックの更新でしか気付かなかった）
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun screenChunkReloadReconnects(helper: GameTestHelper) {
        val case = placeCase(helper, CASE)
        val screen = placeScreen(helper, SCREEN)
        val keyboard = placeKeyboard(helper, KEYBOARD)
        helper.assertTrue(case.pressPowerButton() == ComputerStatus.RUNNING, "case should be running")
        val machine = case.computer.machine!!
        helper.assertTrue(case.wiring.connectedScreen === screen, "the screen should be connected")
        helper.assertTrue(case.wiring.connectedKeyboard === keyboard, "the keyboard in front of the screen should be connected")
        val before = case.wiring.generationOf(screen)!!
        // 画面のチャンクのアンロード（ブロックエンティティはそのまま）
        screen.onChunkUnloaded()
        val listed = machine.devices.list()
        helper.assertTrue(listed == listOf(DeviceInfo(case.speakerAddress, DeviceKind.SPEAKER)), "only the speaker should stay: $listed")
        helper.assertTrue(keyboard.owner == null, "the keyboard behind the unloaded screen should be freed")
        helper.assertTrue(machine.state != MachineState.CRASHED && case.computer.machine === machine, "the machine should keep running")
        // 読み込み直す。つなぐのは tick の終わり
        screen.onLoad()
        helper.assertTrue(case.wiring.connectedScreen == null, "the reloaded screen should be connected at the end of the tick")
        helper.succeedWhen {
            helper.assertTrue(case.wiring.connectedScreen === screen, "the reloaded screen should be connected again")
            helper.assertTrue(case.wiring.connectedKeyboard === keyboard, "the keyboard should be connected again")
            val after = case.wiring.generationOf(screen)!!
            helper.assertTrue(after > before, "the reloaded screen should be a new registration ($before -> $after)")
            helper.assertTrue(screen.registrationGeneration == after, "the screen should know its registration")
            cleanUp(helper, case)
        }
    }

    /**
     * キーボードのチャンクだけがアンロードされたら、マシンは止めずにそのキーボードだけを外す（隣のものも、画面を通した中継のものも）。
     * 読み込み直すと、新しい世代の登録として tick の終わりにつなぎ直す（#129）
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun keyboardChunkReloadReconnects(helper: GameTestHelper) {
        val case = placeCase(helper, CASE)
        val screen = placeScreen(helper, SCREEN)
        // 画面の前（中継）のキーボードと、ケースの隣（南）のキーボード
        val relayed = placeKeyboard(helper, KEYBOARD)
        val adjacent = placeKeyboard(helper, CASE.south())
        helper.assertTrue(case.pressPowerButton() == ComputerStatus.RUNNING, "case should be running")
        val machine = case.computer.machine!!
        helper.assertTrue(case.wiring.connectedScreen === screen, "the screen should be connected")
        helper.assertTrue(case.wiring.generationOf(relayed) != null, "the relayed keyboard should be connected")
        helper.assertTrue(case.wiring.generationOf(adjacent) != null, "the adjacent keyboard should be connected")
        val keyboards = listOf(relayed, adjacent)
        val before = keyboards.map { case.wiring.generationOf(it)!! }
        for (k in keyboards) {
            // キーボードのチャンクのアンロード（ブロックエンティティはそのまま）
            k.onChunkUnloaded()
            helper.assertTrue(case.wiring.generationOf(k) == null, "the unloaded keyboard should be detached")
            helper.assertTrue(k.owner == null && k.wiredTo == null, "the unloaded keyboard should be freed")
        }
        val listed = machine.devices.list()
        helper.assertTrue(
            listed == listOf(DeviceInfo(case.speakerAddress, DeviceKind.SPEAKER), DeviceInfo(screen.deviceAddress, DeviceKind.SCREEN)),
            "only the speaker and the screen should stay: $listed",
        )
        helper.assertTrue(machine.state != MachineState.CRASHED && case.computer.machine === machine, "the machine should keep running")
        helper.assertTrue(case.wiring.connectedScreen === screen, "the screen should stay connected")
        // 読み込み直す。つなぐのは tick の終わり
        for (k in keyboards) k.onLoad()
        helper.assertTrue(case.wiring.connected.none { it is KeyboardBlockEntity }, "the reloaded keyboards should be connected at the end of the tick")
        helper.succeedWhen {
            for ((i, k) in keyboards.withIndex()) {
                val after = case.wiring.generationOf(k)
                helper.assertTrue(after != null, "the reloaded keyboard $i should be connected again")
                helper.assertTrue(after!! > before[i], "the reloaded keyboard $i should be a new registration (${before[i]} -> $after)")
                helper.assertTrue(k.wiredTo === case.wiring && k.owner != null, "the reloaded keyboard $i should be wired to the case")
            }
            helper.assertTrue(machine.devices.list().size == 4, "all the devices should be attached: ${machine.devices.list()}")
            cleanUp(helper, case)
        }
    }

    /**
     * つなぎ直し（[CaseWiring.rewire]）は、ケースの今のマシンがこの結線のマシンのときだけ行う。ケースがもうマシンを持っていない
     * （今のマシンが `null`）ときは、この結線のマシンを今のマシンとみなさず、つながない
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun rewireSkipsWhenCaseHasNoMachine(helper: GameTestHelper) {
        placeCase(helper, CASE)
        val screen = placeScreen(helper, SCREEN)
        val manager = ServerMachines.current!!
        val key = MachineKey(helper.level.dimension(), helper.absolutePos(CASE.east()))
        var caseMachine: Machine? = null
        val wiring = CaseWiring { caseMachine }
        val machine = manager.attach(key, wiring, MachineConfig.DEFAULT)
        caseMachine = machine
        // 起動して（動いている印がないと、つなぎ直さない）つなぐ
        wiring.connect(
            helper.level,
            helper.absolutePos(CASE),
            machine,
            UUID.randomUUID().toString(),
            ProvisionalBoot.bootConfig(emptyList(), ProvisionalBoot.TEST_BOOT_DISK),
        )
        helper.assertTrue(wiring.connectedScreen === screen, "the screen should be connected")
        // 画面を外して、読み込み直す（つなぎ直しを頼まれる）
        screen.onChunkUnloaded()
        screen.onLoad()
        helper.assertTrue(wiring.connectedScreen == null, "the screen should be detached")
        caseMachine = null
        wiring.rewire()
        helper.assertTrue(wiring.connectedScreen == null, "a case without a machine should not reconnect")
        caseMachine = machine
        wiring.rewire()
        helper.assertTrue(wiring.connectedScreen === screen, "the case's machine should reconnect")
        wiring.disconnect()
        manager.release(key, machine, MachineReleaseReason.REMOVED)
        helper.setBlock(CASE, Blocks.AIR)
        helper.succeed()
    }

    /**
     * デバイスは1台のマシンにだけつながる。隣の2台目のケースは、1台目が持つ画面とキーボードを取らない。
     * 1台目の電源を切ると、2台目が tick の終わりにつなぐ（空いたら次へ。#129）
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun secondCaseTakesDevicesWhenFirstTurnsOff(helper: GameTestHelper) {
        val first = placeCase(helper, CASE)
        val screen = placeScreen(helper, SCREEN)
        val keyboard = placeKeyboard(helper, KEYBOARD)
        // 2台目: 画面の西隣
        val second = placeCase(helper, SCREEN.west())
        helper.assertTrue(first.pressPowerButton() == ComputerStatus.RUNNING, "first case should be running")
        helper.assertTrue(second.pressPowerButton() == ComputerStatus.RUNNING, "second case should be running")
        helper.assertTrue(screen.wiredTo === first.wiring && keyboard.wiredTo === first.wiring, "the first case should own the devices")
        helper.assertTrue(second.wiring.connected.isEmpty(), "the second case should take nothing: ${second.wiring.connected}")
        val secondMachine = second.computer.machine!!
        first.pressPowerButton()
        helper.assertTrue(screen.wiredTo == null && keyboard.wiredTo == null, "turning the first case off should free the devices")
        helper.succeedWhen {
            helper.assertTrue(second.wiring.connectedScreen === screen, "the second case should take the freed screen")
            helper.assertTrue(second.wiring.connectedKeyboard === keyboard, "the second case should take the keyboard through the screen")
            helper.assertTrue(screen.display === second.wiring.connectedScreenDevice?.display, "the screen should mirror the second machine")
            helper.assertTrue(
                secondMachine.devices.list().map { it.address } == listOf(second.speakerAddress, screen.deviceAddress, keyboard.deviceAddress),
                "the second machine should have the devices: ${secondMachine.devices.list()}",
            )
            cleanUp(helper, first, second)
        }
    }

    /**
     * データ付きで複製した画面（同じ address）を同じケースの隣に置くと、後からつないだほう（探す順で後の北）の address を
     * 振り直して、両方つなぐ。先の画面（上）の address とコンソールは変わらない（#129。ADR 0065・0066 の残り）
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun copiedScreenAddressIsReassigned(helper: GameTestHelper) {
        val case = placeCase(helper, CASE)
        val original = placeScreen(helper, SCREEN)
        val registries = helper.level.registryAccess()
        val address = original.deviceAddress
        val copy = placeScreen(helper, CASE.north())
        copy.loadWithComponents(original.saveWithoutMetadata(registries), registries)
        helper.assertTrue(copy.deviceAddress == address, "the copy should have the same address")
        helper.assertTrue(case.pressPowerButton() == ComputerStatus.RUNNING, "case should be running")
        val machine = case.computer.machine!!
        helper.assertTrue(original.deviceAddress == address, "the first screen should keep its address")
        helper.assertTrue(copy.deviceAddress != address, "the copy should get a new address")
        helper.assertTrue(
            machine.devices.list() == listOf(
                DeviceInfo(case.speakerAddress, DeviceKind.SPEAKER),
                DeviceInfo(address, DeviceKind.SCREEN),
                DeviceInfo(copy.deviceAddress, DeviceKind.SCREEN),
            ),
            "both screens should be attached: ${machine.devices.list()}",
        )
        helper.assertTrue(case.wiring.connectedScreen === original, "the first screen should stay the console")
        val tag = copy.saveWithoutMetadata(registries)
        helper.assertTrue(tag.getString(ScreenBlockEntity.DEVICE_ADDRESS_TAG) == copy.deviceAddress, "the new address should be saved: $tag")
        cleanUp(helper, case)
        helper.succeed()
    }

    /**
     * 入力 GUI に出す画面は、キーボードがつながったコンピューターのコンソールの画面（#129・#355）。
     * キーボードがマシンから外れたら（電源を切った）、開いている入力のセッションを閉じる（打ったものが次の持ち主に届かないように）
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun keyboardSessionFollowsTheConnectedComputer(helper: GameTestHelper) {
        val case = placeCase(helper, CASE)
        val screen = placeScreen(helper, SCREEN)
        // ケースの隣（南）のキーボード。画面はケースの上
        val keyboardPos = CASE.south()
        val keyboard = placeKeyboard(helper, keyboardPos)
        val abs = helper.absolutePos(keyboardPos)
        helper.assertTrue(KeyboardInput.connectedScreen(keyboard) == null, "no screen before the computer is on")
        helper.assertTrue(case.pressPowerButton() == ComputerStatus.RUNNING, "case should be running")
        helper.assertTrue(KeyboardInput.connectedScreen(keyboard) == screen.blockPos, "the GUI should show the computer's screen")
        val player = helper.makeMockServerPlayerInLevel()
        player.moveTo(Vec3.atCenterOf(abs.east()))
        helper.assertTrue(KeyboardSessions.open(player, abs, null) != null, "the session should open")
        case.pressPowerButton()
        helper.assertTrue(KeyboardSessions.sessionOf(player) == null, "disconnecting the keyboard should close the session")
        helper.assertTrue(KeyboardInput.connectedScreen(keyboard) == null, "no screen after the computer is off")
        KeyboardSessions.loggedOut(player)
        cleanUp(helper, case)
        helper.succeed()
    }
}
