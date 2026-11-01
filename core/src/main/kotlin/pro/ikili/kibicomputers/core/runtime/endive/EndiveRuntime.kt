package pro.ikili.kibicomputers.core.runtime.endive

import pro.ikili.kibicomputers.core.runtime.WasmException
import pro.ikili.kibicomputers.core.runtime.WasmExportDescriptor
import pro.ikili.kibicomputers.core.runtime.WasmExternKind
import pro.ikili.kibicomputers.core.runtime.WasmFuel
import pro.ikili.kibicomputers.core.runtime.WasmFunctionType
import pro.ikili.kibicomputers.core.runtime.WasmImportDescriptor
import pro.ikili.kibicomputers.core.runtime.WasmInstance
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmLinkException
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.WasmMemory
import pro.ikili.kibicomputers.core.runtime.WasmMemoryAccessException
import pro.ikili.kibicomputers.core.runtime.WasmMemoryBudget
import pro.ikili.kibicomputers.core.runtime.WasmModule
import pro.ikili.kibicomputers.core.runtime.WasmRuntime
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind
import pro.ikili.kibicomputers.core.runtime.WasmValueType
import run.endive.runtime.ByteBufferMemory
import run.endive.runtime.ExportFunction
import run.endive.runtime.GlobalFactory
import run.endive.runtime.GlobalInstance
import run.endive.runtime.HostFunction
import run.endive.runtime.ImportValues
import run.endive.runtime.Instance
import run.endive.runtime.TrapException
import run.endive.runtime.WasmInterruptedException
import run.endive.wasm.InvalidException
import run.endive.wasm.MalformedException
import run.endive.wasm.Parser
import run.endive.wasm.UninstantiableException
import run.endive.wasm.UnlinkableException
import run.endive.wasm.WasmEngineException
import run.endive.wasm.types.ExternalType
import run.endive.wasm.types.FunctionImport
import run.endive.wasm.types.FunctionType
import run.endive.wasm.types.MemoryLimits
import run.endive.wasm.types.ValType
import run.endive.runtime.Memory as EndiveMemory
import run.endive.runtime.WasmException as EndiveWasmException
import run.endive.wasm.WasmModule as EndiveModule

/**
 * Endive による [WasmRuntime]。
 *
 * 今はインタプリタで動かす。ランタイムコンパイラ（#29 の決定）と読み込みの共有は #37 で足す。
 *
 * メモリの上限（#39）は、Endive のメモリを作るときに上限を縮め（[WasmInstanceConfig.maxMemoryPages]）、
 * [WasmInstanceConfig.memoryBudget] があれば `grow` を数える包み（[BudgetedMemory]）をかぶせて守る。
 */
public class EndiveRuntime : WasmRuntime {
    override val name: String get() = NAME

    @Throws(WasmLoadException::class)
    override fun load(bytes: ByteArray): WasmModule {
        val module = try {
            Parser.parse(bytes)
        } catch (e: MalformedException) {
            throw WasmLoadException("WASM として読めない: ${e.message}", e)
        } catch (e: InvalidException) {
            throw WasmLoadException("WASM の検証に通らない: ${e.message}", e)
        } catch (e: WasmEngineException) {
            throw WasmLoadException("WASM を読み込めない: ${e.message}", e)
        } catch (e: RuntimeException) {
            // パーサーが壊れた入力で想定外の例外を出すことがある
            throw WasmLoadException("WASM を読み込めない: $e", e)
        }
        return EndiveWasmModule(module)
    }

    public companion object {
        /** ランタイムの名前 */
        public const val NAME: String = "endive"
    }
}

/** メモリの上限のせいでメモリを作れない。Endive の中を通す間だけ使い、[WasmLinkException] にして投げ直す。 */
private class MemoryLimitFailure(message: String) : RuntimeException(message, null, false, false)

/**
 * `grow` を [budget] で数える Endive のメモリ（#39）。ほかの操作は [inner] にそのまま渡す。
 *
 * [reserved] は [budget] から確保したページ数。インスタンスを閉じたときに [releaseAll] で返す。
 */
