package pro.ikili.kibicomputers.neoforge.gametest

import com.mojang.logging.LogUtils
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestAssertException
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.block.Blocks
import org.apache.logging.log4j.core.config.Configurator
import pro.ikili.kibicomputers.core.computer.ComputerStatus
import pro.ikili.kibicomputers.core.display.TextBuffer
import pro.ikili.kibicomputers.core.input.Key
import pro.ikili.kibicomputers.core.input.KeyboardEvent
import pro.ikili.kibicomputers.core.input.KeyboardEventCodec
import pro.ikili.kibicomputers.core.kernel.MachineState
import pro.ikili.kibicomputers.core.config.ConfigSide
import pro.ikili.kibicomputers.core.config.KiBiConfig
import pro.ikili.kibicomputers.core.persist.MachineSaveConfig
import pro.ikili.kibicomputers.core.persist.SaveWriter
import pro.ikili.kibicomputers.core.device.wiring.ExternalDeviceHolder
import pro.ikili.kibicomputers.core.kernel.BootConfig
import pro.ikili.kibicomputers.core.kernel.SchedulerConfig
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
import pro.ikili.kibicomputers.neoforge.computer.ProvisionalBoot
import pro.ikili.kibicomputers.neoforge.config.KiBiConfigs
import pro.ikili.kibicomputers.neoforge.display.ScreenBlockEntity
import pro.ikili.kibicomputers.neoforge.display.ScreenSync
import pro.ikili.kibicomputers.neoforge.input.KeyboardBlockEntity
import pro.ikili.kibicomputers.neoforge.machine.ServerMachines
import pro.ikili.kibicomputers.neoforge.machine.WorldMachineSaves
import java.lang.management.ManagementFactory
import java.lang.management.ThreadInfo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import org.apache.logging.log4j.Level as Log4jLevel
import org.apache.logging.log4j.LogManager as Log4jManager

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
 * @property bootDisk ケースの既定の起動ディスク（`fallbackBootDisk`）。`null` ならゲームの既定（標準 OS。#461）
 * @property computeProcess [computeCommand] が動かすプロセスの名前（`argv[0]`）。`null` でなければ、[LoadPhase.COMPUTE] は、
 *   計算させる台の全部にこのプロセスがあり、全部の台で段階に入ってからの台ごとの燃料切れの巻き戻し（`Machine.fuelUnwindCount`）が
 *   1 回以上になってから測りはじめる（読み込み・起動の途中を測らない。#821）
 * @property ci CI の試験（`runGameTestServer`）でも動かすか。`false` のものは測定用（`-PloadTestOs`）だけ
 * @property backgroundCommand [computeCommand] をバックグラウンドで動かすコマンド（#740。[LoadPhase.COMPUTE_MULTI] で、1台に計算するプロセスを
 *   複数作る）。シェルは `[k] pid` を出してからプロンプトに戻る。`null` なら、1台に計算するプロセスを複数作れない（Lua の REPL）ので、その段階を飛ばす
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
    val bootDisk: String? = null,
    val computeProcess: String? = null,
    val ci: Boolean = true,
    val backgroundCommand: String? = null,
) {
    /** テスト用の起動ディスク（#307）の Lua の REPL。`close` の戻り値 `true` を REPL が出す */
    LUA(
        "lua", "Lua の REPL", '>', "while true do end", "local i = 0 while true do i = i + 1 print(i) end",
        "io.output\"/tmp/l\":write((\"x\"):rep(4^7)):close()", "true", 16_384, bootDisk = ProvisionalBoot.TEST_BOOT_DISK,
    ),

    /** 標準 OS（#98。ゲームの既定。#461）のシェル。`seq 3300` は 15,393 バイト */
    STANDARD(
        "standard", "標準 OS のシェル", '$', "while :; do :; done", "i=0; while :; do i=$((i+1)); echo \$i; done",
        "seq 3300 >/tmp/l; echo ok", "ok", 15_393, backgroundCommand = "while :; do :; done &",
    ),

    /**
     * 標準 OS のシェルから、計算し続ける p2 のコマンド（コンポーネント）を動かす（#821）。起動ディスクは、標準 OS のイメージに
     * `/bin/spin`（core のテストの `fuel-spin-p2.wasm`。wasi-sdk で wasi-libc が p2 の import を直接呼ぶ形）を足した `image:loadtest-p2`
     * （接続層の dev ソースセットのリソース。jar には入らない）。`spin main 9 4000000000` は main の中のループ（止まりうる関数。
     * 燃料切れを巻き戻せる。ADR 0757）で、1 ラウンドに約 4×10⁹ 回まわし、ラウンドが終わるまで画面に何も出さない
     * （既定の燃料 1 tick 100 万では、1 ラウンドに数万 tick かかる）。jar の GameTest にはイメージがないので、CI では動かさない
     */
    STANDARD_P2(
        "standard-p2", "標準 OS のシェル（p2 のコマンドで計算）", '$', "spin main 9 4000000000", "i=0; while :; do i=$((i+1)); echo \$i; done",
        "seq 3300 >/tmp/l; echo ok", "ok", 15_393,
        bootDisk = BootConfig.IMAGE_DISK_PREFIX + "loadtest-p2", computeProcess = "spin", ci = false,
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

    /**
     * 一部の台で、計算し続けるプロセスが1台に複数（[LoadSettings.computeProcesses] 本。#740）。[COMPUTE] の台は電源を切り、別の台
     * （[COMPUTE] と同じ台数）でバックグラウンドに N−1 本、前景に1本動かす。標準 OS のシェルだけ（[LoadOs.backgroundCommand]）
     */
    COMPUTE_MULTI("一部が計算し続ける（1台に複数）"),

    /** 一部の台が画面に出力し続ける。誰も見ていない（計算していた台（[COMPUTE] か [COMPUTE_MULTI]）は電源を切る。残りは入力待ち） */
    OUTPUT("一部が出力し続ける"),

    /** [OUTPUT] の画面を、1人が全部見ている（画面の同期が送られる） */
    WATCHED("一部が出力し続け、1人が見る"),

    /**
     * 並べた範囲のチャンクの強制読み込みを外し、一斉にアンロードさせる（#81）。アンロードで全台を取り出して書く（延ばせない書き込みの最悪値）。
     * 測るだけで、目標値では落とさない（#84 の基準にする）
     */
    UNLOAD("まとめてアンロード"),

    /**
     * [UNLOAD] の後に、外した強制読み込みを戻し、アンロードの時点で動いていた台が全部戻るまで（#582。ADR 0085 の復元の順番待ち）。
     * 同じ JVM なのでモジュールは共有済みで、コンパイル待ちの経路は通らない。測るだけで、目標値では落とさない
     */
    RESTORE("強制読み込みを戻して全台が戻る"),
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
 * @property massRestore [massUnload] の後に [LoadPhase.RESTORE] を測るか（#582。測定用だけ。CI の試験では動かさない）
 * @property quietProgress 試験の間、GameTest のサーバーの進み具合のログを止めるか（#600）
 * @property devicesEvery この台数に1台の割合で、ケースの北隣に他 MOD 役のデバイス（樽。#153）を置く（`-PloadTestDevices`。0 なら置かない）
 * @property productionAutosave 自動保存の段階を本番の形で測るか（`-PloadTestProductionAutosave`。#834）。周期を止めずに、数え口を合わせて
 *   本物の経路（`ServerMachines.autosaveTick` の `% autosavePeriodTicks`）で一巡を始め、バニラの自動保存（`MinecraftServer.tickServer` の
 *   `saveEverything`）を同じ tick（[vanillaSaveOffset] が 0 のとき）に走らせる。一巡の終わりはメインスレッドを止めずに毎 tick 見る。
 *   この段階で試験を終える（計算・出力・アンロードは測らない）
 * @property vanillaSaveOffset [productionAutosave] のとき、バニラの自動保存を一巡の始めの tick から何 tick 後にするか（`-PloadTestVanillaSaveOffset`）。
 *   0 が本番の既定（周期 6000 tick・tick rate 20 のまま）の重なり方。正の値は、周期を変えた・tick rate を変えたときなどに一巡の途中に入る形
 * @property syntheticLogTicks [productionAutosave] の段階の間、メインスレッドに合成のログを何 tick ごとに書かせるか（`-PloadTestSyntheticLogTicks`。0 なら書かない）
 * @property computeProcesses [LoadPhase.COMPUTE_MULTI] で1台に動かす計算するプロセスの数（`-PloadTestComputeProcesses`。#740）。2 未満なら、その段階を飛ばす
 * @property fuelSlice マシンのスライスの差し替え（`-PloadTestFuelSlice`。#740）。`null` なら既定（`fuelPerTick` の 1/4）。
 *   [Long.MAX_VALUE] は `fuelPerTick`（1 tick に1回渡す。#47 の前と同じ受け渡しの回数）に切り詰める
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
    val massRestore: Boolean = false,
    val devicesEvery: Int = System.getProperty(DEVICES_PROPERTY)?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
    val quietProgress: Boolean = System.getProperty(QUIET_PROGRESS_PROPERTY).toBoolean(),
    val productionAutosave: Boolean = false,
    val vanillaSaveOffset: Int = 0,
    val syntheticLogTicks: Int = 0,
    val computeProcesses: Int = 0,
    val fuelSlice: Long? = null,
) {
    companion object {
        /**
         * GameTest のサーバーの進み具合のログ（20 tick ごと。メインスレッドがログのファイルに書く）を止めるシステムプロパティ
         * （`-PloadTestQuietProgress=true`。#600 の比べる回のため）
         */
        const val QUIET_PROGRESS_PROPERTY: String = "kibicomputers.loadTest.quietProgress"

        /** 他 MOD 役のデバイスを置く割合を渡すシステムプロパティ（`-PloadTestDevices`） */
        const val DEVICES_PROPERTY: String = "kibicomputers.loadTest.devices"

        /** 自動保存の段階を本番の形で測るシステムプロパティ（`-PloadTestProductionAutosave=true`。#834） */
        const val PRODUCTION_AUTOSAVE_PROPERTY: String = "kibicomputers.loadTest.productionAutosave"

        /** バニラの自動保存を一巡の始めから何 tick 後にするかのシステムプロパティ（`-PloadTestVanillaSaveOffset`） */
        const val VANILLA_SAVE_OFFSET_PROPERTY: String = "kibicomputers.loadTest.vanillaSaveOffset"

        /** 合成のログの間隔（tick）のシステムプロパティ（`-PloadTestSyntheticLogTicks`） */
        const val SYNTHETIC_LOG_TICKS_PROPERTY: String = "kibicomputers.loadTest.syntheticLogTicks"

        /** 1台に動かす計算するプロセスの数のシステムプロパティ（`-PloadTestComputeProcesses`。#740） */
        const val COMPUTE_PROCESSES_PROPERTY: String = "kibicomputers.loadTest.computeProcesses"

        /** 計算・出力させる台数のシステムプロパティ（`-PloadTestBusy`。#740。0 なら台数の 1/10） */
        const val BUSY_PROPERTY: String = "kibicomputers.loadTest.busy"

        /** スライスの差し替えのシステムプロパティ（`-PloadTestFuelSlice=tick|<燃料>`。#740。空なら既定） */
        const val FUEL_SLICE_PROPERTY: String = "kibicomputers.loadTest.fuelSlice"

        /** [FUEL_SLICE_PROPERTY] の値（`tick` は [Long.MAX_VALUE]。空なら `null`。読めなければ失敗にする） */
        fun fuelSliceProperty(): Long? {
            val v = System.getProperty(FUEL_SLICE_PROPERTY)?.trim().orEmpty()
            if (v.isEmpty()) return null
            if (v == "tick") return Long.MAX_VALUE
            return v.toLongOrNull()?.takeIf { it >= 1 } ?: throw IllegalArgumentException("$FUEL_SLICE_PROPERTY should be tick or a positive number: $v")
        }

        /** 0 以上の整数のシステムプロパティ（なければ・読めなければ 0） */
        fun intProperty(name: String): Int = System.getProperty(name)?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0
    }
}

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

/**
 * スケジューラの燃料と許可の数え口（サーバー全体の累計。#821・#740）: 巻き戻してスレッドを持たずに待った回数、スレッドを持ったまま待った回数、
 * 止まりうる関数でない関数の中で前借りした回数（`MachineScheduler` の `fuelUnwinds`・`fuelBlockingWaits`・`fuelOverdrafts`）と、
 * 許可を取った回数・スライスをその場で足した回数・譲った回数（`permitAcquisitions`・`fuelSliceRenewals`・`fuelYields`）、
 * 使った燃料の合計とそのうちの巻き戻しの費用（`fuelConsumed`・`fuelUnwindCost`）。
 * 値は1つずつ読むので、動いている間に読むと互いに少しずれる（段階の区切りで差を取るぶんには問題にならない）
 */
internal data class FuelCounts(
    val unwinds: Long,
    val blockingWaits: Long,
    val overdrafts: Long,
    val permits: Long = 0,
    val renewals: Long = 0,
    val yields: Long = 0,
    val consumed: Long = 0,
    val unwindCost: Long = 0,
) {
    operator fun minus(o: FuelCounts): FuelCounts = FuelCounts(
        unwinds - o.unwinds, blockingWaits - o.blockingWaits, overdrafts - o.overdrafts,
        permits - o.permits, renewals - o.renewals, yields - o.yields, consumed - o.consumed, unwindCost - o.unwindCost,
    )

    /** 計算の進みに使った燃料（使った燃料から巻き戻しの費用を引いたもの） */
    val computed: Long get() = consumed - unwindCost

    override fun toString(): String =
        "巻き戻し $unwinds 回 / スレッドを持って待った $blockingWaits 回 / 前借り $overdrafts 回 / 許可 $permits 回 / その場で足した $renewals 回 / " +
            "譲った $yields 回 / 使った燃料 $consumed（うち巻き戻しの費用 $unwindCost）"

    companion object {
        val ZERO = FuelCounts(0, 0, 0)

        /** 動いているサーバーのスケジューラの今の値（サーバーが動いていなければ 0） */
        fun current(): FuelCounts = ServerMachines.current?.scheduler?.let {
            FuelCounts(
                it.fuelUnwindCount, it.fuelBlockingWaitCount, it.fuelOverdraftCount,
                it.permitAcquisitionCount, it.fuelSliceRenewalCount, it.fuelYieldCount, it.fuelConsumedTotal, it.fuelUnwindCostTotal,
            )
        } ?: ZERO
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
    /** 測った tick のうち長いもの（[LoadTickProbe.LONG_TICK_MILLIS] 以上。上限まで。#600） */
    val longTicks: List<LongTick> = emptyList(),
    /** 長い tick の数（上限を超えた分も） */
    val longTickCount: Int = 0,
    /** 区間（[LoadTickProbe.Section] の順）ごとの、段階の間に区切りを通った回数。0 の区間は、区切りのイベントが届いていない */
    val sectionMarks: IntArray = IntArray(0),
    /** 測った区間（ウォームアップの後から段階の終わりまで）の、燃料切れの数え口の増分（#821）。測る区間のない段階は `null` */
    val fuel: FuelCounts? = null,
    /**
     * 測っている間に、計算させる台の中で、燃料をスレッドを持ったまま待っていた（`WAITING_FUEL`）プロセスの数の最大
     * （10 tick ごとに数えた中で。[LoadPhase.COMPUTE] だけ。ほかは -1）。巻き戻して待つプロセスは `RUNNABLE` なので数えない（#821）
     */
    val peakFuelThreadWaits: Int = -1,
    /** 測った区間の、このプロセス（JVM）の CPU 時間（ナノ秒。全スレッドの合計。#740）。測る区間のない段階・取れない JVM は -1 */
    val cpuNanos: Long = -1,
    /** 計算・出力させた台の数（[LoadPhase.COMPUTE]・[LoadPhase.COMPUTE_MULTI]・[LoadPhase.OUTPUT]・[LoadPhase.WATCHED]。ほかは 0。#740） */
    val busyMachines: Int = 0,
    /** 計算させた台の、1台の計算するプロセスの数（[LoadPhase.COMPUTE] は 1、[LoadPhase.COMPUTE_MULTI] は N。ほかは 0。#740） */
    val processesPerMachine: Int = 0,
    /**
     * [LoadPhase.COMPUTE_MULTI] の測った区間に、計算するシェル1本が燃料のスライスを受け取った回数の最小（測りはじめにあった全部のシェルの中で。
     * `Machine.fuelGrantsByProcess` の増分。#740）。0 なら、そのシェルは測った区間に一度も動いていない（同じ台の中の譲り合いが壊れて、
     * ほかが回り続けている）。[fuel] の「譲った」は、tick をそろえない回ではプロセスが1本でも増えるので、それだけでは見分けられない。ほかの段階は -1
     */
    val minProcessGrants: Long = -1,
    /** [minProcessGrants] と同じものの最大 */
    val maxProcessGrants: Long = -1,
)

/**
 * 長い tick の記録（#600）。時刻は JFR・`-Xlog` と突き合わせるため、壁時計（ミリ秒）と JVM の uptime の両方を持つ（どちらも tick の終わり）。
 * [sections] は [LoadTickProbe.Section] の順の区間ごとの時間（ナノ秒）。KiBi の `ServerTickEvent.Pre` の内訳は [completionNanos]
 * （完了の受け取り。[completions] 件）・[managerNanos]（管理の tick）・[autosaveNanos]（自動保存の tick。取り出し [captureNanos] を含む）。
 * [pendingCount]・[pendingBytes] は tick の終わりの書き込み待ち、[gcCount]・[gcMillis] はその tick の間（tick をそろえる待ちを除く。MSPT と同じ範囲）の GC の回数と時間
 */
internal class LongTick(
    val phase: LoadPhase,
    val phaseTick: Int,
    val wallMillis: Long,
    val uptimeMillis: Long,
    val tickStartNanos: Long,
    val nanos: Long,
    val sections: LongArray,
    val completionNanos: Long,
    val completions: Long,
    val managerNanos: Long,
    val autosaveNanos: Long,
    val captureNanos: Long,
    val pendingCount: Int,
    val pendingBytes: Long,
    val gcCount: Long,
    val gcMillis: Long,
) {
    /** 区間の内訳（1 ms 以上のものだけ） */
    fun sectionSummary(): String =
        LoadTickProbe.Section.entries.filter { sections[it.ordinal] >= 1_000_000 }
            .joinToString(" ") { String.format(Locale.ROOT, "%s %.1f", it.label, sections[it.ordinal] / 1e6) }

    fun describe(): String = String.format(
        Locale.ROOT,
        "phase tick %d, ended %s (uptime %d ms): %.2f ms = [%s] ms; Pre: completions %.1f ms (%d), manager %.1f ms, autosave %.1f ms (capture %.1f ms); " +
            "pending %d jobs / %d bytes; GC %d / %d ms",
        phaseTick, formatWall(wallMillis), uptimeMillis, nanos / 1e6, sectionSummary(),
        completionNanos / 1e6, completions, managerNanos / 1e6, autosaveNanos / 1e6, captureNanos / 1e6,
        pendingCount, pendingBytes, gcCount, gcMillis,
    )

    companion object {
        private val WALL = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT).withZone(ZoneId.systemDefault())

        fun formatWall(millis: Long): String = WALL.format(Instant.ofEpochMilli(millis))
    }
}

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
 * @property encode 1台あたりの符号化（保存データのバイト列にする）の時間（書き込みのスレッド。#84）
 * @property tickCapture 1 tick の取り出しの時間の合計（取り出した tick の平均と、最大。メインスレッド）
 * @property fileWriteMillis ファイルの書き込み（fsync を含む）の時間の合計（書き込みのスレッド。#84）
 * @property writer 段階の間の書き込みのスレッドの数え口（#84）
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
 * @property submitted 段階に予約した書き込みの数（[ServerMachines.Stats.windowSubmits]。#602）。
 *   段階に予約した書き込みを全部待ってから数えるので、[saves] と書けなかった数（[WriterStats.failures]）の和と同じになる
 */
internal data class SaveCost(
    val saves: Int,
    val capture: AvgMax,
    val encode: AvgMax,
    val tickCapture: AvgMax,
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
    val writer: WriterStats = WriterStats(),
    val submitted: Long = 0,
)

/**
 * 書き込みのスレッド（#84）の数え口: 書き込み待ちの仕事の数・写しの見積もりの最大、アンロード・停止で上限を待った台の数・時間・期限切れ、
 * 自動保存が上限で次の tick に回した回数、書けなかった書き込みの数
 */
internal data class WriterStats(
    val maxPendingCount: Int = 0,
    val maxPendingBytes: Long = 0,
    val capacityWaits: Long = 0,
    val capacityWaitMillis: Double = 0.0,
    val capacityTimeouts: Long = 0,
    val autosaveNoCapacity: Long = 0,
    val failures: Long = 0,
    /** 段階の前に予約して、段階の間に終わった書き込みの数（段階の数には入れない。#614） */
    val staleCompletions: Long = 0,
) {
    override fun toString(): String = String.format(
        Locale.ROOT,
        "待ちの最大 %d 件・%.1f MiB、上限で待った台 %d（%.0f ms、期限切れ %d）、自動保存で次の tick に回した %d、書けなかった %d、前の段階の完了 %d",
        maxPendingCount, maxPendingBytes / (1024.0 * 1024.0), capacityWaits, capacityWaitMillis, capacityTimeouts, autosaveNoCapacity, failures,
        staleCompletions,
    )

    companion object {
        fun current(): WriterStats = ServerMachines.Stats.let {
            WriterStats(
                it.maxPendingCount, it.maxPendingBytes, it.capacityWaits, it.capacityWaitNanos / 1e6, it.capacityTimeouts, it.autosaveNoCapacity,
                it.saveFailures, it.staleCompletions,
            )
        }
    }
}

/**
 * [LoadPhase.AUTOSAVE] の結果: 一巡の tick 数、書き込みに回した台・飛ばした台の数、1 tick で書き込みに回した回数の最大、
 * 書き込みに回すのにかかった時間の合計（ミリ秒。取り出しと予約。メインスレッド）と、tick の時間（[tick]。壁時計）、保存の手間と大きさ（[cost]）。
 * 符号化と書き込み（fsync）は書き込みのスレッドで行う（#84）ので、tick の時間には入らない
 */
internal data class AutosaveResult(
    val cycleTicks: Int,
    val written: Long,
    val skipped: Long,
    val maxTickWrites: Int,
    val writeMillis: Double,
    val tick: TickStats,
    val cost: SaveCost,
)

/**
 * [LoadPhase.UNLOAD] の結果: 取り出して書き込みに回した台と書き込みの回数（合計と 1 tick の最大）、ケースのあるチャンクのうちアンロードされなかった数とその全体、
 * tick の時間（[tick]。壁時計。書き込みは書き込みのスレッドで行う。#84）、保存の手間と大きさ（[cost]）
 */
internal data class UnloadResult(
    val captures: Long,
    val writes: Long,
    val maxTickCaptures: Long,
    val maxTickWrites: Long,
    val stillLoaded: Int,
    val chunks: Int,
    val ticks: Int,
    val tick: TickStats,
    val cost: SaveCost,
)

/**
 * 本番の形の自動保存の段階（#834）の結果。
 *
 * @property periodTicks 一巡を始めた周期（`autosavePeriodTicks`）
 * @property vanillaSaveOffset バニラの自動保存を、一巡の始めの tick から何 tick 後にしたか
 * @property serverTickAtStart 段階の始めのサーバーの tick（`getTickCount`。6000 以上なら、その前にバニラの自動保存が走っている）
 * @property cycleStartTick 周期の数え口が一巡を始めた `ServerTickEvent.Pre` を含む tick の、測った tick の中の番号（0 から。-1 なら見えなかった）。
 *   [vanillaSaveTick] と同じ数え方で、バニラの保存は `Pre` の tick の終わりに走るので、次の tick の番号になる
 *   （[vanillaSaveTick] ＝ [cycleStartTick] ＋ 1 ＋ [vanillaSaveOffset] が、狙った重なり方。外れたら段階が落ちる）
 * @property vanillaSaveTick バニラの自動保存が走った tick の、測った tick の中の番号（0 から。-1 なら走らなかった）
 * @property vanillaSaveNanos その tick の時間（MSPT と同じ数え方）
 * @property vanillaSaveSections その tick の区間の内訳（[LongTick.sectionSummary] と同じ書き方）
 * @property vanillaSavePending バニラの自動保存の中（オーバーワールドの `LevelEvent.Save`）の、KiBi の書き込み待ちの仕事の数
 * @property syntheticLog 合成のログの数え口（書かなければ `null`）
 * @property mainThreadLog 測った区間にメインスレッドが書いたログの行（「レベル ロガー名」→ 行数。多い順）
 * @property tickCount 測った tick の数
 */
internal class ProductionAutosaveResult(
    val periodTicks: Long,
    val vanillaSaveOffset: Int,
    val serverTickAtStart: Int,
    val cycleStartTick: Int,
    val vanillaSaveTick: Int,
    val vanillaSaveNanos: Long,
    val vanillaSaveSections: String,
    val vanillaSavePending: Int,
    val syntheticLog: LoadTickProbe.SyntheticLogStats?,
    val mainThreadLog: List<Pair<String, Int>>,
    val tickCount: Int,
)

/**
 * [LoadPhase.RESTORE] の結果（#582）。tick は段階の中の番号（強制読み込みを戻した tick が 0。-1 は届かなかった）。
 *
 * @property expected 戻るはずの台（アンロードの段階に入るときにマシンがあった台）
 * @property done そのうち、マシンがあって復元の順番待ちでも復元の途中でもなくなった台
 * @property kept [done] のうち、アンロードされずに元のマシンのまま残った台（ケースのブロックエンティティが変わらず、復元も始めていない）
 * @property fromSave 復元を始めた台のうち、保存した世代から戻そうとした数（[ServerMachines.Stats.restoresFromSave]）
 * @property coldStart 復元を始めた台のうち、最初から冷えた起動にした数（[ServerMachines.Stats.restoresColdStart]）
 * @property writeTimeouts 書き込みが期限までに終わらず、冷えた起動にした数
 * @property restored 保存した世代から戻せた数
 * @property fallbacks 戻せずに冷えた起動にした数
 * @property versionMismatches [fallbacks] のうち、MOD のバージョンが違ったもの
 * @property forceMillis 強制読み込みを戻すのにかかった時間（試験の呼び出しの中、1 tick。`setChunkForced` はその場でチャンクを読み込むので、
 *   ディスクからの読み込みまでを含む。MSPT には入らない）。ケースの `onLoad`（復元の順番待ちに入る。ブロックエンティティの
 *   `onLoad` は次の tick のブロックエンティティの処理で呼ばれる。#653）は含まず、測る区間の最初の tick（[firstTickMillis]）に入る
 * @property firstTickMillis 測る区間の最初の tick の時間（強制読み込みを戻した次の tick。ブロックエンティティの `onLoad`・claim が入る）
 * @property chunksTick ケース・画面・キーボードのあるチャンクが全部 FULL で読み込まれた tick
 * @property startedTick 戻るはずの台が全部、復元を始めた（か、残っていた）tick
 * @property doneTick 戻るはずの台が全部 [done] になった tick
 * @property doneSeconds 強制読み込みを戻し終えてから [doneTick] までの壁時計の秒（届かなければ段階を打ち切るまで）
 * @property timedOut [RESTORE_TIMEOUT_TICKS] で打ち切った
 * @property maxQueue 復元の順番待ちの列の長さの最大（毎 tick 数えた中で）
 * @property peakBootThreads `KiBi-boot-*` のスレッドの数の最大（毎 tick 数えた中で）。プールで次の仕事を待つスレッドも数えるので、
 *   作りの上で [maxConcurrentMachines] を超えない（上限が守られているかの検査にはならない。参考の値）
 * @property peakInFlight 復元を受け付けてから終わりの知らせが返るまで（`Machine.isRestoring`）の台数の最大（毎 tick 数えた中で）。
 *   起動用の待ち行列・`KiBi-boot-*` のスレッド・コンパイル待ち・通知の列のどこにいる台も数える。「同時に前段にいる台数」
 * @property maxConcurrentMachines 動いているサーバーの `maxConcurrentMachines`（`KiBi-boot-*` の本数の上限）
 * @property tick 測った tick の時間（強制読み込みを戻した次の tick から、全台が戻るまで）
 */
internal data class RestoreResult(
    val expected: Int,
    val done: Int,
    val kept: Int,
    val fromSave: Long,
    val coldStart: Long,
    val writeTimeouts: Long,
    val restored: Long,
    val fallbacks: Long,
    val versionMismatches: Long,
    val forceMillis: Double,
    val firstTickMillis: Double,
    val chunksTick: Int,
    val startedTick: Int,
    val doneTick: Int,
    val doneSeconds: Double,
    val timedOut: Boolean,
    val maxQueue: Int,
    val peakBootThreads: Int,
    val peakInFlight: Int,
    val maxConcurrentMachines: Int,
    val tick: TickStats,
) {
    companion object {
        /** 全台が戻るまで待つ上限（過ぎたら、そこまでの結果を書く） */
        const val RESTORE_TIMEOUT_TICKS: Int = 6_000
    }
}

/** 負荷試験の結果。 */
internal class LoadReport(
    val settings: LoadSettings,
    /** ブロックを並べるのにかかった時間（1 tick の中） */
    val placeMillis: Double,
    /** 全台の電源を入れるのにかかった時間（1 tick の中） */
    val powerOnMillis: Double,
    /**
     * 1台に計算するプロセスが1本の台（[LoadPhase.COMPUTE] で計算させた台）の電源を切るのにかかった時間（1 tick の中）。
     * [LoadPhase.COMPUTE_MULTI] がある回はその始めに、ない回は [LoadPhase.OUTPUT] の始めに切る（どちらも同じ台・同じ状態）
     */
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
    /** 本番の形の自動保存の結果（#834。測らなければ `null`） */
    val production: ProductionAutosaveResult? = null,
    /** [LoadPhase.RESTORE] の結果（#582。測らなければ `null`） */
    val restore: RestoreResult? = null,
    /** マシンの 1 tick の燃料（計算させた台の設定。#740。分からなければ 0） */
    val fuelPerTick: Long = 0,
    /** マシンのスライス（計算させた台の設定。#740。分からなければ 0） */
    val fuelSlice: Long = 0,
    /** 1台に計算するプロセスが N 本の台（[LoadPhase.COMPUTE_MULTI]）の電源を切るのにかかった時間（[LoadPhase.OUTPUT] の始め。1 tick の中。#740）。その段階がなければ -1 */
    val powerOffMultiMillis: Double = -1.0,
) {
    fun phase(phase: LoadPhase): PhaseResult = phases.first { it.phase == phase }

    /** [phase] の結果（その段階を飛ばしたなら `null`） */
    fun phaseOrNull(phase: LoadPhase): PhaseResult? = phases.firstOrNull { it.phase == phase }

    /**
     * 許可と燃料の受け渡しの表（#740）。計算の進みの比は、計算の進み（使った燃料 − 巻き戻しの費用）÷（計算・出力させた台 × `fuelPerTick` × 測った tick）。
     * 1 に近ければ、計算させた台は tick ごとの燃料を使い切っている。1 未満なら許可（CPU の枠）が足りずに使い残した（過負荷）。
     * 入力待ちの台が使う燃料と、tick をそろえない回（GameTest の tick は短い）の数え方の違いで、1 を少し超えることがある
     */
    private fun appendHandoff(sb: StringBuilder, withFuel: List<PhaseResult>) {
        sb.append(
            String.format(
                Locale.ROOT, "\n許可と燃料の受け渡し（#740。測った区間の増分。サーバー全体。スライス %d、fuelPerTick %d%s）:\n\n",
                fuelSlice, fuelPerTick, if (settings.fuelSlice != null) "。スライスを差し替えた回" else "",
            ),
        )
        sb.append("| 段階 | 計算・出力させた台 × 本数 | 測った tick | 許可を取った | その場で足した | 譲った | 使った燃料 | うち巻き戻しの費用 | 許可1回あたりの燃料 | 計算の進みの比 | CPU 時間 | 計算の進み / CPU 秒 |\n")
        sb.append("| --- | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: | --: |\n")
        for (p in withFuel) {
            val f = p.fuel!!
            val ticks = p.tick.ticks
            val ratio = if (p.busyMachines > 0 && fuelPerTick > 0 && ticks > 0) {
                String.format(Locale.ROOT, "%.3f", f.computed.toDouble() / (p.busyMachines.toDouble() * fuelPerTick * ticks))
            } else {
                "-"
            }
            val perPermit = if (f.permits > 0) String.format(Locale.ROOT, "%.0f", f.consumed.toDouble() / f.permits) else "-"
            val cpu = if (p.cpuNanos >= 0) String.format(Locale.ROOT, "%.1f s", p.cpuNanos / 1e9) else "-"
            val perCpu = if (p.cpuNanos > 0) String.format(Locale.ROOT, "%.3g", f.computed / (p.cpuNanos / 1e9)) else "-"
            val machines = if (p.busyMachines > 0) "${p.busyMachines} × ${p.processesPerMachine.coerceAtLeast(1)}" else "-"
            sb.append(
                String.format(
                    Locale.ROOT, "| %s | %s | %d | %d | %d | %d | %d | %d | %s | %s | %s | %s |\n",
                    p.phase.label, machines, ticks, f.permits, f.renewals, f.yields, f.consumed, f.unwindCost, perPermit, ratio, cpu, perCpu,
                ),
            )
        }
    }

    fun toMarkdown(): String {
        val s = settings
        val mib = 1024.0 * 1024.0
        val placed = phases.firstOrNull { it.phase == LoadPhase.PLACED }?.heapUsedBytes ?: baselineHeapBytes
        val idle = phases.firstOrNull { it.phase == LoadPhase.IDLE }?.heapUsedBytes ?: placed
        fun perMachine(from: Long, to: Long) = (to - from).toDouble() / s.count / mib
        val sb = StringBuilder()
        sb.append("- OS: ").append(s.os.label).append('\n')
        sb.append(String.format(Locale.ROOT, "- 台数: %d（計算・出力はそれぞれ %d 台）、tick を 50 ms にそろえる: %s\n", s.count, s.busy, s.paced))
        if (s.quietProgress) sb.append("- GameTest のサーバーの進み具合のログ: 止めた（#600 の比べる回）\n")
        production?.let {
            sb.append(
                String.format(
                    Locale.ROOT,
                    "- 本番の形の自動保存（#834）: 周期 %d tick、バニラの自動保存は一巡の始めから %d tick 後、合成のログ %s、段階の始めのサーバーの tick %d\n",
                    it.periodTicks, it.vanillaSaveOffset, if (s.syntheticLogTicks > 0) "${s.syntheticLogTicks} tick ごと" else "なし", it.serverTickAtStart,
                ),
            )
            if (it.vanillaSaveTick >= 0) {
                sb.append(
                    String.format(
                        Locale.ROOT, "- バニラの自動保存の tick: 測った %d tick のうち %d 番目（一巡が始まった tick は %d 番目。差 %d、狙いは %d）、%.2f ms = [%s] ms、その保存の中の KiBi の書き込み待ち %d 件\n",
                        it.tickCount, it.vanillaSaveTick, it.cycleStartTick, it.vanillaSaveTick - it.cycleStartTick, 1 + it.vanillaSaveOffset, it.vanillaSaveNanos / 1e6, it.vanillaSaveSections, it.vanillaSavePending,
                    ),
                )
            } else {
                sb.append("- バニラの自動保存の tick: 測った区間に走らなかった\n")
            }
            it.syntheticLog?.let { l ->
                sb.append(
                    String.format(
                        Locale.ROOT, "- 合成のログ: %d 行、うち %d ms 以上 %d 行、平均 %.3f ms、最大 %.2f ms\n",
                        l.lines, LoadTickProbe.STALL_MILLIS, l.slowLines, if (l.lines > 0) l.totalNanos / 1e6 / l.lines else 0.0, l.maxNanos / 1e6,
                    ),
                )
            }
            val total = it.mainThreadLog.sumOf { e -> e.second }
            sb.append("- 測った区間にメインスレッドが書いたログ: ").append(total).append(" 行")
            if (it.mainThreadLog.isNotEmpty()) {
                sb.append("（").append(it.mainThreadLog.take(10).joinToString("、") { (k, v) -> "$k $v" }).append("）")
            }
            sb.append('\n')
        }
        if (s.devicesEvery > 0) {
            sb.append(String.format(Locale.ROOT, "- 他 MOD 役のデバイス（樽）: %d 台に1台（%d 個）\n", s.devicesEvery, (s.count + s.devicesEvery - 1) / s.devicesEvery))
        }
        sb.append(String.format(Locale.ROOT, "- 並べる: %.1f ms、電源を入れる: %.1f ms、計算中の台（1台に1本）の電源を切る: %.1f ms", placeMillis, powerOnMillis, powerOffMillis))
        if (powerOffMultiMillis >= 0) {
            sb.append(String.format(Locale.ROOT, "、1台に %d 本の台の電源を切る: %.1f ms", s.computeProcesses, powerOffMultiMillis))
        }
        sb.append("（それぞれ 1 tick の中）\n")
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
                    "- 自動保存の一巡: %d tick、書き込みに回した台 %d、飛ばした台 %d、予約した書き込み %d、1 tick で回した最大 %d 回、" +
                        "回すのにかかった時間の合計 %.0f ms（取り出しと予約）、MSPT 平均 %.2f / p50 %.2f / p95 %.2f / 最大 %.2f ms\n",
                    it.cycleTicks, it.written, it.skipped, it.cost.submitted, it.maxTickWrites, it.writeMillis,
                    it.tick.avg, it.tick.p50, it.tick.p95, it.tick.max,
                ),
            )
        }
        unload?.let {
            sb.append(
                String.format(
                    Locale.ROOT,
                    "- まとめてアンロード: %d tick、取り出して書き込みに回した台 %d（書き込み %d 回、予約した書き込み %d）、1 tick の最大 %d 台・%d 回、" +
                        "アンロードされなかった %d / %d チャンク（ケースのあるもの）、MSPT 平均 %.2f / p50 %.2f / p95 %.2f / 最大 %.2f ms\n",
                    it.ticks, it.captures, it.writes, it.cost.submitted, it.maxTickCaptures, it.maxTickWrites, it.stillLoaded, it.chunks,
                    it.tick.avg, it.tick.p50, it.tick.p95, it.tick.max,
                ),
            )
        }
        restore?.let {
            fun at(t: Int) = if (t >= 0) "$t tick" else "届かず"
            sb.append(
                String.format(
                    Locale.ROOT,
                    "- 強制読み込みを戻して全台が戻る（#582）: 戻るはずの台 %d、戻った台 %d（うち、アンロードされずに残った %d）%s。" +
                        "強制読み込みを戻す %.1f ms（1 tick の中。チャンクの読み込みを含む。ケースの onLoad は次の tick）、最初の tick %.1f ms、チャンクが全部読み込まれるまで %s、全台が復元を始めるまで %s、全台が戻るまで %s（%.1f s）。" +
                        "復元を始めた台: 保存した世代から %d・最初から冷えた起動 %d（うち書き込み待ちの期限切れ %d）。" +
                        "知らせ: 戻せた %d・冷えた起動 %d（うち MOD のバージョン違い %d）。順番待ちの最大 %d 台、" +
                        "前段にいる台の最大 %d 台、KiBi-boot-* の最大 %d 本（上限 maxConcurrentMachines %d）、MSPT 平均 %.2f / p50 %.2f / p95 %.2f / 最大 %.2f ms（%d tick）\n",
                    it.expected, it.done, it.kept, if (it.timedOut) "、${RestoreResult.RESTORE_TIMEOUT_TICKS} tick で打ち切った" else "",
                    it.forceMillis, it.firstTickMillis, at(it.chunksTick), at(it.startedTick), at(it.doneTick), it.doneSeconds,
                    it.fromSave, it.coldStart, it.writeTimeouts, it.restored, it.fallbacks, it.versionMismatches, it.maxQueue,
                    it.peakInFlight, it.peakBootThreads, it.maxConcurrentMachines, it.tick.avg, it.tick.p50, it.tick.p95, it.tick.max, it.tick.ticks,
                ),
            )
        }
        val costs = listOfNotNull(autosave?.let { LoadPhase.AUTOSAVE to it.cost }, unload?.let { LoadPhase.UNLOAD to it.cost })
        if (costs.isNotEmpty()) {
            sb.append("\n保存の手間と大きさ（#553。時間はミリ秒の 平均 / 最大。1 tick の合計は、保存した tick の 平均 / 最大）:\n\n")
            sb.append("| 段階 | 書いた台 | 取り出し（1台） | 取り出し（1 tick） | 符号化（1台。書き込みのスレッド） | ファイルの書き込みの合計（書き込みのスレッド） |\n")
            sb.append("| --- | --: | --: | --: | --: | --: |\n")
            for ((p, c) in costs) {
                sb.append(
                    String.format(
                        Locale.ROOT, "| %s | %d | %s | %s | %s | %.0f ms |\n",
                        p.label, c.saves, c.capture, c.tickCapture, c.encode, c.fileWriteMillis,
                    ),
                )
            }
            sb.append("\n書き込みのスレッド（#84）:\n\n")
            for ((p, c) in costs) sb.append("- ").append(p.label).append(": ").append(c.writer).append('\n')
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
        val withLong = phases.filter { it.longTickCount > 0 }
        if (withLong.isNotEmpty()) {
            sb.append(String.format(Locale.ROOT, "\n長い tick（%d ms 以上。#600。時刻は tick の終わり。区間の内訳はミリ秒）:\n\n", LoadTickProbe.LONG_TICK_MILLIS))
            sb.append("| 段階 | 段階の中の tick | 時刻 | uptime | MSPT | 区間の内訳 | 取り出し | 書き込み待ち | GC |\n")
            sb.append("| --- | --: | --- | --: | --: | --- | --: | --: | --: |\n")
            for (p in withLong) {
                for (t in p.longTicks) {
                    sb.append(
                        String.format(
                            Locale.ROOT, "| %s | %d | %s | %d ms | %.2f | %s | %.1f ms | %d 件 | %d 回・%d ms |\n",
                            p.phase.label, t.phaseTick, LongTick.formatWall(t.wallMillis), t.uptimeMillis, t.nanos / 1e6, t.sectionSummary(),
                            t.captureNanos / 1e6, t.pendingCount, t.gcCount, t.gcMillis,
                        ),
                    )
                }
                if (p.longTickCount > p.longTicks.size) {
                    sb.append("| ").append(p.phase.label).append(" | ほか ").append(p.longTickCount - p.longTicks.size).append(" 回 | | | | | | | |\n")
                }
            }
        }
        val withFuel = phases.filter { it.fuel != null }
        if (withFuel.isNotEmpty()) {
            sb.append("\n燃料切れの数え口（#821。測った区間の増分。サーバー全体）:\n\n")
            for (p in withFuel) {
                sb.append("- ").append(p.phase.label).append(": ").append(p.fuel)
                if (p.peakFuelThreadWaits >= 0) {
                    sb.append("。計算させる台で、スレッドを持って燃料を待つプロセスの最大 ").append(p.peakFuelThreadWaits)
                }
                if (p.minProcessGrants >= 0) {
                    sb.append("。計算するシェル1本がスライスを受け取った回数: 最小 ").append(p.minProcessGrants).append("・最大 ").append(p.maxProcessGrants)
                }
                sb.append('\n')
            }
        }
        if (withFuel.isNotEmpty()) appendHandoff(sb, withFuel)
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

        /** 段階に入ったときの、この台の燃料切れの巻き戻しの回数（[LoadOs.computeProcess] の起動を台ごとに待つため） */
        var unwindsAtPhase = 0L

        /** 段階に入ってからの、この台の燃料切れの巻き戻しの回数（台のマシンがまだなければ 0） */
        fun unwindsSincePhase(): Long = (case.computer.machine?.fuelUnwindCount ?: 0L) - unwindsAtPhase

        /** [LoadPhase.COMPUTE_MULTI] で、バックグラウンドに打ち込んだ計算するコマンドの数（#740） */
        var jobsTyped = 0

        /** [LoadPhase.COMPUTE_MULTI] で、前景の計算するコマンドを打ち込んだ（#740） */
        var foregroundTyped = false

        /** [LoadPhase.COMPUTE_MULTI] の測りはじめの、計算するシェルごとのスライスを受け取った回数（pid → 回数。#740） */
        var grantsAtMeasure: Map<Int, Long> = emptyMap()
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

    /**
     * 1台に計算するプロセスを複数動かす台（[LoadPhase.COMPUTE_MULTI]。#740）。[computeSet]・[outputSet]・[tmpSet] と重ねない。
     * 台が足りなければ空（その段階を飛ばす）
     */
    private val multiSet: List<Computer> get() =
        if (computers.size >= settings.busy * 4) computers.subList(settings.busy * 3, settings.busy * 4) else emptyList()

    /** 今、計算させている台（[LoadPhase.COMPUTE] なら [computeSet]、[LoadPhase.COMPUTE_MULTI] なら [multiSet]）。次の段階で電源を切る */
    private var computingSet: List<Computer> = emptyList()

    /** [LoadPhase.COMPUTE_MULTI] を動かすか（#740）: 1台に複数のプロセスを作れる OS で、本数が 2 以上で、台が足りる */
    private val multiProcess: Boolean
        get() = settings.os.backgroundCommand != null && settings.computeProcesses >= 2 && settings.busy > 0 &&
            settings.count >= settings.busy * 4

    /** `/tmp` にファイルを書かせる台（#553）。計算・出力させる台とは別にし、最後まで動かしておく（アンロードでも書く） */
    private val tmpSet: List<Computer> get() = computers.subList(minOf(settings.busy * 2, computers.size), minOf(settings.busy * 3, computers.size))

    private var phase: LoadPhase? = null
    private var phaseTick = 0

    /** 測りはじめの基準にする、段階の中の tick。`null` なら、まだ出力（[awaitOutput]）か `/tmp` の書き込み（[awaitTmp]）を待っている */
    private var measureFrom: Int? = 0
    private var outputWaitTicks = 0
    private var phaseStartNanos = 0L

    /** 保存の数え口を段階の始めに reset した後の区間の番号（[verifyCounted] が、途中で reset されていないかを見る。#602） */
    private var phaseWindow = -1L
    private var recording = false
    private var samples = LongArray(1024)

    /** [samples] と同じ tick の、取り出しにかかった時間（[ServerMachines.Stats.captureNanos] の増分） */
    private var captureSamples = LongArray(1024)
    private var sampleCount = 0

    /** 前の呼び出しの終わりの [ServerMachines.Stats] の累計（tick ごとの増分を出すため） */
    private var prevCapture = 0L
    private var peakKernelThreads = 0
    private var peakPhaseKernelThreads = 0

    /** 段階に入ったときの燃料の数え口（[LoadOs.computeProcess] が起動しないときの失敗メッセージに、段階に入ってからの増分を出すため） */
    private var fuelAtPhase = FuelCounts.ZERO

    /** 測りはじめたときの燃料の数え口（測っていなければ `null`） */
    private var fuelAtMeasure: FuelCounts? = null

    /** [PhaseResult.peakFuelThreadWaits] */
    private var peakFuelThreadWaits = 0

    /** 測りはじめたときの、このプロセスの CPU 時間（ナノ秒。#740。測っていない・取れなければ -1） */
    private var cpuAtMeasure = -1L

    /** 段階の終わりに読んだ燃料の数え口と JVM の CPU 時間（[next]。検証や GC の前）。[finish] が測りはじめ（[fuelAtMeasure]・[cpuAtMeasure]）との差を取る */
    private var endFuel: FuelCounts = FuelCounts.ZERO
    private var endCpu = -1L
    private val osBean = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean

    /** [LoadPhase.COMPUTE_MULTI] の測った区間の、シェル1本あたりのスライスの回数の最小と最大（[next] で読む。#740。ほかの段階は -1） */
    private var endMinProcessGrants = -1L
    private var endMaxProcessGrants = -1L

    /** 計算させた台の燃料の設定（#740。結果に書く） */
    private var reportFuelPerTick = 0L
    private var reportFuelSlice = 0L

    /** 段階に入ったときにあったカーネルのスレッドの ID。これらのうち、プールで待っているワーカーは [peakPhaseKernelThreads] に数えない */
    private var baselineKernelThreads: Set<Long> = emptySet()
    private var sentBytes = 0L
    private var countSent = false

    private var placeMillis = 0.0
    private var powerOnMillis = 0.0
    private var powerOffMillis = 0.0

    /** [LoadReport.powerOffMultiMillis] */
    private var powerOffMultiMillis = -1.0
    private var baselineHeap = 0L
    private var watcher: ServerPlayer? = null
    private var finished = false
    private var autosaveResult: AutosaveResult? = null
    private var unloadResult: UnloadResult? = null
    private var unloadCaseChunks = 0
    private var unloadRemaining = 0

    /** [LoadPhase.AUTOSAVE]・[LoadPhase.UNLOAD] の測る区間の tick の時間（終えたら。`null` ならまだ測っている。#614） */
    private var measuredTick: TickStats? = null

    /** 測る区間を終えたときの段階の中の tick（この後は書き込みの終わりを待つ） */
    private var measuredTicks = 0

    /** 段階に予約した書き込みの終わりを待ち始めたとき（`System.nanoTime` の値。失敗の文言に出すだけ。[stopMeasuring] で決める） */
    private var saveWaitStartNanos = 0L

    /** 段階に予約した書き込みの終わりを待った tick の数（[awaitPhaseSaves]） */
    private var saveWaitTicks = 0

    /** 終わった書き込みが増えないまま待った tick の数（[awaitPhaseSaves]） */
    private var saveWaitIdleTicks = 0

    /**
     * 前の tick までに受け取った、段階に予約した書き込みの完了の数（[ServerMachines.Stats.windowCompletions]。増えたら [saveWaitIdleTicks] を数え直す）。
     * 残りの数（予約 − 完了）では見ない。待っている間に予約が増えると（UNLOAD の段階が打ち切られ、残りのチャンクが待ちの間に
     * アンロードされたとき）、完了が進んでいても残りが減らないことがあるため
     */
    private var saveWaitLastCompletions = 0L

    /**
     * 前の tick までに見た、書き込みの列から抜けた仕事の累計（[pro.ikili.kibicomputers.core.persist.MachineSaveStore.finishedCount]。#852）。
     * 段階の保存の前に掃除などの仕事が並んでいても、列が進んでいれば [saveWaitIdleTicks] を数え直す
     */
    private var saveWaitLastFinished = 0L

    /** 待ち始めてから、段階に予約した書き込みの完了を最後に受け取ったとき（`System.nanoTime`。まだなら待ち始め。#852） */
    private var saveWaitLastCompletionNanos = 0L

    /** 待ち始めてから、段階に予約した書き込みの完了を最後に受け取った待ちの tick（[saveWaitTicks] の値。#852） */
    private var saveWaitLastCompletionTick = 0
    private val unforcedOthers = ArrayList<ChunkPos>()
    private var unloadPrevCaptures = 0L
    private var savedSpawnChunkRadius: Int? = null
    private var savedSaveConfig: MachineSaveConfig? = null
    private var savedAutosaveEnabled: Boolean? = null
    private var unloadPrevWrites = 0L
    private var unloadMaxTickCaptures = 0L
    private var unloadMaxTickWrites = 0L

    /**
     * [LoadPhase.RESTORE]（#582）: 戻るはずの台の番号（アンロードの段階に入るときにマシンがあった台）と、そのときの台（古いブロックエンティティ）・
     * 復元を始めた回数。アンロードで外れたブロックエンティティは、読み込み直すと別のものになるので、位置から引き直す（[refresh]）
     */
    private var restoreTargets: IntArray = IntArray(0)
    private var restoreOld: List<Computer> = emptyList()
    private var restoreBaseline: IntArray = IntArray(0)
    private var restoreDone: BooleanArray = BooleanArray(0)
    private var restoreStarted: BooleanArray = BooleanArray(0)
    private var restoreKept: BooleanArray = BooleanArray(0)

    /** 戻るはずの台のケース・画面・キーボードのあるチャンク */
    private var restoreChunks: List<ChunkPos> = emptyList()
    private var restoreChunksTick = -1
    private var restoreStartedTick = -1
    private var restoreDoneTick = -1
    private var restoreDoneNanos = 0L
    private var restoreMaxQueue = 0
    private var restoreForceMillis = 0.0
    private var restorePeakBootThreads = 0
    private var restorePeakInFlight = 0

    /** [LoadPhase.RESTORE] の前に、サーバーが遅れを取り戻して tick の予定に追いついたか待つ（[settlePace]）ために数える */
    private var paceRun = 0
    private var paceSettleTicks = 0
    private var paceSettled = false
    private var restoreResult: RestoreResult? = null

    private var prevCallback = 0L
    private var prevHarness = 0L
    private var prevSleep = 0L

    /** 長い tick の調べ（#600）。区間の時間は毎 tick、止まりの見張りは tick をそろえるとき（手元の測定）だけ */
    private val probe = LoadTickProbe(level.server.runningThread, sampleStalls = settings.paced)
    private val sectionNanos = LongArray(LoadTickProbe.Section.entries.size)
    private val gcBeans = ManagementFactory.getGarbageCollectorMXBeans()
    private val runtime = ManagementFactory.getRuntimeMXBean()

    /** 今の tick の始め（[LoadTickProbe.harnessEnd] の時刻）。止まりのスタックと突き合わせる */
    private var tickStart = 0L

    /** 前の呼び出しの終わりの数え口（tick ごとの増分を出すため。[prevCapture] と同じく step() の後で覚える） */
    private var prevCompletionTick = 0L
    private var prevCompletions = 0L
    private var prevManagerTick = 0L
    private var prevAutosaveTick = 0L
    private var prevGcCount = 0L
    private var prevGcMillis = 0L

    /** 今の段階の長い tick（[LoadTickProbe.LONG_TICK_MILLIS] 以上。[MAX_LONG_TICKS] まで）と、その数（上限を超えた分も） */
    private val longTicks = ArrayList<LongTick>()
    private var longTickCount = 0

    /** 本番の形の自動保存（#834）: バニラの `ticksUntilAutosave`（反射で読み書きする。試験のコードの中だけ） */
    private val vanillaAutosave = if (settings.productionAutosave) VanillaAutosave(level.server) else null

    /** [LoadPhase.RESTORE] の強制読み込みの時間だけ、サーバーの tick の予定をずらす（[TickSchedule]。#582） */
    private val tickSchedule = if (settings.paced && settings.massRestore) TickSchedule(level.server) else null

    /** この呼び出しで tick の予定をずらした時間（[onTick] の待ちで、試験の呼び出しの時間から引く） */
    private var scheduleShiftNanos = 0L
    private var prevVanillaTicks = -1
    private var vanillaSaveTick = -1

    /** 本番の形の自動保存（#834）: 周期の数え口が一巡を始めた tick の番号（[vanillaSaveTick] と同じ数え方。-1 は見ていない）と、前の呼び出しでの始めた回数 */
    private var cycleStartTick = -1
    private var prevCycleStarts = 0L

    /** 本番の形の自動保存（#834）: 仕掛けたときの、周期の数え口が一巡を始めた回数。これより増えたら、一巡が始まった */
    private var cycleStartsAtArm = 0L
    private var vanillaSaveNanos = 0L
    private var vanillaSaveSections = ""
    private var vanillaSavePending = 0
    private var serverTickAtStart = 0
    private var productionPeriod = 0L

    /** 本番の形の自動保存（#834）で、一巡が始まった後に周期を止めた */
    private var productionPeriodStopped = false
    private var productionResult: ProductionAutosaveResult? = null
    private val logCounter = if (settings.productionAutosave) MainThreadLogCounter(level.server.runningThread.name) else null

    /** [LoadSettings.quietProgress] のときに止めた、進み具合のロガーの元のレベル */
    private var savedProgressLevel: Log4jLevel? = null

    /** 段階の間に、区間ごとの区切りを通った回数（今の tick の分は [tickMarks] に取り、測っている間だけ足す） */
    private val sectionMarks = IntArray(LoadTickProbe.Section.entries.size)
    private val tickMarks = IntArray(LoadTickProbe.Section.entries.size)

    fun start() {
        // 結果はコンフィグの値に左右される（runClient などと同じ run/config を使う）。既定と違う値があれば、結果と一緒に残す
        val nonDefault = KiBiConfig.entries(ConfigSide.SERVER).filter { KiBiConfigs.server[it] != it.default }
        if (nonDefault.isNotEmpty()) {
            LOGGER.warn(
                "The load test runs with non-default values in {}: {}",
                ConfigSide.SERVER.fileName,
                nonDefault.joinToString { "${it.path}=${KiBiConfigs.server[it]}" },
            )
        }
        // MOD の自動保存の周期は止める（ほかの段階に混ざらないように）。自動保存の段階は周期を待たずに始める
        savedSaveConfig = ServerMachines.saveConfig
        ServerMachines.saveConfig = ServerMachines.saveConfig.copy(autosavePeriodTicks = Long.MAX_VALUE)
        // GameTest のサーバーでは自動保存が切られているので、試験の間だけ有効にする
        savedAutosaveEnabled = ServerMachines.autosaveEnabled
        ServerMachines.autosaveEnabled = true
        // 保存1回ごとの記録を残す（保存の手間と大きさを測る。#553）
        ServerMachines.Stats.recordSaves = true
        if (settings.quietProgress) {
            savedProgressLevel = Log4jManager.getLogger(PROGRESS_LOGGER).level
            Configurator.setLevel(PROGRESS_LOGGER, Log4jLevel.WARN)
        }
        // スライスを差し替えて比べる回（#740）。電源を入れる前に立て、cleanUp で戻す
        settings.fuelSlice?.let { slice -> CaseBlockEntity.configOverride = java.util.function.UnaryOperator { it.withFuelSlice(slice) } }
        probe.start()
        logCounter?.start()
        helper.onEachTick(::onTick)
    }

    private fun onTick() {
        if (finished) return
        // 区間の終わりと tick の時間の終わりを同じ時刻にする。始めも [LoadTickProbe.harnessEnd] の時刻を共有するので（下の prevSleep）、
        // work は前の tickStart からの差そのものになり、区間の合計（sectionNanos）と作りの上でちょうど同じになる
        val now = probe.harnessStart()
        var work = 0L
        probe.takeSections(sectionNanos, tickMarks)
        if (prevCallback != 0L) {
            work = (now - prevCallback - prevHarness - prevSleep).coerceAtLeast(0)
            // 自動保存（ServerTickEvent.Pre）・アンロード（チャンクのアンロード、ServerTickEvent.Post）の保存は、前の呼び出しからの間に入っている
            val s = ServerMachines.Stats
            vanillaAutosave?.let { observeVanillaSave(it, work) }
            if (recording) {
                record(work, s.captureNanos - prevCapture)
                for (i in tickMarks.indices) sectionMarks[i] += tickMarks[i]
                if (work >= LoadTickProbe.LONG_TICK_MILLIS * 1_000_000) recordLongTick(work, s.captureNanos - prevCapture)
            }
        }
        prevCallback = now
        try {
            step()
        } catch (t: Throwable) {
            cleanUp()
            throw t
        }
        // step() が Stats.reset() したときも、次の増分がこの時点からになるように、step() の後で覚える
        prevCapture = ServerMachines.Stats.captureNanos
        rememberCounters()
        val end = System.nanoTime()
        prevHarness = end - now
        prevSleep = 0
        if (settings.paced && !finished) {
            // 実際のサーバーと同じく、サーバーの次の tick の予定（getNextTickTime）までしか待たない。遅れていれば待たずに追いつく。
            // 1 tick の長さだけで待つと、重い tick（アンロードの書き込みなど）で遅れた分が戻らず、tick の残り時間（haveTime）が
            // ずっとなくなる。するとチャンクのアンロードの列が 2,000 件を超えるまで流れず、アンロードが進まない（#540）
            // 予定をずらした分（RESTORE の強制読み込み）は、読み込みのスレッドで進んだものとして試験の呼び出しの時間から引く
            val wait = minOf(TARGET_NANOS - work - (prevHarness - scheduleShiftNanos), level.server.nextTickTime - System.nanoTime())
            scheduleShiftNanos = 0
            // 予定より先にいる（待てた）tick が続けば、遅れを取り戻している（settlePace）
            paceRun = if (wait > 0) paceRun + 1 else 0
            if (wait > 0) {
                // parkNanos は、ほかのスレッドの unpark（サーバーのスレッドへ仕事を積むたびに起こす。復元の完了の知らせなど）で早く返る。
                // 1 回だけだと、仕事が続く間は待ちが短くなり、tick が 50 ms より詰まる。予定の時刻まで待ち直す
                val deadline = System.nanoTime() + wait
                var left = wait
                while (left > 0) {
                    LockSupport.parkNanos(left)
                    left = deadline - System.nanoTime()
                }
            }
        }
        if (!finished) {
            rememberGcCounters()
            tickStart = probe.harnessEnd()
            // 待ちと GC の数え口を読む時間も、試験の呼び出しの間として引く（区間は harnessEnd から数えるので、ここを引かないと
            // 区間の合計との差になる。GC の数え口を読む間にメインスレッドが止まると、数 ms ずれる）
            prevSleep = tickStart - end
        }
    }

    /**
     * バニラの自動保存が、前の呼び出しからの間（試験の後の区間。`tickServer` の `saveEverything`）に走ったかを見る（#834）。
     * 走ると `ticksUntilAutosave` が次の間隔に戻る（増える）。走った tick の時間（[work]）と区間の内訳を覚える
     */
    private fun observeVanillaSave(vanilla: VanillaAutosave, work: Long) {
        val ticks = vanilla.ticksUntilAutosave
        // 一巡を始めるのは tick の始め（Pre）なので、始まったなら、この呼び出しの tick の中（直前の区間）
        val starts = ServerMachines.Stats.autosaveCycleStarts
        if (phase == LoadPhase.AUTOSAVE && recording && cycleStartTick < 0 && starts > prevCycleStarts) cycleStartTick = sampleCount
        prevCycleStarts = starts
        if (phase == LoadPhase.AUTOSAVE && recording && prevVanillaTicks >= 0 && ticks > prevVanillaTicks && vanillaSaveTick < 0) {
            vanillaSaveTick = sampleCount
            vanillaSaveNanos = work
            vanillaSaveSections = LoadTickProbe.Section.entries.filter { sectionNanos[it.ordinal] >= 1_000_000 }
                .joinToString(" ") { String.format(Locale.ROOT, "%s %.1f", it.label, sectionNanos[it.ordinal] / 1e6) }
            vanillaSavePending = probe.pendingAtLevelSave
        }
        prevVanillaTicks = ticks
    }

    private fun gcCount(): Long = gcBeans.sumOf { it.collectionCount.coerceAtLeast(0) }

    private fun gcMillis(): Long = gcBeans.sumOf { it.collectionTime.coerceAtLeast(0) }

    private fun rememberCounters() {
        val s = ServerMachines.Stats
        prevCompletionTick = s.completionTickNanos
        prevCompletions = s.completionsReceived
        prevManagerTick = s.managerTickNanos
        prevAutosaveTick = s.autosaveTickNanos
    }

    /** GC の数え口は、tick をそろえる待ちの後（tick の始め）で覚える。待ちの間の GC を、次の tick の GC に混ぜないため */
    private fun rememberGcCounters() {
        prevGcCount = gcCount()
        prevGcMillis = gcMillis()
    }

    /** 長い tick を1つ覚える（#600）。ログには段階の終わりにまとめて書く（tick の中でファイルに書かない） */
    private fun recordLongTick(nanos: Long, captureNanos: Long) {
        val p = phase ?: return
        longTickCount++
        if (longTicks.size >= MAX_LONG_TICKS) return
        val s = ServerMachines.Stats
        val store = WorldMachineSaves.current
        longTicks += LongTick(
            phase = p,
            phaseTick = phaseTick,
            wallMillis = System.currentTimeMillis(),
            uptimeMillis = runtime.uptime,
            tickStartNanos = tickStart,
            nanos = nanos,
            sections = sectionNanos.copyOf(),
            completionNanos = s.completionTickNanos - prevCompletionTick,
            completions = s.completionsReceived - prevCompletions,
            managerNanos = s.managerTickNanos - prevManagerTick,
            autosaveNanos = s.autosaveTickNanos - prevAutosaveTick,
            captureNanos = captureNanos,
            pendingCount = store?.pendingCount ?: -1,
            pendingBytes = store?.pendingBytes ?: -1,
            gcCount = gcCount() - prevGcCount,
            gcMillis = gcMillis() - prevGcMillis,
        )
    }

    /**
     * 段階の長い tick と、その間に取ったスタックをログに書く（#600。段階の終わりで、測る区間の外）。
     * スタックは止まりごとに、続けて同じものをまとめる
     */
    private fun logLongTicks(p: LoadPhase) {
        val (stalls, dropped) = probe.takeSamples()
        if (longTickCount == 0 && stalls.isEmpty()) return
        val sb = StringBuilder()
        sb.append(String.format(Locale.ROOT, "Long ticks in the %s phase: %d at or over %d ms", p.name, longTickCount, LoadTickProbe.LONG_TICK_MILLIS))
        if (longTickCount > longTicks.size) sb.append(" (first ").append(longTicks.size).append(" listed)")
        sb.append('\n')
        for (t in longTicks) sb.append("  ").append(t.describe()).append('\n')
        for ((start, samples) in stalls.groupBy { it.tickStartNanos }) {
            val tick = longTicks.firstOrNull { it.tickStartNanos == start }
            sb.append(
                String.format(
                    Locale.ROOT, "  stall at %s (uptime %d ms)%s: %d samples\n",
                    LongTick.formatWall(samples.first().wallMillis), samples.first().uptimeMillis,
                    tick?.let { " in phase tick ${it.phaseTick}" } ?: " (tick not recorded)", samples.size,
                ),
            )
            var i = 0
            while (i < samples.size) {
                val first = samples[i]
                var j = i + 1
                while (
                    j < samples.size && samples[j].serverStack == first.serverStack && samples[j].writerStack == first.writerStack &&
                    samples[j].ioWorkers == first.ioWorkers
                ) {
                    j++
                }
                val last = samples[j - 1]
                sb.append(
                    String.format(
                        Locale.ROOT, "    +%.0f..%.0f ms (x%d), after %s, server %s, writer %s\n",
                        first.elapsedNanos / 1e6, last.elapsedNanos / 1e6, j - i, first.lastSection?.name ?: "-", first.serverState, first.writerState ?: "-",
                    ),
                )
                for (f in first.serverStack) sb.append("      at ").append(f).append('\n')
                if (first.writerStack.isNotEmpty()) {
                    sb.append("      -- writer\n")
                    for (f in first.writerStack) sb.append("      at ").append(f).append('\n')
                }
                for ((name, stack) in first.ioWorkers) {
                    sb.append("      -- ").append(name).append('\n')
                    for (f in stack) sb.append("      at ").append(f).append('\n')
                }
                i = j
            }
        }
        if (dropped > 0) sb.append("  (").append(dropped).append(" stall samples dropped)\n")
        LOGGER.info("{}", sb)
    }

    private fun record(nanos: Long, captureNanos: Long) {
        if (sampleCount == samples.size) {
            samples = samples.copyOf(samples.size * 2)
            captureSamples = captureSamples.copyOf(samples.size)
        }
        samples[sampleCount] = nanos
        captureSamples[sampleCount] = captureNanos
        sampleCount++
    }

    /** 測った tick の時間（壁時計。#84 から書き込みはメインスレッドの外） */
    private fun tickStats(): TickStats = TickStats.of(samples.copyOf(sampleCount))

    /**
     * 段階の間の保存の手間と大きさ（[ServerMachines.Stats.saves] と、tick ごとの増分から）。段階に予約した書き込みがすべて終わってから
     * （[awaitPhaseSaves]）呼ぶ。段階の前に予約した書き込みは数えない（#614）
     */
    private fun saveCost(): SaveCost {
        val saves = ServerMachines.Stats.saves.toList()
        val savedTicks = (0 until sampleCount).filter { captureSamples[it] > 0 }
        fun perTick(f: (Int) -> Long) = AvgMax.ofNanos(LongArray(savedTicks.size) { f(savedTicks[it]) })
        val sizes = saves.map { it.fileBytes }.sorted()
        val tmpPositions = tmpSet.mapTo(HashSet()) { it.case.blockPos }
        val (writers, others) = saves.partition { it.pos in tmpPositions }
        return SaveCost(
            saves = saves.size,
            capture = AvgMax.ofNanos(saves.map { it.captureNanos }.toLongArray()),
            encode = AvgMax.ofNanos(saves.map { it.encodeNanos }.toLongArray()),
            tickCapture = perTick { captureSamples[it] },
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
            writer = WriterStats.current(),
            submitted = ServerMachines.Stats.windowSubmits,
        )
    }

    private fun step() {
        val p = phase
        if (p == null) {
            forceChunks()
            baselineHeap = heapAfterGc()
            enter(sequence.first())
        } else {
            if (p == LoadPhase.RESTORE && phaseTick == 0 && settings.paced && !paceSettled && !settlePace()) return
            if (phaseTick == 0) setUp(p)
            when (p) {
                LoadPhase.BOOT -> boot()
                LoadPhase.AUTOSAVE -> if (settings.productionAutosave) productionAutosave() else autosave()
                LoadPhase.UNLOAD -> unload()
                LoadPhase.RESTORE -> restore()
                else -> steady(p)
            }
        }
        phaseTick++
    }

    /**
     * [LoadPhase.RESTORE] を始める前に、サーバーが tick の予定（`nextTickTime`）に追いついているのを待つ。直前のアンロードの段階は、
     * 長い tick と書き込みの終わりの待ち（[awaitPhaseSaves]）で予定より遅れて終わる。遅れたまま始めると、待ちが効かない（[onTick] の待ちは
     * 予定までしか待たない）ので tick が詰めて回り、tick の数や秒数が 20 TPS のサーバーの値にならない。
     * 試験の呼び出しで何もせず（測らない）、予定より先にいる tick が [PACE_SETTLE_TICKS] 続いたら始める。
     * [PACE_SETTLE_TIMEOUT_TICKS] 待っても追いつかなければ WARN を出して始める
     *
     * @return 始めてよければ `true`（この呼び出しで段階の準備に進む）
     */
    private fun settlePace(): Boolean {
        if (paceRun >= PACE_SETTLE_TICKS) {
            LOGGER.info("The server caught up with its tick schedule after {} ticks; starting the {} phase", paceSettleTicks, phase)
        } else if (++paceSettleTicks < PACE_SETTLE_TIMEOUT_TICKS) {
            return false
        } else {
            LOGGER.warn(
                "The server did not catch up with its tick schedule within {} ticks; the {} phase runs behind schedule (its tick counts and seconds are not 20 TPS values)",
                PACE_SETTLE_TIMEOUT_TICKS,
                phase,
            )
        }
        paceSettled = true
        // 待った時間は段階の秒に入れない（doneSeconds・finish の壁時計は、setUp が強制読み込みを戻し終えた時刻から数え直す）
        phaseStartNanos = System.nanoTime()
        return true
    }

    /** 段階の順番。計算・出力させる台がなければ、その段階は飛ばす */
    private val sequence: List<LoadPhase> = buildList {
        add(LoadPhase.EMPTY)
        add(LoadPhase.PLACED)
        add(LoadPhase.BOOT)
        add(LoadPhase.IDLE)
        add(LoadPhase.AUTOSAVE)
        // 本番の形の自動保存（#834）は、その段階で終える（ほかの段階に、次の周期やバニラの自動保存が混ざらないように）
        if (!settings.productionAutosave) {
            if (settings.busy > 0) add(LoadPhase.COMPUTE)
            if (multiProcess) add(LoadPhase.COMPUTE_MULTI)
            if (settings.busy > 0 && settings.count > settings.busy) {
                add(LoadPhase.OUTPUT)
                add(LoadPhase.WATCHED)
            }
            if (settings.massUnload) {
                add(LoadPhase.UNLOAD)
                if (settings.massRestore) add(LoadPhase.RESTORE)
            }
        }
    }

    private fun next(p: LoadPhase) {
        // CPU 時間と燃料は、検証（全台の describeProcesses）・スレッドの数え・強制 GC より前に読む（その分の CPU 時間を、測った区間に入れない）
        endFuel = FuelCounts.current()
        endCpu = osBean?.processCpuTime ?: -1
        readProcessGrants(p)
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
        val tick = measuredTick ?: if (ServerMachines.isAutosaveCycleDone) {
            stopMeasuring()
        } else {
            if (phaseTick >= AUTOSAVE_TIMEOUT_TICKS) fail("the autosave cycle did not finish within $AUTOSAVE_TIMEOUT_TICKS ticks")
            return
        }
        if (!awaitPhaseSaves()) return
        val s = ServerMachines.Stats
        autosaveResult = AutosaveResult(
            cycleTicks = s.autosaveCycleTicks.lastOrNull() ?: measuredTicks,
            written = s.autosaveWritten,
            skipped = s.autosaveSkipped,
            maxTickWrites = s.autosaveMaxTickWrites,
            writeMillis = s.autosaveWriteNanos / 1e6,
            tick = tick,
            cost = saveCost(),
        )
        next(LoadPhase.AUTOSAVE)
    }

    /**
     * 本番の形の自動保存（#834）: 一巡が終わり、段階に予約した書き込みが全部終わって受け取るまでと、バニラの自動保存が走るまで、毎 tick 見る。
     * メインスレッドを止めて書き込みのスレッドを待たない（待つと、ログの書き込みやバニラの保存と重なる時間そのものが変わる）。
     * 書き込みの完了は、毎 tick の始め（`ServerTickEvent.Pre`）の受け取りに任せる
     */
    private fun productionAutosave() {
        if (phaseTick == 0) return
        val s = ServerMachines.Stats
        if (measuredTick == null) {
            // 一巡の始まりは、cycleStartTick と同じく周期の数え口が一巡を始めた回数で見る（一巡の1 tick 目は止めるだけで、書き込みの予約はまだない）
            val started = s.autosaveCycleStarts > cycleStartsAtArm
            // 一巡が本物の経路で始まったら、その tick のうちに周期を止める（周期は次の一巡を始める tick を決めるだけで、一巡の進め方には使わない）。
            // 一巡の残り・書き込みの残り・バニラの保存を待つ間に 2 つ目の一巡が始まらないので、止めた後の上限は PRODUCTION_TIMEOUT_TICKS。
            // 止める前（一巡が始まる前）は、2 つ目の一巡が来る tick の前に打ち切る（始まると、数え口が 2 周分たまって別の理由で落ちる）
            if (started && !productionPeriodStopped) {
                ServerMachines.saveConfig = ServerMachines.saveConfig.copy(autosavePeriodTicks = Long.MAX_VALUE)
                productionPeriodStopped = true
            }
            val done = started && ServerMachines.isAutosaveCycleDone && s.windowOutstanding <= 0 && vanillaSaveTick >= 0
            if (!done) {
                val limit = if (productionPeriodStopped) {
                    PRODUCTION_TIMEOUT_TICKS
                } else {
                    minOf(PRODUCTION_TIMEOUT_TICKS.toLong(), productionPeriod + PRODUCTION_LEAD_TICKS - 1).toInt()
                }
                if (phaseTick >= limit) {
                    fail(
                        "the production-shaped autosave did not finish within $limit ticks " +
                            "(cycle done ${ServerMachines.isAutosaveCycleDone}, outstanding ${s.windowOutstanding}, vanilla save tick $vanillaSaveTick)",
                    )
                }
                return
            }
            stopMeasuring()
        }
        if (!awaitPhaseSaves()) return
        // バニラの保存が、狙ったとおり「一巡の始めの tick（+ offset）」の終わりに走ったか。数え口の進め方が変わると、別の形を黙って測ることになる
        val expectedGap = 1 + settings.vanillaSaveOffset
        if (cycleStartTick < 0 || vanillaSaveTick - cycleStartTick != expectedGap) {
            fail(
                "the vanilla autosave did not overlap the cycle start as planned: cycle start tick $cycleStartTick, " +
                    "vanilla save tick $vanillaSaveTick (expected the difference to be $expectedGap)",
            )
        }
        probe.syntheticLogEveryTicks = 0
        val tick = measuredTick!!
        autosaveResult = AutosaveResult(
            cycleTicks = s.autosaveCycleTicks.lastOrNull() ?: measuredTicks,
            written = s.autosaveWritten,
            skipped = s.autosaveSkipped,
            maxTickWrites = s.autosaveMaxTickWrites,
            writeMillis = s.autosaveWriteNanos / 1e6,
            tick = tick,
            cost = saveCost(),
        )
        productionResult = ProductionAutosaveResult(
            periodTicks = productionPeriod,
            vanillaSaveOffset = settings.vanillaSaveOffset,
            serverTickAtStart = serverTickAtStart,
            cycleStartTick = cycleStartTick,
            vanillaSaveTick = vanillaSaveTick,
            vanillaSaveNanos = vanillaSaveNanos,
            vanillaSaveSections = vanillaSaveSections,
            vanillaSavePending = vanillaSavePending,
            syntheticLog = if (settings.syntheticLogTicks > 0) probe.takeSyntheticLog() else null,
            mainThreadLog = logCounter?.end() ?: emptyList(),
            tickCount = tick.ticks,
        )
        // 次の周期を止める（試験はこの段階で終わるが、片付けまでに周期が来ないように）
        ServerMachines.saveConfig = ServerMachines.saveConfig.copy(autosavePeriodTicks = Long.MAX_VALUE)
        next(LoadPhase.AUTOSAVE)
    }

    /**
     * 段階の測る区間を終える: ここまでの tick の時間を取り、この後の tick（書き込みの終わりを待つ間）は測らない（#614）
     */
    private fun stopMeasuring(): TickStats {
        val tick = tickStats()
        measuredTick = tick
        measuredTicks = phaseTick
        saveWaitStartNanos = System.nanoTime()
        saveWaitTicks = 0
        saveWaitIdleTicks = 0
        saveWaitLastCompletions = ServerMachines.Stats.windowCompletions
        saveWaitLastFinished = WorldMachineSaves.current?.finishedCount ?: 0L
        saveWaitLastCompletionNanos = saveWaitStartNanos
        saveWaitLastCompletionTick = 0
        recording = false
        return tick
    }

    /**
     * 段階に予約した書き込み（[ServerMachines.Stats.windowOutstanding]）がすべて終わったか（#614）。終わっていなければ、書き込みのスレッドが
     * 空になるのを 1 tick あたり [SAVE_WAIT_STEP_MILLIS] まで待って、終わった書き込みを受け取る。tick をまたいで待つので、
     * 1回でメインスレッドを長く止めず、急いで回す試験（`paced` でない）でも tick の数が増えすぎない。待つ tick は測らない（[stopMeasuring]）。
     * 上限は tick で数える（#854。重いマシンで書き込みのスレッドが I/O で遅れても、進んでいるうちは落とさない）:
     * 段階の書き込みの完了も、列の仕事（掃除なども。#852）の終わりも増えない tick が [SAVE_WAIT_IDLE_TICKS] 続くか、
     * 待つ tick が全部で [SAVE_WAIT_TIMEOUT_TICKS] に達したら失敗する。
     * 1 tick に [SAVE_WAIT_STEP_MILLIS] まで待つので、どちらも壁時計では元の上限（120 秒）より短くならない。
     * 失敗の文言には、遅れの形（一様に遅い・1 件が止まった・取りこぼし）を見分ける材料を出す（[saveWaitDiagnostics]。#852）
     */
    private fun awaitPhaseSaves(): Boolean {
        val s = ServerMachines.Stats
        val store = WorldMachineSaves.current
        if (s.windowOutstanding > 0 && store != null) {
            try {
                store.flush(SAVE_WAIT_STEP_MILLIS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        ServerMachines.processCompletions()
        val outstanding = s.windowOutstanding
        if (outstanding <= 0) return true
        saveWaitTicks++
        val finished = store?.finishedCount ?: saveWaitLastFinished
        val completed = s.windowCompletions > saveWaitLastCompletions
        if (completed) {
            saveWaitLastCompletionNanos = System.nanoTime()
            saveWaitLastCompletionTick = saveWaitTicks
        }
        if (completed || finished > saveWaitLastFinished) {
            saveWaitLastCompletions = s.windowCompletions
            saveWaitLastFinished = finished
            saveWaitIdleTicks = 0
        } else {
            saveWaitIdleTicks++
        }
        val reason = when {
            saveWaitIdleTicks >= SAVE_WAIT_IDLE_TICKS -> "made no progress for $SAVE_WAIT_IDLE_TICKS ticks"
            saveWaitTicks >= SAVE_WAIT_TIMEOUT_TICKS -> "did not finish within $SAVE_WAIT_TIMEOUT_TICKS ticks"
            else -> return false
        }
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - saveWaitStartNanos)
        fail(
            "$outstanding of ${s.windowSubmits} saves queued in the $phase phase $reason " +
                "(waited $saveWaitTicks ticks, $elapsed ms; pending ${store?.pendingCount} jobs, ${store?.pendingBytes} bytes; ${WriterStats.current()}; " +
                "${saveWaitDiagnostics()})",
        )
    }

    /**
     * 書き込みの待ちで失敗したときの材料（#852。失敗したときだけ作る）: 最後に段階の完了を受け取ってからの tick・ms、残っている予約
     * （古い順に [SAVE_WAIT_REPORT_OUTSTANDING] 件。予約してからの tick・ms）、ここまでの完了の符号化・書き込みの時間（最大・合計）、
     * 列から抜けた仕事の累計、書き込みのスレッドの状態とスタックの先頭。
     * 見分け方: 書き込みの時間の最大が大きく、スレッドが `force`・`move` などの中なら 1 件が止まった。最大が合計 ÷ 件数に近く、
     * 全体が長いなら一様に遅い。スレッドが列を待って（`WAITING`）いるのに残りがあるなら取りこぼし
     */
    private fun saveWaitDiagnostics(): String {
        val now = System.nanoTime()
        val tickNow = level.server.tickCount
        val sinceCompletionMs = TimeUnit.NANOSECONDS.toMillis(now - saveWaitLastCompletionNanos)
        val outstanding = ServerMachines.outstandingWindowSaves(SAVE_WAIT_REPORT_OUTSTANDING).joinToString(prefix = "[", postfix = "]") {
            "${it.identity.id}@${it.identity.generation}${it.pos?.let { p -> " at ${p.toShortString()}" } ?: ""} " +
                "submitted ${if (it.submittedAtTick >= 0) tickNow - it.submittedAtTick else "?"} ticks / " +
                "${TimeUnit.NANOSECONDS.toMillis(now - it.submittedAtNanos)} ms ago"
        }
        val saves = ServerMachines.Stats.saves
        val writeMax = saves.maxOfOrNull { it.fileWriteNanos } ?: 0L
        val writeSum = saves.sumOf { it.fileWriteNanos }
        val encodeMax = saves.maxOfOrNull { it.encodeNanos } ?: 0L
        val encodeSum = saves.sumOf { it.encodeNanos }
        val writer = Thread.getAllStackTraces().entries.firstOrNull { it.key.name == SaveWriter.DEFAULT_THREAD_NAME }
        val writerText = if (writer == null) {
            "writer thread not found"
        } else {
            "writer ${writer.key.state} at " + writer.value.take(SAVE_WAIT_REPORT_FRAMES).joinToString(" < ")
        }
        return "last phase completion ${saveWaitTicks - saveWaitLastCompletionTick} ticks / $sinceCompletionMs ms ago; " +
            "outstanding $outstanding; " +
            "completed ${saves.size} writes: file write max %.1f ms, total %.1f ms, encode max %.1f ms, total %.1f ms; ".format(
                Locale.ROOT, writeMax / 1e6, writeSum / 1e6, encodeMax / 1e6, encodeSum / 1e6,
            ) +
            "finished jobs ${WorldMachineSaves.current?.finishedCount}; $writerText"
    }

    private fun unload() {
        val s = ServerMachines.Stats
        unloadMaxTickCaptures = maxOf(unloadMaxTickCaptures, s.unloadCaptures - unloadPrevCaptures)
        unloadMaxTickWrites = maxOf(unloadMaxTickWrites, s.unloadWrites - unloadPrevWrites)
        unloadPrevCaptures = s.unloadCaptures
        unloadPrevWrites = s.unloadWrites
        if (measuredTick == null) {
            if (phaseTick % 10 != 0) return
            // ケースのあるチャンクが全部アンロードされたら終わり（hasChunk は FULL でなくなっただけでも偽になるので、Unload を数える）
            val remaining = unloadCaseChunks - s.unloadEventsWithCases.toInt()
            if (remaining > 0 && phaseTick < UNLOAD_TIMEOUT_TICKS) return
            unloadRemaining = remaining.coerceAtLeast(0)
            stopMeasuring()
        }
        if (!awaitPhaseSaves()) return
        unloadResult = UnloadResult(
            s.unloadCaptures, s.unloadWrites, unloadMaxTickCaptures, unloadMaxTickWrites, unloadRemaining, unloadCaseChunks, measuredTicks,
            measuredTick!!, saveCost(),
        )
        next(LoadPhase.UNLOAD)
    }

    /**
     * [LoadPhase.RESTORE]（#582）: 毎 tick、`KiBi-boot-*` の本数と順番待ちの長さを数え、読み込まれたチャンクの台を引き直して、
     * 戻るはずの台が全部戻ったか（マシンがあって、復元の順番待ちでも途中でもない）を見る。画面は見ない（画面の中身は保存しないので、
     * 冷えた起動でもプロンプトが出る。ADR 0085）。全部戻るか、[RestoreResult.RESTORE_TIMEOUT_TICKS] で打ち切る
     */
    private fun restore() {
        restoreMaxQueue = maxOf(restoreMaxQueue, ServerMachines.pendingRestores)
        restorePeakBootThreads = maxOf(restorePeakBootThreads, bootThreads())
        if (phaseTick % 10 == 0) sampleKernelThreads()
        if (restoreChunksTick < 0 && restoreChunks.all { level.chunkSource.getChunkNow(it.x, it.z) != null }) restoreChunksTick = phaseTick
        var started = 0
        var done = 0
        var inFlight = 0
        for (k in restoreTargets.indices) {
            if (!restoreDone[k]) {
                val c = refresh(restoreTargets[k])
                if (c != null) {
                    val old = restoreOld[k]
                    val starts = if (c.case === old.case) c.case.restoreStarts - restoreBaseline[k] else c.case.restoreStarts
                    if (starts > 0) restoreStarted[k] = true
                    val computer = c.case.computer
                    if (computer.isRestoring) inFlight++
                    if (computer.machine != null && !computer.isRestorePending && !computer.isRestoring) {
                        restoreDone[k] = true
                        restoreKept[k] = !restoreStarted[k] && c.case === old.case
                    }
                }
            }
            if (restoreDone[k]) done++
            if (restoreDone[k] || restoreStarted[k]) started++
        }
        restorePeakInFlight = maxOf(restorePeakInFlight, inFlight)
        if (restoreStartedTick < 0 && started == restoreTargets.size) restoreStartedTick = phaseTick
        val all = done == restoreTargets.size
        if (all) {
            restoreDoneTick = phaseTick
            restoreDoneNanos = System.nanoTime()
        } else if (phaseTick < RestoreResult.RESTORE_TIMEOUT_TICKS) {
            return
        }
        val tick = tickStats()
        recording = false
        val s = ServerMachines.Stats
        restoreResult = RestoreResult(
            expected = restoreTargets.size,
            done = done,
            kept = restoreKept.count { it },
            fromSave = s.restoresFromSave,
            coldStart = s.restoresColdStart,
            writeTimeouts = s.restoreWriteTimeouts,
            restored = s.restored,
            fallbacks = s.restoreFallbacks,
            versionMismatches = s.restoreVersionMismatches,
            forceMillis = restoreForceMillis,
            firstTickMillis = if (sampleCount > 0) samples[0] / 1e6 else 0.0,
            chunksTick = restoreChunksTick,
            startedTick = restoreStartedTick,
            doneTick = restoreDoneTick,
            doneSeconds = ((if (all) restoreDoneNanos else System.nanoTime()) - phaseStartNanos) / 1e9,
            timedOut = !all,
            maxQueue = restoreMaxQueue,
            peakBootThreads = restorePeakBootThreads,
            peakInFlight = restorePeakInFlight,
            maxConcurrentMachines = (ServerMachines.schedulerConfig ?: SchedulerConfig.DEFAULT).maxConcurrentMachines,
            tick = tick,
        )
        next(LoadPhase.RESTORE)
    }

    /**
     * [i] 番目の台を位置から引き直す（#582）。ケース・画面・キーボードのチャンクが FULL で読み込まれていなければ `null`
     * （`getBlockEntity` はその場でチャンクを読み込むので、読み込まれたチャンクにだけ問い合わせる）。変わっていれば [computers] を差し替える
     */
    private fun refresh(i: Int): Computer? {
        val pos = helper.absolutePos(LoadGameTests.Layout.casePos(i, columns))
        val case = loadedBlockEntity(pos) as? CaseBlockEntity ?: return null
        val current = computers[i]
        if (current.case === case) return current
        val screen = loadedBlockEntity(pos.above()) as? ScreenBlockEntity ?: return null
        val keyboard = loadedBlockEntity(pos.south()) as? KeyboardBlockEntity ?: return null
        return Computer(case, screen, keyboard).also { computers[i] = it }
    }

    private fun loadedBlockEntity(pos: BlockPos) = level.chunkSource.getChunkNow(pos.x shr 4, pos.z shr 4)?.getBlockEntity(pos)

    /** `KiBi-boot-*` のスレッドの数（スタックを取らない。毎 tick 数える） */
    private fun bootThreads(): Int =
        threads.getThreadInfo(threads.allThreadIds, 0).count { it != null && it.threadName.startsWith(BOOT_THREAD_PREFIX) }

    private fun steady(p: LoadPhase) {
        val from = measureFrom ?: return when (p) {
            LoadPhase.IDLE -> awaitTmp()
            LoadPhase.COMPUTE -> awaitCompute()
            LoadPhase.COMPUTE_MULTI -> awaitComputeMulti()
            else -> awaitOutput()
        }
        val t = phaseTick - from
        if (t == settings.warmupTicks) {
            recording = true
            countSent = true
            threads.resetPeakThreadCount()
            peakKernelThreads = 0
            peakPhaseKernelThreads = 0
            peakFuelThreadWaits = 0
            fuelAtMeasure = FuelCounts.current()
            cpuAtMeasure = osBean?.processCpuTime ?: -1
            if (p == LoadPhase.COMPUTE_MULTI) {
                for (c in multiSet) {
                    val shells = computingShellPids(c)
                    c.grantsAtMeasure = (c.case.computer.machine?.fuelGrantsByProcess() ?: emptyMap()).filterKeys { it in shells }
                }
            }
        }
        if (recording && phaseTick % 10 == 0) {
            sampleKernelThreads()
            if (p == LoadPhase.COMPUTE || p == LoadPhase.COMPUTE_MULTI) {
                peakFuelThreadWaits = maxOf(peakFuelThreadWaits, computingSet.sumOf { fuelThreadWaits(it) })
            }
        }
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
            if (!computeStarted()) {
                if (phaseTick >= settings.outputTimeoutTicks) {
                    val c = computeSet.firstOrNull { !hasComputeProcess(it) || it.unwindsSincePhase() <= 0 }
                    fail(
                        "the compute command did not start within ${settings.outputTimeoutTicks} ticks " +
                            "(fuel since the phase began: ${FuelCounts.current() - fuelAtPhase}; " +
                            "processes: ${c?.case?.computer?.machine?.describeProcesses()})",
                    )
                }
                return
            }
            measureFrom = phaseTick
        } else if (phaseTick >= settings.outputTimeoutTicks) {
            val c = computeSet.first { !runsLoop(it) }
            fail("$waiting of ${computeSet.size} computing machines did not show the command within ${settings.outputTimeoutTicks} ticks: ${lines(c.screen.text)}")
        }
    }

    /**
     * [LoadOs.computeProcess] のある OS で、計算させる台の全部にそのプロセスがあり、全部の台が段階に入ってから燃料切れで巻き戻したか（#821）。
     * コマンドの読み込み（検証・変換・コンパイル）と起動の途中（realloc の中の燃料切れは巻き戻せないので、スレッドを持って待つ。ADR 0757）を
     * 測らないため。起動が済んで main のループに入った台だけが巻き戻すので、台ごとの回数で見る（サーバー全体の累計だと、先に起動した
     * 少数の台だけで超える）。ないなら、いつも真
     */
    private fun computeStarted(): Boolean {
        if (settings.os.computeProcess == null) return true
        if (!computeSet.all(::hasComputeProcess)) return false
        return computeSet.all { it.unwindsSincePhase() > 0 }
    }

    /**
     * [LoadPhase.COMPUTE_MULTI] のはじめ（#740）: 台ごとに、バックグラウンドの計算するコマンドを N−1 回、前景のものを1回、1行ずつ打ち込む。
     * 次の行は、シェルが前の行のジョブの知らせ（`[k] pid`）を出してプロンプトに戻ってから打つ（まとめて打つと、先打ちのエコーと知らせの順が入れ替わり、
     * 画面の最後の行で判断できない）。全部の台が前景のループに入り、計算するシェルが N 本あったら測りはじめる。
     * 画面だけでは、ジョブが起動に失敗して消えたのを見落とすので、プロセスの表（[computingShells]）でも確かめる
     */
    private fun awaitComputeMulti() {
        if (phaseTick % 5 != 0) return
        val n = settings.computeProcesses
        val background = settings.os.backgroundCommand!!
        var waiting = 0
        for (c in multiSet) {
            if (c.foregroundTyped) {
                if (!runsLoop(c)) waiting++
                continue
            }
            waiting++
            val text = lines(c.screen.text)
            text.firstOrNull { it.contains(SPAWN_FAILURE) }?.let { fail("a background job did not start: $text") }
            val atPrompt = text.lastOrNull { it.isNotEmpty() } == settings.os.prompt.toString()
            val noticed = c.jobsTyped == 0 || text.any { it.startsWith("[${c.jobsTyped}] ") }
            if (!atPrompt || !noticed) continue
            if (c.jobsTyped < n - 1) {
                type(c, background)
                c.jobsTyped++
            } else {
                type(c, settings.os.computeCommand)
                c.foregroundTyped = true
            }
        }
        if (waiting == 0) {
            val short = multiSet.firstOrNull { computingShells(it) < n }
            if (short == null) {
                measureFrom = phaseTick
            } else if (phaseTick >= settings.outputTimeoutTicks) {
                fail("a machine should run $n computing shells: ${short.case.computer.machine?.describeProcesses()}")
            }
        } else if (phaseTick >= settings.outputTimeoutTicks) {
            val c = multiSet.first { !it.foregroundTyped || !runsLoop(it) }
            fail(
                "$waiting of ${multiSet.size} machines did not start $n computing commands within ${settings.outputTimeoutTicks} ticks " +
                    "(${c.jobsTyped} in the background): ${lines(c.screen.text)}",
            )
        }
    }

    /** 台の中で、計算している（終わっていない・イベントを待っていない）シェルの数（#740。`describeProcesses` はカーネルロックを取るので、段階の始めと終わりだけで呼ぶ） */
    private fun computingShells(c: Computer): Int = computingShellPids(c).size

    /** 台の中で、計算しているシェルの pid（[computingShells]） */
    private fun computingShellPids(c: Computer): Set<Int> =
        processEntries(c).filter { it.name.substringAfterLast('/') == SHELL && it.state in COMPUTING_STATES }.mapTo(HashSet()) { it.pid }

    /**
     * [LoadPhase.COMPUTE_MULTI] の終わり（#740）: 測りはじめにあった計算するシェルごとに、測った区間にスライスを受け取った回数を出し、
     * 最小と最大を [endMinProcessGrants]・[endMaxProcessGrants] に入れる（終わったシェルは 0 回として数える）。ほかの段階は -1
     */
    private fun readProcessGrants(p: LoadPhase) {
        endMinProcessGrants = -1
        endMaxProcessGrants = -1
        if (p != LoadPhase.COMPUTE_MULTI || fuelAtMeasure == null) return
        val counts = multiSet.flatMap { c ->
            val now = c.case.computer.machine?.fuelGrantsByProcess() ?: emptyMap()
            c.grantsAtMeasure.map { (pid, at) -> (now[pid] ?: at) - at }
        }
        if (counts.isEmpty()) return
        endMinProcessGrants = counts.min()
        endMaxProcessGrants = counts.max()
    }

    /** 台の、終わっていないプロセスの名前と状態 */
    private fun processStates(c: Computer): List<Pair<String, String>> = processEntries(c).map { it.name to it.state }

    private class ProcessEntry(val pid: Int, val name: String, val state: String)

    /** 台の、終わっていないプロセスの pid・名前・状態（`Machine.describeProcesses` の `<pid>:<名前>=<状態> waits=...` から） */
    private fun processEntries(c: Computer): List<ProcessEntry> {
        val text = c.case.computer.machine?.describeProcesses() ?: return emptyList()
        if (text == "none") return emptyList()
        return text.split("; ").mapNotNull { entry ->
            val head = entry.substringBefore(" waits=")
            val colon = head.indexOf(':')
            val eq = head.lastIndexOf('=')
            val pid = if (colon < 0) null else head.substring(0, colon).toIntOrNull()
            if (pid == null || eq < colon) null else ProcessEntry(pid, head.substring(colon + 1, eq), head.substring(eq + 1))
        }
    }

    private fun hasComputeProcess(c: Computer): Boolean = processStates(c).any { it.first.substringAfterLast('/') == settings.os.computeProcess }

    /** 台の中で、燃料をスレッドを持ったまま待っているプロセス（`WAITING_FUEL`）の数 */
    private fun fuelThreadWaits(c: Computer): Int = processStates(c).count { it.second == WAITING_FUEL }

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
            LoadPhase.IDLE -> {
                for (c in tmpSet) type(c, settings.os.tmpCommand)
                // 本番の形（#834）: 測るバニラの自動保存を、新しいワールドの最初の保存（読み込んだチャンクを全部書く）にしないよう、
                // この tick の終わりに1回走らせておく（測る区間の外。全台が入力待ちの段階は、この後で測りはじめる）
                vanillaAutosave?.ticksUntilAutosave = 1
            }
            LoadPhase.PLACED -> {
                val t0 = System.nanoTime()
                for (i in 0 until settings.count) LoadGameTests.Layout.placeComputer(helper, LoadGameTests.Layout.casePos(i, columns), settings.os)
                // 他 MOD 役のデバイス（#153）。北隣は前の列のキーボードの南で、ほかのケースには隣り合わない
                if (settings.devicesEvery > 0) {
                    for (i in 0 until settings.count step settings.devicesEvery) {
                        helper.setBlock(LoadGameTests.Layout.casePos(i, columns).north(), Blocks.BARREL)
                    }
                }
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
                if (settings.devicesEvery > 0) {
                    for (i in computers.indices step settings.devicesEvery) {
                        if (computers[i].case.wiring.connected.none { it is ExternalDeviceHolder<*> }) {
                            fail("computer #$i is not wired to the test device next to it (is kibicomputers.dev.testDevices set?)")
                        }
                    }
                }
                // 起動は、電源を入れた次の tick から終わるまでを全部測る
                recording = true
            }
            LoadPhase.AUTOSAVE -> {
                ServerMachines.Stats.reset()
                phaseWindow = ServerMachines.Stats.window
                // この試験で置いた台だけを周期に入れる。前の回の強制読み込みが残ったワールドでは、よその台も動いている（#749）。
                // Gradle の run は毎回ワールドを消す（#792）ので、これは -PkeepGameTestWorld や IDE から起動して使い回したときの保険
                val own: MutableSet<CaseBlockEntity> = Collections.newSetFromMap(IdentityHashMap())
                for (c in computers) own += c.case
                val others = ServerMachines.trackedCases().filter { it !in own && it.computer.machine != null }
                if (others.isNotEmpty() && settings.productionAutosave) {
                    // 本番の形は本物の経路（ServerMachines.autosaveTick）で一巡を始めるので、よその台を周期から外せない。
                    // 台数が合わずに後で落ちるより、ここで理由を書いて落とす
                    fail(
                        "${others.size} running machines that this load test did not place are loaded; the production-shaped autosave " +
                            "starts the cycle on the real path and cannot leave them out (run it on a fresh world): " +
                            others.take(5).joinToString(" ") { "(${it.blockPos.toShortString()})" },
                    )
                }
                if (others.isNotEmpty()) {
                    LOGGER.warn(
                        "{} running machines that this load test did not place are loaded; the autosave cycle leaves them out: {}",
                        others.size,
                        others.take(5).joinToString(" ") { "(${it.blockPos.toShortString()})" },
                    )
                }
                if (settings.productionAutosave) startProductionAutosave() else ServerMachines.startAutosaveCycle(own)
                recording = true
            }
            LoadPhase.COMPUTE -> {
                computingSet = computeSet
                for (c in computeSet) type(c, settings.os.computeCommand)
            }
            LoadPhase.COMPUTE_MULTI -> {
                // 1本の台は電源を切る（電源ボタンは押すたびに入・切が入れ替わるので、今計算している台だけを押す）。
                // 1台に1本の台を切る時間は、段階を飛ばす回（OUTPUT の始めに切る）と同じものとしてここで測る
                val t0 = System.nanoTime()
                for (c in computingSet) c.case.pressPowerButton()
                powerOffMillis = (System.nanoTime() - t0) / 1e6
                computingSet = multiSet
                for (c in multiSet) {
                    c.jobsTyped = 0
                    c.foregroundTyped = false
                }
            }
            LoadPhase.OUTPUT -> {
                val t0 = System.nanoTime()
                val multi = computingSet.isNotEmpty() && computingSet == multiSet
                for (c in computingSet) c.case.pressPowerButton()
                computingSet = emptyList()
                val millis = (System.nanoTime() - t0) / 1e6
                if (multi) powerOffMultiMillis = millis else powerOffMillis = millis
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
                phaseWindow = ServerMachines.Stats.window
                // 戻るはずの台（#582）: 今マシンがある台（計算させた台は電源を切ったので戻らない）
                restoreTargets = computers.indices.filter { computers[it].case.computer.machine != null }.toIntArray()
                restoreOld = restoreTargets.map { computers[it] }
                restoreBaseline = IntArray(restoreTargets.size) { restoreOld[it].case.restoreStarts }
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
            LoadPhase.RESTORE -> {
                // 保存の数え口は使わない（復元の数え口だけを 0 から数える。アンロードの結果は作り終えている）
                ServerMachines.Stats.reset()
                restoreDone = BooleanArray(restoreTargets.size)
                restoreStarted = BooleanArray(restoreTargets.size)
                restoreKept = BooleanArray(restoreTargets.size)
                restoreChunks = restoreTargets.flatMap { i ->
                    val pos = helper.absolutePos(LoadGameTests.Layout.casePos(i, columns))
                    listOf(ChunkPos(pos), ChunkPos(pos.above()), ChunkPos(pos.south()))
                }.distinct()
                // テストの構造物のチャンクを先に戻してから、並べた範囲を強制読み込みする（はじめと同じ順。構造物のチャンクは forced に入らない）。
                // スポーンチャンクの半径は cleanUp まで 0 のまま（戻すと、スポーンチャンクの読み込みが混ざる）
                val t0 = System.nanoTime()
                for (chunk in unforcedOthers) level.setChunkForced(chunk.x, chunk.z, true)
                unforcedOthers.clear()
                forceChunks()
                val forceNanos = System.nanoTime() - t0
                restoreForceMillis = forceNanos / 1e6
                // 試験の呼び出しの中で同期に読み込んだ分、サーバーは tick の予定から遅れる。そのままだと、追いつくまで tick が詰めて回り
                // （前の回は強制読み込みの後の 20 tick が 0.45 s）、測る区間が 20 TPS にならない。読み込みの時間だけ予定をずらす（TickSchedule）
                tickSchedule?.let {
                    it.delay(forceNanos)
                    scheduleShiftNanos = forceNanos
                }
                // doneSeconds・finish の壁時計は強制読み込みを戻し終えてから数える（読み込みの時間は forceMillis に別に出す）
                phaseStartNanos = System.nanoTime()
                recording = true
            }
        }
    }

    /**
     * 本番の形の自動保存（#834）を仕掛ける（試験の呼び出し、つまり `tickChildren` の中で呼ぶ。この tick の `ServerTickEvent.Pre` は済んでいる）。
     *
     * - 周期を本番の値（試験の前のコンフィグの `autosavePeriodTicks`。既定 6000）に戻し、数え口を [PRODUCTION_LEAD_TICKS] tick 後の
     *   `ServerTickEvent.Pre` に周期が来るように合わせる（`ServerMachines.alignAutosaveCounter`）。周期は本物の経路で始まる
     * - バニラの `ticksUntilAutosave` を `PRODUCTION_LEAD_TICKS + 1 + offset` にする。この tick の `tickServer` の終わりから1つずつ減るので、
     *   周期が来た tick（+ offset）の `tickServer` の終わりに 0 になり、`saveEverything` が走る。
     *   本番（起動から 6000 tick 目の `Pre` で KiBi の周期が始まり、同じ tick の終わりにバニラの自動保存が走る）と同じ重なり方
     */
    private fun startProductionAutosave() {
        val vanilla = vanillaAutosave ?: fail("the production-shaped autosave needs MinecraftServer.ticksUntilAutosave")
        val period = savedSaveConfig?.autosavePeriodTicks ?: ServerMachines.saveConfig.autosavePeriodTicks
        if (period <= PRODUCTION_LEAD_TICKS + 1) fail("the autosave period ($period ticks) is too short for the production-shaped autosave")
        productionPeriod = period
        serverTickAtStart = level.server.tickCount
        ServerMachines.saveConfig = ServerMachines.saveConfig.copy(autosavePeriodTicks = period)
        ServerMachines.alignAutosaveCounter(PRODUCTION_LEAD_TICKS.toLong())
        // この tick の終わりに1つ減るので、+1 して、周期が来る tick（PRODUCTION_LEAD_TICKS 後）の終わりに 0 にする
        vanilla.ticksUntilAutosave = PRODUCTION_LEAD_TICKS + 1 + settings.vanillaSaveOffset
        probe.armLevelSave()
        prevVanillaTicks = vanilla.ticksUntilAutosave
        vanillaSaveTick = -1
        cycleStartTick = -1
        prevCycleStarts = ServerMachines.Stats.autosaveCycleStarts
        cycleStartsAtArm = prevCycleStarts
        probe.takeSyntheticLog()
        probe.syntheticLogEveryTicks = settings.syntheticLogTicks
        logCounter?.begin()
    }

    private fun verify(p: LoadPhase) {
        when (p) {
            LoadPhase.EMPTY, LoadPhase.PLACED, LoadPhase.BOOT -> Unit
            LoadPhase.AUTOSAVE -> {
                val r = autosaveResult!!
                if (r.written + r.skipped != settings.count.toLong()) fail("the autosave should visit all ${settings.count} machines: $r")
                verifyCounted(p, r.cost, handedToWriter = r.written, exact = false)
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
            LoadPhase.COMPUTE_MULTI -> for (c in multiSet) {
                if (c.case.computer.machine?.state != MachineState.RUNNING) fail("a computing machine stopped: ${lines(c.screen.text)}")
                if (!runsLoop(c)) fail("the foreground loop should keep running: ${lines(c.screen.text)}")
                val shells = computingShells(c)
                if (shells < settings.computeProcesses) {
                    fail("${settings.computeProcesses} computing shells should keep running, but $shells: ${c.case.computer.machine?.describeProcesses()}")
                }
                if (c.grantsAtMeasure.size < settings.computeProcesses) {
                    fail("${settings.computeProcesses} computing shells should be counted when the measurement began, but ${c.grantsAtMeasure.size}")
                }
            }
            LoadPhase.UNLOAD -> unloadResult!!.let { verifyCounted(p, it.cost, handedToWriter = it.captures, exact = true) }
            // 測るだけで落とさない。ただし、気づくべきことは WARN にする（#582）
            LoadPhase.RESTORE -> restoreResult!!.let {
                if (it.timedOut) {
                    LOGGER.warn("Only {} of {} computers came back within {} ticks: {}", it.done, it.expected, RestoreResult.RESTORE_TIMEOUT_TICKS, it)
                }
                // アンロードの段階は予約した書き込みを全部待ってから終わるので、書き込み待ちで冷えた起動にする台はないはず
                if (it.writeTimeouts > 0) LOGGER.warn("{} computers cold-booted because their writes did not finish: {}", it.writeTimeouts, it)
            }
            LoadPhase.OUTPUT, LoadPhase.WATCHED -> for (c in outputSet) {
                if (c.case.computer.machine?.state != MachineState.RUNNING) fail("an output machine stopped: ${lines(c.screen.text)}")
                if (!showsOutput(c)) fail("output should be shown: ${lines(c.screen.text)}")
            }
        }
    }

    /**
     * 段階に予約した書き込みが、すべて保存の手間と大きさの表（書けた・書けなかった）に入ったか（#602）。次の3つを確かめる。
     * 1. 段階の途中で [ServerMachines.Stats.reset] が呼ばれていない（区間の番号が段階の始めのまま）。reset は予約・完了・書けた数を同時に 0 にし、
     *    前の区間の完了は数え口に入れないので、途中で呼ばれても 2. は成り立ったままになり、2. だけでは気づけない
     * 2. 書けた数 + 書けなかった数 == 予約した数。#614 の前は、書き込みのスレッドを `writerFlushMillis`（30 秒）まで待って打ち切っていたので、
     *    待ち切れなかった分が表から落ちていた。[awaitPhaseSaves] が全部の完了を待つので、これは作りの上で成り立つ。落ちるのは、
     *    待ちを打ち切りに戻したときだけ
     * 3. 予約した数と、段階が書き込みに回した台の数（[handedToWriter]。予約の数え口とは別のところで数える）の関係。
     *    アンロードは、取り出しに成功した台だけを数える（`unloadCaptures`）ので、予約した数と一致する（[exact]）。
     *    自動保存の `written` は、`captureAndSubmit` が null を返した台（CRASHED・復元中など）も「回した」に数える（AutosaveRotation）ので、
     *    予約した数は `written` 以下であればよい。予約が回した台より多ければ、数え口か回し方のどちらかが壊れている。
     *    「回した台に対して表が足りない」差は、報告の「書き込みに回した台」と「予約した書き込み」を並べて読む
     */
    private fun verifyCounted(p: LoadPhase, cost: SaveCost, handedToWriter: Long, exact: Boolean) {
        val window = ServerMachines.Stats.window
        if (window != phaseWindow) fail("the save statistics were reset in the middle of the $p phase (window $phaseWindow -> $window)")
        val counted = cost.saves + cost.writer.failures
        if (counted != cost.submitted) {
            fail("${cost.submitted} saves were queued in the $p phase, but $counted were counted (${cost.saves} written, ${cost.writer.failures} failed)")
        }
        if (if (exact) cost.submitted != handedToWriter else cost.submitted > handedToWriter) {
            fail("${cost.submitted} saves were queued in the $p phase, but the phase handed ${if (exact) "" else "at most "}$handedToWriter machines to the writer")
        }
    }

    private fun enter(p: LoadPhase) {
        phase = p
        phaseTick = -1 // step() が 0 にする
        measureFrom = if (p == LoadPhase.OUTPUT || p == LoadPhase.IDLE || p == LoadPhase.COMPUTE || p == LoadPhase.COMPUTE_MULTI) null else 0
        phaseStartNanos = System.nanoTime()
        recording = false
        measuredTick = null
        measuredTicks = 0
        countSent = false
        longTicks.clear()
        longTickCount = 0
        sectionMarks.fill(0)
        sampleCount = 0
        sentBytes = 0
        peakKernelThreads = 0
        peakPhaseKernelThreads = 0
        threads.resetPeakThreadCount()
        baselineKernelThreads = kernelThreadInfos().mapTo(HashSet()) { it.threadId }
        fuelAtPhase = FuelCounts.current()
        for (c in computers) c.unwindsAtPhase = c.case.computer.machine?.fuelUnwindCount ?: 0L
        fuelAtMeasure = null
        cpuAtMeasure = -1
        peakFuelThreadWaits = 0
    }

    private fun finish() {
        val p = phase ?: return
        val wall = (System.nanoTime() - phaseStartNanos) / 1e9
        val stats = TickStats.of(samples.copyOf(sampleCount))
        val infos = kernelThreadInfos()
        val names = infos.groupingBy { it.threadName.replace(DIGITS, "#") }.eachCount().toSortedMap()
        val kernel = infos.size
        val idleWorkers = infos.count(::isIdleWorker)
        logLongTicks(p)
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
            longTicks = longTicks.toList(),
            longTickCount = longTickCount,
            sectionMarks = sectionMarks.copyOf(),
            fuel = fuelAtMeasure?.let { endFuel - it },
            peakFuelThreadWaits = if ((p == LoadPhase.COMPUTE || p == LoadPhase.COMPUTE_MULTI) && fuelAtMeasure != null) peakFuelThreadWaits else -1,
            cpuNanos = if (cpuAtMeasure >= 0 && fuelAtMeasure != null && endCpu >= 0) endCpu - cpuAtMeasure else -1,
            busyMachines = when (p) {
                LoadPhase.COMPUTE, LoadPhase.COMPUTE_MULTI -> computingSet.size
                LoadPhase.OUTPUT, LoadPhase.WATCHED -> outputSet.size
                else -> 0
            },
            processesPerMachine = when (p) {
                LoadPhase.COMPUTE -> 1
                LoadPhase.COMPUTE_MULTI -> settings.computeProcesses
                else -> 0
            },
            minProcessGrants = endMinProcessGrants,
            maxProcessGrants = endMaxProcessGrants,
        )
        if (p == LoadPhase.COMPUTE) {
            computeSet.firstNotNullOfOrNull { it.case.computer.machine?.config }?.let {
                reportFuelPerTick = it.fuelPerTick
                reportFuelSlice = it.fuelSliceValue
            }
        }
        results += result
    }

    private fun done() {
        val report = LoadReport(
            settings, placeMillis, powerOnMillis, powerOffMillis, outputWaitTicks, baselineHeap, results.toList(), autosaveResult, unloadResult, productionResult,
            restoreResult,
            fuelPerTick = reportFuelPerTick, fuelSlice = reportFuelSlice, powerOffMultiMillis = powerOffMultiMillis,
        )
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
        probe.syntheticLogEveryTicks = 0
        probe.stop()
        logCounter?.stop()
        savedProgressLevel?.let { Configurator.setLevel(PROGRESS_LOGGER, it) }
        savedProgressLevel = null
        for (i in 0 until settings.count) {
            val pos = LoadGameTests.Layout.casePos(i, columns)
            helper.setBlock(pos, Blocks.AIR)
            helper.setBlock(pos.above(), Blocks.AIR)
            helper.setBlock(pos.south(), Blocks.AIR)
            if (settings.devicesEvery > 0) helper.setBlock(pos.north(), Blocks.AIR)
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
        // スライスの差し替え（#740）を残さない（同じ JVM で続けて動く GameTest のスライスが変わる）
        if (settings.fuelSlice != null) CaseBlockEntity.configOverride = null
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
        private val LOGGER = LogUtils.getLogger()

        /** 自動保存の一巡を待つ上限 */
        private const val AUTOSAVE_TIMEOUT_TICKS = 2_000

        /** まとめてアンロードを待つ上限（過ぎたら、そこまでの結果を書く） */
        private const val UNLOAD_TIMEOUT_TICKS = 2_000

        /**
         * 段階に予約した書き込みが終わるのを待つ間、終わった書き込みが1件も増えない tick の上限（#614・#854）。
         * 1 tick あたり [SAVE_WAIT_STEP_MILLIS] まで待つので、壁時計では 120 秒以上になる（以前の壁時計の上限と同じ）
         */
        private const val SAVE_WAIT_IDLE_TICKS = 2_400

        /**
         * 段階に予約した書き込みが終わるのを待つ tick の全体の上限（#854）。進んでいても、ここで打ち切る。
         * 待つ段階は多くて 2 つ（AUTOSAVE と UNLOAD）。本番の形の自動保存（[LoadSettings.productionAutosave]）は AUTOSAVE の段階の中で
         * `autosave()` の代わりに呼ばれ、その形のときは UNLOAD が入らない。試験の上限（`CI_TIMEOUT_TICKS`）は、その 2 段階分を足しても収まるようにしてある
         */
        private const val SAVE_WAIT_TIMEOUT_TICKS = 6_000

        /** 段階に予約した書き込みの終わりを待つ間、1 tick で書き込みのスレッドを待つ上限 */
        private const val SAVE_WAIT_STEP_MILLIS = 50L

        /** 書き込みの待ちで失敗したとき、文言に出す残りの予約の数（[saveWaitDiagnostics]。#852） */
        private const val SAVE_WAIT_REPORT_OUTSTANDING = 5

        /** 書き込みの待ちで失敗したとき、文言に出す書き込みのスレッドのスタックの深さ（[saveWaitDiagnostics]。#852） */
        private const val SAVE_WAIT_REPORT_FRAMES = 8

        private val DIGITS = Regex("[0-9]+")

        /** 復元の前段のスレッドの名前（core の `MachineScheduler`） */
        private const val BOOT_THREAD_PREFIX = "KiBi-boot-"

        /** [LoadPhase.RESTORE] の前に、予定より先にいる tick がこれだけ続くのを待つ（[settlePace]。1 秒） */
        private const val PACE_SETTLE_TICKS = 20

        /** 追いつくのを待つ上限（tick。サーバーは 2 秒以上遅れると予定を飛ばして追いつくので、ふつうはもっと早い） */
        private const val PACE_SETTLE_TIMEOUT_TICKS = 1_200

        /** `Machine.describeProcesses` に出る、燃料をスレッドを持ったまま待つプロセスの状態（core の `ProcessState.WAITING_FUEL`。内部の型なので名前で見る） */
        private const val WAITING_FUEL = "WAITING_FUEL"

        /** 標準 OS のシェルのプロセスの名前（`argv[0]` の最後の部分。バックグラウンドのジョブもシェル自身を起動する） */
        private const val SHELL = "sh"

        /** 計算している（CPU・許可・燃料を待つか、動いている）プロセスの状態（core の `ProcessState`。内部の型なので名前で見る） */
        private val COMPUTING_STATES = setOf("RUNNABLE", "RUNNING", WAITING_FUEL)

        /** 標準 OS のシェルが、バックグラウンドのジョブを起動できなかったときに出す文 */
        private const val SPAWN_FAILURE = "cannot start a subshell"

        /** プールで仕事を待っているワーカーを見分けるのに読むスタックの深さ */
        private const val STACK_DEPTH = 32

        /** GameTest のサーバーが 20 tick ごとに進み具合を書くロガー */
        private const val PROGRESS_LOGGER = "net.minecraft.gametest.framework.GameTestServer"

        /**
         * 本番の形の自動保存（#834）で、仕掛けてから一巡とバニラの自動保存が始まるまでの tick 数。
         * 短くして、測る区間のほとんどを一巡の間にする
         */
        private const val PRODUCTION_LEAD_TICKS = 20

        /**
         * 本番の形の自動保存（#834）で、一巡と書き込みが全部終わるまで待つ上限（tick）。メインスレッドを止めて待たないので、
         * 一巡の後の書き込みの残りも tick を数えて待つ（1,000 台で 1〜2 分かかる回がある）。
         * 一巡が本物の経路で始まった tick（周期の数え口が一巡を始めた tick）に周期を止める（2 つ目の一巡は始まらない）ので、一巡の残り・書き込みの残り・バニラの保存を待つ間は、
         * この値（段階の始めから数える）が上限になる。一巡が始まる前だけは、上限が「周期 + [PRODUCTION_LEAD_TICKS] - 1」を超えない
         */
        private const val PRODUCTION_TIMEOUT_TICKS = 12_000

        /** 段階ごとに覚える長い tick の上限 */
        private const val MAX_LONG_TICKS = 32

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
