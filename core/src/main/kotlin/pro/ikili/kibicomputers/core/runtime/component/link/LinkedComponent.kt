package pro.ikili.kibicomputers.core.runtime.component.link

import pro.ikili.kibicomputers.core.runtime.WasmAsyncStack
import pro.ikili.kibicomputers.core.runtime.WasmAsyncState
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
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonContext.Companion.u32
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonLimits
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonRealloc
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonResources
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonTraps
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonValue

/**
 * コンポーネントをインスタンス化する場所の口（#263）。カーネルならプロセスの口（上限・燃料・ホスト関数の入口の照合）を渡す。
 * テストでは既定のまま（何もしない）でよい
 */
internal interface LinkEnv {
    /**
     * core インスタンスを作る設定に、場所が決めるもの（メモリ・テーブルの上限、燃料切れの処理）を足す。
     *
     * 口の約束: core インスタンス 1 つにつき、そのインスタンスを作る直前にちょうど 1 回呼ぶ（作らない段では呼ばない。同じインスタンスに 2 回呼ばない）。
     * 作ったら [onInstanceCreated] が続く（start 関数のトラップなどで作り終えられなければ続かない）。
     * 場所は、この呼び出しを「これから core インスタンスを 1 つ作る」知らせとして使ってよい。カーネルは、ここで core インスタンスの固定費を RAM に
     * 数える（#270）ので、[pro.ikili.kibicomputers.core.runtime.WasmLinkException]（RAM が足りない）や
     * `ProcessKilledException`（止められた）を投げることがある。投げたら、そのインスタンスは作らない
     */
    fun configure(builder: WasmInstanceConfig.Builder) {}

    /** canon lower で作るホストの関数（core の import）を包む（カーネルなら、呼んだインスタンスの照合） */
    fun wrapHost(function: WasmHostFunction): WasmHostFunction = function

    /** ホスト側の処理の中からゲストのコード（realloc・post-return）を呼ぶ（カーネルなら、止まる口を使えなくする。ADR 0073） */
    fun <T> callGuest(block: () -> T): T = block()

    /** core インスタンスを 1 つ作った（作った順に呼ぶ。インスタンス化が途中で失敗したら、作ったものは linker が閉じる） */
    fun onInstanceCreated(instance: WasmInstance) {}

    /** lift・lower で写した量の費用 [amount] を燃料から引く */
    fun chargeFuel(amount: Long) {}

    /**
     * 巻き直し（[LinkedComponent.beginRewind]）が、巻き戻したホストの関数の呼び出しに着いた（#757）。状態はもう `NORMAL` に戻してある。
     * そのホストの関数を呼ぶ直前に呼ぶ
     */
    fun onRewound() {}

    companion object {
        /** 何もしない場所 */
        val NONE: LinkEnv = object : LinkEnv {}
    }
}