private class BudgetedMemory(
    private val inner: EndiveMemory,
    private val budget: WasmMemoryBudget,
    private var reserved: Int,
) : EndiveMemory by inner {
    @Synchronized
    override fun grow(size: Int): Int {
        // 0 は今の大きさを返すだけ。負（符号なしで 2^31 以上）は Endive が -1 を返す
        if (size <= 0 || reserved < 0) return inner.grow(size)
        val current = inner.pages()
        if (current.toLong() + size > inner.maximumPages()) return -1
        if (!budget.tryReserve(size)) return -1
        val previous = try {
            inner.grow(size)
        } catch (t: Throwable) {
            budget.release(size)
            throw t
        }
        if (previous < 0) {
            budget.release(size)
            return -1
        }
        reserved += size
        return previous
    }

    /** 確保した分をすべて返す。以後 `grow` は数えない（閉じたインスタンスのメモリ）。2回呼んでもよい */
    @Synchronized
    fun releaseAll() {
        if (reserved > 0) budget.release(reserved)
        reserved = -1
    }
}

/**
 * [config] の上限で Endive のメモリを作る（#39）。数えるメモリは [created] に足す。
 *
 * 最初のページ数が上限を超える、または確保を断られたら [MemoryLimitFailure] を投げる。
 */
private fun createMemory(limits: MemoryLimits, config: WasmInstanceConfig, created: MutableList<BudgetedMemory>): EndiveMemory {
    val initial = limits.initialPages()
    val max = minOf(limits.maximumPages(), config.maxMemoryPages)
    if (initial > max) {
        throw MemoryLimitFailure("メモリの最初のページ数 $initial が上限 $max を超える")
    }
    val budget = config.memoryBudget
    if (budget == null) {
        return ByteBufferMemory(if (max == limits.maximumPages()) limits else MemoryLimits(initial, max, limits.shared()))
    }
    if (initial > 0 && !budget.tryReserve(initial)) {
        throw MemoryLimitFailure("メモリの最初の $initial ページを確保できない（RAM が足りない）")
    }
    val inner = try {
        ByteBufferMemory(MemoryLimits(initial, max, limits.shared()))
    } catch (t: Throwable) {
        if (initial > 0) budget.release(initial)
        throw t
    }
    return BudgetedMemory(inner, budget, initial).also { created += it }
}

/** ホストの関数が投げた例外を、Endive の中を通す間だけ包む。外に出るときに外す。 */
private class HostThrowable(val original: Throwable) : RuntimeException(null, original, false, false)

private fun valueType(type: ValType): WasmValueType = when (type) {
    ValType.I32 -> WasmValueType.I32
    ValType.I64 -> WasmValueType.I64
    ValType.F32 -> WasmValueType.F32
    ValType.F64 -> WasmValueType.F64
    else -> WasmValueType.UNSUPPORTED
}

private fun valType(type: WasmValueType): ValType = when (type) {
    WasmValueType.I32 -> ValType.I32
    WasmValueType.I64 -> ValType.I64
    WasmValueType.F32 -> ValType.F32
    WasmValueType.F64 -> ValType.F64
    WasmValueType.UNSUPPORTED -> throw IllegalArgumentException("ホストの関数に使えない型")
}

private fun functionType(type: FunctionType): WasmFunctionType = WasmFunctionType(type.params().map(::valueType), type.returns().map(::valueType))

private fun endiveType(type: WasmFunctionType): FunctionType = FunctionType.of(type.params.map(::valType), type.results.map(::valType))

private fun externKind(type: ExternalType): WasmExternKind = when (type) {
    ExternalType.FUNCTION -> WasmExternKind.FUNCTION
    ExternalType.TABLE -> WasmExternKind.TABLE
    ExternalType.MEMORY -> WasmExternKind.MEMORY
    ExternalType.GLOBAL -> WasmExternKind.GLOBAL
    ExternalType.TAG -> WasmExternKind.TAG
}

/** 包んだホストの例外があれば、それを返す。 */
private fun hostThrowable(e: Throwable): Throwable? {
    var cur: Throwable? = e
    var depth = 0
    while (cur != null && depth++ < 32) {
        if (cur is HostThrowable) return cur.original
        cur = cur.cause
    }
    return null
}

/** メモリの上限のせいで作れなかったなら、その例外を返す。 */
private fun memoryLimitFailure(e: Throwable): MemoryLimitFailure? {
    var cur: Throwable? = e
    var depth = 0
    while (cur != null && depth++ < 32) {
        if (cur is MemoryLimitFailure) return cur
        cur = cur.cause
    }
    return null
}

