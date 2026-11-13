package pro.ikili.kibicomputers.core.runtime.component

// コンポーネントのバイナリを読んだ結果（#262）。Component Model のバイナリ形式（Binary.md、version 0x0d）の木を、
// 宣言の順のまま持つ。添字空間の番号はセクションの出てくる順に振られるので、並べ替えたりまとめたりしない
// （#263 のリンクと、#269 の保存データが番号で指すため）。
// 数値の添字は Int で持つ（読むときに Int の範囲と上限で確かめている）。

/** 元のバイト列の中の範囲。中のコアモジュールは写さず、この範囲で持つ（読み込みのメモリを倍にしないため） */
internal data class ByteRange(val offset: Int, val length: Int) {
    val end: Int get() = offset + length

    /** [bytes] から、この範囲を写したもの */
    fun copyOf(bytes: ByteArray): ByteArray = bytes.copyOfRange(offset, end)
}

/** 添字空間の種類（`sort`）。core の種類はバイナリでは `0x00` の後の1バイト */
internal enum class Sort(val isCore: Boolean, val label: String) {
    CORE_FUNC(true, "core func"),
    CORE_TABLE(true, "core table"),
    CORE_MEMORY(true, "core memory"),
    CORE_GLOBAL(true, "core global"),
    CORE_TAG(true, "core tag"),
    CORE_TYPE(true, "core type"),
    CORE_MODULE(true, "core module"),
    CORE_INSTANCE(true, "core instance"),
    FUNC(false, "func"),
    VALUE(false, "value"),
    TYPE(false, "type"),
    COMPONENT(false, "component"),
    INSTANCE(false, "instance"),
}

/** 添字空間と、その中の番号 */
internal data class SortIdx(val sort: Sort, val index: Int)

// ---- core の型 ----

/** core の値の型。参照の型の長い形（`0x63`/`0x64`）だけ [heapType] を持つ */
internal data class CoreValType(val code: Int, val heapType: Long? = null) {
    companion object {
        val I32: CoreValType = CoreValType(0x7f)
    }
}

/** core のテーブル・メモリの大きさ（フラグと最小・最大。ページの大きさの指定は [pageSizeLog2]） */
internal data class CoreLimits(val flags: Int, val min: Long, val max: Long?, val pageSizeLog2: Int? = null)

/** core の import・export の記述 */
internal sealed interface CoreExternDesc {
    data class Func(val typeIndex: Int) : CoreExternDesc
    data class Table(val element: CoreValType, val limits: CoreLimits) : CoreExternDesc
    data class Memory(val limits: CoreLimits) : CoreExternDesc
    data class Global(val type: CoreValType, val mutable: Boolean) : CoreExternDesc
    data class Tag(val typeIndex: Int) : CoreExternDesc
}

/** core の型の定義（関数の型とモジュールの型。GC の型は対応していない） */
internal sealed interface CoreDefType {
    data class Func(val params: List<CoreValType>, val results: List<CoreValType>) : CoreDefType
    data class Module(val decls: List<CoreModuleDecl>) : CoreDefType
}

/** モジュールの型の中の宣言 */
internal sealed interface CoreModuleDecl {
    data class Import(val module: String, val name: String, val desc: CoreExternDesc) : CoreModuleDecl
    data class Type(val type: CoreDefType) : CoreModuleDecl

    /** 外側の型の別名（`alias outer`。種類は core type だけ） */
    data class OuterAlias(val count: Int, val index: Int) : CoreModuleDecl
    data class Export(val name: String, val desc: CoreExternDesc) : CoreModuleDecl
}

// ---- コンポーネントの型 ----

