package pro.ikili.kibicomputers.neoforge.gametest

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestAssertException
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.InteractionHand
import net.minecraft.world.ItemInteractionResult
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.storage.loot.LootParams
import net.minecraft.world.level.storage.loot.parameters.LootContextParams
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.computer.CaseTier
import pro.ikili.kibicomputers.core.display.ScreenTier
import pro.ikili.kibicomputers.core.input.Key
import pro.ikili.kibicomputers.core.input.KeyModifiers
import pro.ikili.kibicomputers.core.input.KeyboardEvent
import pro.ikili.kibicomputers.core.input.KeyboardEventCodec
import pro.ikili.kibicomputers.core.input.KeyboardListener
import pro.ikili.kibicomputers.core.input.TerminalKeyEncoder
import pro.ikili.kibicomputers.neoforge.display.ScreenBlock
import pro.ikili.kibicomputers.neoforge.input.KeyboardBlock
import pro.ikili.kibicomputers.neoforge.input.KeyboardBlockEntity
import pro.ikili.kibicomputers.neoforge.input.KeyboardInput
import pro.ikili.kibicomputers.neoforge.input.KeyboardInputPayload
import pro.ikili.kibicomputers.neoforge.input.KeyboardSessions
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks
import java.util.UUID

/** キーボードブロックと入力（#142）の GameTest。 */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object KeyboardGameTests {
    private val POS = BlockPos(1, 1, 1)

    /** 置いたプレイヤーの方を向き、ツルハシで壊すと自身を落とす。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun keyboardFacesPlacerAndDropsItself(helper: GameTestHelper) {
        val block = KiBiBlocks.KEYBOARD.get()
        val player = helper.makeMockPlayer(GameType.CREATIVE)
        player.setYRot(0f) // 南（+Z）を向いている
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(block))
        helper.placeAt(player, ItemStack(block), POS.below(), Direction.UP)
        val state = helper.getBlockState(POS)
        helper.assertTrue(state.block == block, "keyboard should be placed but was ${state.block}")
        helper.assertTrue(state.getValue(KeyboardBlock.FACING) == Direction.NORTH, "keyboard should face the player")
        val params = LootParams.Builder(helper.level)
            .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(helper.absolutePos(POS)))
            .withParameter(LootContextParams.TOOL, ItemStack(Items.IRON_PICKAXE))
            .withOptionalParameter(LootContextParams.BLOCK_ENTITY, helper.getBlockEntity<KeyboardBlockEntity>(POS))
        val drops = state.getDrops(params)
        helper.assertTrue(drops.size == 1 && drops[0].`is`(block.asItem()), "keyboard should drop itself but dropped $drops")
        helper.succeed()
    }

    /**
     * 照準は、キーボードのあるブロックの床のどこを指しても、キーボードに当たる（#348）。
     * 見た目どおりの小さな形だと、置いた直後の照準（手前の端）が下のブロックに当たり、右クリックで GUI が開かなかった。
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun keyboardIsTargetedAcrossItsWholeFloor(helper: GameTestHelper) {
        helper.setBlock(POS.below(), Blocks.STONE)
        for (facing in Direction.Plane.HORIZONTAL) {
            helper.setBlock(POS, KiBiBlocks.KEYBOARD.get().defaultBlockState().setValue(KeyboardBlock.FACING, facing))
            val abs = helper.absolutePos(POS)
            for (dx in listOf(0.05, 0.5, 0.95)) {
                for (dz in listOf(0.05, 0.5, 0.95)) {
                    // 斜め上の手前から、床の点を狙う（テストの範囲の外に出ないよう近くから）
                    val target = Vec3(abs.x + dx, abs.y + 0.01, abs.z + dz)
                    val eye = target.add(0.0, 0.5, -0.5)
                    val to = target.add(target.subtract(eye).normalize().scale(0.5))
                    val hit = helper.level.clip(ClipContext(eye, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()))
                    helper.assertTrue(
                        hit.type == HitResult.Type.BLOCK && hit.blockPos == abs,
                        "aiming at ($dx, $dz) facing $facing should hit the keyboard but hit ${hit.blockPos} ${hit.type}",
                    )
                }
            }
        }
        // 右クリックすると使った扱いになる（手に何か持っていても、ブロックの右クリックに回る）
        val player = helper.makeMockPlayer(GameType.CREATIVE)
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.STONE))
        val abs = helper.absolutePos(POS)
        val state = helper.getBlockState(POS)
        val hit = BlockHitResult(Vec3.atCenterOf(abs), Direction.UP, abs, false)
        val itemResult = state.useItemOn(player.mainHandItem, helper.level, player, InteractionHand.MAIN_HAND, hit)
        helper.assertTrue(
            itemResult == ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION,
            "holding an item should fall through to the block interaction but was $itemResult",
        )
        val result = state.useWithoutItem(helper.level, player, hit)
        helper.assertTrue(result.consumesAction(), "right-clicking the keyboard should be handled but was $result")
        helper.succeed()
    }

    /** 入力 GUI に出す画面: 隣のケースの画面（テスト用コンピューター）か、このキーボードを見つける画面。なければ null。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun keyboardFindsItsScreen(helper: GameTestHelper) {
        val keyboard = BlockPos(1, 1, 2)
        helper.setBlock(keyboard, KiBiBlocks.KEYBOARD.get())
        val absKeyboard = helper.absolutePos(keyboard)
        fun find() = KeyboardInput.findScreen(helper.level, absKeyboard)
        helper.assertTrue(find() == null, "no screen yet")
        // 机の上のキーボード: 画面は1つ上の段で、正面がキーボードの方
        val screen = BlockPos(1, 2, 1)
        helper.setBlock(screen, KiBiBlocks.SCREENS.getValue(ScreenTier.COLOR).get().defaultBlockState().setValue(ScreenBlock.FACING, Direction.SOUTH))
        helper.assertTrue(find() == helper.absolutePos(screen), "screen above in front should be found but was ${find()}")
        // 背を向けた画面は、このキーボードを見つけないが、隣にあるので見つける
        helper.setBlock(screen, Blocks.AIR)
        val back = BlockPos(1, 1, 1)
        helper.setBlock(back, KiBiBlocks.SCREENS.getValue(ScreenTier.COLOR).get().defaultBlockState().setValue(ScreenBlock.FACING, Direction.NORTH))
        helper.assertTrue(find() == helper.absolutePos(back), "adjacent screen should be found but was ${find()}")
        helper.setBlock(back, Blocks.AIR)
        // テスト用コンピューター: キーボードの隣のケースの、上の画面
        helper.setBlock(back, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get())
        val caseScreen = BlockPos(1, 2, 1)
        helper.setBlock(caseScreen, KiBiBlocks.SCREENS.getValue(ScreenTier.COLOR).get().defaultBlockState().setValue(ScreenBlock.FACING, Direction.SOUTH))
        helper.assertTrue(find() == helper.absolutePos(caseScreen), "the case's screen should be found but was ${find()}")
        helper.succeed()
    }

    /** 画面の前・前の下・隣のキーボードを見つける。遠いもの・画面でないものは見つけない。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun screenFindsAdjacentKeyboard(helper: GameTestHelper) {
        val screen = BlockPos(1, 2, 1)
        val screenState = KiBiBlocks.SCREENS.getValue(ScreenTier.MONOCHROME).get().defaultBlockState()
            .setValue(ScreenBlock.FACING, Direction.SOUTH)
        helper.setBlock(screen, screenState)
        val absScreen = helper.absolutePos(screen)
        fun find() = KeyboardInput.findKeyboard(helper.level, absScreen)
        helper.assertTrue(find() == null, "no keyboard yet")
        // 正面の前の1つ下（机の上）
        val desk = screen.relative(Direction.SOUTH).below()
        helper.setBlock(desk, KiBiBlocks.KEYBOARD.get())
        helper.assertTrue(find() == helper.absolutePos(desk), "keyboard in front and below should be found but was ${find()}")
        helper.setBlock(desk, Blocks.AIR)
        // 横
        val side = screen.relative(Direction.EAST)
        helper.setBlock(side, KiBiBlocks.KEYBOARD.get())
        helper.assertTrue(find() == helper.absolutePos(side), "keyboard beside should be found")
        // 正面の前があれば、そちらを先に選ぶ
        val front = screen.relative(Direction.SOUTH)
        helper.setBlock(front, KiBiBlocks.KEYBOARD.get())
        helper.assertTrue(find() == helper.absolutePos(front), "keyboard in front should win")
        helper.setBlock(front, Blocks.AIR)
        helper.setBlock(side, Blocks.AIR)
        // 斜め上は対象外
        helper.setBlock(screen.relative(Direction.SOUTH).above(), KiBiBlocks.KEYBOARD.get())
        helper.assertTrue(find() == null, "diagonal keyboard above should not be found")
        // 画面でない位置からは探さない
        helper.assertTrue(KeyboardInput.findKeyboard(helper.level, helper.absolutePos(POS)) == null, "non-screen has no keyboard")
        helper.succeed()
    }

    /** 打った人と入力の組 */
    private data class Typed(val typist: UUID, val event: KeyboardEvent)

    private fun listen(keyboard: KeyboardBlockEntity, into: MutableList<Typed>): KeyboardListener {
        val listener = KeyboardListener { typist, event -> into += Typed(typist, event) }
        keyboard.claim(listener)
        return listener
    }

    private fun packet(vararg events: KeyboardEvent): ByteArray = KeyboardEventCodec.encode(events.toList()).single()

    /**
     * 右クリックがサーバーに届くと、入力のセッションを開く（#360）。セッションから届いたパケットを読んで、打った人を付けて配る。
     * 端末向けのバイト列にもできる。GUI を閉じると、押したままのキーの離しを合成する。
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun keyboardDeliversInputFromSession(helper: GameTestHelper) {
        helper.setBlock(POS, KiBiBlocks.KEYBOARD.get())
        val keyboard = helper.getBlockEntity<KeyboardBlockEntity>(POS)
        val received = ArrayList<Typed>()
        listen(keyboard, received)
        val player = helper.makeMockServerPlayerInLevel()
        player.moveTo(Vec3.atCenterOf(helper.absolutePos(POS.east())))
        val abs = helper.absolutePos(POS)
        val hit = BlockHitResult(Vec3.atCenterOf(abs), Direction.UP, abs, false)
        val result = helper.getBlockState(POS).useWithoutItem(helper.level, player, hit)
        helper.assertTrue(result.consumesAction(), "right-click should be handled but was $result")
        val session = KeyboardSessions.sessionOf(player) ?: throw GameTestAssertException("right-click should open a session")
        helper.assertTrue(session.typist == player.uuid, "the session should belong to the player")
        val events = listOf(
            KeyboardEvent.KeyDown(Key.H, KeyModifiers.NONE, false),
            KeyboardEvent.Text("hé日本"),
            KeyboardEvent.KeyUp(Key.H, KeyModifiers.NONE),
            KeyboardEvent.KeyDown(Key.C, KeyModifiers.CONTROL, false),
            KeyboardEvent.Paste("a\nb"),
            KeyboardEvent.KeyDown(Key.ENTER, KeyModifiers.NONE, false),
        )
        val n = KeyboardSessions.receive(player, KeyboardInputPayload(session.id, packet(*events.toTypedArray())))
        helper.assertTrue(n == events.size, "all events should be delivered but was $n")
        helper.assertTrue(received == events.map { Typed(player.uuid, it) }, "listener should receive the events in order but got $received")
        val bytes = String(TerminalKeyEncoder.encodeAll(received.map { it.event }), Charsets.UTF_8)
        helper.assertTrue(bytes == "hé日本\u0003a\rb\r", "terminal bytes were ${bytes.toByteArray().toList()}")
        // GUI を閉じると、押したままのキー（C と Enter）の離しを合成する
        received.clear()
        KeyboardSessions.closeFromClient(player, session.id)
        helper.assertTrue(KeyboardSessions.sessionOf(player) == null, "closing should end the session")
        helper.assertTrue(
            received.map { it.event } == listOf(KeyboardEvent.KeyUp(Key.C, KeyModifiers.NONE), KeyboardEvent.KeyUp(Key.ENTER, KeyModifiers.NONE)),
            "held keys should be released on close but got $received",
        )
        KeyboardSessions.loggedOut(player)
        helper.succeed()
    }

    /**
     * セッションのないパケット・番号の違うパケット・閉じたセッションのパケットは捨てる（#360）。遠いプレイヤーや、
     * キーボードでない位置には、セッションを開かない。壊れたパケットも捨てる。
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun keyboardRejectsInputWithoutSession(helper: GameTestHelper) {
        helper.setBlock(POS, KiBiBlocks.KEYBOARD.get())
        val keyboard = helper.getBlockEntity<KeyboardBlockEntity>(POS)
        val received = ArrayList<Typed>()
        listen(keyboard, received)
        val abs = helper.absolutePos(POS)
        val data = packet(KeyboardEvent.Text("x"))
        val player = helper.makeMockServerPlayerInLevel()
        player.moveTo(Vec3.atCenterOf(abs.east()))
        // セッションなし（GUI をクライアントだけで開いた、パケットを直接送った）
        helper.assertTrue(KeyboardSessions.receive(player, KeyboardInputPayload(1, data)) == -1, "input without a session should be rejected")
        // キーボードでない位置には開かない
        helper.assertTrue(KeyboardSessions.open(player, abs.above(), null) == null, "a session to a non-keyboard should not open")
        // 遠いと開かない
        player.moveTo(Vec3.atCenterOf(abs).add(0.0, 0.0, 30.0))
        helper.assertTrue(KeyboardSessions.open(player, abs, null) == null, "a far player should not open a session")
        player.moveTo(Vec3.atCenterOf(abs.east()))
        val session = KeyboardSessions.open(player, abs, null) ?: throw GameTestAssertException("a near player should open a session")
        // 番号が違う
        helper.assertTrue(KeyboardSessions.receive(player, KeyboardInputPayload(session.id + 1, data)) == -1, "a wrong session id should be rejected")
        // 壊れている
        helper.assertTrue(KeyboardSessions.receive(player, KeyboardInputPayload(session.id, byteArrayOf(9))) == -1, "malformed should be rejected")
        // ほかのプレイヤーは、人のセッションの番号を使えない
        val other = helper.makeMockServerPlayerInLevel()
        other.moveTo(Vec3.atCenterOf(abs.west()))
        helper.assertTrue(KeyboardSessions.receive(other, KeyboardInputPayload(session.id, data)) == -1, "another player's session should be rejected")
        helper.assertTrue(received.isEmpty(), "nothing should be delivered but got $received")
        // 閉じたあとに届いた古いパケット
        KeyboardSessions.closeFromClient(player, session.id)
        helper.assertTrue(KeyboardSessions.receive(player, KeyboardInputPayload(session.id, data)) == -1, "a closed session should be rejected")
        helper.assertTrue(received.isEmpty(), "nothing should be delivered but got $received")
        KeyboardSessions.loggedOut(player)
        KeyboardSessions.loggedOut(other)
        helper.succeed()
    }

    /** 受け取る量の上限は、プレイヤーとキーボードの組ごと（#360）。1人が使い切っても、ほかの人の入力は通る。開き直しても戻らない。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun keyboardBudgetIsPerPlayer(helper: GameTestHelper) {
        helper.setBlock(POS, KiBiBlocks.KEYBOARD.get())
        val keyboard = helper.getBlockEntity<KeyboardBlockEntity>(POS)
        listen(keyboard, ArrayList())
        val abs = helper.absolutePos(POS)
        val flooder = helper.makeMockServerPlayerInLevel()
        flooder.moveTo(Vec3.atCenterOf(abs.east()))
        val typist = helper.makeMockServerPlayerInLevel()
        typist.moveTo(Vec3.atCenterOf(abs.west()))
        val flood = KeyboardSessions.open(flooder, abs, null) ?: throw GameTestAssertException("flooder should open a session")
        val typing = KeyboardSessions.open(typist, abs, null) ?: throw GameTestAssertException("typist should open a session")
        // 同じ tick に送りすぎると、上限を超えた分は捨てる
        val big = packet(KeyboardEvent.Paste("y".repeat(KeyboardEventCodec.MAX_TEXT_BYTES)))
        var accepted = 0
        repeat(10) { if (KeyboardSessions.receive(flooder, KeyboardInputPayload(flood.id, big)) > 0) accepted++ }
        helper.assertTrue(accepted in 1..9, "budget should accept some and reject the rest but accepted $accepted")
        helper.assertTrue(KeyboardSessions.receive(typist, KeyboardInputPayload(typing.id, big)) > 0, "another player's input should still be accepted")
        // 開き直しても、使い切った分は戻らない
        val reopened = KeyboardSessions.open(flooder, abs, null) ?: throw GameTestAssertException("flooder should reopen")
        helper.assertTrue(reopened.id != flood.id, "reopening should make a new session")
        helper.assertTrue(KeyboardSessions.receive(flooder, KeyboardInputPayload(reopened.id, big)) == -1, "reopening should not refill the budget")
        KeyboardSessions.loggedOut(flooder)
        KeyboardSessions.loggedOut(typist)
        helper.succeed()
    }

    /** 届く距離を外れたら、セッションを閉じ、押したままのキーの離しを合成する（#360）。ログアウト・キーボードが壊れたときも閉じる。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun sessionClosesWhenPlayerLeaves(helper: GameTestHelper) {
        helper.setBlock(POS, KiBiBlocks.KEYBOARD.get())
        val keyboard = helper.getBlockEntity<KeyboardBlockEntity>(POS)
        val received = ArrayList<Typed>()
        listen(keyboard, received)
        val abs = helper.absolutePos(POS)
        val player = helper.makeMockServerPlayerInLevel()
        player.moveTo(Vec3.atCenterOf(abs.east()))
        val session = KeyboardSessions.open(player, abs, null) ?: throw GameTestAssertException("should open a session")
        val down = KeyboardEvent.KeyDown(Key.A, KeyModifiers.NONE, false)
        helper.assertTrue(KeyboardSessions.receive(player, KeyboardInputPayload(session.id, packet(down))) == 1, "input should be accepted")
        // 離れる
        player.moveTo(Vec3.atCenterOf(abs).add(0.0, 0.0, 30.0))
        KeyboardSessions.tick()
        helper.assertTrue(KeyboardSessions.sessionOf(player) == null, "leaving should close the session")
        helper.assertTrue(
            received == listOf(Typed(player.uuid, down), Typed(player.uuid, KeyboardEvent.KeyUp(Key.A, KeyModifiers.NONE))),
            "the held key should be released but got $received",
        )
        helper.assertTrue(KeyboardSessions.receive(player, KeyboardInputPayload(session.id, packet(down))) == -1, "input after leaving should be rejected")
        // ログアウト
        player.moveTo(Vec3.atCenterOf(abs.east()))
        val again = KeyboardSessions.open(player, abs, null) ?: throw GameTestAssertException("should reopen")
        received.clear()
        KeyboardSessions.receive(player, KeyboardInputPayload(again.id, packet(KeyboardEvent.KeyDown(Key.B, KeyModifiers.NONE, false))))
        KeyboardSessions.loggedOut(player)
        helper.assertTrue(KeyboardSessions.sessionOf(player) == null, "logging out should close the session")
        helper.assertTrue(
            received.lastOrNull() == Typed(player.uuid, KeyboardEvent.KeyUp(Key.B, KeyModifiers.NONE)),
            "logging out should release keys but got $received",
        )
        // キーボードが壊れたら閉じる
        helper.assertTrue(KeyboardSessions.open(player, abs, null) != null, "should reopen")
        helper.setBlock(POS, Blocks.AIR)
        KeyboardSessions.tick()
        helper.assertTrue(KeyboardSessions.sessionOf(player) == null, "breaking the keyboard should close the session")
        KeyboardSessions.loggedOut(player)
        helper.succeed()
    }

    /** 1つのキーボードにつながる相手は1つだけ（#360）。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun keyboardHasSingleOwner(helper: GameTestHelper) {
        helper.setBlock(POS, KiBiBlocks.KEYBOARD.get())
        val keyboard = helper.getBlockEntity<KeyboardBlockEntity>(POS)
        val first = KeyboardListener { _, _ -> }
        val second = KeyboardListener { _, _ -> }
        helper.assertTrue(keyboard.claim(first), "the first listener should be connected")
        helper.assertTrue(keyboard.claim(first), "claiming again should keep it")
        helper.assertTrue(!keyboard.claim(second), "the second listener should be refused")
        keyboard.release(second)
        helper.assertTrue(keyboard.owner === first, "releasing someone else should not change the owner")
        keyboard.release(first)
        helper.assertTrue(keyboard.claim(second), "after release, another listener can connect")
        helper.succeed()
    }
}
