package pro.ikili.kibicomputers.core.runtime.component.link

import pro.ikili.kibicomputers.core.runtime.WasmExternKind
import pro.ikili.kibicomputers.core.runtime.WasmHostFunction
import pro.ikili.kibicomputers.core.runtime.WasmInstance
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmLinkException
import pro.ikili.kibicomputers.core.runtime.WasmMemory
import pro.ikili.kibicomputers.core.runtime.WasmSharedFuel
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonBudget
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonContext
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonRealloc
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonTraps
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonValue

/**
 * コンポーネントをインスタンス化する場所の口（#263）。カーネルならプロセスの口（上限・燃料・ホスト関数の入口の照合）を渡す。
 * テストでは既定のまま（何もしない）でよい
 */
internal interface LinkEnv {
    /** core インスタンスを作る設定に、場所が決めるもの（メモリ・テーブルの上限、燃料切れの処理）を足す */
    fun configure(builder: WasmInstanceConfig.Builder) {}

    /** canon lower で作るホストの関数（core の import）を包む（カーネルなら、呼んだインスタンスの照合） */
    fun wrapHost(function: WasmHostFunction): WasmHostFunction = function

    /** ホスト側の処理の中からゲストのコード（realloc・post-return）を呼ぶ（カーネルなら、止まる口を使えなくする。ADR 0073） */
    fun <T> callGuest(block: () -> T): T = block()

    /** core インスタンスを 1 つ作った（作った順に呼ぶ。インスタンス化が途中で失敗したら、作ったものは linker が閉じる） */
    fun onInstanceCreated(instance: WasmInstance) {}

    /** lift・lower で写した量の費用 [amount] を燃料から引く */
    fun chargeFuel(amount: Long) {}

    companion object {
        /** 何もしない場所 */
        val NONE: LinkEnv = object : LinkEnv {}
    }
}

/**
 * インスタンス化したコンポーネント（#263。ADR 0263）。[ComponentPlan] のとおりに core インスタンスを作り、canon の lift・lower で
 * つなぐ。1 つのプロセス（スレッド 1 本）からだけ使う（スレッドをまたいで共有しない）。
 *
 * Canonical ABI の約束（仕様の `definitions.py`）のうち、同期の呼び出しに要るものを守る:
 * - 呼ぶ側のコンポーネントのインスタンスの `may_leave` が下りている（realloc・post-return の中）ときの lower の呼び出しはトラップ
 * - realloc と post-return は `may_leave` を下ろして呼ぶ。post-return は、結果を呼ぶ側に渡し終えた（lower した・写した）後に呼ぶ
 * - 一度トラップしたら、それ以上は呼べない（以後の呼び出しはトラップ）
 *
 * コンポーネントどうしの呼び出し（lift した関数を lower した import）は、ゲストからゲストへの呼び出しなので [LinkEnv.callGuest] で包まない
 */
