package pro.ikili.kibicomputers.neoforge.gametest

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.computer.CaseTier
import pro.ikili.kibicomputers.core.computer.ComputerStatus
import pro.ikili.kibicomputers.core.computer.PartKind
import pro.ikili.kibicomputers.core.computer.PartTier
import pro.ikili.kibicomputers.core.display.ScreenTier
import pro.ikili.kibicomputers.core.display.TextBuffer
import pro.ikili.kibicomputers.core.input.Key
import pro.ikili.kibicomputers.core.input.KeyboardEvent
import pro.ikili.kibicomputers.core.input.KeyboardEventCodec
import pro.ikili.kibicomputers.core.kernel.MachineState
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
import pro.ikili.kibicomputers.neoforge.computer.CaseMenu
import pro.ikili.kibicomputers.neoforge.computer.DiskItem
import pro.ikili.kibicomputers.neoforge.computer.ProvisionalBoot
import pro.ikili.kibicomputers.neoforge.computer.WorldDisks
import pro.ikili.kibicomputers.neoforge.display.ScreenBlock
import pro.ikili.kibicomputers.neoforge.display.ScreenBlockEntity
import pro.ikili.kibicomputers.neoforge.input.KeyboardBlockEntity
import pro.ikili.kibicomputers.neoforge.input.KeyboardInputPayload
import pro.ikili.kibicomputers.neoforge.input.KeyboardSessions
import pro.ikili.kibicomputers.neoforge.machine.MachineKey
import pro.ikili.kibicomputers.neoforge.machine.ServerMachines
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** ゲームの中で標準 OS を起動する仮の起動経路（#461）の GameTest。 */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object StandardOsGameTests {
    /** 起動を待つ tick 数の上限（[TestComputerGameTests] と同じ理由で長くとる。init とシェルの読み込みを2回待つ） */
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

    /** キーボードで1行打つ（Enter まで） */
    private fun typeLine(helper: GameTestHelper, keyboard: KeyboardBlockEntity, line: String) {
        val events = listOf(KeyboardEvent.Text(line), KeyboardEvent.KeyDown(Key.ENTER, 0, false))
        for (packet in KeyboardEventCodec.encode(events)) {
            helper.assertTrue(keyboard.receive(UUID(0, 1), packet) == 2, "keyboard should accept the input")
        }
    }

    private data class Rig(val case: CaseBlockEntity, val screen: ScreenBlockEntity, val keyboard: KeyboardBlockEntity)

    /** ケース（HDD Tier 1 入り）・画面・キーボードを置き、電源を入れる */
    private fun placeComputer(helper: GameTestHelper): Rig {
        helper.setBlock(CASE, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get())
        helper.setBlock(
            SCREEN,
            KiBiBlocks.SCREENS.getValue(ScreenTier.COLOR).get().defaultBlockState().setValue(ScreenBlock.FACING, Direction.SOUTH),
        )
        helper.setBlock(KEYBOARD, KiBiBlocks.KEYBOARD.get())
        val case = helper.getBlockEntity<CaseBlockEntity>(CASE)
        val screen = helper.getBlockEntity<ScreenBlockEntity>(SCREEN)
        val keyboard = helper.getBlockEntity<KeyboardBlockEntity>(KEYBOARD)
        for (kind in PartKind.entries.filter { it.required && it != PartKind.BOOT && it != PartKind.STORAGE }) {
            case.parts.setStackInSlot(case.tier.firstSlot(kind), ItemStack(Items.IRON_INGOT))
        }
        case.parts.setStackInSlot(case.tier.firstSlot(PartKind.STORAGE), ItemStack(KiBiBlocks.part(PartTier.TIER_1, PartKind.STORAGE)))
        helper.assertTrue(case.pressPowerButton() == ComputerStatus.RUNNING, "case should run but was ${case.computer}")
        return Rig(case, screen, keyboard)
    }

    /** ケースの HDD（`~`）の、ワールドの中のディレクトリ */
    private fun homeDirectory(helper: GameTestHelper, case: CaseBlockEntity): Path {
        val store = WorldDisks.current
        helper.assertTrue(store != null, "the server should have a disk store")
        val id = DiskItem.diskIdOf(case.parts.getStackInSlot(case.tier.firstSlot(PartKind.STORAGE)))
        helper.assertTrue(id != null, "the disk should get an ID")
        return store!!.directoryOf(id!!)!!
    }

    /**
     * 標準 OS の `reboot` では、ケースの GUI（[CaseMenu.status]）は RUNNING のままで、同じマシンが起動し直す。
     * `shutdown now` では、GUI が OFF になり、ケースはマシンを外し、キーボードも放す。電源ボタンを1回押せば、また起動する（#640）
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun guestShutdownTurnsCaseOffButRebootKeepsRunning(helper: GameTestHelper) {
        val (case, screen, keyboard) = placeComputer(helper)
        val player = helper.makeMockPlayer(GameType.SURVIVAL)
        val menu = CaseMenu.server(0, player.inventory, case)
        val key = MachineKey.of(helper.level, helper.absolutePos(CASE))
        val machine = case.computer.machine!!
        val booting = "kibi: booting from disk ${ProvisionalBoot.OS_BOOT_DISK}"
        var step = 0
        helper.succeedWhen {
            val text = lines(screen.text)
            helper.assertTrue(case.wiring.bootFailure == null, "boot should not fail but was ${case.wiring.bootFailure}: $text")
            when (step) {
                0 -> {
                    helper.assertTrue(text.any { it == "$" }, "the shell prompt should be shown: $text")
                    typeLine(helper, keyboard, "clear")
                    step = 1
                    helper.fail("typed clear")
                }
                1 -> {
                    // 画面を消してから再起動し、起動の行がまた出るのを待つ
                    helper.assertTrue(text.none { it == booting } && text.any { it == "$" }, "the screen should be cleared: $text")
                    typeLine(helper, keyboard, "reboot")
                    step = 2
                    helper.fail("typed reboot")
                }
                2 -> {
                    helper.assertTrue(menu.status == ComputerStatus.RUNNING, "reboot should keep the case running but was ${menu.status}")
                    helper.assertTrue(case.computer.machine === machine, "reboot should keep the same machine")
                    helper.assertTrue(text.any { it == booting }, "the machine should boot again: $text")
                    helper.assertTrue(text.any { it == "$" }, "the shell prompt should be shown again: $text")
                    helper.assertTrue(case.computer.poweredOn, "reboot should keep the power on")
                    typeLine(helper, keyboard, "shutdown now")
                    step = 3
                    helper.fail("typed shutdown now")
                }
                3 -> {
                    helper.assertTrue(menu.status == ComputerStatus.OFF, "shutdown should turn the case off but was ${menu.status}")
                    helper.assertTrue(case.computer.machine == null, "the case should release the machine")
                    helper.assertTrue(ServerMachines.current!![key] == null, "the manager should drop the machine")
                    helper.assertTrue(!case.computer.poweredOn, "the power should be off")
                    helper.assertTrue(keyboard.owner == null, "the keyboard should be released")
                    helper.assertTrue(case.wiring.connectedMachine == null, "the wiring should be disconnected")
                    // 1回押せば起動する
                    helper.assertTrue(menu.clickMenuButton(player, CaseMenu.POWER_BUTTON), "power button should be handled")
                    helper.assertTrue(menu.status == ComputerStatus.RUNNING, "one press should boot again but was ${menu.status}")
                    val next = case.computer.machine
                    helper.assertTrue(next != null && next !== machine, "a new machine should be created")
                    helper.assertTrue(ServerMachines.current!![key] === next, "the manager should hold the new machine")
                    case.pressPowerButton()
                    helper.setBlock(CASE, Blocks.AIR)
                }
            }
        }
    }

    /**
     * ケースと HDD・画面・キーボードで電源を入れると、標準 OS のシェルのプロンプトが出る。`~`（`/home`）は HDD で、
     * `echo hi > ~/a; cat ~/a` が動き、電源を入れ直した後も `~/a` が残る。
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun shellWritesHomeOnDiskAndKeepsItAcrossPowerCycles(helper: GameTestHelper) {
        val (case, screen, keyboard) = placeComputer(helper)
        val file = homeDirectory(helper, case).resolve("a")
        var step = 0
        helper.succeedWhen {
            val text = lines(screen.text)
            helper.assertTrue(case.wiring.bootFailure == null, "boot should not fail but was ${case.wiring.bootFailure}: $text")
            when (step) {
                0 -> {
                    helper.assertTrue(
                        text.any { it == "kibi: booting from disk ${ProvisionalBoot.OS_BOOT_DISK}" },
                        "should boot the OS image: $text",
                    )
                    helper.assertTrue(text.any { it == "$" }, "the shell prompt should be shown: $text")
                    typeLine(helper, keyboard, "echo hi > ~/a; cat ~/a")
                    step = 1
                    helper.fail("typed the first line")
                }
                1 -> {
                    helper.assertTrue(text.any { it == "$ echo hi > ~/a; cat ~/a" }, "the typed line should be echoed: $text")
                    helper.assertTrue(text.any { it == "hi" }, "cat should print the file: $text")
                    helper.assertTrue(Files.isRegularFile(file) && Files.readString(file) == "hi\n", "~/a should be on the disk: $text")
                    // 電源を入れ直す
                    case.pressPowerButton()
                    helper.assertTrue(case.computer.status == ComputerStatus.OFF, "case should turn off but was ${case.computer}")
                    helper.assertTrue(case.pressPowerButton() == ComputerStatus.RUNNING, "case should run again but was ${case.computer}")
                    step = 2
                    helper.fail("power cycled")
                }
                2 -> {
                    helper.assertTrue(text.none { it == "hi" }, "a new display should start empty: $text")
                    helper.assertTrue(text.any { it == "$" }, "the shell prompt should be shown again: $text")
                    typeLine(helper, keyboard, "cat ~/a")
                    step = 3
                    helper.fail("typed the second line")
                }
                else -> {
                    helper.assertTrue(text.any { it == "$ cat ~/a" }, "the typed line should be echoed: $text")
                    helper.assertTrue(text.any { it == "hi" }, "~/a should survive the power cycle: $text")
                    helper.assertTrue(case.computer.machine?.state == MachineState.RUNNING, "machine should keep running")
                    case.pressPowerButton()
                    helper.setBlock(CASE, Blocks.AIR)
                }
            }
        }
    }

    /**
     * 標準 OS のイメージの `/bin/lua`（#603）: シェルの `printf` で `~/s.lua`（`#!/bin/lua` のスクリプト）を HDD に書き、
     * `lua ~/s.lua` と、シバンで `~/s.lua` を直接動かせる。
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun shellRunsLuaScriptWrittenToHome(helper: GameTestHelper) {
        val (case, screen, keyboard) = placeComputer(helper)
        val file = homeDirectory(helper, case).resolve("s.lua")
        // シェルの組み込みの printf が `\n` を改行にする（`\\n` は Kotlin の文字列の中の `\n` そのもの）
        val line = "printf '#!/bin/lua\\nprint(\"lua\"..6*7)\\n' > ~/s.lua; lua ~/s.lua; ~/s.lua"
        var typed = false
        helper.succeedWhen {
            val text = lines(screen.text)
            helper.assertTrue(case.wiring.bootFailure == null, "boot should not fail but was ${case.wiring.bootFailure}: $text")
            if (!typed) {
                helper.assertTrue(text.any { it == "$" }, "the shell prompt should be shown: $text")
                typeLine(helper, keyboard, line)
                typed = true
                helper.fail("typed the line")
            }
            // 打った行には `lua42` が含まれないので、出力の行だけが数に入る（`lua ~/s.lua` とシバンで2回）
            helper.assertTrue(text.count { it == "lua42" } == 2, "lua should run the script twice: $text")
            helper.assertTrue(text.last { it.isNotEmpty() } == "$", "the shell should be back at the prompt: $text")
            helper.assertTrue(
                Files.isRegularFile(file) && Files.readString(file) == "#!/bin/lua\nprint(\"lua\"..6*7)\n",
                "~/s.lua should be on the disk: $text",
            )
            case.pressPowerButton()
            helper.setBlock(CASE, Blocks.AIR)
        }
    }

    /**
     * Lua の peripheral（#94、ADR 0094）: 標準 OS の `/bin/lua` から、ケースのスピーカーと隣の画面・キーボードを列挙し、
     * 種類で探して、画面の `get-size` をキャメルケースの別名で呼べる
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun luaListsAndCallsAdjacentDevices(helper: GameTestHelper) {
        val (case, screen, keyboard) = placeComputer(helper)
        val script = "local s = peripheral.find('screen'); local w, h = s.getSize(); " +
            "print(table.concat({'pk', #peripheral.getNames(), peripheral.getType(peripheral.getName(peripheral.find('speaker'))), " +
            "tostring(peripheral.hasType(peripheral.getName(peripheral.find('keyboard')), 'kibicomputers:keyboard')), w .. 'x' .. h}, ','))"
        val expected = "pk,3,kibicomputers:speaker,true,${screen.text.width}x${screen.text.height}"
        var typed = false
        helper.succeedWhen {
            val text = lines(screen.text)
            helper.assertTrue(case.wiring.bootFailure == null, "boot should not fail but was ${case.wiring.bootFailure}: $text")
            if (!typed) {
                helper.assertTrue(text.any { it == "$" }, "the shell prompt should be shown: $text")
                typeLine(helper, keyboard, "lua -e \"$script\"")
                typed = true
                helper.fail("typed the line")
            }
            helper.assertTrue(text.any { it == expected }, "lua should list and call the devices ($expected): $text")
            case.pressPowerButton()
            helper.setBlock(CASE, Blocks.AIR)
        }
    }

    /**
     * Lua の parallel（#95、ADR 0095）: 標準 OS の `/bin/lua` から、2つのコルーチンが sleep とイベントで待ち合わせて終わる
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun luaParallelRunsCoroutinesTogether(helper: GameTestHelper) {
        val (case, screen, keyboard) = placeComputer(helper)
        val script = "local t = {}; parallel.waitForAll(function() os.sleep(0.2); t[#t+1] = 'a'; os.queueEvent('go', 'b') end, " +
            "function() local _, v = os.pullEvent('go'); t[#t+1] = v end); print('par,' .. table.concat(t))"
        var typed = false
        helper.succeedWhen {
            val text = lines(screen.text)
            helper.assertTrue(case.wiring.bootFailure == null, "boot should not fail but was ${case.wiring.bootFailure}: $text")
            if (!typed) {
                helper.assertTrue(text.any { it == "$" }, "the shell prompt should be shown: $text")
                typeLine(helper, keyboard, "lua -e \"$script\"")
                typed = true
                helper.fail("typed the line")
            }
            helper.assertTrue(text.any { it == "par,ab" }, "lua should run parallel: $text")
            case.pressPowerButton()
            helper.setBlock(CASE, Blocks.AIR)
        }
    }

    /**
     * Lua の peripheral.call をコルーチンの中で呼ぶ（#744、ADR 0744）: 標準 OS の `/bin/lua` から、`parallel` の2つのコルーチンで
     * 隣の画面のメソッドを `peripheral.call`（start → take の道）と `wrap` の関数で呼びながら、もう一方で `os.sleep` する
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun luaPeripheralCallRunsInsideParallel(helper: GameTestHelper) {
        val (case, screen, keyboard) = placeComputer(helper)
        val script = "local s = peripheral.find('screen'); local n = peripheral.getName(s); local c = 0; " +
            "parallel.waitForAll(function() for i = 1, 300 do local w = peripheral.call(n, 'getSize'); local w2 = s.getSize(); " +
            "if w == w2 then c = c + 1 end end end, function() os.sleep(0.1); c = c + 1000 end); print('pc,' .. c)"
        var typed = false
        helper.succeedWhen {
            val text = lines(screen.text)
            helper.assertTrue(case.wiring.bootFailure == null, "boot should not fail but was ${case.wiring.bootFailure}: $text")
            if (!typed) {
                helper.assertTrue(text.any { it == "$" }, "the shell prompt should be shown: $text")
                typeLine(helper, keyboard, "lua -e \"$script\"")
                typed = true
                helper.fail("typed the line")
            }
            helper.assertTrue(text.any { it == "pc,1300" }, "lua should call the screen inside parallel: $text")
            case.pressPowerButton()
            helper.setBlock(CASE, Blocks.AIR)
        }
    }
    /**
     * キーの押下のイベント（#326、ADR 0326）: 標準 OS の `/bin/lua` で `os.pullEvent('key-pressed')` を待っている間に
     * キーボードで `q` を押すと、キー名・修飾キー・繰り返しと、source（キーボードの address）が届く。`q` の押下は TTY に何も流さない
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun luaReceivesKeyEvents(helper: GameTestHelper) {
        val (case, screen, keyboard) = placeComputer(helper)
        // 最初の pullEvent でカーネルのキューが開くので、kready が出た時点でキーのイベントを受けられる
        val script = "os.queueEvent('r0'); os.pullEvent('r0'); print('kready'); local e; " +
            "repeat e = {os.pullEvent('key-pressed')} until e[3] == 'q'; " +
            "print(table.concat({'kp', e[3], e[4], tostring(e[5]), tostring(e[2] == peripheral.getName(peripheral.find('keyboard')))}, ','))"
        var step = 0
        helper.succeedWhen {
            val text = lines(screen.text)
            helper.assertTrue(case.wiring.bootFailure == null, "boot should not fail but was ${case.wiring.bootFailure}: $text")
            when (step) {
                0 -> {
                    helper.assertTrue(text.any { it == "$" }, "the shell prompt should be shown: $text")
                    typeLine(helper, keyboard, "lua -e \"$script\"")
                    step = 1
                    helper.fail("typed the line")
                }
                1 -> {
                    helper.assertTrue(text.any { it == "kready" }, "lua should be waiting for keys: $text")
                    for (packet in KeyboardEventCodec.encode(listOf(KeyboardEvent.KeyDown(Key.Q, 0, false)))) {
                        helper.assertTrue(keyboard.receive(UUID(0, 1), packet) == 1, "keyboard should accept the key")
                    }
                    step = 2
                    helper.fail("pressed q")
                }
            }
            helper.assertTrue(text.any { it == "kp,q,0,false,true" }, "lua should receive the key event: $text")
            case.pressPowerButton()
            helper.setBlock(CASE, Blocks.AIR)
        }
    }

    /**
     * キーのイベントを順に画面に出す Lua（#326）。`kready` が出たらキーのイベントを受けられる。`key-pressed`・`key-released` は
     * `ev,<名前>,<キー>`、キーボードの `device-detached`（Lua では `peripheral_detach`。ADR 0094）は `ev,detached`
     */
    private const val KEY_LOG_SCRIPT = "os.queueEvent('r0'); os.pullEvent('r0'); " +
        "local kn = peripheral.getName(peripheral.find('keyboard')); print('kready'); " +
        "while true do local e = {os.pullEvent()}; " +
        "if e[1] == 'key-pressed' or e[1] == 'key-released' then print('ev,' .. e[1] .. ',' .. e[3]) " +
        "elseif e[1] == 'peripheral_detach' and e[2] == kn then print('ev,detached') end end"

    /** 入力のセッションを開いて、[key] を押す（サーバーに届いた入力と同じ道。[KeyboardSessions]） */
    private fun pressThroughSession(helper: GameTestHelper, player: ServerPlayer, key: Key) {
        val abs = helper.absolutePos(KEYBOARD)
        player.moveTo(Vec3.atCenterOf(abs.east()))
        val session = KeyboardSessions.open(player, abs, null)
        helper.assertTrue(session != null, "should open a session")
        val packet = KeyboardEventCodec.encode(listOf(KeyboardEvent.KeyDown(key, 0, false))).single()
        helper.assertTrue(KeyboardSessions.receive(player, KeyboardInputPayload(session!!.id, packet)) == 1, "input should be accepted")
    }

    /**
     * キーの離しのイベント（#326、ADR 0326）: 押したままのキーのある入力の GUI を閉じると、合成した `key-released` が届く。
     * チャンクのアンロード（`onChunkUnloaded` は `setRemoved` より先に来る）では、`key-released` が `device-detached` の前に届く
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun luaReceivesSyntheticKeyReleases(helper: GameTestHelper) {
        val (case, screen, keyboard) = placeComputer(helper)
        val player = helper.makeMockServerPlayerInLevel()
        var step = 0
        helper.succeedWhen {
            val text = lines(screen.text)
            helper.assertTrue(case.wiring.bootFailure == null, "boot should not fail but was ${case.wiring.bootFailure}: $text")
            when (step) {
                0 -> {
                    helper.assertTrue(text.any { it == "$" }, "the shell prompt should be shown: $text")
                    typeLine(helper, keyboard, "lua -e \"$KEY_LOG_SCRIPT\"")
                    step = 1
                    helper.fail("typed the line")
                }
                1 -> {
                    helper.assertTrue(text.any { it == "kready" }, "lua should be waiting for keys: $text")
                    pressThroughSession(helper, player, Key.Q)
                    step = 2
                    helper.fail("pressed q")
                }
                2 -> {
                    helper.assertTrue(text.any { it == "ev,key-pressed,q" }, "q should be pressed: $text")
                    // GUI を閉じる（クライアントからの閉じる）
                    KeyboardSessions.closeFromClient(player, KeyboardSessions.sessionOf(player)!!.id)
                    step = 3
                    helper.fail("closed the session")
                }
                3 -> {
                    helper.assertTrue(text.any { it == "ev,key-released,q" }, "closing the session should release q: $text")
                    helper.assertTrue(text.none { it == "ev,detached" }, "the keyboard should still be connected: $text")
                    pressThroughSession(helper, player, Key.W)
                    step = 4
                    helper.fail("pressed w")
                }
                4 -> {
                    helper.assertTrue(text.any { it == "ev,key-pressed,w" }, "w should be pressed: $text")
                    keyboard.onChunkUnloaded()
                    step = 5
                    helper.fail("unloaded the keyboard")
                }
            }
            val released = text.indexOf("ev,key-released,w")
            val detached = text.indexOf("ev,detached")
            helper.assertTrue(released >= 0, "unloading should release w: $text")
            helper.assertTrue(detached > released, "device-detached should come after the release: $text")
            KeyboardSessions.loggedOut(player)
            case.pressPowerButton()
            helper.setBlock(CASE, Blocks.AIR)
        }
    }

    /**
     * キーボードのブロックを壊したとき（#326、ADR 0326）: 押したままのキーの離しが、`device-detached` の前に届く
     * （`setRemoved` が `isRemoved` を立てる前にセッションを閉じる）
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun luaReceivesReleasesBeforeDetachWhenKeyboardIsBroken(helper: GameTestHelper) {
        val (case, screen, keyboard) = placeComputer(helper)
        val player = helper.makeMockServerPlayerInLevel()
        var step = 0
        helper.succeedWhen {
            val text = lines(screen.text)
            helper.assertTrue(case.wiring.bootFailure == null, "boot should not fail but was ${case.wiring.bootFailure}: $text")
            when (step) {
                0 -> {
                    helper.assertTrue(text.any { it == "$" }, "the shell prompt should be shown: $text")
                    typeLine(helper, keyboard, "lua -e \"$KEY_LOG_SCRIPT\"")
                    step = 1
                    helper.fail("typed the line")
                }
                1 -> {
                    helper.assertTrue(text.any { it == "kready" }, "lua should be waiting for keys: $text")
                    pressThroughSession(helper, player, Key.Q)
                    step = 2
                    helper.fail("pressed q")
                }
                2 -> {
                    helper.assertTrue(text.any { it == "ev,key-pressed,q" }, "q should be pressed: $text")
                    helper.setBlock(KEYBOARD, Blocks.AIR)
                    step = 3
                    helper.fail("broke the keyboard")
                }
            }
            val released = text.indexOf("ev,key-released,q")
            val detached = text.indexOf("ev,detached")
            helper.assertTrue(released >= 0, "breaking the keyboard should release q: $text")
            helper.assertTrue(detached > released, "device-detached should come after the release: $text")
            helper.assertTrue(KeyboardSessions.sessionOf(player) == null, "breaking the keyboard should close the session")
            KeyboardSessions.loggedOut(player)
            case.pressPowerButton()
            helper.setBlock(CASE, Blocks.AIR)
        }
    }
}