/** プリミティブの値の型（バイナリの1バイトの符号） */
internal enum class PrimValType(val code: Int, val label: String) {
    BOOL(0x7f, "bool"),
    S8(0x7e, "s8"),
    U8(0x7d, "u8"),
    S16(0x7c, "s16"),
    U16(0x7b, "u16"),
    S32(0x7a, "s32"),
    U32(0x79, "u32"),
    S64(0x78, "s64"),
    U64(0x77, "u64"),
    F32(0x76, "f32"),
    F64(0x75, "f64"),
    CHAR(0x74, "char"),
    STRING(0x73, "string"),
    ERROR_CONTEXT(0x64, "error-context"),
    ;

    companion object {
        private val BY_CODE = entries.associateBy { it.code }

        fun of(code: Int): PrimValType? = BY_CODE[code]
    }
}

/** 値の型（プリミティブか、型の添字） */
internal sealed interface ValType {
    data class Primitive(val type: PrimValType) : ValType
    data class Ref(val typeIndex: Int) : ValType
}

/** 名前と値の型の組（record のフィールド、関数の引数） */
internal data class LabeledValType(val name: String, val type: ValType)

/** variant の場合 */
internal data class VariantCase(val name: String, val type: ValType?)

/** 型の定義 */
internal sealed interface DefType

/** 値の型の定義（`defvaltype`） */
internal sealed interface DefValType : DefType {
    data class Primitive(val type: PrimValType) : DefValType
    data class Record(val fields: List<LabeledValType>) : DefValType
    data class Variant(val cases: List<VariantCase>) : DefValType

    /** `list<T>`。長さ固定の `list<T, N>` は [fixedLength] を持つ */
    data class ListOf(val element: ValType, val fixedLength: Long? = null) : DefValType
    data class MapOf(val key: ValType, val value: ValType) : DefValType
    data class Tuple(val types: List<ValType>) : DefValType
    data class Flags(val names: List<String>) : DefValType
    data class Enum(val names: List<String>) : DefValType
    data class Option(val type: ValType) : DefValType
    data class Result(val ok: ValType?, val err: ValType?) : DefValType
    data class Own(val typeIndex: Int) : DefValType
    data class Borrow(val typeIndex: Int) : DefValType
    data class Stream(val element: ValType?) : DefValType
    data class Future(val element: ValType?) : DefValType
}

/** 関数の型。[async] は p3 の `async` の関数 */
internal data class FuncType(val async: Boolean, val params: List<LabeledValType>, val result: ValType?) : DefType

/** resource の型（表現は core の `i32`、デストラクターは core の関数の添字） */
internal data class ResourceType(val rep: CoreValType, val destructor: Int?) : DefType

/** コンポーネントの型 */
internal data class ComponentTypeDef(val decls: List<TypeDecl>) : DefType

/** インスタンスの型 */
internal data class InstanceTypeDef(val decls: List<TypeDecl>) : DefType

/** コンポーネント型・インスタンス型の中の宣言（import はコンポーネント型の中だけ） */
internal sealed interface TypeDecl {
    data class CoreType(val type: CoreDefType) : TypeDecl
    data class Type(val type: DefType) : TypeDecl
    data class AliasDecl(val alias: Alias) : TypeDecl
    data class Import(val name: String, val desc: ExternDesc) : TypeDecl
    data class Export(val name: String, val desc: ExternDesc) : TypeDecl
}

/** import・export の記述（`externdesc`） */
internal sealed interface ExternDesc {
    /** 添字空間の種類（import・export がどの空間に番号を足すか） */
    val sort: Sort

    data class Module(val coreTypeIndex: Int) : ExternDesc {
        override val sort: Sort get() = Sort.CORE_MODULE
    }

    data class Func(val typeIndex: Int) : ExternDesc {
        override val sort: Sort get() = Sort.FUNC
    }

    /** 型の import・export。[eqTypeIndex] が `null` なら `(sub resource)` */
    data class Type(val eqTypeIndex: Int?) : ExternDesc {
        override val sort: Sort get() = Sort.TYPE
    }

    data class Component(val typeIndex: Int) : ExternDesc {
        override val sort: Sort get() = Sort.COMPONENT
    }

