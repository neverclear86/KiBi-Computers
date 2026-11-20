package pro.ikili.kibicomputers.neoforge.gametest

import net.minecraft.server.level.ServerLevel
import net.neoforged.bus.api.EventPriority
import net.neoforged.neoforge.common.NeoForge
import net.minecraft.world.level.Level
import net.neoforged.neoforge.event.level.LevelEvent
import net.neoforged.neoforge.event.tick.LevelTickEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import org.slf4j.LoggerFactory
import pro.ikili.kibicomputers.core.persist.SaveWriter
import pro.ikili.kibicomputers.neoforge.machine.WorldMachineSaves
import java.lang.management.ManagementFactory

/**
 * 負荷試験の長い tick の調べ（#600）。サーバーの tick を区間に分けて時間を数え、長く止まった tick の間のスタックを取る。
 *
 * - 区間: tick の中のイベント（[ServerTickEvent]・[LevelTickEvent] の最初と最後の優先度）と、試験の呼び出し（[harnessStart]・[harnessEnd]）で
 *   区切る。前の区切りからの時間を、区切りの手前の区間（[Section]）に足す。試験の呼び出しの間（試験の処理と、tick をそろえる待ち）は数えない。
 *   なので区間の合計は、試験が測る tick の時間（MSPT）と同じになる
 * - 止まりの見張り（[sampleStalls] のとき）: デーモンのスレッドが [SAMPLE_INTERVAL_MILLIS] ごとに、試験の呼び出しの後からの時間を見て、
 *   [STALL_MILLIS] を超えていたら、メインスレッドと書き込みのスレッドのスタックを取る（1回の止まりに [MAX_SAMPLES_PER_STALL] まで）。
 *   別のスレッドのスタックは、そのスレッドとのハンドシェイクで取る（全体の safepoint は要らない。native の中で止まっていてもフレームが取れる）。
 *   見張りは volatile の時刻だけを読み、[pro.ikili.kibicomputers.neoforge.machine.ServerMachines.Stats]（メインスレッドだけで使う）には触らない。
 *   名前は `KiBi-` で始めない（カーネルのスレッドに数えない）
 *
 * - 合成のログ（[syntheticLogEveryTicks] が 1 以上のとき。#834）: 本番のサーバーでメインスレッドが書くログ（プレイヤーの出入り・チャット・
 *   他 MOD の info など）の代わりに、`ServerTickEvent.Post`（既定の優先度。区間 [Section.POST]）で [SYNTHETIC_LOGGER] に INFO を1行書き、
 *   1行ごとの時間を数える（[takeSyntheticLog]）。INFO なので、本番と同じ log4j の設定で `latest.log`・`debug.log` の両方に書かれる
 *
 * tick の中では、合成のログのほかはログに書かない（メインスレッドのファイルの書き込みそのものが止まりの候補なので）。結果は [takeSections]・[takeSamples] で取り、
 * 段階の終わり（試験の処理の時間。MSPT に入らない）にまとめて出す。tick ごとの処理は区切りの数だけで、台数によらない。
 */
internal class LoadTickProbe(private val serverThread: Thread, private val sampleStalls: Boolean) {
    /** tick の区間。前の区切りからの時間を、区切ったところの手前の区間に足す */
    enum class Section(val label: String) {
        /** `ServerTickEvent.Post` の後から、次の tick の `ServerTickEvent.Pre` まで（GameTest のサーバーの後処理・進み具合のログ・タスクの実行） */
        BETWEEN("tick の間"),

        /** `ServerTickEvent.Pre`（KiBi の完了の受け取り・管理の tick・自動保存の取り出し） */
        PRE("Pre"),

        /** `Pre` の後からディメンションの tick の前まで、とディメンションの間（コマンドの関数など） */
        SERVER("サーバー"),

        /** ディメンションの tick（`LevelTickEvent` を含む。チャンクの保存・アンロード・ブロックエンティティ） */
        LEVELS("ディメンション"),