internal class LinkedComponent private constructor(
    val plan: ComponentPlan,
    private val env: LinkEnv,
) : AutoCloseable {
    private val instances = ArrayList<WasmInstance>(plan.steps.size)
    private val mayLeave = BooleanArray(plan.componentInstances) { true }
    private val memories = HashMap<CoreRef.Export, WasmMemory>()
    private var poisoned: String? = null
    private var closed = false

    /** 作った core インスタンス（作った順） */
    val coreInstances: List<WasmInstance> get() = instances

    companion object {
        /**
         * [plan] をインスタンス化する。core モジュールが燃料を共有する（[pro.ikili.kibicomputers.core.runtime.WasmModule.sharesFuel]）なら
         * [sharedFuel] が要る。core のモジュールの start 関数はここで動く（その中からホストの関数が呼ばれうる）
         *
         * @throws WasmLinkException core インスタンスを作れない（上限・型の違いなど）
         * @throws WasmTrapException start 関数がトラップした
         */
        @Throws(WasmLinkException::class, WasmTrapException::class)
        fun instantiate(plan: ComponentPlan, env: LinkEnv = LinkEnv.NONE, sharedFuel: WasmSharedFuel? = null): LinkedComponent {
            val c = LinkedComponent(plan, env)
            try {
                for (step in plan.steps) c.instantiateStep(step, sharedFuel)
            } catch (t: Throwable) {
                c.close()
                throw t
            }
            return c
        }
    }

    private fun instantiateStep(step: CoreStep, sharedFuel: WasmSharedFuel?) {
        val module = plan.modules[step.module]
        val builder = WasmInstanceConfig.builder()
        env.configure(builder)
        if (module.sharesFuel) builder.sharedFuel(requireNotNull(sharedFuel) { "燃料を共有するモジュールに、共有の残りを渡していない" })
        for ((imp, ref) in step.imports) {
            when (ref) {
                is CoreRef.Export -> builder.importExtern(imp.module, imp.name, extern(ref))
                is CoreRef.Lowered -> {
                    val spec = plan.lowers[ref.lower]
                    builder.hostFunction(imp.module, imp.name, ref.functionType, env.wrapHost { _, args -> callLowered(spec, args) })
                }
                is CoreRef.Trapping -> builder.hostFunction(imp.module, imp.name, ref.functionType, env.wrapHost { _, _ -> CanonTraps.trap(ref.reason) })
            }
        }
        val instance = module.instantiate(builder.build())
        instances += instance
        env.onInstanceCreated(instance)
    }

    private fun extern(ref: CoreRef.Export) = instanceOf(ref).extern(ref.name) ?: throw WasmLinkException("$ref を渡せない（ランタイムが対応していない）")

    private fun instanceOf(ref: CoreRef.Export): WasmInstance =
        instances.getOrNull(ref.step) ?: throw IllegalStateException("$ref はまだ作っていない（計画の誤り）")

    // ---- 外からの呼び出し ----

    /** 一番外の export [path]（インスタンスの export なら、インスタンスの名前・関数の名前の順）の関数。なければ `null` */
    fun function(vararg path: String): FuncRef? {
        var items: Map<String, ExportItem> = plan.exports
        for ((i, name) in path.withIndex()) {
            when (val item = items[name]) {
                is ExportItem.Func -> return if (i == path.size - 1) item.ref else null
                is ExportItem.Instance -> items = item.exports
                else -> return null
            }
        }
        return null
    }

    /**
     * export した関数 [func] を、ホストから呼ぶ（引数は値。結果のない関数は `null`）。ホストの関数の中からは呼ばない
     *
     * @throws WasmTrapException ゲストがトラップした（以後、このコンポーネントは呼べない）
     */
    @Throws(WasmTrapException::class)
    fun call(func: FuncRef, args: List<CanonValue>): CanonValue? = guarded {
        val budget = CanonBudget()
        val r = callFunc(func, args, budget) { it }
        env.chargeFuel(cost(budget))
        r
    }

    // ---- 呼び出しの中身 ----

    /** ゲストの core が canon lower した import を呼んだ */
    private fun callLowered(spec: LowerSpec, args: LongArray): LongArray = guarded {
        if (!mayLeave[spec.instance]) CanonTraps.trap("realloc・post-return の中から import を呼んだ（may_leave）")
        val budget = CanonBudget()
        val cx = context(spec.options, spec.instance, budget)
        val values = spec.function.liftParams(cx, args)
        val out = callFunc(spec.callee, values, budget) { spec.function.lowerResult(cx, it, args) }
        env.chargeFuel(cost(budget))
        out
    }

    /** [func] を呼び、結果を [onResult] に渡す（lift した関数なら、その後に post-return を呼ぶ） */
    private inline fun <T> callFunc(func: FuncRef, args: List<CanonValue>, budget: CanonBudget, onResult: (CanonValue?) -> T): T = when (func) {
        is FuncRef.Host -> onResult(func.host.body.call(ComponentCall(this, env, func.interfaceName, func.name), args))
        is FuncRef.Lifted -> {
            val spec = plan.lifts[func.lift]
            val cx = context(spec.options, spec.instance, budget)
            val coreArgs = spec.function.lowerParams(cx, args)
            val results = callCore(spec.core, coreArgs)
            val r = onResult(spec.function.liftResult(cx, results))
            spec.options.postReturn?.let { pr -> withoutLeave(spec.instance) { callCore(pr, results) } }
            r
        }
    }

    /** core の関数 [ref] を呼ぶ */
    private fun callCore(ref: CoreRef, args: LongArray): LongArray = when (ref) {
        is CoreRef.Export -> instanceOf(ref).call(ref.name, *args)
        is CoreRef.Lowered -> callLowered(plan.lowers[ref.lower], args)
        is CoreRef.Trapping -> CanonTraps.trap(ref.reason)
    }

    /** ホスト側の処理の中から、`may_leave` を下ろしてゲストの [block] を呼ぶ */
    private inline fun <T> withoutLeave(instance: Int, crossinline block: () -> T): T {
        val previous = mayLeave[instance]
        mayLeave[instance] = false
        try {
            return env.callGuest { block() }
        } finally {
            mayLeave[instance] = previous
        }
    }

    private fun context(options: CanonBinding, instance: Int, budget: CanonBudget): CanonContext {
        val memory = options.memory?.let { memoryOf(it) }
        val realloc = options.realloc?.let { ref ->
            CanonRealloc { oldPtr, oldSize, align, newSize ->
                withoutLeave(instance) {
                    callCore(ref, longArrayOf(oldPtr.toLong(), oldSize.toLong(), align.toLong(), newSize.toLong()))[0].toInt()
                }
            }
        }
        return CanonContext(memory, options.encoding, realloc, budget = budget)
    }

    private fun memoryOf(ref: CoreRef): WasmMemory {
        if (ref !is CoreRef.Export || ref.kind != WasmExternKind.MEMORY) CanonTraps.trap("canon の memory が線形メモリでない（$ref）")
        return memories.getOrPut(ref) { instanceOf(ref).exportedMemory(ref.name) ?: CanonTraps.trap("$ref の線形メモリを引けない") }
    }

    /**
     * 写した量の燃料（#263 で決めた仮の値。確定は #251）。値 1 つで 1、写したバイト 8 つで 1。
     * 命令の燃料（[pro.ikili.kibicomputers.core.runtime.fuel.FuelInjector]）で、ゲストが同じ量を自分で写すときに近い値にする
     */
    private fun cost(budget: CanonBudget): Long = budget.usedValues + (budget.usedBytes ushr 3)

    /** トラップしたら、それ以上は呼ばせない（仕様では、トラップしたストアは使えなくなる） */
    private inline fun <T> guarded(block: () -> T): T {
        poisoned?.let { CanonTraps.trap("前にトラップしたコンポーネントは呼べない（$it）") }
        check(!closed) { "閉じたコンポーネントを呼んだ" }
        try {
            return block()
        } catch (t: Throwable) {
            if (poisoned == null) poisoned = t.message ?: t.javaClass.simpleName
            throw t
        }
    }

    /** core インスタンスをすべて閉じる。2 回呼んでもよい */
    override fun close() {
        if (closed) return
        closed = true
        memories.clear()
        for (i in instances.indices.reversed()) {
            try {
                instances[i].close()
            } catch (_: Exception) {
                // 閉じる失敗は無視する（ほかのインスタンスは閉じる）
            }
        }
    }
}
