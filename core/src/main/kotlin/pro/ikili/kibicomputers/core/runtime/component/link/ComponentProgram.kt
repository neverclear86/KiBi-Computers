package pro.ikili.kibicomputers.core.runtime.component.link

import pro.ikili.kibicomputers.core.runtime.Instrumentation
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.WasmModule
import pro.ikili.kibicomputers.core.runtime.WasmRuntime
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.asyncify.Asyncify
import pro.ikili.kibicomputers.core.runtime.asyncify.AsyncifyUnsupported
import pro.ikili.kibicomputers.core.runtime.component.ByteRange
import pro.ikili.kibicomputers.core.runtime.component.ComponentImportPolicy
import pro.ikili.kibicomputers.core.runtime.component.ComponentLimits
import pro.ikili.kibicomputers.core.runtime.component.ComponentLoader
import pro.ikili.kibicomputers.core.runtime.fuel.FuelInjector
import pro.ikili.kibicomputers.core.runtime.validation.LoadDeadline
import java.nio.ByteBuffer

/**
 * 読み込んでリンクの計画まで済ませたコンポーネント（#263。ADR 0263）。不変で、同じものから何度でもインスタンス化できる
 * （[LinkedComponent.instantiate]）。
 *
 * @property sha256 元のバイト列の SHA-256
 * @property metered 中の core モジュールに燃料の計測コードを挿入したか（挿入したものは燃料を共有する。[WasmModule.sharesFuel]）
 * @property cacheWeight `ProgramCache` で持つときの重さの見積もり（#687。[weightOf]）
 * @property waitPoints 中の core モジュールすべてに待機点への変換をかけたか（#757。かけたものは `WAIT_POINT` で動かせる）
 * @property waitPointRejection 変換を試して、かけなかった理由（[ComponentAsyncify]。かけた・試していないなら `null`）
 * @property fuelYield 待機点への変換で、燃料切れの待ち点（`kibi.async_fuel`）を 1 つ以上作った（#803。段1。`Program.fuelYield` に渡す。
 *   前借りは、これが真のときだけする）
 * @property fuelYieldRejection 段1を断られて段2（燃料切れを待ち点にしない）で変換した core モジュールがあれば、その最初の理由。なければ `null`
 */
