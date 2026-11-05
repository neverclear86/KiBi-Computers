package pro.ikili.kibicomputers.neoforge.gametest

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.computer.CaseTier
import pro.ikili.kibicomputers.core.computer.ComputerError
import pro.ikili.kibicomputers.core.computer.ComputerStatus
import pro.ikili.kibicomputers.core.computer.DiskStore
import pro.ikili.kibicomputers.core.computer.PartKind
import pro.ikili.kibicomputers.core.computer.PartTier
import pro.ikili.kibicomputers.core.display.ScreenTier
import pro.ikili.kibicomputers.core.display.TextBuffer
import pro.ikili.kibicomputers.core.kernel.MachineState
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
import pro.ikili.kibicomputers.neoforge.computer.CaseParts
import pro.ikili.kibicomputers.neoforge.computer.DiskItem
import pro.ikili.kibicomputers.neoforge.computer.TestComputerWiring
import pro.ikili.kibicomputers.neoforge.computer.WorldDisks
import pro.ikili.kibicomputers.neoforge.display.ScreenBlock
import pro.ikili.kibicomputers.neoforge.display.ScreenBlockEntity
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks
import pro.ikili.kibicomputers.neoforge.registry.KiBiDataComponents
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

/** 内蔵ディスク（HDD）のアイテム（#124）の GameTest。 */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object DiskItemGameTests {
    /** 起動を待つ tick 数の上限（[TestComputerGameTests] と同じ理由で長くとる） */
    private const val BOOT_TIMEOUT_TICKS = 12_000

    private val CASE = BlockPos(1, 1, 1)
    private val SCREEN = BlockPos(1, 2, 1)

    /** 画面の各行の文字列（行末の空白を除く） */
    private fun lines(text: TextBuffer): List<String> = (0 until text.height).map { y ->
        val sb = StringBuilder()
        for (x in 0 until text.width) sb.appendCodePoint(text.getCodePoint(x, y))
        sb.toString().trimEnd()
    }

    private fun store(helper: GameTestHelper): DiskStore {
        val store = WorldDisks.current
        helper.assertTrue(store != null, "the server should have a disk store")
        return store!!
    }

    /**
     * ケース（ディスクのスロットに [disk]、ほかの必須の部品は種類を確かめずに鉄インゴット）と、[withScreen] なら上に画面を置く。
     */
    private fun build(helper: GameTestHelper, disk: ItemStack, withScreen: Boolean): Pair<CaseBlockEntity, ScreenBlockEntity?> {
        helper.setBlock(CASE, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get())
        val screen = if (withScreen) {
            helper.setBlock(
                SCREEN,
                // 識別子（36 文字）入りの起動の行が折り返さない幅の画面
                KiBiBlocks.SCREENS.getValue(ScreenTier.COLOR).get().defaultBlockState().setValue(ScreenBlock.FACING, Direction.SOUTH),
            )
            helper.getBlockEntity<ScreenBlockEntity>(SCREEN)
        } else {
            null
        }
        val case = helper.getBlockEntity<CaseBlockEntity>(CASE)
        for (kind in PartKind.entries.filter { it.required && it != PartKind.BOOT && it != PartKind.STORAGE }) {
            case.parts.setStackInSlot(case.tier.firstSlot(kind), ItemStack(Items.IRON_INGOT))
        }
        case.parts.setStackInSlot(case.tier.firstSlot(PartKind.STORAGE), disk)
        return case to screen
    }

    private fun disk(tier: PartTier = PartTier.TIER_1): ItemStack = ItemStack(KiBiBlocks.part(tier, PartKind.STORAGE))

    private fun diskSlot(case: CaseBlockEntity): ItemStack = case.parts.getStackInSlot(case.tier.firstSlot(PartKind.STORAGE))

    /**
     * 同梱イメージ testboot（`/sbin/init` と `/bin/lua`）の全部の項目を [dir] に写し、`/sbin/init` のシバンの次の行に
     * `print("KiBi disk init <id>")` を差し込む（testboot ではなくディスクの init が動いたことを見分けるため）
     */
    private fun writeTestbootTo(dir: Path, id: String) {
        val stream = KiBiComputers::class.java.getResourceAsStream("/kibicomputers/images/testboot.zip")
            ?: throw IllegalStateException("testboot.zip is missing")
        var copied = 0
        ZipInputStream(stream).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = dir.resolve(entry.name.trimEnd('/'))
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                    continue
                }
                Files.createDirectories(target.parent)
                Files.write(target, zip.readBytes())
                copied++
            }
        }
        check(copied >= 2) { "testboot.zip should hold /sbin/init and /bin/lua but held $copied files" }
        val init = dir.resolve("sbin/init")
        val text = Files.readString(init)
        val newline = text.indexOf('\n')
        Files.writeString(init, text.substring(0, newline + 1) + "print(\"KiBi disk init $id\")\n" + text.substring(newline + 1))
    }

    /** 部品のアイテムの登録とタグ: 正式なティアはディスクだけで、ディスクのスロットに挿せる。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun formalDisksFitStorageSlots(helper: GameTestHelper) {
        helper.setBlock(CASE, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get())
        val case = helper.getBlockEntity<CaseBlockEntity>(CASE)
        for (tier in PartTier.entries) {
            val stack = disk(tier)
            helper.assertTrue(stack.item is DiskItem, "$tier disk should be a DiskItem")
            helper.assertTrue(stack.`is`(CaseParts.tagOf(PartKind.STORAGE)), "$tier disk should be in the storage tag")
            for (slot in 0 until case.tier.slotCount) {
                val fits = case.tier.kindOf(slot) == PartKind.STORAGE
                helper.assertTrue(case.parts.isItemValid(slot, stack) == fits, "$tier disk in slot $slot should fit: $fits")
            }
            helper.assertTrue(!stack.has(KiBiDataComponents.DISK_ID.get()), "a new disk should have no ID")
        }
        helper.assertTrue(KiBiBlocks.PARTS.getValue(PartTier.TIER_1).keys == setOf(PartKind.STORAGE), "only disks have formal tiers")
        helper.succeed()
    }

    /** 識別子のないディスクに電源を入れたときに識別子が付き、切って入れ直しても変わらない。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun diskIdIsAssignedOnPowerOnAndStable(helper: GameTestHelper) {
        val (case, _) = build(helper, disk(), withScreen = false)
        helper.assertTrue(DiskItem.diskIdOf(diskSlot(case)) == null, "the disk should have no ID before power-on")
        helper.assertTrue(case.pressPowerButton() == ComputerStatus.RUNNING, "case should run but was ${case.computer}")
        val id = DiskItem.diskIdOf(diskSlot(case))
        helper.assertTrue(id != null && DiskStore.isValidId(id), "the disk should get a valid ID but had $id")
        helper.assertTrue(case.computer.bootDiskIds == setOf(id), "the case should remember the boot disk but had ${case.computer.bootDiskIds}")
        helper.assertTrue(
            case.computer.machine!!.lastBootConfig!!.bootDisks == listOf(id, TestComputerWiring.TEST_BOOT_DISK),
            "boot candidates should be the disk then testboot",
        )
        case.pressPowerButton()
        helper.assertTrue(case.computer.bootDiskIds.isEmpty(), "turning off should forget the disks")
        helper.assertTrue(case.pressPowerButton() == ComputerStatus.RUNNING, "case should run again")
        helper.assertTrue(DiskItem.diskIdOf(diskSlot(case)) == id, "the ID should not change across power cycles")
        case.pressPowerButton()
        helper.setBlock(CASE, Blocks.AIR)
        helper.succeed()
    }

    /**
     * ディスクの init から起動する: testboot の中身を写し、目印を差し込んだディスクで、ディスクの init が動き、
     * Lua の REPL が起動する。動いている間に起動ディスクを抜くと止まる。
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun bootsFromDiskInitAndStopsWhenPulled(helper: GameTestHelper) {
        val store = store(helper)
        val id = store.newId()
        writeTestbootTo(store.directoryOf(id)!!, id)
        val stack = disk(PartTier.TIER_2)
        stack.set(KiBiDataComponents.DISK_ID.get(), id)
        val (case, screen) = build(helper, stack, withScreen = true)
        helper.assertTrue(case.pressPowerButton() == ComputerStatus.RUNNING, "case should run but was ${case.computer}")
        val machine = case.computer.machine!!
        var pulled = false
        helper.succeedWhen {
            if (!pulled) {
                val text = lines(screen!!.text)
                helper.assertTrue(case.wiring.bootFailure == null, "boot should not fail but was ${case.wiring.bootFailure}: $text")
                helper.assertTrue(text.any { it == "kibi: booting from disk $id" }, "should boot from the disk: $text")
                helper.assertTrue(text.any { it == "KiBi disk init $id" }, "the disk's init should run: $text")
                helper.assertTrue(text.any { it == "KiBi test init: Lua REPL" }, "the init should continue: $text")
                helper.assertTrue(text.any { it == ">" }, "the Lua REPL prompt should be shown: $text")
                helper.assertTrue(machine.state == MachineState.RUNNING, "machine should be running but was ${machine.state}")
                helper.assertTrue(DiskItem.diskIdOf(diskSlot(case)) == id, "a preassigned ID should be kept")
                // 起動ディスクを抜く
                case.parts.setStackInSlot(case.tier.firstSlot(PartKind.STORAGE), ItemStack.EMPTY)
                pulled = true
            }
            helper.assertTrue(case.computer.status == ComputerStatus.ERROR, "pulling the boot disk should stop the case: ${case.computer}")
            helper.assertTrue(case.computer.error == ComputerError.MISSING_PARTS, "error should be missing parts")
            helper.assertTrue(case.computer.missingParts == listOf(PartKind.STORAGE), "missing should be [STORAGE] but was ${case.computer.missingParts}")
            helper.assertTrue(case.computer.machine == null, "the machine should be released")
            helper.assertTrue(case.wiring.connectedScreen == null, "the wiring should be disconnected")
            helper.setBlock(CASE, Blocks.AIR)
        }
    }

    /** init のないディスクでは、候補の順のとおり同梱イメージ testboot から起動する。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun emptyDiskFallsBackToTestboot(helper: GameTestHelper) {
        val (case, screen) = build(helper, disk(), withScreen = true)
        helper.assertTrue(case.pressPowerButton() == ComputerStatus.RUNNING, "case should run but was ${case.computer}")
        val machine = case.computer.machine!!
        helper.succeedWhen {
            val text = lines(screen!!.text)
            helper.assertTrue(text.any { it == "kibi: booting from disk ${TestComputerWiring.TEST_BOOT_DISK}" }, "should fall back to testboot: $text")
            helper.assertTrue(text.any { it == ">" }, "the Lua REPL prompt should be shown: $text")
            helper.assertTrue(machine.state == MachineState.RUNNING, "machine should be running")
            case.pressPowerButton()
            helper.setBlock(CASE, Blocks.AIR)
        }
    }

    /** 形の正しくない `disk_id` は起動の候補に入れず、上書きもしない。 */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun invalidDiskIdIsIgnoredAndKept(helper: GameTestHelper) {
        val stack = disk()
        stack.set(KiBiDataComponents.DISK_ID.get(), "../evil")
        val (case, _) = build(helper, stack, withScreen = false)
        helper.assertTrue(case.pressPowerButton() == ComputerStatus.RUNNING, "case should run but was ${case.computer}")
        helper.assertTrue(diskSlot(case).get(KiBiDataComponents.DISK_ID.get()) == "../evil", "an invalid ID should not be overwritten")
        helper.assertTrue(DiskItem.diskIdOf(diskSlot(case)) == null, "an invalid ID should not be used")
        helper.assertTrue(case.computer.bootDiskIds.isEmpty(), "an invalid ID should not be a boot disk")
        helper.assertTrue(
            case.computer.machine!!.lastBootConfig!!.bootDisks == listOf(TestComputerWiring.TEST_BOOT_DISK),
            "boot candidates should be testboot only",
        )
        case.pressPowerButton()
        helper.setBlock(CASE, Blocks.AIR)
        helper.succeed()
    }
}
