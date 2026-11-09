package pro.ikili.kibicomputers.neoforge.gametest

import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestAssertException
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.block.Blocks
import pro.ikili.kibicomputers.core.computer.ComputerStatus
import pro.ikili.kibicomputers.core.display.TextBuffer
import pro.ikili.kibicomputers.core.input.Key
import pro.ikili.kibicomputers.core.input.KeyboardEvent
import pro.ikili.kibicomputers.core.input.KeyboardEventCodec
import pro.ikili.kibicomputers.core.kernel.MachineState
import pro.ikili.kibicomputers.core.persist.MachineSaveConfig
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
import pro.ikili.kibicomputers.neoforge.display.ScreenBlockEntity
import pro.ikili.kibicomputers.neoforge.display.ScreenSync
import pro.ikili.kibicomputers.neoforge.input.KeyboardBlockEntity
import pro.ikili.kibicomputers.neoforge.machine.ServerMachines
import java.lang.management.ManagementFactory
import java.lang.management.ThreadInfo
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.locks.LockSupport

/** 負荷試験で打つ人（偽物） */
private val LOAD_TYPIST: UUID = UUID(0, 0x10AD)

/**
 * 負荷試験で動かす OS と、そこで打ち込むもの（#476）。
 *
 * @property id `-PloadTestOs` に渡す名前
 * @property label 結果に書く名前
 * @property prompt プロンプトの文字。画面に、この文字だけの行が出たら入力待ち
 * @property computeCommand 計算し続けるコマンド（画面に何も出さない無限ループ）
 * @property outputCommand 画面に数字を1行ずつ出し続けるコマンド
 * @property tmpCommand `/tmp` にファイルを1つ書いて、プロンプトに戻るコマンド（#553）。画面の幅（単色の画面は 50 桁）に収まる長さにする
 * @property tmpDoneLine [tmpCommand] が終わったときに画面に出る行
 * @property tmpBytes [tmpCommand] が書くファイルの大きさ（バイト）。`TMPFS` 節はこれより大きくなる
 */
internal enum class LoadOs(
    val id: String,
    val label: String,
    val prompt: Char,
    val computeCommand: String,
    val outputCommand: String,
    val tmpCommand: String,
    val tmpDoneLine: String,
    val tmpBytes: Int,
) {
    /** テスト用の起動ディスク（#307）の Lua の REPL。`close` の戻り値 `true` を REPL が出す */
    LUA(
        "lua", "Lua の REPL", '>', "while true do end", "local i = 0 while true do i = i + 1 print(i) end",
        "io.output\"/tmp/l\":write((\"x\"):rep(4^7)):close()", "true", 16_384,
    ),

    /** 標準 OS（#98。ゲームの既定。#461）のシェル。`seq 3300` は 15,393 バイト */
    STANDARD(
        "standard", "標準 OS のシェル", '$', "while :; do :; done", "i=0; while :; do i=$((i+1)); echo \$i; done",
        "seq 3300 >/tmp/l; echo ok", "ok", 15_393,
    ),
    ;

    companion object {
        /** 測定用の OS を渡すシステムプロパティ（`-PloadTestOs`） */
        const val PROPERTY: String = "kibicomputers.loadTest.os"

        /** [PROPERTY] の OS。なければ [LUA]。知らない名前なら失敗にする */
        fun fromProperty(): LoadOs {
            val id = System.getProperty(PROPERTY)?.trim().orEmpty().ifEmpty { return LUA }
            return entries.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("unknown $PROPERTY: $id (expected ${entries.joinToString { it.id }})")
        }
    }
}

/** 負荷試験の段階。 */
internal enum class LoadPhase(val label: String) {
    /** 何も置いていない（並べる範囲のチャンクは読み込んである）。比べる基準 */
    EMPTY("何もない"),

    /** 並べたが、電源は入れていない */
    PLACED("並べただけ（電源なし）"),

    /** 全台が起動して、プロンプトを出すまで（初回のコンパイルを含む） */
    BOOT("起動"),

    /** 全台が入力を待っている（はじめに一部の台が `/tmp` にファイルを書く。#553） */
    IDLE("全台が入力待ち"),

    /** 全台が入力を待っている間に、自動保存を1周期回す（#81）。一巡したら終わる */
    AUTOSAVE("自動保存の一巡（全台が入力待ち）"),

    /** 一部の台が無限ループで計算し続ける（残りは入力待ち） */
    COMPUTE("一部が計算し続ける"),

    /** 一部の台が画面に出力し続ける。誰も見ていない（計算していた台は電源を切る。残りは入力待ち） */
    OUTPUT("一部が出力し続ける"),

    /** [OUTPUT] の画面を、1人が全部見ている（画面の同期が送られる） */
    WATCHED("一部が出力し続け、1人が見る"),

    /**
     * 並べた範囲のチャンクの強制読み込みを外し、一斉にアンロードさせる（#81）。アンロードで全台を取り出して書く（延ばせない書き込みの最悪値）。
     * 測るだけで、目標値では落とさない（#84 の基準にする）
     */
    UNLOAD("まとめてアンロード"),
}

/**
 * 負荷試験の設定。
 *
 * @property os 動かす OS
 * @property count 並べる台数
 * @property busy 計算・出力させる台数（それぞれ）
 * @property paced 実際のサーバーと同じく 1 tick を 50 ms にそろえるか。GameTest のサーバーは待たずに tick を進めるので、
 *   そろえないと計算している台に配る燃料（tick ごと）が実際より多くなる。重い tick で遅れたら、実際のサーバーと同じく待たずに追いつく（#540）
 * @property warmupTicks 段階が変わってから（[LoadPhase.OUTPUT] では、出力が出てから）、測りはじめるまでの tick 数
 * @property measureTicks 測る tick 数
 * @property bootTimeoutTicks 全台がプロンプトを出すまで待つ上限
 * @property outputTimeoutTicks [LoadPhase.OUTPUT] で、出力させる台が全部、画面に出すまで待つ上限。出なければ失敗。
 *   [LoadPhase.COMPUTE] で、計算させる台が全部、打ち込んだ命令を画面に出すまで待つ上限にも使う
 *   [LoadPhase.IDLE] で、`/tmp` に書かせる台が全部、書き終わるまで待つ上限にも使う
 * @property massUnload 最後に [LoadPhase.UNLOAD] を測るか
 */
internal data class LoadSettings(
    val os: LoadOs,
    val count: Int,
    val busy: Int,
    val paced: Boolean,
    val warmupTicks: Int,
    val measureTicks: Int,
    val bootTimeoutTicks: Int = if (paced) 150_000 else 15_000,
    val outputTimeoutTicks: Int = if (paced) 6_000 else 10_000,
    val massUnload: Boolean = false,
)