/** Endive の例外を、この抽象の例外に変えて投げる。ホストの例外はそのまま投げる。 */
private fun rethrow(e: Throwable): Nothing {
    hostThrowable(e)?.let { throw it }
    throw when (e) {
        is WasmException -> e
        is StackOverflowError -> WasmTrapException(WasmTrapKind.STACK_OVERFLOW, "呼び出しが深すぎる", e)
        // Thread.interrupt で止められた（#43。カーネルの stop が使う）
        is WasmInterruptedException -> WasmTrapException(WasmTrapKind.INTERRUPTED, "実行しているスレッドが割り込まれた", e)
        is EndiveWasmException -> WasmTrapException(WasmTrapKind.UNCAUGHT_EXCEPTION, "捕まえられていない WASM の例外", e)
        is WasmEngineException -> WasmTrapException(trapKind(e.message.orEmpty()), e.message ?: e.toString(), e)
        else -> e
    }
}

/** Endive のトラップのメッセージから種類を決める。詳しい扱いは #41。 */
internal fun trapKind(message: String): WasmTrapKind {
    val m = message.lowercase()
    return when {
        "unreachable" in m -> WasmTrapKind.UNREACHABLE
        "out of bounds memory" in m -> WasmTrapKind.MEMORY_OUT_OF_BOUNDS
        "out of bounds table" in m || "undefined element" in m -> WasmTrapKind.TABLE_OUT_OF_BOUNDS
        "divide by zero" in m -> WasmTrapKind.INTEGER_DIVIDE_BY_ZERO
        "integer overflow" in m -> WasmTrapKind.INTEGER_OVERFLOW
        "invalid conversion" in m -> WasmTrapKind.INVALID_CONVERSION
        "call stack exhausted" in m -> WasmTrapKind.STACK_OVERFLOW
        "indirect call" in m || "uninitialized element" in m -> WasmTrapKind.INDIRECT_CALL
        "null" in m -> WasmTrapKind.NULL_REFERENCE
        else -> WasmTrapKind.OTHER
    }
}

private class EndiveWasmModule(val module: EndiveModule) : WasmModule {
    /** import の関数の型（関数の番号の順） */
    private val importFunctionTypes: List<FunctionType>

    private val rawImports: List<WasmImportDescriptor>
    private val rawExports: List<WasmExportDescriptor>

    override val isFuelMetered: Boolean

    /** 燃料の残りを入れるグローバルの番号。計測しないなら -1 */
    private val fuelGlobalIndex: Int
    override val imports: List<WasmImportDescriptor>
    override val exports: List<WasmExportDescriptor>

    init {
        val types = module.typeSection()
        val section = module.importSection()
        val funcTypes = mutableListOf<FunctionType>()
        val imps = mutableListOf<WasmImportDescriptor>()
        for (i in 0 until section.importCount()) {
            val imp = section.getImport(i)
            val type = (imp as? FunctionImport)?.let { types.getType(it.typeIndex()) }
            if (type != null) funcTypes += type
            imps += WasmImportDescriptor(imp.module(), imp.name(), externKind(imp.importType()), type?.let(::functionType))
        }
        importFunctionTypes = funcTypes
        rawImports = imps

        val exportSection = module.exportSection()
        rawExports = (0 until exportSection.exportCount()).map { i ->
            val exp = exportSection.getExport(i)
            val kind = externKind(exp.exportType())
            WasmExportDescriptor(exp.name(), kind, if (kind == WasmExternKind.FUNCTION) functionType(functionTypeOf(exp.index())) else null)
        }

        val fuelImport = rawImports.firstOrNull { it.module == WasmFuel.IMPORT_MODULE && it.name == WasmFuel.IMPORT_NAME }
        val fuelExport = rawExports.firstOrNull { it.name == WasmFuel.EXPORT_GLOBAL }
        if ((fuelImport == null) != (fuelExport == null)) {
            throw WasmLoadException("燃料の計測の import と export の片方しかない")
        }
        if (fuelImport != null && (fuelImport.functionType != WasmFuel.IMPORT_TYPE || fuelExport!!.kind != WasmExternKind.GLOBAL)) {
            throw WasmLoadException("燃料の計測の import・export の型が違う")
        }
        isFuelMetered = fuelImport != null
        if (fuelExport != null && module.importSection().count(ExternalType.GLOBAL) > 0) {
            throw WasmLoadException("グローバルの import には対応していない")
        }
        fuelGlobalIndex = if (fuelExport != null) exportSection.let { es -> (0 until es.exportCount()).map(es::getExport).first { it.name() == WasmFuel.EXPORT_GLOBAL }.index() } else -1
        imports = rawImports.filter { it !== fuelImport }
        exports = rawExports.filter { it !== fuelExport }
    }