    data class Instance(val typeIndex: Int) : ExternDesc {
        override val sort: Sort get() = Sort.INSTANCE
    }
}

// ---- 定義 ----

/** alias */
internal sealed interface Alias {
    val sort: Sort

    /** インスタンスの export（`alias export`） */
    data class InstanceExport(override val sort: Sort, val instance: Int, val name: String) : Alias

    /** core のインスタンスの export（`alias core export`） */
    data class CoreInstanceExport(override val sort: Sort, val instance: Int, val name: String) : Alias

    /** 外側の定義（`alias outer`）。[count] 段外の [index] 番 */
    data class Outer(override val sort: Sort, val count: Int, val index: Int) : Alias
}

/** core のインスタンスの定義 */
internal sealed interface CoreInstanceExpr {
    /** モジュールのインスタンス化。引数は名前とコアのインスタンスの添字 */
    data class Instantiate(val module: Int, val args: List<Pair<String, Int>>) : CoreInstanceExpr

    /** export を並べただけのインスタンス */
    data class FromExports(val exports: List<Pair<String, SortIdx>>) : CoreInstanceExpr
}

/** インスタンスの定義 */
internal sealed interface InstanceExpr {
    /** コンポーネントのインスタンス化 */
    data class Instantiate(val component: Int, val args: List<Pair<String, SortIdx>>) : InstanceExpr

    /** export を並べただけのインスタンス */
    data class FromExports(val exports: List<Pair<String, SortIdx>>) : InstanceExpr
}

/** canon の option */
internal sealed interface CanonOption {
    /** 文字列の符号化（0: UTF-8、1: UTF-16、2: latin1+UTF-16） */
    data class StringEncoding(val code: Int) : CanonOption
    data class Memory(val coreMemory: Int) : CanonOption
    data class Realloc(val coreFunc: Int) : CanonOption
    data class PostReturn(val coreFunc: Int) : CanonOption
    data object Async : CanonOption
    data class Callback(val coreFunc: Int) : CanonOption
    data class CoreType(val coreType: Int) : CanonOption
    data object Gc : CanonOption
}

/**
 * canon の組み込み（lift・lower・resource 以外）。[code] はバイナリの1バイト目。オペランドの形は [shape]。
 * 対応するのは p3 の非同期（`cm-async`）までで、スレッド（`cm-threading`）は含めない（[ComponentValidator.FEATURES]）
 */
