package pro.ikili.kibicomputers.core.runtime.component.link

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonValue
import pro.ikili.kibicomputers.core.wasi.kibi.WitTool

/**
 * Component Model の仕様のテスト（`test/values`・`test/linking` の wast）を、リンカーで動かす（#263）。
 *
 * wast は仕様のリポジトリ（https://github.com/WebAssembly/component-model 、コミット dd1fefc0c618382359feca6f2c758a2b3ef21c36、
 * Apache-2.0 WITH LLVM-exception）から写したもの（`src/test/resources/component-spec/`。`values/post-return.wast` は仕様の
 * `nyi.txt` にあり、同梱の wasm-tools でも読めないので入れていない）。`wasm-tools json-from-wast` で命令の並びとバイナリにして動かす。
 *
 * トラップのメッセージは比べない（トラップしたことだけを確かめる）。まだ扱えない機能（resource のハンドル表 #265、async #266 など）で
 * 通らないものは [KNOWN] に理由と一緒に挙げる。挙げたものが通るようになったら、[KNOWN] から外す（ずれたら落ちる）
 */
class ComponentSpecTest {
    private val files = listOf(
        "values-alignment",
        "values-concat",
        "values-numerics",
        "values-realloc",
        "values-strings",
        "values-transcode",
        "values-variants",
        "linking-link-time-virtualization",
        "linking-shared-everything-dynamic-linking",
        "linking-tags",
        "linking-unit",
    )

    /** 通らないと分かっているもの（`ファイル:行` → 理由） */
    private val known: Map<String, String> = KNOWN

    @Test
    fun `仕様のwastを動かす`() {
        val failures = LinkedHashMap<String, String>()
        var passed = 0
        for (name in files) {
            val r = SpecRun(name)
            r.run()
            passed += r.passed
            failures.putAll(r.failures)
        }
        val unexpected = failures.filterKeys { it !in known }
        val fixed = known.keys.filter { it !in failures }
        assertTrue(unexpected.isEmpty() && fixed.isEmpty()) {
            "通らないもの:\n" + unexpected.entries.joinToString("\n") { "  ${it.key}: ${it.value}" } +
                "\n通るようになったもの（KNOWN から外す）: $fixed"
        }
        assertTrue(passed >= 250, "通った数が少ない: $passed")
    }

    private class SpecRun(val name: String) {
        val failures = LinkedHashMap<String, String>()
        var passed = 0
        private var current: LinkedComponent? = null
        private var currentError: String? = null
        private val definitions = HashMap<String, ByteArray>()
        private val instances = HashMap<String, Pair<LinkedComponent?, String?>>()

        fun run() {
            val wast = requireNotNull(javaClass.getResourceAsStream("/component-spec/$name.wast")) { "$name.wast がない" }.readBytes()
            val outputs = listOf("out/t.json") + (0 until 400).map { "out/t.$it.wasm" }
            val r = WitTool.run(
                listOf("json-from-wast", "work/t.wast", "-o", "work/out/t.json", "--wasm-dir", "work/out"),
                mapOf("t.wast" to wast, "out/.keep" to ByteArray(0)),
                outputs,
            )
            assertEquals(0, r.exitCode, "$name: json-from-wast に失敗: ${r.stderr}")
            val json = Json.parse(String(r.outputs.getValue("out/t.json"), Charsets.UTF_8)) as Map<*, *>
            fun wasm(file: Any?): ByteArray = r.outputs["out/$file"] ?: error("$name: $file がない")
            try {
                for (c in json["commands"] as List<*>) {
                    c as Map<*, *>
                    val key = "$name:${(c["line"] as Number).toInt()}"
                    try {
                        when (c["type"]) {
                            "module" -> instantiate(key, wasm(c["filename"]), c["name"] as String?)
                            "module_definition" -> definitions[c["name"] as String] = wasm(c["filename"])
                            "module_instance" -> instantiate(key, definitions.getValue(c["module"] as String), c["instance"] as String?)
                            "assert_return" -> assertReturn(key, c)
                            "assert_trap" -> assertTrap(key, c)
                            "assert_invalid" -> assertInvalid(key, wasm(c["filename"]))
                            else -> failures[key] = "扱わない命令 ${c["type"]}"
                        }
                    } catch (e: Throwable) {
                        failures[key] = "想定外の例外: $e"
                    }
                }
            } finally {
                current?.close()
                instances.values.forEach { it.first?.close() }
            }
        }

