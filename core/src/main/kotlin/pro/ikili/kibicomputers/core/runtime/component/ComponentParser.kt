package pro.ikili.kibicomputers.core.runtime.component

import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.validation.LoadDeadline
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * コンポーネントのバイナリを読む自前のパーサー（#262。ADR 0260 の方式 B）。
 *
 * Component Model のバイナリ形式（version 0x0d。wasm-tools 1.256.0 の wasmparser と同じ読み方）を読み、
 * セクションを宣言の順に並べた木（[ParsedComponent]）にする。型・import・export・alias・canon・インスタンスは
 * デコードし、中の core モジュールは写さずに範囲（[ByteRange]）で持つ。
 *
 * 型の正しさ（添字が範囲にあるか、型が合うか）は確かめない。それは後の検証（[ComponentValidator]）がする。
 * ここで確かめるのは、小さなバイト列で大きな確保や長い処理をさせないための構造の上限（[ComponentLimits]）と、
 * バイナリとして読めること（LEB128、UTF-8 の名前、知っている符号）だけ。数・長さは、上限と残りのバイト数の両方で
 * 確かめてから確保する。値（`cm-values`）・start・スレッド（`cm-threading`）・GC の型など、
 * 対応しない機能の符号は、ここで断る。
 */
internal object ComponentParser {
    private const val SEC_CUSTOM = 0
    private const val SEC_CORE_MODULE = 1
    private const val SEC_CORE_INSTANCE = 2
    private const val SEC_CORE_TYPE = 3
    private const val SEC_COMPONENT = 4
    private const val SEC_INSTANCE = 5
    private const val SEC_ALIAS = 6
    private const val SEC_TYPE = 7
    private const val SEC_CANON = 8
    private const val SEC_START = 9
    private const val SEC_IMPORT = 10
    private const val SEC_EXPORT = 11
    private const val SEC_VALUE = 12

    /** 何個の要素を読むごとに時間の上限を確かめるか */
    private const val DEADLINE_INTERVAL = 256

    /**
     * [bytes] 全体をコンポーネントとして読む。だめなら [WasmLoadException]。
     * ヘッダーが layer 1・version 0x0d であることも確かめる
     */
    @Throws(WasmLoadException::class)
    fun parse(bytes: ByteArray, limits: ComponentLimits, deadline: LoadDeadline?): ParsedComponent {
        if (bytes.size > limits.maxBytes) fail("大きすぎる（${bytes.size} バイト。上限 ${limits.maxBytes}）")
        val state = State(bytes, limits, deadline)
        val result = try {
            state.component(0, bytes.size, 0)
        } catch (e: StackOverflowError) {
            // 深さは上限で抑えているが、スレッドのスタックが小さいときのため
            fail("入れ子が深すぎる")
        }
        deadline?.check("コンポーネントの構造の確認")
        return result
    }

    /** 構造の失敗 */
    internal fun fail(message: String): Nothing =
        throw WasmLoadException("コンポーネントの構造が上限を超える、または壊れている: $message")

    /** バイナリ全体で数える合計 */
    private class Totals {
        var coreModules = 0
        var components = 0
        var types = 0
        var imports = 0
        var exports = 0
        var aliases = 0
        var canons = 0
        var instances = 0
        var items = 0

        /** デコードしたベクターの要素の合計（木のメモリの目安） */
        var decoded = 0L
    }

    private class State(val bytes: ByteArray, val limits: ComponentLimits, val deadline: LoadDeadline?) {
        val totals = Totals()

        /** 要素を1つ読むたびに呼ぶ。一定の間隔で時間の上限を確かめる */
        fun tick() {
            if (++totals.items % DEADLINE_INTERVAL == 0) deadline?.check("コンポーネントの構造の確認")
        }

        /** [n] 個の要素をデコードする前に、総数の上限を確かめる */
        fun reserve(n: Int) {
            totals.decoded += n
            if (totals.decoded > limits.maxDecodedItems) fail("デコードする要素の総数が上限 ${limits.maxDecodedItems} を超える")
        }

        fun add(what: String, current: Int, n: Int, max: Int): Int {
            val total = current.toLong() + n
            if (total > max) fail("${what}の数が上限 $max を超える")
            return total.toInt()
        }

