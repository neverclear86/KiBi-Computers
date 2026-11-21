package pro.ikili.kibicomputers.neoforge.gametest

import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.storage.LevelResource
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.computer.CaseTier
import pro.ikili.kibicomputers.core.computer.ComputerStatus
import pro.ikili.kibicomputers.core.computer.PartKind
import pro.ikili.kibicomputers.core.persist.MachineIdIndex
import pro.ikili.kibicomputers.core.persist.MachineOwner
import pro.ikili.kibicomputers.core.persist.MachineSaveStore
import pro.ikili.kibicomputers.core.persist.OwnerRecord
import pro.ikili.kibicomputers.core.persist.SaveCleanup
import pro.ikili.kibicomputers.core.persist.SaveCleanupSettings
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
import pro.ikili.kibicomputers.neoforge.machine.ChunkReferenceProbe
import pro.ikili.kibicomputers.neoforge.machine.SaveCleanups
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.Comparator
import java.util.UUID

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

    /** コマンドの経路（[SaveCleanups.start]）の試しの実行は消さずに数え、`saves status` が結果を返す */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 400)
    fun dryRunCountsWithoutDeleting(helper: GameTestHelper) {
        val level = helper.level
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
}
