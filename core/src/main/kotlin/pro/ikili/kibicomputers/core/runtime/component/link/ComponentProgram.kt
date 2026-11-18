package pro.ikili.kibicomputers.core.runtime.component.link

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
 */
internal class ComponentProgram private constructor(
    val plan: ComponentPlan,
    val metered: Boolean,
    private val hash: ByteArray,
    val cacheWeight: Long,
    val waitPoints: Boolean,
    val waitPointRejection: String?,
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
         *    かけられなければ、どのモジュールにもかけない（理由は [waitPointRejection]。`BLOCKING` で動く）
         * 5. ランタイムに読み込ませる（パース・検証・コンパイル。各モジュール 1 回だけ）
         *
         * 燃料の挿入と変換の順は p1 と同じ（[pro.ikili.kibicomputers.core.runtime.Instrumentation]。#341）。燃料切れは待ち点にしない
         * （燃料切れの巻き戻しは、呼んだインスタンス 1 つの状態しか変えないため。燃料を待つ間はスレッドを持つ。ADR 0757）。
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
        ): ComponentProgram {
            ComponentLoader.warmUp()
            val deadline = LoadDeadline(limits.moduleLimits.timeLimitNanos)
            val loaded = ComponentLoader.load(bytes, limits, policy, deadline = deadline, validate = validate)
            val injected = ArrayList<ByteArray>()
            val byContent = HashMap<ByteBuffer, Int>()
            val moduleIndex = HashMap<ByteRange, Int>()
            for (range in loaded.coreModules) {
                val original = loaded.coreModuleBytes(range)
                val id = byContent.getOrPut(ByteBuffer.wrap(original)) {
                    injected += if (metered) {
                        FuelInjector.inject(original, sharedGlobal = true) { deadline.check("燃料の計測コードの挿入") }.bytes
                    } else {
                        original
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
            val finalBytes = if (asyncify) {
                val r = transform(injected, planned, deadline)
                rejection = r.second
                r.first ?: injected
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
            return ComponentProgram(plan, metered, loaded.sha256, weightOf(moduleBytes, plan), waitPoints, rejection)
        }

        /** 待機点への変換をかけた全モジュールのバイト列。かけられなければ `null` と理由 */
        private fun transform(injected: List<ByteArray>, planned: ComponentPlanner.Planned, deadline: LoadDeadline): Pair<List<ByteArray>?, String?> {
            val checkpoint = { deadline.check(STAGE_ASYNCIFY) }
            val prepared = injected.map { Asyncify.prepare(it, checkpoint) }
            val analysis = ComponentAsyncify.analyze(planned, prepared, checkpoint)
            analysis.rejection?.let { return null to it }
            val out = ArrayList<ByteArray>(injected.size)
            for ((i, p) in prepared.withIndex()) {
                out += try {
                    p.transform(analysis.waitImports[i], analysis.externalTypes, checkpoint = checkpoint).bytes
                } catch (e: AsyncifyUnsupported) {
                    return null to (e.message ?: "理由不明")
                }
            }
            return out to null
        }

        private const val STAGE_ASYNCIFY = "待機点への変換"
    }
}
