package pro.ikili.kibicomputers.neoforge.gametest

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.storage.LevelResource
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.computer.CaseTier
import pro.ikili.kibicomputers.core.computer.ComputerStatus
import pro.ikili.kibicomputers.core.computer.PartKind
import pro.ikili.kibicomputers.core.display.ScreenTier
import pro.ikili.kibicomputers.core.display.TextBuffer
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.core.persist.ColdBootReason
import pro.ikili.kibicomputers.core.persist.MachineSnapshot
import pro.ikili.kibicomputers.core.persist.ModuleStore
import pro.ikili.kibicomputers.core.persist.SnapshotFormatException
import pro.ikili.kibicomputers.core.persist.SnapshotInspection
import pro.ikili.kibicomputers.core.persist.SnapshotSection
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
import pro.ikili.kibicomputers.neoforge.display.ScreenBlock
import pro.ikili.kibicomputers.neoforge.machine.WorldMachineSaves
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * 待機点保存の世代のファイルの中身（#82、#552）の GameTest: 入力待ちまで起動した標準 OS の台は冷えていない写しになり、
 * `KERNEL`・`PROCESS_ABI` 節が読め、各プロセスのモジュールの元のバイト列がワールドのモジュールの置き場にある。
 * 起動中の台は冷えた起動の写し（`KILLED` 節だけ）になる
 */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object MachineSnapshotContentGameTests {
    /** 起動を待つ tick 数の上限（[StandardOsGameTests] と同じ理由で長くとる） */
    private const val BOOT_TIMEOUT_TICKS = 12_000

    private val CASE = BlockPos(1, 1, 1)
    private val SCREEN = BlockPos(1, 2, 1)

    private fun installRequired(be: CaseBlockEntity) {
        for (kind in PartKind.entries.filter { it.required && it != PartKind.BOOT }) {
            be.parts.setStackInSlot(be.tier.firstSlot(kind), ItemStack(Items.IRON_INGOT))
        }
    }

    /** 画面の各行の文字列（行末の空白を除く） */
    private fun lines(text: TextBuffer): List<String> = (0 until text.height).map { y ->
        val sb = StringBuilder()
        for (x in 0 until text.width) sb.appendCodePoint(text.getCodePoint(x, y))
        sb.toString().trimEnd()
    }

    /** 同梱イメージ `os.zip` の [name]（先頭の `/` なし）のバイト列 */
    private fun osImageEntry(name: String): ByteArray {
        val stream = KiBiComputers::class.java.getResourceAsStream("/kibicomputers/images/os.zip")
            ?: throw IllegalStateException("os.zip is missing")
        ZipInputStream(stream).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name.trimStart('/') == name) return zip.readBytes()
            }
        }
        throw IllegalStateException("os.zip should hold $name")
    }

    /** プログラムの名前（argv[0] の最後の `/` の後） */
    private fun programName(args: List<String>): String? = args.firstOrNull()?.substringAfterLast('/')

    /**
     * 入力待ちまで起動した標準 OS の台を保存すると、冷えた起動の理由が NONE の世代のファイルになり、`KERNEL` 節が
     * `KernelSectionCodec.read` に、各プロセスの `PROCESS_ABI` 節が p1 の層の読み手に通る。init（`/sbin/init`）と sh の
     * モジュール参照の元のハッシュのファイルが `<world>/kibicomputers/modules/<sha>.wasm` にあり、中身の SHA-256 がその
     * ハッシュと、同梱イメージの元のバイト列と一致する（接続層で置き場が渡らなければ、すべての写しが冷えた起動になる）
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun warmSnapshotHasSectionsAndModules(helper: GameTestHelper) {
        val store = WorldMachineSaves.current
        helper.assertTrue(store != null, "the server should have a machine save store")
        helper.setBlock(CASE, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get())
        helper.setBlock(
            SCREEN,
            KiBiBlocks.SCREENS.getValue(ScreenTier.COLOR).get().defaultBlockState().setValue(ScreenBlock.FACING, Direction.SOUTH),
        )
        val be = helper.getBlockEntity<CaseBlockEntity>(CASE)
        installRequired(be)
        helper.assertTrue(be.pressPowerButton() == ComputerStatus.RUNNING, "case should run but was ${be.computer}")
        val modules: Path = helper.level.server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()
            .resolve(KiBiComputers.MOD_ID).resolve("modules")

        helper.startSequence().thenWaitUntil {
            val screen = be.wiring.connectedScreen
            helper.assertTrue(screen != null, "the screen should be connected")
            val text = lines(screen!!.text)
            helper.assertTrue(be.wiring.bootFailure == null, "boot should not fail but was ${be.wiring.bootFailure}: $text")
            helper.assertTrue(text.any { it == "$" }, "the shell prompt should be shown: $text")
        }.thenExecute {
            val maxProcesses = be.computer.machine!!.config.maxProcesses
            // アンロードの手順と同じく止めてから取り出す
            be.computer.prepareUnload()
            try {
                helper.assertTrue(be.captureAndWrite(CapturePolicy.KILL_TRANSIENT) > 0, "the case should be saved")
                val identity = be.identity!!
                val snapshot = DataInputStream(Files.newInputStream(store!!.generationFile(identity.id, identity.generation)).buffered())
                    .use { MachineSnapshot.readFrom(it) }
                helper.assertTrue(
                    snapshot.coldBootReason == ColdBootReason.NONE,
                    "a machine waiting for input should not need a cold boot but was ${snapshot.coldBootReason}",
                )
                val kinds = snapshot.sections.map { it.kind }
                helper.assertTrue(SnapshotSection.KERNEL in kinds, "the snapshot should have the KERNEL section: $kinds")
                helper.assertTrue(SnapshotSection.PROCESS_ABI in kinds, "the snapshot should have PROCESS_ABI sections: $kinds")
                val contents = try {
                    SnapshotInspection.inspect(snapshot, maxProcesses)
                } catch (e: SnapshotFormatException) {
                    throw IllegalStateException("the KERNEL and PROCESS_ABI sections should be readable", e)
                }

                // すべてのプロセスが p1 の層を持ち、元のハッシュのファイルが置き場にあって、中身のハッシュが合う
                for (p in contents.processes) {
                    helper.assertTrue(p.abiLayer == SnapshotSection.ABI_LAYER_P1, "every process should have the p1 layer: $p")
                    val hash = p.originalHash!!
                    val file = modules.resolve(ModuleStore.hex(hash) + ".wasm")
                    helper.assertTrue(Files.isRegularFile(file), "the module of $p should be in the module store: $file")
                    helper.assertTrue(
                        ModuleStore.sha256(Files.readAllBytes(file)).contentEquals(hash),
                        "the module file of $p should hash to its name",
                    )
                }
                // init と sh は同梱イメージの元のバイト列
                val init = contents.processes.singleOrNull { it.pid == contents.initPid }
                helper.assertTrue(init != null && programName(init.args) == "init", "init should be saved: ${contents.processes}")
                helper.assertTrue(
                    init!!.originalHash!!.contentEquals(ModuleStore.sha256(osImageEntry("sbin/init"))),
                    "init should refer to /sbin/init of the OS image",
                )
                val sh = contents.processes.firstOrNull { programName(it.args) == "sh" }
                helper.assertTrue(sh != null, "sh should be saved: ${contents.processes}")
                helper.assertTrue(
                    sh!!.originalHash!!.contentEquals(ModuleStore.sha256(osImageEntry("bin/sh"))),
                    "sh should refer to /bin/sh of the OS image",
                )
            } finally {
                be.computer.endQuiesce()
            }
            be.pressPowerButton()
            helper.setBlock(CASE, Blocks.AIR)
        }.thenSucceed()
    }

    /**
     * 電源を入れたのと同じ tick（init がまだない）に取り出すと、冷えた起動（BOOTING）の写しになり、`KILLED` 節だけを書く
     * （`KERNEL`・`PROCESS_ABI` 節はない）
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = 400)
    fun bootingSnapshotIsCold(helper: GameTestHelper) {
        helper.setBlock(CASE, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get())
        val be = helper.getBlockEntity<CaseBlockEntity>(CASE)
        installRequired(be)
        helper.assertTrue(be.pressPowerButton() == ComputerStatus.RUNNING, "case should run but was ${be.computer}")
        // 同じ tick なので、ケースの onLoad（持ち主と ID）はまだない。写しは直接作る
        be.computer.prepareUnload()
        val snapshot = try {
            be.computer.capture(CapturePolicy.KILL_TRANSIENT)?.build(UUID.randomUUID(), 1, "test")
        } finally {
            be.computer.endQuiesce()
        }
        helper.assertTrue(snapshot != null, "the booting machine should be captured")
        helper.assertTrue(
            snapshot!!.coldBootReason == ColdBootReason.BOOTING,
            "a booting machine should need a cold boot (BOOTING) but was ${snapshot.coldBootReason}",
        )
        helper.assertTrue(snapshot.sections.isEmpty(), "a cold snapshot should carry only the KILLED section but was ${snapshot.sections}")
        be.pressPowerButton()
        helper.setBlock(CASE, Blocks.AIR)
        helper.succeed()
    }
}
