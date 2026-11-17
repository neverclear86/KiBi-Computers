package pro.ikili.kibicomputers.core.wasi.p2

import pro.ikili.kibicomputers.core.runtime.component.canon.CanonFunction
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType

/**
 * WIT のインターフェース 1 つを、ホストの型にしたもの（#267。ADR 0267）。
 *
 * @property name 版を外した名前（`wasi:io/streams`）
 * @property functions 関数の名前（`[method]input-stream.read` など、import の member の名前）→ ホストの型
 * @property resources resource の型の名前 → ホストの resource の識別子（`use` で取り込んだものも入れる。ゲストのインスタンス型には
 *   取り込んだ型も member として出るので、リンクが `HostInterface.resources` で引けるようにする）
 * @property types 名前の付いた値の型（record・variant・enum・flags・`type` の別名）。ホストの実装が値を名前で作る・読むのに使う
 */
internal class WitInterface(
    val name: String,
    val functions: Map<String, CanonFunction>,
    val resources: Map<String, Int>,
    val types: Map<String, CanonType>,
) {
    /** 名前の付いた型 [name]（ないのは内部の誤り） */
    inline fun <reified T : CanonType> type(name: String): T =
        checkNotNull(types[name] as? T) { "${this.name} に型 $name（${T::class.simpleName}）がない" }
}

/**
 * ホストの WIT（[WasiP2Wit.TEXT]）を読む小さな読み手（#267）。ホストの関数の型を手で書かずに済ませるためのもので、
 * `wasm-tools component wit` が書き出す形（`package <ns>:<pkg>@<版> { interface … world … }` を並べたもの）だけを読む。
 * world は読み飛ばす。`include`・入れ子の package・`@since` などの注釈・`own<…>` 以外の書き方の省略形は扱わない
 * （書き出す形に出てこない）。読めないものは [IllegalArgumentException]（ホストの作りの誤り。テストが落とす）。
 *
 * resource には、出てきた順に [firstResourceId] から番号を振る（`HostResource.id`。ホスト全体で一意）。
 *
 * [imported] は、[text] の外で読み済みのインターフェース（`ns:pkg/iface@版` → 中身。#685）。[text] にない `use` の先はここから引き、
 * 型と resource の識別子をそのまま使う（`kibi:*` の WIT が `use wasi:io/poll@0.2.12.{pollable}` するとき、`pollable` を
 * `WasiP2Host` と同じ識別子にする。番号を振り直すと、`wasi:io/poll.poll` に並べて渡せない）。版は完全一致で引く
 */
