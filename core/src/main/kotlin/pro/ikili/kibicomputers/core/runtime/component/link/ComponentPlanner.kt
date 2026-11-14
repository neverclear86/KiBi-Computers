package pro.ikili.kibicomputers.core.runtime.component.link

import pro.ikili.kibicomputers.core.runtime.WasmExternKind
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.WasmModule
import pro.ikili.kibicomputers.core.runtime.component.Alias
import pro.ikili.kibicomputers.core.runtime.component.ByteRange
import pro.ikili.kibicomputers.core.runtime.component.Canon
import pro.ikili.kibicomputers.core.runtime.component.ComponentSection
import pro.ikili.kibicomputers.core.runtime.component.ComponentTypeDef
import pro.ikili.kibicomputers.core.runtime.component.CoreValType
import pro.ikili.kibicomputers.core.runtime.component.CoreInstanceExpr
import pro.ikili.kibicomputers.core.runtime.component.DefType
import pro.ikili.kibicomputers.core.runtime.component.DefValType
import pro.ikili.kibicomputers.core.runtime.component.ExternDesc
import pro.ikili.kibicomputers.core.runtime.component.FuncType
import pro.ikili.kibicomputers.core.runtime.component.InstanceExpr
import pro.ikili.kibicomputers.core.runtime.component.InstanceTypeDef
import pro.ikili.kibicomputers.core.runtime.component.LoadedComponent
import pro.ikili.kibicomputers.core.runtime.component.ParsedComponent
import pro.ikili.kibicomputers.core.runtime.component.ResourceType
import pro.ikili.kibicomputers.core.runtime.component.Sort
import pro.ikili.kibicomputers.core.runtime.component.SortIdx
import pro.ikili.kibicomputers.core.runtime.component.TypeDecl
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonFunction
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonOptions
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonResourceMatch
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonTypeConvert
import pro.ikili.kibicomputers.core.runtime.validation.LoadDeadline

/**
 * コンポーネントをなぞって、リンクの計画（[ComponentPlan]）を作る（#263。ADR 0263）。
 *
 * 一番外のコンポーネントから、宣言の順に添字空間を作りながら進み、入れ子のコンポーネントのインスタンス化はその場で展開する
 * （展開の数と手間は [pro.ikili.kibicomputers.core.runtime.component.ComponentExpansion] が上限で抑えてある）。
 * 添字空間の番号の振り方は [pro.ikili.kibicomputers.core.runtime.component.ComponentParser] と同じ（import・alias・
 * canon・インスタンス・export が、それぞれの種類の空間に番号を足す）。
 *
 * 型は、インスタンス化ごとに解決する（resource の型はインスタンス化・import のたびに新しく作る。生成的）。
 * 一番外のコンポーネントの import はホスト（[ComponentHost]）から引き、インスタンス型がホストのインターフェースの部分型か
 * （ゲストが宣言した関数・resource がすべてあり、関数の型が同じ形か）をここで確かめる。
 *
 * 検証（wasm-tools）に通ったものだけを渡す。それでも合わないもの（ホストにない import、まだ扱えない canon・型、
 * core の import の種類・型の違い）は [WasmLoadException] にする（読み込みの失敗として覚えてよい）
 */