        /** 最後のディメンションの後から、試験の呼び出しまで（通信・プレイヤー） */
        BEFORE_HARNESS("試験の前"),

        /** 試験の呼び出しの後から `ServerTickEvent.Post` まで（チャンクの送信・バニラの自動保存） */
        AFTER_HARNESS("試験の後"),

        /** `ServerTickEvent.Post`（KiBi のアンロードの保存など） */
        POST("Post"),
    }

    /**
     * 止まっている間に取ったスタック。
     *
     * @property tickStartNanos 止まった tick の始め（試験の呼び出しの後。[harnessEnd] の時刻）。長い tick の記録と突き合わせる
     * @property elapsedNanos 取ったときの、tick の始めからの時間
     * @property lastSection 取ったときに、最後に通った区切りの手前の区間（この後の区間の途中で止まっている）
     * @property ioWorkers 取ったときに動いていた（`RUNNABLE` の）バニラの I/O のスレッド（`IO-Worker-`。リージョンファイルの書き込み）の名前とスタック（#834）
     */
    class StallSample(
        val tickStartNanos: Long,
        val elapsedNanos: Long,
        val wallMillis: Long,
        val uptimeMillis: Long,
        val lastSection: Section?,
        val serverState: Thread.State,
        val serverStack: List<StackTraceElement>,
        val writerState: Thread.State?,
        val writerStack: List<StackTraceElement>,
        val ioWorkers: List<Pair<String, List<StackTraceElement>>> = emptyList(),
    )

    /**
     * 合成のログの数え口（#834）: 書いた行数、そのうち [STALL_MILLIS] 以上かかった行数、時間の合計と最大（ナノ秒）
     */
    class SyntheticLogStats(val lines: Int, val slowLines: Int, val totalNanos: Long, val maxNanos: Long)

    private val sections = LongArray(Section.entries.size)

    /** 区間ごとに、区切りを通った回数（[takeSections] で 0 に戻す） */
    private val marks = IntArray(Section.entries.size)
    private var lastMark = 0L

    /** 試験の呼び出しの後の時刻（`System.nanoTime`）。試験の呼び出しの間は 0。見張りが読む */
    @Volatile
    private var workSince = 0L

    /** 最後に通った区切りの手前の区間の番号（-1 はまだない）。見張りが読む */
    @Volatile
    private var lastSection = -1

    private val samples = ArrayList<StallSample>()
    private var droppedSamples = 0
    private var sampler: Thread? = null

    @Volatile
    private var stopped = false

    /** 合成のログを書く間隔（tick。0 なら書かない。#834）。メインスレッドだけで使う */
    var syntheticLogEveryTicks: Int = 0
    private var syntheticTick = 0L
    private var syntheticLines = 0
    private var syntheticSlow = 0
    private var syntheticTotal = 0L
    private var syntheticMax = 0L

    /**
     * オーバーワールドの `LevelEvent.Save`（バニラの保存の中。`saveAllChunks` の始め）が最後に来たときの、KiBi の書き込み待ちの仕事の数（#834）。
     * -1 はまだ来ていない。[armLevelSave] で -1 に戻す。メインスレッドだけで使う
     */
    var pendingAtLevelSave: Int = -1
        private set

    fun armLevelSave() {
        pendingAtLevelSave = -1
    }

    /** ここまでの合成のログの数え口を渡して 0 に戻す */
    fun takeSyntheticLog(): SyntheticLogStats {
        val r = SyntheticLogStats(syntheticLines, syntheticSlow, syntheticTotal, syntheticMax)
        syntheticLines = 0
        syntheticSlow = 0
        syntheticTotal = 0
        syntheticMax = 0
        return r
    }

