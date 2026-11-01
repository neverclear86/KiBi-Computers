package pro.ikili.kibicomputers.core.runtime.endive

import org.jetbrains.annotations.ApiStatus
import pro.ikili.kibicomputers.core.runtime.WasmAsync
import pro.ikili.kibicomputers.core.runtime.WasmAsyncState
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
import pro.ikili.kibicomputers.core.runtime.WasmTableBudget
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind
import pro.ikili.kibicomputers.core.runtime.WasmValueType
import run.endive.compiler.InterpreterFallback
import run.endive.compiler.MachineFactoryCompiler
import run.endive.runtime.ByteBufferMemory
import run.endive.runtime.CompiledModule
import run.endive.runtime.ExportFunction
import run.endive.runtime.GlobalFactory
import run.endive.runtime.GlobalInstance
import run.endive.runtime.HostFunction
import run.endive.runtime.ImportValues
import run.endive.runtime.Instance
import run.endive.runtime.TableInstance
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
import run.endive.wasm.types.Table
import run.endive.wasm.types.TableLimits
import run.endive.wasm.types.ValType
import run.endive.runtime.Memory as EndiveMemory
import run.endive.runtime.WasmException as EndiveWasmException
import run.endive.wasm.WasmModule as EndiveModule

/**
 * Endive による [WasmRuntime]。
 *
 * [load] でパース・検証の後に、ランタイムコンパイラで JVM のバイトコードにコンパイルする（#29 の決定 B、#37）。
 * JVM のメソッドの上限を超える関数だけは、Endive がその関数をインタプリタで動かす（[InterpreterFallback.WARN]。ログに出る）。
 * コンパイルの後は、関数の本体を除いたモジュール（[CompiledModule.wasmModule]）だけを持ち、パースした命令の列は捨てる
 * （挿入後の lua.wasm で、ヒープが約 18 MB → 約 0.08 MB）。同じ WASM の読み込みの共有は、呼び出し側（カーネルの `ProgramCache`）が受け持つ。
 *
 * メモリの上限（#39）は、Endive のメモリを作るときに上限を縮め（[WasmInstanceConfig.maxMemoryPages]）、
 * [WasmInstanceConfig.memoryBudget] があれば `grow` を数える包み（[BudgetedMemory]）をかぶせて守る。
 * 共有しないメモリは、書き込んだページだけ JVM のメモリを確保する（[LazyMemory]、#334）。
 * 上限と RAM は宣言したページ数（最初のページ数と `grow` の合計）で数え、確保したページ数では数えない。
 *
 * テーブル（#361）も同じように、作るときに要素数の上限を縮め（[WasmInstanceConfig.maxTableElements]）、
 * [WasmInstanceConfig.tableBudget] があれば、要素数 × 要素1つのバイト数を数える（[BudgetedTable]）。
 */
