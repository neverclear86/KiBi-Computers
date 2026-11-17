package pro.ikili.kibicomputers.neoforge.gametest

import com.mojang.authlib.GameProfile
import io.netty.channel.embedded.EmbeddedChannel
import net.minecraft.commands.CommandSource
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.gametest.framework.GameTestInfo
import net.minecraft.gametest.framework.GameTestListener
import net.minecraft.gametest.framework.GameTestRunner
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.IntTag
import net.minecraft.network.Connection
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.contents.TranslatableContents
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.util.FakePlayerFactory
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.access.AccessRequest
import pro.ikili.kibicomputers.core.access.AccessRequestKind
import pro.ikili.kibicomputers.core.access.AccessRequestLimiter
import pro.ikili.kibicomputers.core.computer.CaseTier
import pro.ikili.kibicomputers.neoforge.computer.AccessRequests
import pro.ikili.kibicomputers.neoforge.computer.CaseAccess
import pro.ikili.kibicomputers.neoforge.computer.CaseBlock
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
import pro.ikili.kibicomputers.neoforge.input.KeyboardSessions
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks
import java.util.IdentityHashMap
import java.util.UUID

/**
 * OS からの持ち主・メンバーの変更の依頼（#110、ADR 0110）の GameTest: 届け・知らせ・`/kibicomputers access approve|deny|list`。
 *
 * 依頼は、OS の `request-*` と同じく、枠を取って（`tryReserve`）からポートの届けを呼んで出す（ゲストの側は core の WasiP1AccessTest・
 * BasicCommandsTest が確かめる）。プレイヤーは名前を指定して作り、OP は `hasPermissions` を上書きする。チャットは
 * `sendSystemMessage` を上書きして拾う
 */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object AccessRequestGameTests {
    private val CASE = BlockPos(1, 1, 1)
    private val OTHER_CASE = BlockPos(1, 1, 0)
    private val cleanups = IdentityHashMap<GameTestInfo, MutableList<() -> Unit>>()

    /** チャット（システムメッセージ）を拾うプレイヤー */
    internal class Listener(server: net.minecraft.server.MinecraftServer, level: ServerLevel, profile: GameProfile, info: net.minecraft.server.level.ClientInformation, private val op: Boolean) :
        ServerPlayer(server, level, profile, info) {
        val messages = ArrayList<Component>()

        override fun hasPermissions(permissionLevel: Int): Boolean = op

        /** この MOD の依頼の文言だけを拾う（同時に動くほかのテストのプレイヤーの参加・退出などは拾わない） */
        override fun sendSystemMessage(component: Component, bypassHiddenChat: Boolean) {
            if (keysOf(component).any { it.startsWith(ACCESS_KEY_PREFIX) }) messages += component
        }

        fun clear() = messages.clear()

        /** 拾った文言の翻訳キー（入れ子・引数の中も） */
        fun keys(): List<String> = messages.flatMap(::keysOf)

        /** `list` に出た依頼の ID */
        fun listedIds(): List<Long> = messages.flatMap { m ->
            ((m.contents as? TranslatableContents)?.takeIf { it.key == AccessRequests.KEY_LIST_ENTRY }?.args?.firstOrNull() as? Long)?.let { listOf(it) } ?: emptyList()
        }
    }

    private const val ACCESS_KEY_PREFIX = "message.kibicomputers.access."

    /** 名前 [name] のプレイヤーをサーバーに入れる */
    internal fun player(helper: GameTestHelper, name: String, op: Boolean = false): Listener {
        val cookie = CommonListenerCookie.createInitial(GameProfile(UUID.randomUUID(), name), false)
        val level = helper.level
        val player = Listener(level.server, level, cookie.gameProfile(), cookie.clientInformation(), op)
        val connection = Connection(PacketFlow.SERVERBOUND)
        EmbeddedChannel(connection)
        level.server.playerList.placeNewPlayer(connection, player, cookie)
        onEnd(helper) {
            KeyboardSessions.loggedOut(player)
            level.server.playerList.remove(player)
        }
        player.moveTo(Vec3.atCenterOf(helper.absolutePos(CASE.east())))
        player.clear()
        return player
    }

    internal fun onEnd(helper: GameTestHelper, action: () -> Unit) {
        val info = helper.testInfo
        val list = cleanups.getOrPut(info) {
            info.addListener(
                object : GameTestListener {
                    override fun testStructureLoaded(info: GameTestInfo) {}

                    override fun testPassed(info: GameTestInfo, runner: GameTestRunner) = runCleanups(info)

                    override fun testFailed(info: GameTestInfo, runner: GameTestRunner) = runCleanups(info)

                    override fun testAddedForRerun(old: GameTestInfo, new: GameTestInfo, runner: GameTestRunner) {}
                },
            )
            ArrayList()
        }
        list += action
    }

    private fun runCleanups(info: GameTestInfo) {
        val actions = cleanups.remove(info) ?: return
        for (action in actions.asReversed()) {
            try {
                action()
            } catch (e: Exception) {
                System.getLogger("kibicomputers.gametest").log(System.Logger.Level.WARNING, "GameTest cleanup failed", e)
            }
        }
    }

    internal fun keysOf(c: Component): List<String> {
        val out = ArrayList<String>()
        fun walk(x: Component) {
            (x.contents as? TranslatableContents)?.let { t ->
                out += t.key
                for (a in t.args) if (a is Component) walk(a)
            }
            x.siblings.forEach(::walk)
        }
        walk(c)
        return out
    }

    /** [c] の中の、クリックで走るコマンド */
    internal fun commandsOf(c: Component): List<String> {
        val out = ArrayList<String>()
        fun walk(x: Component) {
            x.style.clickEvent?.takeIf { it.action == ClickEvent.Action.RUN_COMMAND }?.let { out += it.value }
            (x.contents as? TranslatableContents)?.args?.forEach { if (it is Component) walk(it) }
            x.siblings.forEach(::walk)
        }
        walk(c)
        return out
    }

    private fun placeBy(helper: GameTestHelper, placer: ServerPlayer?, pos: BlockPos = CASE): CaseBlockEntity {
        helper.setBlock(pos, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get())
        val be = helper.getBlockEntity<CaseBlockEntity>(pos)
        val abs = helper.absolutePos(pos)
        val state = helper.level.getBlockState(abs)
        (state.block as CaseBlock).setPlacedBy(helper.level, abs, state, placer as LivingEntity?, ItemStack(state.block))
        return be
    }

    /** OS の `request-*` と同じく、枠を取ってから届ける。表に載せた ID（捨てたら `null`） */
    internal fun request(be: CaseBlockEntity, kind: AccessRequestKind, argument: String): Long? {
        val r = AccessRequest.parse(kind, argument)!!
        val reserved = be.accessPort.limiter.tryReserve(r, System.currentTimeMillis(), be.accessPort.requestRules())
        check(reserved == AccessRequestLimiter.Reserve.ACCEPTED) { "the slot should be reserved but was $reserved" }
        return AccessRequests.deliver(be, r)
    }

    internal fun command(helper: GameTestHelper, player: ServerPlayer, command: String) {
        helper.level.server.commands.performPrefixedCommand(player.createCommandSourceStack(), "kibicomputers access $command")
    }

    /** 依頼 → 持ち主に承認・拒否のボタン付きの知らせ → 持ち主の approve で保留に入り、本人のアクセスでメンバーになる。枠も返る */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun ownerApprovesAnAddRequest(helper: GameTestHelper) {
        val alex = player(helper, "Alex110a")
        val bob = player(helper, "Bob110a")
        val be = placeBy(helper, alex)
        val id = request(be, AccessRequestKind.ADD, "Bob110a")!!
        helper.assertTrue(AccessRequests.KEY_REQUEST_ADD in alex.keys(), "the owner should be told: ${alex.messages}")
        val commands = alex.messages.flatMap(::commandsOf)
        helper.assertTrue(
            commands == listOf("/kibicomputers access approve $id", "/kibicomputers access deny $id"),
            "the message should carry approve and deny buttons: $commands",
        )
        helper.assertTrue(bob.messages.isEmpty(), "only the owner should be told")
        command(helper, alex, "approve $id")
        helper.assertTrue(AccessRequests.KEY_APPROVED_ADD in alex.keys(), "the owner should see the result: ${alex.messages}")
        helper.assertTrue(be.playerAccess.pending.map { it.name } == listOf("bob110a"), "Bob should be pending: ${be.playerAccess}")
        helper.assertTrue(be.playerAccess.members.isEmpty(), "an approved add should not add a member directly")
        helper.assertTrue(AccessRequests.peek(id) == null, "the request should be taken")
        helper.assertTrue(be.accessPort.limiter.size == 0, "the slot should be released")
        helper.assertTrue(be.touchAccess(bob), "Bob should be promoted on access")
        helper.assertTrue(be.playerAccess.members.any { it.id == bob.uuid }, "Bob should be a member: ${be.playerAccess}")
        helper.succeed()
    }

    /**
     * 他人・FakePlayer の approve・deny は、「依頼がない」と同じ文言で断られ、依頼も枠も残る。その後、OP は承認できる。
     * 持ち主が変わった後の元の持ち主の承認も断られる
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun onlyManagersDecide(helper: GameTestHelper) {
        val alex = player(helper, "Alex110b")
        val stranger = player(helper, "Stranger110b")
        val op = player(helper, "Op110b", op = true)
        val be = placeBy(helper, alex)
        val id = request(be, AccessRequestKind.ADD, "Bob110b")!!
        val before = be.playerAccess
        command(helper, stranger, "approve $id")
        command(helper, stranger, "deny $id")
        command(helper, stranger, "approve 999999")
        val keys = stranger.keys()
        helper.assertTrue(keys == List(3) { AccessRequests.KEY_UNKNOWN }, "a stranger should get the unknown message each time: $keys")
        val fake = FakePlayerFactory.get(helper.level, GameProfile(UUID.randomUUID(), "[kibi-fake-110]"))
        helper.level.server.commands.performPrefixedCommand(fake.createCommandSourceStack(), "kibicomputers access approve $id")
        helper.level.server.commands.performPrefixedCommand(fake.createCommandSourceStack(), "kibicomputers access deny $id")
        helper.assertTrue(be.playerAccess === before, "nothing should change: ${be.playerAccess}")
        helper.assertTrue(AccessRequests.peek(id) != null, "the request should stay")
        helper.assertTrue(be.accessPort.limiter.size == 1, "the slot should stay")
        command(helper, op, "approve $id")
        helper.assertTrue(AccessRequests.KEY_APPROVED_ADD in op.keys(), "an operator should approve: ${op.messages}")
        helper.assertTrue(be.playerAccess.pending.map { it.name } == listOf("bob110b"), "${be.playerAccess}")

        // 持ち主が変わった後の、元の持ち主の承認
        val id2 = request(be, AccessRequestKind.ADD, "Carl110b")!!
        be.replacePlayerAccess(be.playerAccess.transferOwner(CaseAccess.ref(stranger)))
        alex.clear()
        command(helper, alex, "approve $id2")
        helper.assertTrue(alex.keys() == listOf(AccessRequests.KEY_UNKNOWN), "the old owner should be refused: ${alex.messages}")
        helper.assertTrue(AccessRequests.peek(id2) != null, "the request should stay")
        stranger.clear()
        command(helper, stranger, "approve $id2")
        helper.assertTrue(AccessRequests.KEY_APPROVED_ADD in stranger.keys(), "the new owner should approve: ${stranger.messages}")
        helper.succeed()
    }

    /** 壊して置き直した後の古い依頼は無効で、表から捨てる（新しいケースには当てない） */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun replacedCaseInvalidatesRequests(helper: GameTestHelper) {
        val alex = player(helper, "Alex110c")
        val be = placeBy(helper, alex)
        val id = request(be, AccessRequestKind.ADD, "Bob110c")!!
        helper.setBlock(CASE, Blocks.AIR)
        val fresh = placeBy(helper, alex)
        helper.assertTrue(fresh !== be, "the case should be a new block entity")
        alex.clear()
        command(helper, alex, "approve $id")
        helper.assertTrue(alex.keys() == listOf(AccessRequests.KEY_UNKNOWN), "the old request should be gone: ${alex.messages}")
        helper.assertTrue(AccessRequests.peek(id) == null, "the invalid request should be dropped")
        helper.assertTrue(fresh.playerAccess.pending.isEmpty(), "the new case should not change: ${fresh.playerAccess}")
        helper.succeed()
    }

    /** 持ち主のいないケースの持ち主の変更は、相手本人に知らせ、相手本人だけが承認・拒否できる（OP も代われない） */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun unownedTransferIsDecidedByTheTarget(helper: GameTestHelper) {
        val target = player(helper, "Target110d")
        val op = player(helper, "Op110d", op = true)
        val be = placeBy(helper, null)
        helper.assertTrue(!be.playerAccess.hasOwner, "the case should be ownerless")
        // 持ち主なしの追加・削除は届けで捨て、枠を返す
        helper.assertTrue(request(be, AccessRequestKind.ADD, "Bob110d") == null, "an ownerless add should be dropped")
        helper.assertTrue(request(be, AccessRequestKind.REMOVE, "Bob110d") == null, "an ownerless remove should be dropped")
        helper.assertTrue(request(be, AccessRequestKind.TRANSFER, "Offline110d") == null, "a transfer to an offline player should be dropped")
        helper.assertTrue(be.accessPort.limiter.size == 0, "dropped requests should release their slots")
        val id = request(be, AccessRequestKind.TRANSFER, "target110d")!!
        helper.assertTrue(AccessRequests.KEY_REQUEST_TRANSFER in target.keys(), "the target should be told: ${target.messages}")
        command(helper, op, "approve $id")
        command(helper, op, "deny $id")
        helper.assertTrue(op.keys() == List(2) { AccessRequests.KEY_UNKNOWN }, "an operator should not decide for the target: ${op.messages}")
        helper.assertTrue(AccessRequests.peek(id) != null && !be.playerAccess.hasOwner, "the request should stay")
        command(helper, target, "approve $id")
        helper.assertTrue(be.playerAccess.owner?.id == target.uuid, "the target should own the case: ${be.playerAccess}")
        helper.succeed()
    }

    /** 読めなかった記録のケースの持ち主の変更は、だれにも知らせず、OP だけが扱える（元の持ち主の deny も断る） */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun lockedTransferIsDecidedByOperators(helper: GameTestHelper) {
        val alex = player(helper, "Alex110e")
        val target = player(helper, "Target110e")
        val op = player(helper, "Op110e", op = true)
        val be = placeBy(helper, alex)
        val tag = be.saveWithoutMetadata(helper.level.registryAccess())
        tag.put(CaseBlockEntity.PLAYER_ACCESS_TAG, CompoundTag().apply { put("Version", IntTag.valueOf(99)) })
        be.loadWithComponents(tag, helper.level.registryAccess())
        helper.assertTrue(be.playerAccess.locked, "the case should be locked")
        helper.assertTrue(request(be, AccessRequestKind.ADD, "Bob110e") == null, "a locked add should be dropped")
        val id = request(be, AccessRequestKind.TRANSFER, "Target110e")!!
        helper.assertTrue(alex.messages.isEmpty() && target.messages.isEmpty() && op.messages.isEmpty(), "nobody should be told")
        command(helper, alex, "deny $id")
        command(helper, target, "approve $id")
        helper.assertTrue(alex.keys() == listOf(AccessRequests.KEY_UNKNOWN) && target.keys() == listOf(AccessRequests.KEY_UNKNOWN), "only operators decide")
        command(helper, op, "list")
        helper.assertTrue(AccessRequests.KEY_LIST_ENTRY in op.keys(), "an operator should see it in the list: ${op.messages}")
        command(helper, op, "approve $id")
        helper.assertTrue(!be.playerAccess.locked && be.playerAccess.owner?.id == target.uuid, "the operator should fix the owner: ${be.playerAccess}")
        helper.succeed()
    }

    /**
     * 持ち主の deny は表から外し、反映せず、枠は期限まで占める（同じ依頼はまとめられる）。上限まで依頼すると次は断られ、
     * 1件を承認すると次の依頼が通る
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun denyKeepsTheSlotAndApproveReleasesIt(helper: GameTestHelper) {
        val alex = player(helper, "Alex110f")
        val be = placeBy(helper, alex)
        val rules = be.accessPort.requestRules()
        val denied = request(be, AccessRequestKind.ADD, "Den110f")!!
        command(helper, alex, "deny $denied")
        helper.assertTrue(AccessRequests.KEY_DENIED in alex.keys(), "the owner should see the denial: ${alex.messages}")
        helper.assertTrue(AccessRequests.peek(denied) == null, "the denied request should be taken")
        helper.assertTrue(be.playerAccess.pending.isEmpty(), "a denied request should not change anything")
        val again = be.accessPort.limiter.tryReserve(AccessRequest.parse(AccessRequestKind.ADD, "den110f")!!, System.currentTimeMillis(), rules)
        helper.assertTrue(again == AccessRequestLimiter.Reserve.MERGED, "the denied request should hold its slot: $again")
        val ids = (1 until rules.maxOutstanding).map { request(be, AccessRequestKind.ADD, "Fill${it}x110f")!! }
        val full = be.accessPort.limiter.tryReserve(AccessRequest.parse(AccessRequestKind.ADD, "More110f")!!, System.currentTimeMillis(), rules)
        helper.assertTrue(full == AccessRequestLimiter.Reserve.FULL, "the limit should be reached: $full")
        command(helper, alex, "approve ${ids.first()}")
        helper.assertTrue(request(be, AccessRequestKind.ADD, "More110f") != null, "approving should free a slot")
        helper.succeed()
    }

    /** `list` には、実行者が扱える依頼だけが出る */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun listShowsOnlyDecidableRequests(helper: GameTestHelper) {
        val alex = player(helper, "Alex110g")
        val steve = player(helper, "Steve110g")
        val mine = request(placeBy(helper, alex), AccessRequestKind.ADD, "Bob110g")!!
        val theirs = request(placeBy(helper, steve, OTHER_CASE), AccessRequestKind.ADD, "Carl110g")!!
        alex.clear()
        command(helper, alex, "list")
        val listed = alex.listedIds()
        helper.assertTrue(listed == listOf(mine), "only Alex's request should be listed ($mine, not $theirs): $listed ${alex.messages}")
        helper.succeed()
    }

    /** 持ち主のいる（owned）ケースの持ち主の変更は、持ち主に知らせ、届けで相手を固定し、持ち主の approve で持ち主が替わる（元の持ち主はメンバーに残らない） */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun ownedTransferNotifiesTheOwnerAndPinsTheTarget(helper: GameTestHelper) {
        val alex = player(helper, "Alex110h")
        val target = player(helper, "Target110h")
        val be = placeBy(helper, alex)
        val id = request(be, AccessRequestKind.TRANSFER, "target110h")!!
        helper.assertTrue(AccessRequests.KEY_REQUEST_TRANSFER in alex.keys(), "the owner should be told: ${alex.messages}")
        helper.assertTrue(alex.messages.flatMap(::commandsOf) == listOf("/kibicomputers access approve $id", "/kibicomputers access deny $id"), "the message should carry the buttons")
        helper.assertTrue(target.messages.isEmpty(), "the target should not be told while the case has an owner: ${target.messages}")
        helper.assertTrue(AccessRequests.peek(id)?.target?.id == target.uuid, "the target should be pinned at delivery")
        // 相手本人は、持ち主のいるケースの依頼を決められない
        command(helper, target, "approve $id")
        helper.assertTrue(target.keys() == listOf(AccessRequests.KEY_UNKNOWN) && AccessRequests.peek(id) != null, "the target should not decide: ${target.messages}")
        command(helper, alex, "approve $id")
        helper.assertTrue(AccessRequests.KEY_APPROVED_TRANSFER in alex.keys(), "the owner should see the result: ${alex.messages}")
        helper.assertTrue(be.playerAccess.owner?.id == target.uuid, "the target should own the case: ${be.playerAccess}")
        helper.assertTrue(be.playerAccess.members.none { it.id == alex.uuid }, "the old owner should not stay a member: ${be.playerAccess}")
        helper.assertTrue(be.accessPort.limiter.size == 0, "the slot should be released")
        helper.succeed()
    }

    /**
     * 名前での remove は、届けのときにその名前のオンラインのプレイヤーを `target` に固定し、approve でそのメンバーも外れる。
     * オンラインにいない名前なら `target` はなく、保留だけが対象になる
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun removeByNamePinsTheOnlinePlayer(helper: GameTestHelper) {
        val alex = player(helper, "Alex110i")
        val steve = player(helper, "Steve110i")
        val be = placeBy(helper, alex)
        val now = System.currentTimeMillis()
        val rules = CaseAccess.rules()
        be.replacePlayerAccess(be.playerAccess.addPending("Steve110i", now, rules).access.onAccess(CaseAccess.ref(steve), now, rules).addPending("Gone110i", now, rules).access)
        helper.assertTrue(be.playerAccess.members.any { it.id == steve.uuid }, "Steve should be a member: ${be.playerAccess}")
        val byName = request(be, AccessRequestKind.REMOVE, "steve110i")!!
        val offline = request(be, AccessRequestKind.REMOVE, "Gone110i")!!
        helper.assertTrue(AccessRequests.peek(byName)?.target?.id == steve.uuid, "the online player should be pinned")
        helper.assertTrue(AccessRequests.peek(offline)?.target == null, "an offline name should have no target")
        command(helper, alex, "approve $byName")
        helper.assertTrue(AccessRequests.KEY_APPROVED_REMOVE in alex.keys(), "the owner should see the result: ${alex.messages}")
        helper.assertTrue(be.playerAccess.members.isEmpty(), "the online member should be removed: ${be.playerAccess}")
        command(helper, alex, "approve $offline")
        helper.assertTrue(be.playerAccess.pending.isEmpty(), "the pending entry should be removed: ${be.playerAccess}")
        helper.succeed()
    }

    /** 変わらなかった承認（NO_CHANGE）は、表から外し、枠を返し、理由の文言を返す。すでに持ち主への transfer も同じ */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun approvingANoChangeRequestReleasesTheSlot(helper: GameTestHelper) {
        val alex = player(helper, "Alex110j")
        val be = placeBy(helper, alex)
        val first = request(be, AccessRequestKind.ADD, "Bob110j")!!
        command(helper, alex, "approve $first")
        helper.assertTrue(AccessRequests.KEY_APPROVED_ADD in alex.keys(), "the first add should apply: ${alex.messages}")
        val second = request(be, AccessRequestKind.ADD, "Bob110j")!!
        alex.clear()
        command(helper, alex, "approve $second")
        val keys = alex.keys()
        helper.assertTrue(AccessRequests.KEY_UNCHANGED in keys && AccessRequests.KEY_REASON_PREFIX + "already_present" in keys, "the reason should be shown: $keys")
        helper.assertTrue(AccessRequests.peek(second) == null, "the request should be taken")
        helper.assertTrue(be.accessPort.limiter.size == 0, "the slot should be released")
        helper.assertTrue(be.playerAccess.pending.size == 1, "nothing should change: ${be.playerAccess}")
        // すでに持ち主への transfer
        val self = request(be, AccessRequestKind.TRANSFER, "Alex110j")!!
        alex.clear()
        command(helper, alex, "approve $self")
        helper.assertTrue(alex.keys() == listOf(AccessRequests.KEY_UNCHANGED, AccessRequests.KEY_REASON_PREFIX + "already_present"), "a transfer to the owner should change nothing: ${alex.messages}")
        helper.assertTrue(AccessRequests.peek(self) == null && be.accessPort.limiter.size == 0, "the request should be taken and the slot released")
        helper.succeed()
    }

    /** プレイヤーでない実行者（コンソール）は、approve・deny・list のどれも `players_only` で断られ、表は変わらない */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun consoleCannotDecide(helper: GameTestHelper) {
        val alex = player(helper, "Alex110k")
        val be = placeBy(helper, alex)
        val id = request(be, AccessRequestKind.ADD, "Bob110k")!!
        val captured = ArrayList<Component>()
        val source = object : CommandSource {
            override fun sendSystemMessage(component: Component) {
                captured += component
            }

            override fun acceptsSuccess(): Boolean = true

            override fun acceptsFailure(): Boolean = true

            override fun shouldInformAdmins(): Boolean = false
        }
        val console = helper.level.server.createCommandSourceStack().withSource(source)
        for (c in listOf("approve $id", "deny $id", "list")) {
            helper.level.server.commands.performPrefixedCommand(console, "kibicomputers access $c")
        }
        val keys = captured.flatMap(::keysOf)
        helper.assertTrue(keys == List(3) { AccessRequests.KEY_PLAYERS_ONLY }, "the console should be refused: $keys")
        helper.assertTrue(AccessRequests.peek(id) != null && be.playerAccess.pending.isEmpty() && be.accessPort.limiter.size == 1, "nothing should change")
        helper.succeed()
    }

    /** OP の `list` には、持ち主のいないケースの持ち主の変更が出ない（相手本人にだけ見せる）。相手本人の `list` には出る */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun listHidesAnUnownedTransferFromOperators(helper: GameTestHelper) {
        val target = player(helper, "Target110l")
        val op = player(helper, "Op110l", op = true)
        val be = placeBy(helper, null)
        helper.assertTrue(!be.playerAccess.hasOwner, "the case should be ownerless")
        val id = request(be, AccessRequestKind.TRANSFER, "Target110l")!!
        command(helper, op, "list")
        helper.assertTrue(id !in op.listedIds(), "an operator should not see it: ${op.messages}")
        command(helper, target, "list")
        helper.assertTrue(id in target.listedIds(), "the target should see it: ${target.messages}")
        helper.succeed()
    }
}
