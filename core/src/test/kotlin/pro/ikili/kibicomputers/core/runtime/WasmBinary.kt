package pro.ikili.kibicomputers.core.runtime

import java.io.ByteArrayOutputStream

/**
 * テスト用の小さな WASM のバイナリの組み立て。関数の本体は命令のバイト列をそのまま渡す。
 *
 * 関数の番号は、import した関数が先（足した順）、その後に [func] で足した順。
 */
class WasmBinary {
    private val types = mutableListOf<Pair<List<Int>, List<Int>>>()
    private val imports = mutableListOf<Triple<String, String, Int>>()
    private val functions = mutableListOf<Pair<Int, ByteArray>>()
    private val functionLocals = mutableListOf<List<Int>>()
    private val exports = mutableListOf<Triple<String, Int, Int>>()
    private val globals = mutableListOf<ByteArray>()
    private val data = mutableListOf<Pair<Int, ByteArray>>()
    private var memoryPages: Int? = null
    private var tableSize: Int? = null
    private var start: Int? = null

    /** 関数の型の番号（`call_indirect` に使う）。なければ足す */
    fun typeIndex(params: List<Int>, results: List<Int>): Int = type(params, results)

    /** funcref のテーブル（要素は空）を1つ足す */
    fun table(size: Int) = apply { tableSize = size }

    private fun type(params: List<Int>, results: List<Int>): Int {
        val t = params to results
        val i = types.indexOf(t)
        if (i >= 0) return i
        types += t
        return types.size - 1
    }

    /** 関数を import して、その関数の番号を返す。 */
    fun importFunc(module: String, name: String, params: List<Int>, results: List<Int>): Int {
        check(functions.isEmpty()) { "import は関数より先に足す" }
        imports += Triple(module, name, type(params, results))
        return imports.size - 1
    }

    /** 関数を足して、その関数の番号を返す。[body] は `end` を含めない。 */
    fun func(params: List<Int>, results: List<Int>, body: ByteArray, locals: List<Int> = emptyList()): Int {
        functions += type(params, results) to body
        functionLocals += locals
        return imports.size + functions.size - 1
    }

    fun exportFunc(name: String, index: Int) = apply { exports += Triple(name, 0x00, index) }

    fun memory(pages: Int, exportName: String? = "memory") = apply {
        memoryPages = pages
        if (exportName != null) exports += Triple(exportName, 0x02, 0)
    }

    /** 可変の i64 のグローバルを足して、番号を返す。 */
    fun mutableI64Global(initial: Long, exportName: String? = null): Int {
        globals += byteArrayOf(I64.toByte(), 0x01, 0x42) + sleb(initial) + byteArrayOf(0x0b)
        val index = globals.size - 1
        if (exportName != null) exports += Triple(exportName, 0x03, index)
        return index
    }

    fun data(offset: Int, bytes: ByteArray) = apply { data += offset to bytes }

    fun start(index: Int) = apply { start = index }

    fun build(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00))
        section(out, 1, vec(types.map { (p, r) -> byteArrayOf(0x60) + vec(p.map { byteArrayOf(it.toByte()) }) + vec(r.map { byteArrayOf(it.toByte()) }) }))
        if (imports.isNotEmpty()) {
            section(out, 2, vec(imports.map { (m, n, t) -> name(m) + name(n) + byteArrayOf(0x00) + uleb(t.toLong()) }))
        }
        if (functions.isNotEmpty()) section(out, 3, vec(functions.map { uleb(it.first.toLong()) }))
        tableSize?.let { section(out, 4, vec(listOf(byteArrayOf(0x70, 0x00) + uleb(it.toLong())))) }
        memoryPages?.let { section(out, 5, vec(listOf(byteArrayOf(0x00) + uleb(it.toLong())))) }
        if (globals.isNotEmpty()) section(out, 6, vec(globals))
        if (exports.isNotEmpty()) section(out, 7, vec(exports.map { (n, k, i) -> name(n) + byteArrayOf(k.toByte()) + uleb(i.toLong()) }))
        start?.let { section(out, 8, uleb(it.toLong())) }
        if (functions.isNotEmpty()) {
            section(
                out,
                10,
                vec(
                    functions.mapIndexed { i, (_, body) ->
                        val locals = vec(functionLocals[i].map { uleb(1) + byteArrayOf(it.toByte()) })
                        val code = locals + body + byteArrayOf(0x0b)
                        uleb(code.size.toLong()) + code
                    },
                ),
            )
        }
        if (data.isNotEmpty()) {
            section(out, 11, vec(data.map { (offset, bytes) -> byteArrayOf(0x00, 0x41) + sleb(offset.toLong()) + byteArrayOf(0x0b) + uleb(bytes.size.toLong()) + bytes }))
        }
        return out.toByteArray()
    }

    private fun section(out: ByteArrayOutputStream, id: Int, content: ByteArray) {
        out.write(id)
        out.write(uleb(content.size.toLong()))
        out.write(content)
    }

    companion object {
        const val I32 = 0x7f
        const val I64 = 0x7e

        fun uleb(value: Long): ByteArray {
            val out = ByteArrayOutputStream()
            var v = value
            do {
                var b = (v and 0x7f).toInt()
                v = v ushr 7
                if (v != 0L) b = b or 0x80
                out.write(b)
            } while (v != 0L)
            return out.toByteArray()
        }

        fun sleb(value: Long): ByteArray {
            val out = ByteArrayOutputStream()
            var v = value
            while (true) {
                val b = (v and 0x7f).toInt()
                v = v shr 7
                val done = (v == 0L && b and 0x40 == 0) || (v == -1L && b and 0x40 != 0)
                out.write(if (done) b else b or 0x80)
                if (done) break
            }
            return out.toByteArray()
        }

        private fun name(s: String): ByteArray = s.toByteArray().let { uleb(it.size.toLong()) + it }

        private fun vec(items: List<ByteArray>): ByteArray = items.fold(uleb(items.size.toLong())) { acc, b -> acc + b }

        /** 命令の並びを作る。Int は 1 バイト、ByteArray はそのまま */
        fun code(vararg parts: Any): ByteArray {
            val out = ByteArrayOutputStream()
            for (p in parts) {
                when (p) {
                    is Int -> out.write(p)
                    is ByteArray -> out.write(p)
                    else -> error("命令に使えない: $p")
                }
            }
            return out.toByteArray()
        }
    }
}
