package pro.ikili.kibicomputers.neoforge.gametest

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.computer.CaseTier
import pro.ikili.kibicomputers.core.computer.ComputerError
import pro.ikili.kibicomputers.core.computer.ComputerStatus
import pro.ikili.kibicomputers.core.computer.PartKind
import pro.ikili.kibicomputers.core.device.DeviceInfo
import pro.ikili.kibicomputers.core.device.DeviceKind
import pro.ikili.kibicomputers.core.device.SpeakerDevice
import pro.ikili.kibicomputers.core.display.ScreenTier
import pro.ikili.kibicomputers.core.display.TextBuffer
import pro.ikili.kibicomputers.core.input.Key
import pro.ikili.kibicomputers.core.input.KeyboardEvent
import pro.ikili.kibicomputers.core.input.KeyboardEventCodec
import pro.ikili.kibicomputers.core.kernel.BootConfig
import pro.ikili.kibicomputers.core.kernel.BootFailure
import pro.ikili.kibicomputers.core.kernel.MachineConfig
import pro.ikili.kibicomputers.core.kernel.MachineState
import pro.ikili.kibicomputers.core.machine.MachineReleaseReason
import pro.ikili.kibicomputers.core.persist.SaveReadiness
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
import pro.ikili.kibicomputers.neoforge.computer.CaseMenu
import pro.ikili.kibicomputers.neoforge.computer.CaseWiring
import pro.ikili.kibicomputers.neoforge.computer.ProvisionalBoot
import pro.ikili.kibicomputers.neoforge.display.ScreenBlock
import pro.ikili.kibicomputers.neoforge.display.ScreenBlockEntity
import pro.ikili.kibicomputers.neoforge.input.KeyboardBlockEntity
import pro.ikili.kibicomputers.neoforge.machine.MachineKey
import pro.ikili.kibicomputers.neoforge.machine.ServerMachines
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks
import java.util.UUID

