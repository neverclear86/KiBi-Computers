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
 * コンポーネントどうしの呼び出し（lift した関数を lower した import）は、ゲストからゲストへの呼び出しなので [LinkEnv.callGuest] で包まない。
 *
 * ホストからの [call] は入れ子にできない（#691）。ホストの関数（[HostFunctionBody]）は必ず [call] かインスタンス化の下で動くので、
 * その中から [call] を呼ぶと [IllegalStateException] になる。ホストの関数の中から呼べると、core の関数が [LinkEnv.callGuest] で
 * 包まれずに動き、ホスト関数の途中で止まる口を使えてしまう（ADR 0073）。仕様の「同じコンポーネントのインスタンスに入れ子で入らない」にも合う
 *
 * resource（#265。ADR 0265）: ハンドル表はコンポーネントのインスタンスごとに 1 つ（[ResourceTable]。使ったインスタンスの分だけ作る）。
 * borrow の範囲は、同期の呼び出しのスタック（仕様の Task の番号と borrow の数、Subtask の lenders）で持ち、引数に borrow を含む
 * 関数の呼び出しのときだけ積む。閉じるとき（[close]）は、表に残ったハンドルの dtor を呼ばない
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
            c.entered = true
            try {
                for (step in plan.steps) c.instantiateStep(step, sharedFuel)
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
        for (r in func.function.paramResources) {
            val info = plan.resource(r)
            require(info.impl !is ResourceImpl.Guest) { "ゲストが実装した resource（${info.canonical}）の own・borrow をホストから渡した" }
        }
        entered = true
        try {
            return guarded {
                val budget = CanonBudget()
                val r = callFunc(func, args, budget) { it }
                env.chargeFuel(cost(budget))
                r
            }
        } finally {
            entered = false
        }
    }

    // ---- 呼び出しの中身 ----

    /** ゲストの core が canon lower した import を呼んだ */
    private fun callLowered(spec: LowerSpec, args: LongArray): LongArray = guarded {
        if (!mayLeave[spec.instance]) CanonTraps.trap("realloc・post-return の中から import を呼んだ（may_leave）")
        val budget = CanonBudget()
        val cx = context(spec.options, spec.instance, budget)
        // 仕様の Subtask: 引数の borrow の lift で貸したハンドルを、呼び出しが済んだら（post-return の後）戻す（deliver_resolve）
        val lenders = lenderTop
        val out = try {
            val values = spec.function.liftParams(cx, args)
            callFunc(spec.callee, values, budget) { spec.function.lowerResult(cx, it, args) }
        } finally {
            if (lenderTop != lenders) returnLenders(lenders)
        }
        env.chargeFuel(cost(budget))
        out
    }

    /** [func] を呼び、結果を [onResult] に渡す（lift した関数なら、その後に post-return を呼ぶ） */
    private inline fun <T> callFunc(func: FuncRef, args: List<CanonValue>, budget: CanonBudget, onResult: (CanonValue?) -> T): T = when (func) {
        is FuncRef.Host -> onResult(func.host.body.call(ComponentCall(this, env, func.interfaceName, func.name), args))
        is FuncRef.Lifted -> {
            val spec = plan.lifts[func.lift]
            val cx = context(spec.options, spec.instance, budget)
            // 仕様の Task: 引数に borrow を受ける関数なら、borrow の範囲を積む（ない関数は何も積まない）
            val task = spec.function.paramsHaveBorrow
            if (task) pushTask()
            val results: LongArray
            val value: CanonValue?
            try {
                val coreArgs = spec.function.lowerParams(cx, args)
                results = callCore(spec.core, coreArgs)
                value = spec.function.liftResult(cx, results)
                // Task.return_: 受けた borrow をすべて drop してから返る。呼ぶ側への lower（呼ぶ側の realloc が動く）より前に確かめる
                if (task && taskBorrows[taskDepth - 1] > 0) CanonTraps.trap("borrow のハンドルを drop せずに返った")
            } finally {
                if (task) taskDepth--
            }
            val r = onResult(value)
            spec.options.postReturn?.let { pr -> withoutLeave(spec.instance) { callCore(pr, results) } }
            r
        }
    }

    /** core の関数 [ref] を呼ぶ */
    private fun callCore(ref: CoreRef, args: LongArray): LongArray = when (ref) {
        is CoreRef.Export -> instanceOf(ref).call(ref.name, *args)
        is CoreRef.Lowered -> callLowered(plan.lowers[ref.lower], args)
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
        // 仕様の canon_resource_drop は dtor を canon_lower → canon_lift で呼び（実装したインスタンスへの再入を検査）、dtor がなければ
        // trap_if_on_the_stack する。ここでは写していない（通常の呼び出しにも再入の検査がない。#705。ADR 0265）
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