/** tick の時間の統計（ミリ秒）。 */
internal data class TickStats(val ticks: Int, val avg: Double, val p50: Double, val p95: Double, val max: Double) {
    override fun toString(): String = String.format(Locale.ROOT, "%d ticks, avg %.2f / p50 %.2f / p95 %.2f / max %.2f ms", ticks, avg, p50, p95, max)

    companion object {
        fun of(nanos: LongArray): TickStats {
            if (nanos.isEmpty()) return TickStats(0, 0.0, 0.0, 0.0, 0.0)
            val sorted = nanos.sortedArray()
            fun ms(n: Long) = n / 1_000_000.0
            fun pct(p: Double) = ms(sorted[((sorted.size - 1) * p).toInt()])
            return TickStats(sorted.size, ms(sorted.sum()) / sorted.size, pct(0.5), pct(0.95), ms(sorted.last()))
        }
    }
}

/** 1つの段階の結果。 */
internal data class PhaseResult(
    val phase: LoadPhase,
    /** 段階の終わりに動いていたマシンの数 */
    val running: Int,
    val tick: TickStats,
    /** 段階の終わりの、カーネルのスレッド（名前が `KiBi-` で始まるもの）の数 */
    val kernelThreads: Int,
    /** [kernelThreads] のうち、仕事がなくプールで次の仕事を待っているワーカー（しばらくすると終わる） */
    val idleWorkerThreads: Int,
    /** 測っている間の、カーネルのスレッドの最大（10 tick ごとに数えた中で） */
    val peakKernelThreads: Int,
    /**
     * 測っている間の、カーネルのスレッドのうち、段階に入る前からあってプールで次の仕事を待っているワーカーを除いた数の最大
     * （10 tick ごとに数えた中で）。前の試験で増えて、まだ消えていないワーカーを数えない（#476: CI で OS ごとに続けて動かすため）。
     * この段階で作られたスレッドは、待っているワーカーでも数える（#483: 台ごとにワーカーを作り、数える前に待ちに入る退行を見落とさない）
     */
    val peakPhaseKernelThreads: Int,
    /** 段階の終わりの、JVM のスレッドの数 */
    val jvmThreads: Int,
    /** 測っている間の、JVM のスレッドの最大 */
    val peakJvmThreads: Int,
    /** 段階の終わりに GC したあとのヒープ使用量 */
    val heapUsedBytes: Long,
    /** 測っている間に、見ている人へ送った画面の同期のバイト数 */
    val sentBytes: Long,
    /** 段階の長さ（tick） */
    val durationTicks: Int,
    /** 段階の長さ（秒） */
    val wallSeconds: Double,
    /** 段階の終わりの、カーネルのスレッドの内訳（名前の数字を `#` にしたもの → 数） */
    val kernelThreadNames: Map<String, Int>,
)

/** 平均と最大（ミリ秒） */
internal data class AvgMax(val avg: Double, val max: Double) {
    override fun toString(): String = String.format(Locale.ROOT, "%.3f / %.3f", avg, max)

    companion object {
        val ZERO = AvgMax(0.0, 0.0)

        fun ofNanos(values: LongArray): AvgMax =
            if (values.isEmpty()) ZERO else AvgMax(values.average() / 1e6, values.max() / 1e6)
    }
}

/**
 * 保存（取り出して書く）の手間と大きさ（#553）。[LoadPhase.AUTOSAVE] と [LoadPhase.UNLOAD] で測る。
 *
 * @property saves 世代のファイルを書いた回数
 * @property capture 1台あたりの取り出し（`captureCopy`。カーネルロックの中。メインスレッドで呼ぶ）の時間
 * @property encode 1台あたりの符号化（保存データのバイト列にする）の時間
 * @property tickCapture 1 tick の取り出しの時間の合計（保存した tick の平均と、最大）
 * @property tickEncode 1 tick の符号化の時間の合計（同じ）
 * @property tickCaptureEncode 1 tick の取り出しと符号化の時間の合計（同じ）
 * @property fileWriteMillis ファイルの書き込み（fsync を含む）の時間の合計
 * @property fileBytesAvg 世代のファイルの大きさの平均
 * @property fileBytesP50 世代のファイルの大きさの中央値
 * @property fileBytesMax 世代のファイルの大きさの最大
 * @property fileBytesTotal 世代のファイルの大きさの合計
 * @property tmpWriters `/tmp` に書かせた台のうち、書いた台の数
 * @property tmpWriterTmpfsMin それらの台の `TMPFS` 節の中身の大きさの最小
 * @property tmpWriterTmpfsAvg 同じく平均
 * @property tmpWriterTmpfsMax 同じく最大
 * @property otherTmpfsAvg ほかの台（`/tmp` が空）の `TMPFS` 節の中身の大きさの平均
 * @property otherTmpfsMax 同じく最大
 * @property fullSaves 全体の世代を書いた回数（#83）
 * @property fullBytesTotal 全体の世代のファイルの大きさの合計
 * @property deltaSaves 差分の世代を書いた回数（#83）
 * @property deltaBytesTotal 差分の世代のファイルの大きさの合計
 */
internal data class SaveCost(
    val saves: Int,
    val capture: AvgMax,
    val encode: AvgMax,
    val tickCapture: AvgMax,
    val tickEncode: AvgMax,
    val tickCaptureEncode: AvgMax,
    val fileWriteMillis: Double,
    val fileBytesAvg: Double,
    val fileBytesP50: Int,
    val fileBytesMax: Int,
    val fileBytesTotal: Long,
    val tmpWriters: Int,
    val tmpWriterTmpfsMin: Int,
    val tmpWriterTmpfsAvg: Double,
    val tmpWriterTmpfsMax: Int,
    val otherTmpfsAvg: Double,
    val otherTmpfsMax: Int,
    val fullSaves: Int = 0,
    val fullBytesTotal: Long = 0,
    val deltaSaves: Int = 0,
    val deltaBytesTotal: Long = 0,
)

/**
 * [LoadPhase.AUTOSAVE] の結果: 一巡の tick 数、書いた台・飛ばした台の数、1 tick の書き込み（fsync）の最大、
 * 書き込みにかかった時間の合計（ミリ秒。取り出し・符号化を含む）と、tick の時間からファイルの書き込みの時間を引いたもの
 * （[tickExcludingWrites]。取り出し・符号化は含む。#553）、保存の手間と大きさ（[cost]）。
 * 書き込みはメインスレッドで同期して fsync する（#84 まで）ので、壁時計の MSPT はディスクの速さに左右される
 */