internal class ComponentProgram private constructor(
    val plan: ComponentPlan,
    val metered: Boolean,
    private val hash: ByteArray,
    val cacheWeight: Long,
    val waitPoints: Boolean,
    val waitPointRejection: String?,
    val fuelYield: Boolean,
    val fuelYieldRejection: String?,
) {
    val sha256: ByteArray get() = hash.copyOf()

    /** 中の core モジュールの数（同じ中身は 1 つと数える） */
    val moduleCount: Int get() = plan.modules.size

    companion object {
        /**
         * core モジュール 1 つあたりの固定の重さ（バイト。仮の値、確定は #251）。コンパイル結果のクラス（Metaspace）と
         * ランタイムの持つ表の分。挿入後の lua.wasm で約 1.3 MB の実測（`ProgramCache`）があるが、小さなモジュールはずっと軽いので、
         * 大きさに比例する分はモジュールのバイト数で数え、ここは小さなモジュールでも必ずかかる分だけにする
         */
        const val WEIGHT_PER_MODULE: Long = 64L * 1024

        /** 計画の項目（core のインスタンス化の手順・lift・lower・resource）1 つあたりの重さ（バイト。仮の値、確定は #251） */
        const val WEIGHT_PER_PLAN_ITEM: Long = 256

        /**
         * `ProgramCache` で持つときの重さの見積もり（#687）: 中の core モジュール（同じ中身は 1 つ）のランタイムに渡したバイト数の合計
         * ＋ core モジュールの数 × [WEIGHT_PER_MODULE] ＋ 計画の項目の数 × [WEIGHT_PER_PLAN_ITEM]。
         * コンポーネントは core モジュールを最大 1,000 個持てるので、元のバイト数だけで数えると、小さな入力で Metaspace を大量に使える
         */
        fun weightOf(moduleBytes: Long, plan: ComponentPlan): Long {
            val items = plan.steps.size.toLong() + plan.lifts.size + plan.lowers.size + plan.resources.size
            return moduleBytes + plan.modules.size * WEIGHT_PER_MODULE + items * WEIGHT_PER_PLAN_ITEM
        }

        /**
         * [bytes] を読み込む。順番は次のとおり（#757）。全体で 1 つの時間の上限（[ComponentLimits.moduleLimits] の時間）を守る。
         * 1. コンポーネントの検証（[ComponentLoader]）
         * 2. 中の core モジュールを、同じ中身は 1 つにまとめ、[metered] なら燃料の計測コードを共有の形で挿入する
         * 3. import と export だけを読み（[WasmRuntime.describe]）、リンクの計画を作る（[ComponentPlanner]）
         * 4. [asyncify] なら、計画から止まりうる import を決めて（[ComponentAsyncify]）、全モジュールに待機点への変換をかける。
         *    かけられなければ、どのモジュールにもかけない（理由は [waitPointRejection]。`BLOCKING` で動く）。
         *    変換はモジュールごとに 2 段で試す（#803。p1 の [Instrumentation] と同じ）: 止まりうる関数の中の燃料切れ（`kibi.fuel`）も
         *    待ち点にする（段1）→ 断られたら、そのモジュールだけ燃料切れを待ち点にせずに変換する（段2）。段2も断られたら上のとおり
         *    どのモジュールにもかけない。[fuelYield] が偽なら（か燃料を計測しないなら）段1は試さない
         * 5. ランタイムに読み込ませる（パース・検証・コンパイル。各モジュール 1 回だけ）
         *
         * 変換をかけられなかったときは、理由をログに出す（同じコンポーネントでは 1 回だけ。[WaitPointRejectionLog]）。`BLOCKING` のコンポーネントは
         * 待つ間スレッドを持ち、`maxBlockingProcesses`（1 台・全体）に数えられるので、運用の側から原因を追えるようにする。
         *
         * 燃料の挿入と変換の順は p1 と同じ（[Instrumentation]。#341）。燃料切れの待ち点（`kibi.async_fuel`）で巻き戻すときは、
         * カーネルが `LinkedComponent.requestUnwind` を通して全インスタンスの状態を同時に変える（#803。ADR 0757 の「燃料切れの待ち点」）。
         * 段1は止まりうる関数の判定を変えない（`kibi.fuel` は止まりうる import に入れない。[ComponentAsyncify] の結果をそのまま使う）ので、
         * 段1を断られたモジュールが混ざっても、待機点型（`WAIT_POINT`）で動けるかは変わらない。段2に落ちたモジュールがあれば、
         * 段1を断った理由をログに出す（同じコンポーネントでは 1 回だけ。[FuelYieldRejectionLog]）。
         *
         * core モジュールは、コンポーネントの中の元のバイト列（を書き換えたもの）を Endive に渡す（書き換えの前に Endive のパーサーが
         * 元のバイト列を読んで検証する）。wasm-tools の検証は関数の本体を除いた写しにしか当てていない（#262）ので、本体の検証、
         * core モジュールのカスタム セクションの壊れ、コードのセクションの後ろの余りは、ここで Endive が断る。
         *
         * 重いので、コンパイル用のスレッドから呼ぶ。[validate] は wasm-tools の検証の差し替え（テスト用。[ComponentLoader.load]）
         *
         * @throws WasmLoadException 読めない・検証に通らない・ホストにない import・まだ扱えない機能・上限を超えた
         * @throws WasmTrapException 検証の途中でスレッドが割り込まれた（[pro.ikili.kibicomputers.core.runtime.WasmTrapKind.INTERRUPTED]。
         *   内容の失敗ではないので、読み込みの失敗として覚えない）
         */
        @Throws(WasmLoadException::class, WasmTrapException::class)
        fun load(
            runtime: WasmRuntime,
            bytes: ByteArray,
            host: ComponentHost,
            metered: Boolean = true,
            limits: ComponentLimits = ComponentLimits.DEFAULT,
            policy: ComponentImportPolicy = ComponentImportPolicy.DEFAULT,
            validate: ((ByteArray) -> Unit)? = null,
            asyncify: Boolean = true,
            fuelYield: Boolean = true,
        ): ComponentProgram {
            ComponentLoader.warmUp()
            val deadline = LoadDeadline(limits.moduleLimits.timeLimitNanos)
            val loaded = ComponentLoader.load(bytes, limits, policy, deadline = deadline, validate = validate)
            val injected = ArrayList<ByteArray>()
            // 元のモジュールの関数の番号への戻し方（燃料の挿入が足した import の分。断る理由に書く番号を p1 と同じ元の番号にする。#398）
            val numbering = ArrayList<(Int) -> Int>()
            val byContent = HashMap<ByteBuffer, Int>()
            val moduleIndex = HashMap<ByteRange, Int>()
            for (range in loaded.coreModules) {
                val original = loaded.coreModuleBytes(range)
                val id = byContent.getOrPut(ByteBuffer.wrap(original)) {
                    if (metered) {
                        val r = FuelInjector.inject(original, sharedGlobal = true) { deadline.check("燃料の計測コードの挿入") }
                        injected += r.bytes
                        numbering += r::originalFunc
                    } else {
                        injected += original
                        numbering += { it }
                    }
                    injected.size - 1
                }
                moduleIndex[range] = id
            }
            val shapes = injected.map { runtime.describe(it) }
            deadline.check("core モジュールの読み込み")
            val planned = ComponentPlanner.plan(loaded, shapes, moduleIndex, host, deadline)
            deadline.check("コンポーネントのリンク")
            var rejection: String? = null
            var yielded = false
            var yieldRejection: String? = null
            val finalBytes = if (asyncify) {
                // 燃料切れの import（`kibi.fuel`）は、計測コードを挿入したときだけある
                val r = transform(injected, numbering, planned, deadline, fuelYield && metered)
                rejection = r.rejection
                rejection?.let { WaitPointRejectionLog.report(loaded.sha256, it) }
                if (r.bytes != null) {
                    yielded = r.fuelYield
                    yieldRejection = r.fuelYieldRejection
                    yieldRejection?.let { FuelYieldRejectionLog.report(loaded.sha256, it) }
                }
                r.bytes ?: injected
            } else {
                injected
            }
            val modules = ArrayList<WasmModule>(finalBytes.size)
            var moduleBytes = 0L
            for ((i, b) in finalBytes.withIndex()) {
                val module = runtime.load(b)
                deadline.check("core モジュールのパース・検証・コンパイル")
                // 計画は変換の前の形で作った。ランタイムは変換・挿入が足したものを見せないので、同じ形になる
                check(module.imports == shapes[i].imports && module.exports == shapes[i].exports) { "読み込んだ core モジュール $i の形が計画と違う" }
                modules += module
                moduleBytes += b.size
            }
            val plan = planned.link(modules)
            val waitPoints = asyncify && rejection == null
            return ComponentProgram(
                plan,
                metered,
                loaded.sha256,
                weightOf(moduleBytes, plan),
                waitPoints,
                rejection,
                fuelYield = waitPoints && yielded,
                fuelYieldRejection = yieldRejection,
            )
        }

        /**
         * [transform] の結果。
         *
         * @property bytes 待機点への変換をかけた全モジュールのバイト列（かけられなければ `null`。理由は [rejection]）
         * @property fuelYield 段1で変換し、燃料切れの待ち点（`kibi.async_fuel`）が 1 つ以上できたモジュールがある
         * @property fuelYieldRejection 段2に落ちたモジュールの、段1を断った最初の理由
         */
        private class Transformed(val bytes: List<ByteArray>?, val rejection: String?, val fuelYield: Boolean, val fuelYieldRejection: String?)

        /**
         * 待機点への変換をかけた全モジュールのバイト列。かけられなければ `null` と理由。
         * [fuelYield] なら、モジュールごとに段1（燃料切れも待ち点にする）を試し、断られたらそのモジュールだけ段2にする（#803）。
         * 段1と段2は同じ [Asyncify.Prepared] から変換する（変換は読んだモジュールを書き換えず、呼ぶたびに作り直すので、何度呼んでもよい）
         */
        private fun transform(
            injected: List<ByteArray>,
            numbering: List<(Int) -> Int>,
            planned: ComponentPlanner.Planned,
            deadline: LoadDeadline,
            fuelYield: Boolean,
        ): Transformed {
            val checkpoint = { deadline.check(STAGE_ASYNCIFY) }
            val prepared = injected.mapIndexed { i, b -> Asyncify.prepare(b, checkpoint, numbering[i]) }
            val analysis = ComponentAsyncify.analyze(planned, prepared, checkpoint)
            analysis.rejection?.let { return Transformed(null, it, false, null) }
            val out = ArrayList<ByteArray>(injected.size)
            var yielded = false
            var yieldRejection: String? = null
            for ((i, p) in prepared.withIndex()) {
                if (fuelYield) {
                    try {
                        val r = p.transform(analysis.waitImports[i], analysis.externalTypes, Instrumentation.YIELD_IMPORT, checkpoint = checkpoint)
                        out += r.bytes
                        if (r.yieldSites > 0) yielded = true
                        continue
                    } catch (e: AsyncifyUnsupported) {
                        if (yieldRejection == null) yieldRejection = "core モジュール $i: ${e.message ?: "理由不明"}"
                    }
                }
                out += try {
                    p.transform(analysis.waitImports[i], analysis.externalTypes, checkpoint = checkpoint).bytes
                } catch (e: AsyncifyUnsupported) {
                    return Transformed(null, "core モジュール $i: ${e.message ?: "理由不明"}", false, null)
                }
            }
            return Transformed(out, null, yielded, yieldRejection)
        }

        private const val STAGE_ASYNCIFY = "待機点への変換"
    }
}

