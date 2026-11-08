package pro.ikili.kibicomputers.neoforge.gametest

import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.GameType
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.computer.CaseTier
import pro.ikili.kibicomputers.core.computer.ComputerStatus
import pro.ikili.kibicomputers.core.computer.PartKind
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.core.persist.ClaimResult
import pro.ikili.kibicomputers.core.persist.MachineSaveStore
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
import pro.ikili.kibicomputers.neoforge.machine.ServerMachines
import pro.ikili.kibicomputers.neoforge.machine.WorldMachineSaves
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks
import java.nio.file.Files
import java.util.UUID

/** 待機点保存（#81、ADR 0081）の GameTest: マシンの ID、世代のファイル、チャンクの印、アンロードと停止の手順 */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object MachineSaveGameTests {
    private val CASE = BlockPos(1, 1, 1)
    private val OTHER = BlockPos(0, 1, 0)

    private fun store(helper: GameTestHelper): MachineSaveStore {
        val store = WorldMachineSaves.current
        helper.assertTrue(store != null, "the server should have a machine save store")
        return store!!
    }

    private fun installRequired(be: CaseBlockEntity) {
        for (kind in PartKind.entries.filter { it.required && it != PartKind.BOOT }) {
            be.parts.setStackInSlot(be.tier.firstSlot(kind), ItemStack(Items.IRON_INGOT))
        }
    }

    private fun placeRunning(helper: GameTestHelper, pos: BlockPos): CaseBlockEntity {
        helper.setBlock(pos, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get())
        val be = helper.getBlockEntity<CaseBlockEntity>(pos)
        installRequired(be)
        helper.assertTrue(be.pressPowerButton() == ComputerStatus.RUNNING, "case should run but was ${be.computer}")
        return be
    }

    /** チャンクの NBT（`block_entities` だけ）に [tags] を入れたもの */
    private fun chunkData(vararg tags: CompoundTag): CompoundTag {
        val list = ListTag()
        for (t in tags) list.add(t)
        return CompoundTag().apply { put("block_entities", list) }
    }

    /**
     * 取り出して書くと ID が振られ、世代のファイルと `owner.bin` ができる。`saveWithFullMetadata`（`/data get`・`/clone` と同じ）は
     * 印を動かさず、チャンクの保存の NBT で持ち主の位置のタグだけが印を動かす。`/data merge` で ID は変わらず、
     * 写したタグを別のケースに読ませると新しい ID になる。停止の手順はチャンクの保存の後に印が新しい世代を指す。
     * プレイヤーの破壊はディレクトリを消し、`setRemoved` だけでは消さない
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 400)
    fun identityGenerationsAndMarks(helper: GameTestHelper) {
        val store = store(helper)
        val dimension = helper.level.dimension().location().toString()
        val be = placeRunning(helper, CASE)
        // 置いたブロックエンティティの onLoad は次の tick に来る（索引に入るのはそこ）
        helper.runAfterDelay(1) { afterPlace(helper, store, dimension, be) }
    }

    private fun afterPlace(helper: GameTestHelper, store: MachineSaveStore, dimension: String, be: CaseBlockEntity) {
        helper.assertTrue(be.identity == null, "a case gets no id until the first capture")
        helper.assertTrue(be.plannedWrites == 2, "the first save also writes owner.bin")

        // アンロードの手順と同じく止めてから取り出す（止めている間は1回だけ）
        be.computer.prepareUnload()
        val writes = be.captureAndWrite(CapturePolicy.KILL_TRANSIENT)
        helper.assertTrue(writes == 2, "the first save writes owner.bin and a generation but wrote $writes")
        val identity = be.identity!!
        val id = identity.id
        helper.assertTrue(Files.isRegularFile(store.generationFile(id, identity.generation)), "the generation file should exist")
        helper.assertTrue(Files.isRegularFile(store.directoryOf(id).resolve("owner.bin")), "owner.bin should exist")
        helper.assertTrue(be.captureAndWrite(CapturePolicy.KILL_TRANSIENT) == 0, "a machine is captured only once while quiesced")
        be.computer.endQuiesce()

        // /data get block・/clone・ピックブロックと同じ保存は印を動かさない
        val tag = be.saveWithFullMetadata(helper.level.registryAccess())
        helper.assertTrue(tag.getString(CaseBlockEntity.MACHINE_ID_TAG) == id.toString(), "the tag should carry the id: $tag")
        helper.assertTrue(!store.chunkGeneration(id).isPresent, "saving the block entity alone should not mark the chunk")

        // 持ち主でない位置のタグは無視し、持ち主の位置のタグで印が動く
        val moved = tag.copy().apply { putInt("x", getInt("x") + 7) }
        helper.assertTrue(ServerMachines.markChunkGenerations(dimension, chunkData(moved)) == 0, "a tag at another position should be ignored")
        helper.assertTrue(!store.chunkGeneration(id).isPresent, "the mark should not move for a non-owner tag")
        helper.assertTrue(ServerMachines.markChunkGenerations(dimension, chunkData(tag)) == 1, "the owner's tag should mark")
        helper.assertTrue(store.chunkGeneration(id).asLong == identity.generation, "the mark should point at the saved generation")

        // /data merge で ID を書き換えても、確定した ID は変わらない
        val merged = tag.copy().apply { putString(CaseBlockEntity.MACHINE_ID_TAG, UUID.randomUUID().toString()) }
        be.loadWithComponents(merged, helper.level.registryAccess())
        helper.assertTrue(be.identity?.id == id, "a loaded case should keep its id on /data merge but was ${be.identity}")

        // 写したタグを別のケースに読ませると、読み込み中の重複として新しい ID になる
        helper.setBlock(OTHER, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get())
        val copy = helper.getBlockEntity<CaseBlockEntity>(OTHER)
        copy.loadWithComponents(tag, helper.level.registryAccess())
        // copy の onLoad（持ち主を確かめる）を待つ
        helper.runAfterDelay(2) { afterLoad(helper, store, be, copy, id, identity.generation) }
    }

    private fun afterLoad(helper: GameTestHelper, store: MachineSaveStore, be: CaseBlockEntity, copy: CaseBlockEntity, id: UUID, firstGeneration: Long) {
        helper.assertTrue(copy.lastClaim == ClaimResult.NEW_ID_DUPLICATE_LOADED, "a duplicate should get a new id but was ${copy.lastClaim}")
        val copyId = copy.identity?.id
        helper.assertTrue(copyId != null && copyId != id, "the duplicate should not share the id: $copyId")

        // 停止の手順: 書いて setChanged し、チャンクの保存の後に印が新しい世代を指す
        helper.assertTrue(ServerMachines.saveForStop(listOf(be), keepQuiesced = false) == 1, "the stop save should write the machine")
        val afterStop = be.identity!!
        helper.assertTrue(afterStop.generation > firstGeneration, "the stop save should write a new generation")
        helper.assertTrue(afterStop.id == id, "the stop save should keep the id but was ${afterStop.id}")
        helper.level.chunkSource.save(true)
        helper.assertTrue(
            store.chunkGeneration(id).asLong == afterStop.generation,
            "the chunk save should mark ${afterStop.generation} but was ${store.chunkGeneration(id)}",
        )

        // setRemoved だけ（ブロックを運ぶ MOD と同じ）ではディレクトリを消さない
        helper.setBlock(OTHER, Blocks.AIR)
        helper.assertTrue(Files.isDirectory(store.directoryOf(copyId!!)), "setRemoved alone should keep the directory")
        // プレイヤーの破壊では消す
        be.pressPowerButton()
        val player = helper.makeMockPlayer(GameType.SURVIVAL)
        val abs = helper.absolutePos(CASE)
        val state = helper.level.getBlockState(abs)
        state.block.playerWillDestroy(helper.level, abs, state, player)
        helper.assertTrue(!Files.exists(store.directoryOf(id)), "a player breaking the case should delete its directory")
        helper.setBlock(CASE, Blocks.AIR)
        helper.succeed()
    }

    /**
     * 本物のアンロードの経路（`ChunkEvent.Unload` → `ChunkMap.save`）: 強制読み込みを外したチャンクのケースは、
     * アンロードで取り出して書かれ、ディスクに行ったチャンクの NBT が書いた世代を指し、印も同じ世代になる
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 2400)
    fun chunkUnloadSavesTheMachine(helper: GameTestHelper) {
        val store = store(helper)
        val level = helper.level
        // テストの場所から離れた、だれも読み込んでいないチャンク
        val chunk = ChunkPos(helper.absolutePos(CASE).x / 16 + 400, helper.absolutePos(CASE).z / 16 + 400)
        val pos = BlockPos(chunk.minBlockX + 3, 200, chunk.minBlockZ + 5)
        level.setChunkForced(chunk.x, chunk.z, true)
        level.getChunk(chunk.x, chunk.z)
        level.setBlock(pos, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get().defaultBlockState(), 3)
        val be = level.getBlockEntity(pos) as CaseBlockEntity
        installRequired(be)
        helper.assertTrue(be.pressPowerButton() == ComputerStatus.RUNNING, "case should run but was ${be.computer}")
        val capturesBefore = ServerMachines.Stats.unloadCaptures
        level.setChunkForced(chunk.x, chunk.z, false)
        helper.succeedWhen {
            helper.assertTrue(!level.chunkSource.hasChunk(chunk.x, chunk.z), "the chunk should unload")
            helper.assertTrue(ServerMachines.Stats.unloadCaptures > capturesBefore, "the unload should capture the machine")
            val data = level.chunkSource.chunkMap.read(chunk).join().orElse(null)
            helper.assertTrue(data != null, "the chunk should be on disk")
            val tag = data!!.getList("block_entities", Tag.TAG_COMPOUND.toInt()).map { it as CompoundTag }
                .firstOrNull { it.getInt("x") == pos.x && it.getInt("y") == pos.y && it.getInt("z") == pos.z }
            helper.assertTrue(tag != null && tag.contains(CaseBlockEntity.MACHINE_ID_TAG), "the saved chunk should have the case with an id: $tag")
            val id = UUID.fromString(tag!!.getString(CaseBlockEntity.MACHINE_ID_TAG))
            val generation = tag.getLong(CaseBlockEntity.SAVE_GENERATION_TAG)
            helper.assertTrue(store.latestGeneration(id).asLong == generation, "the chunk should point at the latest generation")
            helper.assertTrue(Files.isRegularFile(store.generationFile(id, generation)), "the generation file should exist")
            helper.assertTrue(store.chunkGeneration(id).asLong == generation, "the mark should follow the chunk save")
        }
    }

    /**
     * FULL でなくなったがメモリに残るチャンク（#545）: 5 チャンク離れたチャンクを強制読み込みにしたまま（ケースのチャンクの
     * チケットの段階は 36。FULL でなく、アンロードもされない）、ケースのチャンクの強制読み込みを外すと、マシンが取り出されて書かれ、
     * 外される。チャンクはアンロードされず、ブロックエンティティは残る。FULL に戻すと、同じブロックエンティティが ID を保ったまま、
     * 電源が切れている（アンロードの後に読み込み直したのと同じ）
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 1200)
    fun demotedChunkSavesAndStopsTheMachine(helper: GameTestHelper) {
        val store = store(helper)
        val level = helper.level
        // テストの場所から離れた、だれも読み込んでいないチャンク（ほかのテストと重ならない向き）
        val chunk = ChunkPos(helper.absolutePos(CASE).x / 16 - 400, helper.absolutePos(CASE).z / 16 + 400)
        val keeper = ChunkPos(chunk.x + 5, chunk.z)
        val pos = BlockPos(chunk.minBlockX + 6, 200, chunk.minBlockZ + 9)
        level.setChunkForced(keeper.x, keeper.z, true)
        level.setChunkForced(chunk.x, chunk.z, true)
        level.getChunk(chunk.x, chunk.z)
        level.setBlock(pos, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get().defaultBlockState(), 3)
        val be = level.getBlockEntity(pos) as CaseBlockEntity
        installRequired(be)
        helper.assertTrue(be.pressPowerButton() == ComputerStatus.RUNNING, "case should run but was ${be.computer}")
        // 共有のカウンター（ServerMachines.Stats）は、同じバッチで並んで動くほかのテストのチャンクでも動くので見ない（#548）。
        // このテストのブロックエンティティだけを見る
        // onLoad（追跡と索引）を待ってから外す
        helper.runAfterDelay(1) {
            helper.assertTrue(be in ServerMachines.trackedCases(), "the case should be tracked after onLoad")
            level.setChunkForced(chunk.x, chunk.z, false)
            var id: UUID? = null
            helper.startSequence().thenWaitUntil {
                // アンロードされていない: アンロードなら setRemoved され、追跡からも外れる。FULL でなくなっただけなら両方そのまま
                helper.assertTrue(!be.isRemoved, "the chunk should stay in memory (the block entity was removed)")
                helper.assertTrue(be in ServerMachines.trackedCases(), "the demoted case should stay tracked")
                // 外された: マシンがなく、電源が切れている（取り出して書いた世代は下で確かめる）
                helper.assertTrue(be.computer.machine == null, "the machine should be stopped but was ${be.computer}")
                helper.assertTrue(be.computer.status == ComputerStatus.OFF, "the case should be off but was ${be.computer}")
                helper.assertTrue(!level.isLoaded(pos), "the chunk should no longer be FULL")
                val identity = be.identity
                helper.assertTrue(identity != null, "the demotion should assign an id")
                helper.assertTrue(Files.isRegularFile(store.generationFile(identity!!.id, identity.generation)), "the generation file should exist")
                helper.assertTrue(store.latestGeneration(identity.id).asLong == identity.generation, "the case should point at the latest generation")
                id = identity.id
            }.thenExecute { afterDemotion(helper, chunk, keeper, pos, be, id!!) }.thenSucceed()
        }
    }

    private fun afterDemotion(helper: GameTestHelper, chunk: ChunkPos, keeper: ChunkPos, pos: BlockPos, be: CaseBlockEntity, id: UUID) {
        val level = helper.level
        // FULL に戻す: 同じブロックエンティティが ID を保ち、電源は切れている。電源はまた入る
        level.setChunkForced(chunk.x, chunk.z, true)
        level.getChunk(chunk.x, chunk.z)
        helper.assertTrue(level.getBlockEntity(pos) === be, "the block entity should stay in memory")
        helper.assertTrue(be.computer.status == ComputerStatus.OFF, "the case should stay off after the chunk is FULL again")
        helper.assertTrue(be.identity?.id == id, "the case should keep its id but was ${be.identity}")
        helper.assertTrue(be.pressPowerButton() == ComputerStatus.RUNNING, "the case should run again but was ${be.computer}")
        be.pressPowerButton()
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3)
        level.setChunkForced(chunk.x, chunk.z, false)
        level.setChunkForced(keeper.x, keeper.z, false)
    }

    /**
     * 爆発（`onBlockExploded`）で壊れたケースは、保存データのディレクトリを消す。ほかのテストのブロックを壊さないよう、
     * 離れたチャンクで爆発させる
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 400)
    fun explosionDeletesTheSaves(helper: GameTestHelper) {
        val store = store(helper)
        val level = helper.level
        val chunk = ChunkPos(helper.absolutePos(CASE).x / 16 + 400, helper.absolutePos(CASE).z / 16 - 400)
        val pos = BlockPos(chunk.minBlockX + 8, 200, chunk.minBlockZ + 8)
        level.setChunkForced(chunk.x, chunk.z, true)
        level.getChunk(chunk.x, chunk.z)
        level.setBlock(pos, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get().defaultBlockState(), 3)
        val be = level.getBlockEntity(pos) as CaseBlockEntity
        installRequired(be)
        helper.assertTrue(be.pressPowerButton() == ComputerStatus.RUNNING, "case should run but was ${be.computer}")
        // onLoad（索引に入る）を待ってから取り出して書く
        helper.runAfterDelay(1) {
            be.computer.prepareUnload()
            helper.assertTrue(be.captureAndWrite(CapturePolicy.KILL_TRANSIENT) > 0, "the case should be saved")
            be.computer.endQuiesce()
            val id = be.identity!!.id
            helper.assertTrue(Files.isDirectory(store.directoryOf(id)), "the save directory should exist")
            // 中心のブロックは必ず壊れる強さ（ケースの爆発耐性 6）
            level.explode(null, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, 4.0f, Level.ExplosionInteraction.BLOCK)
            level.setChunkForced(chunk.x, chunk.z, false)
            helper.assertTrue(level.getBlockEntity(pos) !is CaseBlockEntity, "the explosion should destroy the case")
            helper.assertTrue(!Files.exists(store.directoryOf(id)), "an exploded case should delete its directory")
            helper.succeed()
        }
    }
}