        private fun instantiate(key: String, bytes: ByteArray, name: String?) {
            val result = try {
                val p = ComponentProgram.load(LinkTesting.runtime, bytes, ComponentHost.NONE, metered = false, policy = LinkTesting.ANY_IMPORT)
                LinkedComponent.instantiate(p.plan) to null
            } catch (e: WasmLoadException) {
                null to "読み込めない: ${e.message}"
            } catch (e: Exception) {
                null to "インスタンス化できない: $e"
            }
            result.second?.let { failures[key] = it } ?: passed++
            current = result.first
            currentError = result.second
            if (name != null) instances[name] = result
        }

        private fun target(key: String, action: Map<*, *>): LinkedComponent? {
            val module = action["module"] as String?
            val (c, err) = if (module != null) instances[module] ?: (null to "インスタンス $module がない") else current to currentError
            if (c == null) failures[key] = "インスタンスがない（${err ?: "?"}）"
            return c
        }

        private fun invoke(key: String, c: LinkedComponent, action: Map<*, *>): CanonValue? {
            val field = action["field"] as String
            val f = c.function(field) ?: error("export $field がない")
            val args = (action["args"] as List<*>).mapIndexed { i, a -> value(a as Map<*, *>, f.function.params[i]) }
            return c.call(f, args)
        }

        private fun assertReturn(key: String, cmd: Map<*, *>) {
            val action = cmd["action"] as Map<*, *>
            val c = target(key, action) ?: return
            val f = c.function(action["field"] as String)
            val result = try {
                invoke(key, c, action)
            } catch (e: WasmTrapException) {
                failures[key] = "トラップした: ${e.message}"
                return
            }
            val expected = (cmd["expected"] as List<*>).map { value(it as Map<*, *>, f!!.function.result!!) }
            val actual = listOfNotNull(result)
            if (expected == actual) passed++ else failures[key] = "結果が違う: $actual（期待 $expected）"
        }

        private fun assertTrap(key: String, cmd: Map<*, *>) {
            val action = cmd["action"] as Map<*, *>
            val c = target(key, action) ?: return
            try {
                val r = invoke(key, c, action)
                failures[key] = "トラップしない（$r。期待: ${cmd["text"]}）"
            } catch (e: WasmTrapException) {
                passed++
            }
        }

        private fun assertInvalid(key: String, bytes: ByteArray) {
            try {
                ComponentProgram.load(LinkTesting.runtime, bytes, ComponentHost.NONE, metered = false, policy = LinkTesting.ANY_IMPORT)
                failures[key] = "読み込めてしまう"
            } catch (e: WasmLoadException) {
                passed++
            }
        }

        /** wast の値（json）を、型 [t] の値にする */
        private fun value(v: Map<*, *>, t: CanonType): CanonValue {
            val x = v["value"]
            return when (v["type"]) {
                "bool" -> if (x as Boolean) CanonValue.TRUE else CanonValue.FALSE
                "u8", "s8", "u16", "s16", "u32", "s32" -> CanonValue.I32((x as String).toLong().toInt())
                "u64" -> CanonValue.I64(java.lang.Long.parseUnsignedLong(x as String))
                "s64" -> CanonValue.I64((x as String).toLong())
                "f32" -> CanonValue.F32(java.lang.Float.intBitsToFloat((x as String).toLong().toInt()))
                "f64" -> CanonValue.F64(java.lang.Double.longBitsToDouble(java.lang.Long.parseUnsignedLong(x as String)))
                "char" -> CanonValue.Char((x as String).codePointAt(0))
                "string" -> CanonValue.Str.of(x as String)
                "list" -> {
                    val e = (t as CanonType.ListOf).element
                    val items = (x as List<*>).map { value(it as Map<*, *>, e) }
                    if (e == CanonType.U8) CanonValue.Bytes(ByteArray(items.size) { (items[it] as CanonValue.I32).value.toByte() }) else CanonValue.Items(items)
                }
                "tuple" -> CanonValue.Fields((x as List<*>).mapIndexed { i, it -> value(it as Map<*, *>, (t as CanonType.Fields).fieldTypes[i]) })
                "record" -> CanonValue.Fields((x as List<*>).mapIndexed { i, it -> value((it as List<*>)[1] as Map<*, *>, (t as CanonType.Fields).fieldTypes[i]) })
                "variant" -> {
                    t as CanonType.Variant
                    x as Map<*, *>
                    val i = t.names.indexOf(x["case"] as String)
                    CanonValue.Case(i, (x["payload"] as Map<*, *>?)?.let { value(it, t.caseTypes[i]!!) })
                }
                "enum" -> CanonValue.Case((t as CanonType.Enum).names.indexOf(x as String), null)
                "flags" -> {
                    t as CanonType.Flags
                    CanonValue.Flags((x as List<*>).fold(0) { acc, n -> acc or (1 shl t.names.indexOf(n as String)) })
                }
                "option" -> if (x == null) CanonValue.NONE else CanonValue.some(value(x as Map<*, *>, (t as CanonType.Option).type))
                "result" -> {
                    t as CanonType.Result
                    x as Map<*, *>
                    if (x.containsKey("Ok")) {
                        CanonValue.ok((x["Ok"] as Map<*, *>?)?.let { value(it, t.ok!!) })
                    } else {
                        CanonValue.err((x["Err"] as Map<*, *>?)?.let { value(it, t.err!!) })
                    }
                }
                else -> error("扱わない値 ${v["type"]}")
            }
        }
    }