internal class WitReader private constructor(
    private val text: String,
    firstResourceId: Int,
    private val imported: Map<String, WitInterface>,
) {
    private var pos = 0
    private var nextResource = firstResourceId

    /** 読んだインターフェース（版を外した名前 → 中身） */
    private val interfaces = LinkedHashMap<String, WitInterface>()

    /** resource の識別子 → 名前（`wasi:io/streams#input-stream`） */
    private val resourceNames = LinkedHashMap<Int, String>()

    /** 名前の付いた型（値の型か resource） */
    private sealed interface Named {
        class Value(val type: CanonType) : Named
        class Resource(val id: Int) : Named
    }

    /** 読み途中のインターフェース 1 つの名前の表（`use` で引くので、読み終えたものも残す） */
    private val scopes = HashMap<String, Map<String, Named>>()

    companion object {
        /** [text] を読み、インターフェースの表と、resource の識別子 → 名前の表（[text] で定義したものだけ）を返す */
        fun read(
            text: String,
            firstResourceId: Int,
            imported: Map<String, WitInterface> = emptyMap(),
        ): Pair<Map<String, WitInterface>, Map<Int, String>> {
            val r = WitReader(text, firstResourceId, imported)
            r.readAll()
            return r.interfaces to r.resourceNames
        }

        private val RESOURCE = Regex("""(?m)^\s*resource\s+%?([a-z][a-z0-9-]*)""")

        private val PRIMITIVES: Map<String, CanonType> = mapOf(
            "bool" to CanonType.Bool, "s8" to CanonType.S8, "u8" to CanonType.U8, "s16" to CanonType.S16, "u16" to CanonType.U16,
            "s32" to CanonType.S32, "u32" to CanonType.U32, "s64" to CanonType.S64, "u64" to CanonType.U64,
            "f32" to CanonType.F32, "f64" to CanonType.F64, "char" to CanonType.Char, "string" to CanonType.Str,
        )
    }

    // ---- 字句 ----

    private fun skipSpace() {
        while (pos < text.length) {
            val c = text[pos]
            when {
                c.isWhitespace() -> pos++
                text.startsWith("//", pos) -> while (pos < text.length && text[pos] != '\n') pos++
                else -> return
            }
        }
    }

    private fun fail(what: String): Nothing {
        val line = text.substring(0, minOf(pos, text.length)).count { it == '\n' } + 1
        throw IllegalArgumentException("WIT の $line 行目: $what")
    }

    private fun peek(s: String): Boolean {
        skipSpace()
        return text.startsWith(s, pos)
    }

    private fun expect(s: String) {
        if (!peek(s)) fail("$s がない")
        pos += s.length
    }

    private fun accept(s: String): Boolean {
        if (!peek(s)) return false
        pos += s.length
        return true
    }

    /** 識別子（`%` を外した名前） */
    private fun ident(): String {
        skipSpace()
        if (pos < text.length && text[pos] == '%') pos++
        val start = pos
        while (pos < text.length && (text[pos].isLetterOrDigit() || text[pos] == '-')) pos++
        if (start == pos) fail("名前がない")
        return text.substring(start, pos)
    }

    /** 識別子が [word] か（読み進めない。`%` 付きの名前は予約語と見なさない） */
    private fun peekWord(word: String): Boolean {
        skipSpace()
        if (!text.startsWith(word, pos)) return false
        val end = pos + word.length
        return end >= text.length || !(text[end].isLetterOrDigit() || text[end] == '-')
    }

    private fun version(): String {
        skipSpace()
        val start = pos
        while (pos < text.length && (text[pos].isDigit() || text[pos] == '.')) pos++
        // `@0.2.12.{…}` の最後の `.` は版ではない
        if (pos > start && text[pos - 1] == '.') pos--
        if (pos == start) fail("版がない")
        return text.substring(start, pos)
    }

    /** `{` から対応する `}` まで読み飛ばす */
    private fun skipBlock() {
        expect("{")
        var depth = 1
        while (depth > 0) {
            if (pos >= text.length) fail("} がない")
            when (text[pos++]) {
                '{' -> depth++
                '}' -> depth--
            }
        }
    }

    // ---- 構文 ----

    private fun readAll() {
        while (true) {
            skipSpace()
            if (pos >= text.length) return
            if (!peekWord("package")) fail("package がない")
            ident()
            val ns = ident()
            expect(":")
            val pkg = ident()
            if (accept("@")) version()
            expect("{")
            while (!accept("}")) {
                when {
                    peekWord("interface") -> {
                        ident()
                        readInterface(ns, pkg, ident())
                    }
                    peekWord("world") -> {
                        ident()
                        ident()
                        skipBlock()
                    }
                    else -> fail("interface か world がない")
                }
            }
        }
    }

    private fun readInterface(ns: String, pkg: String, iface: String) {
        val full = "$ns:$pkg/$iface"
        val scope = LinkedHashMap<String, Named>()
        val functions = LinkedHashMap<String, CanonFunction>()
        val deferred = ArrayList<() -> Unit>()
        // resource は定義より前のメソッドの型からも指されるので、先に番号を振っておく
        val declared = predeclareResources(full)
        for ((n, id) in declared) scope[n] = Named.Resource(id)
        expect("{")
        while (!accept("}")) {
            when {
                peekWord("use") -> {
                    ident()
                    val (path, version) = usePath(ns, pkg)
                    val from = scopes[path] ?: importedScope(path, version) ?: fail("use の先 $path がまだない")
                    expect(".")
                    expect("{")
                    do {
                        val n = ident()
                        val alias = if (peekWord("as")) { ident(); ident() } else n
                        scope[alias] = from[n] ?: fail("$path に $n がない")
                    } while (accept(","))
                    expect("}")
                    expect(";")
                }
                peekWord("type") -> {
                    ident()
                    val n = ident()
                    expect("=")
                    scope[n] = Named.Value(type(scope))
                    expect(";")
                }
                peekWord("record") -> {
                    ident()
                    val n = ident()
                    val names = ArrayList<String>()
                    val types = ArrayList<CanonType>()
                    list("{", "}") {
                        names += ident()
                        expect(":")
                        types += type(scope)
                    }
                    scope[n] = Named.Value(CanonType.Record(names, types))
                }
                peekWord("variant") -> {
                    ident()
                    val n = ident()
                    val names = ArrayList<String>()
                    val types = ArrayList<CanonType?>()
                    list("{", "}") {
                        names += ident()
                        types += if (accept("(")) type(scope).also { expect(")") } else null
                    }
                    scope[n] = Named.Value(CanonType.Variant(names, types))
                }
                peekWord("enum") -> {
                    ident()
                    val n = ident()
                    val names = ArrayList<String>()
                    list("{", "}") { names += ident() }
                    scope[n] = Named.Value(CanonType.Enum(names))
                }
                peekWord("flags") -> {
                    ident()
                    val n = ident()
                    val names = ArrayList<String>()
                    list("{", "}") { names += ident() }
                    scope[n] = Named.Value(CanonType.Flags(names))
                }
                peekWord("resource") -> {
                    ident()
                    val n = ident()
                    val id = declared.getValue(n)
                    scope[n] = Named.Resource(id)
                    if (!accept(";")) {
                        // 中身は後で読む（メソッドの型が、後で定義する record などを指すことがある。wasm-tools は resource を先に書く）
                        skipSpace()
                        val at = pos
                        deferred += { pos = at; expect("{"); while (!accept("}")) readResourceItem(scope, n, id, functions) }
                        skipBlock()
                    }
                }
                else -> {
                    val n = ident()
                    expect(":")
                    if (!peekWord("func")) fail("$n: func がない")
                    ident()
                    skipSpace()
                    val at = pos
                    deferred += { pos = at; functions[n] = func(scope, null); expect(";") }
                    pos = text.indexOf(';', pos).also { if (it < 0) fail("; がない") } + 1
                }
            }
        }
        // 関数の型は、インターフェースの型をすべて読んでから読む（定義より前の型を指せるように）
        val end = pos
        for (d in deferred) d()
        pos = end
        scopes[full] = scope
        val resources = LinkedHashMap<String, Int>()
        val types = LinkedHashMap<String, CanonType>()
        for ((n, v) in scope) {
            when (v) {
                is Named.Resource -> resources[n] = v.id
                is Named.Value -> types[n] = v.type
            }
        }
        interfaces[full] = WitInterface(full, functions, resources, types)
    }

    /** 今の位置（インターフェースの `{`）から対応する `}` までの、直下の `resource <名前>` に番号を振る */
    private fun predeclareResources(full: String): Map<String, Int> {
        skipSpace()
        val start = pos
        var depth = 0
        var end = start
        while (end < text.length) {
            val c = text[end++]
            if (c == '{') depth++
            if (c == '}' && --depth == 0) break
        }
        val out = LinkedHashMap<String, Int>()
        for (m in RESOURCE.findAll(text.substring(start, end))) {
            val n = m.groupValues[1]
            val id = nextResource++
            resourceNames[id] = "$full#$n"
            out[n] = id
        }
        return out
    }

    private fun readResourceItem(scope: Map<String, Named>, resource: String, id: Int, out: MutableMap<String, CanonFunction>) {
        if (peekWord("constructor")) {
            ident()
            val f = func(scope, null, constructorOf = id)
            out["[constructor]$resource"] = f
            expect(";")
            return
        }
        val n = ident()
        expect(":")
        if (peekWord("static")) {
            ident()
            ident() // func
            out["[static]$resource.$n"] = func(scope, null)
        } else {
            if (!peekWord("func")) fail("$resource.$n: func がない")
            ident()
            out["[method]$resource.$n"] = func(scope, CanonType.Borrow(id))
        }
        expect(";")
    }

    /** `use` の先の名前（同じパッケージのインターフェースか、`ns:pkg/iface@版`）と版（なければ `null`）。[text] の中の先は版を見ない */
    private fun usePath(ns: String, pkg: String): Pair<String, String?> {
        val first = ident()
        if (!accept(":")) return "$ns:$pkg/$first" to null
        val p = ident()
        expect("/")
        val i = ident()
        val v = if (accept("@")) version() else null
        return "$first:$p/$i" to v
    }

    /** 読み済みの外のインターフェース（[imported]）[path]@[version] の名前の表。なければ `null` */
    private fun importedScope(path: String, version: String?): Map<String, Named>? {
        val w = imported[if (version == null) path else "$path@$version"] ?: return null
        val out = LinkedHashMap<String, Named>()
        for ((n, id) in w.resources) out[n] = Named.Resource(id)
        for ((n, t) in w.types) out[n] = Named.Value(t)
        return out
    }

    /** `func` の後ろの `(引数) [-> 結果]`。[self] はメソッドの最初の引数 */
    private fun func(scope: Map<String, Named>, self: CanonType?, constructorOf: Int? = null): CanonFunction {
        val params = ArrayList<CanonType>()
        if (self != null) params += self
        list("(", ")") {
            ident()
            expect(":")
            params += type(scope)
        }
        val result = when {
            constructorOf != null -> CanonType.Own(constructorOf)
            accept("->") -> type(scope)
            else -> null
        }
        return CanonFunction(params, result)
    }

    /** [open] … [close] の、`,` で区切った並び（最後の `,` があってもよい） */
    private inline fun list(open: String, close: String, item: () -> Unit) {
        expect(open)
        while (!accept(close)) {
            item()
            if (!accept(",")) {
                expect(close)
                return
            }
        }
    }

    private fun type(scope: Map<String, Named>): CanonType {
        val n = ident()
        PRIMITIVES[n]?.let { return it }
        return when (n) {
            "list" -> generic { CanonType.ListOf(type(scope)) }
            "option" -> generic { CanonType.Option(type(scope)) }
            "tuple" -> {
                val items = ArrayList<CanonType>()
                list("<", ">") { items += type(scope) }
                CanonType.Tuple(items)
            }
            "result" -> {
                if (!accept("<")) return CanonType.Result(null, null)
                val ok = if (accept("_")) null else type(scope)
                val err = if (accept(",")) type(scope) else null
                expect(">")
                CanonType.Result(ok, err)
            }
            "borrow" -> generic { CanonType.Borrow(resourceId(scope, ident())) }
            "own" -> generic { CanonType.Own(resourceId(scope, ident())) }
            else -> when (val named = scope[n] ?: fail("型 $n がない")) {
                is Named.Value -> named.type
                is Named.Resource -> CanonType.Own(named.id)
            }
        }
    }

    private inline fun generic(inner: () -> CanonType): CanonType {
        expect("<")
        val t = inner()
        expect(">")
        return t
    }

    private fun resourceId(scope: Map<String, Named>, n: String): Int =
        (scope[n] as? Named.Resource)?.id ?: fail("$n は resource でない")
}
