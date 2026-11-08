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
import pro.ikili.kibicomputers.core.kernel.SchedulerConfig
import pro.ikili.kibicomputers.core.computer.PartKind
import pro.ikili.kibicomputers.core.display.ScreenSyncSender
import pro.ikili.kibicomputers.core.display.ScreenSyncTraffic
import pro.ikili.kibicomputers.core.display.ScreenTier
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
import pro.ikili.kibicomputers.neoforge.computer.TestComputerWiring
import pro.ikili.kibicomputers.neoforge.display.ScreenBlock
import pro.ikili.kibicomputers.neoforge.machine.ServerMachines
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks

/**
 * ゲームの中での多数台の負荷試験（#345）。手順と結果は `docs/load-test.md`。
 *
 * - CI 用（[CI_COUNT] 台）: `runGameTestServer` で毎回動く。全台が起動し、待っている間と一部が計算・出力している間（見ていても、見ていなくても）に
 *   tick が重くなりすぎない、カーネルのスレッドが台数に比例して増えない、を確かめる。Lua の REPL と標準 OS のシェルの両方で1回ずつ動かす（#476）
 * - 測定用: `runLoadTestServer -PloadTestCounts=100,500,1000` で動く。台数ごとに MSPT・スレッド数・ヒープ使用量をログに書く。
 *   OS は `-PloadTestOs=lua|standard`（[LoadOs]。既定は Lua の REPL）。
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
     * CI で、待っている間・計算している間・出力している間の tick の平均の上限（ミリ秒）。20 TPS を保てる値。
     * CI の機械の速さに左右されないよう、目標（[TARGET_TICK_MILLIS]）そのものを上限にする
     */
    private const val CI_MAX_AVG_TICK_MILLIS = 50.0

    /**
     * CI で、待っている間のカーネルのスレッド（`KiBi-` で始まるもの）のうち、プールで次の仕事を待っているワーカーを除いた数の上限。
     * 台数（[CI_COUNT]）より十分少ない。待っている台はスレッドを持たない（#37・#329）ので、
     * ディスパッチャー・タイマー・コンパイラーだけになる。プールで待つワーカーは、起動の間に増えた分がしばらく残るので数えない
     */
    private const val CI_MAX_IDLE_KERNEL_THREADS = 8

    /**
     * CI で、起動の間のカーネルのスレッドの最大の上限（#350）。
     * 台数によらず、許可の数（同時に WASM を実行する台数）に比例する:
     * 許可を持つワーカーと起動用のスレッド（どちらも許可の数まで）、それに [CI_MAX_IDLE_KERNEL_THREADS] の分。
     * 起動の前からあってプールで待っているワーカーは数えない。先に動いた OS の試験で増えたワーカーが、まだ消えずに残っているため（#476）。
     * 起動の間に作られたスレッドは、待っているワーカーでも数える（#483）
     */
    private fun ciMaxBootKernelThreads(): Int = 2 * SchedulerConfig.DEFAULT.maxConcurrentMachines + CI_MAX_IDLE_KERNEL_THREADS

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
        // OS ごとにも別のバッチにする（同じ範囲に並べるため）
        for (os in LoadOs.entries) {
            val suffix = if (os == LoadOs.LUA) "" else "_${os.id}"
            tests += TestFunction(
                "kibicomputers_load_ci$suffix", "loadgametests.ci$suffix", "${KiBiComputers.MOD_ID}:${SmokeGameTests.EMPTY}",
                CI_TIMEOUT_TICKS, 0, true,
            ) { ci(it, os) }
        }
        val counts = measureCounts()
        if (counts.isNotEmpty()) {
            val os = LoadOs.fromProperty()
            for (count in counts) {
                tests += TestFunction(
                    "kibicomputers_load_$count", "loadgametests.measure_$count", "$MEASURE_NAMESPACE:${SmokeGameTests.EMPTY}",
                    MEASURE_TIMEOUT_TICKS, 0, true,
                ) { measure(it, os, count) }
            }
        }
        return tests
    }

    private fun measureCounts(): List<Int> =
        System.getProperty(COUNTS_PROPERTY).orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it > 0 }.distinct()

    private fun ci(helper: GameTestHelper, os: LoadOs) {
        val settings = LoadSettings(os = os, count = CI_COUNT, busy = CI_BUSY, paced = false, warmupTicks = 20, measureTicks = 100)
        LoadRun(helper, settings) { report ->
            LOGGER.info("Load test (CI, {})\n{}", os.id, report.toMarkdown())
            val boot = report.phase(LoadPhase.BOOT)
            val idle = report.phase(LoadPhase.IDLE)
            val compute = report.phase(LoadPhase.COMPUTE)
            val autosave = report.phase(LoadPhase.AUTOSAVE)
            val cycle = report.autosave!!
            // 自動保存（#81、ADR 0081）: 一巡しても TPS を落とさず、1 tick の書き込みは予算 + 1 以下
            helper.assertTrue(
                autosave.tick.avg < CI_MAX_AVG_TICK_MILLIS,
                "ticks during an autosave cycle should stay under $CI_MAX_AVG_TICK_MILLIS ms on average: ${autosave.tick}",
            )
            val budget = ServerMachines.saveConfig.autosaveWritesPerTick
            helper.assertTrue(cycle.maxTickWrites <= budget + 1, "an autosave tick should write at most ${budget + 1} times: $cycle")
            val unwatched = report.phase(LoadPhase.OUTPUT)
            val output = report.phase(LoadPhase.WATCHED)
            helper.assertTrue(
                idle.tick.avg < CI_MAX_AVG_TICK_MILLIS,
                "idle ticks should stay under $CI_MAX_AVG_TICK_MILLIS ms on average: ${idle.tick}",
            )
            helper.assertTrue(
                compute.tick.avg < CI_MAX_AVG_TICK_MILLIS,
                "ticks with $CI_BUSY computing machines should stay under $CI_MAX_AVG_TICK_MILLIS ms on average: ${compute.tick}",
            )
            // 出力し続ける台の端末の処理は、プロセスのスレッドで行う（#355・#357）。メインスレッドは差分を送るだけ（#351）
            helper.assertTrue(
                unwatched.tick.avg < CI_MAX_AVG_TICK_MILLIS,
                "ticks with $CI_BUSY machines printing to their screens should stay under $CI_MAX_AVG_TICK_MILLIS ms on average: ${unwatched.tick}",
            )
            helper.assertTrue(
                output.tick.avg < CI_MAX_AVG_TICK_MILLIS,
                "ticks with $CI_BUSY watched machines printing to their screens should stay under $CI_MAX_AVG_TICK_MILLIS ms on average: ${output.tick}",
            )
            helper.assertTrue(
                idle.kernelThreads - idle.idleWorkerThreads <= CI_MAX_IDLE_KERNEL_THREADS,
                "idle machines should not hold kernel threads: ${idle.kernelThreads - idle.idleWorkerThreads} busy threads " +
                    "for $CI_COUNT machines (${idle.kernelThreadNames})",
            )
            helper.assertTrue(
                boot.peakPhaseKernelThreads <= ciMaxBootKernelThreads(),
                "booting $CI_COUNT machines at once should not create a thread per machine: ${boot.peakPhaseKernelThreads} kernel threads " +
                    "created or working during the boot " +
                    "(limit ${ciMaxBootKernelThreads()})",
            )
            helper.assertTrue(output.sentBytes > 0, "the watched screens should send their output")
            // 1人に送る量は、見ている画面の数によらず、プレイヤーごとの予算に収まる（#352）
            val perSecond = ScreenSyncTraffic.DEFAULT_PLAYER_BYTES_PER_SECOND.toLong()
            val limit = perSecond * (output.tick.ticks + 2) / ScreenSyncSender.TICKS_PER_SECOND + perSecond
            helper.assertTrue(
                output.sentBytes <= limit,
                "the watcher received ${output.sentBytes} bytes in ${output.tick.ticks} ticks, over the per-player budget ($limit)",
            )
        }.start()
    }

    private fun measure(helper: GameTestHelper, os: LoadOs, count: Int) {
        val settings = LoadSettings(os = os, count = count, busy = maxOf(1, count / 10), paced = true, warmupTicks = 100, measureTicks = 600, massUnload = true)
        LoadRun(helper, settings) { report -> LOGGER.info("Load test ({} computers, {})\n{}", count, os.id, report.toMarkdown()) }.start()
    }

    /** 1台分のブロックの置き方: ケースの上に画面、ケースの南にキーボード。 */
    internal object Layout {
        /** x 方向の間隔。画面どうしが隣り合わないようにあける */
        const val STEP_X = 2

        /** z 方向の間隔。キーボードが隣の列のケースと隣り合わないようにあける */
        const val STEP_Z = 3

        /** [index] 番目のケースの、テンプレートからの相対位置 */
        fun casePos(index: Int, columns: Int): BlockPos = BlockPos((index % columns) * STEP_X, 1, (index / columns) * STEP_Z)

        fun placeComputer(helper: GameTestHelper, case: BlockPos, os: LoadOs) {
            helper.setBlock(case, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get())
            helper.setBlock(
                case.above(),
                KiBiBlocks.SCREENS.getValue(ScreenTier.MONOCHROME).get().defaultBlockState().setValue(ScreenBlock.FACING, Direction.SOUTH),
            )
            helper.setBlock(case.south(), KiBiBlocks.KEYBOARD.get())
            val be = helper.getBlockEntity<CaseBlockEntity>(case)
            // Lua の REPL で測るときは、テスト用の起動ディスクから起動する。標準 OS はゲームの既定（#461）なので、何も入れない
            if (os == LoadOs.LUA) be.fallbackBootDisk = TestComputerWiring.TEST_BOOT_DISK
            // 起動部品のほかの必須の部品（#122〜#124）はまだないので、種類を確かめずに入れる（TestComputerGameTests と同じ）
            for (kind in PartKind.entries.filter { it.required && it != PartKind.BOOT }) {
                be.parts.setStackInSlot(be.tier.firstSlot(kind), ItemStack(Items.IRON_INGOT))
            }
        }

        fun columns(count: Int): Int = Math.ceil(Math.sqrt(count.toDouble())).toInt().coerceAtLeast(1)
    }
}