internal class ComponentPlanner private constructor(
    private val modules: List<WasmModule>,
    private val moduleIndex: Map<ByteRange, Int>,
    private val host: ComponentHost,
    private val deadline: LoadDeadline?,
) {
    private val steps = ArrayList<CoreStep>()
    private val lifts = ArrayList<LiftSpec>()
    private val lowers = ArrayList<LowerSpec>()
    private var frames = 0
    private var work = 0L

    /** resource の番号 → 記録（0 番は使わない。#265） */
    private val resources = arrayListOf<ResourceInfo?>(null)

    /** ゲストの resource の識別子 → ホストの resource の識別子（一番外の import から作った対応） */
    private val hostResources = HashMap<Int, Int>()

    /** ホストの resource の識別子 → 最初に結び付いたゲストの resource の番号（正規の番号。#265） */
    private val hostCanonical = HashMap<Int, Int>()

    /** ゲストとホストの型を比べるときの resource の対応 */
    private val hostMatch = CanonResourceMatch { guest, hostId -> hostResources[guest] == hostId }

    companion object {
        /**
         * [component] の計画を作る。[modules] は読み込んだ core モジュール、[moduleIndex] は core モジュールの範囲 → [modules] の番号
         */
        @Throws(WasmLoadException::class)
        fun plan(
            component: LoadedComponent,
            modules: List<WasmModule>,
            moduleIndex: Map<ByteRange, Int>,
            host: ComponentHost,
            deadline: LoadDeadline? = null,
        ): ComponentPlan {
            val p = ComponentPlanner(modules, moduleIndex, host, deadline)
            val root = p.instantiate(component.root, null, null)
            val exports = LinkedHashMap<String, ExportItem>()
            for ((name, item) in root) exports[name] = p.exportItem(item)
            return ComponentPlan(modules, p.steps.toList(), p.lifts.toList(), p.lowers.toList(), p.frames, exports, p.resources.toList())
        }

        private fun fail(message: String): Nothing = throw WasmLoadException(message)
    }

    // ---- 値 ----

    /** 型の添字空間の 1 つ */
    private sealed interface TypeEntry {
        class Value(val type: CanonType) : TypeEntry
        class Func(val function: CanonFunction) : TypeEntry
        class Resource(val id: Int) : TypeEntry

        /** インスタンス型。中身は import・alias のたびに解決する（resource をそのたびに作るため） */
        class InstanceDecl(val decls: List<TypeDecl>, val scope: TypeScope) : TypeEntry
        data object ComponentDecl : TypeEntry
        class Unresolved(val reason: String) : TypeEntry
    }

    /** インスタンス型を解決した形の export 1 つ */
    private sealed interface Shape {
        class Func(val entry: TypeEntry) : Shape
        class Type(val entry: TypeEntry) : Shape
        data object Other : Shape
    }

    /** コンポーネントの値（インスタンスの export、インスタンス化の引数、alias で渡るもの） */
    private sealed interface Item {
        class Func(val ref: FuncRef) : Item
        class Type(val entry: TypeEntry) : Item
        class Component(val def: ParsedComponent, val env: Frame) : Item
        class Instance(val exports: Map<String, Item>) : Item
        class Module(val index: Int) : Item
    }

    /** 型の添字空間。[parent] は外側（`alias outer` で 1 段外） */
    private inner class TypeScope(val parent: TypeScope?) : CanonTypeConvert.Refs {
        val types = ArrayList<TypeEntry>()

        fun ref(index: Int): TypeEntry = types.getOrNull(index) ?: TypeEntry.Unresolved("前にない型 $index を参照した")

        fun outer(count: Int, index: Int): TypeEntry {
            var s: TypeScope = this
            repeat(count) { s = s.parent ?: return TypeEntry.Unresolved("外側の型 $index がない") }
            return s.ref(index)
        }

        override fun value(index: Int): CanonType = when (val e = ref(index)) {
            is TypeEntry.Value -> e.type
            is TypeEntry.Unresolved -> throw CanonTypeConvert.Unresolved(e.reason)
            else -> throw CanonTypeConvert.Unresolved("型 $index は値の型ではない")
        }

        override fun resource(index: Int): Int = when (val e = ref(index)) {
            is TypeEntry.Resource -> e.id
            is TypeEntry.Unresolved -> throw CanonTypeConvert.Unresolved(e.reason)
            else -> throw CanonTypeConvert.Unresolved("型 $index は resource ではない")
        }

        fun define(t: DefType): TypeEntry = when (t) {
            is DefValType -> resolving { TypeEntry.Value(CanonTypeConvert.defValType(t, this)) }
            is FuncType -> resolving { TypeEntry.Func(CanonTypeConvert.funcType(t, this)) }
            // resource の定義はコンポーネントの Types セクションにだけ来る（Frame で記録する。guestResource）。
            // インスタンス型の中には来ない（検証で通らない）
            is ResourceType -> TypeEntry.Unresolved("インスタンス型の中の resource の定義")
            is InstanceTypeDef -> TypeEntry.InstanceDecl(t.decls, this)
            is ComponentTypeDef -> TypeEntry.ComponentDecl
        }

        private inline fun resolving(block: () -> TypeEntry): TypeEntry = try {
            block()
        } catch (e: CanonTypeConvert.Unresolved) {
            TypeEntry.Unresolved(e.message!!)
        }
    }

    /** コンポーネントのインスタンス化 1 回の添字空間 */
    private inner class Frame(val id: Int, val lexical: Frame?) {
        val scope: TypeScope = TypeScope(lexical?.scope)
        val coreFuncs = ArrayList<CoreRef>()
        val coreTables = ArrayList<CoreRef>()
        val coreMemories = ArrayList<CoreRef>()
        val coreGlobals = ArrayList<CoreRef>()
        val coreTags = ArrayList<CoreRef>()
        val coreModules = ArrayList<Int>()
        val coreInstances = ArrayList<Map<String, CoreRef>>()
        val funcs = ArrayList<FuncRef>()
        val components = ArrayList<Item.Component>()
        val instances = ArrayList<Item.Instance>()

        fun outerFrame(count: Int): Frame {
            var f: Frame = this
            repeat(count) { f = f.lexical ?: fail("外側のコンポーネントがない（alias outer $count）") }
            return f
        }

        fun coreSpace(sort: Sort): ArrayList<CoreRef> = when (sort) {
            Sort.CORE_FUNC -> coreFuncs
            Sort.CORE_TABLE -> coreTables
            Sort.CORE_MEMORY -> coreMemories
            Sort.CORE_GLOBAL -> coreGlobals
            Sort.CORE_TAG -> coreTags
            else -> fail("${sort.label} は core の値の空間ではない")
        }

        fun core(sort: Sort, index: Int): CoreRef = coreSpace(sort).getOrNull(index) ?: fail("${sort.label} $index がない")

        fun lookup(target: SortIdx): Item = when (target.sort) {
            Sort.FUNC -> Item.Func(funcs.getOrNull(target.index) ?: fail("func ${target.index} がない"))
            Sort.TYPE -> Item.Type(scope.ref(target.index))
            Sort.COMPONENT -> components.getOrNull(target.index) ?: fail("component ${target.index} がない")
            Sort.INSTANCE -> instances.getOrNull(target.index) ?: fail("instance ${target.index} がない")
            Sort.CORE_MODULE -> Item.Module(coreModules.getOrNull(target.index) ?: fail("core module ${target.index} がない"))
            Sort.VALUE -> fail("値（cm-values）は扱わない")
            else -> fail("${target.sort.label} はコンポーネントの値として渡せない")
        }

        fun push(sort: Sort, item: Item) {
            when (sort) {
                Sort.FUNC -> funcs += (item as? Item.Func ?: mismatch(sort, item)).ref
                Sort.TYPE -> scope.types += (item as? Item.Type ?: mismatch(sort, item)).entry
                Sort.COMPONENT -> components += item as? Item.Component ?: mismatch(sort, item)
                Sort.INSTANCE -> instances += item as? Item.Instance ?: mismatch(sort, item)
                Sort.CORE_MODULE -> coreModules += (item as? Item.Module ?: mismatch(sort, item)).index
                else -> fail("${sort.label} をコンポーネントの値として受けられない")
            }
        }

        private fun mismatch(sort: Sort, item: Item): Nothing = fail("${sort.label} に種類の違う値（$item）を渡した")
    }

    private fun newResource(impl: ResourceImpl): Int {
        val id = resources.size
        resources += ResourceInfo(id, impl)
        return id
    }

    /**
     * [f] の Types セクションの resource の定義 [t] を記録する。dtor は、定義した時点の [f] の core func の添字空間で引く。
     * rep は i32 だけ（[CanonType.Handle] は 4 バイト・i32 の固定。memory64 は無効なので、検証を通ったものには来ない）
     */
    private fun guestResource(f: Frame, t: ResourceType): TypeEntry {
        if (t.rep != CoreValType.I32) fail("resource の rep が i32 でない（${t.rep}）")
        val dtor = t.destructor?.let { f.core(Sort.CORE_FUNC, it) }
        if (dtor != null && dtor.functionType != I32_TO_NONE) fail("resource の dtor の型が (i32) -> () でない（${dtor.functionType}）")
        return TypeEntry.Resource(newResource(ResourceImpl.Guest(f.id, dtor)))
    }

    /** なぞった宣言の数を数え、ときどき時間の上限を確かめる */
    private fun step() {
        if (++work and 0xff == 0L) deadline?.check("コンポーネントのリンク")
    }

    // ---- インスタンス化 ----

    /**
     * [def] をインスタンス化し、export を返す。[lexical] は定義した場所の添字空間（`alias outer` で引く）、
     * [args] はインスタンス化の引数（一番外なら `null`。import はホストから引く）
     */
    private fun instantiate(def: ParsedComponent, lexical: Frame?, args: Map<String, Item>?): Map<String, Item> {
        val f = Frame(frames++, lexical)
        val exports = LinkedHashMap<String, Item>()
        for (section in def.sections) {
            step()
            when (section) {
                is ComponentSection.Custom -> Unit
                is ComponentSection.CoreModule ->
                    f.coreModules += moduleIndex[section.range] ?: fail("core モジュールが読み込まれていない（内部の誤り）")
                is ComponentSection.CoreTypes -> Unit
                is ComponentSection.CoreInstances -> for (item in section.items) {
                    step()
                    f.coreInstances += coreInstance(f, item)
                }
                is ComponentSection.Nested -> f.components += Item.Component(section.component, f)
                is ComponentSection.Instances -> for (item in section.items) {
                    step()
                    f.instances += when (item) {
                        is InstanceExpr.Instantiate -> {
                            val target = f.components.getOrNull(item.component) ?: fail("component ${item.component} がない")
                            val a = LinkedHashMap<String, Item>()
                            for ((name, idx) in item.args) a[name] = f.lookup(idx)
                            Item.Instance(instantiate(target.def, target.env, a))
                        }
                        is InstanceExpr.FromExports -> Item.Instance(item.exports.associate { (name, idx) -> name to f.lookup(idx) })
                    }
                }
                is ComponentSection.Aliases -> for (a in section.items) {
                    step()
                    alias(f, a)
                }
                is ComponentSection.Types -> for (t in section.items) {
                    step()
                    f.scope.types += if (t is ResourceType) guestResource(f, t) else f.scope.define(t)
                }
                is ComponentSection.Canons -> for (c in section.items) {
                    step()
                    canon(f, c)
                }
                is ComponentSection.Imports -> for (imp in section.items) {
                    step()
                    val item = if (args == null) hostImport(f, imp.name, imp.desc) else args[imp.name] ?: fail("import ${imp.name} の引数がない")
                    f.push(imp.desc.sort, item)
                }
                is ComponentSection.Exports -> for (e in section.items) {
                    step()
                    val item = f.lookup(e.target)
                    exports[e.name] = item
                    // export も、その種類の添字空間に新しい番号を足す
                    f.push(e.target.sort, item)
                }
            }
        }
        return exports
    }

    private fun coreInstance(f: Frame, expr: CoreInstanceExpr): Map<String, CoreRef> = when (expr) {
        is CoreInstanceExpr.Instantiate -> {
            val moduleId = f.coreModules.getOrNull(expr.module) ?: fail("core module ${expr.module} がない")
            val module = modules[moduleId]
            val args = HashMap<String, Map<String, CoreRef>>()
            for ((name, idx) in expr.args) args[name] = f.coreInstances.getOrNull(idx) ?: fail("core instance $idx がない")
            val bindings = module.imports.map { imp ->
                val ref = args[imp.module]?.get(imp.name) ?: fail("core モジュールの import ${imp.module}.${imp.name} に渡すものがない")
                if (ref.kind != imp.kind) fail("core モジュールの import ${imp.module}.${imp.name} の種類が違う（${imp.kind} に ${ref.kind}）")
                if (imp.kind == WasmExternKind.FUNCTION && ref.functionType != imp.functionType) {
                    fail("core モジュールの import ${imp.module}.${imp.name} の型が違う（${imp.functionType} に ${ref.functionType}）")
                }
                imp to ref
            }
            // コンポーネントの中の core モジュールは、同じ (module, name) の import を2回書けない（Component Model の検証の規則。
            // wasm-tools も断る）。検証を通ったものには来ないが、来たらインスタンス化の IllegalArgumentException でなく、
            // 読み込みの失敗にする（#690）
            val seen = HashSet<Pair<String, String>>()
            for ((imp, _) in bindings) {
                if (!seen.add(imp.module to imp.name)) fail("core モジュールが import ${imp.module}.${imp.name} を2回書いている")
            }
            val step = steps.size
            steps += CoreStep(moduleId, bindings)
            module.exports.associate { it.name to CoreRef.Export(step, it.name, it.kind, it.functionType) }
        }
        is CoreInstanceExpr.FromExports -> expr.exports.associate { (name, idx) -> name to f.core(idx.sort, idx.index) }
    }

    private fun alias(f: Frame, a: Alias) {
        when (a) {
            is Alias.CoreInstanceExport -> {
                val inst = f.coreInstances.getOrNull(a.instance) ?: fail("core instance ${a.instance} がない")
                val ref = inst[a.name] ?: fail("core instance ${a.instance} に export ${a.name} がない")
                if (a.sort == Sort.CORE_TYPE) fail("core type の alias は扱わない")
                if (ref.kind != coreKind(a.sort)) fail("core instance の export ${a.name} の種類が違う（${a.sort.label}）")
                f.coreSpace(a.sort) += ref
            }
            is Alias.InstanceExport -> {
                val inst = f.instances.getOrNull(a.instance) ?: fail("instance ${a.instance} がない")
                val item = inst.exports[a.name] ?: fail("instance ${a.instance} に export ${a.name} がない")
                f.push(a.sort, item)
            }
            is Alias.Outer -> when (a.sort) {
                Sort.TYPE -> f.scope.types += f.scope.outer(a.count, a.index)
                Sort.CORE_TYPE -> Unit
                Sort.COMPONENT -> f.components += f.outerFrame(a.count).components.getOrNull(a.index) ?: fail("外側の component ${a.index} がない")
                Sort.CORE_MODULE -> f.coreModules += f.outerFrame(a.count).coreModules.getOrNull(a.index) ?: fail("外側の core module ${a.index} がない")
                else -> fail("${a.sort.label} の alias outer は扱わない")
            }
        }
    }

    private fun coreKind(sort: Sort): WasmExternKind = when (sort) {
        Sort.CORE_FUNC -> WasmExternKind.FUNCTION
        Sort.CORE_TABLE -> WasmExternKind.TABLE
        Sort.CORE_MEMORY -> WasmExternKind.MEMORY
        Sort.CORE_GLOBAL -> WasmExternKind.GLOBAL
        Sort.CORE_TAG -> WasmExternKind.TAG
        else -> fail("${sort.label} は core の値の空間ではない")
    }

    private fun canon(f: Frame, c: Canon) {
        when (c) {
            is Canon.Lift -> {
                val function = funcEntry(f.scope.ref(c.typeIndex), "canon lift の型 ${c.typeIndex}")
                val core = f.core(Sort.CORE_FUNC, c.coreFunc)
                if (core.functionType != function.liftSignature.toWasm()) {
                    fail("canon lift の core の関数の型が違う（${core.functionType}。${function.liftSignature}）")
                }
                val id = lifts.size
                lifts += LiftSpec(core, function, binding(f, CanonOptions.of(c.options)), f.id)
                f.funcs += FuncRef.Lifted(id, function)
            }
            is Canon.Lower -> {
                val callee = f.funcs.getOrNull(c.func) ?: fail("func ${c.func} がない")
                val id = lowers.size
                lowers += LowerSpec(callee, callee.function, binding(f, CanonOptions.of(c.options)), f.id)
                f.coreFuncs += CoreRef.Lowered(id, callee.function.lowerSignature.toWasm())
            }
            is Canon.ResourceNew -> f.coreFuncs += resourceOp(f, CoreRef.ResourceOp.Op.NEW, c.typeIndex)
            is Canon.ResourceRep -> f.coreFuncs += resourceOp(f, CoreRef.ResourceOp.Op.REP, c.typeIndex)
            is Canon.ResourceDrop -> f.coreFuncs += resourceOp(f, CoreRef.ResourceOp.Op.DROP, c.typeIndex)
            is Canon.Builtin -> fail("canon ${c.op.label} はまだ扱えない（#266）")
        }
    }

    /** canon `resource.*` の [op]。new・rep は、その resource を定義したインスタンスの中でだけ書ける（検証の規則。守りとして確かめる） */
    private fun resourceOp(f: Frame, op: CoreRef.ResourceOp.Op, typeIndex: Int): CoreRef.ResourceOp {
        val e = f.scope.ref(typeIndex) as? TypeEntry.Resource ?: fail("canon ${op.label} の型 $typeIndex が resource でない")
        if (op != CoreRef.ResourceOp.Op.DROP) {
            val impl = resources[e.id]?.impl
            if (impl !is ResourceImpl.Guest || impl.instance != f.id) fail("canon ${op.label} の resource $typeIndex は、このコンポーネントが定義したものでない")
        }
        return CoreRef.ResourceOp(op, e.id, f.id)
    }

    private fun binding(f: Frame, o: CanonOptions): CanonBinding = CanonBinding(
        o.encoding,
        o.memory?.let { f.core(Sort.CORE_MEMORY, it) },
        o.realloc?.let { f.core(Sort.CORE_FUNC, it) },
        o.postReturn?.let { f.core(Sort.CORE_FUNC, it) },
    )

    private fun funcEntry(e: TypeEntry, what: String): CanonFunction = when (e) {
        is TypeEntry.Func -> e.function
        is TypeEntry.Unresolved -> fail("$what を Canonical ABI の型にできない: ${e.reason}")
        else -> fail("$what は関数の型ではない")
    }

    // ---- ホスト ----

    /** インスタンス型 [decl] を、import・alias 1 回分として解決する（`(sub resource)` の型は、ここで新しく作る） */
    private fun shapeOf(decl: TypeEntry.InstanceDecl): Map<String, Shape> {
        val scope = TypeScope(decl.scope)
        val shape = LinkedHashMap<String, Shape>()
        for (d in decl.decls) {
            step()
            when (d) {
                is TypeDecl.CoreType, is TypeDecl.Import -> Unit
                is TypeDecl.Type -> scope.types += scope.define(d.type)
                is TypeDecl.AliasDecl -> {
                    val a = d.alias
                    if (a.sort == Sort.TYPE) {
                        scope.types += if (a is Alias.Outer) scope.outer(a.count, a.index) else TypeEntry.Unresolved("インスタンス型の中の alias")
                    }
                }
                is TypeDecl.Export -> shape[d.name] = when (val desc = d.desc) {
                    is ExternDesc.Func -> Shape.Func(scope.ref(desc.typeIndex))
                    is ExternDesc.Type -> {
                        val e = desc.eqTypeIndex?.let { scope.ref(it) } ?: TypeEntry.Resource(newResource(ResourceImpl.Abstract))
                        scope.types += e
                        Shape.Type(e)
                    }
                    else -> Shape.Other
                }
            }
        }
        return shape
    }

    /** 一番外のコンポーネントの import [name] を、ホストのインターフェースから作る */
    private fun hostImport(f: Frame, name: String, desc: ExternDesc): Item {
        if (desc !is ExternDesc.Instance) fail("import $name: ホストはインスタンスだけを出す（${desc.sort.label}）")
        val decl = f.scope.ref(desc.typeIndex) as? TypeEntry.InstanceDecl ?: fail("import $name の型がインスタンス型でない")
        val iface = host.interfaceOf(name) ?: fail("ホストが出さないインターフェース $name")
        val exports = LinkedHashMap<String, Item>()
        for ((member, shape) in shapeOf(decl)) {
            exports[member] = when (shape) {
                is Shape.Type -> {
                    val e = shape.entry
                    if (e is TypeEntry.Resource && (iface.fallback == null || member in iface.resources)) {
                        bindHostResource(name, member, e.id, iface.resources[member])
                    }
                    Item.Type(e)
                }
                is Shape.Func -> {
                    val guest = funcEntry(shape.entry, "import $name の関数 $member の型")
                    val declared = iface.functions[member]
                    val hf = when {
                        declared != null -> declared.also { if (!sameFunction(guest, it.type)) fail("ホストのインターフェース $name の関数 $member の型が違う") }
                        else -> iface.fallback?.function(member, guest)?.let { HostFunction(guest, it) }
                            ?: fail("ホストのインターフェース $name に関数 $member がない")
                    }
                    Item.Func(FuncRef.Host(name, member, hf, guest))
                }
                Shape.Other -> fail("ホストのインターフェース $name は $member を出さない（関数と型だけ）")
            }
        }
        return Item.Instance(exports)
    }

    /**
     * ゲストの resource [guest] を、ホストの resource [host] に結び付ける。同じホストの resource に結び付いたゲストの番号は、
     * どれも同じ正規の番号（最初に結び付いた番号）にする（#265）
     */
    private fun bindHostResource(iface: String, member: String, guest: Int, host: HostResource?) {
        if (resources[guest]?.impl is ResourceImpl.Guest) fail("ホストのインターフェース $iface の resource $member が、ゲストの定義した resource を指す")
        val bound = hostResources[guest]
        when {
            host == null && bound == null -> fail("ホストのインターフェース $iface に resource $member がない")
            host == null -> Unit
            bound == null -> {
                hostResources[guest] = host.id
                val canonical = hostCanonical.getOrPut(host.id) { guest }
                resources[guest] = ResourceInfo(canonical, ResourceImpl.Host(iface, member, host))
            }
            bound != host.id -> fail("ホストのインターフェース $iface の resource $member が、ほかのインターフェースの同じ型と違う")
        }
    }

    private fun sameFunction(guest: CanonFunction, hostType: CanonFunction): Boolean =
        guest.params.size == hostType.params.size &&
            guest.params.indices.all { CanonType.sameShape(guest.params[it], hostType.params[it], hostMatch) } &&
            CanonType.sameShape(guest.result, hostType.result, hostMatch)

    // ---- export ----

    private fun exportItem(item: Item): ExportItem = when (item) {
        is Item.Func -> ExportItem.Func(item.ref)
        is Item.Instance -> ExportItem.Instance(LinkedHashMap<String, ExportItem>().also { m -> for ((k, v) in item.exports) m[k] = exportItem(v) })
        else -> ExportItem.Other
    }
}
