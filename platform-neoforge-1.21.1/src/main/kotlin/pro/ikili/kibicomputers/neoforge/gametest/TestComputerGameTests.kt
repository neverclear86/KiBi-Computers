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
import pro.ikili.kibicomputers.core.kernel.MachineState
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
import pro.ikili.kibicomputers.neoforge.computer.CaseMenu
import pro.ikili.kibicomputers.neoforge.computer.TestComputerWiring
import pro.ikili.kibicomputers.neoforge.display.ScreenBlock
import pro.ikili.kibicomputers.neoforge.display.ScreenBlockEntity
import pro.ikili.kibicomputers.neoforge.input.KeyboardBlockEntity
import pro.ikili.kibicomputers.neoforge.machine.MachineKey
import pro.ikili.kibicomputers.neoforge.machine.ServerMachines
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks
import java.util.UUID

/** テスト用コンピューターの仮の結線（#307）の GameTest。 */
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
    private val KEYBOARD = BlockPos(1, 1, 2)

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
        case.fallbackBootDisk = TestComputerWiring.TEST_BOOT_DISK
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
        second.fallbackBootDisk = TestComputerWiring.TEST_BOOT_DISK
        for (kind in PartKind.entries.filter { it.required && it != PartKind.BOOT }) {
            second.parts.setStackInSlot(second.tier.firstSlot(kind), ItemStack(Items.IRON_INGOT))
        }
        helper.assertTrue(first.pressPowerButton() == ComputerStatus.RUNNING, "first case should be running")
        helper.assertTrue(second.pressPowerButton() == ComputerStatus.RUNNING, "second case should be running")
        helper.assertTrue(first.wiring.connectedKeyboard === keyboard, "the first case should take the keyboard")
        helper.assertTrue(second.wiring.connectedKeyboard == null, "the second case should not take the keyboard")
        helper.assertTrue(keyboard.owner === first.wiring, "the keyboard should deliver only to the first case")
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
     * 結線も外す（#641）。失敗の1行は画面に残り、失敗の理由（[TestComputerWiring.bootFailure]）も外した後に残る。
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
     * 止まったマシンにはつながず、キーボードも取らない（#535）
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
        helper.succeedWhen {
            if (!stopped) {
                // 起動の途中で止めると起動の取り消しになる（ゲストの停止ではない。#641 の範囲）ので、起動し終えてから止める
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
                helper.setBlock(
                    SCREEN,
                    KiBiBlocks.SCREENS.getValue(ScreenTier.MONOCHROME).get().defaultBlockState().setValue(ScreenBlock.FACING, Direction.SOUTH),
                )
                placedTicks = 0
            }
            helper.assertTrue(case.wiring.connectedScreen == null, "the stopped machine should not take the screen")
            helper.assertTrue(keyboard.owner == null, "the stopped machine should not take the keyboard")
            helper.assertTrue(case.wiring.connectedMachine == null, "the wiring should stay disconnected")
            helper.assertTrue(++placedTicks > 3, "watch for a few ticks after placing")
            helper.setBlock(CASE, Blocks.AIR)
        }
    }
}