        /** [start]〜[end] のコンポーネント（ヘッダー付き）を読む */
        fun component(start: Int, end: Int, depth: Int): ParsedComponent {
            val length = end - start
            if (WasmBinaryHeader.kindOf(bytes, start, length) != WasmBinaryKind.COMPONENT) {
                fail(if (depth == 0) "コンポーネントのヘッダーがない" else "入れ子のコンポーネントのヘッダーがない")
            }
            val version = WasmBinaryHeader.version(bytes, start)
            if (version != WasmBinaryHeader.COMPONENT_VERSION) {
                throw WasmLoadException(
                    "対応していないコンポーネントの版（version 0x${version.toString(16)}。対応するのは 0x${WasmBinaryHeader.COMPONENT_VERSION.toString(16)}）",
                )
            }
            val spaces = IntArray(Sort.entries.size)
            val sections = ArrayList<ComponentSection>()
            val r = Reader(this, start + WasmBinaryHeader.SIZE, end)
            while (r.pos < r.end) {
                val id = r.u8()
                val size = r.u32()
                if (size > r.end - r.pos) fail("セクション $id の大きさ $size が残りを超える")
                val s = Reader(this, r.pos, r.pos + size.toInt())
                r.pos = s.end
                val range = ByteRange(s.pos, s.end - s.pos)
                val section = section(id, s, range, depth, spaces)
                if (s.pos != s.end) fail("セクション $id の後ろに余分なバイトがある")
                sections += section
                tick()
            }
            return ParsedComponent(ByteRange(start, length), depth, sections, spaces)
        }

        private fun section(id: Int, s: Reader, range: ByteRange, depth: Int, spaces: IntArray): ComponentSection = when (id) {
            SEC_CUSTOM -> {
                val name = s.name()
                s.pos = s.end
                ComponentSection.Custom(range, name)
            }
            SEC_CORE_MODULE -> {
                totals.coreModules = add("コアモジュール", totals.coreModules, 1, limits.maxCoreModules)
                s.pos = s.end
                spaces[Sort.CORE_MODULE.ordinal]++
                ComponentSection.CoreModule(range)
            }
            SEC_CORE_INSTANCE -> {
                val items = s.vec("core instance") { coreInstance() }
                totals.instances = add("インスタンス", totals.instances, items.size, limits.maxInstances)
                spaces[Sort.CORE_INSTANCE.ordinal] += items.size
                ComponentSection.CoreInstances(range, items)
            }
            SEC_CORE_TYPE -> {
                val items = s.vec("core type") { coreDefType(0) }
                spaces[Sort.CORE_TYPE.ordinal] += items.size
                ComponentSection.CoreTypes(range, items)
            }
            SEC_COMPONENT -> {
                if (depth + 1 > limits.maxNestingDepth) fail("コンポーネントの入れ子が深すぎる（上限 ${limits.maxNestingDepth}）")
                totals.components = add("入れ子のコンポーネント", totals.components, 1, limits.maxComponents)
                val nested = component(s.pos, s.end, depth + 1)
                s.pos = s.end
                spaces[Sort.COMPONENT.ordinal]++
                ComponentSection.Nested(range, nested)
            }
            SEC_INSTANCE -> {
                val items = s.vec("instance") { instance() }
                totals.instances = add("インスタンス", totals.instances, items.size, limits.maxInstances)
                spaces[Sort.INSTANCE.ordinal] += items.size
                ComponentSection.Instances(range, items)
            }
            SEC_ALIAS -> {
                val items = s.vec("alias") { alias() }
                totals.aliases = add("alias", totals.aliases, items.size, limits.maxAliases)
                for (a in items) spaces[a.sort.ordinal]++
                ComponentSection.Aliases(range, items)
            }
            SEC_TYPE -> {
                val items = s.vec("type") { defType(0) }
                spaces[Sort.TYPE.ordinal] += items.size
                ComponentSection.Types(range, items)
            }
            SEC_CANON -> {
                val items = s.vec("canon") { canon() }
                totals.canons = add("canon", totals.canons, items.size, limits.maxCanons)
                for (c in items) spaces[c.sort.ordinal]++
                ComponentSection.Canons(range, items)
            }
            SEC_IMPORT -> {
                val items = s.vec("import") { ComponentImport(externName(), externDesc()) }
                totals.imports = add("import", totals.imports, items.size, limits.maxImports)
                for (i in items) spaces[i.desc.sort.ordinal]++
                ComponentSection.Imports(range, items)
            }
            SEC_EXPORT -> {
                val items = s.vec("export") {
                    val name = externName()
                    val target = sortIdx()
                    val desc = when (val b = u8()) {
                        0x00 -> null
                        0x01 -> externDesc()
                        else -> fail("export の型の指定が壊れている（0x${b.toString(16)}）")
                    }
                    ComponentExport(name, target, desc)
                }
                totals.exports = add("export", totals.exports, items.size, limits.maxExports)
                for (e in items) spaces[e.target.sort.ordinal]++
                ComponentSection.Exports(range, items)
            }
            SEC_START -> fail("start のセクションには対応していない（値の機能 cm-values）")
            SEC_VALUE -> fail("値のセクションには対応していない（値の機能 cm-values）")
            else -> fail("知らないセクション $id")
        }

