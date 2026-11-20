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
     * ディスクのチャンクにケースがある位置の ID は残し、同じチャンクのケースのない位置・ないディメンション・読み込まれたチャンクの
     * 位置を指すものは、それぞれ消す・残す・残す
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
                helper.assertTrue(report.candidates == 4, "four candidates: $report")
                helper.assertTrue(Files.isDirectory(root.resolve(caseId.toString())), "the directory of the saved case should stay: $report")
                helper.assertTrue(!Files.exists(root.resolve(absent.toString())), "the unreferenced directory should be deleted: $report")
                helper.assertTrue(Files.isDirectory(root.resolve(noDimension.toString())), "a missing dimension should keep the directory: $report")
                helper.assertTrue(Files.isDirectory(root.resolve(loaded.toString())), "a loaded chunk should keep the directory: $report")
                helper.assertTrue(report.deleted == 1 && report.present == 1 && report.undetermined == 2, "counts: $report")
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
}