internal data class AutosaveResult(
    val cycleTicks: Int,
    val written: Long,
    val skipped: Long,
    val maxTickWrites: Int,
    val writeMillis: Double,
    val tickExcludingWrites: TickStats,
    val cost: SaveCost,
)

/**
 * [LoadPhase.UNLOAD] の結果: 取り出して書いた台と書き込みの回数（合計と 1 tick の最大）、ケースのあるチャンクのうちアンロードされなかった数とその全体、
 * tick の時間からファイルの書き込みの時間を引いたもの（[tickExcludingWrites]。#553）、保存の手間と大きさ（[cost]）
 */
internal data class UnloadResult(
    val captures: Long,
    val writes: Long,
    val maxTickCaptures: Long,
    val maxTickWrites: Long,
    val stillLoaded: Int,
    val chunks: Int,
    val ticks: Int,
    val tickExcludingWrites: TickStats,
    val cost: SaveCost,
)

/** 負荷試験の結果。 */
internal class LoadReport(
    val settings: LoadSettings,
    /** ブロックを並べるのにかかった時間（1 tick の中） */
    val placeMillis: Double,
    /** 全台の電源を入れるのにかかった時間（1 tick の中） */
    val powerOnMillis: Double,
    /** 計算している台の電源を切るのにかかった時間（1 tick の中） */
    val powerOffMillis: Double,
    /** [LoadPhase.OUTPUT] で、出力させる台が全部、画面に出すまでの tick 数（この後から測る） */
    val outputWaitTicks: Int,
    /** 並べる前に GC したあとのヒープ使用量 */
    val baselineHeapBytes: Long,
    val phases: List<PhaseResult>,
    /** [LoadPhase.AUTOSAVE] の結果 */
    val autosave: AutosaveResult?,
    /** [LoadPhase.UNLOAD] の結果（測らなければ `null`） */
    val unload: UnloadResult? = null,
) {
    fun phase(phase: LoadPhase): PhaseResult = phases.first { it.phase == phase }

    fun toMarkdown(): String {
        val s = settings
        val mib = 1024.0 * 1024.0
        val placed = phases.firstOrNull { it.phase == LoadPhase.PLACED }?.heapUsedBytes ?: baselineHeapBytes
        val idle = phases.firstOrNull { it.phase == LoadPhase.IDLE }?.heapUsedBytes ?: placed
        fun perMachine(from: Long, to: Long) = (to - from).toDouble() / s.count / mib
        val sb = StringBuilder()
        sb.append("- OS: ").append(s.os.label).append('\n')
        sb.append(String.format(Locale.ROOT, "- 台数: %d（計算・出力はそれぞれ %d 台）、tick を 50 ms にそろえる: %s\n", s.count, s.busy, s.paced))
        sb.append(String.format(Locale.ROOT, "- 並べる: %.1f ms、電源を入れる: %.1f ms、計算中の台の電源を切る: %.1f ms（それぞれ 1 tick の中）\n", placeMillis, powerOnMillis, powerOffMillis))
        sb.append(String.format(Locale.ROOT, "- 出力させる台が全部、画面に出すまで: %d tick（ここから「一部が出力し続ける」を測る）\n", outputWaitTicks))
        sb.append(
            String.format(
                Locale.ROOT,
                "- ヒープ（GC 後）: 並べる前 %.0f MiB、並べただけ %.0f MiB、全台が入力待ち %.0f MiB。1台あたり ブロック %.3f MiB + マシン %.3f MiB。上限 %.0f MiB\n",
                baselineHeapBytes / mib, placed / mib, idle / mib, perMachine(baselineHeapBytes, placed), perMachine(placed, idle),
                Runtime.getRuntime().maxMemory() / mib,
            ),
        )
        autosave?.let {
            sb.append(
                String.format(
                    Locale.ROOT,
                    "- 自動保存の一巡: %d tick、書いた台 %d、飛ばした台 %d、1 tick の書き込み（fsync）の最大 %d 回、書き込みの時間の合計 %.0f ms（取り出し・符号化を含む）、" +
                        "書き込みを除く MSPT 平均 %.2f / p50 %.2f / p95 %.2f / 最大 %.2f ms\n",
                    it.cycleTicks, it.written, it.skipped, it.maxTickWrites, it.writeMillis,
                    it.tickExcludingWrites.avg, it.tickExcludingWrites.p50, it.tickExcludingWrites.p95, it.tickExcludingWrites.max,
                ),
            )
        }
        unload?.let {
            sb.append(
                String.format(
                    Locale.ROOT,
                    "- まとめてアンロード: %d tick、取り出して書いた台 %d（書き込み %d 回）、1 tick の最大 %d 台・%d 回、アンロードされなかった %d / %d チャンク（ケースのあるもの）、" +
                        "書き込みを除く MSPT 平均 %.2f / p50 %.2f / p95 %.2f / 最大 %.2f ms\n",
                    it.ticks, it.captures, it.writes, it.maxTickCaptures, it.maxTickWrites, it.stillLoaded, it.chunks,
                    it.tickExcludingWrites.avg, it.tickExcludingWrites.p50, it.tickExcludingWrites.p95, it.tickExcludingWrites.max,
                ),
            )
        }
        val costs = listOfNotNull(autosave?.let { LoadPhase.AUTOSAVE to it.cost }, unload?.let { LoadPhase.UNLOAD to it.cost })
        if (costs.isNotEmpty()) {
            sb.append("\n保存の手間と大きさ（#553。時間はミリ秒の 平均 / 最大。1 tick の合計は、保存した tick の 平均 / 最大）:\n\n")
            sb.append("| 段階 | 書いた台 | 取り出し（1台） | 符号化（1台） | 取り出し（1 tick） | 符号化（1 tick） | 取り出し + 符号化（1 tick） | ファイルの書き込みの合計 |\n")
            sb.append("| --- | --: | --: | --: | --: | --: | --: | --: |\n")
            for ((p, c) in costs) {
                sb.append(
                    String.format(
                        Locale.ROOT, "| %s | %d | %s | %s | %s | %s | %s | %.0f ms |\n",
                        p.label, c.saves, c.capture, c.encode, c.tickCapture, c.tickEncode, c.tickCaptureEncode, c.fileWriteMillis,
                    ),
                )
            }
            sb.append("\n| 段階 | 世代のファイル（平均 / p50 / 最大） | 合計 | TMPFS 節: /tmp に書いた台（台数・最小 / 平均 / 最大） | TMPFS 節: ほかの台（平均 / 最大） |\n")
            sb.append("| --- | --: | --: | --: | --: |\n")
            for ((p, c) in costs) {
                sb.append(
                    String.format(
                        Locale.ROOT, "| %s | %.0f / %d / %d B | %.1f MiB | %d 台・%d / %.0f / %d B | %.0f / %d B |\n",
                        p.label, c.fileBytesAvg, c.fileBytesP50, c.fileBytesMax, c.fileBytesTotal / mib,
                        c.tmpWriters, c.tmpWriterTmpfsMin, c.tmpWriterTmpfsAvg, c.tmpWriterTmpfsMax, c.otherTmpfsAvg, c.otherTmpfsMax,
                    ),
                )
            }
            sb.append("\n| 段階 | 全体の世代（数・合計） | 差分の世代（数・合計） |\n")
            sb.append("| --- | --: | --: |\n")
            for ((p, c) in costs) {
                sb.append(
                    String.format(
                        Locale.ROOT, "| %s | %d・%.1f MiB | %d・%.1f MiB |\n",
                        p.label, c.fullSaves, c.fullBytesTotal / mib, c.deltaSaves, c.deltaBytesTotal / mib,
                    ),
                )
            }
        }
        sb.append("\n| 段階 | 動いている台 | 長さ | MSPT 平均 | p50 | p95 | 最大 | KiBi スレッド（終わり / うち待機中のワーカー / 最大） | JVM スレッド（終わり / 最大） | ヒープ（GC 後） | 画面の同期 |\n")
        sb.append("| --- | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: |\n")
        for (p in phases) {
            val sent = if (p.tick.ticks > 0) p.sentBytes.toDouble() / p.tick.ticks else 0.0
            sb.append(
                String.format(
                    Locale.ROOT, "| %s | %d | %d tick / %.1f s | %.2f | %.2f | %.2f | %.2f | %d / %d / %d | %d / %d | %.0f MiB | %.0f B/tick |\n",
                    p.phase.label, p.running, p.durationTicks, p.wallSeconds, p.tick.avg, p.tick.p50, p.tick.p95, p.tick.max,
                    p.kernelThreads, p.idleWorkerThreads, p.peakKernelThreads, p.jvmThreads, p.peakJvmThreads, p.heapUsedBytes / mib, sent,
                ),
            )
        }
        sb.append("\nカーネルのスレッドの内訳（段階の終わり）:\n")
        for (p in phases) sb.append("- ").append(p.phase.label).append(": ").append(p.kernelThreadNames).append('\n')
        return sb.toString()
    }
}