        fun countType(n: Int) {
            totals.types = add("型", totals.types, n, limits.maxTypes)
        }
    }

    /** [bytes] の [pos]〜[end] を読む */
    private class Reader(val state: State, var pos: Int, val end: Int) {
        val bytes: ByteArray get() = state.bytes
        val limits: ComponentLimits get() = state.limits

        fun peek(): Int {
            if (pos >= end) fail("途中で終わっている")
            return bytes[pos].toInt() and 0xff
        }

        fun u8(): Int = peek().also { pos++ }

        fun bool(): Boolean = when (val b = u8()) {
            0 -> false
            1 -> true
            else -> fail("真偽の値が壊れている（0x${b.toString(16)}）")
        }

        /** 符号なし LEB128 の 32 ビット */
        fun u32(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = u8()
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
                if (shift >= 35) fail("整数が長すぎる")
            }
            if (result > 0xffff_ffffL) fail("整数が 32 ビットを超える")
            return result
        }

        /** 符号なし LEB128 の 64 ビット（64 ビットのメモリ・テーブルの大きさ） */
        fun u64(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = u8()
                if (shift == 63 && b and 0x7e != 0) fail("整数が 64 ビットを超える")
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
                if (shift > 63) fail("整数が長すぎる")
            }
            return result
        }

        /** 符号つき LEB128 の 33 ビット */
        fun s33(): Long {
            var result = 0L
            var shift = 0
            var b: Int
            do {
                b = u8()
                result = result or ((b and 0x7f).toLong() shl shift)
                shift += 7
                if (shift > 35) fail("整数が長すぎる")
            } while (b and 0x80 != 0)
            if (shift < 64 && b and 0x40 != 0) result = result or (-1L shl shift)
            if (result < -(1L shl 32) || result >= (1L shl 32)) fail("整数が 33 ビットを超える")
            return result
        }

        /** 添字（32 ビットの符号なし整数で、Int に収まるもの） */
        fun index(): Int {
            val v = u32()
            if (v > Int.MAX_VALUE) fail("添字 $v が大きすぎる")
            return v.toInt()
        }

        /** ベクターの数を読み、上限と残りのバイト数で確かめる（要素は 1 バイト以上） */
        fun count(what: String): Int {
            val n = u32()
            if (n > limits.maxVectorItems) fail("${what}の数 $n が上限 ${limits.maxVectorItems} を超える")
            if (n > end - pos) fail("${what}の数 $n が残りのバイト数に合わない")
            state.reserve(n.toInt())
            return n.toInt()
        }

        inline fun <T> vec(what: String, item: Reader.() -> T): List<T> {
            val n = count(what)
            val out = ArrayList<T>(n)
            repeat(n) {
                out += item()
                state.tick()
            }
            return out
        }

        /** 長さ付きの UTF-8 の名前 */
        fun name(): String {
            val n = u32()
            if (n > limits.maxNameBytes) fail("名前が長すぎる（$n バイト。上限 ${limits.maxNameBytes}）")
            if (n > end - pos) fail("名前の長さ $n が残りを超える")
            val len = n.toInt()
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val s = try {
                decoder.decode(ByteBuffer.wrap(bytes, pos, len)).toString()
            } catch (e: CharacterCodingException) {
                fail("名前が UTF-8 として正しくない")
            }
            pos += len
            return s
        }

        /** import・export の名前（`0x00`/`0x01` と名前。オプション付きの `0x02` は cm-implements なので断る） */
        fun externName(): String = when (val b = u8()) {
            0x00, 0x01 -> name()
            0x02 -> fail("オプション付きの名前には対応していない（cm-implements）")
            else -> fail("名前の形が壊れている（0x${b.toString(16)}）")
        }

        // ---- 添字空間 ----

        /** core の export の種類（`external_kind`） */
        fun coreExternalKind(b: Int): Sort = when (b) {
            0x00 -> Sort.CORE_FUNC
            0x01 -> Sort.CORE_TABLE
            0x02 -> Sort.CORE_MEMORY
            0x03 -> Sort.CORE_GLOBAL
            0x04 -> Sort.CORE_TAG
            else -> fail("知らない core の種類 0x${b.toString(16)}")
        }

        /** core の sort（`core:sort`） */
        fun coreSort(b: Int): Sort = when (b) {
            0x10 -> Sort.CORE_TYPE
            0x11 -> Sort.CORE_MODULE
            0x12 -> Sort.CORE_INSTANCE
            else -> coreExternalKind(b)
        }

        /** コンポーネントの sort（`sort`。core のものは `0x00` の後に core の sort） */
        fun sort(): Sort = when (val b = u8()) {
            0x00 -> coreSort(u8())
            0x01 -> Sort.FUNC
            0x02 -> Sort.VALUE
            0x03 -> Sort.TYPE
            0x04 -> Sort.COMPONENT
            0x05 -> Sort.INSTANCE
            else -> fail("知らない sort 0x${b.toString(16)}")
        }

        fun sortIdx(): SortIdx = SortIdx(sort(), index())

        // ---- core ----

        fun coreValType(): CoreValType = when (val b = u8()) {
            0x63, 0x64 -> CoreValType(b, s33())
            else -> CoreValType(b)
        }

        fun coreLimits(): CoreLimits {
            val flags = u8()
            if (flags and 0x0f.inv() != 0) fail("大きさのフラグが壊れている（0x${flags.toString(16)}）")
            val wide = flags and 0x04 != 0
            val min = if (wide) u64() else u32()
            val max = if (flags and 0x01 != 0) (if (wide) u64() else u32()) else null
            val pageSize = if (flags and 0x08 != 0) index() else null
            return CoreLimits(flags, min, max, pageSize)
        }

        fun coreExternDesc(): CoreExternDesc = when (val b = u8()) {
            0x00 -> CoreExternDesc.Func(index())
            0x01 -> CoreExternDesc.Table(coreValType(), coreLimits())
            0x02 -> CoreExternDesc.Memory(coreLimits())
            0x03 -> {
                val type = coreValType()
                val m = u8()
                if (m > 1) fail("グローバルの可変の指定が壊れている（0x${m.toString(16)}）")
                CoreExternDesc.Global(type, m == 1)
            }
            0x04 -> {
                if (u8() != 0x00) fail("タグの属性が壊れている")
                CoreExternDesc.Tag(index())
            }
            else -> fail("知らない core の import の種類 0x${b.toString(16)}")
        }

        fun coreDefType(depth: Int): CoreDefType {
            state.countType(1)
            return when (val b = u8()) {
                0x60 -> CoreDefType.Func(vec("core の引数") { coreValType() }, vec("core の結果") { coreValType() })
                0x50 -> {
                    if (depth + 1 > limits.maxTypeDepth) fail("型の入れ子が深すぎる（上限 ${limits.maxTypeDepth}）")
                    CoreDefType.Module(vec("モジュール型の宣言") { coreModuleDecl(depth + 1) })
                }
                else -> fail("対応していない core の型 0x${b.toString(16)}（GC の型など）")
            }
        }

        fun coreModuleDecl(depth: Int): CoreModuleDecl = when (val b = u8()) {
            0x00 -> CoreModuleDecl.Import(name(), name(), coreExternDesc())
            0x01 -> CoreModuleDecl.Type(coreDefType(depth))
            0x02 -> {
                if (u8() != 0x10) fail("モジュール型の中の alias は core type だけ")
                if (u8() != 0x01) fail("モジュール型の中の alias は outer だけ")
                CoreModuleDecl.OuterAlias(index(), index())
            }
            0x03 -> CoreModuleDecl.Export(name(), coreExternDesc())
            else -> fail("知らないモジュール型の宣言 0x${b.toString(16)}")
        }

        fun coreInstance(): CoreInstanceExpr = when (val b = u8()) {
            0x00 -> CoreInstanceExpr.Instantiate(
                index(),
                vec("core のインスタンス化の引数") {
                    val name = name()
                    if (u8() != 0x12) fail("core のインスタンス化の引数はインスタンスだけ")
                    name to index()
                },
            )
            0x01 -> CoreInstanceExpr.FromExports(vec("core の export") { name() to SortIdx(coreExternalKind(u8()), index()) })
            else -> fail("知らない core instance の形 0x${b.toString(16)}")
        }

        // ---- コンポーネント ----

        fun instance(): InstanceExpr = when (val b = u8()) {
            0x00 -> InstanceExpr.Instantiate(index(), vec("インスタンス化の引数") { name() to sortIdx() })
            0x01 -> InstanceExpr.FromExports(vec("インスタンスの export") { externName() to sortIdx() })
            else -> fail("知らない instance の形 0x${b.toString(16)}")
        }

        fun alias(): Alias {
            val first = u8()
            val coreByte = if (first == 0x00) u8() else -1
            return when (val target = u8()) {
                0x00 -> Alias.InstanceExport(sortOf(first, coreByte), index(), name())
                0x01 -> {
                    if (first != 0x00) fail("core export の alias の種類が core でない")
                    Alias.CoreInstanceExport(coreExternalKind(coreByte), index(), name())
                }
                0x02 -> {
                    val sort = sortOf(first, coreByte)
                    if (sort != Sort.CORE_TYPE && sort != Sort.CORE_MODULE && sort != Sort.TYPE && sort != Sort.COMPONENT) {
                        fail("outer の alias にできない種類（${sort.label}）")
                    }
                    Alias.Outer(sort, index(), index())
                }
                else -> fail("知らない alias の形 0x${target.toString(16)}")
            }
        }

        private fun sortOf(first: Int, coreByte: Int): Sort = when (first) {
            0x00 -> coreSort(coreByte)
            0x01 -> Sort.FUNC
            0x02 -> Sort.VALUE
            0x03 -> Sort.TYPE
            0x04 -> Sort.COMPONENT
            0x05 -> Sort.INSTANCE
            else -> fail("知らない sort 0x${first.toString(16)}")
        }

        fun externDesc(): ExternDesc = when (val b = u8()) {
            0x00 -> {
                if (u8() != 0x11) fail("core の import はモジュールだけ")
                ExternDesc.Module(index())
            }
            0x01 -> ExternDesc.Func(index())
            0x02 -> fail("値の import・export には対応していない（cm-values）")
            0x03 -> when (val bound = u8()) {
                0x00 -> ExternDesc.Type(index())
                0x01 -> ExternDesc.Type(null)
                else -> fail("知らない型の境界 0x${bound.toString(16)}")
            }
            0x04 -> ExternDesc.Component(index())
            0x05 -> ExternDesc.Instance(index())
            else -> fail("知らない import・export の種類 0x${b.toString(16)}")
        }

        fun valType(): ValType {
            PrimValType.of(peek())?.let {
                pos++
                return ValType.Primitive(it)
            }
            val v = s33()
            if (v < 0 || v > Int.MAX_VALUE) fail("値の型 $v が壊れている")
            return ValType.Ref(v.toInt())
        }

        fun optionalValType(): ValType? = when (val b = u8()) {
            0x00 -> null
            0x01 -> valType()
            else -> fail("省略できる値の型の形が壊れている（0x${b.toString(16)}）")
        }

        fun resultList(): ValType? = when (val b = u8()) {
            0x00 -> valType()
            0x01 -> {
                if (u8() != 0x00) fail("結果の数が壊れている")
                null
            }
            else -> fail("結果の形が壊れている（0x${b.toString(16)}）")
        }

        fun defType(depth: Int): DefType {
            state.countType(1)
            return when (val b = u8()) {
                0x3f -> {
                    val rep = coreValType()
                    val dtor = when (val d = u8()) {
                        0x00 -> null
                        0x01 -> index()
                        else -> fail("resource のデストラクターの形が壊れている（0x${d.toString(16)}）")
                    }
                    ResourceType(rep, dtor)
                }
                0x40, 0x43 -> FuncType(b == 0x43, vec("関数の引数") { LabeledValType(name(), valType()) }, resultList())
                0x41 -> {
                    if (depth + 1 > limits.maxTypeDepth) fail("型の入れ子が深すぎる（上限 ${limits.maxTypeDepth}）")
                    ComponentTypeDef(
                        vec("コンポーネント型の宣言") {
                            if (peek() == 0x03) {
                                pos++
                                TypeDecl.Import(externName(), externDesc())
                            } else {
                                instanceDecl(depth + 1)
                            }
                        },
                    )
                }
                0x42 -> {
                    if (depth + 1 > limits.maxTypeDepth) fail("型の入れ子が深すぎる（上限 ${limits.maxTypeDepth}）")
                    InstanceTypeDef(vec("インスタンス型の宣言") { instanceDecl(depth + 1) })
                }
                else -> PrimValType.of(b)?.let { DefValType.Primitive(it) } ?: defValType(b)
            }
        }

        fun instanceDecl(depth: Int): TypeDecl = when (val b = u8()) {
            0x00 -> TypeDecl.CoreType(coreDefType(depth))
            0x01 -> TypeDecl.Type(defType(depth))
            0x02 -> TypeDecl.AliasDecl(alias())
            0x04 -> TypeDecl.Export(externName(), externDesc())
            else -> fail("知らない型の宣言 0x${b.toString(16)}")
        }

        fun defValType(b: Int): DefValType = when (b) {
            0x72 -> DefValType.Record(vec("record のフィールド") { LabeledValType(name(), valType()) })
            0x71 -> DefValType.Variant(
                vec("variant の場合") {
                    val case = VariantCase(name(), optionalValType())
                    if (u8() != 0x00) fail("variant の場合の終わりが 0 でない")
                    case
                },
            )
            0x70 -> DefValType.ListOf(valType())
            0x67 -> DefValType.ListOf(valType(), u32())
            0x63 -> DefValType.MapOf(valType(), valType())
            0x6f -> DefValType.Tuple(vec("tuple の型") { valType() })
            0x6e -> DefValType.Flags(vec("flags の名前") { name() })
            0x6d -> DefValType.Enum(vec("enum の名前") { name() })
            0x6b -> DefValType.Option(valType())
            0x6a -> DefValType.Result(optionalValType(), optionalValType())
            0x69 -> DefValType.Own(index())
            0x68 -> DefValType.Borrow(index())
            0x66 -> DefValType.Stream(optionalValType())
            0x65 -> DefValType.Future(optionalValType())
            else -> fail("知らない型 0x${b.toString(16)}")
        }

        // ---- canon ----

        fun options(): List<CanonOption> = vec("canon の option") {
            when (val b = u8()) {
                0x00, 0x01, 0x02 -> CanonOption.StringEncoding(b)
                0x03 -> CanonOption.Memory(index())
                0x04 -> CanonOption.Realloc(index())
                0x05 -> CanonOption.PostReturn(index())
                0x06 -> CanonOption.Async
                0x07 -> CanonOption.Callback(index())
                0x08 -> CanonOption.CoreType(index())
                0x09 -> CanonOption.Gc
                else -> fail("知らない canon の option 0x${b.toString(16)}")
            }
        }

        fun canon(): Canon = when (val b = u8()) {
            0x00 -> {
                if (u8() != 0x00) fail("canon lift の形が壊れている")
                val coreFunc = index()
                val options = options()
                Canon.Lift(coreFunc, options, index())
            }
            0x01 -> {
                if (u8() != 0x00) fail("canon lower の形が壊れている")
                Canon.Lower(index(), options())
            }
            0x02 -> Canon.ResourceNew(index())
            0x03 -> Canon.ResourceDrop(index())
            0x04 -> Canon.ResourceRep(index())
            else -> {
                val op = CanonBuiltin.of(b) ?: fail("対応していない canon 0x${b.toString(16)}")
                when (op.shape) {
                    CanonBuiltin.Shape.NONE -> Canon.Builtin(op)
                    CanonBuiltin.Shape.FLAG -> Canon.Builtin(op, flag = bool())
                    CanonBuiltin.Shape.TYPE -> Canon.Builtin(op, typeIndex = index())
                    CanonBuiltin.Shape.TYPE_OPTIONS -> Canon.Builtin(op, typeIndex = index(), options = options())
                    CanonBuiltin.Shape.TYPE_FLAG -> Canon.Builtin(op, typeIndex = index(), flag = bool())
                    CanonBuiltin.Shape.OPTIONS -> Canon.Builtin(op, options = options())
                    CanonBuiltin.Shape.FLAG_MEMORY -> Canon.Builtin(op, flag = bool(), coreMemory = index())
                    CanonBuiltin.Shape.RESULT_OPTIONS -> {
                        val result = resultList()
                        Canon.Builtin(op, hasResult = result != null, result = result, options = options())
                    }
                    CanonBuiltin.Shape.CONTEXT -> Canon.Builtin(op, contextType = coreValType(), slot = index())
                }
            }
        }
    }
}