    private fun syntheticLog() {
        val every = syntheticLogEveryTicks
        if (every <= 0) return
        syntheticTick++
        if (syntheticTick % every != 0L) return
        val t0 = System.nanoTime()
        SYNTHETIC.info("Synthetic main thread log line {} (load test, #834)", syntheticTick)
        val d = System.nanoTime() - t0
        syntheticLines++
        syntheticTotal += d
        if (d > syntheticMax) syntheticMax = d
        if (d >= STALL_MILLIS * 1_000_000L) syntheticSlow++
    }

    fun start() {
        active = this
        installListeners()
        if (sampleStalls) {
            sampler = Thread(::watch, "LoadTest stall sampler").apply {
                isDaemon = true
                priority = Thread.MAX_PRIORITY
                start()
            }
        }
    }

    fun stop() {
        stopped = true
        if (active === this) active = null
        sampler?.interrupt()
        sampler = null
    }

    /** 試験の呼び出しの始め（メインスレッド）。ここまでを [Section.BEFORE_HARNESS] に足し、見張りを止める。区切った時刻を返す */
    fun harnessStart(): Long {
        val now = mark(Section.BEFORE_HARNESS)
        workSince = 0
        return now
    }

    /** 試験の呼び出しの終わり（tick をそろえて待った後。メインスレッド）。ここから次の区間を数える。tick の始めの時刻を返す */
    fun harnessEnd(): Long {
        val now = System.nanoTime()
        lastMark = now
        workSince = now
        return now
    }

    /** 区間ごとの時間（ナノ秒。[Section] の順）を [into] に、区切りを通った回数を [intoMarks] に写し、0 に戻す */
    fun takeSections(into: LongArray, intoMarks: IntArray) {
        sections.copyInto(into)
        sections.fill(0)
        marks.copyInto(intoMarks)
        marks.fill(0)
    }

    /** ここまでに取ったスタックを渡して空にする。あわせて、上限で捨てた数を返す */
    fun takeSamples(): Pair<List<StallSample>, Int> = synchronized(samples) {
        val taken = samples.toList()
        samples.clear()
        val dropped = droppedSamples
        droppedSamples = 0
        taken to dropped
    }

    private fun mark(section: Section): Long {
        val now = System.nanoTime()
        if (lastMark != 0L) sections[section.ordinal] += now - lastMark
        marks[section.ordinal]++
        lastMark = now
        lastSection = section.ordinal
        return now
    }

    private fun watch() {
        val runtime = ManagementFactory.getRuntimeMXBean()
        val stallNanos = STALL_MILLIS * 1_000_000L
        var stall = 0L
        var taken = 0
        var writer: Thread? = null
        while (!stopped) {
            try {
                Thread.sleep(SAMPLE_INTERVAL_MILLIS)
            } catch (e: InterruptedException) {
                return
            }
            val since = workSince
            if (since == 0L) continue
            val elapsed = System.nanoTime() - since
            if (elapsed < stallNanos) continue
            if (since != stall) {
                stall = since
                taken = 0
            }
            if (taken >= MAX_SAMPLES_PER_STALL) continue
            taken++
            val section = lastSection
            val serverState = serverThread.state
            val serverStack = serverThread.stackTrace.take(SERVER_STACK_DEPTH)
            if (writer == null || !writer.isAlive) writer = findThread(SaveWriter.DEFAULT_THREAD_NAME)
            val w = writer
            val sample = StallSample(
                tickStartNanos = since,
                elapsedNanos = elapsed,
                wallMillis = System.currentTimeMillis(),
                uptimeMillis = runtime.uptime,
                lastSection = Section.entries.getOrNull(section),
                serverState = serverState,
                serverStack = serverStack,
                writerState = w?.state,
                writerStack = w?.stackTrace?.take(WRITER_STACK_DEPTH) ?: emptyList(),
                ioWorkers = runningIoWorkers(),
            )
            synchronized(samples) {
                if (samples.size < MAX_SAMPLES) samples += sample else droppedSamples++
            }
        }
    }