@ApiStatus.Internal
public class EndiveRuntime internal constructor(
    /** `false` ならコンパイルせずインタプリタで動かす（テストで、インタプリタとの結果を比べるため） */
    private val compile: Boolean,
) : WasmRuntime {
    /** ランタイムコンパイラで動かすランタイムを作る */
    public constructor() : this(true)

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
        } catch (e: StackOverflowError) {
            throw WasmLoadException("WASM を読み込めない: 入れ子が深すぎる", e)
        }
        val interpreted = try {
            EndiveWasmModule(module, null)
        } catch (e: WasmLoadException) {
            throw e
        } catch (e: RuntimeException) {
            // Endive の検証は export の番号の範囲などを確かめないことがある（#40 で見つけた）。ここで分かったものも断る
            throw WasmLoadException("WASM の検証に通らない: $e", e)
        }
        if (!compile) return interpreted
        // コンパイルできなければ、インタプリタで動かす（パースと検証は通っているので、動かせる WASM を断らない）
        val compiled = compileOrNull(module) ?: return interpreted
        // パースした命令の列（module）はここで手放す。持つのは関数の本体を除いたモジュールとコンパイル結果だけ
        return EndiveWasmModule(compiled.wasmModule(), compiled)
    }

    /**
     * [module] をコンパイルする。できなければ `null`（ログに出す）。
     *
     * Endive のコンパイラは、JVM のメソッドの上限を超える関数をインタプリタに回す（[InterpreterFallback.WARN]）が、
     * 上限の近くで ASM が上限超えを見分けられずに別の例外で落ちることがある（待機点への変換の後に燃料の計測コードを
     * 挿入した lua.wasm の `luaV_execute`。#37 で見つけた。`exec` は逆の順にして上限に収めている。#341）。そのときは、命令の数が多い関数から順にインタプリタに回して
     * [MAX_COMPILE_RETRIES] 回までやり直す。それでもだめ（SIMD の局所変数など、コンパイラが対応しない作り）なら `null`。
     *
     * ただし、例外処理（タグ）を使うモジュールでは、関数ごとにインタプリタとコンパイルしたコードを混ぜない
     * （混ぜると、Lua のコルーチンの戻り値が欠けるなど、結果が変わった。#341）。1つでもコンパイルできない関数があれば、
     * モジュール全体をインタプリタで動かす。
     */
    private fun compileOrNull(module: EndiveModule): CompiledModule? {
        val mixable = module.tagSection().map { it.tagCount() == 0 }.orElse(true) && module.importSection().count(ExternalType.TAG) == 0
        val interpreted = LinkedHashSet<Int>()
        var candidates: List<Int>? = null
        var attempt = 0
        while (true) {
            val failure = try {
                val builder = MachineFactoryCompiler.builder(module)
                when {
                    !mixable -> builder.withInterpreterFallback(InterpreterFallback.FAIL)
                    interpreted.isEmpty() -> builder.withInterpreterFallback(InterpreterFallback.WARN)
                    // 決めた関数をインタプリタに回すときは、ほかの関数の自動の切り替えは使えない（Endive の制約）
                    else -> builder.withInterpreterFallback(InterpreterFallback.FAIL).withInterpretedFunctions(interpreted)
                }
                return builder.compileModule().also {
                    if (interpreted.isNotEmpty()) LOG.log(System.Logger.Level.WARNING, "WASM の関数 $interpreted をインタプリタで動かす（コンパイルできない）")
                }
            } catch (e: RuntimeException) {
                e
            } catch (e: StackOverflowError) {
                e
            } catch (e: LinkageError) {
                // 生成したクラスを JVM が受け付けない（VerifyError など）
                e
            }
            val next = if (mixable) (candidates ?: largeFunctions(module).also { candidates = it }).firstOrNull { it !in interpreted } else null
            if (attempt++ >= MAX_COMPILE_RETRIES || next == null) {
                LOG.log(System.Logger.Level.WARNING, "WASM をコンパイルできないので、モジュール全体をインタプリタで動かす: $failure")
                return null
            }
            interpreted += next
        }
    }

    public companion object {
        /** ランタイムの名前 */
        public const val NAME: String = "endive"

        private val LOG: System.Logger = System.getLogger("kibicomputers.wasm")

        /** コンパイルに失敗したとき、関数をインタプリタに回してやり直す回数 */
        private const val MAX_COMPILE_RETRIES: Int = 3

        /** やり直しでインタプリタに回す候補にする、関数の命令の数の下限（これより小さい関数は JVM のメソッドの上限に届かない） */
        private const val LARGE_FUNCTION_INSTRUCTIONS: Int = 4096

        /** 命令の数が [LARGE_FUNCTION_INSTRUCTIONS] 以上の関数の番号（import を含めた番号）。大きい順 */
        private fun largeFunctions(module: EndiveModule): List<Int> {
            val imported = module.importSection().count(ExternalType.FUNCTION)
            val bodies = module.codeSection().functionBodies()
            return bodies.indices
                .map { it to bodies[it].instructions().size }
                .filter { it.second >= LARGE_FUNCTION_INSTRUCTIONS }
                .sortedByDescending { it.second }
                .map { it.first + imported }
        }
    }
}

/** メモリ・テーブルの上限のせいで作れない（#39・#361）。Endive の中を通す間だけ使い、[WasmLinkException] にして投げ直す。 */
private class MemoryLimitFailure(message: String) : RuntimeException(message, null, false, false)

/**
 * `grow` を [budget] で数える Endive のメモリ（#39）。ほかの操作は [inner] にそのまま渡す。
 *
 * [reserved] は [budget] から確保したページ数。インスタンスを閉じたときに [releaseAll] で返す。
 */