/**
 * インスタンス化したコンポーネント（#263。ADR 0263）。[ComponentPlan] のとおりに core インスタンスを作り、canon の lift・lower で
 * つなぐ。1 つのプロセスからだけ、同時に 1 本のスレッドで使う。待機点型（#757）では step ごとに別のワーカーのスレッドで動くが、
 * step の受け渡しはカーネルロックを通る（前の step の書き込みが次の step から見える）ので、中の変数はただの変数のままでよい。
 *
 * 待機点（#757。ADR 0757）: 中の core モジュールすべてに待機点への変換をかけた計画（[asyncified]）では、ホストの関数が [requestUnwind] で
 * 巻き戻せる。core インスタンスの状態のグローバルはインスタンスごとにあるので、巻き戻し・巻き直し・着いたときに全インスタンスをそろえて
 * 書き換え、退避した値は全インスタンスで 1 つのスタック（[instantiate] の `asyncStack`）に積む（内側から積み、外側から取り出すので、
 * モジュールをまたいでも順番が合う）。巻き戻すと、ホストの関数の結果は lower せず（ダミーの 0 を返す）、外からの [call] は結果を
 * lift せず post-return も呼ばずに `null` で戻る（[unwound]）。再開は [beginRewind] の後に同じ関数を [call] で呼び直す。
 * 巻き戻せるのは、外からの [call] の 1 段の下で、ホストの関数を core から直接呼んだときだけ（[canUnwind]）。
 *
 * Canonical ABI の約束（仕様の `definitions.py`）のうち、同期の呼び出しに要るものを守る:
 * - 呼ぶ側のコンポーネントのインスタンスの `may_leave` が下りている（realloc・post-return の中）ときの lower の呼び出しはトラップ
 * - realloc と post-return は `may_leave` を下ろして呼ぶ。post-return は、結果を呼ぶ側に渡し終えた（lower した・写した）後に呼ぶ
 * - 一度トラップしたら、それ以上は呼べない（以後の呼び出しはトラップ）
 *
 * コンポーネントどうしの呼び出し（lift した関数を lower した import）は、ゲストからゲストへの呼び出しなので [LinkEnv.callGuest] で包まない。
 *
 * ホストからの [call] は入れ子にできない（#691）。ホストの関数（[HostFunctionBody]）は必ず [call] かインスタンス化の下で動くので、
 * その中から [call] を呼ぶと [IllegalStateException] になる。ホストの関数の中から呼べると、core の関数が [LinkEnv.callGuest] で
 * 包まれずに動き、ホスト関数の途中で止まる口を使えてしまう（ADR 0073）。これは安全のための独自の規則で、仕様の再入の検査ではない。
 * ゲストからゲストへの再入（A→B→A…、donut の相互再帰、dtor の再入）は、固定した仕様（dd1fefc）のとおりトラップにしない（#705。ADR 0265）
 *
 * 一時的な値（#753。ADR 0264「予算」）: lift した引数（[CanonValue] のリスト）は、callee の線形メモリへ lower し終えたら、callee が動いている間は
 * 持たない（[LiftedArgs] で参照を切る）。なので、ゲストからゲストへの呼び出しを深くしても（A→B→C…、再入する A→B→A… でも）、ホストが
 * 同時に持つ一時的な値は「いま lift・lower している 1 段」の分で頭打ちになる。守りとして、外側の段がまだ持っている量（[heldBytes]・[heldValues]）を
 * 入れ子の段の予算から引く（値を持ったまま下の段へ降りる経路ができても、上限が深さに比例しない）。
 * 一時的な値は RAM（ADR 0048）には数えない（解放しても減らない最高値の勘定なので、大きな呼び出し 1 回がプロセスの終わりまで RAM を引いてしまう）
 *
 * resource（#265。ADR 0265）: ハンドル表はコンポーネントのインスタンスごとに 1 つ（[ResourceTable]。使ったインスタンスの分だけ作る）。
 * borrow の範囲は、同期の呼び出しのスタック（仕様の Task の番号と borrow の数、Subtask の lenders）で持ち、引数に borrow を含む
 * 関数の呼び出しのときだけ積む。閉じるとき（[close]）は、表に残ったハンドルの dtor を呼ばない
 */