    private fun functionTypeOf(index: Int): FunctionType = if (index < importFunctionTypes.size) {
        importFunctionTypes[index]
    } else {
        module.functionSection().getFunctionType(index - importFunctionTypes.size, module.typeSection())
    }

    fun exportedFunctionType(name: String): WasmFunctionType? = exports.firstOrNull { it.name == name && it.kind == WasmExternKind.FUNCTION }?.functionType

    @Throws(WasmLinkException::class, WasmLoadException::class, WasmTrapException::class)
    override fun instantiate(config: WasmInstanceConfig): WasmInstance {
        val wrapper = EndiveWasmInstance(this, config)
        val values = ImportValues.builder()
        for (imp in rawImports) {
            if (imp.kind != WasmExternKind.FUNCTION) {
                throw WasmLinkException("関数でない import には対応していない: $imp")
            }
            val type = imp.functionType!!
            if (isFuelMetered && imp.module == WasmFuel.IMPORT_MODULE && imp.name == WasmFuel.IMPORT_NAME) {
                values.addFunction(hostFunction(imp.module, imp.name, type, wrapper) { args -> longArrayOf(wrapper.fuelExhausted(args[0])) })
                continue
            }
            val host = config.hostImport(imp.module, imp.name) ?: throw WasmLinkException("import がない: $imp")
            if (host.type != type) {
                throw WasmLinkException("import の型が違う: $imp に $host")
            }
            values.addFunction(hostFunction(imp.module, imp.name, type, wrapper) { args -> host.function.call(wrapper, args) })
        }
        // 燃料のグローバルは、作るときに最初の燃料を入れる（start 関数は build の中で実行されるので、その前に要る）
        val fuelIndex = fuelGlobalIndex
        var globalIndex = 0
        val globals = GlobalFactory { low, high, type, mutability ->
            val value = if (globalIndex++ == fuelIndex) config.initialFuel else low
            GlobalInstance(value, high, type, mutability)
        }
        // 数えるメモリ（作るのに失敗したら、確保した分を返す）
        val memories = ArrayList<BudgetedMemory>(1)
        wrapper.budgetedMemories = memories
        val instance = try {
            // withStart(false): export の `_start` は呼ばない（start セクションの関数は build の中で実行される）
            Instance.builder(module)
                .withImportValues(values.build())
                .withGlobalFactory(globals)
                .withMemoryFactory { limits -> createMemory(limits, config, memories) }
                .withStart(false)
                .build()
        } catch (e: Throwable) {
            memories.forEach { it.releaseAll() }
            translateInstantiateFailure(e)
        }
        wrapper.attach(instance)
        return wrapper
    }

    /** インスタンスを作るときの例外を、この抽象の例外にして投げる。 */
    private fun translateInstantiateFailure(e: Throwable): Nothing {
        hostThrowable(e)?.let { throw it }
        memoryLimitFailure(e)?.let { throw WasmLinkException("インスタンスを作れない: ${it.message}", it) }
        when (e) {
            is UnlinkableException -> throw WasmLinkException("インスタンスを作れない: ${e.message}", e)
            is UninstantiableException -> {
                // start 関数のトラップは Endive が UninstantiableException に包むので、トラップとして投げ直す
                val cause = e.cause
                if (cause != null && (cause is TrapException || cause is WasmInterruptedException || cause is EndiveWasmException || cause is StackOverflowError)) rethrow(cause)
                throw WasmLinkException("インスタンスを作れない: ${e.message}", e)
            }
            is InvalidException -> throw WasmLoadException("WASM の検証に通らない: ${e.message}", e)
            else -> rethrow(e)
        }
    }

    private fun hostFunction(moduleName: String, name: String, type: WasmFunctionType, wrapper: EndiveWasmInstance, body: (LongArray) -> LongArray): HostFunction =
        HostFunction(moduleName, name, endiveType(type)) { raw, args ->
            // start 関数は build の中で実行されるので、そこから呼ばれたときに Endive のインスタンスを結び付ける
            wrapper.attachIfAbsent(raw)
            try {
                body(args)
            } catch (t: Throwable) {
                throw HostThrowable(t)
            }
        }
}