/**
 * 同じコンポーネント（元のバイト列の SHA-256）につき 1 回だけ出すログ（[WaitPointRejectionLog]・[FuelYieldRejectionLog]）。
 * 読み込みは `ProgramCache` で共有するので普通は 1 回だが、キャッシュから外れて読み込み直したときにも出さないように、
 * 出したものを最近の [MAX_REMEMBERED] 個まで覚えておく（ログごとに別に覚える）。
 *
 * スレッド: どのスレッドから呼んでもよい（読み込みのスレッドから呼ぶ）。
 */
internal abstract class ComponentOnceLog {
    private val reported = object : LinkedHashMap<String, Unit>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean = size > MAX_REMEMBERED
    }

    /** 出す文。[key] はコンポーネントの SHA-256 の 16 進 */
    protected abstract fun message(key: String, reason: String): String

    /** [sha256] のコンポーネントの理由 [reason] をログに出す。同じコンポーネントでもう出していれば出さずに `false` */
    fun report(sha256: ByteArray, reason: String): Boolean {
        val key = sha256.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val first = synchronized(reported) { reported.put(key, Unit) == null }
        if (first) LOG.log(System.Logger.Level.INFO, message(key, reason))
        return first
    }

    companion object {
        /** 覚えておくコンポーネントの数の上限（あふれたら、最も前に出したものから忘れる） */
        const val MAX_REMEMBERED: Int = 1024

        private val LOG: System.Logger = System.getLogger("kibicomputers.kernel")
    }
}