internal enum class CanonBuiltin(val code: Int, val label: String, val shape: Shape) {
    TASK_CANCEL(0x05, "task.cancel", Shape.NONE),
    SUBTASK_CANCEL(0x06, "subtask.cancel", Shape.FLAG),
    TASK_RETURN(0x09, "task.return", Shape.RESULT_OPTIONS),
    CONTEXT_GET(0x0a, "context.get", Shape.CONTEXT),
    CONTEXT_SET(0x0b, "context.set", Shape.CONTEXT),
    THREAD_YIELD(0x0c, "thread.yield", Shape.FLAG),
    SUBTASK_DROP(0x0d, "subtask.drop", Shape.NONE),
    STREAM_NEW(0x0e, "stream.new", Shape.TYPE),
    STREAM_READ(0x0f, "stream.read", Shape.TYPE_OPTIONS),
    STREAM_WRITE(0x10, "stream.write", Shape.TYPE_OPTIONS),
    STREAM_CANCEL_READ(0x11, "stream.cancel-read", Shape.TYPE_FLAG),
    STREAM_CANCEL_WRITE(0x12, "stream.cancel-write", Shape.TYPE_FLAG),
    STREAM_DROP_READABLE(0x13, "stream.drop-readable", Shape.TYPE),
    STREAM_DROP_WRITABLE(0x14, "stream.drop-writable", Shape.TYPE),
    FUTURE_NEW(0x15, "future.new", Shape.TYPE),
    FUTURE_READ(0x16, "future.read", Shape.TYPE_OPTIONS),
    FUTURE_WRITE(0x17, "future.write", Shape.TYPE_OPTIONS),
    FUTURE_CANCEL_READ(0x18, "future.cancel-read", Shape.TYPE_FLAG),
    FUTURE_CANCEL_WRITE(0x19, "future.cancel-write", Shape.TYPE_FLAG),
    FUTURE_DROP_READABLE(0x1a, "future.drop-readable", Shape.TYPE),
    FUTURE_DROP_WRITABLE(0x1b, "future.drop-writable", Shape.TYPE),
    ERROR_CONTEXT_NEW(0x1c, "error-context.new", Shape.OPTIONS),
    ERROR_CONTEXT_DEBUG_MESSAGE(0x1d, "error-context.debug-message", Shape.OPTIONS),
    ERROR_CONTEXT_DROP(0x1e, "error-context.drop", Shape.NONE),
    WAITABLE_SET_NEW(0x1f, "waitable-set.new", Shape.NONE),
    WAITABLE_SET_WAIT(0x20, "waitable-set.wait", Shape.FLAG_MEMORY),
    WAITABLE_SET_POLL(0x21, "waitable-set.poll", Shape.FLAG_MEMORY),
    WAITABLE_SET_DROP(0x22, "waitable-set.drop", Shape.NONE),
    WAITABLE_JOIN(0x23, "waitable.join", Shape.NONE),
    BACKPRESSURE_INC(0x24, "backpressure.inc", Shape.NONE),
    BACKPRESSURE_DEC(0x25, "backpressure.dec", Shape.NONE),
    ;

    /** オペランドの形 */
    enum class Shape {
        NONE,

        /** 真偽（`async` か `cancellable`） */
        FLAG,

        /** 型の添字 */
        TYPE,

        /** 型の添字と option */
        TYPE_OPTIONS,

        /** 型の添字と真偽（`async`） */
        TYPE_FLAG,

        /** option */
        OPTIONS,

        /** 真偽（`cancellable`）と core のメモリの添字 */
        FLAG_MEMORY,

        /** 結果の型（なくてもよい）と option */
        RESULT_OPTIONS,

        /** core の値の型（`i32`）とスロットの番号 */
        CONTEXT,
    }

    companion object {
        private val BY_CODE = entries.associateBy { it.code }

        fun of(code: Int): CanonBuiltin? = BY_CODE[code]
    }
}

/** canon の定義 */
internal sealed interface Canon {
    /** 新しく番号が付く添字空間（lift は func、ほかは core func） */
    val sort: Sort

    data class Lift(val coreFunc: Int, val options: List<CanonOption>, val typeIndex: Int) : Canon {
        override val sort: Sort get() = Sort.FUNC
    }

    data class Lower(val func: Int, val options: List<CanonOption>) : Canon {
        override val sort: Sort get() = Sort.CORE_FUNC
    }

    data class ResourceNew(val typeIndex: Int) : Canon {
        override val sort: Sort get() = Sort.CORE_FUNC
    }

    data class ResourceDrop(val typeIndex: Int) : Canon {
        override val sort: Sort get() = Sort.CORE_FUNC
    }

    data class ResourceRep(val typeIndex: Int) : Canon {
        override val sort: Sort get() = Sort.CORE_FUNC
    }

    /**
     * そのほかの組み込み。使うオペランドは [CanonBuiltin.shape] で決まり、使わないものは `null`・`false`・空。
     * [hasResult] は `task.return` で結果の型があるか
     */
    data class Builtin(
        val op: CanonBuiltin,
        val typeIndex: Int? = null,
        val flag: Boolean = false,
        val coreMemory: Int? = null,
        val contextType: CoreValType? = null,
        val slot: Int? = null,
        val hasResult: Boolean = false,
        val result: ValType? = null,
        val options: List<CanonOption> = emptyList(),
    ) : Canon {
        override val sort: Sort get() = Sort.CORE_FUNC
    }
}