private class BudgetedMemory(
    val inner: EndiveMemory,
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
 * 要素数の上限を縮め、確保を [budget] で数える Endive のテーブル（#361）。
 *
 * Endive のテーブルは `table.grow` のたびに配列を丸ごと確保し直すので、増やす前に上限と [budget] を確かめ、
 * 断られたら Endive に渡さずに -1 を返す（失敗した grow は数えない。#335 と同じ）。
 * [reserved] は [budget] から確保したバイト数。インスタンスを閉じたときに [releaseAll] で返す。
 */
private class BudgetedTable(
    table: Table,
    initValue: Int,
    private val budget: WasmTableBudget?,
    private val elementBytes: Long,
    private var reserved: Long,
) : TableInstance(table, initValue) {
    override fun grow(size: Int, value: Int, instance: Instance?): Int = counted(size) { super.grow(size, value, instance) }

    override fun growWithRef(size: Int, refValue: Any?, instance: Instance?): Int = counted(size) { super.growWithRef(size, refValue, instance) }

    @Synchronized
    private fun counted(size: Int, grow: () -> Int): Int {
        // 0 は今の大きさを返すだけ。負（符号なしで 2^31 以上）は Endive が -1 を返す
        if (size <= 0) return grow()
        if (size().toLong() + size > limits().max()) return -1
        val bytes = size * elementBytes
        val counting = budget != null && reserved >= 0
        if (counting && !budget!!.tryReserveBytes(bytes)) return -1
        val previous = try {
            grow()
        } catch (t: Throwable) {
            if (counting) budget!!.releaseBytes(bytes)
            throw t
        }
        if (!counting) return previous
        if (previous < 0) {
            budget!!.releaseBytes(bytes)
            return -1
        }
        reserved += bytes
        return previous
    }

    /** 確保した分をすべて返す。以後 `grow` は数えない（閉じたインスタンスのテーブル）。2回呼んでもよい */
    @Synchronized
    fun releaseAll() {
        if (reserved > 0) budget?.releaseBytes(reserved)
        reserved = -1
    }
}

/**
 * テーブルの要素1つが使う JVM のメモリ（バイト。#361）の見積もり。Endive は要素ごとに `int`（4）と
 * インスタンスへの参照（圧縮しない参照で 8）を持ち、GC の参照のテーブルはさらに値の参照（8）を持つ。
 */
private fun tableElementBytes(table: Table): Long = if (table.elementType().isObjectRef()) 20L else 12L

/**
 * [config] の上限で Endive のテーブルを作る（#361）。数えるテーブルは [created] に足す。
 *
 * Endive の [TableInstance] は、`grow` のたびにモジュールの [Table] の要素数を書き換える。同じモジュールから作る
 * ほかのインスタンスに響かないように、インスタンスごとに [Table] を作り直し、そのときに上限を縮める。
 * 最初の要素数が上限を超える、または確保を断られたら [MemoryLimitFailure] を投げる。
 */
private fun createTable(table: Table, initValue: Int, config: WasmInstanceConfig, created: MutableList<BudgetedTable>): TableInstance {
    val limits = table.limits()
    val initial = limits.min()
    val max = minOf(limits.max(), config.maxTableElements.toLong())
    if (initial > max) {
        throw MemoryLimitFailure("テーブルの最初の要素数 $initial が上限 $max を超える")
    }
    val own = Table(table.elementType(), TableLimits(initial, max, limits.shared()), table.initialize())
    val budget = config.tableBudget
    val elementBytes = tableElementBytes(table)
    val initialBytes = initial * elementBytes
    if (budget != null && initialBytes > 0 && !budget.tryReserveBytes(initialBytes)) {
        throw MemoryLimitFailure("テーブルの最初の $initial 要素を確保できない（RAM が足りない）")
    }
    val reserved = if (budget != null) initialBytes else 0L
    return try {
        BudgetedTable(own, initValue, budget, elementBytes, reserved)
    } catch (t: Throwable) {
        if (budget != null && initialBytes > 0) budget.releaseBytes(initialBytes)
        throw t
    }.also { created += it }
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
    if (budget == null) return newMemory(MemoryLimits(initial, max, limits.shared()))
    if (initial > 0 && !budget.tryReserve(initial)) {
        throw MemoryLimitFailure("メモリの最初の $initial ページを確保できない（RAM が足りない）")
    }
    val inner = try {
        newMemory(MemoryLimits(initial, max, limits.shared()))
    } catch (t: Throwable) {
        if (initial > 0) budget.release(initial)
        throw t
    }
    return BudgetedMemory(inner, budget, initial).also { created += it }
}

/**
 * Endive のメモリを作る。共有しないメモリは、書き込んだページだけ確保する [LazyMemory]（#334）。
 * 共有メモリは Endive の [ByteBufferMemory]（最初のページをすべて確保する）。
 */
private fun newMemory(limits: MemoryLimits): EndiveMemory = if (limits.shared()) ByteBufferMemory(limits) else LazyMemory(limits)

/**
 * [memory] のうち、JVM のメモリを確保したページ数（#334。テストと計測用）。
 * 書き込んだページだけ確保するメモリでなければ、宣言したページ数を返す。
 */
internal fun committedPages(memory: WasmMemory): Int {
    val inner = (memory as? EndiveWasmMemory)?.raw ?: return memory.pages
    val lazy = (inner as? BudgetedMemory)?.inner ?: inner
    return (lazy as? LazyMemory)?.committedPages ?: memory.pages
}

/** [module] をコンパイルしたコードで動かすなら `true`（#37。テスト用） */
internal fun isCompiled(module: WasmModule): Boolean = (module as? EndiveWasmModule)?.isCompiled ?: false

/** 戻り値のないホストの関数の戻り値 */
private val NO_VALUES = LongArray(0)

/** 状態のグローバルの値 → 状態 */
private val ASYNC_STATES: Array<WasmAsyncState> = WasmAsyncState.values()

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

/**
 * Endive のモジュール。[compiled] があればコンパイルしたコードで、なければインタプリタで動かす。
 * [module] は、コンパイルしたなら関数の本体を除いたもの（[CompiledModule.wasmModule]）。
 */
private class EndiveWasmModule(val module: EndiveModule, private val compiled: CompiledModule?) : WasmModule {
    /** コンパイルしたコードで動かすなら `true`（テスト用） */
    val isCompiled: Boolean get() = compiled != null

    /** import の関数の型（関数の番号の順） */
    private val importFunctionTypes: List<FunctionType>

    private val rawImports: List<WasmImportDescriptor>
    private val rawExports: List<WasmExportDescriptor>

    override val isFuelMetered: Boolean

    /** 燃料の残りを入れるグローバルの番号。計測しないなら -1 */
    private val fuelGlobalIndex: Int

    override val isAsyncified: Boolean
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
        // 待機点への変換（#329）: import 2つと状態のグローバルの export は、そろっていて型が合うときだけ
        val pushImport = rawImports.firstOrNull { it.module == WasmAsync.IMPORT_MODULE && it.name == WasmAsync.PUSH }
        val popImport = rawImports.firstOrNull { it.module == WasmAsync.IMPORT_MODULE && it.name == WasmAsync.POP }
        val stateExport = rawExports.firstOrNull { it.name == WasmAsync.STATE_EXPORT }
        val asyncParts = listOfNotNull(pushImport, popImport, stateExport).size
        if (asyncParts != 0 && asyncParts != 3) {
            throw WasmLoadException("待機点への変換の import・export の一部しかない")
        }
        if (asyncParts == 3) {
            if (pushImport!!.functionType != WasmAsync.PUSH_TYPE || popImport!!.functionType != WasmAsync.POP_TYPE || stateExport!!.kind != WasmExternKind.GLOBAL) {
                throw WasmLoadException("待機点への変換の import・export の型が違う")
            }
        }
        isAsyncified = asyncParts == 3
        val hidden = listOfNotNull(fuelImport, fuelExport, pushImport, popImport, stateExport)
        imports = rawImports.filter { imp -> hidden.none { it === imp } }
        exports = rawExports.filter { exp -> hidden.none { it === exp } }
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
            if (isAsyncified && imp.module == WasmAsync.IMPORT_MODULE && (imp.name == WasmAsync.PUSH || imp.name == WasmAsync.POP)) {
                val stack = config.asyncStack ?: throw WasmLinkException("待機点への変換をかけたモジュールに asyncStack がない")
                values.addFunction(
                    if (imp.name == WasmAsync.PUSH) {
                        hostFunction(imp.module, imp.name, type, wrapper) { args -> stack.push(args[0]); NO_VALUES }
                    } else {
                        hostFunction(imp.module, imp.name, type, wrapper) { longArrayOf(stack.pop()) }
                    },
                )
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
        // 数えるテーブル（#361。同じく、失敗したら返す）
        val tables = ArrayList<BudgetedTable>(1)
        wrapper.budgetedTables = tables
        val instance = try {
            // withStart(false): export の `_start` は呼ばない（start セクションの関数は build の中で実行される）
            (if (compiled != null) Instance.builder(compiled) else Instance.builder(module))
                .withImportValues(values.build())
                .withGlobalFactory(globals)
                .withMemoryFactory { limits -> createMemory(limits, config, memories) }
                .withTableFactory { table, initValue -> createTable(table, initValue, config, tables) }
                .withStart(false)
                .build()
        } catch (e: Throwable) {
            memories.forEach { it.releaseAll() }
            tables.forEach { it.releaseAll() }
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

    /** 数えるテーブル（#361。閉じたときに確保した分を返す） */
    var budgetedTables: List<BudgetedTable> = emptyList()

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

    override var asyncState: WasmAsyncState
        get() {
            check(module.isAsyncified) { "待機点への変換をかけていないモジュール" }
            val v = instance().exports().global(WasmAsync.STATE_EXPORT).getValue().toInt()
            return ASYNC_STATES.getOrNull(v) ?: throw IllegalStateException("待機点への変換の状態が壊れている: $v")
        }
        set(value) {
            check(module.isAsyncified) { "待機点への変換をかけていないモジュール" }
            instance().exports().global(WasmAsync.STATE_EXPORT).setValue(value.ordinal.toLong())
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
            budgetedTables.forEach { it.releaseAll() }
        }
    }
}

private class EndiveWasmMemory(private val memory: EndiveMemory) : WasmMemory {
    val raw: EndiveMemory get() = memory

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
