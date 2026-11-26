package pro.ikili.kibicomputers.neoforge.gametest

import com.mojang.brigadier.exceptions.CommandSyntaxException
import net.minecraft.core.BlockPos
import net.minecraft.core.MappedRegistry
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.gametest.framework.GameTestInfo
import net.minecraft.gametest.framework.GameTestListener
import net.minecraft.gametest.framework.GameTestRunner
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.level.ChunkMap
import net.minecraft.server.level.FullChunkStatus
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.chunk.storage.ChunkStorage
import net.minecraft.world.level.chunk.storage.IOWorker
import net.minecraft.world.level.chunk.storage.RegionFile
import net.minecraft.world.level.chunk.storage.RegionFileStorage
import net.minecraft.world.level.dimension.DimensionType
import net.minecraft.world.level.storage.LevelResource
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.computer.CaseTier
import pro.ikili.kibicomputers.core.computer.ComputerStatus
import pro.ikili.kibicomputers.core.computer.PartKind
import pro.ikili.kibicomputers.core.persist.FileModuleStore
import pro.ikili.kibicomputers.core.persist.MachineIdIndex
import pro.ikili.kibicomputers.core.persist.MachineOwner
import pro.ikili.kibicomputers.core.persist.MachineReference
import pro.ikili.kibicomputers.core.persist.MachineSaveStore
import pro.ikili.kibicomputers.core.persist.ModuleStore
import pro.ikili.kibicomputers.core.persist.OwnerRecord
import pro.ikili.kibicomputers.core.persist.SaveCleanup
import pro.ikili.kibicomputers.core.persist.SaveCleanupSettings
import pro.ikili.kibicomputers.core.persist.SaveWriter
import pro.ikili.kibicomputers.neoforge.computer.CaseAccess
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
import pro.ikili.kibicomputers.neoforge.machine.ChunkReferenceProbe
import pro.ikili.kibicomputers.neoforge.machine.SaveCleanups
import pro.ikili.kibicomputers.neoforge.machine.ServerMachines
import pro.ikili.kibicomputers.neoforge.machine.TransformActivity
import pro.ikili.kibicomputers.neoforge.machine.TransformAudit
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.Comparator
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * 残ったディレクトリの掃除（#564、ADR 0564）の GameTest。サーバーの置き場（開いたときの候補）は触らず、ワールドの下の別の根に
 * 前のセッションのディレクトリ（古い `owner.bin`）を作り、その根で開いた置き場と本物のチャンクの問い（[ChunkReferenceProbe]）で掃除する
 */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object SaveCleanupGameTests {
    private fun installRequired(be: CaseBlockEntity) {
        for (kind in PartKind.entries.filter { it.required && it != PartKind.BOOT }) {
            be.parts.setStackInSlot(be.tier.firstSlot(kind), ItemStack(Items.IRON_INGOT))
        }
    }

    private fun tempRoot(helper: GameTestHelper): Path =
        helper.level.server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()
            .resolve(KiBiComputers.MOD_ID).resolve("cleanup-test-${UUID.randomUUID()}").resolve("machines")

    /** 前のセッションの台のディレクトリ（`owner.bin` だけ。30 日前に更新）を作る */
    private fun previousDirectory(root: Path, id: UUID, owner: MachineOwner) {
        val dir = root.resolve(id.toString())
        val file = dir.resolve(OwnerRecord.FILE_NAME)
        OwnerRecord.write(file, OwnerRecord(0, owner))
        val old = FileTime.fromMillis(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
        Files.setLastModifiedTime(file, old)
        Files.setLastModifiedTime(dir, old)
    }

    /**
     * 今のディメンションの保存先に、バニラの名前のリージョンファイルを確かに作る（どのテストも読まない離れたチャンクに空のチャンクの NBT を書き、IOWorker を流す）。
     * チャンクの問いは、保存先にバニラの名前のリージョンファイルが1つもなければ「判定できない」にする（[ChunkReferenceProbe.regionAbsence]）ので、
     * まだチャンクを保存していない新しいワールドで「ない」を確かめるテストの前に呼ぶ
     */
    private fun ensureVanillaRegion(helper: GameTestHelper) {
        val map = helper.level.chunkSource.chunkMap
        map.write(FarChunks.reserve(helper), CompoundTag().apply { put("block_entities", ListTag()) })
        map.flushWorker()
    }

    private fun deleteTree(root: Path) {
        val base = root.parent
        if (Files.exists(base)) Files.walk(base).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    /**
     * ディスクのチャンクにケースがある位置の ID は残し、同じチャンクのケースのない位置・ないディメンションは、それぞれ消す・残す。
     * 読み込まれたチャンク（スポーン・チャンクローダーの周りの残骸もこれ）は、メモリのブロックエンティティで判定し、
     * その座標にその ID のケースがあれば残し、なければ消す
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 2400)
    fun cleanupDeletesOnlyUnreferencedDirectories(helper: GameTestHelper) {
        val level = helper.level
        val dimension = level.dimension().location().toString()
        val slot = FarChunks.allocate(helper)
        val chunk = slot.chunk
        val pos = slot.pos(3, 200, 5)
        level.setBlock(pos, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get().defaultBlockState(), 3)
        val be = level.getBlockEntity(pos) as CaseBlockEntity
        installRequired(be)
        helper.assertTrue(be.pressPowerButton() == ComputerStatus.RUNNING, "case should run but was ${be.computer}")
        slot.setFull(false)
        val root = tempRoot(helper)
        var caseId: UUID? = null
        val absent = UUID.randomUUID()
        val noDimension = UUID.randomUUID()
        val loaded = UUID.randomUUID()
        val loadedCasePos = helper.absolutePos(BlockPos(2, 1, 2))
        level.setBlock(loadedCasePos, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get().defaultBlockState(), 3)
        val loadedCase = level.getBlockEntity(loadedCasePos) as CaseBlockEntity
        // 読み込まれたケースの ID（NBT から読んだ ID。保存前の置いたばかりのケースは確定した ID を持たない）
        val loadedCaseId = UUID.randomUUID()
        loadedCase.loadCustomOnly(CompoundTag().apply { putString(CaseBlockEntity.MACHINE_ID_TAG, loadedCaseId.toString()) }, level.registryAccess())
        var loadedCaseDirectory: UUID? = null
        var cleanup: SaveCleanup? = null
        helper.startSequence().thenWaitUntil {
            // アンロードで取り出され、ディスクのチャンクに ID が載るまで待つ
            helper.assertTrue(!level.chunkSource.hasChunk(chunk.x, chunk.z), "the chunk should unload")
            val data = level.chunkSource.chunkMap.read(chunk).join().orElse(null)
            val tag = data?.getList("block_entities", Tag.TAG_COMPOUND.toInt())?.map { it as CompoundTag }
                ?.firstOrNull { it.getInt("x") == pos.x && it.getInt("y") == pos.y && it.getInt("z") == pos.z }
            helper.assertTrue(tag != null && tag.contains(CaseBlockEntity.MACHINE_ID_TAG), "the saved chunk should have the case with an id: $tag")
            caseId = UUID.fromString(tag!!.getString(CaseBlockEntity.MACHINE_ID_TAG))
        }.thenExecute {
            previousDirectory(root, loadedCaseId, MachineOwner(dimension, loadedCasePos.x, loadedCasePos.y, loadedCasePos.z))
            loadedCaseDirectory = loadedCaseId
            previousDirectory(root, caseId!!, MachineOwner(dimension, pos.x, pos.y, pos.z))
            previousDirectory(root, absent, MachineOwner(dimension, pos.x + 1, pos.y, pos.z))
            previousDirectory(root, noDimension, MachineOwner("kibicomputers:no_such_dimension", 0, 64, 0))
            val here = helper.absolutePos(BlockPos(0, 1, 0))
            previousDirectory(root, loaded, MachineOwner(dimension, here.x, here.y, here.z))
            val store = MachineSaveStore(root, MachineIdIndex(), "test")
            cleanup = SaveCleanup(store, null, ChunkReferenceProbe(level.server), SaveCleanupSettings(safetyMinimum = 100))
        }.thenWaitUntil {
            val c = cleanup!!
            c.tick()
            helper.assertTrue(c.finished, "the cleanup should finish (${c.phase})")
        }.thenExecute {
            try {
                val report = cleanup!!.report
                helper.assertTrue(report.candidates == 5, "five candidates: $report")
                helper.assertTrue(Files.isDirectory(root.resolve(caseId.toString())), "the directory of the saved case should stay: $report")
                helper.assertTrue(!Files.exists(root.resolve(absent.toString())), "the unreferenced directory should be deleted: $report")
                helper.assertTrue(Files.isDirectory(root.resolve(noDimension.toString())), "a missing dimension should keep the directory: $report")
                helper.assertTrue(!Files.exists(root.resolve(loaded.toString())), "a loaded chunk without the case should delete the directory: $report")
                helper.assertTrue(Files.isDirectory(root.resolve(loadedCaseDirectory.toString())), "a loaded chunk with the case should keep the directory: $report")
                helper.assertTrue(report.deleted == 2 && report.present == 2 && report.undetermined == 1, "counts: $report")
            } finally {
                deleteTree(root)
            }
        }.thenSucceed()
    }

    /** [SaveCleanups.start] の試しの実行は消さずに数え、`saves status` が結果を返す（コマンドの `cleanup` の経路は [cleanupCommandsRequireOpAndPassModes]） */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 400)
    fun dryRunCountsWithoutDeleting(helper: GameTestHelper) {
        val level = helper.level
        ensureVanillaRegion(helper)
        val root = tempRoot(helper)
        val absent = UUID.randomUUID()
        val far = FarChunks.reserve(helper)
        previousDirectory(root, absent, MachineOwner(level.dimension().location().toString(), far.minBlockX, 64, far.minBlockZ))
        val store = MachineSaveStore(root, MachineIdIndex(), "test")
        val cleanup = SaveCleanups.start(dryRun = true, force = false, store = store, modules = null)
        helper.assertTrue(cleanup != null, "the cleanup should start")
        helper.startSequence().thenWaitUntil {
            helper.assertTrue(cleanup!!.finished, "the cleanup should finish (${cleanup.phase})")
        }.thenExecute {
            try {
                val report = cleanup!!.report
                helper.assertTrue(report.unreferenced == 1 && report.deleted == 0, "dry run counts only: $report")
                helper.assertTrue(Files.isDirectory(root.resolve(absent.toString())), "dry run should not delete")
                helper.assertTrue(SaveCleanups.last === cleanup, "the last cleanup should be this one")
                val server = level.server
                val result = server.commands.dispatcher.execute("kibicomputers saves status", server.createCommandSourceStack())
                helper.assertTrue(result == 1, "saves status should report the last cleanup")
            } finally {
                deleteTree(root)
            }
        }.thenSucceed()
    }

    /**
     * 本番の経路 [SaveCleanups.start] が、検出した理由（[ChunkReferenceProbe.untrustedRegionStorage]）を問い（[ChunkReferenceProbe.regionNamesTrusted]）に渡す（#899）。
     * 保存先に別の座標のリージョンファイルだけがあり、台の位置のリージョンファイルがないとき、理由が空でなければ台のディレクトリは「判定できない」に数えられて残り、
     * 空なら「ない」に数えられる。検出（[vanillaRegionStorageIsTrusted]）と問い（[regionlessPositionIsKeptWhenRegionStorageIsModified]）の間をつなぐ部分を確かめる。
     * 共有の [SaveCleanups] を使うので、ほかの掃除のテスト（[dryRunCountsWithoutDeleting] など）と重ならないよう別の batch にする
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 800, batch = "kibicomputers_save_cleanup_untrusted")
    fun startPassesUntrustedReasonsToTheProbe(helper: GameTestHelper) {
        val level = helper.level
        val dimension = level.dimension().location().toString()
        ensureVanillaRegion(helper)
        val keptChunk = FarChunks.reserve(helper)
        val absentChunk = FarChunks.reserve(helper)
        val keptRoot = tempRoot(helper)
        val absentRoot = tempRoot(helper)
        val keptId = UUID.randomUUID()
        val absentId = UUID.randomUUID()
        previousDirectory(keptRoot, keptId, MachineOwner(dimension, keptChunk.minBlockX, 64, keptChunk.minBlockZ))
        previousDirectory(absentRoot, absentId, MachineOwner(dimension, absentChunk.minBlockX, 64, absentChunk.minBlockZ))
        val untrusted = SaveCleanups.start(
            dryRun = true, force = false, store = MachineSaveStore(keptRoot, MachineIdIndex(), "test"), modules = null,
            untrustedReasons = { listOf("test: mixin on the chunk storage") },
        )
        helper.assertTrue(untrusted != null, "the cleanup with untrusted reasons should start")
        var trusted: SaveCleanup? = null
        helper.startSequence().thenWaitUntil {
            helper.assertTrue(untrusted!!.finished, "the first cleanup should finish (${untrusted.phase})")
        }.thenExecute {
            val report = untrusted!!.report
            helper.assertTrue(report.candidates == 1 && report.undetermined == 1 && report.unreferenced == 0, "untrusted reasons should keep the directory: $report")
            trusted = SaveCleanups.start(
                dryRun = true, force = false, store = MachineSaveStore(absentRoot, MachineIdIndex(), "test"), modules = null,
                untrustedReasons = { emptyList() },
            )
            helper.assertTrue(trusted != null, "the cleanup without untrusted reasons should start")
        }.thenWaitUntil {
            helper.assertTrue(trusted!!.finished, "the second cleanup should finish (${trusted!!.phase})")
        }.thenExecute {
            try {
                val report = trusted!!.report
                helper.assertTrue(report.candidates == 1 && report.unreferenced == 1 && report.undetermined == 0, "no reasons should let the file names decide: $report")
                helper.assertTrue(!Files.exists(ChunkReferenceProbe.regionFile(level, keptChunk)), "the cleanup should not create region files")
            } finally {
                deleteTree(keptRoot)
                deleteTree(absentRoot)
            }
        }.thenSucceed()
    }

    /**
     * サーバーと同じ形の置き場（書き込みのスレッド [SaveWriter] で仕事を走らせる）とモジュールの置き場で掃除すると、指されていないディレクトリと、
     * 参照されていないモジュール・古い一時ファイルを、書き込みのスレッドの仕事（[MachineSaveStore.readModuleReferences]・[MachineSaveStore.runOnWriter]）で消す
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 1200)
    fun cleanupOnWriterThreadDeletesDirectoriesAndModules(helper: GameTestHelper) {
        val level = helper.level
        ensureVanillaRegion(helper)
        val root = tempRoot(helper)
        val modulesRoot = root.resolveSibling("modules")
        val absent = UUID.randomUUID()
        val far = FarChunks.reserve(helper)
        previousDirectory(root, absent, MachineOwner(level.dimension().location().toString(), far.minBlockX, 64, far.minBlockZ))
        // 前のセッションのモジュール（どの世代も参照しない）と、古い一時ファイル
        val bytes = "unused-${UUID.randomUUID()}".toByteArray()
        val hash = ModuleStore.sha256(bytes)
        helper.assertTrue(FileModuleStore(modulesRoot).put(hash, bytes), "the module should be written")
        val module = modulesRoot.resolve("${ModuleStore.hex(hash)}.wasm")
        val stale = modulesRoot.resolve("stale.tmp")
        Files.writeString(stale, "x")
        val old = FileTime.fromMillis(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
        Files.setLastModifiedTime(module, old)
        Files.setLastModifiedTime(stale, old)
        val writer = SaveWriter("kibicomputers-cleanup-gametest")
        val store = MachineSaveStore(root, MachineIdIndex(), "test", writer)
        val cleanup = SaveCleanup(store, FileModuleStore(modulesRoot), ChunkReferenceProbe(level.server), SaveCleanupSettings(safetyMinimum = 100))
        helper.startSequence().thenWaitUntil {
            cleanup.tick()
            helper.assertTrue(cleanup.finished, "the cleanup should finish (${cleanup.phase})")
        }.thenExecute {
            try {
                val report = cleanup.report
                helper.assertTrue(cleanup.phase == SaveCleanup.Phase.DONE, "the cleanup should be done: ${cleanup.phase}")
                helper.assertTrue(report.deleted == 1 && !Files.exists(root.resolve(absent.toString())), "the unreferenced directory should be deleted: $report")
                helper.assertTrue(!report.modulesAborted && report.modulesDeleted == 1 && !Files.exists(module), "the unreferenced module should be deleted: $report")
                helper.assertTrue(report.tempsDeleted == 1 && !Files.exists(stale), "the stale temporary file should be deleted: $report")
            } finally {
                writer.close(10_000)
                deleteTree(root)
            }
        }.thenSucceed()
    }

    /**
     * 自動の掃除の経路（[SaveCleanups.armAuto]・tick）は、決めた tick 数の後にだけ始まり、停止（[SaveCleanups.stop]）で止まって何も消さない。
     * 本番の 600 tick の仕掛けと [net.neoforged.neoforge.event.server.ServerStoppingEvent] の取り消しと同じ口を通す。
     * 共有の [SaveCleanups] を使うので、ほかのテストと重ならないよう別の batch にする
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 400, batch = "kibicomputers_save_cleanup_auto")
    fun autoCleanupStartsLateAndStopCancels(helper: GameTestHelper) {
        val level = helper.level
        val root = tempRoot(helper)
        val absent = UUID.randomUUID()
        val far = FarChunks.reserve(helper)
        previousDirectory(root, absent, MachineOwner(level.dimension().location().toString(), far.minBlockX, 64, far.minBlockZ))
        val store = MachineSaveStore(root, MachineIdIndex(), "test")
        val before = SaveCleanups.last
        var armedAt = -1L
        helper.startSequence().thenExecute {
            armedAt = level.gameTime
            SaveCleanups.armAuto(5, store, null)
            helper.assertTrue(SaveCleanups.last === before, "the cleanup should not start at once")
        }.thenWaitUntil {
            // 始まった tick で止める（結果は次の tick に返るので、まだ終わっていない）
            helper.assertTrue(SaveCleanups.last !== before, "the automatic cleanup should start")
            helper.assertTrue(level.gameTime - armedAt >= 4, "the cleanup should start after the delay (${level.gameTime - armedAt})")
            SaveCleanups.stop()
        }.thenIdle(20).thenExecute {
            try {
                val cleanup = SaveCleanups.last!!
                helper.assertTrue(cleanup.phase == SaveCleanup.Phase.CANCELLED, "stopping should cancel the cleanup (${cleanup.phase})")
                helper.assertTrue(cleanup.report.deleted == 0 && Files.isDirectory(root.resolve(absent.toString())), "a cancelled cleanup should delete nothing: ${cleanup.report}")
            } finally {
                deleteTree(root)
            }
        }.thenSucceed()
    }

    /** [probe] に問い、答え（どのスレッドから来てもよい）を受け取る入れ物を返す */
    private fun ask(probe: ChunkReferenceProbe, id: UUID, dimension: String, pos: BlockPos): AtomicReference<MachineReference?> {
        val answer = AtomicReference<MachineReference?>(null)
        probe.probe(id, MachineOwner(dimension, pos.x, pos.y, pos.z)) { answer.set(it) }
        return answer
    }

    /**
     * holder はあるが FULL でないチャンク（keeper で INACCESSIBLE に降りた `LevelChunk`、続くアンロード待ち）では、ケースがなくても
     * 「判定できない」になる（メモリにだけ新しい状態があるかもしれないので、消す側に倒さない）。FULL のうちは「ない」、アンロードされてディスクから読めば「ない」
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 2400)
    fun notFullChunkIsUndetermined(helper: GameTestHelper) {
        val level = helper.level
        val dimension = level.dimension().location().toString()
        val slot = FarChunks.allocate(helper, keeper = true)
        val chunk = slot.chunk
        val pos = slot.pos(4, 100, 4)
        val id = UUID.randomUUID()
        val probe = ChunkReferenceProbe(level.server)
        var unloadingProbes = 0
        var unloaded: AtomicReference<MachineReference?>? = null
        helper.startSequence().thenExecute {
            val full = ask(probe, id, dimension, pos).get()
            helper.assertTrue(full == MachineReference.ABSENT, "a FULL chunk without the case should be absent: $full")
            slot.setFull(false)
        }.thenWaitUntil {
            // keeper がアンロードを止めるので、holder は残ったまま FULL でなくなる
            val holder = ServerMachines.findHolder(level, chunk.toLong())
            helper.assertTrue(holder != null && !holder.fullStatus.isOrAfter(FullChunkStatus.FULL), "the chunk should drop below FULL: ${holder?.fullStatus}")
            helper.assertTrue(holder!!.latestChunk is LevelChunk, "the holder should keep the LevelChunk: ${holder.latestChunk}")
        }.thenExecute {
            val answer = ask(probe, id, dimension, pos).get()
            helper.assertTrue(answer == MachineReference.UNKNOWN, "an inaccessible LevelChunk should be undetermined: $answer")
            slot.releaseKeeper()
        }.thenWaitUntil {
            // アンロードが終わるまで（アンロード待ちの表に入っている間も）、tick ごとに問い、ずっと「判定できない」
            if (ServerMachines.findHolder(level, chunk.toLong()) != null) {
                val answer = ask(probe, id, dimension, pos).get()
                helper.assertTrue(answer == MachineReference.UNKNOWN, "a chunk with a holder below FULL should be undetermined: $answer")
                unloadingProbes++
                helper.fail("the chunk should unload")
            }
            val pending = unloaded ?: ask(probe, id, dimension, pos).also { unloaded = it }
            helper.assertTrue(pending.get() != null, "the probe of the unloaded chunk should answer")
        }.thenExecute {
            helper.assertTrue(unloadingProbes > 0, "the probe should be asked while unloading")
            val answer = unloaded!!.get()
            helper.assertTrue(answer == MachineReference.ABSENT, "the saved chunk without the case should be absent: $answer")
        }.thenSucceed()
    }

    /**
     * リージョンファイルのない位置は、読んでリージョンファイルを作らずに「ない」と答える。リージョンファイルのある所の、IOWorker の書き込み待ち
     * （まだリージョンに出ていないかもしれないチャンクの NBT）にケースがあれば「ある」と答える
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 2400)
    fun regionlessPositionsAreAbsentWithoutCreatingRegions(helper: GameTestHelper) {
        val level = helper.level
        val dimension = level.dimension().location().toString()
        val probe = ChunkReferenceProbe(level.server)
        ensureVanillaRegion(helper)
        val empty = FarChunks.reserve(helper)
        val emptyRegion = ChunkReferenceProbe.regionFile(level, empty)
        helper.assertTrue(!Files.exists(emptyRegion), "the region of the reserved chunk should not exist yet: $emptyRegion")
        val absent = ask(probe, UUID.randomUUID(), dimension, BlockPos(empty.minBlockX, 64, empty.minBlockZ))
        // リージョンファイルを作る: 離れたチャンクを読み込んでアンロードさせる
        val slot = FarChunks.allocate(helper)
        slot.setFull(false)
        val region = ChunkReferenceProbe.regionFile(level, slot.chunk)
        // 同じリージョンの、読み込みの届かない（16 チャンク離れた）チャンクに、書き込み待ちのケースを入れる
        val minX = slot.chunk.regionX * 32
        val pending = ChunkPos(minX + (slot.chunk.x - minX + 16) % 32, slot.chunk.z)
        val id = UUID.randomUUID()
        val pos = BlockPos(pending.minBlockX + 1, 70, pending.minBlockZ + 2)
        var present: AtomicReference<MachineReference?>? = null
        var otherId: AtomicReference<MachineReference?>? = null
        var otherType: AtomicReference<MachineReference?>? = null
        helper.startSequence().thenWaitUntil {
            helper.assertTrue(absent.get() != null, "the probe of the regionless position should answer")
            helper.assertTrue(ServerMachines.findHolder(level, slot.chunk.toLong()) == null, "the far chunk should unload")
            helper.assertTrue(Files.exists(region), "the region file $region should be written")
        }.thenExecute {
            helper.assertTrue(absent.get() == MachineReference.ABSENT, "a position without a region file should be absent: ${absent.get()}")
            helper.assertTrue(!Files.exists(emptyRegion), "the probe should not create the region file $emptyRegion")
            helper.assertTrue(ServerMachines.findHolder(level, pending.toLong()) == null, "the pending chunk $pending should not be loaded")
            val caseTag = CompoundTag().apply {
                putString("id", BlockEntityType.getKey(KiBiBlocks.CASE_BLOCK_ENTITY.get()).toString())
                putInt("x", pos.x)
                putInt("y", pos.y)
                putInt("z", pos.z)
                putString(CaseBlockEntity.MACHINE_ID_TAG, id.toString())
            }
            // 同じ ID のタグを持つ、別の種類のブロックエンティティ（隣の位置）
            val chestTag = caseTag.copy().apply {
                putString("id", BlockEntityType.getKey(BlockEntityType.CHEST).toString())
                putInt("x", pos.x + 1)
            }
            // アンロード直後と同じく、IOWorker に NBT を入れた直後に問う
            level.chunkSource.chunkMap.write(pending, CompoundTag().apply { put("block_entities", ListTag().apply { add(caseTag); add(chestTag) }) })
            present = ask(probe, id, dimension, pos)
        }.thenWaitUntil {
            helper.assertTrue(present!!.get() != null, "the probe of the pending chunk should answer")
        }.thenExecute {
            helper.assertTrue(present!!.get() == MachineReference.PRESENT, "the pending chunk with the case should be present: ${present.get()}")
            // 同じ位置でも、ID が違えば「ない」（ID の振り直しの残骸の形）。別の種類のブロックエンティティは、ID のタグが同じでも「ない」
            otherId = ask(probe, UUID.randomUUID(), dimension, pos)
            otherType = ask(probe, id, dimension, BlockPos(pos.x + 1, pos.y, pos.z))
        }.thenWaitUntil {
            helper.assertTrue(otherId!!.get() != null && otherType!!.get() != null, "the probes of the pending chunk should answer")
        }.thenExecute {
            helper.assertTrue(otherId!!.get() == MachineReference.ABSENT, "another id at the case position should be absent: ${otherId.get()}")
            helper.assertTrue(otherType!!.get() == MachineReference.ABSENT, "another block entity type with the same id tag should be absent: ${otherType.get()}")
        }.thenSucceed()
    }

    /**
     * ディスクのチャンクの NBT（[ChunkReferenceProbe.referenceIn]）は、位置・ブロックエンティティの種類・ID がそろったときだけ「ある」。
     * ID が違う（振り直し）・位置が違う・種類が違う（ID のタグが同じでも）は「ない」
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun diskReferenceRequiresPositionTypeAndId(helper: GameTestHelper) {
        val pos = BlockPos(10, 70, 20)
        val id = UUID.randomUUID()
        fun tag(type: String, at: BlockPos, machineId: UUID) = CompoundTag().apply {
            putString("id", type)
            putInt("x", at.x)
            putInt("y", at.y)
            putInt("z", at.z)
            putString(CaseBlockEntity.MACHINE_ID_TAG, machineId.toString())
        }
        val caseType = BlockEntityType.getKey(KiBiBlocks.CASE_BLOCK_ENTITY.get()).toString()
        val chestType = BlockEntityType.getKey(BlockEntityType.CHEST).toString()
        val data = CompoundTag().apply {
            put(
                "block_entities",
                ListTag().apply {
                    add(tag(caseType, pos, id))
                    add(tag(chestType, pos.east(), id))
                    add(tag(caseType, pos.north(), UUID.randomUUID()))
                },
            )
        }
        fun check(expected: MachineReference, at: BlockPos, machineId: UUID, what: String) {
            val actual = ChunkReferenceProbe.referenceIn(data, machineId, at)
            helper.assertTrue(actual == expected, "$what should be $expected but was $actual")
        }
        check(MachineReference.PRESENT, pos, id, "the case with the same id")
        check(MachineReference.ABSENT, pos, UUID.randomUUID(), "the case with another id")
        check(MachineReference.ABSENT, pos.east(), id, "another block entity type with the same id tag")
        check(MachineReference.ABSENT, pos.north(), id, "the case at that position with another id")
        check(MachineReference.ABSENT, pos.above(), id, "a position without a block entity")
        helper.succeed()
    }

    /**
     * 読み込まれたチャンク（メモリ）の問いも、ID が違うケース・別の種類のブロックエンティティは「ない」。
     * 同じ ID は「ある」。ID の振り直しの残骸が、読み込まれているチャンクで消える側になる
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun memoryReferenceRequiresTypeAndId(helper: GameTestHelper) {
        val level = helper.level
        val dimension = level.dimension().location().toString()
        val probe = ChunkReferenceProbe(level.server)
        val casePos = helper.absolutePos(BlockPos(1, 1, 1))
        level.setBlock(casePos, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get().defaultBlockState(), 3)
        val case = level.getBlockEntity(casePos) as CaseBlockEntity
        val id = UUID.randomUUID()
        case.loadCustomOnly(CompoundTag().apply { putString(CaseBlockEntity.MACHINE_ID_TAG, id.toString()) }, level.registryAccess())
        val chestPos = helper.absolutePos(BlockPos(1, 1, 0))
        level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3)
        helper.assertTrue(level.getBlockEntity(chestPos) != null, "the chest should have a block entity")
        fun check(expected: MachineReference, at: BlockPos, machineId: UUID, what: String) {
            // holder があるチャンクはメインスレッドで同期に答える
            val actual = ask(probe, machineId, dimension, at).get()
            helper.assertTrue(actual == expected, "$what should be $expected but was $actual")
        }
        check(MachineReference.PRESENT, casePos, id, "the loaded case with the same id")
        check(MachineReference.ABSENT, casePos, UUID.randomUUID(), "the loaded case with another id")
        check(MachineReference.ABSENT, chestPos, id, "another block entity type at the position")
        check(MachineReference.ABSENT, helper.absolutePos(BlockPos(2, 1, 2)), id, "a position without a block entity")
        helper.succeed()
    }

    /**
     * 保存先に別の座標のリージョンファイルだけがあり、台の位置のリージョンファイルがないとき、チャンクを読み書きする側が既定の作りと確かめられなければ
     * （[ChunkReferenceProbe.regionNamesTrusted] が偽。リージョン形式を変える MOD への移行の後の混ざった状態など）、台のディレクトリは残り、
     * 「判定できない」に数えられる（#899）。リージョンファイルも作らない。同じ位置は、既定の作りなら「ない」になる（別の座標のファイルが前提の対照）。
     * 書き込み待ちのチャンク（リージョンにまだ出ていないかもしれない）のケースも「ない」にならない
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 1200)
    fun regionlessPositionIsKeptWhenRegionStorageIsModified(helper: GameTestHelper) {
        val level = helper.level
        val server = level.server
        val dimension = level.dimension().location().toString()
        ensureVanillaRegion(helper)
        val target = FarChunks.reserve(helper)
        val targetRegion = ChunkReferenceProbe.regionFile(level, target)
        helper.assertTrue(!Files.exists(targetRegion), "the region of the target chunk should not exist yet: $targetRegion")
        val others = Files.newDirectoryStream(targetRegion.parent, "r.*.mca").use { dir -> dir.map { it.fileName.toString() } }
        helper.assertTrue(others.isNotEmpty(), "the storage should have a region file of another position")
        val pos = BlockPos(target.minBlockX + 2, 64, target.minBlockZ + 3)
        val id = UUID.randomUUID()
        val modified = ChunkReferenceProbe(server, regionNamesTrusted = false)
        val untrusted = ask(modified, id, dimension, pos)
        val trusted = ask(ChunkReferenceProbe(server, regionNamesTrusted = true), id, dimension, pos)
        // 書き込み待ちのケース（ほかのテストが読まない別の離れたチャンク。リージョンファイルはまだない）
        val pendingChunk = FarChunks.reserve(helper)
        val pendingRegion = ChunkReferenceProbe.regionFile(level, pendingChunk)
        val pendingPos = BlockPos(pendingChunk.minBlockX + 1, 70, pendingChunk.minBlockZ + 1)
        val pendingId = UUID.randomUUID()
        helper.assertTrue(!Files.exists(pendingRegion), "the region of the pending chunk should not exist yet: $pendingRegion")
        val caseTag = CompoundTag().apply {
            putString("id", BlockEntityType.getKey(KiBiBlocks.CASE_BLOCK_ENTITY.get()).toString())
            putInt("x", pendingPos.x)
            putInt("y", pendingPos.y)
            putInt("z", pendingPos.z)
            putString(CaseBlockEntity.MACHINE_ID_TAG, pendingId.toString())
        }
        level.chunkSource.chunkMap.write(pendingChunk, CompoundTag().apply { put("block_entities", ListTag().apply { add(caseTag) }) })
        val pending = ask(modified, pendingId, dimension, pendingPos)
        val root = tempRoot(helper)
        previousDirectory(root, id, MachineOwner(dimension, pos.x, pos.y, pos.z))
        val store = MachineSaveStore(root, MachineIdIndex(), "test")
        val cleanup = SaveCleanup(store, null, modified, SaveCleanupSettings.DEFAULT)
        helper.startSequence().thenWaitUntil {
            helper.assertTrue(untrusted.get() != null && trusted.get() != null && pending.get() != null, "the probes should answer")
            cleanup.tick()
            helper.assertTrue(cleanup.finished, "the cleanup should finish (${cleanup.phase})")
        }.thenExecute {
            try {
                helper.assertTrue(untrusted.get() == MachineReference.UNKNOWN, "a regionless position with modified storage should be undetermined: ${untrusted.get()}")
                helper.assertTrue(trusted.get() == MachineReference.ABSENT, "a regionless position with vanilla storage should be absent: ${trusted.get()}")
                // IOWorker が先に書き出していれば読んで「ある」、まだなら「判定できない」。どちらでも消す側にはならない
                val p = pending.get()
                helper.assertTrue(p == MachineReference.PRESENT || p == MachineReference.UNKNOWN, "a pending case with modified storage should not be absent: $p")
                val report = cleanup.report
                helper.assertTrue(Files.isDirectory(root.resolve(id.toString())), "the directory should stay: $report")
                helper.assertTrue(report.candidates == 1 && report.undetermined == 1 && report.deleted == 0 && report.unreferenced == 0, "counts: $report")
                helper.assertTrue(!Files.exists(targetRegion), "the probes should not create the region file $targetRegion")
            } finally {
                deleteTree(root)
            }
        }.thenSucceed()
    }

    /**
     * 実際のチャンクの読み書きの経路（`RegionFileStorage`・`RegionFile`・`IOWorker`・`ChunkStorage`）は、GameTest のサーバー（と製品の jar のサーバー）では
     * Mixin が当たっておらず（[ChunkReferenceProbe.modifiedRegionStorage] が空）、全ディメンションのインスタンスもバニラのクラスそのもので
     * （[ChunkReferenceProbe.substitutedRegionStorage] が空）、既定の問いはファイル名で判定する。
     * 合わせたメンバーの印（[ChunkReferenceProbe.hasMergedMembers]）は、印の付いたメソッドのあるクラスで見つかる（`@MixinMerged` はソースに書けないので、テスト用の実行時の注釈 [TestMark] で確かめる）。
     * インスタンスの差し替え（サブクラス・ない）は [ChunkReferenceProbe.substitutedInstances] が見つける
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun vanillaRegionStorageIsTrusted(helper: GameTestHelper) {
        val server = helper.level.server
        val modified = ChunkReferenceProbe.modifiedRegionStorage()
        helper.assertTrue(modified.isEmpty(), "the chunk storage classes should have no mixins: $modified")
        val substituted = ChunkReferenceProbe.substitutedRegionStorage(server)
        helper.assertTrue(substituted.isEmpty(), "the chunk storage instances should be vanilla: $substituted")
        // 変換の記録（#959）: ModLauncher の記録が引け、読み書きの経路の5クラスに数える変換がない（製品の jar では AT が plugin として当たる）
        val audit = ChunkReferenceProbe.modLauncherAudit()
        helper.assertTrue(audit != null, "the transformer audit trail should be available")
        AUDIT_LOGGER.log(System.Logger.Level.INFO) {
            "Transformer audit of the chunk storage: " + AUDITED.joinToString("; ") { type -> "${type.name}=${audit?.activities(type.name)?.map { it.describe() }}" }
        }
        val transformed = ChunkReferenceProbe.transformedRegionStorage(audit)
        helper.assertTrue(transformed.isEmpty(), "the chunk storage classes should have no transformers: $transformed")
        helper.assertTrue(ChunkReferenceProbe.untrustedRegionStorage(server).isEmpty(), "the chunk storage should be trusted")
        helper.assertTrue(ChunkReferenceProbe(server).regionNamesTrusted, "the default probe should trust the region names")
        helper.assertTrue(ChunkReferenceProbe.hasMergedMembers(MarkedMember::class.java, TestMark::class.java.name), "a marked method should be found")
        helper.assertTrue(!ChunkReferenceProbe.hasMergedMembers(UnmarkedMember::class.java, TestMark::class.java.name), "an unmarked class should have no mark")
        helper.assertTrue(!ChunkReferenceProbe.isMixinTarget(UnmarkedMember::class.java), "a plain class should not be a mixin target")
        // インスタンスがクラスそのものなら差し替えでない。サブクラス・ないものは差し替え（ないものは確かめられない）
        val same = ChunkReferenceProbe.substitutedInstances(listOf(BaseStorage::class.java to BaseStorage()))
        helper.assertTrue(same.isEmpty(), "an exact instance should not be substituted: $same")
        val sub = ChunkReferenceProbe.substitutedInstances(listOf(BaseStorage::class.java to SubStorage(), BaseStorage::class.java to null))
        helper.assertTrue(sub == listOf(SubStorage::class.java.name, "null"), "a subclass and a missing instance should be substituted: $sub")
        helper.succeed()
    }

    private val AUDIT_LOGGER = System.getLogger("kibicomputers.gametest")

    /** 変換の記録を見るクラス（[ChunkReferenceProbe.transformedRegionStorage] の既定と同じ5つ。ログに出す用） */
    private val AUDITED = listOf(RegionFileStorage::class.java, RegionFile::class.java, IOWorker::class.java, ChunkStorage::class.java, ChunkMap::class.java)

    /** テスト用の coremod の変換の記録（FML の coremod は、サービス名 `fml` の TRANSFORMER として残る） */
    private val COREMOD = TransformActivity(TransformActivity.TRANSFORMER, listOf("fml", "kibicomputers_test_coremod"))

    /**
     * 変換の記録での判定（[ChunkReferenceProbe.transformedRegionStorage]。#959）。偽の記録で、数えないもの（記録なし、`accesstransformer`・`mixin`・`runtimedistcleaner` の PLUGIN、REASON）と、
     * 数えるもの（TRANSFORMER、知らない PLUGIN、知らない種類、記録が引けない・読めない、`TransformingClassLoader` で読み込まれていない）を確かめる。
     * 陽性の対照として、NeoForge 自身の coremod（`finalize_spawn_targets.json`）が当たる `EntityType` の本物の記録に TRANSFORMER `fml` があり、同じ判定で数えられることも見る
     * （この環境の記録が生きていて、本物の coremod が見えること。対照は NeoForge の版に依存するので、本番の自己診断にはしない）
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun transformAuditCountsCoremods(helper: GameTestHelper) {
        val classes = listOf(RegionFileStorage::class.java)
        fun reasons(activities: List<TransformActivity>?, transforming: Boolean = true): List<String> =
            ChunkReferenceProbe.transformedRegionStorage(TransformAudit { activities }, classes) { transforming }
        fun plugin(name: String) = TransformActivity(TransformActivity.PLUGIN, listOf(name, "A"))
        fun reason(name: String) = TransformActivity(TransformActivity.REASON, listOf(name))
        helper.assertTrue(reasons(emptyList()).isEmpty(), "no activity should not count")
        val harmless = listOf(reason("classloading"), plugin("accesstransformer"), plugin("mixin"), plugin("runtimedistcleaner"), reason("computing_frames"))
        helper.assertTrue(reasons(harmless).isEmpty(), "harmless plugins and reasons should not count: ${reasons(harmless)}")
        val coremod = reasons(harmless + COREMOD)
        helper.assertTrue(coremod.size == 1 && "TRANSFORMER fml:kibicomputers_test_coremod" in coremod[0], "a coremod transformer should count: $coremod")
        helper.assertTrue(reasons(listOf(plugin("slf4jfixer"))).size == 1, "an unknown plugin should count")
        helper.assertTrue(reasons(listOf(TransformActivity(TransformActivity.PLUGIN, emptyList()))).size == 1, "a plugin without a name should count")
        // 前方一致では許さない
        helper.assertTrue(reasons(listOf(plugin("mixin_extras"))).size == 1, "a plugin that only starts with a harmless name should count")
        helper.assertTrue(reasons(listOf(TransformActivity("OTHER", listOf("x")))).size == 1, "an unknown activity type should count")
        helper.assertTrue(reasons(null).size == 1, "a missing trail for the class should count")
        helper.assertTrue(reasons(emptyList(), transforming = false).size == 1, "a class outside the transforming class loader should count")
        val none = ChunkReferenceProbe.transformedRegionStorage(null, classes) { true }
        helper.assertTrue(none.size == 1, "an unavailable trail should count: $none")
        val failing = ChunkReferenceProbe.transformedRegionStorage(TransformAudit { throw ConcurrentModificationException() }, classes) { true }
        helper.assertTrue(failing.size == 1, "an unreadable trail should count: $failing")
        // 本物の記録: 読み書きの経路のクラスは TransformingClassLoader で読み込まれ、NeoForge の coremod の当たるクラスは数えられる
        val audit = ChunkReferenceProbe.modLauncherAudit()
        helper.assertTrue(audit != null, "the transformer audit trail should be available")
        helper.assertTrue(ChunkReferenceProbe.transformedRegionStorage(audit, classes).isEmpty(), "the real RegionFileStorage should not count")
        val entityType = ChunkReferenceProbe.transformedRegionStorage(audit, listOf(EntityType::class.java))
        helper.assertTrue(
            entityType.size == 1 && "TRANSFORMER fml" in entityType[0],
            "the NeoForge coremod on EntityType should count: $entityType (${audit?.activities(EntityType::class.java.name)?.map { it.describe() }})",
        )
        helper.succeed()
    }

    /**
     * Issue #959 の端から端まで: 実行時のクラス・インスタンス・Mixin の印は本物のままで、変換の記録の口にだけ「`RegionFileStorage` に coremod の TRANSFORMER がある」
     * 偽の記録を渡し、[SaveCleanups.start] で本番の掃除（消す側。猶予の外の台）を回す。台の位置にはリージョンファイルがなく、別の座標の `.mca` だけがある。
     * coremod が別の名前の保存先に読み書きを移した環境の代わりに、台の位置のチャンクにケースを書き込み待ちで入れる（`ChunkMap.read` からは読めるが、
     * その位置の `r.X.Z.mca` はまだない）形も見る。どちらの台のディレクトリも残り（「判定できない」か「ある」）、消されない。
     * 対照: 記録が本物なら、ケースのない位置は「ない」に数えられる（試しの実行）。共有の [SaveCleanups] を使うので、別の batch にする
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 1200, batch = "kibicomputers_save_cleanup_coremod")
    fun regionlessPositionIsKeptWhenCoremodTransformsStorage(helper: GameTestHelper) {
        val level = helper.level
        val dimension = level.dimension().location().toString()
        ensureVanillaRegion(helper)
        val target = FarChunks.reserve(helper)
        val targetRegion = ChunkReferenceProbe.regionFile(level, target)
        helper.assertTrue(!Files.exists(targetRegion), "the region of the target chunk should not exist yet: $targetRegion")
        val pendingChunk = FarChunks.reserve(helper)
        helper.assertTrue(!Files.exists(ChunkReferenceProbe.regionFile(level, pendingChunk)), "the region of the pending chunk should not exist yet")
        val controlChunk = FarChunks.reserve(helper)
        val root = tempRoot(helper)
        val controlRoot = tempRoot(helper)
        val id = UUID.randomUUID()
        val pendingId = UUID.randomUUID()
        val controlId = UUID.randomUUID()
        previousDirectory(root, id, MachineOwner(dimension, target.minBlockX + 2, 64, target.minBlockZ + 3))
        val pendingPos = BlockPos(pendingChunk.minBlockX + 1, 70, pendingChunk.minBlockZ + 1)
        val caseTag = CompoundTag().apply {
            putString("id", BlockEntityType.getKey(KiBiBlocks.CASE_BLOCK_ENTITY.get()).toString())
            putInt("x", pendingPos.x)
            putInt("y", pendingPos.y)
            putInt("z", pendingPos.z)
            putString(CaseBlockEntity.MACHINE_ID_TAG, pendingId.toString())
        }
        level.chunkSource.chunkMap.write(pendingChunk, CompoundTag().apply { put("block_entities", ListTag().apply { add(caseTag) }) })
        previousDirectory(root, pendingId, MachineOwner(dimension, pendingPos.x, pendingPos.y, pendingPos.z))
        previousDirectory(controlRoot, controlId, MachineOwner(dimension, controlChunk.minBlockX, 64, controlChunk.minBlockZ))
        val real = ChunkReferenceProbe.modLauncherAudit()
        helper.assertTrue(real != null, "the transformer audit trail should be available")
        val fake = TransformAudit { name ->
            val activities = real!!.activities(name)
            if (name == RegionFileStorage::class.java.name) activities.orEmpty() + COREMOD else activities
        }
        var reasons: List<String> = emptyList()
        val cleanup = SaveCleanups.start(
            dryRun = false, force = false, store = MachineSaveStore(root, MachineIdIndex(), "test"), modules = null,
            untrustedReasons = { server -> ChunkReferenceProbe.untrustedRegionStorage(server, fake).also { reasons = it } },
        )
        helper.assertTrue(cleanup != null, "the cleanup should start")
        helper.assertTrue(reasons.size == 1 && reasons[0].startsWith(RegionFileStorage::class.java.name), "only the fake coremod should be a reason: $reasons")
        var control: SaveCleanup? = null
        helper.startSequence().thenWaitUntil {
            helper.assertTrue(cleanup!!.finished && !cleanup.settling, "the cleanup should finish (${cleanup.phase})")
        }.thenExecute {
            val report = cleanup!!.report
            helper.assertTrue(Files.isDirectory(root.resolve(id.toString())), "the directory at the regionless position should stay: $report")
            helper.assertTrue(Files.isDirectory(root.resolve(pendingId.toString())), "the directory of the pending case should stay: $report")
            helper.assertTrue(report.candidates == 2 && report.deleted == 0 && report.unreferenced == 0, "nothing should be deleted: $report")
            helper.assertTrue(report.undetermined + report.present == 2 && report.undetermined >= 1, "the regionless position should be undetermined: $report")
            helper.assertTrue(!Files.exists(targetRegion), "the cleanup should not create the region file $targetRegion")
            control = SaveCleanups.start(dryRun = true, force = false, store = MachineSaveStore(controlRoot, MachineIdIndex(), "test"), modules = null)
            helper.assertTrue(control != null, "the control cleanup should start")
        }.thenWaitUntil {
            helper.assertTrue(control!!.finished, "the control cleanup should finish (${control!!.phase})")
        }.thenExecute {
            try {
                val report = control!!.report
                helper.assertTrue(report.candidates == 1 && report.unreferenced == 1 && report.undetermined == 0, "the real trail should let the file names decide: $report")
                helper.assertTrue(!Files.exists(ChunkReferenceProbe.regionFile(level, controlChunk)), "the control should not create region files")
            } finally {
                deleteTree(root)
                deleteTree(controlRoot)
            }
        }.thenSucceed()
    }

    /**
     * 本物の Mixin が当たったクラスでの検出。NeoForge 自身が `neoforge.mixins.json` で `BlockEntityType`・`MappedRegistry` にアクセサーの Mixin を当てているので、
     * 本物の `@MixinMerged` が見つかること、その Mixin がインターフェース（アクセサーだけ）と判定されて数えられないこと（本物のクラスローダーでクラスファイルが読めること）を確かめる。
     * 数える側（処理を書き換える Mixin の印、`mixin` が読めない印、クラスファイルが読めない Mixin の印）は、`@MixinMerged` と同じ形（`mixin` を持つ）のテスト用の印
     * [TestMerged] を付けたクラスで、本番と同じ [ChunkReferenceProbe.isMixinTarget] の経路を通して確かめる（#899）
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun mixinTargetIsDetectedOnRealMixins(helper: GameTestHelper) {
        for (type in listOf(BlockEntityType::class.java, MappedRegistry::class.java)) {
            val mixins = type.declaredMethods.flatMap { it.declaredAnnotations.toList() }.filter { it.annotationClass.java.name == MIXIN_MERGED }
            helper.assertTrue(ChunkReferenceProbe.hasMergedMembers(type), "the real mixin on ${type.name} should leave a merged mark")
            helper.assertTrue(mixins.isNotEmpty(), "the merged mark should be readable on ${type.name}")
            val loader = type.classLoader
            val names = mixins.map { mergedName(it) }
            helper.assertTrue(names.all { ChunkReferenceProbe.isAccessorMixin(loader, it) }, "the accessor mixins of ${type.name} should be interfaces: $names")
            helper.assertTrue(!ChunkReferenceProbe.isMixinTarget(type), "accessor-only mixins on ${type.name} should not count: $names")
        }
        val merged = TestMerged::class.java.name
        // 処理を書き換える Mixin（インターフェースでないもの）の印なら数える。アクセサー（インターフェース）の印なら数えない
        helper.assertTrue(ChunkReferenceProbe.isMixinTarget(MergedByClassMixin::class.java, merged), "a mark of a non-accessor mixin should count")
        helper.assertTrue(!ChunkReferenceProbe.isMixinTarget(MergedByAccessor::class.java, merged), "a mark of an accessor mixin should not count")
        // アクセサーの印と、処理を書き換える Mixin の印が両方あれば数える
        helper.assertTrue(ChunkReferenceProbe.isMixinTarget(MergedByBoth::class.java, merged), "a non-accessor mark beside an accessor mark should count")
        // Mixin のクラスファイルが読めない印、`mixin` が読めない印は数える（残す側）
        helper.assertTrue(ChunkReferenceProbe.isMixinTarget(MergedByUnreadable::class.java, merged), "a mark of an unreadable mixin should count")
        helper.assertTrue(ChunkReferenceProbe.isMixinTarget(MarkedMember::class.java, TestMark::class.java.name), "a mark without the mixin name should count")
        // ChunkMap 向け: 親のメソッドを上書きする、処理を書き換える Mixin の印だけを数える
        val base = OverriddenBase::class.java
        helper.assertTrue(!ChunkReferenceProbe.overridesWithMixin(ChunkMap::class.java, ChunkStorage::class.java), "the real ChunkMap should not override ChunkStorage with a mixin")
        helper.assertTrue(ChunkReferenceProbe.overridesWithMixin(OverrideByClassMixin::class.java, base, merged), "a mixin override of a parent method should count")
        helper.assertTrue(!ChunkReferenceProbe.overridesWithMixin(OverrideByAccessor::class.java, base, merged), "an accessor mark on an override should not count")
        helper.assertTrue(!ChunkReferenceProbe.overridesWithMixin(PlainOverride::class.java, base, merged), "an unmarked override should not count")
        helper.assertTrue(!ChunkReferenceProbe.overridesWithMixin(MergedNonOverride::class.java, base, merged), "a mixin method that overrides nothing should not count")
        helper.succeed()
    }

    /** [mixinTargetIsDetectedOnRealMixins] の、`ChunkStorage` の代わりの親のクラス */
    internal open class OverriddenBase {
        open fun read(pos: Int): Int = pos
    }

    /** [mixinTargetIsDetectedOnRealMixins] の、処理を書き換える Mixin が親のメソッドを上書きしたクラス */
    internal class OverrideByClassMixin : OverriddenBase() {
        @TestMerged("pro.ikili.kibicomputers.neoforge.gametest.SaveCleanupGameTests\$ClassMixinLike")
        override fun read(pos: Int): Int = 0
    }

    /** [mixinTargetIsDetectedOnRealMixins] の、アクセサーの印の付いた上書きのクラス */
    internal class OverrideByAccessor : OverriddenBase() {
        @TestMerged("pro.ikili.kibicomputers.neoforge.gametest.SaveCleanupGameTests\$AccessorLike")
        override fun read(pos: Int): Int = 0
    }

    /** [mixinTargetIsDetectedOnRealMixins] の、印のない上書き（バニラ自身の上書きの代わり）のクラス */
    internal class PlainOverride : OverriddenBase() {
        override fun read(pos: Int): Int = 0
    }

    /** [mixinTargetIsDetectedOnRealMixins] の、親のメソッドを上書きしない、Mixin の合わせたメソッドのクラス */
    internal class MergedNonOverride : OverriddenBase() {
        @TestMerged("pro.ikili.kibicomputers.neoforge.gametest.SaveCleanupGameTests\$ClassMixinLike")
        fun other(pos: Int): Int = pos
    }

    /** `@MixinMerged` の `mixin`（合わせた Mixin のクラス名） */
    private fun mergedName(mark: Annotation): String = mark.annotationClass.java.getMethod("mixin").invoke(mark) as String

    private const val MIXIN_MERGED = "org.spongepowered.asm.mixin.transformer.meta.MixinMerged"

    /** [mixinTargetIsDetectedOnRealMixins] の、アクセサーの Mixin の代わりのインターフェース */
    internal interface AccessorLike

    /** [mixinTargetIsDetectedOnRealMixins] の、処理を書き換える Mixin の代わりのクラス */
    internal class ClassMixinLike

    /**
     * [mixinTargetIsDetectedOnRealMixins] の、`@MixinMerged` と同じ形の印（`mixin` に合わせた Mixin のクラス名）。
     * 本番のコードがリフレクションで `mixin` を読むので、JVM の上で public にする（`internal`）
     */
    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.FUNCTION)
    internal annotation class TestMerged(val mixin: String)

    /** [mixinTargetIsDetectedOnRealMixins] の、処理を書き換える Mixin の印の付いたクラス */
    internal class MergedByClassMixin {
        @TestMerged("pro.ikili.kibicomputers.neoforge.gametest.SaveCleanupGameTests\$ClassMixinLike")
        fun merged(): Int = 1
    }

    /** [mixinTargetIsDetectedOnRealMixins] の、アクセサーの Mixin の印だけが付いたクラス */
    internal class MergedByAccessor {
        @TestMerged("pro.ikili.kibicomputers.neoforge.gametest.SaveCleanupGameTests\$AccessorLike")
        fun merged(): Int = 1
    }

    /** [mixinTargetIsDetectedOnRealMixins] の、アクセサーの印と処理を書き換える Mixin の印の両方が付いたクラス */
    internal class MergedByBoth {
        @TestMerged("pro.ikili.kibicomputers.neoforge.gametest.SaveCleanupGameTests\$AccessorLike")
        fun accessor(): Int = 1

        @TestMerged("pro.ikili.kibicomputers.neoforge.gametest.SaveCleanupGameTests\$ClassMixinLike")
        fun merged(): Int = 1
    }

    /** [mixinTargetIsDetectedOnRealMixins] の、クラスファイルが読めない Mixin の印の付いたクラス */
    internal class MergedByUnreadable {
        @TestMerged("no.such.Mixin")
        fun merged(): Int = 1
    }

    /** [vanillaRegionStorageIsTrusted] の、`@MixinMerged` の代わりの印（`mixin` を持たない。実行時に見える注釈） */
    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.FUNCTION)
    internal annotation class TestMark

    /** [vanillaRegionStorageIsTrusted] の、差し替えの判定の期待するクラス */
    internal open class BaseStorage

    /** [vanillaRegionStorageIsTrusted] の、差し替えたサブクラス */
    internal class SubStorage : BaseStorage()

    /** [vanillaRegionStorageIsTrusted] の、印の付いたメソッドのあるクラス */
    private class MarkedMember {
        @TestMark
        fun marked(): Int = 1
    }

    /** [vanillaRegionStorageIsTrusted] の、印のないクラス */
    private class UnmarkedMember {
        fun plain(): Int = 1
    }

    /**
     * リージョンファイルがないときの答え（[ChunkReferenceProbe.regionAbsence]）は、ないと確かめられ、チャンクを読み書きする側が既定の作りで、
     * 同じ保存先にバニラの名前のリージョンファイルがあるときだけ「ない」。保存先がない・バニラの名前のファイルがない（名前・形式を変える環境かもしれない）・
     * 既定の作りと確かめられない（別の座標のファイルは証拠にならない。#899）なら「判定できない」。ファイルがあれば読む（`null`）
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun regionAbsenceNeedsVanillaRegionFiles(helper: GameTestHelper) {
        val folder = tempRoot(helper).resolve("region")
        try {
            val target = folder.resolve("r.3.-4.mca")
            fun check(expected: MachineReference?, what: String, trusted: Boolean = true) {
                val actual = ChunkReferenceProbe.regionAbsence(target, HashSet(), trusted)
                helper.assertTrue(actual == expected, "$what should be $expected but was $actual")
            }
            check(MachineReference.UNKNOWN, "a missing folder")
            Files.createDirectories(folder)
            check(MachineReference.UNKNOWN, "an empty folder")
            Files.write(folder.resolve("c.0.0.mcc"), ByteArray(0))
            Files.write(folder.resolve("r.0.0.region"), ByteArray(0))
            Files.write(folder.resolve("r.a.b.mca"), ByteArray(0))
            check(MachineReference.UNKNOWN, "a folder with only other region names")
            Files.write(folder.resolve("r.-1.2.mca"), ByteArray(0))
            check(MachineReference.ABSENT, "a folder with a vanilla region file")
            check(MachineReference.UNKNOWN, "a folder with a vanilla region file of another position and modified storage", trusted = false)
            Files.write(target, ByteArray(0))
            check(null, "an existing region file")
            check(null, "an existing region file with modified storage", trusted = false)
        } finally {
            deleteTree(folder)
        }
        helper.succeed()
    }

    /**
     * `/kibicomputers saves cleanup`・`cleanup dry-run`・`cleanup force`・`saves status` は、OP の権限（コンフィグの `opPermissionLevel`）のない実行者を弾き、
     * OP なら、それぞれ正しい組（dryRun・force）で [SaveCleanups.startFromCommand] に届く。サーバーの本物の置き場を消さないよう、
     * 置き場は [SaveCleanups.commandOverride] で差し替える。共有の [SaveCleanups] を使うので、ほかの掃除のテストと重ならないよう別の batch にする
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 600, batch = "kibicomputers_save_cleanup_command")
    fun cleanupCommandsRequireOpAndPassModes(helper: GameTestHelper) {
        val level = helper.level
        val server = level.server
        val dispatcher = server.commands.dispatcher
        val root = tempRoot(helper)
        val absent = UUID.randomUUID()
        ensureVanillaRegion(helper)
        val far = FarChunks.reserve(helper)
        previousDirectory(root, absent, MachineOwner(level.dimension().location().toString(), far.minBlockX, 64, far.minBlockZ))
        SaveCleanups.commandOverride = MachineSaveStore(root, MachineIdIndex(), "test") to null
        // 成功・失敗・タイムアウトのどれでも差し替えを外す（後のコマンドが別の置き場を使わないように）
        helper.testInfo.addListener(
            object : GameTestListener {
                override fun testStructureLoaded(info: GameTestInfo) {}

                override fun testPassed(info: GameTestInfo, runner: GameTestRunner) = clear()

                override fun testFailed(info: GameTestInfo, runner: GameTestRunner) = clear()

                override fun testAddedForRerun(old: GameTestInfo, new: GameTestInfo, runner: GameTestRunner) {}

                private fun clear() {
                    SaveCleanups.commandOverride = null
                    deleteTree(root)
                }
            },
        )
        val opLevel = CaseAccess.rules().opPermissionLevel
        val op = server.createCommandSourceStack().withPermission(opLevel)
        val notOp = server.createCommandSourceStack().withPermission(opLevel - 1)
        // OP でない実行者には、saves の下のコマンドが見えない（Brigadier の requires で弾かれ、何も始まらない）
        val before = SaveCleanups.last
        for (command in listOf("saves cleanup", "saves cleanup dry-run", "saves cleanup force", "saves status")) {
            val rejected = try {
                dispatcher.execute("kibicomputers $command", notOp)
                false
            } catch (e: CommandSyntaxException) {
                true
            }
            helper.assertTrue(rejected, "'$command' should be rejected without the op permission")
        }
        helper.assertTrue(SaveCleanups.last === before, "a rejected command should not start a cleanup")
        // OP なら、リテラルごとの組（dryRun・force）で始まる。1つずつ終わるのを待つ（走っている間は次を始めない）
        val modes = listOf(
            Triple("saves cleanup dry-run", true, false),
            Triple("saves cleanup", false, false),
            Triple("saves cleanup force", false, true),
        )
        var sequence = helper.startSequence()
        var started: SaveCleanup? = null
        for ((command, dryRun, force) in modes) {
            sequence = sequence.thenExecute {
                val previous = SaveCleanups.last
                val result = dispatcher.execute("kibicomputers $command", op)
                helper.assertTrue(result == 1, "'$command' should start a cleanup")
                started = SaveCleanups.last
                val s = started
                helper.assertTrue(s != null && s !== previous, "'$command' should start a new cleanup")
                helper.assertTrue(s!!.dryRun == dryRun && s.force == force, "'$command' should start dryRun=$dryRun force=$force but was ${s.dryRun}/${s.force}")
            }.thenWaitUntil {
                helper.assertTrue(started!!.finished, "'$command' should finish (${started!!.phase})")
            }.thenExecute {
                val report = started!!.report
                val directory = root.resolve(absent.toString())
                when {
                    dryRun -> helper.assertTrue(report.unreferenced == 1 && report.deleted == 0 && Files.isDirectory(directory), "the dry run should count only: $report")
                    !force -> helper.assertTrue(report.deleted == 1 && !Files.exists(directory), "the cleanup should delete: $report")
                    else -> helper.assertTrue(report.deleted == 0 && !Files.exists(directory), "nothing should be left to delete for the forced cleanup: $report")
                }
                helper.assertTrue(dispatcher.execute("kibicomputers saves status", op) == 1, "saves status should report the last cleanup")
            }
        }
        sequence.thenSucceed()
    }

    /**
     * チャンクの問いが見るリージョンファイルの保存先（[ChunkReferenceProbe.regionFile]）は、今のサーバーのどのディメンションでも、
     * バニラの保存先（`DimensionType.getStorageFolder` の `region`）と同じ
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun regionFileMatchesStorageOfEveryDimension(helper: GameTestHelper) {
        val server = helper.level.server
        val root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()
        var count = 0
        for (level in server.allLevels) {
            val expected = DimensionType.getStorageFolder(level.dimension(), root).resolve("region").resolve("r.0.-1.mca").toAbsolutePath().normalize()
            val actual = ChunkReferenceProbe.regionFile(level, ChunkPos(3, -5)).toAbsolutePath().normalize()
            helper.assertTrue(actual == expected, "the region file of ${level.dimension().location()} should be $expected but was $actual")
            count++
        }
        helper.assertTrue(count >= 1, "the server should have a level")
        helper.succeed()
    }

    /**
     * 起動から [SaveCleanups.START_DELAY_TICKS] tick 以内の試しの実行（`dry-run`）は、自動の掃除（本番）を取り消さない。
     * 試しの実行が走っている間に自動の時機が来ても、終わるのを待って始める。共有の [SaveCleanups] を使うので、ほかの掃除のテストと重ならないよう別の batch にする
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 400, batch = "kibicomputers_save_cleanup_auto_dry")
    fun dryRunDoesNotCancelAutomaticCleanup(helper: GameTestHelper) {
        val level = helper.level
        val dimension = level.dimension().location().toString()
        val autoRoot = tempRoot(helper)
        val dryRoot = tempRoot(helper)
        val autoId = UUID.randomUUID()
        val dryId = UUID.randomUUID()
        ensureVanillaRegion(helper)
        val far = FarChunks.reserve(helper)
        previousDirectory(autoRoot, autoId, MachineOwner(dimension, far.minBlockX, 64, far.minBlockZ))
        previousDirectory(dryRoot, dryId, MachineOwner(dimension, far.minBlockX + 1, 64, far.minBlockZ))
        var dry: SaveCleanup? = null
        helper.startSequence().thenExecute {
            SaveCleanups.armAuto(2, MachineSaveStore(autoRoot, MachineIdIndex(), "test"), null)
            // 自動の前に始めた試しの実行（走っている間に自動の時機が来る）
            dry = SaveCleanups.start(dryRun = true, force = false, store = MachineSaveStore(dryRoot, MachineIdIndex(), "test"), modules = null)
            helper.assertTrue(dry != null, "the dry run should start")
        }.thenWaitUntil {
            helper.assertTrue(SaveCleanups.last !== dry, "the automatic cleanup should start after the dry run")
        }.thenWaitUntil {
            helper.assertTrue(SaveCleanups.last!!.finished, "the automatic cleanup should finish (${SaveCleanups.last!!.phase})")
        }.thenExecute {
            try {
                val auto = SaveCleanups.last!!
                helper.assertTrue(!auto.dryRun && auto.report.deleted == 1, "the automatic cleanup should delete: ${auto.report}")
                helper.assertTrue(!Files.exists(autoRoot.resolve(autoId.toString())), "the automatic cleanup should delete its directory")
                helper.assertTrue(dry!!.finished && Files.isDirectory(dryRoot.resolve(dryId.toString())), "the dry run should delete nothing")
            } finally {
                SaveCleanups.stop()
                deleteTree(autoRoot)
                deleteTree(dryRoot)
            }
        }.thenSucceed()
    }
}