internal class LinkedComponent private constructor(
    val plan: ComponentPlan,
    val env: LinkEnv,
) : AutoCloseable {
    private val instances = ArrayList<WasmInstance>(plan.steps.size)
    private val mayLeave = BooleanArray(plan.componentInstances) { true }
    private val memories = HashMap<CoreRef.Export, WasmMemory>()
    private var poisoned: String? = null
    private var closed = false

    /** [call] かインスタンス化の途中（プロセスのスレッドだけが読み書きする。#691） */
    private var entered = false

    // ---- resource（#265） ----

    /** コンポーネントのインスタンスごとのハンドル表（使うまで作らない） */
    private val tables = arrayOfNulls<ResourceTable>(plan.componentInstances)

    /** インスタンスごとの lift・lower の口（使うまで作らない。呼び出しのたびには作らない） */
    private val resourceViews = arrayOfNulls<InstanceResources>(plan.componentInstances)

    /** 全インスタンスの表にあるハンドルの数（[CanonLimits.MAX_HANDLES] で抑える） */
    private var liveHandles = 0

    private val taskIds = TaskIds()

    /** 同期の呼び出しのスタックの Task（borrow を受ける関数のものだけ）: 番号と、受けた borrow のうちまだ drop していない数 */
    private var taskStack = LongArray(4)
    private var taskBorrows = IntArray(4)
    private var taskDepth = 0

    /** Subtask の lenders（貸したハンドルの、インスタンスの番号とハンドルの番号の組を積む） */
    private var lenderInstances = IntArray(4)
    private var lenderHandles = IntArray(4)
    private var lenderTop = 0

    // ---- 一時的な値（#753） ----

    /** 外側の段が lift してまだ持っている（callee へ lower し終えていない）バイト数（プロセスのスレッドだけが読み書きする） */
    internal var heldBytes: Long = 0
        private set

    /** [heldBytes] と同じく、値の数 */
    internal var heldValues: Long = 0
        private set

    /** [heldBytes] の最高値（テスト用） */
    internal var peakHeldBytes: Long = 0
        private set

    /** 作った core インスタンス（作った順） */
    val coreInstances: List<WasmInstance> get() = instances

    // ---- 待機点（#757） ----

    /** 中の core モジュールすべてに待機点への変換をかけたか */
    val asyncified: Boolean = plan.modules.isNotEmpty() && plan.modules.all { it.isAsyncified }

    init {
        check(asyncified || plan.modules.none { it.isAsyncified }) { "待機点への変換をかけたモジュールとかけていないモジュールが混ざっている" }
    }

    /** ホストの関数が巻き戻しを求めてから、外からの [call] が戻るまで */
    private var unwinding = false

    /** [beginRewind] から、巻き戻したホストの関数の呼び出しに着くまで */
    private var rewinding = false

    /** 外からの [call] が巻き戻して戻った（次の [call] か [beginRewind] で下ろす） */
    var unwound: Boolean = false
        private set

    /** lift した関数の呼び出しの入れ子の深さ（外からの [call] が 1。ゲストからゲストへの呼び出しで増える） */
    private var liftDepth = 0

    /** 退避した値の置き場所を渡されなかったときに使う、この中だけのスタック（カーネルの外。テストなど） */
    private class LocalStack : WasmAsyncStack {
        private val values = ArrayList<Long>()

        override fun push(value: Long) {
            values += value
        }

        override fun pop(): Long = values.removeAt(values.size - 1)
    }

    companion object {
        /**
         * [plan] をインスタンス化する。core モジュールが燃料を共有する（[pro.ikili.kibicomputers.core.runtime.WasmModule.sharesFuel]）なら
         * [sharedFuel] が要る。待機点への変換をかけた計画（[asyncified]）なら、全インスタンスが退避した値を [asyncStack] に積む
         * （`null` なら、この中だけのスタックを使う。#757）。core のモジュールの start 関数はここで動く（その中からホストの関数が呼ばれうる）
         *
         * @throws WasmLinkException core インスタンスを作れない（上限・型の違いなど）
         * @throws WasmTrapException start 関数がトラップした
         */
        @Throws(WasmLinkException::class, WasmTrapException::class)
        fun instantiate(
            plan: ComponentPlan,
            env: LinkEnv = LinkEnv.NONE,
            sharedFuel: WasmSharedFuel? = null,
            asyncStack: WasmAsyncStack? = null,
        ): LinkedComponent {
            val c = LinkedComponent(plan, env)
            val stack = if (c.asyncified) asyncStack ?: LocalStack() else null
            c.entered = true
            try {
                for (step in plan.steps) c.instantiateStep(step, sharedFuel, stack)
            } catch (t: Throwable) {
                c.close()
                throw t
            } finally {
                c.entered = false
            }
            return c
        }

        private val EMPTY = LongArray(0)
    }

    private fun instantiateStep(step: CoreStep, sharedFuel: WasmSharedFuel?, asyncStack: WasmAsyncStack?) {
        val module = plan.modules[step.module]
        val builder = WasmInstanceConfig.builder()
        env.configure(builder)
        if (module.sharesFuel) builder.sharedFuel(requireNotNull(sharedFuel) { "燃料を共有するモジュールに、共有の残りを渡していない" })
        if (module.isAsyncified) builder.asyncStack(asyncStack)
        for ((imp, ref) in step.imports) {
            when (ref) {
                is CoreRef.Export -> builder.importExtern(imp.module, imp.name, extern(ref))
                is CoreRef.Lowered -> {
                    val spec = plan.lowers[ref.lower]
                    val results = ref.functionType.results.size
                    builder.hostFunction(imp.module, imp.name, ref.functionType, env.wrapHost { caller, args -> callLowered(spec, args, results, caller.memory) })
                }
                is CoreRef.ResourceOp ->
                    builder.hostFunction(imp.module, imp.name, ref.functionType, env.wrapHost { _, args -> guarded { resourceOp(ref, args) } })
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
     * export した関数 [func] を、ホストから呼ぶ（引数は値。結果のない関数は `null`）。
     *
     * ホストの関数の中・インスタンス化の途中（start 関数から呼ばれたホストの関数）からは呼べない（[IllegalStateException]。#691）。
     * この検査は呼び出しの前に行うので、断った呼び出しはこのコンポーネントを壊さない（例外が外側の呼び出しまで伝われば、外側が壊す）。
     *
     * own・borrow の値は rep（[HostFunctionBody] と同じ）。引数に、ゲストが実装した resource の own・borrow は渡せない
     * （ホストはゲストの dtor を呼べず、rep を偽れるため。[IllegalArgumentException]。コンポーネントは使えなくならない）。
     * 結果の own は rep で受け、所有はホストに移る（ゲストが実装した resource なら、ホストからは dtor を呼べないので、
     * 捨てても dtor は呼ばれない。仕様の埋め込みと同じ）
     *
     * @throws WasmTrapException ゲストがトラップした（以後、このコンポーネントは呼べない）
     * @throws IllegalStateException ホストの関数の中から呼んだ、または閉じたコンポーネントを呼んだ
     * @throws IllegalArgumentException 引数に、ゲストが実装した resource の own・borrow を渡した
     */
    @Throws(WasmTrapException::class)
    fun call(func: FuncRef, args: List<CanonValue>): CanonValue? {
        check(!entered) { "ホストの関数の中（またはインスタンス化の途中）から LinkedComponent.call を呼んだ" }
        check(!unwinding) { "巻き戻しの途中で call を呼んだ（内部の誤り）" }
        // 巻き直しでは、引数を callee の線形メモリへ lower しない（realloc を巻き直しの途中で動かさない）
        if (rewinding) require(func.function.params.isEmpty()) { "巻き直しで呼べるのは引数のない関数だけ" }
        unwound = false
        for (r in func.function.paramResources) {
            val info = plan.resource(r)
            require(info.impl !is ResourceImpl.Guest) { "ゲストが実装した resource（${info.canonical}）の own・borrow をホストから渡した" }
        }
        entered = true
        try {
            return guarded {
                val budget = CanonBudget()
                // ホストが渡したリストはホストのもの（参照を切るだけで、中身は消さない）
                val r = callFunc(func, LiftedArgs(args, heldBytes, heldValues), budget) { it }
                env.chargeFuel(cost(budget))
                if (unwinding) {
                    // 巻き戻して戻った: 状態をそろえて NORMAL に戻す（待っている間は常に NORMAL）
                    unwinding = false
                    setAsyncState(WasmAsyncState.NORMAL)
                    unwound = true
                    null
                } else {
                    check(!rewinding) { "巻き直しが止まった呼び出しに着かずに戻った" }
                    r
                }
            }
        } finally {
            entered = false
        }
    }

    /**
     * 今のホストの関数の呼び出しで巻き戻せるか（#757）: 変換した計画で、外からの [call] の 1 段の下で、core から直接呼ばれた
     * ホストの関数の中（ゲストからゲストへの呼び出しの下でない。インスタンス化の途中でない。realloc・post-return の中でない）
     */
    fun canUnwind(): Boolean = asyncified && !unwinding && !rewinding && liftDepth == 1 && mayLeave.all { it }

    /**
     * 巻き戻しを求める（#757。ホストの関数の中から）。ホストの関数はそのまま戻り（戻り値は捨てる）、core のコードが退避しながら
     * 外からの [call] まで戻る
     */
    fun requestUnwind() {
        check(canUnwind()) { "巻き戻せないところで巻き戻しを求めた" }
        unwinding = true
        setAsyncState(WasmAsyncState.UNWINDING)
    }

    /** 巻き直しを始める（#757）。この後に、巻き戻した外からの呼び出しと同じ関数を [call] で呼び直す */
    fun beginRewind() {
        check(asyncified && !unwinding && !rewinding && !entered) { "巻き直しを始められない" }
        check(unwound) { "巻き戻していないのに巻き直した" }
        unwound = false
        rewinding = true
        setAsyncState(WasmAsyncState.REWINDING)
    }

    private fun setAsyncState(state: WasmAsyncState) {
        for (i in instances) i.asyncState = state
    }

    // ---- 呼び出しの中身 ----

    /**
     * ゲストの core が canon lower した import を呼んだ。[callerMemory] は、その lower を import した core インスタンスの 0 番の線形メモリ
     * （ランタイムが import を結んだインスタンスで、export し直して別の core インスタンスが呼んでも変わらない。ホストの関数の [ComponentCall.callerMemory] に渡す。#777）
     */
    private fun callLowered(spec: LowerSpec, args: LongArray, coreResults: Int, callerMemory: WasmMemory?): LongArray = guarded {
        if (!mayLeave[spec.instance]) CanonTraps.trap("realloc・post-return の中から import を呼んだ（may_leave）")
        if (rewinding) {
            // 巻き直しで、巻き戻したホストの関数の呼び出しに着いた（変換したコードは、止まった呼び出しのほかは呼ばない。#757）
            check(spec.callee is FuncRef.Host) { "巻き直しがホストの関数でない呼び出しに着いた" }
            rewinding = false
            setAsyncState(WasmAsyncState.NORMAL)
            env.onRewound()
        }
        // 外側の段がまだ持っている分を、この段の予算から引く（#753。深くしても、同時に持つ量が 1 回の予算を超えない）
        val outerBytes = heldBytes
        val outerValues = heldValues
        val budget = CanonBudget(CanonLimits.DEFAULT_MAX_BYTES - outerBytes, CanonLimits.DEFAULT_MAX_VALUES - outerValues)
        val cx = context(spec.options, spec.instance, budget)
        // 仕様の Subtask: 引数の borrow の lift で貸したハンドルを、呼び出しが済んだら（post-return の後）戻す（deliver_resolve）
        val lenders = lenderTop
        val out = try {
            // lift した値は名前のある変数に置かない（callee が動いている間、このフレームから参照を残さない。#753）
            callFunc(spec.callee, liftArgs(spec, cx, args, outerBytes, outerValues), budget, callerMemory) {
                // 巻き戻すなら、結果を lower しない（retptr に書かず、realloc も呼ばない）。core の関数の型どおりの数の 0 を返す（捨てられる）
                if (unwinding) LongArray(coreResults) else spec.function.lowerResult(cx, it, args)
            }
        } finally {
            if (lenderTop != lenders) returnLenders(lenders)
            heldBytes = outerBytes
            heldValues = outerValues
        }
        env.chargeFuel(cost(budget))
        out
    }

    /** [spec] の引数を lift し、持っている量を [heldBytes]・[heldValues] に足して包む */
    private fun liftArgs(spec: LowerSpec, cx: CanonContext, args: LongArray, outerBytes: Long, outerValues: Long): LiftedArgs {
        val lifted = LiftedArgs(spec.function.liftParams(cx, args), outerBytes, outerValues)
        heldBytes = outerBytes + cx.budget.usedBytes
        heldValues = outerValues + cx.budget.usedValues
        if (heldBytes > peakHeldBytes) peakHeldBytes = heldBytes
        return lifted
    }

    /**
     * lift した引数（#753）。callee へ渡すときに [take] で参照を切る（callee が lift した関数なら、lower し終えた後は誰も持たない）。
     * [outerBytes]・[outerValues] は、この段より外側が持っている量（lower し終えたら [heldBytes]・[heldValues] をここへ戻す）
     */
    private class LiftedArgs(private var values: List<CanonValue>?, val outerBytes: Long, val outerValues: Long) {
        fun take(): List<CanonValue> {
            val v = checkNotNull(values) { "lift した引数を 2 回渡した（内部の誤り）" }
            values = null
            return v
        }
    }

    /**
     * [func] を呼び、結果を [onResult] に渡す（lift した関数なら、その後に post-return を呼ぶ）。
     * [callerMemory] は、ホストの関数なら [ComponentCall.callerMemory] に渡す（lift した関数には渡さない）
     */
    private inline fun <T> callFunc(
        func: FuncRef,
        args: LiftedArgs,
        budget: CanonBudget,
        callerMemory: WasmMemory? = null,
        onResult: (CanonValue?) -> T,
    ): T = when (func) {
        // ホストの関数は、ホストの処理の間ずっと値を持つ（ホストの関数の中からはゲストへ入れないので、入れ子にはならない。#691）
        is FuncRef.Host -> onResult(func.host.body.call(ComponentCall(this, env, func.interfaceName, func.name, callerMemory), args.take()))
        is FuncRef.Lifted -> {
            val spec = plan.lifts[func.lift]
            val cx = context(spec.options, spec.instance, budget)
            // 仕様の Task: 引数に borrow を受ける関数なら、borrow の範囲を積む（ない関数は何も積まない）
            val task = spec.function.paramsHaveBorrow
            if (task) pushTask()
            val results: LongArray
            val value: CanonValue?
            liftDepth++
            try {
                val coreArgs = spec.function.lowerParams(cx, args.take())
                // callee の線形メモリへ写し終えた: この段はもう値を持たない（#753）
                heldBytes = args.outerBytes
                heldValues = args.outerValues
                results = callCore(spec.core, coreArgs)
                if (unwinding) {
                    // 巻き戻して戻った（#757。巻き戻せるのは外からの call の 1 段だけ）: 結果を lift せず、post-return も呼ばない
                    check(liftDepth == 1) { "ゲストからゲストへの呼び出しの下で巻き戻した（内部の誤り）" }
                    value = null
                } else {
                    value = spec.function.liftResult(cx, results)
                    // Task.return_: 受けた borrow をすべて drop してから返る。呼ぶ側への lower（呼ぶ側の realloc が動く）より前に確かめる
                    if (task && taskBorrows[taskDepth - 1] > 0) CanonTraps.trap("borrow のハンドルを drop せずに返った")
                }
            } finally {
                liftDepth--
                if (task) taskDepth--
            }
            val r = onResult(value)
            if (!unwinding) spec.options.postReturn?.let { pr -> withoutLeave(spec.instance) { callCore(pr, results) } }
            r
        }
    }

    /** core の関数 [ref] を呼ぶ */
    private fun callCore(ref: CoreRef, args: LongArray): LongArray = when (ref) {
        is CoreRef.Export -> instanceOf(ref).call(ref.name, *args)
        // lift した関数の中身・realloc・post-return として呼ぶ lower は、呼び手の core インスタンスがない（#777）
        is CoreRef.Lowered -> callLowered(plan.lowers[ref.lower], args, ref.functionType.results.size, null)
        is CoreRef.ResourceOp -> resourceOp(ref, args)
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
        return CanonContext(memory, options.encoding, realloc, resourcesOf(instance), budget)
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

    // ---- resource（#265。ADR 0265。仕様の definitions.py のとおり） ----

    private fun tableOf(instance: Int): ResourceTable = tables[instance] ?: ResourceTable().also { tables[instance] = it }

    /** 表を作っていなければ、どの番号もない */
    private fun existing(instance: Int, handle: Int): ResourceTable =
        tables[instance] ?: CanonTraps.trap("ハンドル表にない番号 ${Integer.toUnsignedString(handle)}")

    private fun resourcesOf(instance: Int): CanonResources =
        resourceViews[instance] ?: InstanceResources(instance).also { resourceViews[instance] = it }

    /** 正規の resource の番号 */
    private fun canonical(resource: Int): Int = plan.resource(resource).canonical

    private fun reserveHandle() {
        if (liveHandles >= CanonLimits.MAX_HANDLES) CanonTraps.trap("ハンドルの数が上限（${CanonLimits.MAX_HANDLES}）を超えた")
    }

    private fun addOwn(instance: Int, resource: Int, rep: Int): Int {
        reserveHandle()
        val h = tableOf(instance).addOwn(canonical(resource), rep)
        liveHandles++
        return h
    }

    /** [table] の [handle] が、型 [resource] の resource のハンドルか確かめる（違えばトラップ） */
    private fun checkType(table: ResourceTable, handle: Int, resource: Int) {
        if (table.resource(handle) != canonical(resource)) CanonTraps.trap("ハンドル ${Integer.toUnsignedString(handle)} の resource の型が違う")
    }

    private fun removeHandle(table: ResourceTable, handle: Int) {
        table.remove(handle)
        liveHandles--
    }

    private fun pushTask() {
        if (taskDepth == taskStack.size) {
            taskStack = taskStack.copyOf(taskDepth * 2)
            taskBorrows = taskBorrows.copyOf(taskDepth * 2)
        }
        taskStack[taskDepth] = taskIds.take()
        taskBorrows[taskDepth] = 0
        taskDepth++
    }

    /** 番号 [task] の Task の、スタックの位置（同期なので深さは小さい。上から探す） */
    private fun taskIndex(task: Long): Int {
        for (i in taskDepth - 1 downTo 0) if (taskStack[i] == task) return i
        throw IllegalStateException("borrow の範囲の task $task がスタックにない（内部の誤り）")
    }

    private fun pushLender(instance: Int, handle: Int) {
        if (lenderTop == lenderHandles.size) {
            lenderInstances = lenderInstances.copyOf(lenderTop * 2)
            lenderHandles = lenderHandles.copyOf(lenderTop * 2)
        }
        lenderInstances[lenderTop] = instance
        lenderHandles[lenderTop] = handle
        lenderTop++
    }

    /** [mark] より上に積んだ lenders の貸し出しを戻す（仕様の `Subtask.deliver_resolve`） */
    private fun returnLenders(mark: Int) {
        while (lenderTop > mark) {
            lenderTop--
            tables[lenderInstances[lenderTop]]?.lend(lenderHandles[lenderTop], -1)
        }
    }

    /** canon `resource.new`・`rep`・`drop`（new と drop は may_leave を見る。rep は見ない） */
    private fun resourceOp(ref: CoreRef.ResourceOp, args: LongArray): LongArray {
        val arg = args[0].toInt()
        return when (ref.op) {
            CoreRef.ResourceOp.Op.NEW -> {
                if (!mayLeave[ref.instance]) CanonTraps.trap("realloc・post-return の中から resource.new を呼んだ（may_leave）")
                longArrayOf(u32(addOwn(ref.instance, ref.resource, arg)))
            }
            CoreRef.ResourceOp.Op.REP -> {
                val t = existing(ref.instance, arg)
                checkType(t, arg, ref.resource)
                longArrayOf(u32(t.rep(arg)))
            }
            CoreRef.ResourceOp.Op.DROP -> {
                if (!mayLeave[ref.instance]) CanonTraps.trap("realloc・post-return の中から resource.drop を呼んだ（may_leave）")
                drop(ref, arg)
                EMPTY
            }
        }
    }

    private fun drop(ref: CoreRef.ResourceOp, handle: Int) {
        val t = existing(ref.instance, handle)
        checkType(t, handle, ref.resource)
        if (t.lends(handle) != 0) CanonTraps.trap("貸しているハンドル $handle を drop した")
        val own = t.isOwn(handle)
        val rep = t.rep(handle)
        val scope = if (own) 0L else t.scope(handle)
        removeHandle(t, handle)
        if (!own) {
            taskBorrows[taskIndex(scope)]--
            return
        }
        // own なら dtor を呼ぶ（同じインスタンスでも呼ぶ。ゲストからゲストへの呼び出しなので callGuest では包まない）
        // 仕様の canon_resource_drop は dtor を store.lift → store.lower で呼ぶ（dtor がなければ何もしない関数）。同期の呼び出しでは、
        // 再入をトラップにする検査はなく（dd1fefc）、core 関数を直接呼ぶのと観測できる差はない（#705。ADR 0265）
        when (val impl = plan.resource(ref.resource).impl) {
            is ResourceImpl.Guest -> impl.dtor?.let { callCore(it, longArrayOf(u32(rep))) }
            is ResourceImpl.Host -> impl.resource.drop?.drop(ComponentCall(this, env, impl.interfaceName, "[resource-drop]${impl.name}"), rep)
            ResourceImpl.Abstract -> Unit
        }
    }

    /** インスタンス [instance] のハンドルの lift・lower（仕様の lift_own・lift_borrow・add_own・store_borrow） */
    private inner class InstanceResources(private val instance: Int) : CanonResources {
        override fun liftOwn(type: CanonType.Own, handle: Int): Int {
            val t = existing(instance, handle)
            checkType(t, handle, type.resource)
            if (t.lends(handle) != 0) CanonTraps.trap("貸している own のハンドル $handle を渡した")
            if (!t.isOwn(handle)) CanonTraps.trap("borrow のハンドル $handle を own として渡した")
            val rep = t.rep(handle)
            removeHandle(t, handle)
            return rep
        }

        override fun liftBorrow(type: CanonType.Borrow, handle: Int): Int {
            val t = existing(instance, handle)
            checkType(t, handle, type.resource)
            t.lend(handle, 1)
            pushLender(instance, handle)
            return t.rep(handle)
        }

        override fun lowerOwn(type: CanonType.Own, value: Int): Int = addOwn(instance, type.resource, value)

        override fun lowerBorrow(type: CanonType.Borrow, value: Int): Int {
            // その resource を実装したインスタンスに渡すなら、rep のまま（pass_rep_not_handle）
            val impl = plan.resource(type.resource).impl
            if (impl is ResourceImpl.Guest && impl.instance == instance) return value
            check(taskDepth > 0) { "borrow を受ける task がない（内部の誤り）" }
            reserveHandle()
            val h = tableOf(instance).addBorrow(canonical(type.resource), value, taskStack[taskDepth - 1])
            liveHandles++
            taskBorrows[taskDepth - 1]++
            return h
        }
    }

    /** core インスタンスをすべて閉じる。2 回呼んでもよい（ハンドル表に残ったハンドルの dtor は呼ばない） */
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
