package pro.ikili.kibicomputers.neoforge.gametest

import com.mojang.logging.LogUtils
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestGenerator
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.gametest.framework.TestFunction
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.computer.CaseTier
import pro.ikili.kibicomputers.core.computer.PartKind
import pro.ikili.kibicomputers.core.display.ScreenTier
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
import pro.ikili.kibicomputers.neoforge.display.ScreenBlock
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks

/**
 * ゲームの中での多数台の負荷試験（#345）。手順と結果は `docs/load-test.md`。
 *
 * - CI 用（[CI_COUNT] 台）: `runGameTestServer` で毎回動く。全台が起動し、待っている間と一部が計算・出力している間に
 *   tick が重くなりすぎない、カーネルのスレッドが台数に比例して増えない、を確かめる
 * - 測定用: `runLoadTestServer -PloadTestCounts=100,500,1000` で動く。台数ごとに MSPT・スレッド数・ヒープ使用量をログに書く。
 *   テンプレートを名前空間 [MEASURE_NAMESPACE] のもの（`src/dev/resources`。jar には入らない）にして、
 *   測定用の起動（その名前空間だけを有効にする）でほかの GameTest が混ざらないようにする
 */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object LoadGameTests {
    private val LOGGER = LogUtils.getLogger()

    /** 測定する台数（カンマ区切り）を渡すシステムプロパティ。なければ測定用のテストは作らない */
    const val COUNTS_PROPERTY: String = "kibicomputers.loadTest.counts"

    /** 測定用のテストのテンプレートの名前空間 */
    const val MEASURE_NAMESPACE: String = "kibicomputers_load"

    /** CI で並べる台数 */
    const val CI_COUNT: Int = 100

    /** CI で、計算・出力させる台数 */
    private const val CI_BUSY = 10

    /**
     * CI で、待っている間・計算している間の tick の平均の上限（ミリ秒）。20 TPS を保てる値。
     * CI の機械の速さに左右されないよう、目標（[TARGET_TICK_MILLIS]）そのものを上限にする
     */
    private const val CI_MAX_AVG_TICK_MILLIS = 50.0

    /**
     * CI で、待っている間のカーネルのスレッド（`KiBi-` で始まるもの）のうち、プールで次の仕事を待っているワーカーを除いた数の上限。
     * 台数（[CI_COUNT]）より十分少ない。待っている台はスレッドを持たない（#37・#329）ので、
     * ディスパッチャー・タイマー・コンパイラーだけになる。プールで待つワーカーは、起動の間に増えた分がしばらく残るので数えない
     */
    private const val CI_MAX_IDLE_KERNEL_THREADS = 8

    /** 起動（上限 15,000 tick）と出力を待つ間（上限 10,000 tick）、ほかの段階を足しても収まる長さ */
    private const val CI_TIMEOUT_TICKS = 30_000
    private const val MEASURE_TIMEOUT_TICKS = 200_000

    /** 実際のサーバーの1 tick（20 TPS） */
    const val TARGET_TICK_MILLIS: Double = 50.0

    @JvmStatic
    @GameTestGenerator
    fun loadTests(): Collection<TestFunction> {
        val tests = ArrayList<TestFunction>()
        // 別のバッチにして、ほかのテストと同時に動かない（並べる範囲がテンプレートの外にはみ出すため）
        tests += TestFunction(
            "kibicomputers_load_ci", "loadgametests.ci", "${KiBiComputers.MOD_ID}:${SmokeGameTests.EMPTY}",
            CI_TIMEOUT_TICKS, 0, true,
        ) { ci(it) }
        for (count in measureCounts()) {
            tests += TestFunction(
                "kibicomputers_load_$count", "loadgametests.measure_$count", "$MEASURE_NAMESPACE:${SmokeGameTests.EMPTY}",
                MEASURE_TIMEOUT_TICKS, 0, true,
            ) { measure(it, count) }
        }
        return tests
    }

    private fun measureCounts(): List<Int> =
        System.getProperty(COUNTS_PROPERTY).orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it > 0 }.distinct()

    private fun ci(helper: GameTestHelper) {
        val settings = LoadSettings(count = CI_COUNT, busy = CI_BUSY, paced = false, warmupTicks = 20, measureTicks = 100)
        LoadRun(helper, settings) { report ->
            LOGGER.info("Load test (CI)\n{}", report.toMarkdown())
            val idle = report.phase(LoadPhase.IDLE)
            val compute = report.phase(LoadPhase.COMPUTE)
            val output = report.phase(LoadPhase.WATCHED)
            helper.assertTrue(
                idle.tick.avg < CI_MAX_AVG_TICK_MILLIS,
                "idle ticks should stay under $CI_MAX_AVG_TICK_MILLIS ms on average: ${idle.tick}",
            )
            helper.assertTrue(
                compute.tick.avg < CI_MAX_AVG_TICK_MILLIS,
                "ticks with $CI_BUSY computing machines should stay under $CI_MAX_AVG_TICK_MILLIS ms on average: ${compute.tick}",
            )
            helper.assertTrue(
                idle.kernelThreads - idle.idleWorkerThreads <= CI_MAX_IDLE_KERNEL_THREADS,
                "idle machines should not hold kernel threads: ${idle.kernelThreads - idle.idleWorkerThreads} busy threads " +
                    "for $CI_COUNT machines (${idle.kernelThreadNames})",
            )
            helper.assertTrue(output.sentBytes > 0, "the watched screens should send their output")
        }.start()
    }

    private fun measure(helper: GameTestHelper, count: Int) {
        val settings = LoadSettings(count = count, busy = maxOf(1, count / 10), paced = true, warmupTicks = 100, measureTicks = 600)
        LoadRun(helper, settings) { report -> LOGGER.info("Load test ({} computers)\n{}", count, report.toMarkdown()) }.start()
    }

    /** 1台分のブロックの置き方: ケースの上に画面、ケースの南にキーボード。 */
    internal object Layout {
        /** x 方向の間隔。画面どうしが隣り合わないようにあける */
        const val STEP_X = 2

        /** z 方向の間隔。キーボードが隣の列のケースと隣り合わないようにあける */
        const val STEP_Z = 3

        /** [index] 番目のケースの、テンプレートからの相対位置 */
        fun casePos(index: Int, columns: Int): BlockPos = BlockPos((index % columns) * STEP_X, 1, (index / columns) * STEP_Z)

        fun placeComputer(helper: GameTestHelper, case: BlockPos) {
            helper.setBlock(case, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get())
            helper.setBlock(
                case.above(),
                KiBiBlocks.SCREENS.getValue(ScreenTier.MONOCHROME).get().defaultBlockState().setValue(ScreenBlock.FACING, Direction.SOUTH),
            )
            helper.setBlock(case.south(), KiBiBlocks.KEYBOARD.get())
            val be = helper.getBlockEntity<CaseBlockEntity>(case)
            // 起動部品のほかの必須の部品（#122〜#124）はまだないので、種類を確かめずに入れる（TestComputerGameTests と同じ）
            for (kind in PartKind.entries.filter { it.required && it != PartKind.BOOT }) {
                be.parts.setStackInSlot(be.tier.firstSlot(kind), ItemStack(Items.IRON_INGOT))
            }
        }

        fun columns(count: Int): Int = Math.ceil(Math.sqrt(count.toDouble())).toInt().coerceAtLeast(1)
    }
}