    /** テストのための小さな JSON の読み手（json-from-wast の出力だけを読む） */
    private object Json {
        fun parse(text: String): Any? = Reader(text).run { value().also { ws(); require(i == s.length) { "JSON の後ろに余り" } } }

        private class Reader(val s: String) {
            var i = 0

            fun ws() {
                while (i < s.length && s[i].isWhitespace()) i++
            }

            fun value(): Any? {
                ws()
                return when (s[i]) {
                    '{' -> {
                        i++
                        val m = LinkedHashMap<String, Any?>()
                        ws()
                        if (s[i] == '}') {
                            i++
                            return m
                        }
                        while (true) {
                            ws()
                            val k = string()
                            ws()
                            require(s[i++] == ':')
                            m[k] = value()
                            ws()
                            if (s[i++] == '}') return m
                        }
                        @Suppress("UNREACHABLE_CODE")
                        m
                    }
                    '[' -> {
                        i++
                        val l = ArrayList<Any?>()
                        ws()
                        if (s[i] == ']') {
                            i++
                            return l
                        }
                        while (true) {
                            l += value()
                            ws()
                            if (s[i++] == ']') return l
                        }
                        @Suppress("UNREACHABLE_CODE")
                        l
                    }
                    '"' -> string()
                    't' -> literal("true", true)
                    'f' -> literal("false", false)
                    'n' -> literal("null", null)
                    else -> {
                        val start = i
                        while (i < s.length && (s[i] == '-' || s[i] == '+' || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i].isDigit())) i++
                        s.substring(start, i).toDouble()
                    }
                }
            }

            private fun literal(word: String, v: Any?): Any? {
                require(s.startsWith(word, i))
                i += word.length
                return v
            }

            fun string(): String {
                require(s[i++] == '"')
                val sb = StringBuilder()
                while (true) {
                    val c = s[i++]
                    when (c) {
                        '"' -> return sb.toString()
                        '\\' -> when (val e = s[i++]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'u' -> {
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> sb.append(e)
                        }
                        else -> sb.append(c)
                    }
                }
            }
        }
    }

    private companion object {
        const val MAP = "map 型（component-model-map）は検証器で有効にしていない（#264 で扱わないとした型）"
        const val ASYNC = "async（stackful）の機能を使う。#266"
        const val MULTI_MEMORY = "core モジュールのメモリは 1 つまで（p1 と同じ上限。ModuleLimits・検証器の multi-memory）"
        const val RESOURCES = "resource のハンドル表がまだない（resource.new がトラップする。#265）"

        val KNOWN: Map<String, String> = buildMap {
            fun add(file: String, reason: String, vararg lines: Int) = lines.forEach { put("$file:$it", reason) }
            add("values-concat", MAP, 463, 723, 729, 732, 741, 748, 755, 762, 770, 788)
            add("values-variants", ASYNC, 83, 183, 184, 185, 186)
            add("linking-unit", MULTI_MEMORY, 261, 295, 296, 297, 298, 299, 300, 301, 302, 308, 344, 345, 346, 347, 348, 349, 350, 351, 355, 374, 375)
            add(
                "linking-unit", RESOURCES, 725, 726, 727, 728, 729, 730, 788, 789, 790, 791, 792, 793, 864, 865, 866, 867, 931, 932, 933, 934,
                995, 996, 997, 998, 1019, 1020, 1021, 1022, 1023, 1094, 1095, 1096, 1097, 1161, 1162, 1163, 1164, 1165, 1166, 1167, 1168,
            )
        }
    }
}