/**
 * ケースと隣の画面・キーボードの結線（#129。もとは仮の結線 #307）の GameTest。キーボードは画面の正面の前に置く
 * （ケースの隣ではなく、画面を通してつながる。画面が外れたら一緒に外れる）
 */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object TestComputerGameTests {
    /**
     * 起動を待つ tick 数の上限。GameTest のサーバーは待たずに tick を進めるので、lua.wasm の読み込み
     * （待機点への変換・燃料の計測コードの挿入・検証。#329）と Lua の起動が終わる前に、普通の上限（100 tick）を使い切ってしまう。
     * 温まっていない JVM で複数のテストが同時に読み込むと、読み込みだけで数秒かかる
     */
    private const val BOOT_TIMEOUT_TICKS = 12_000

    private val CASE = BlockPos(1, 1, 1)
    private val SCREEN = BlockPos(1, 2, 1)
    private val KEYBOARD = BlockPos(1, 2, 2)

    /** 画面の各行の文字列（行末の空白を除く） */
    private fun lines(text: TextBuffer): List<String> = (0 until text.height).map { y ->
        val sb = StringBuilder()
        for (x in 0 until text.width) sb.appendCodePoint(text.getCodePoint(x, y))
        sb.toString().trimEnd()
    }

    private fun build(helper: GameTestHelper): Triple<CaseBlockEntity, ScreenBlockEntity, KeyboardBlockEntity> {
        helper.setBlock(CASE, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get())
        helper.setBlock(
            SCREEN,
            KiBiBlocks.SCREENS.getValue(ScreenTier.MONOCHROME).get().defaultBlockState().setValue(ScreenBlock.FACING, Direction.SOUTH),
        )
        helper.setBlock(KEYBOARD, KiBiBlocks.KEYBOARD.get())
        val case = helper.getBlockEntity<CaseBlockEntity>(CASE)
        // Lua の REPL で確かめる（ゲームの既定は標準 OS。#461）
        case.fallbackBootDisk = ProvisionalBoot.TEST_BOOT_DISK
        // 起動部品のほかの必須の部品（#122〜#124）はまだないので、種類を確かめずに入れる
        for (kind in PartKind.entries.filter { it.required && it != PartKind.BOOT }) {
            case.parts.setStackInSlot(case.tier.firstSlot(kind), ItemStack(Items.IRON_INGOT))
        }
        return Triple(case, helper.getBlockEntity(SCREEN), helper.getBlockEntity(KEYBOARD))
    }

    /** 電源を入れると同梱イメージの init（Lua の REPL。#315）が動いて画面に出し、キーボードで打った式の結果が出る。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun typedLuaExpressionIsEvaluatedOnScreen(helper: GameTestHelper) {
        val (case, screen, keyboard) = build(helper)
        helper.assertTrue(case.pressPowerButton() == ComputerStatus.RUNNING, "case should be running")
        helper.assertTrue(case.wiring.connectedScreen === screen, "the adjacent screen should be connected")
        helper.assertTrue(case.wiring.connectedKeyboard === keyboard, "the adjacent keyboard should be connected")
        helper.assertTrue(screen.display === case.wiring.connectedScreenDevice?.display, "the screen should mirror the machine's display device")
        val machine = case.computer.machine!!
        var typed = false
        helper.succeedWhen {
            val text = lines(screen.text)
            if (!typed) {
                helper.assertTrue(text.any { it == "KiBi test init: Lua REPL" }, "init banner should be shown: $text")
                helper.assertTrue(text.any { it == ">" }, "prompt should be shown: $text")
                val events = listOf(KeyboardEvent.Text("print(1+1)"), KeyboardEvent.KeyDown(Key.ENTER, 0, false))
                for (packet in KeyboardEventCodec.encode(events)) {
                    helper.assertTrue(keyboard.receive(UUID(0, 1), packet) == 2, "keyboard should accept the input")
                }
                typed = true
            }
            helper.assertTrue(text.any { it == "> print(1+1)" }, "typed line should be echoed by the TTY: $text")
            helper.assertTrue(text.any { it == "2" }, "Lua should print the result: $text")
            helper.assertTrue(machine.state == MachineState.RUNNING, "machine should keep running")
            case.pressPowerButton()
            helper.assertTrue(case.wiring.connectedKeyboard == null, "turning off should disconnect the keyboard")
            helper.setBlock(CASE, Blocks.AIR)
        }
    }

    /** キーボードで1行打つ（Enter まで） */
    private fun typeLine(helper: GameTestHelper, keyboard: KeyboardBlockEntity, line: String) {
        val events = listOf(KeyboardEvent.Text(line), KeyboardEvent.KeyDown(Key.ENTER, 0, false))
        for (packet in KeyboardEventCodec.encode(events)) {
            helper.assertTrue(keyboard.receive(UUID(0, 1), packet) == 2, "keyboard should accept the input")
        }
    }

    /** 電源が入ったまま画面を壊すと、出力がなくてもすぐに画面とキーボードから外す（#397）。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun breakingScreenWhileRunningDisconnectsAtOnce(helper: GameTestHelper) {
        val (case, screen, keyboard) = build(helper)
        helper.assertTrue(case.pressPowerButton() == ComputerStatus.RUNNING, "case should be running")
        val machine = case.computer.machine!!
        val console = case.wiring.connectedScreenDevice!!
        // 壊した直後に確かめたこと（succeedWhen は失敗すると次の tick にやり直すので、結果を取っておく）
        var problem: String? = null
        var broken = false
        helper.succeedWhen {
            if (!broken) {
                val text = lines(screen.text)
                helper.assertTrue(text.any { it == ">" }, "prompt should be shown: $text")
                helper.setBlock(SCREEN, Blocks.AIR)
                broken = true
                problem = when {
                    !screen.isRemoved -> "the screen block entity should be removed"
                    case.wiring.connectedScreen != null -> "breaking the screen should disconnect it at once"
                    case.wiring.connectedScreenDevice != null || machine.devices.primaryScreen != null -> "breaking the screen should detach the screen device"
                    case.wiring.connectedKeyboard != null || keyboard.owner != null -> "breaking the screen should free the keyboard"
                    console.backlogBytes != 0 -> "nothing should be kept for the removed screen: ${console.backlogBytes}"
                    else -> null
                }
            }
            helper.assertTrue(problem == null, problem ?: "")
            helper.assertTrue(machine.state == MachineState.RUNNING, "machine should keep running without a screen")
            case.pressPowerButton()
            helper.setBlock(CASE, Blocks.AIR)
        }
    }

    /**
     * 動いているマシンの画面を壊しても、ケースのスピーカーはつないだままで、ビープが届く。
     * 画面を置き直すと、次の tick に画面とキーボードをつなぎ直し、打った式の結果が新しい画面に出る（#531・#535）
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun breakingScreenKeepsSpeakerAndReplacingReconnects(helper: GameTestHelper) {
        val (case, screen, keyboard) = build(helper)
        helper.assertTrue(case.pressPowerButton() == ComputerStatus.RUNNING, "case should be running")
        val machine = case.computer.machine!!
        var broken = false
        var replaced: ScreenBlockEntity? = null
        var typed = false
        helper.succeedWhen {
            if (!broken) {
                helper.assertTrue(lines(screen.text).any { it == ">" }, "prompt should be shown")
                helper.setBlock(SCREEN, Blocks.AIR)
                broken = true
                val listed = machine.devices.list()
                helper.assertTrue(listed == listOf(DeviceInfo(case.speakerAddress, DeviceKind.SPEAKER)), "only the speaker should stay: $listed")
                val speaker = machine.devices.deviceAt(case.speakerAddress) as? SpeakerDevice
                helper.assertTrue(speaker?.beep(440, 100) == true, "the speaker should still accept a beep")
            }
            if (replaced == null) {
                helper.assertTrue(case.wiring.beeps.get() == 1L, "the beep should be delivered after the screen is gone")
                helper.setBlock(
                    SCREEN,
                    KiBiBlocks.SCREENS.getValue(ScreenTier.MONOCHROME).get().defaultBlockState().setValue(ScreenBlock.FACING, Direction.SOUTH),
                )
                replaced = helper.getBlockEntity<ScreenBlockEntity>(SCREEN)
                // 置いたブロックのデータを読み込んだ後につなぐので、置いた tick にはまだつながない（#535）
                helper.assertTrue(case.wiring.connectedScreen == null, "the placed screen should be reconnected on the next tick")
            }
            if (!typed) {
                val placed = replaced!!
                helper.assertTrue(case.wiring.connectedScreen === placed, "the placed screen should be reconnected")
                helper.assertTrue(case.wiring.connectedKeyboard === keyboard, "the keyboard should be reconnected")
                helper.assertTrue(machine.devices.primaryScreen === case.wiring.connectedScreenDevice, "the screen device should be the console again")
                val expected = listOf(
                    DeviceInfo(case.speakerAddress, DeviceKind.SPEAKER),
                    DeviceInfo(placed.deviceAddress, DeviceKind.SCREEN),
                    DeviceInfo(keyboard.deviceAddress, DeviceKind.KEYBOARD),
                )
                helper.assertTrue(machine.devices.list() == expected, "devices should be attached again: ${machine.devices.list()}")
                typeLine(helper, keyboard, "print(20+22)")
                typed = true
            }
            val text = locked(replaced!!) { lines(replaced!!.text) }
            helper.assertTrue(text.any { it == "42" }, "the result should be shown on the placed screen: $text")
            helper.assertTrue(machine.state == MachineState.RUNNING, "machine should keep running")
            case.pressPowerButton()
            helper.setBlock(CASE, Blocks.AIR)
        }
    }

    /**
     * プログラムが出力し続けている最中（プロセスのスレッドが表示デバイスに書いている間。#355）に画面を壊すと、すぐに外し、
     * 外れた画面（が映していた表示デバイス）にはもう書かない（#397）。
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun breakingScreenWhileFloodingStopsWritingToIt(helper: GameTestHelper) {
        val (case, screen, keyboard) = build(helper)
        helper.assertTrue(case.pressPowerButton() == ComputerStatus.RUNNING, "case should be running")
        val machine = case.computer.machine!!
        val console = case.wiring.connectedScreenDevice!!
        helper.assertTrue(screen.display === console.display, "the screen should mirror the machine's display device")
        var typed = false
        var broken = false
        var removedText: List<String> = emptyList()
        var ticksAfterBreak = 0
        helper.succeedWhen {
            if (!typed) {
                val text = locked(screen) { lines(screen.text) }
                helper.assertTrue(text.any { it == ">" }, "prompt should be shown: $text")
                typeLine(helper, keyboard, "while true do io.write((\"x\\n\"):rep(500)) end")
                typed = true
            }
            if (!broken) {
                helper.assertTrue(locked(screen) { lines(screen.text) }.any { it == "x" }, "the program should be flooding the screen")
                helper.setBlock(SCREEN, Blocks.AIR)
                broken = true
                helper.assertTrue(case.wiring.connectedScreen == null, "breaking the screen should disconnect it at once")
                removedText = locked(screen) { lines(screen.text) }
            }
            // 外れた画面には、もう書かない
            helper.assertTrue(locked(screen) { lines(screen.text) } == removedText, "the removed screen should not be written to")
            helper.assertTrue(machine.state == MachineState.RUNNING, "machine should keep running without a screen")
            helper.assertTrue(++ticksAfterBreak > 20, "watch the removed screen for a while")
            case.pressPowerButton()
            helper.setBlock(CASE, Blocks.AIR)
        }
    }

    // 表示デバイスはプロセスのスレッドも書くので、ロックを持って読む（#355）
    private fun <T> locked(screen: ScreenBlockEntity, block: () -> T): T {
        val lock = screen.display.lock
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /** 画面がなくても起動し、キーボードはつながない。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun bootsWithoutScreen(helper: GameTestHelper) {
        val (case, _, _) = build(helper)
        helper.setBlock(SCREEN, Blocks.AIR)
        helper.assertTrue(case.pressPowerButton() == ComputerStatus.RUNNING, "case should be running")
        helper.assertTrue(case.wiring.connectedScreen == null, "no screen should be connected")
        helper.assertTrue(case.wiring.connectedKeyboard == null, "keyboard is not connected without a screen")
        val machine = case.computer.machine!!
        helper.succeedWhen {
            helper.assertTrue(machine.state == MachineState.RUNNING, "init should start")
            helper.setBlock(CASE, Blocks.AIR)
        }
    }

    /**
     * 1つのキーボードは、最初につないだケースだけが持つ（#360）。隣に置いた2台目のケースには、入力が届かない。
     * 1台目が切れたあとに入れ直した2台目は、つなげる。
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun secondCaseDoesNotTakeTheKeyboard(helper: GameTestHelper) {
        val (first, _, keyboard) = build(helper)
        // 2台目: キーボードの東隣のケースと、その上の画面
        val secondPos = KEYBOARD.east()
        helper.setBlock(secondPos, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get())
        helper.setBlock(
            secondPos.above(),
            KiBiBlocks.SCREENS.getValue(ScreenTier.MONOCHROME).get().defaultBlockState().setValue(ScreenBlock.FACING, Direction.SOUTH),
        )
        val second = helper.getBlockEntity<CaseBlockEntity>(secondPos)
        second.fallbackBootDisk = ProvisionalBoot.TEST_BOOT_DISK
        for (kind in PartKind.entries.filter { it.required && it != PartKind.BOOT }) {
            second.parts.setStackInSlot(second.tier.firstSlot(kind), ItemStack(Items.IRON_INGOT))
        }
        helper.assertTrue(first.pressPowerButton() == ComputerStatus.RUNNING, "first case should be running")
        helper.assertTrue(second.pressPowerButton() == ComputerStatus.RUNNING, "second case should be running")
        helper.assertTrue(first.wiring.connectedKeyboard === keyboard, "the first case should take the keyboard")
        helper.assertTrue(second.wiring.connectedKeyboard == null, "the second case should not take the keyboard")
        helper.assertTrue(keyboard.wiredTo === first.wiring, "the keyboard should deliver only to the first case")
        // 1台目を切ると空く。2台目を入れ直すとつながる
        first.pressPowerButton()
        helper.assertTrue(keyboard.owner == null, "turning the first case off should free the keyboard")
        second.pressPowerButton()
        helper.assertTrue(second.pressPowerButton() == ComputerStatus.RUNNING, "second case should restart")
        helper.assertTrue(second.wiring.connectedKeyboard === keyboard, "the restarted second case should take the free keyboard")
        second.pressPowerButton()
        helper.setBlock(CASE, Blocks.AIR)
        helper.setBlock(secondPos, Blocks.AIR)
        helper.succeed()
    }

    /**
     * 電源を入れると、ケースのスピーカーと隣の画面・キーボードのデバイスを、それぞれのブロックの address でつなぐ（#65）。
     * address はセーブデータに書き、読み直しても変わらない。電源を切ると外す
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun devicesAreAttachedWithSavedAddresses(helper: GameTestHelper) {
        val (case, screen, keyboard) = build(helper)
        helper.assertTrue(case.pressPowerButton() == ComputerStatus.RUNNING, "case should be running")
        val machine = case.computer.machine!!
        val expected = listOf(
            DeviceInfo(case.speakerAddress, DeviceKind.SPEAKER),
            DeviceInfo(screen.deviceAddress, DeviceKind.SCREEN),
            DeviceInfo(keyboard.deviceAddress, DeviceKind.KEYBOARD),
        )
        helper.assertTrue(machine.devices.list() == expected, "devices should be attached: ${machine.devices.list()}")
        helper.assertTrue(machine.devices.primaryScreen === case.wiring.connectedScreenDevice, "the screen device should be the console")
        helper.assertTrue(expected.map { it.address }.toSet().size == 3, "addresses should differ: $expected")
        // セーブデータに書いた address は、読み直しても同じ
        val registries = helper.level.registryAccess()
        val caseTag = case.saveWithoutMetadata(registries)
        helper.assertTrue(caseTag.getString(CaseBlockEntity.SPEAKER_ADDRESS_TAG) == case.speakerAddress, "speaker address should be saved: $caseTag")
        val reloadedScreen = ScreenBlockEntity(screen.blockPos, screen.blockState)
        reloadedScreen.loadWithComponents(screen.saveWithoutMetadata(registries), registries)
        helper.assertTrue(reloadedScreen.deviceAddress == screen.deviceAddress, "screen address should survive a reload")
        val reloadedKeyboard = KeyboardBlockEntity(keyboard.blockPos, keyboard.blockState)
        reloadedKeyboard.loadWithComponents(keyboard.saveWithoutMetadata(registries), registries)
        helper.assertTrue(reloadedKeyboard.deviceAddress == keyboard.deviceAddress, "keyboard address should survive a reload")
        // スピーカーのビープは、次の tick からメインスレッドで受け手に届く
        val speaker = machine.devices.deviceAt(case.speakerAddress) as SpeakerDevice
        helper.assertTrue(speaker.beep(440, 100), "the speaker should accept a beep")
        helper.succeedWhen {
            helper.assertTrue(case.wiring.beeps.get() == 1L, "the beep should be delivered")
            case.pressPowerButton()
            helper.assertTrue(machine.devices.list().isEmpty(), "turning off should detach the devices: ${machine.devices.list()}")
            helper.setBlock(CASE, Blocks.AIR)
        }
    }

    /**
     * データ付きで置いた画面（クリエイティブのピックブロック・NBT 付きの `/setblock`）は、置いた後に読み込んだ、保存されていた address で
     * つなぎ直す。壊したら、その address で外れる（#535）
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun screenPlacedWithDataIsReconnectedWithSavedAddress(helper: GameTestHelper) {
        val (case, screen, keyboard) = build(helper)
        val registries = helper.level.registryAccess()
        // address は最初に使うときに作るので、作ってから保存する
        val savedAddress = screen.deviceAddress
        val savedTag = screen.saveWithoutMetadata(registries)
        helper.setBlock(SCREEN, Blocks.AIR)
        helper.assertTrue(case.pressPowerButton() == ComputerStatus.RUNNING, "case should be running")
        val machine = case.computer.machine!!
        // アイテムの置き方と同じく、setBlock（隣のケースに知らせる）の後でブロックエンティティのデータを読み込む
        helper.setBlock(
            SCREEN,
            KiBiBlocks.SCREENS.getValue(ScreenTier.MONOCHROME).get().defaultBlockState().setValue(ScreenBlock.FACING, Direction.SOUTH),
        )
        val placed = helper.getBlockEntity<ScreenBlockEntity>(SCREEN)
        placed.loadWithComponents(savedTag, registries)
        helper.assertTrue(placed.deviceAddress == savedAddress, "the placed screen should have the saved address")
        var broken = false
        helper.succeedWhen {
            if (!broken) {
                helper.assertTrue(case.wiring.connectedScreen === placed, "the placed screen should be reconnected")
                val expected = listOf(
                    DeviceInfo(case.speakerAddress, DeviceKind.SPEAKER),
                    DeviceInfo(savedAddress, DeviceKind.SCREEN),
                    DeviceInfo(keyboard.deviceAddress, DeviceKind.KEYBOARD),
                )
                helper.assertTrue(machine.devices.list() == expected, "the screen should be attached with the saved address: ${machine.devices.list()}")
                helper.setBlock(SCREEN, Blocks.AIR)
                broken = true
                val listed = machine.devices.list()
                helper.assertTrue(listed == listOf(DeviceInfo(case.speakerAddress, DeviceKind.SPEAKER)), "breaking should detach the screen: $listed")
                helper.assertTrue(machine.devices.primaryScreen == null, "no screen should stay on the bus")
            }
            case.pressPowerButton()
            helper.setBlock(CASE, Blocks.AIR)
        }
    }

    /**
     * マシンが CRASHED になったら、ケースはエラーにしてマシンを外し、結線も外れる（#640）。閉じたマシンをつないだまま残さず、
     * キーボードの持ち主も画面の listener も残さない。マシンを実際に壊す手段が接続層にないので、ケースがマシンに渡す通知
     * （[pro.ikili.kibicomputers.core.computer.ComputerCase.host]）に CRASHED を流して、通知の経路を確かめる
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun crashedMachineDisconnectsWiring(helper: GameTestHelper) {
        val (case, screen, keyboard) = build(helper)
        helper.assertTrue(case.pressPowerButton() == ComputerStatus.RUNNING, "case should be running")
        val machine = case.computer.machine!!
        helper.assertTrue(case.wiring.connectedMachine === machine, "the machine should be connected")
        helper.assertTrue(case.wiring.connectedScreen === screen, "the screen should be connected")
        helper.assertTrue(keyboard.owner != null, "the keyboard should be owned by the machine")
        case.computer.host.onStateChanged(machine, MachineState.CRASHED, null)
        helper.assertTrue(case.computer.status == ComputerStatus.ERROR, "the case should show the error: ${case.computer}")
        helper.assertTrue(case.computer.error == ComputerError.CRASHED, "the error should be CRASHED: ${case.computer.error}")
        helper.assertTrue(case.computer.machine == null, "the case should release the crashed machine")
        helper.assertTrue(!case.computer.poweredOn, "the case should not stay powered on")
        helper.assertTrue(case.wiring.connectedMachine == null, "the wiring should be disconnected")
        helper.assertTrue(case.wiring.connectedScreen == null, "the screen should be detached")
        helper.assertTrue(keyboard.owner == null, "the keyboard should be freed")
        helper.setBlock(CASE, Blocks.AIR)
        helper.succeed()
    }

    /**
     * 起動に失敗したら、ケースの GUI（[CaseMenu.status]）はエラー（[ComputerError.BOOT_FAILED]）になり、ケースはマシンを外して
     * 結線も外す（#641）。失敗の1行は画面に残り、失敗の理由（[CaseWiring.bootFailure]）も外した後に残る。
     * 電源ボタンを1回押せば、新しいマシンでまた起動を試みる（ここでもまた失敗する）
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun bootFailureShowsErrorAndDisconnectsWiring(helper: GameTestHelper) {
        val (case, screen, keyboard) = build(helper)
        case.fallbackBootDisk = BootConfig.IMAGE_DISK_PREFIX + "no-such-image"
        val player = helper.makeMockPlayer(GameType.SURVIVAL)
        val menu = CaseMenu.server(0, player.inventory, case)
        val key = MachineKey.of(helper.level, helper.absolutePos(CASE))
        helper.assertTrue(menu.clickMenuButton(player, CaseMenu.POWER_BUTTON), "power button should be handled")
        helper.assertTrue(menu.status == ComputerStatus.RUNNING, "case should be running but was ${menu.status}")
        val first = case.computer.machine!!
        helper.assertTrue(case.wiring.connectedMachine === first, "the machine should be connected")
        var pressedAgain = false
        helper.succeedWhen {
            val text = lines(screen.text)
            helper.assertTrue(menu.status == ComputerStatus.ERROR, "the boot failure should show an error but was ${menu.status}: $text")
            helper.assertTrue(menu.error == ComputerError.BOOT_FAILED, "the error should be BOOT_FAILED but was ${menu.error}")
            helper.assertTrue(case.computer.machine == null, "the case should release the machine")
            helper.assertTrue(ServerMachines.current!![key] == null, "the manager should drop the machine")
            helper.assertTrue(!case.computer.poweredOn, "the power should be off")
            helper.assertTrue(keyboard.owner == null, "the keyboard should be released")
            helper.assertTrue(case.wiring.connectedMachine == null, "the wiring should be disconnected")
            helper.assertTrue(case.wiring.bootFailure == BootFailure.NO_BOOT_DISK, "the failure should stay: ${case.wiring.bootFailure}")
            helper.assertTrue(text.any { it == "kibi: no bootable disk" }, "the failure line should stay on the screen: $text")
            if (!pressedAgain) {
                // 1回押せば、新しいマシンで起動し直す（空振りしない）
                helper.assertTrue(menu.clickMenuButton(player, CaseMenu.POWER_BUTTON), "power button should be handled")
                helper.assertTrue(menu.status == ComputerStatus.RUNNING, "one press should boot again but was ${menu.status}")
                val next = case.computer.machine
                helper.assertTrue(next != null && next !== first, "a new machine should be created")
                helper.assertTrue(ServerMachines.current!![key] === next, "the manager should hold the new machine")
                helper.assertTrue(case.wiring.bootFailure == null, "connecting a new machine should clear the failure")
                pressedAgain = true
                helper.fail("pressed the power button again")
            }
            helper.setBlock(CASE, Blocks.AIR)
        }
    }

    /**
     * マシンが自分で止まった（ゲストが止めた）ら、ケースはマシンを外して OFF にし、結線も外れる（#640）。その後に画面を置き直しても、
     * 止まったマシンにはつながず、キーボードも取らない（#535）。
     *
     * 起動し終えてから止める（#633）。起動の途中の [pro.ikili.kibicomputers.core.kernel.Machine.stop] は起動の取り消しになり、
     * 起動用のスレッドで起動の後始末が走るまで起動中の扱いのまま残る。STOPPED の通知も出ないので、ケースはマシンを持ち続け、
     * 置いた画面をつなぎうる（本番で外から起動の途中に `stop()` を呼ぶ道はない。ADR 0085）
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun replacingScreenDoesNotReconnectStoppedMachine(helper: GameTestHelper) {
        val (case, _, keyboard) = build(helper)
        helper.assertTrue(case.pressPowerButton() == ComputerStatus.RUNNING, "case should be running")
        val machine = case.computer.machine!!
        helper.setBlock(SCREEN, Blocks.AIR)
        helper.assertTrue(keyboard.owner == null, "breaking the screen should free the keyboard")
        var stopped = false
        var placedTicks = -1
        var attachedBefore = 0
        helper.succeedWhen {
            if (!stopped) {
                // 起動の途中で止めると起動の取り消しになる（ゲストの停止ではない。#641・#633）ので、起動し終えてから止める
                helper.assertTrue(machine.state == MachineState.RUNNING && !machine.isBooting, "machine should finish booting")
                // ゲストが止めたのと同じく、電源を入れたまま、マシンだけが止まる
                machine.stop()
                stopped = true
            }
            if (placedTicks < 0) {
                helper.assertTrue(machine.state == MachineState.STOPPED, "machine should stop: ${machine.state}")
                helper.assertTrue(case.computer.status == ComputerStatus.OFF, "the case should turn off: ${case.computer}")
                helper.assertTrue(case.computer.machine == null, "the case should release the stopped machine")
                helper.assertTrue(!case.computer.poweredOn, "the case should not stay powered on")
                helper.assertTrue(case.wiring.connectedMachine == null, "the wiring should be disconnected")
                attachedBefore = case.wiring.screenAttachCount
                helper.setBlock(
                    SCREEN,
                    KiBiBlocks.SCREENS.getValue(ScreenTier.MONOCHROME).get().defaultBlockState().setValue(ScreenBlock.FACING, Direction.SOUTH),
                )
                placedTicks = 0
            }
            helper.assertTrue(case.wiring.connectedScreen == null, "the stopped machine should not take the screen")
            helper.assertTrue(keyboard.owner == null, "the stopped machine should not take the keyboard")
            helper.assertTrue(case.wiring.connectedMachine == null, "the wiring should stay disconnected")
            // tick の終わりの状態だけでは、一時的につないですぐ外れたものが見えない。つないだ回数で確かめる（#633）
            helper.assertTrue(
                case.wiring.screenAttachCount == attachedBefore,
                "the screen should never have been attached to the stopped machine (${case.wiring.screenAttachCount - attachedBefore} attach)",
            )
            helper.assertTrue(++placedTicks > 3, "watch for a few ticks after placing")
            helper.setBlock(CASE, Blocks.AIR)
        }
    }

    /**
     * 再起動の途中（[pro.ikili.kibicomputers.core.kernel.Machine.reboot] を受け付けてから、起動し直すまで）に画面を置いても、
     * 次の tick に画面とキーボードをつなぎ直し、起動し直したマシンに打った式の結果が置いた画面に出る（#626）
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun screenPlacedWhileRebootingIsReconnected(helper: GameTestHelper) {
        val (case, _, keyboard) = build(helper)
        helper.assertTrue(case.pressPowerButton() == ComputerStatus.RUNNING, "case should be running")
        val machine = case.computer.machine!!
        var broken = false
        var ticksAfterBreak = 0
        var placed: ScreenBlockEntity? = null
        var typed = false
        helper.succeedWhen {
            if (!broken) {
                helper.assertTrue(machine.state == MachineState.RUNNING && !machine.isBooting, "machine should finish booting")
                helper.setBlock(SCREEN, Blocks.AIR)
                broken = true
                helper.assertTrue(keyboard.owner == null, "breaking the screen should free the keyboard")
            }
            if (placed == null) {
                // 壊した tick に回ったつなぎ直し（RUNNING なので回る）が済んでから置く。同じ tick に置くと、その予約で
                // 直す前のコードでもつながってしまう（scheduleTick は同じ位置の予約を重ねない）
                helper.assertTrue(++ticksAfterBreak > 2, "wait for the reconnect scheduled when the screen was broken")
                helper.assertTrue(machine.reboot(), "reboot should be accepted")
                helper.assertTrue(!machine.isRunningOrBooting, "the machine should be in the middle of rebooting: ${machine.state}")
                helper.setBlock(
                    SCREEN,
                    KiBiBlocks.SCREENS.getValue(ScreenTier.MONOCHROME).get().defaultBlockState().setValue(ScreenBlock.FACING, Direction.SOUTH),
                )
                placed = helper.getBlockEntity<ScreenBlockEntity>(SCREEN)
                // 置いたブロックのデータを読み込んだ後につなぐので、置いた tick にはまだつながない（#535）
                helper.assertTrue(case.wiring.connectedScreen == null, "the placed screen should be reconnected on the next tick")
            }
            if (!typed) {
                val screen = placed!!
                helper.assertTrue(case.wiring.connectedScreen === screen, "the placed screen should be reconnected")
                helper.assertTrue(case.wiring.connectedKeyboard === keyboard, "the keyboard should be reconnected")
                // 起動し直すまで待ってから打つ（プロンプトは画面をつなぐ前に出ていることがあるので、プロンプトは待たない）
                helper.assertTrue(machine.state == MachineState.RUNNING && !machine.isBooting, "machine should finish rebooting")
                helper.assertTrue(case.computer.machine === machine, "the case should keep the rebooted machine")
                helper.assertTrue(machine.devices.primaryScreen === case.wiring.connectedScreenDevice, "the screen device should be the console")
                val expected = listOf(
                    DeviceInfo(case.speakerAddress, DeviceKind.SPEAKER),
                    DeviceInfo(screen.deviceAddress, DeviceKind.SCREEN),
                    DeviceInfo(keyboard.deviceAddress, DeviceKind.KEYBOARD),
                )
                helper.assertTrue(machine.devices.list() == expected, "devices should be attached again: ${machine.devices.list()}")
                typeLine(helper, keyboard, "print(20+22)")
                typed = true
            }
            val text = locked(placed!!) { lines(placed!!.text) }
            // 打った行は REPL が読み始める前にエコーされることがあり、そのときは結果がプロンプトの後に続く（"> 42"）
            helper.assertTrue(text.any { it == "42" || it == "> 42" }, "the result should be shown on the placed screen: $text")
            case.pressPowerButton()
            helper.setBlock(CASE, Blocks.AIR)
        }
    }

    /** 再起動を求めた後に止めた（ゲストが止めたのと同じ）マシンには、その間に置いた画面をつながない（#535・#626） */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun screenPlacedAfterCancelledRebootIsNotReconnected(helper: GameTestHelper) {
        val (case, _, keyboard) = build(helper)
        helper.assertTrue(case.pressPowerButton() == ComputerStatus.RUNNING, "case should be running")
        val machine = case.computer.machine!!
        helper.setBlock(SCREEN, Blocks.AIR)
        helper.assertTrue(keyboard.owner == null, "breaking the screen should free the keyboard")
        var ticksAfterBreak = 0
        var placedTicks = -1
        var attachedBefore = 0
        helper.succeedWhen {
            if (placedTicks < 0) {
                helper.assertTrue(machine.state == MachineState.RUNNING && !machine.isBooting, "machine should finish booting")
                helper.assertTrue(++ticksAfterBreak > 2, "wait for the reconnect scheduled when the screen was broken")
                attachedBefore = case.wiring.screenAttachCount
                helper.assertTrue(machine.reboot(), "reboot should be accepted")
                machine.stop()
                helper.setBlock(
                    SCREEN,
                    KiBiBlocks.SCREENS.getValue(ScreenTier.MONOCHROME).get().defaultBlockState().setValue(ScreenBlock.FACING, Direction.SOUTH),
                )
                placedTicks = 0
            }
            helper.assertTrue(case.wiring.connectedScreen == null, "the stopped machine should not take the screen")
            helper.assertTrue(keyboard.owner == null, "the stopped machine should not take the keyboard")
            helper.assertTrue(++placedTicks > 3, "watch for a few ticks after placing")
            // 一時的につないですぐ外れたものは、tick の終わりの状態では見えない（止まった通知でケースが結線を外す）。つないだ回数で確かめる
            helper.assertTrue(
                case.wiring.screenAttachCount == attachedBefore,
                "the screen should never have been attached to the stopped machine (${case.wiring.screenAttachCount - attachedBefore} attach)",
            )
            helper.assertTrue(case.computer.status == ComputerStatus.OFF, "the case should turn off: ${case.computer}")
            helper.assertTrue(case.wiring.connectedMachine == null, "the wiring should be disconnected")
            helper.setBlock(CASE, Blocks.AIR)
        }
    }

    /**
     * 再起動を繰り返して起動し直すまでの待ち（#635）に入った台は、アンロードと停止の待ち（メインスレッドで保存の判定が変わるのを待つ）に
     * 数えない。待ちの間の保存の判定は `TRANSIENT` のままだが、tick でしか解けないので、待つと予算を使い切るだけになる。
     * 待ちの間（STOPPED の通知は先に届く）もケースは電源を入れたまま・表示も RUNNING のままで、期限の後に同じマシンが起動し直す
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun deferredRebootIsNotWaitedForOnSave(helper: GameTestHelper) {
        val (case, _, _) = build(helper)
        helper.assertTrue(case.pressPowerButton() == ComputerStatus.RUNNING, "case should be running")
        val machine = case.computer.machine!!
        var requested = 0
        var sawDeferral = false
        var deferredTicks = 0
        helper.succeedWhen {
            if (!sawDeferral) {
                if (!machine.isRebootDeferred) {
                    // 3 回までは待たずに起動し直す。起動し直したら、また求める
                    if (machine.state == MachineState.RUNNING && !machine.isBooting && !machine.isRebootPending) {
                        helper.assertTrue(++requested <= 8, "the reboots should be deferred after a few requests")
                        helper.assertTrue(machine.reboot(), "reboot should be accepted")
                    }
                    helper.assertTrue(false, "wait for the reboot to be deferred ($requested requests)")
                }
                sawDeferral = true
                helper.assertTrue(machine.isRebootPending, "a deferred reboot is still pending")
                helper.assertTrue(machine.saveReadiness() == SaveReadiness.TRANSIENT, "the save readiness stays transient")
                helper.assertTrue(!case.computer.isSettlingForSave, "waiting for a deferred reboot would only burn the wait budget")
                helper.assertTrue(!ServerMachines.anySettling(listOf(case)), "the unload and stop waits should not wait for it")
            }
            if (machine.isRebootDeferred) {
                // 待ちの間: マシンは STOPPED だが、ケースは電源を切らず、表示も変えない
                deferredTicks++
                helper.assertTrue(machine.state == MachineState.STOPPED, "the deferred machine is stopped: ${machine.state}")
                helper.assertTrue(case.computer.poweredOn, "the case should stay powered on while the reboot is deferred")
                helper.assertTrue(case.computer.status == ComputerStatus.RUNNING, "the case should show RUNNING: ${case.computer}")
                helper.assertTrue(case.computer.machine === machine, "the case should keep the machine")
                helper.assertTrue(false, "wait for the deferred reboot ($deferredTicks ticks)")
            }
            // 期限の後: 同じマシンがケース経由で起動し直す
            helper.assertTrue(deferredTicks > 1, "the reboot should have been deferred for some ticks ($deferredTicks)")
            helper.assertTrue(case.computer.machine === machine, "the case should keep the machine after the reboot")
            helper.assertTrue(case.computer.poweredOn, "the case should stay powered on after the reboot")
            helper.assertTrue(case.computer.status == ComputerStatus.RUNNING, "the case should show RUNNING: ${case.computer}")
            helper.assertTrue(
                machine.state == MachineState.RUNNING && !machine.isBooting && !machine.isRebootPending,
                "wait for the machine to boot again: ${machine.state}",
            )
            case.pressPowerButton()
            helper.setBlock(CASE, Blocks.AIR)
        }
    }

    /**
     * つなげる数（`MachineConfig.maxDevices`。#66）が2のマシンでは、スピーカーと画面がつながり、キーボードは断られる。
     * 断られたキーボードは手放し（ほかのケースが使える）、マシンは動き続ける
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun deviceLimitRefusesKeyboardAndKeepsRunning(helper: GameTestHelper) {
        val (_, screen, keyboard) = build(helper)
        val manager = ServerMachines.current!!
        // ケースのブロックの電源は入れず、上限の小さいマシンを同じ場所の結線でつなぐ
        val key = MachineKey(helper.level.dimension(), helper.absolutePos(KEYBOARD.east()))
        val wiring = CaseWiring()
        val machine = manager.attach(key, wiring, MachineConfig.builder().maxDevices(2).build())
        val speaker = UUID.randomUUID().toString()
        wiring.connect(helper.level, helper.absolutePos(CASE), machine, speaker, ProvisionalBoot.bootConfig(emptyList(), ProvisionalBoot.TEST_BOOT_DISK))
        helper.assertTrue(machine.devices.limit == 2, "the limit should come from the config")
        helper.assertTrue(wiring.connectedScreen === screen, "the screen should be connected")
        helper.assertTrue(wiring.connectedKeyboard == null, "the keyboard should be refused")
        helper.assertTrue(keyboard.owner == null, "the refused keyboard should be released")
        helper.assertTrue(
            machine.devices.list().map { it.address } == listOf(speaker, screen.deviceAddress),
            "only the speaker and the screen should be attached: ${machine.devices.list()}",
        )
        helper.succeedWhen {
            helper.assertTrue(machine.state == MachineState.RUNNING, "the machine should keep running: ${machine.state}")
            helper.assertTrue(wiring.connectedScreenDevice === machine.devices.primaryScreen, "the screen should be the console")
            wiring.disconnect()
            manager.release(key, machine, MachineReleaseReason.REMOVED)
            helper.setBlock(CASE, Blocks.AIR)
        }
    }

    /**
     * つなげる数が1のマシンでは、スピーカーで満杯になり、画面は断られる（先読みで表示デバイスを作らずに諦める）。
     * 断られた画面は何も変えず（つないだ印、つないだ回数、address、画面が映す表示デバイス）、キーボードは取らず、
     * ログは1回だけ。その後の [CaseWiring.rewire] でも変わらない。マシンは動き続ける
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun deviceLimitRefusesScreenAndKeepsRunning(helper: GameTestHelper) {
        val (_, screen, keyboard) = build(helper)
        val manager = ServerMachines.current!!
        val key = MachineKey(helper.level.dimension(), helper.absolutePos(KEYBOARD.east()))
        val wiring = CaseWiring()
        val machine = manager.attach(key, wiring, MachineConfig.builder().maxDevices(1).build())
        val speaker = UUID.randomUUID().toString()
        val displayBefore = screen.display
        wiring.connect(helper.level, helper.absolutePos(CASE), machine, speaker, ProvisionalBoot.bootConfig(emptyList(), ProvisionalBoot.TEST_BOOT_DISK))
        fun assertRefused(stage: String) {
            helper.assertTrue(wiring.connectedScreen == null, "$stage: the refused screen should not be connected")
            helper.assertTrue(wiring.connectedScreenDevice == null, "$stage: no screen device should be kept")
            helper.assertTrue(wiring.screenAttachCount == 0, "$stage: the screen should never have been attached (${wiring.screenAttachCount})")
            helper.assertTrue(wiring.connectedKeyboard == null, "$stage: the keyboard should not be connected")
            helper.assertTrue(keyboard.owner == null, "$stage: the keyboard should not be taken")
            helper.assertTrue(screen.display === displayBefore, "$stage: the screen should keep its own display")
            helper.assertTrue(machine.devices.list().map { it.address } == listOf(speaker), "$stage: only the speaker: ${machine.devices.list()}")
            helper.assertTrue(wiring.limitLogCount == 1, "$stage: the refusal should be logged once (${wiring.limitLogCount})")
        }
        assertRefused("after connect")
        // 隣のブロックが変わったときの探し直しでも、状態は変わらず、ログも増えない
        helper.assertTrue(wiring.wantsRewire(machine), "the wiring should try to reconnect the screen")
        wiring.rewire()
        assertRefused("after reconnect")
        helper.succeedWhen {
            helper.assertTrue(machine.state == MachineState.RUNNING, "the machine should keep running: ${machine.state}")
            wiring.disconnect()
            manager.release(key, machine, MachineReleaseReason.REMOVED)
            helper.setBlock(CASE, Blocks.AIR)
        }
    }

    /**
     * 同じマシンに同じ address のデバイスがもうつながっていたら（ブロックの複製など。ここではスピーカーをキーボードと同じ address にする）、
     * 後から来たほう（キーボード）の address を振り直してつなぐ（#129。例外で止めない）。振り直した address はセーブデータに書く
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun duplicateAddressIsReassignedForTheLaterDevice(helper: GameTestHelper) {
        val (_, screen, keyboard) = build(helper)
        val manager = ServerMachines.current!!
        val key = MachineKey(helper.level.dimension(), helper.absolutePos(KEYBOARD.east()))
        val wiring = CaseWiring()
        val machine = manager.attach(key, wiring, MachineConfig.DEFAULT)
        val shared = keyboard.deviceAddress
        wiring.attach(helper.level, helper.absolutePos(CASE), machine, shared)
        helper.assertTrue(wiring.connectedKeyboard === keyboard, "the keyboard should be connected")
        helper.assertTrue(keyboard.deviceAddress != shared, "the later keyboard should get a new address")
        helper.assertTrue(
            machine.devices.list().map { it.address } == listOf(shared, screen.deviceAddress, keyboard.deviceAddress),
            "the speaker keeps the address and the keyboard is attached with the new one: ${machine.devices.list()}",
        )
        val tag = keyboard.saveWithoutMetadata(helper.level.registryAccess())
        helper.assertTrue(tag.getString(KeyboardBlockEntity.DEVICE_ADDRESS_TAG) == keyboard.deviceAddress, "the new address should be saved: $tag")
        wiring.disconnect()
        helper.assertTrue(keyboard.owner == null, "the keyboard should be released")
        manager.release(key, machine, MachineReleaseReason.REMOVED)
        helper.setBlock(CASE, Blocks.AIR)
        helper.succeed()
    }
}