/**
 * コンポーネントを待機点に変換できなかった（[ComponentProgram.waitPointRejection]）ときの、理由のログ（#757）。
 *
 * 変換できなかったコンポーネントは `BLOCKING` で動き、待つ間スレッドを持つ。スレッドは `maxBlockingProcesses`（1 台・サーバー全体。ADR 0263）に
 * 数えられ、超えると子の `spawn` が `NFILE` になるので、運用の側から原因（どの core モジュールの、どの関数か）を追えるように出す。
 * 同じコンポーネントでは 1 回だけ出す（[ComponentOnceLog]）
 */
internal object WaitPointRejectionLog : ComponentOnceLog() {
    override fun message(key: String, reason: String): String =
        "WASM コンポーネント $key は待機点に変換できないので、待つ間スレッドを持つ（`maxBlockingProcesses` に数える）: $reason"
}

/**
 * コンポーネントの core モジュールで燃料切れを待ち点にできなかった（段2に落ちた。[ComponentProgram.fuelYieldRejection]）ときの、
 * 理由のログ（#803。p1 の `pro.ikili.kibicomputers.core.kernel.process.FuelYieldRejectionLog` に当たる）。
 * [WaitPointRejectionLog]（`BLOCKING` に落ちた理由）とは別に出す。
 *
 * 段2に落ちたモジュールの中で燃料が尽きると、待機点型のプロセスでも燃料を待つ間スレッドを持つ（`maxBlockingProcesses` には数えない）ので、
 * 運用の側から原因を追えるように出す。同じコンポーネントでは 1 回だけ出す（[ComponentOnceLog]）
 */
internal object FuelYieldRejectionLog : ComponentOnceLog() {
    override fun message(key: String, reason: String): String =
        "WASM コンポーネント $key には燃料切れを待ち点にできない core モジュールがあるので、その中で燃料を待つ間はスレッドを持つ: $reason"
}
