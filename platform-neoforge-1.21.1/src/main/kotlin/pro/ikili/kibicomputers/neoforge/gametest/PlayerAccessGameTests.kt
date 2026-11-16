package pro.ikili.kibicomputers.neoforge.gametest

import com.mojang.authlib.GameProfile
import io.netty.channel.embedded.EmbeddedChannel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestAssertException
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.gametest.framework.GameTestInfo
import net.minecraft.gametest.framework.GameTestListener
import net.minecraft.gametest.framework.GameTestRunner
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.IntTag
import net.minecraft.network.Connection
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.item.PrimedTnt
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.GameType
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.util.FakePlayerFactory
import net.neoforged.neoforge.event.EventHooks
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.access.AccessRules
import pro.ikili.kibicomputers.core.access.PendingInvite
import pro.ikili.kibicomputers.core.access.PlayerAccess
import pro.ikili.kibicomputers.core.access.PlayerAccessRecord
import pro.ikili.kibicomputers.core.access.PlayerRef
import pro.ikili.kibicomputers.core.computer.CaseTier
import pro.ikili.kibicomputers.core.computer.ComputerStatus
import pro.ikili.kibicomputers.core.computer.PartKind
import pro.ikili.kibicomputers.core.input.KeyboardEvent
import pro.ikili.kibicomputers.core.input.KeyboardEventCodec
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.neoforge.computer.CaseAccess
import pro.ikili.kibicomputers.neoforge.computer.CaseBlock
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
import pro.ikili.kibicomputers.neoforge.computer.CaseMenu
import pro.ikili.kibicomputers.neoforge.computer.PlayerAccessNbt
import pro.ikili.kibicomputers.neoforge.input.KeyboardInputPayload
import pro.ikili.kibicomputers.neoforge.input.KeyboardSessions
import pro.ikili.kibicomputers.neoforge.machine.ServerMachines
import pro.ikili.kibicomputers.neoforge.machine.WorldMachineSaves
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks
import java.nio.file.Files
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID

/**
 * 持ち主と権限（#131、ADR 0131）の GameTest: 設置、GUI、破壊、爆発、mob の破壊、NBT、保留の昇格、キーボードのセッション。
 *
 * プレイヤーは名前を指定して作る（`makeMockServerPlayerInLevel` は名前が固定）。OP は `hasPermissions` を上書きして作る
 * （`server.playerList.op` は `run/ops.json` を書き換えるので使わない）。
 */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object PlayerAccessGameTests {
    private val CASE = BlockPos(1, 1, 1)
    private val KEYBOARD = BlockPos(1, 1, 2)
    private val HOUR = AccessRules.hoursToMillis(1)
    private val cleanups = java.util.IdentityHashMap<GameTestInfo, MutableList<() -> Unit>>()
    private val LOGGER = System.getLogger("kibicomputers.gametest")

    /**
     * [ownedCasesSurviveExplosions] の爆発を置く離れたチャンク（テストの場所からのチャンク数）。ほかのテストの離れたチャンク
     * （±400・±420・±440・+800 の向き。ChunkUnloadDrainGameTests は +400 から +x・+z へ）と、x・z の両方で 150 チャンク以上離す。
     * GameTest の並び（1 行 8 件で x に約 4 チャンク、z はバッチをまたいで十数チャンク）の中で、ほかのテストと重ならない（#746）
     */
    private const val BLAST_CHUNKS_X = -600
    private const val BLAST_CHUNKS_Z = 600

    /** 爆発どうしの間（チャンク）。爆発の範囲（8 ブロック）が隣のチャンクの爆発に届かないように空ける */
    private const val BLAST_CHUNK_STEP = 4

    /** 名前 [name] のプレイヤーをサーバーに入れ、ケースの隣に立たせる */
    private fun player(helper: GameTestHelper, name: String, op: Boolean = false, mode: GameType = GameType.SURVIVAL): ServerPlayer {
        val cookie = CommonListenerCookie.createInitial(GameProfile(UUID.randomUUID(), name), false)
        val level = helper.level
        val player = object : ServerPlayer(level.server, level, cookie.gameProfile(), cookie.clientInformation()) {
            override fun hasPermissions(permissionLevel: Int): Boolean = op
        }
        val connection = Connection(PacketFlow.SERVERBOUND)
        EmbeddedChannel(connection)
        level.server.playerList.placeNewPlayer(connection, player, cookie)
        onEnd(helper) {
            KeyboardSessions.loggedOut(player)
            level.server.playerList.remove(player)
        }
        player.setGameMode(mode)
        player.moveTo(Vec3.atCenterOf(helper.absolutePos(CASE.east())))
        return player
    }

    /** テストが終わったとき（成功・失敗・タイムアウトのどれでも）に走らせる後始末。失敗したテストの残りものが、あとのテストに響かないようにする */
    private fun onEnd(helper: GameTestHelper, action: () -> Unit) {
        val info = helper.testInfo
        val list = cleanups.getOrPut(info) {
            val actions = ArrayList<() -> Unit>()
            info.addListener(
                object : GameTestListener {
                    override fun testStructureLoaded(info: GameTestInfo) {}

                    override fun testPassed(info: GameTestInfo, runner: GameTestRunner) = runCleanups(info)

                    override fun testFailed(info: GameTestInfo, runner: GameTestRunner) = runCleanups(info)

                    override fun testAddedForRerun(old: GameTestInfo, new: GameTestInfo, runner: GameTestRunner) {}
                },
            )
            actions
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

    private fun ref(player: ServerPlayer): PlayerRef = CaseAccess.ref(player)

    private fun placeCase(helper: GameTestHelper, pos: BlockPos = CASE): CaseBlockEntity {
        helper.setBlock(pos, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get())
        return helper.getBlockEntity(pos)
    }

    private fun placeBy(helper: GameTestHelper, placer: Any?, pos: BlockPos = CASE): CaseBlockEntity {
        val be = placeCase(helper, pos)
        val abs = helper.absolutePos(pos)
        val state = helper.level.getBlockState(abs)
        (state.block as CaseBlock).setPlacedBy(helper.level, abs, state, placer as? net.minecraft.world.entity.LivingEntity, ItemStack(state.block))
        return be
    }

    private fun installRequired(be: CaseBlockEntity) {
        for (kind in PartKind.entries.filter { it.required && it != PartKind.BOOT }) {
            be.parts.setStackInSlot(be.tier.firstSlot(kind), ItemStack(Items.IRON_INGOT))
        }
    }

    private fun use(helper: GameTestHelper, player: ServerPlayer, pos: BlockPos = CASE) =
        helper.absolutePos(pos).let { abs ->
            helper.level.getBlockState(abs).useWithoutItem(helper.level, player, BlockHitResult(Vec3.atCenterOf(abs), Direction.UP, abs, false))
        }

    /** 置いた本物のプレイヤーが持ち主になる。FakePlayer・`setBlock` は持ち主なし。アイテムの NBT の他人の持ち主は引き継がない */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun placingSetsTheOwner(helper: GameTestHelper) {
        val alex = player(helper, "Alex131a")
        val other = player(helper, "Other131a")
        val placed = placeBy(helper, alex)
        helper.assertTrue(placed.playerAccess.owner?.id == alex.uuid, "the placer should own the case: ${placed.playerAccess}")
        helper.assertTrue(placed.ownerPlayerId == alex.uuid, "the owner id should be readable")

        val fake = FakePlayerFactory.get(helper.level, GameProfile(UUID.randomUUID(), "[kibi-fake]"))
        helper.assertTrue(!placeBy(helper, fake).playerAccess.hasOwner, "a fake player should not own the case")
        helper.assertTrue(!placeBy(helper, null).playerAccess.hasOwner, "a case placed without a player should be ownerless")
        helper.assertTrue(!placeCase(helper).playerAccess.hasOwner, "setBlock should leave the case ownerless")

        // ピックブロックで写した他人の持ち主の NBT を持つアイテムから置いても、置いた人が持ち主になる
        val tag = CompoundTag()
        tag.put(CaseBlockEntity.PLAYER_ACCESS_TAG, PlayerAccessNbt.write(PlayerAccessRecord.of(PlayerAccess.ownedBy(ref(other)))!!))
        val stack = ItemStack(KiBiBlocks.CASES.getValue(CaseTier.BASIC).get())
        stack.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(tag))
        helper.setBlock(CASE, Blocks.AIR)
        helper.setBlock(CASE.below(), Blocks.STONE)
        val floor = helper.absolutePos(CASE.below())
        alex.setItemInHand(InteractionHand.MAIN_HAND, stack)
        val result = (stack.item as BlockItem).place(
            BlockPlaceContext(alex, InteractionHand.MAIN_HAND, stack, BlockHitResult(Vec3.atCenterOf(floor), Direction.UP, floor, false)),
        )
        helper.assertTrue(result.consumesAction(), "the case item should be placed but was $result")
        val fromItem = helper.getBlockEntity<CaseBlockEntity>(CASE)
        helper.assertTrue(fromItem.playerAccess.owner?.id == alex.uuid, "the placer should replace the copied owner: ${fromItem.playerAccess}")
        helper.succeed()
    }

    /**
     * 他人は GUI を開けない。持ち主・メンバー・OP は開ける。メンバーから外すと、開いている GUI が続けられなくなる。
     * 偽のプレイヤーは NeoForge の画面を開くパケットを受けられないので、通る側は右クリックと同じ判定（[CaseBlockEntity.touchAccess]）で確かめる
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun onlyPermittedPlayersOpenTheGui(helper: GameTestHelper) {
        val alex = player(helper, "Alex131b")
        val steve = player(helper, "Steve131b")
        val stranger = player(helper, "Stranger131b")
        val op = player(helper, "Op131b", op = true)
        val be = placeBy(helper, alex)

        val denied = use(helper, stranger)
        helper.assertTrue(!denied.consumesAction(), "a stranger should be refused but was $denied")
        helper.assertTrue(stranger.containerMenu === stranger.inventoryMenu, "a stranger should not get the case menu")

        helper.assertTrue(be.touchAccess(alex), "the owner should open the menu")
        helper.assertTrue(be.touchAccess(op), "an operator should open the menu")
        helper.assertTrue(!be.touchAccess(steve), "a player who is not a member yet should be refused")

        // 保留に入れたメンバーは、アクセスで昇格して開ける
        be.replacePlayerAccess(be.playerAccess.addPending(steve.gameProfile.name, System.currentTimeMillis(), CaseAccess.rules()).access)
        helper.assertTrue(be.touchAccess(steve), "a pending member should open the menu")
        helper.assertTrue(be.playerAccess.members.any { it.id == steve.uuid }, "the pending member should be promoted: ${be.playerAccess}")
        val menu = CaseMenu.server(0, steve.inventory, be)
        helper.assertTrue(menu.stillValid(steve), "the member's menu should stay valid")
        helper.assertTrue(!menu.clickMenuButton(stranger, CaseMenu.POWER_BUTTON), "a stranger should not press the power button")
        be.replacePlayerAccess(be.playerAccess.removeMember(steve.uuid.toString()).access)
        helper.assertTrue(!menu.stillValid(steve), "a removed member's menu should be closed")
        helper.assertTrue(!menu.clickMenuButton(steve, CaseMenu.POWER_BUTTON), "a removed member should not press the power button")
        helper.succeed()
    }

    /** 保留は名前（大文字・小文字を無視）で照らし、期限内なら昇格する。期限切れなら昇格せず、捨てる */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun pendingInvitesArePromotedOnlyBeforeTheyExpire(helper: GameTestHelper) {
        val alex = player(helper, "Alex131c")
        val fresh = player(helper, "Fresh131c")
        val late = player(helper, "Late131c")
        val be = placeBy(helper, alex)
        val now = System.currentTimeMillis()
        val ttl = CaseAccess.rules().pendingTtlMillis
        val record = PlayerAccessRecord.of(be.playerAccess)!!
        val invites = listOf(PendingInvite("FRESH131C", now - HOUR), PendingInvite("late131c", now - ttl - HOUR))
        val tag = be.saveWithoutMetadata(helper.level.registryAccess())
        tag.put(
            CaseBlockEntity.PLAYER_ACCESS_TAG,
            PlayerAccessNbt.write(PlayerAccessRecord(record.version, record.ownerId, record.ownerName, emptyList(), invites)),
        )
        be.loadWithComponents(tag, helper.level.registryAccess())
        helper.assertTrue(be.playerAccess.pending.size == 2, "both invites should load: ${be.playerAccess}")

        helper.assertTrue(!use(helper, late).consumesAction(), "an expired invite should not let the player in")
        helper.assertTrue(be.playerAccess.pending.map { it.name } == listOf("fresh131c"), "the expired invite should be dropped: ${be.playerAccess}")
        helper.assertTrue(be.touchAccess(fresh), "a fresh invite should let the player in")
        helper.assertTrue(be.playerAccess.members.map { it.id } == listOf(fresh.uuid) && be.playerAccess.pending.isEmpty(), "promoted: ${be.playerAccess}")
        helper.succeed()
    }

    /**
     * 他人の破壊（サバイバル・クリエイティブ）は止まり、ブロック・部品・保存のディレクトリが残る。持ち主・メンバー・OP は壊せる。
     * 持ち主のないケースは誰でも壊せる
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 400)
    fun strangersCannotBreakOwnedCases(helper: GameTestHelper) {
        val alex = player(helper, "Alex131d")
        val steve = player(helper, "Steve131d")
        val survival = player(helper, "Survivor131d")
        val creative = player(helper, "Creative131d", mode = GameType.CREATIVE)
        val op = player(helper, "Op131d", op = true, mode = GameType.CREATIVE)
        val be = placeBy(helper, alex)
        installRequired(be)
        helper.assertTrue(be.pressPowerButton() == ComputerStatus.RUNNING, "case should run but was ${be.computer}")
        val abs = helper.absolutePos(CASE)
        // onLoad（索引に入る）を待ってから取り出して書く
        helper.runAfterDelay(1) {
            be.computer.prepareUnload()
            helper.assertTrue(saveNow(be, CapturePolicy.KILL_TRANSIENT) > 0, "the case should be saved")
            be.computer.endQuiesce()
            val store = WorldMachineSaves.current!!
            val id = be.identity!!.id
            for (p in listOf(survival, creative)) {
                helper.assertTrue(!p.gameMode.destroyBlock(abs), "${p.gameProfile.name} should not break the case")
                helper.assertTrue(helper.level.getBlockEntity(abs) === be, "the case should remain")
            }
            ServerMachines.drainSaves()
            helper.assertTrue(Files.isDirectory(store.directoryOf(id)), "a refused break should keep the save directory")
            helper.assertTrue(!be.parts.getStackInSlot(0).isEmpty, "the parts should stay in the case")
            val drops = helper.level.getEntitiesOfClass(ItemEntity::class.java, AABB(abs).inflate(3.0))
            helper.assertTrue(drops.isEmpty(), "a refused break should drop nothing but dropped ${drops.map { it.item }}")

            helper.assertTrue(op.gameMode.destroyBlock(abs), "an operator should break the case")
            ServerMachines.drainSaves()
            helper.assertTrue(!Files.exists(store.directoryOf(id)), "the operator's break should delete the save directory")

            val owned = placeBy(helper, alex)
            helper.assertTrue(alex.gameMode.destroyBlock(abs), "the owner should break the case")
            val withMember = placeBy(helper, alex)
            withMember.replacePlayerAccess(withMember.playerAccess.addPending("steve131d", System.currentTimeMillis(), CaseAccess.rules()).access)
            withMember.touchAccess(steve)
            helper.assertTrue(steve.gameMode.destroyBlock(abs), "a member should break the case")
            placeCase(helper)
            helper.assertTrue(survival.gameMode.destroyBlock(abs), "anyone should break an ownerless case")
            helper.assertTrue(owned.isRemoved, "the owner's case should be gone")
            for (e in helper.level.getEntitiesOfClass(ItemEntity::class.java, AABB(abs).inflate(3.0))) e.discard()
            helper.succeed()
        }
    }

    private class Blast(val name: String, val pos: BlockPos, val chunk: ChunkPos, val be: CaseBlockEntity, val explode: () -> Unit)

    /**
     * 持ち主のある（読めなかった記録を含む）ケースは、源が誰の爆発でも（BLOCK・TNT）壊れない。保存のディレクトリと部品が残り、
     * 何も落ちない。隣の土は壊れる（爆発耐性は変えていない）。持ち主のないケースが壊れることは
     * [MachineSaveGameTests.explosionDeletesTheSaves] が確かめる
     *
     * 4 つの爆発は、それぞれ別の離れたチャンク（[BLAST_CHUNKS_X]・[BLAST_CHUNKS_Z]、間は [BLAST_CHUNK_STEP]）に置く。
     * 落とし物は、爆発の前からあった物を除いた、その爆発で増えた物だけを数える（#746。並んで動くほかのテストの落とし物が、
     * 同じチャンクに残っていることがある）。検索がそのチャンクで働いていること（エンティティのセクションが見えること）は、
     * 爆発の後に置く目印の物が検索に出ることで確かめる
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 400)
    fun ownedCasesSurviveExplosions(helper: GameTestHelper) {
        val alex = player(helper, "Alex131e")
        val stranger = player(helper, "Stranger131e")
        val level = helper.level
        val base = helper.absolutePos(CASE)
        val blasts = ArrayList<Blast>()
        fun setUp(name: String, index: Int, access: (CaseBlockEntity) -> Unit, explode: (Vec3) -> Unit) {
            val chunk = ChunkPos(base.x / 16 + BLAST_CHUNKS_X - index * BLAST_CHUNK_STEP, base.z / 16 + BLAST_CHUNKS_Z)
            val pos = BlockPos(chunk.minBlockX + 8, 200, chunk.minBlockZ + 8)
            level.setChunkForced(chunk.x, chunk.z, true)
            onEnd(helper) { level.setChunkForced(chunk.x, chunk.z, false) }
            level.getChunk(chunk.x, chunk.z)
            level.setBlock(pos, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get().defaultBlockState(), 3)
            level.setBlock(pos.east(), Blocks.DIRT.defaultBlockState(), 3)
            val be = level.getBlockEntity(pos) as CaseBlockEntity
            access(be)
            installRequired(be)
            helper.assertTrue(be.pressPowerButton() == ComputerStatus.RUNNING, "case should run but was ${be.computer}")
            blasts += Blast(name, pos, chunk, be) { explode(Vec3.atCenterOf(pos)) }
        }
        val owned: (CaseBlockEntity) -> Unit = { it.replacePlayerAccess(PlayerAccess.ownedBy(ref(alex))) }
        fun tnt(at: Vec3, by: ServerPlayer?) = PrimedTnt(level, at.x, at.y, at.z, by)
        setUp("block, no source", 0, owned) { level.explode(null, it.x, it.y, it.z, 4.0f, Level.ExplosionInteraction.BLOCK) }
        setUp("tnt by a stranger", 1, owned) { level.explode(tnt(it, stranger), it.x, it.y, it.z, 4.0f, Level.ExplosionInteraction.TNT) }
        setUp("tnt by the owner", 2, owned) { level.explode(tnt(it, alex), it.x, it.y, it.z, 4.0f, Level.ExplosionInteraction.TNT) }
        setUp("locked", 3, { be ->
            val tag = be.saveWithoutMetadata(level.registryAccess())
            tag.put(CaseBlockEntity.PLAYER_ACCESS_TAG, CompoundTag().apply { put("Version", IntTag.valueOf(99)) })
            be.loadWithComponents(tag, level.registryAccess())
            helper.assertTrue(be.playerAccess.locked, "an unknown version should be locked")
        }) { level.explode(null, it.x, it.y, it.z, 4.0f, Level.ExplosionInteraction.BLOCK) }

        // 強制読み込みしたチャンクが FULL に上がり、エンティティのセクションが見えるようになるまで待つ。見えないうちは
        // 落とし物が検索に出ない（1 tick では上がりきらないことがある）
        fun ready() = blasts.all { level.areEntitiesLoaded(it.chunk.toLong()) && level.isPositionEntityTicking(it.pos) }
        fun blastWhenReady(ticksLeft: Int, run: () -> Unit) {
            if (ready()) {
                run()
            } else {
                helper.assertTrue(ticksLeft > 0, "the blast chunks should become entity ticking")
                helper.runAfterDelay(1) { blastWhenReady(ticksLeft - 1, run) }
            }
        }
        helper.runAfterDelay(1) { blastWhenReady(200) {
            val store = WorldMachineSaves.current!!
            for (b in blasts) {
                b.be.computer.prepareUnload()
                helper.assertTrue(saveNow(b.be, CapturePolicy.KILL_TRANSIENT) > 0, "${b.name}: the case should be saved")
                b.be.computer.endQuiesce()
                val id = b.be.identity!!.id
                val parts = (0 until b.be.parts.slots).map { b.be.parts.getStackInSlot(it).copy() }
                val area = AABB(b.pos).inflate(8.0)
                // 爆発の落とし物（ルートと onRemove の部品）は、この呼び出しの中で同期に出る。前からあった物を除けば、この爆発の物だけになる
                val before = Collections.newSetFromMap(IdentityHashMap<ItemEntity, Boolean>())
                before += level.getEntitiesOfClass(ItemEntity::class.java, area)
                if (before.isNotEmpty()) {
                    LOGGER.log(System.Logger.Level.WARNING, "${b.name}: other items were near the case before the blast: ${before.map { it.item }}")
                }
                b.explode()
                helper.assertTrue(level.getBlockEntity(b.pos) === b.be && !b.be.isRemoved, "${b.name}: the owned case should survive")
                helper.assertTrue(level.getBlockState(b.pos.east()).isAir, "${b.name}: the dirt next to the case should be blown up")
                val now = (0 until b.be.parts.slots).map { b.be.parts.getStackInSlot(it) }
                helper.assertTrue(parts.zip(now).all { (a, c) -> ItemStack.matches(a, c) }, "${b.name}: the parts should not change")
                // 対照: チャンクのエンティティのセクションがまだ見えないと、落とし物があっても検索に出ず、下の確かめが素通りする。
                // 爆発の後に目印の物を置き、それが検索に出ることを確かめてから数える
                val probe = ItemEntity(level, b.pos.x + 0.5, b.pos.y + 2.0, b.pos.z + 0.5, ItemStack(Items.STICK))
                helper.assertTrue(level.addFreshEntity(probe), "${b.name}: the probe item should be added")
                val found = level.getEntitiesOfClass(ItemEntity::class.java, area).filter { it !in before }
                helper.assertTrue(probe in found, "${b.name}: the entity search should see items in the blast chunk")
                probe.discard()
                val drops = found.filter { it !== probe }
                val bad = drops.filter { it.item.`is`(Items.IRON_INGOT) || it.item.item is BlockItem && (it.item.item as BlockItem).block is CaseBlock }
                helper.assertTrue(bad.isEmpty(), "${b.name}: nothing of the case should drop but dropped ${bad.map { it.item }}")
                drops.forEach { it.discard() }
                ServerMachines.drainSaves()
                helper.assertTrue(Files.isDirectory(store.directoryOf(id)), "${b.name}: the save directory should remain")
                b.be.deleteSaves()
                removeCaseWithoutDrops(level, b.pos)
                level.setChunkForced(b.chunk.x, b.chunk.z, false)
            }
            ServerMachines.drainSaves()
            helper.succeed()
        } }
    }

    /** mob の破壊（ウィザーなど。`LivingDestroyBlockEvent`）は、持ち主のあるケースでは止まり、持ち主のないケースでは止まらない */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun mobsCannotBreakOwnedCases(helper: GameTestHelper) {
        val alex = player(helper, "Alex131f")
        val wither = EntityType.WITHER.create(helper.level) ?: throw GameTestAssertException("a wither should be created")
        val abs = helper.absolutePos(CASE)
        val be = placeBy(helper, alex)
        helper.assertTrue(!EventHooks.onEntityDestroyBlock(wither, abs, helper.level.getBlockState(abs)), "a mob should not break an owned case")
        be.replacePlayerAccess(PlayerAccess.NONE)
        helper.assertTrue(EventHooks.onEntityDestroyBlock(wither, abs, helper.level.getBlockState(abs)), "a mob may break an ownerless case")
        helper.succeed()
    }

    /**
     * NBT: 往復できる。持ち主がいなければタグを書かず、タグのない古いデータは持ち主なし。知らない版は OP だけが通り、そのまま書き戻す
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun accessIsSavedInTheBlockEntity(helper: GameTestHelper) {
        val alex = player(helper, "Alex131g")
        val stranger = player(helper, "Stranger131g")
        val op = player(helper, "Op131g", op = true)
        val registries = helper.level.registryAccess()
        val be = placeBy(helper, alex)
        be.replacePlayerAccess(be.playerAccess.addPending("bob", System.currentTimeMillis(), CaseAccess.rules()).access)
        val tag = be.saveWithoutMetadata(registries)
        helper.assertTrue(tag.contains(CaseBlockEntity.PLAYER_ACCESS_TAG), "an owned case should write the tag: $tag")
        val copy = placeCase(helper, CASE.above())
        copy.loadWithComponents(tag, registries)
        helper.assertTrue(copy.playerAccess == be.playerAccess, "the access should round-trip: ${copy.playerAccess}")

        helper.setBlock(CASE, Blocks.AIR)
        val ownerless = placeCase(helper)
        val plain = ownerless.saveWithoutMetadata(registries)
        helper.assertTrue(!plain.contains(CaseBlockEntity.PLAYER_ACCESS_TAG), "an ownerless case should not write the tag: $plain")
        copy.loadWithComponents(plain, registries)
        helper.assertTrue(copy.playerAccess === PlayerAccess.NONE, "old data without the tag should be ownerless")

        val unknown = CompoundTag().apply { putInt("Version", 99); putString("Future", "x") }
        plain.put(CaseBlockEntity.PLAYER_ACCESS_TAG, unknown)
        copy.loadWithComponents(plain, registries)
        helper.assertTrue(copy.playerAccess.locked, "an unknown version should be locked")
        helper.assertTrue(!copy.checkAccess(alex) && !copy.checkAccess(stranger) && copy.checkAccess(op), "only operators should use a locked case")
        val written = copy.saveWithoutMetadata(registries).getCompound(CaseBlockEntity.PLAYER_ACCESS_TAG)
        helper.assertTrue(written == unknown, "the unknown tag should be written back as read: $written")

        // locked の記録への変更（locked ならそのまま返る）を渡しても、生のタグは捨てず、読み直しても locked のまま
        copy.replacePlayerAccess(copy.playerAccess.addPending("bob", System.currentTimeMillis(), CaseAccess.rules()).access)
        helper.assertTrue(copy.playerAccess.locked, "a change to a locked record should keep it locked")
        val rewritten = copy.saveWithoutMetadata(registries)
        helper.assertTrue(rewritten.getCompound(CaseBlockEntity.PLAYER_ACCESS_TAG) == unknown, "the raw tag should survive: $rewritten")
        val reloaded = placeCase(helper, CASE.below())
        reloaded.loadWithComponents(rewritten, registries)
        helper.assertTrue(reloaded.playerAccess.locked, "the locked case should stay locked after a save and reload")
        // locked でないケースを locked にはできない（保存すると持ち主なしに戻ってしまう）
        val refused = runCatching { ownerless.replacePlayerAccess(PlayerAccess.LOCKED) }.exceptionOrNull()
        helper.assertTrue(refused is IllegalArgumentException, "making a readable case locked should be refused but was $refused")
        helper.assertTrue(!ownerless.playerAccess.hasOwner, "the refused replacement should not change the access")
        helper.succeed()
    }

    /**
     * キーボード: 他人のケースにつながったキーボードは開けない。つながっていない間に開いたセッションは、持ち主のあるケースが電源を
     * 入れてつないだら、次の入力で（ケースに届く前に捨てて）閉じるか、次の tick で閉じる。持ち主のセッションは続き、入力も届く
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 200)
    fun keyboardSessionsFollowTheWiredCase(helper: GameTestHelper) {
        val alex = player(helper, "Alex131h")
        val stranger = player(helper, "Stranger131h")
        val typist = player(helper, "Typist131h")
        val be = placeBy(helper, alex)
        be.fallbackBootDisk = pro.ikili.kibicomputers.neoforge.computer.ProvisionalBoot.TEST_BOOT_DISK
        installRequired(be)
        helper.setBlock(KEYBOARD, KiBiBlocks.KEYBOARD.get())
        val keyboard = helper.absolutePos(KEYBOARD)
        for (p in listOf(alex, stranger, typist)) p.moveTo(Vec3.atCenterOf(keyboard.east()))

        // つながっていない間は誰でも開ける
        helper.assertTrue(KeyboardSessions.open(stranger, keyboard, null) != null, "an unwired keyboard should open for anyone")
        val typing = KeyboardSessions.open(typist, keyboard, null) ?: throw GameTestAssertException("an unwired keyboard should open for anyone")
        val owners = KeyboardSessions.open(alex, keyboard, null) ?: throw GameTestAssertException("the owner should open the keyboard")
        helper.assertTrue(be.pressPowerButton() == ComputerStatus.RUNNING, "case should run but was ${be.computer}")
        helper.assertTrue(be.wiring.connectedKeyboard != null, "the keyboard should be wired to the case")
        // tick の前に届いた入力も、入力のたびの確かめで捨て、セッションを閉じる。持ち主の入力は届く
        val data = KeyboardEventCodec.encode(listOf(KeyboardEvent.Text("x"))).single()
        helper.assertTrue(KeyboardSessions.receive(typist, KeyboardInputPayload(typing.id, data)) == -1, "input from a player who may not use the case should be rejected")
        helper.assertTrue(KeyboardSessions.sessionOf(typist) == null, "the rejected player's session should be closed")
        helper.assertTrue(KeyboardSessions.receive(alex, KeyboardInputPayload(owners.id, data)) == 1, "the owner's input should reach the case")
        KeyboardSessions.tick()
        helper.assertTrue(KeyboardSessions.sessionOf(stranger) == null, "the stranger's session should close once the keyboard is wired to an owned case")
        helper.assertTrue(KeyboardSessions.sessionOf(alex) != null, "the owner's session should stay")
        helper.assertTrue(KeyboardSessions.open(stranger, keyboard, null) == null, "a keyboard wired to someone else's case should not open")
        helper.assertTrue(KeyboardSessions.open(alex, keyboard, null) != null, "the owner should reopen the keyboard")
        be.pressPowerButton()
        helper.succeed()
    }
}