private class EndiveWasmInstance(override val module: EndiveWasmModule, private val config: WasmInstanceConfig) : WasmInstance {
    private var raw: Instance? = null
    private var memoryWrapper: WasmMemory? = null
    private var closed = false

    /** 数えるメモリ（閉じたときに確保した分を返す） */
    var budgetedMemories: List<BudgetedMemory> = emptyList()

    /** export の関数（名前ごとに1回だけ引く） */
    private val exportFunctions = HashMap<String, ExportFunction>()

    fun attach(instance: Instance) {
        raw = instance
        memoryWrapper = if (module.module.memorySection().map { it.memoryCount() > 0 }.orElse(false)) EndiveWasmMemory(instance.memory()) else null
    }

    fun attachIfAbsent(instance: Instance?) {
        if (raw == null && instance != null) attach(instance)
    }

    private fun instance(): Instance {
        check(!closed) { "閉じたインスタンス" }
        return checkNotNull(raw) { "インスタンスがまだできていない" }
    }

    override val memory: WasmMemory? get() = memoryWrapper

    override var fuel: Long
        get() {
            check(module.isFuelMetered) { "燃料を計測しないモジュール" }
            return instance().exports().global(WasmFuel.EXPORT_GLOBAL).getValue()
        }
        set(value) {
            check(module.isFuelMetered) { "燃料を計測しないモジュール" }
            instance().exports().global(WasmFuel.EXPORT_GLOBAL).setValue(value)
        }

    fun fuelExhausted(remaining: Long): Long {
        val handler = config.fuelHandler ?: throw WasmTrapException(WasmTrapKind.FUEL_EXHAUSTED, "燃料が切れた")
        return handler.onFuelExhausted(this, remaining)
    }

    @Throws(WasmTrapException::class)
    override fun call(name: String, vararg args: Long): LongArray {
        val type = requireNotNull(module.exportedFunctionType(name)) { "関数 $name は export されていない" }
        require(args.size == type.params.size) { "関数 $name の引数は ${type.params.size} 個（${args.size} 個渡された）" }
        check(!closed) { "閉じたインスタンス" }
        val function = exportFunctions.getOrPut(name) { instance().export(name) }
        val result = try {
            function.apply(*args)
        } catch (e: Throwable) {
            rethrow(e)
        }
        return result ?: LongArray(0)
    }

    override fun close() {
        if (closed) return
        closed = true
        exportFunctions.clear()
        try {
            raw?.close()
        } finally {
            budgetedMemories.forEach { it.releaseAll() }
        }
    }
}

private class EndiveWasmMemory(private val memory: EndiveMemory) : WasmMemory {
    override val pages: Int get() = memory.pages()

    override fun grow(deltaPages: Int): Int = try {
        memory.grow(deltaPages)
    } catch (e: WasmEngineException) {
        -1
    }

    /** [address]（符号なし）から [length] バイトが範囲に収まるかを確かめ、Endive のアドレスを返す。 */
    private fun check(address: Int, length: Int): Int {
        if (length < 0) throw WasmMemoryAccessException("長さが負: $length")
        val start = address.toUInt().toLong()
        if (start + length > byteSize) {
            throw WasmMemoryAccessException("メモリの範囲外: ${start}〜${start + length}（大きさ $byteSize）")
        }
        return address
    }

    override fun read(address: Int, dst: ByteArray, dstOffset: Int, length: Int) {
        require(dstOffset >= 0 && dstOffset + length <= dst.size) { "dst の範囲外" }
        System.arraycopy(memory.readBytes(check(address, length), length), 0, dst, dstOffset, length)
    }

    override fun read(address: Int, length: Int): ByteArray = memory.readBytes(check(address, length), length)

    override fun write(address: Int, src: ByteArray, srcOffset: Int, length: Int) {
        require(srcOffset >= 0 && srcOffset + length <= src.size) { "src の範囲外" }
        memory.write(check(address, length), src, srcOffset, length)
    }

    override fun readU8(address: Int): Int = memory.read(check(address, 1)).toInt() and 0xff

    override fun writeU8(address: Int, value: Int) = memory.writeByte(check(address, 1), value.toByte())

    override fun readI32(address: Int): Int = memory.readInt(check(address, 4))

    override fun writeI32(address: Int, value: Int) = memory.writeI32(check(address, 4), value)

    override fun readI64(address: Int): Long = memory.readLong(check(address, 8))

    override fun writeI64(address: Int, value: Long) = memory.writeLong(check(address, 8), value)
}
