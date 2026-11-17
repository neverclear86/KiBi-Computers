package pro.ikili.kibicomputers.core.runtime.component.link

import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.WasmModule
import pro.ikili.kibicomputers.core.runtime.WasmRuntime
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
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
 */
internal class ComponentProgram private constructor(
    val plan: ComponentPlan,
    val metered: Boolean,
    private val hash: ByteArray,
    val cacheWeight: Long,
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
         * [bytes] を読み込む。順番は「コンポーネントの検証（[ComponentLoader]）→ 中の core モジュールを、同じ中身は 1 つにまとめて
         * ランタイムに読み込ませる（[metered] なら燃料の計測コードを共有の形で挿入してから）→ リンクの計画（[ComponentPlanner]）」。
         * 全体で 1 つの時間の上限（[ComponentLimits.moduleLimits] の時間）を守る。
         *
         * core モジュールは、コンポーネントの中の元のバイト列をそのまま Endive に渡す（挿入するときも、挿入の前に Endive のパーサーが
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
        ): ComponentProgram {
            ComponentLoader.warmUp()
            val deadline = LoadDeadline(limits.moduleLimits.timeLimitNanos)
            val loaded = ComponentLoader.load(bytes, limits, policy, deadline = deadline, validate = validate)
            val modules = ArrayList<WasmModule>()
            val byContent = HashMap<ByteBuffer, Int>()
            val moduleIndex = HashMap<ByteRange, Int>()
            var moduleBytes = 0L
            for (range in loaded.coreModules) {
                val original = loaded.coreModuleBytes(range)
                val id = byContent.getOrPut(ByteBuffer.wrap(original)) {
                    val (module, size) = loadModule(runtime, original, metered, deadline)
                    modules += module
                    moduleBytes += size
                    modules.size - 1
                }
                moduleIndex[range] = id
            }
            deadline.check("core モジュールの読み込み")
            val plan = ComponentPlanner.plan(loaded, modules, moduleIndex, host, deadline)
            deadline.check("コンポーネントのリンク")
            return ComponentProgram(plan, metered, loaded.sha256, weightOf(moduleBytes, plan))
        }

        /** core モジュールを 1 つ読み込む。ランタイムに渡したバイト数（挿入の後）と組にして返す */
        private fun loadModule(runtime: WasmRuntime, original: ByteArray, metered: Boolean, deadline: LoadDeadline): Pair<WasmModule, Long> {
            val bytes = if (metered) {
                FuelInjector.inject(original, sharedGlobal = true) { deadline.check("燃料の計測コードの挿入") }.bytes
            } else {
                original
            }
            val module = runtime.load(bytes)
            deadline.check("core モジュールのパース・検証・コンパイル")
            return module to bytes.size.toLong()
        }
    }
}