    companion object {
        /** 長い tick とみなす tick の時間（ミリ秒）。ふだんの最大は 30〜40 ms */
        const val LONG_TICK_MILLIS: Long = 50

        /** 見張りがスタックを取りはじめる、tick の始めからの時間（ミリ秒） */
        const val STALL_MILLIS: Long = 50

        /** 見張りが見る間隔（ミリ秒） */
        const val SAMPLE_INTERVAL_MILLIS: Long = 10

        /** 1回の止まりで取るスタックの上限 */
        const val MAX_SAMPLES_PER_STALL: Int = 40

        /** 段階ごとに残すスタックの上限 */
        const val MAX_SAMPLES: Int = 400

        private const val SERVER_STACK_DEPTH = 48
        private const val WRITER_STACK_DEPTH = 16

        /** バニラの I/O のスレッドの名前の始め（`Util.ioPool`。`IOWorker` のリージョンファイルの読み書き） */
        private const val IO_WORKER_PREFIX = "IO-Worker-"

        /** 合成のログのロガーの名前（#834）。`kibicomputers` の下にしない（core のログの JUL の写しに混ぜない） */
        const val SYNTHETIC_LOGGER: String = "LoadTestSyntheticLog"
        private val SYNTHETIC = LoggerFactory.getLogger(SYNTHETIC_LOGGER)

        @Volatile
        private var active: LoadTickProbe? = null
        private var installed = false

        /** イベントの受け口を1回だけ登録する（外さない。動いている調べがなければ何もしない） */
        private fun installListeners() {
            if (installed) return
            installed = true
            val bus = NeoForge.EVENT_BUS
            bus.addListener(EventPriority.HIGHEST, false, ServerTickEvent.Pre::class.java) { active?.mark(Section.BETWEEN) }
            bus.addListener(EventPriority.LOWEST, false, ServerTickEvent.Pre::class.java) { active?.mark(Section.PRE) }
            bus.addListener(EventPriority.HIGHEST, false, LevelTickEvent.Pre::class.java) { if (it.level is ServerLevel) active?.mark(Section.SERVER) }
            bus.addListener(EventPriority.LOWEST, false, LevelTickEvent.Post::class.java) { if (it.level is ServerLevel) active?.mark(Section.LEVELS) }
            bus.addListener(EventPriority.HIGHEST, false, ServerTickEvent.Post::class.java) { active?.mark(Section.AFTER_HARNESS) }
            bus.addListener(EventPriority.NORMAL, false, ServerTickEvent.Post::class.java) { active?.syntheticLog() }
            bus.addListener(EventPriority.NORMAL, false, LevelEvent.Save::class.java) {
                val level = it.level
                if (level is ServerLevel && level.dimension() == Level.OVERWORLD) {
                    active?.let { a -> a.pendingAtLevelSave = WorldMachineSaves.current?.pendingCount ?: -1 }
                }
            }
            bus.addListener(EventPriority.LOWEST, false, ServerTickEvent.Post::class.java) { active?.mark(Section.POST) }
        }

        private fun allThreads(): List<Thread> {
            var group = Thread.currentThread().threadGroup
            while (group.parent != null) group = group.parent
            val threads = arrayOfNulls<Thread>(group.activeCount() * 2 + 16)
            val n = group.enumerate(threads, true)
            return (0 until n).mapNotNull { threads[it] }
        }

        private fun findThread(name: String): Thread? = allThreads().firstOrNull { it.name == name }

        /** 動いている（`RUNNABLE` の）バニラの I/O のスレッドのスタック。待っているものは入れない */
        private fun runningIoWorkers(): List<Pair<String, List<StackTraceElement>>> =
            allThreads().filter { it.name.startsWith(IO_WORKER_PREFIX) && it.state == Thread.State.RUNNABLE }
                .map { it.name to it.stackTrace.take(WRITER_STACK_DEPTH) }
                .filter { it.second.isNotEmpty() }
    }
}