/**
 * 負荷試験を1回動かす（#345）。[start] で GameTest の毎 tick に段階を進め、終わったら [onDone] に結果を渡して成功にする。
 *
 * tick の時間は、毎 tick の呼び出しの間隔から、この試験の処理（並べる・起動したかを見る・数える）と、
 * tick をそろえるために待った時間を引いたもの。GameTest のサーバーは tick の間で待たないので、残りはサーバーの tick の処理になる。
 * ただし、並べる・電源を入れる・切るのにかかった時間は、別に [LoadReport] に書く。
 */
internal class LoadRun(
    private val helper: GameTestHelper,
    private val settings: LoadSettings,
    private val onDone: (LoadReport) -> Unit,
) {
    private class Computer(val case: CaseBlockEntity, val screen: ScreenBlockEntity, val keyboard: KeyboardBlockEntity) {
        var ready = false
    }

    private val level = helper.level
    private val threads = ManagementFactory.getThreadMXBean()
    private val memory = ManagementFactory.getMemoryMXBean()
    private val columns = LoadGameTests.Layout.columns(settings.count)
    private val computers = ArrayList<Computer>(settings.count)
    private val forced = ArrayList<ChunkPos>()
    private val results = ArrayList<PhaseResult>()

    private val computeSet: List<Computer> get() = computers.subList(0, minOf(settings.busy, computers.size))
    private val outputSet: List<Computer> get() = computers.subList(minOf(settings.busy, computers.size), minOf(settings.busy * 2, computers.size))

    /** `/tmp` にファイルを書かせる台（#553）。計算・出力させる台とは別にし、最後まで動かしておく（アンロードでも書く） */
    private val tmpSet: List<Computer> get() = computers.subList(minOf(settings.busy * 2, computers.size), minOf(settings.busy * 3, computers.size))

    private var phase: LoadPhase? = null
    private var phaseTick = 0

    /** 測りはじめの基準にする、段階の中の tick。`null` なら、まだ出力（[awaitOutput]）か `/tmp` の書き込み（[awaitTmp]）を待っている */
    private var measureFrom: Int? = 0
    private var outputWaitTicks = 0
    private var phaseStartNanos = 0L
    private var recording = false
    private var samples = LongArray(1024)

    /** [samples] と同じ tick の、保存のファイルの書き込み（fsync を含む）にかかった時間（[ServerMachines.Stats.fileWriteNanos] の増分） */
    private var writeSamples = LongArray(1024)

    /** [samples] と同じ tick の、取り出し・符号化にかかった時間（[ServerMachines.Stats.captureNanos]・`encodeNanos` の増分） */
    private var captureSamples = LongArray(1024)
    private var encodeSamples = LongArray(1024)
    private var sampleCount = 0

    /** 前の呼び出しの終わりの [ServerMachines.Stats] の累計（tick ごとの増分を出すため） */
    private var prevFileWrite = 0L
    private var prevCapture = 0L
    private var prevEncode = 0L
    private var peakKernelThreads = 0
    private var peakPhaseKernelThreads = 0

    /** 段階に入ったときにあったカーネルのスレッドの ID。これらのうち、プールで待っているワーカーは [peakPhaseKernelThreads] に数えない */
    private var baselineKernelThreads: Set<Long> = emptySet()
    private var sentBytes = 0L
    private var countSent = false

    private var placeMillis = 0.0
    private var powerOnMillis = 0.0
    private var powerOffMillis = 0.0
    private var baselineHeap = 0L
    private var watcher: ServerPlayer? = null
    private var finished = false
    private var autosaveResult: AutosaveResult? = null
    private var unloadResult: UnloadResult? = null
    private var unloadCaseChunks = 0
    private val unforcedOthers = ArrayList<ChunkPos>()
    private var unloadPrevCaptures = 0L
    private var savedSpawnChunkRadius: Int? = null
    private var savedSaveConfig: MachineSaveConfig? = null
    private var savedAutosaveEnabled: Boolean? = null
    private var unloadPrevWrites = 0L
    private var unloadMaxTickCaptures = 0L
    private var unloadMaxTickWrites = 0L

    private var prevCallback = 0L
    private var prevHarness = 0L
    private var prevSleep = 0L

    fun start() {
        // MOD の自動保存の周期は止める（ほかの段階に混ざらないように）。自動保存の段階は周期を待たずに始める
        savedSaveConfig = ServerMachines.saveConfig
        ServerMachines.saveConfig = ServerMachines.saveConfig.copy(autosavePeriodTicks = Long.MAX_VALUE)
        // GameTest のサーバーでは自動保存が切られているので、試験の間だけ有効にする
        savedAutosaveEnabled = ServerMachines.autosaveEnabled
        ServerMachines.autosaveEnabled = true
        // 保存1回ごとの記録を残す（保存の手間と大きさを測る。#553）
        ServerMachines.Stats.recordSaves = true
        helper.onEachTick(::onTick)
    }

    private fun onTick() {
        if (finished) return
        val now = System.nanoTime()
        var work = 0L
        if (prevCallback != 0L) {
            work = (now - prevCallback - prevHarness - prevSleep).coerceAtLeast(0)
            // 自動保存（ServerTickEvent.Pre）・アンロード（チャンクのアンロード、ServerTickEvent.Post）の保存は、前の呼び出しからの間に入っている
            val s = ServerMachines.Stats
            if (recording) record(work, s.fileWriteNanos - prevFileWrite, s.captureNanos - prevCapture, s.encodeNanos - prevEncode)
        }
        prevCallback = now
        try {
            step()
        } catch (t: Throwable) {
            cleanUp()
            throw t
        }
        // step() が Stats.reset() したときも、次の増分がこの時点からになるように、step() の後で覚える
        ServerMachines.Stats.let {
            prevFileWrite = it.fileWriteNanos
            prevCapture = it.captureNanos
            prevEncode = it.encodeNanos
        }
        val end = System.nanoTime()
        prevHarness = end - now
        prevSleep = 0
        if (settings.paced && !finished) {
            // 実際のサーバーと同じく、サーバーの次の tick の予定（getNextTickTime）までしか待たない。遅れていれば待たずに追いつく。
            // 1 tick の長さだけで待つと、重い tick（アンロードの書き込みなど）で遅れた分が戻らず、tick の残り時間（haveTime）が
            // ずっとなくなる。するとチャンクのアンロードの列が 2,000 件を超えるまで流れず、アンロードが進まない（#540）
            val wait = minOf(TARGET_NANOS - work - prevHarness, level.server.nextTickTime - System.nanoTime())
            if (wait > 0) {
                LockSupport.parkNanos(wait)
                prevSleep = System.nanoTime() - end
            }
        }
    }

    private fun record(nanos: Long, writeNanos: Long, captureNanos: Long, encodeNanos: Long) {
        if (sampleCount == samples.size) {
            samples = samples.copyOf(samples.size * 2)
            writeSamples = writeSamples.copyOf(samples.size)
            captureSamples = captureSamples.copyOf(samples.size)
            encodeSamples = encodeSamples.copyOf(samples.size)
        }
        samples[sampleCount] = nanos
        writeSamples[sampleCount] = writeNanos
        captureSamples[sampleCount] = captureNanos
        encodeSamples[sampleCount] = encodeNanos
        sampleCount++
    }

    /** 測った tick の時間から、ファイルの書き込みの時間を引いたもの（取り出し・符号化は含む） */
    private fun tickExcludingWrites(): TickStats =
        TickStats.of(LongArray(sampleCount) { (samples[it] - writeSamples[it]).coerceAtLeast(0) })

    /** 段階の間の保存の手間と大きさ（[ServerMachines.Stats.saves] と、tick ごとの増分から） */
    private fun saveCost(): SaveCost {
        val saves = ServerMachines.Stats.saves.toList()
        val savedTicks = (0 until sampleCount).filter { captureSamples[it] > 0 || encodeSamples[it] > 0 }
        fun perTick(f: (Int) -> Long) = AvgMax.ofNanos(LongArray(savedTicks.size) { f(savedTicks[it]) })
        val sizes = saves.map { it.fileBytes }.sorted()
        val tmpPositions = tmpSet.mapTo(HashSet()) { it.case.blockPos }
        val (writers, others) = saves.partition { it.pos in tmpPositions }
        return SaveCost(
            saves = saves.size,
            capture = AvgMax.ofNanos(saves.map { it.captureNanos }.toLongArray()),
            encode = AvgMax.ofNanos(saves.map { it.encodeNanos }.toLongArray()),
            tickCapture = perTick { captureSamples[it] },
            tickEncode = perTick { encodeSamples[it] },
            tickCaptureEncode = perTick { captureSamples[it] + encodeSamples[it] },
            fileWriteMillis = saves.sumOf { it.fileWriteNanos } / 1e6,
            fileBytesAvg = if (sizes.isEmpty()) 0.0 else sizes.average(),
            fileBytesP50 = if (sizes.isEmpty()) 0 else sizes[(sizes.size - 1) / 2],
            fileBytesMax = sizes.lastOrNull() ?: 0,
            fileBytesTotal = sizes.sumOf { it.toLong() },
            tmpWriters = writers.size,
            tmpWriterTmpfsMin = writers.minOfOrNull { it.tmpfsBytes } ?: 0,
            tmpWriterTmpfsAvg = if (writers.isEmpty()) 0.0 else writers.map { it.tmpfsBytes }.average(),
            tmpWriterTmpfsMax = writers.maxOfOrNull { it.tmpfsBytes } ?: 0,
            otherTmpfsAvg = if (others.isEmpty()) 0.0 else others.map { it.tmpfsBytes }.average(),
            otherTmpfsMax = others.maxOfOrNull { it.tmpfsBytes } ?: 0,
            fullSaves = saves.count { !it.delta },
            fullBytesTotal = saves.filter { !it.delta }.sumOf { it.fileBytes.toLong() },
            deltaSaves = saves.count { it.delta },
            deltaBytesTotal = saves.filter { it.delta }.sumOf { it.fileBytes.toLong() },
        )
    }

    private fun step() {
        val p = phase
        if (p == null) {
            forceChunks()
            baselineHeap = heapAfterGc()
            enter(sequence.first())
        } else {
            if (phaseTick == 0) setUp(p)
            when (p) {
                LoadPhase.BOOT -> boot()
                LoadPhase.AUTOSAVE -> autosave()
                LoadPhase.UNLOAD -> unload()
                else -> steady(p)
            }
        }
        phaseTick++
    }

    /** 段階の順番。計算・出力させる台がなければ、その段階は飛ばす */
    private val sequence: List<LoadPhase> = buildList {
        add(LoadPhase.EMPTY)
        add(LoadPhase.PLACED)
        add(LoadPhase.BOOT)
        add(LoadPhase.IDLE)
        add(LoadPhase.AUTOSAVE)
        if (settings.busy > 0) add(LoadPhase.COMPUTE)
        if (settings.busy > 0 && settings.count > settings.busy) {
            add(LoadPhase.OUTPUT)
            add(LoadPhase.WATCHED)
        }
        if (settings.massUnload) add(LoadPhase.UNLOAD)
    }

    private fun next(p: LoadPhase) {
        verify(p)
        finish()
        val i = sequence.indexOf(p) + 1
        if (i < sequence.size) enter(sequence[i]) else done()
    }

    private fun boot() {
        if (phaseTick % 10 != 0) return
        sampleKernelThreads()
        var waiting = 0
        for (c in computers) {
            if (!c.ready) c.ready = hasPrompt(c.screen.text, settings.os.prompt)
            if (!c.ready) waiting++
        }
        if (waiting == 0) {
            next(LoadPhase.BOOT)
        } else if (phaseTick >= settings.bootTimeoutTicks) {
            val c = computers.first { !it.ready }
            fail("$waiting of ${settings.count} computers did not show the prompt within ${settings.bootTimeoutTicks} ticks: ${lines(c.screen.text)}")
        }
    }

    private fun autosave() {
        if (phaseTick == 0) return
        if (ServerMachines.isAutosaveCycleDone) {
            val s = ServerMachines.Stats
            autosaveResult = AutosaveResult(
                cycleTicks = s.autosaveCycleTicks.lastOrNull() ?: phaseTick,
                written = s.autosaveWritten,
                skipped = s.autosaveSkipped,
                maxTickWrites = s.autosaveMaxTickWrites,
                writeMillis = s.autosaveWriteNanos / 1e6,
                tickExcludingWrites = tickExcludingWrites(),
                cost = saveCost(),
            )
            next(LoadPhase.AUTOSAVE)
        } else if (phaseTick >= AUTOSAVE_TIMEOUT_TICKS) {
            fail("the autosave cycle did not finish within $AUTOSAVE_TIMEOUT_TICKS ticks")
        }
    }

    private fun unload() {
        val s = ServerMachines.Stats
        unloadMaxTickCaptures = maxOf(unloadMaxTickCaptures, s.unloadCaptures - unloadPrevCaptures)
        unloadMaxTickWrites = maxOf(unloadMaxTickWrites, s.unloadWrites - unloadPrevWrites)
        unloadPrevCaptures = s.unloadCaptures
        unloadPrevWrites = s.unloadWrites
        if (phaseTick % 10 != 0) return
        // ケースのあるチャンクが全部アンロードされたら終わり（hasChunk は FULL でなくなっただけでも偽になるので、Unload を数える）
        val remaining = unloadCaseChunks - s.unloadEventsWithCases.toInt()
        if (remaining <= 0 || phaseTick >= UNLOAD_TIMEOUT_TICKS) {
            unloadResult = UnloadResult(
                s.unloadCaptures, s.unloadWrites, unloadMaxTickCaptures, unloadMaxTickWrites, remaining.coerceAtLeast(0), unloadCaseChunks, phaseTick,
                tickExcludingWrites(), saveCost(),
            )
            next(LoadPhase.UNLOAD)
        }
    }

    private fun steady(p: LoadPhase) {
        val from = measureFrom ?: return when (p) {
            LoadPhase.IDLE -> awaitTmp()
            LoadPhase.COMPUTE -> awaitCompute()
            else -> awaitOutput()
        }
        val t = phaseTick - from
        if (t == settings.warmupTicks) {
            recording = true
            countSent = true
            threads.resetPeakThreadCount()
            peakKernelThreads = 0
            peakPhaseKernelThreads = 0
        }
        if (recording && phaseTick % 10 == 0) sampleKernelThreads()
        if (t == settings.warmupTicks + settings.measureTicks) next(p)
    }

    /**
     * [LoadPhase.OUTPUT] のはじめ: 出力させる台が全部、画面に数字を出すまで待ってから測りはじめる。
     * GameTest のサーバーは tick の間で待たないので、測る tick 数だけでは実時間が足りないことがある
     * （CPU の枠が少ない環境では、10 台が順に枠を待つ。CI のランナーは2コアで枠が1つ）
     */
    private fun awaitOutput() {
        if (phaseTick % 10 != 0) return
        val waiting = outputSet.count { !showsOutput(it) }
        if (waiting == 0) {
            measureFrom = phaseTick
            outputWaitTicks = phaseTick
        } else if (phaseTick >= settings.outputTimeoutTicks) {
            val c = outputSet.first { !showsOutput(it) }
            fail("$waiting of ${outputSet.size} output machines showed nothing within ${settings.outputTimeoutTicks} ticks: ${lines(c.screen.text)}")
        }
    }

    /**
     * [LoadPhase.COMPUTE] のはじめ: 計算させる台が全部、打ち込んだ命令を画面に出す（シェルが読んだ）まで待ってから測りはじめる。
     * GameTest のサーバーは tick の間で待たないので、CPU の枠が少ない環境（CI のランナーは2コア）では、
     * 測る tick 数が終わっても、シェルが入力を読んでいないことがある
     */
    private fun awaitCompute() {
        if (phaseTick % 10 != 0) return
        val waiting = computeSet.count { !runsLoop(it) }
        if (waiting == 0) {
            measureFrom = phaseTick
        } else if (phaseTick >= settings.outputTimeoutTicks) {
            val c = computeSet.first { !runsLoop(it) }
            fail("$waiting of ${computeSet.size} computing machines did not show the command within ${settings.outputTimeoutTicks} ticks: ${lines(c.screen.text)}")
        }
    }

    private fun runsLoop(c: Computer): Boolean =
        lines(c.screen.text).lastOrNull { it.isNotEmpty() } == "${settings.os.prompt} ${settings.os.computeCommand}"

    /**
     * [LoadPhase.IDLE] のはじめ: `/tmp` に書かせる台（[tmpSet]）が全部、書き終えてプロンプトに戻るまで待ってから測りはじめる（#553）。
     * 書き終えたかは、終わりの行（[LoadOs.tmpDoneLine]）が出て、最後の行がプロンプトだけになったかで見る
     */
    private fun awaitTmp() {
        if (phaseTick % 10 != 0) return
        val waiting = tmpSet.count { !wroteTmp(it) }
        if (waiting == 0) {
            measureFrom = phaseTick
        } else if (phaseTick >= settings.outputTimeoutTicks) {
            val c = tmpSet.first { !wroteTmp(it) }
            fail("$waiting of ${tmpSet.size} machines did not finish writing to /tmp within ${settings.outputTimeoutTicks} ticks: ${lines(c.screen.text)}")
        }
    }

    private fun wroteTmp(c: Computer): Boolean {
        val text = lines(c.screen.text)
        return text.lastOrNull { it.isNotEmpty() } == settings.os.prompt.toString() && text.any { it == settings.os.tmpDoneLine }
    }

    private fun showsOutput(c: Computer): Boolean = lines(c.screen.text).any { line -> line.isNotEmpty() && line.all { it.isDigit() } }

    private fun setUp(p: LoadPhase) {
        when (p) {
            LoadPhase.EMPTY -> Unit
            // 一部の台に `/tmp` へファイルを書かせる（保存の `TMPFS` 節を測るため。#553）。書き終わるまで待ってから測る
            LoadPhase.IDLE -> for (c in tmpSet) type(c, settings.os.tmpCommand)
            LoadPhase.PLACED -> {
                val t0 = System.nanoTime()
                for (i in 0 until settings.count) LoadGameTests.Layout.placeComputer(helper, LoadGameTests.Layout.casePos(i, columns), settings.os)
                placeMillis = (System.nanoTime() - t0) / 1e6
            }
            LoadPhase.BOOT -> {
                val t0 = System.nanoTime()
                for (i in 0 until settings.count) {
                    val pos = LoadGameTests.Layout.casePos(i, columns)
                    val case = helper.getBlockEntity<CaseBlockEntity>(pos)
                    if (case.pressPowerButton() != ComputerStatus.RUNNING) fail("computer #$i did not turn on: ${case.computer.status}")
                    computers += Computer(case, helper.getBlockEntity(pos.above()), helper.getBlockEntity(pos.south()))
                }
                powerOnMillis = (System.nanoTime() - t0) / 1e6
                for (c in computers) {
                    if (c.case.wiring.connectedScreen !== c.screen || c.case.wiring.connectedKeyboard !== c.keyboard) {
                        fail("computer at ${c.case.blockPos} is not wired to its own screen and keyboard")
                    }
                }
                // 起動は、電源を入れた次の tick から終わるまでを全部測る
                recording = true
            }
            LoadPhase.AUTOSAVE -> {
                ServerMachines.Stats.reset()
                ServerMachines.startAutosaveCycle()
                recording = true
            }
            LoadPhase.COMPUTE -> for (c in computeSet) type(c, settings.os.computeCommand)
            LoadPhase.OUTPUT -> {
                val t0 = System.nanoTime()
                for (c in computeSet) c.case.pressPowerButton()
                powerOffMillis = (System.nanoTime() - t0) / 1e6
                for (c in outputSet) type(c, settings.os.outputCommand)
            }
            LoadPhase.WATCHED -> watch(outputSet)
            LoadPhase.UNLOAD -> {
                // 見ている人は遠くへ動かす（プレイヤーの近くのチャンクは読み込まれたまま。外すのは cleanUp）
                watcher?.let {
                    it.teleportTo(it.x + 1_000_000, it.y, it.z)
                    level.chunkSource.move(it)
                }
                ServerMachines.Stats.reset()
                // スポーンチャンクに重なると読み込まれたままになるので、この段階だけスポーンチャンクをなくす
                val rule = level.gameRules.getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS)
                savedSpawnChunkRadius = rule.get()
                rule.set(0, level.server)
                unloadCaseChunks = computers.mapTo(HashSet()) { ChunkPos(it.case.blockPos) }.size
                for (chunk in forced) level.setChunkForced(chunk.x, chunk.z, false)
                forced.clear()
                // テストの構造物のチャンク（GameTest が強制読み込みする）も外す。強制読み込みのチャンクは、まわり 13 チャンクを
                // アンロードさせない（チケットの段階 31 が 44 まで広がる）ので、並べた範囲が全部残ってしまう。終わったら戻す
                for (packed in level.forcedChunks.toLongArray()) {
                    val chunk = ChunkPos(packed)
                    level.setChunkForced(chunk.x, chunk.z, false)
                    unforcedOthers += chunk
                }
                recording = true
            }
        }
    }

    private fun verify(p: LoadPhase) {
        when (p) {
            LoadPhase.EMPTY, LoadPhase.PLACED, LoadPhase.BOOT -> Unit
            LoadPhase.AUTOSAVE -> {
                val r = autosaveResult!!
                if (r.written + r.skipped != settings.count.toLong()) fail("the autosave should visit all ${settings.count} machines: $r")
                val notRunning = computers.count { it.case.computer.machine?.state != MachineState.RUNNING }
                if (notRunning > 0) fail("$notRunning computers stopped during the autosave")
            }
            LoadPhase.IDLE -> {
                val notRunning = computers.count { it.case.computer.machine?.state != MachineState.RUNNING }
                if (notRunning > 0) fail("$notRunning computers stopped while waiting for input")
            }
            LoadPhase.COMPUTE -> for (c in computeSet) {
                if (c.case.computer.machine?.state != MachineState.RUNNING) fail("a computing machine stopped: ${lines(c.screen.text)}")
                val text = lines(c.screen.text)
                // 計算している間はプロンプトに戻らない
                if (!runsLoop(c)) fail("the loop should keep running: $text")
            }
            LoadPhase.UNLOAD -> Unit
            LoadPhase.OUTPUT, LoadPhase.WATCHED -> for (c in outputSet) {
                if (c.case.computer.machine?.state != MachineState.RUNNING) fail("an output machine stopped: ${lines(c.screen.text)}")
                if (!showsOutput(c)) fail("output should be shown: ${lines(c.screen.text)}")
            }
        }
    }

    private fun enter(p: LoadPhase) {
        phase = p
        phaseTick = -1 // step() が 0 にする
        measureFrom = if (p == LoadPhase.OUTPUT || p == LoadPhase.IDLE || p == LoadPhase.COMPUTE) null else 0
        phaseStartNanos = System.nanoTime()
        recording = false
        countSent = false
        sampleCount = 0
        sentBytes = 0
        peakKernelThreads = 0
        peakPhaseKernelThreads = 0
        threads.resetPeakThreadCount()
        baselineKernelThreads = kernelThreadInfos().mapTo(HashSet()) { it.threadId }
    }

    private fun finish() {
        val p = phase ?: return
        val wall = (System.nanoTime() - phaseStartNanos) / 1e9
        val stats = TickStats.of(samples.copyOf(sampleCount))
        val infos = kernelThreadInfos()
        val names = infos.groupingBy { it.threadName.replace(DIGITS, "#") }.eachCount().toSortedMap()
        val kernel = infos.size
        val idleWorkers = infos.count(::isIdleWorker)
        val result = PhaseResult(
            phase = p,
            running = computers.count { it.case.computer.machine?.state == MachineState.RUNNING },
            tick = stats,
            kernelThreads = kernel,
            idleWorkerThreads = idleWorkers,
            peakKernelThreads = maxOf(peakKernelThreads, kernel),
            peakPhaseKernelThreads = maxOf(peakPhaseKernelThreads, infos.count(::countsInPhase)),
            jvmThreads = threads.threadCount,
            peakJvmThreads = threads.peakThreadCount,
            heapUsedBytes = heapAfterGc(),
            sentBytes = sentBytes,
            durationTicks = phaseTick + 1,
            wallSeconds = wall,
            kernelThreadNames = names,
        )
        results += result
    }

    private fun done() {
        val report = LoadReport(settings, placeMillis, powerOnMillis, powerOffMillis, outputWaitTicks, baselineHeap, results.toList(), autosaveResult, unloadResult)
        cleanUp()
        onDone(report)
        helper.succeed()
    }

    private fun type(c: Computer, line: String) {
        val events = listOf(KeyboardEvent.Text(line), KeyboardEvent.KeyDown(Key.ENTER, 0, false))
        for (packet in KeyboardEventCodec.encode(events)) {
            if (c.keyboard.receive(LOAD_TYPIST, packet) < 0) fail("the keyboard rejected the input")
        }
    }

    // 偽物のプレイヤーを並べた範囲の真ん中に置く。[screens] を見るかはサーバーが距離で決める（#359）。送ったものは数えるだけ
    private fun watch(screens: List<Computer>) {
        if (screens.isEmpty()) return
        ScreenSync.transport = ScreenSync.Transport { player, payload ->
            if (player === watcher) {
                if (countSent) sentBytes += payload.data.size
            } else {
                ScreenSync.NETWORK.send(player, payload)
            }
        }
        val player = helper.makeMockServerPlayerInLevel()
        val rows = (settings.count + columns - 1) / columns
        val center = helper.absoluteVec(
            BlockPos(columns * LoadGameTests.Layout.STEP_X / 2, 3, rows * LoadGameTests.Layout.STEP_Z / 2).center,
        )
        player.teleportTo(center.x, center.y, center.z)
        level.chunkSource.move(player)
        watcher = player
        for (c in screens) ScreenSyncGameTests.deliverChunks(player, c.screen.blockPos)
    }

    private fun forceChunks() {
        val rows = (settings.count + columns - 1) / columns
        val a = helper.absolutePos(BlockPos(0, 0, 0))
        val b = helper.absolutePos(BlockPos(columns * LoadGameTests.Layout.STEP_X, 0, rows * LoadGameTests.Layout.STEP_Z))
        val already = level.forcedChunks
        for (cx in (minOf(a.x, b.x) shr 4)..(maxOf(a.x, b.x) shr 4)) {
            for (cz in (minOf(a.z, b.z) shr 4)..(maxOf(a.z, b.z) shr 4)) {
                val chunk = ChunkPos(cx, cz)
                if (!already.contains(chunk.toLong()) && level.setChunkForced(cx, cz, true)) forced += chunk
            }
        }
    }

    private fun cleanUp() {
        if (finished) return
        finished = true
        recording = false
        for (i in 0 until settings.count) {
            val pos = LoadGameTests.Layout.casePos(i, columns)
            helper.setBlock(pos, Blocks.AIR)
            helper.setBlock(pos.above(), Blocks.AIR)
            helper.setBlock(pos.south(), Blocks.AIR)
        }
        watcher?.let { level.server.playerList.remove(it) }
        watcher = null
        ScreenSync.transport = ScreenSync.NETWORK
        for (chunk in forced) level.setChunkForced(chunk.x, chunk.z, false)
        forced.clear()
        for (chunk in unforcedOthers) level.setChunkForced(chunk.x, chunk.z, true)
        unforcedOthers.clear()
        savedSpawnChunkRadius?.let { level.gameRules.getRule(GameRules.RULE_SPAWN_CHUNK_RADIUS).set(it, level.server) }
        savedSpawnChunkRadius = null
        savedSaveConfig?.let { ServerMachines.saveConfig = it }
        savedSaveConfig = null
        savedAutosaveEnabled?.let { ServerMachines.autosaveEnabled = it }
        savedAutosaveEnabled = null
        ServerMachines.Stats.recordSaves = false
        ServerMachines.Stats.saves.clear()
    }

    private fun sampleKernelThreads() {
        val infos = kernelThreadInfos()
        peakKernelThreads = maxOf(peakKernelThreads, infos.size)
        peakPhaseKernelThreads = maxOf(peakPhaseKernelThreads, infos.count(::countsInPhase))
    }

    private fun kernelThreadInfos(): List<ThreadInfo> =
        threads.dumpAllThreads(false, false, STACK_DEPTH).filter { it.threadName.startsWith("KiBi-") }

    /** [peakPhaseKernelThreads] に数えるか: この段階で作られたスレッドか、仕事をしている（プールで待っていない）スレッド */
    private fun countsInPhase(info: ThreadInfo): Boolean = info.threadId !in baselineKernelThreads || !isIdleWorker(info)

    /** プールで次の仕事を待っているワーカー（しばらくすると終わる） */
    private fun isIdleWorker(info: ThreadInfo): Boolean =
        info.stackTrace.any { it.className == ThreadPoolExecutor::class.java.name && it.methodName == "getTask" }

    private fun heapAfterGc(): Long {
        System.gc()
        return memory.heapMemoryUsage.used
    }

    private fun fail(message: String): Nothing = throw GameTestAssertException(message)

    companion object {
        /** 自動保存の一巡を待つ上限 */
        private const val AUTOSAVE_TIMEOUT_TICKS = 2_000

        /** まとめてアンロードを待つ上限（過ぎたら、そこまでの結果を書く） */
        private const val UNLOAD_TIMEOUT_TICKS = 2_000

        private val DIGITS = Regex("[0-9]+")

        /** プールで仕事を待っているワーカーを見分けるのに読むスタックの深さ */
        private const val STACK_DEPTH = 32

        private const val TARGET_NANOS = (LoadGameTests.TARGET_TICK_MILLIS * 1_000_000).toLong()

        /** 画面にプロンプト（[prompt] の文字だけの行）がある */
        fun hasPrompt(text: TextBuffer, prompt: Char): Boolean {
            for (y in 0 until text.height) {
                if (text.getCodePoint(0, y) != prompt.code) continue
                var blank = true
                for (x in 1 until text.width) {
                    if (text.getCodePoint(x, y) != ' '.code) {
                        blank = false
                        break
                    }
                }
                if (blank) return true
            }
            return false
        }

        fun lines(text: TextBuffer): List<String> = (0 until text.height).map { y ->
            val sb = StringBuilder()
            for (x in 0 until text.width) sb.appendCodePoint(text.getCodePoint(x, y))
            sb.toString().trimEnd()
        }
    }
}