/** コンポーネントの import */
internal data class ComponentImport(val name: String, val desc: ExternDesc)

/** コンポーネントの export。[desc] は型を明示したときだけ */
internal data class ComponentExport(val name: String, val target: SortIdx, val desc: ExternDesc?)

// ---- セクション ----

/** セクション。[range] はセクションの中身（id と大きさの後）の範囲 */
internal sealed interface ComponentSection {
    val range: ByteRange

    data class Custom(override val range: ByteRange, val name: String) : ComponentSection

    /** core モジュール。[range] がモジュールのバイト列（ヘッダー付きの完全なモジュール） */
    data class CoreModule(override val range: ByteRange) : ComponentSection
    data class CoreInstances(override val range: ByteRange, val items: List<CoreInstanceExpr>) : ComponentSection
    data class CoreTypes(override val range: ByteRange, val items: List<CoreDefType>) : ComponentSection

    /** 入れ子のコンポーネント */
    data class Nested(override val range: ByteRange, val component: ParsedComponent) : ComponentSection
    data class Instances(override val range: ByteRange, val items: List<InstanceExpr>) : ComponentSection
    data class Aliases(override val range: ByteRange, val items: List<Alias>) : ComponentSection
    data class Types(override val range: ByteRange, val items: List<DefType>) : ComponentSection
    data class Canons(override val range: ByteRange, val items: List<Canon>) : ComponentSection
    data class Imports(override val range: ByteRange, val items: List<ComponentImport>) : ComponentSection
    data class Exports(override val range: ByteRange, val items: List<ComponentExport>) : ComponentSection
}

/**
 * 読んだコンポーネント1つ（入れ子のものも同じ形）。[sections] は宣言の順。
 * [indexSpaceSizes] は、このコンポーネントの各添字空間に付いた番号の数（[Sort] の順）
 */
internal class ParsedComponent(
    /** コンポーネントのバイト列（ヘッダー付き）の範囲 */
    val range: ByteRange,
    /** 入れ子の深さ（一番外が 0） */
    val depth: Int,
    val sections: List<ComponentSection>,
    private val indexSpaceSizes: IntArray,
) {
    override fun equals(other: Any?): Boolean = other is ParsedComponent && range == other.range && depth == other.depth &&
        sections == other.sections && indexSpaceSizes.contentEquals(other.indexSpaceSizes)

    override fun hashCode(): Int = (range.hashCode() * 31 + depth) * 31 + sections.hashCode()

    /** 添字空間 [sort] の大きさ */
    fun indexSpaceSize(sort: Sort): Int = indexSpaceSizes[sort.ordinal]

    /** このコンポーネントの import（入れ子の中は含まない） */
    val imports: List<ComponentImport> get() = sections.filterIsInstance<ComponentSection.Imports>().flatMap { it.items }

    /** このコンポーネントの export（入れ子の中は含まない） */
    val exports: List<ComponentExport> get() = sections.filterIsInstance<ComponentSection.Exports>().flatMap { it.items }

    /** このコンポーネントで定義する core モジュールの範囲（入れ子の中は含まない。core module の添字の順ではない: import と alias は含まない） */
    val coreModules: List<ByteRange> get() = sections.filterIsInstance<ComponentSection.CoreModule>().map { it.range }

    /** 入れ子も含めた、すべての core モジュールの範囲（バイナリに出てくる順） */
    fun allCoreModules(): List<ByteRange> {
        val out = ArrayList<ByteRange>()
        fun walk(c: ParsedComponent) {
            for (s in c.sections) {
                when (s) {
                    is ComponentSection.CoreModule -> out += s.range
                    is ComponentSection.Nested -> walk(s.component)
                    else -> Unit
                }
            }
        }
        walk(this)
        return out
    }
}
