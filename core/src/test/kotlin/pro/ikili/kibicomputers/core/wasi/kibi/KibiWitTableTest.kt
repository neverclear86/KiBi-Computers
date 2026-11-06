package pro.ikili.kibicomputers.core.wasi.kibi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.wasi.p1.WasiP1Kibi
import java.io.File

/**
 * p1 向けの import の表（[WasiP1Kibi]）が、`core/src/main/wit/` の WIT からずれていないこと（#73・#75・#426・#427）。
 * 完全なパーサーではなく、`package`・`interface`・`resource`・関数の名前だけを読む。
 * WIT として正しいか（解析・解決できるか）は `KibiWitParseTest` が wasm-tools で確かめる。ここの予約語の検査は、速く分かりやすく失敗させるための補助。
 */
class KibiWitTableTest {
    /** WIT から ADR 0073 の規則で導いた、(モジュール名, 関数名) の集合 */
    private fun derived(): Set<Pair<String, String>> {
        val out = HashSet<Pair<String, String>>()
        val files = File("src/main/wit").walkTopDown().filter { it.isFile && it.extension == "wit" }.toList()
        assertTrue(files.isNotEmpty(), "WIT がない")
        val pkgRe = Regex("""^\s*package\s+([a-z0-9-]+):([a-z0-9-]+)@([0-9.]+)\s*;""")
        val ifaceRe = Regex("""^\s*interface\s+([a-z0-9-]+)\s*\{""")
        val resourceRe = Regex("""^\s*resource\s+([a-z0-9-]+)\s*\{""")
        val funcRe = Regex("""^\s*%?([a-z0-9-]+)\s*:\s*(static\s+)?(async\s+)?func\b""")
        val ctorRe = Regex("""^\s*constructor\s*\(""")
        for (f in files) {
            var pkg: Triple<String, String, String>? = null
            var iface: String? = null
            var resource: String? = null
            var depth = 0
            for (raw in f.readLines()) {
                val line = raw.substringBefore("//")
                pkgRe.find(line)?.let { pkg = Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
                val p = pkg
                if (depth == 0) ifaceRe.find(line)?.let { iface = it.groupValues[1] }
                if (depth == 1) resourceRe.find(line)?.let { r ->
                    resource = r.groupValues[1]
                    out += module(p!!, iface!!) to "[resource-drop]${r.groupValues[1]}"
                }
                val i = iface
                if (p != null && i != null) {
                    val r = resource
                    if (depth == 1 && r == null) funcRe.find(line)?.let { out += module(p, i) to it.groupValues[1] }
                    if (depth == 2 && r != null) {
                        funcRe.find(line)?.let {
                            val kind = if (it.groupValues[2].isNotBlank()) "[static]" else "[method]"
                            out += module(p, i) to "$kind$r.${it.groupValues[1]}"
                        }
                        if (ctorRe.containsMatchIn(line)) out += module(p, i) to "[constructor]$r"
                    }
                }
                depth += line.count { it == '{' } - line.count { it == '}' }
                if (depth <= 1) resource = null
                if (depth == 0) iface = null
            }
        }
        return out
    }

    private fun module(p: Triple<String, String, String>, iface: String) = "${p.first}:${p.second}/$iface@${p.third}"

    @Test
    fun `p1向けのimportはすべてWITにある`() {
        val wit = derived()
        for (fn in WasiP1Kibi.functions) assertTrue(fn in wit, "$fn が WIT にない（WIT から導いたもの: $wit）")
    }

    @Test
    fun `p1に出しているinterfaceのWITの関数はすべてp1に出している`() {
        val wit = derived().filter { it.first in WasiP1Kibi.modules }.toSet()
        assertTrue(wit.isNotEmpty())
        for (fn in wit) assertTrue(fn in WasiP1Kibi.functions, "$fn を p1 に出していない")
    }
    private fun witFiles(): List<File> = File("src/main/wit").walkTopDown().filter { it.isFile && it.extension == "wit" }.toList()

    private fun witText(path: String): String = File("src/main/wit/$path").readText()

    @Test
    fun `kibi_ioのfdとpipeの関数はすべてp1に出している`() {
        val io = derived().filter { it.first.startsWith("kibi:io/") }.toSet()
        assertEquals(
            setOf(WasiP1Kibi.IO_FD to "dup", WasiP1Kibi.IO_FD to "dup-to", WasiP1Kibi.IO_PIPE to "create"),
            io.filter { it.first == WasiP1Kibi.IO_FD || it.first == WasiP1Kibi.IO_PIPE }.toSet(),
        )
        // p2・p3 だけの interface は p1 に出さない
        for (m in listOf("kibi:io/pipe-streams@0.1.0", "kibi:io/pipe-async@0.1.0")) {
            assertTrue(io.any { it.first == m }, "$m が WIT にない")
            assertFalse(m in WasiP1Kibi.modules, "$m を p1 に出している")
        }
    }

    @Test
    fun `kibi_ioのp1のworldとp3のinterfaceはwasiを参照せず、p2はwasi_io_streamsのstreamだけを使う`() {
        val world = witText("kibi-io/world.wit")
        val imports = Regex("""world\s+imports\s*\{([^}]*)}""").find(world)!!.groupValues[1]
        assertFalse("wasi:" in imports, imports)
        for (f in listOf("fd.wit", "pipe.wit", "pipe-async.wit", "tty.wit")) {
            assertFalse(useLines(witText("kibi-io/$f")).any { "wasi:" in it }, "$f が wasi:* を使っている")
        }
        val uses = useLines(witText("kibi-io/pipe-streams.wit")).filter { "wasi:" in it }
        assertEquals(listOf("use wasi:io/streams@0.2.3.{input-stream, output-stream};"), uses)
    }

    @Test
    fun `kibi_io_ttyの関数はすべてp1に出していて、3つのworldがttyをimportし、ttyはwasiを参照しない`() {
        val expected = setOf("is-tty", "get-mode", "set-mode", "get-size", "get-foreground", "set-foreground").map { WasiP1Kibi.IO_TTY to it }.toSet()
        assertEquals(expected, derived().filter { it.first == WasiP1Kibi.IO_TTY }.toSet())
        for (fn in expected) assertTrue(fn in WasiP1Kibi.functions, "$fn を p1 に出していない")
        val world = witText("kibi-io/world.wit")
        for (w in listOf("imports", "imports-p2", "imports-p3")) {
            val body = Regex("""world\s+$w\s*\{([^}]*)}""").find(world)!!.groupValues[1]
            assertTrue(Regex("""(?m)^\s*import\s+tty\s*;""").containsMatchIn(body), "$w が tty を import していない: $body")
        }
        assertFalse(useLines(witText("kibi-io/tty.wit")).any { "wasi:" in it }, "tty.wit が wasi:* を使っている")
    }

    @Test
    fun `kibi_kernel_timersとkibi_computer_clockの関数はすべてp1に出している`() {
        val wit = derived()
        val timers = setOf(
            "[static]timer.create", "[method]timer.set-after", "[method]timer.set-at", "[method]timer.cancel", "[method]timer.read", "[resource-drop]timer",
        ).map { WasiP1Kibi.TIMERS to it }.toSet()
        val clock = setOf("uptime-ticks", "world-ticks").map { WasiP1Kibi.CLOCK to it }.toSet()
        assertEquals(timers, wit.filter { it.first == WasiP1Kibi.TIMERS }.toSet())
        assertEquals(clock, wit.filter { it.first == WasiP1Kibi.CLOCK }.toSet())
        for (fn in timers + clock) assertTrue(fn in WasiP1Kibi.functions, "$fn を p1 に出していない")
    }

    @Test
    fun `kibi_kernelのp2とp3のinterfaceはWITにあり、p1には出さない`() {
        val wit = derived()
        val expected = mapOf(
            "kibi:kernel/timers-poll@0.1.0" to "subscribe",
            "kibi:kernel/events-poll@0.1.0" to "subscribe",
            "kibi:kernel/timers-async@0.1.0" to "wait",
            "kibi:kernel/events-async@0.1.0" to "take",
        )
        for ((m, f) in expected) {
            assertTrue((m to f) in wit, "$m.$f が WIT にない（async func も読めること）")
            assertFalse(m in WasiP1Kibi.modules, "$m を p1 に出している")
        }
    }

    @Test
    fun `kibi_kernelのp1とp3の部分はwasiを参照せず、p2はpipe-streamsと同じ版のwasi_io_pollのpollableだけを使う`() {
        val world = witText("kibi-kernel/world.wit")
        val imports = Regex("""world\s+imports\s*\{([^}]*)}""").find(world)!!.groupValues[1]
        assertFalse("wasi:" in imports, imports)
        for (f in listOf("types.wit", "events.wit", "timers.wit", "timers-async.wit", "events-async.wit", "world.wit")) {
            assertFalse(useLines(witText("kibi-kernel/$f")).any { "wasi:" in it }, "$f が wasi:* を使っている")
        }
        assertFalse(useLines(witText("kibi-computer/clock.wit")).any { "wasi:" in it })
        for (f in listOf("timers-poll.wit", "events-poll.wit")) {
            val uses = useLines(witText("kibi-kernel/$f")).filter { "wasi:" in it }
            assertEquals(listOf("use wasi:io/poll@0.2.3.{pollable};"), uses, f)
        }
        // pipe-streams（ADR 0075）と同じ wasi:io の版（同じゲストの中で pollable の型をそろえる。ADR 0426）
        val ioVersion = Regex("""wasi:io/[a-z-]+@([0-9]+\.[0-9]+\.[0-9]+)""")
        val streamsVersion = ioVersion.find(witText("kibi-io/pipe-streams.wit"))!!.groupValues[1]
        val pollVersion = ioVersion.find(witText("kibi-kernel/timers-poll.wit"))!!.groupValues[1]
        assertEquals(streamsVersion, pollVersion)
    }

    @Test
    fun `kibi_processのprocessの関数はすべてp1に出し、process-pollとprocess-asyncは出さない`() {
        val wit = derived()
        val expected = setOf(
            "[static]process.spawn", "[method]process.pid", "[method]process.status", "[method]process.kill", "[resource-drop]process",
            "kill-pid", "self-pid", "parent-pid", "set-signal-ignored", "get-cwd", "set-cwd",
        ).map { WasiP1Kibi.PROCESS to it }.toSet()
        assertEquals(expected, wit.filter { it.first == WasiP1Kibi.PROCESS }.toSet())
        for (fn in expected) assertTrue(fn in WasiP1Kibi.functions, "$fn を p1 に出していない")
        assertTrue(WasiP1Kibi.needsRealloc(WasiP1Kibi.PROCESS, "get-cwd"))
        assertFalse(WasiP1Kibi.needsRealloc(WasiP1Kibi.PROCESS, "[static]process.spawn"))
        for ((m, f) in listOf("kibi:process/process-poll@0.1.0" to "subscribe", "kibi:process/process-async@0.1.0" to "wait")) {
            assertTrue((m to f) in wit, "$m.$f が WIT にない")
            assertFalse(m in WasiP1Kibi.modules, "$m を p1 に出している")
        }
    }

    @Test
    fun `kibi_processのp1の部分はwasiを参照せず、p2とp3はほかのkibiパッケージと同じ版のwasiだけを使う`() {
        val world = witText("kibi-process/world.wit")
        val imports = Regex("""world\s+imports\s*\{([^}]*)}""").find(world)!!.groupValues[1]
        assertFalse("wasi:" in imports, imports)
        assertFalse(useLines(witText("kibi-process/process.wit")).any { "wasi:" in it }, "process.wit が wasi:* を使っている")
        assertEquals(
            listOf(
                "use wasi:io/poll@0.2.3.{pollable};",
                "use wasi:io/streams@0.2.3.{input-stream, output-stream};",
                "use wasi:filesystem/types@0.2.3.{descriptor};",
            ),
            useLines(witText("kibi-process/process-poll.wit")).filter { "wasi:" in it },
        )
        assertEquals(
            listOf("use wasi:filesystem/types@0.3.0.{descriptor};"),
            useLines(witText("kibi-process/process-async.wit")).filter { "wasi:" in it },
        )
        // process は kibi:kernel/types の error-code を使う（p1 の errno と同じ並び）
        assertTrue("use kibi:kernel/types@0.1.0.{error-code};" in useLines(witText("kibi-process/process.wit")))
    }

    /** WIT のパッケージ（`ns:pkg@ver`）→ interface 名 → その中で定義した型の名前 */
    private fun definedTypes(texts: List<String>): Map<String, Map<String, Set<String>>> {
        val out = HashMap<String, HashMap<String, HashSet<String>>>()
        val pkgRe = Regex("""^\s*package\s+([a-z0-9-]+:[a-z0-9-]+@[0-9.]+)\s*;""")
        val ifaceRe = Regex("""^\s*interface\s+([a-z0-9-]+)\s*\{""")
        val typeRe = Regex("""^\s*(?:enum|variant|record|flags|resource|type)\s+%?([a-z0-9-]+)""")
        for (text in texts) {
            var pkg: String? = null
            var iface: String? = null
            var depth = 0
            for (raw in text.lines()) {
                val line = raw.substringBefore("//")
                pkgRe.find(line)?.let { pkg = it.groupValues[1] }
                if (depth == 0) ifaceRe.find(line)?.let { iface = it.groupValues[1] }
                val p = pkg
                val i = iface
                if (depth == 1 && p != null && i != null) typeRe.find(line)?.let { out.getOrPut(p) { HashMap() }.getOrPut(i) { HashSet() } += it.groupValues[1] }
                depth += line.count { it == '{' } - line.count { it == '}' }
                if (depth == 0) iface = null
            }
        }
        return out
    }

    /** [text]（package 宣言のある1ファイル）の `use` の参照のうち、[types] で見つからないもの */
    private fun unresolvedUses(text: String, types: Map<String, Map<String, Set<String>>>): List<String> {
        val own = Regex("""(?m)^\s*package\s+([a-z0-9-]+:[a-z0-9-]+@[0-9.]+)\s*;""").find(text)!!.groupValues[1]
        val useRe = Regex("""^use\s+(?:([a-z0-9-]+:[a-z0-9-]+)/)?([a-z0-9-]+)(@[0-9.]+)?\.\{([^}]*)}\s*;$""")
        val out = ArrayList<String>()
        for (u in useLines(text)) {
            val m = useRe.find(u) ?: run {
                out += u
                null
            } ?: continue
            val ns = m.groupValues[1]
            if (ns.startsWith("wasi:")) {
                if (!Regex("""^[0-9]+\.[0-9]+\.[0-9]+$""").matches(m.groupValues[3].removePrefix("@"))) out += u
                continue
            }
            val pkg = if (ns.isEmpty()) own else "$ns${m.groupValues[3]}"
            val iface = types[pkg]?.get(m.groupValues[2])
            for (name in m.groupValues[4].split(",").map { it.trim().substringBefore(" as ") }.filter { it.isNotEmpty() }) {
                if (iface == null || name !in iface) out += "$u ($name)"
            }
        }
        return out
    }

    @Test
    fun `WITのuseの参照先のパッケージ・interface・型がsrc_main_witにある`() {
        val texts = witFiles().map { it.readText() }
        val types = definedTypes(texts)
        for (f in witFiles()) {
            assertEquals(emptyList<String>(), unresolvedUses(f.readText(), types), f.path)
        }
        // 誤りを見つけられること: ない型・ない interface・版の違い
        val bad = """
            package kibi:process@0.1.0;
            interface x {
                use kibi:kernel/types@0.1.0.{no-such-type};
                use kibi:kernel/nothing@0.1.0.{error-code};
                use kibi:kernel/types@0.9.0.{error-code};
                use process.{exit-status, nope};
            }
        """.trimIndent()
        assertEquals(4, unresolvedUses(bad, types).size, unresolvedUses(bad, types).toString())
    }

    @Test
    fun `src_main_witにはdepsもシンボリックリンクも置かない`() {
        val root = File("src/main/wit")
        val all = root.walkTopDown().toList()
        assertFalse(all.any { it.isDirectory && it.name == "deps" }, "deps/ を置いている（解決はテストの時に組み立てる）")
        assertFalse(all.any { java.nio.file.Files.isSymbolicLink(it.toPath()) }, "シンボリックリンクを置いている")
    }

    private fun useLines(text: String): List<String> =
        text.lines().map { it.substringBefore("//").trim() }.filter { it.startsWith("use ") }

    @Test
    fun `WITの識別子は予約語に当たらない`() {
        for (f in witFiles()) {
            val bad = reservedIdentifiers(f.readText())
            assertTrue(bad.isEmpty(), "${f.path}: 予約語の識別子（% で逃がすか改名する）: $bad")
        }
    }

    @Test
    fun `予約語の検査は引数名のfromを拾い、%fromは通す`() {
        val bad = """
            interface fd {
                dup-to: func(from: u32, to: u32) -> result<_, error-code>;
            }
        """.trimIndent()
        assertEquals(listOf("from"), reservedIdentifiers(bad))
        assertEquals(emptyList<String>(), reservedIdentifiers(bad.replace("(from:", "(%from:")))
        val names = """
            interface use {
                enum e {
                    ok,
                    stream,
                }
            }
        """.trimIndent()
        assertEquals(listOf("use", "stream"), reservedIdentifiers(names))
    }

    /**
     * [text] の中で、`%` を付けずに予約語を使った識別子（定義する名前・引数名・フィールド名・case 名）。
     * `package`・`use`・`import`・`export`・`include` の行の中（パッケージ名など）は見ない
     */
    private fun reservedIdentifiers(text: String): List<String> {
        val out = ArrayList<String>()
        val named = Regex("""(?<![%\w-])(?:interface|world|resource|record|variant|enum|flags|type)\s+([a-z][a-z0-9-]*)""")
        val beforeColon = Regex("""(?<![%\w-])([a-z][a-z0-9-]*)\s*:(?!:)""")
        val caseName = Regex("""^\s*(%?)([a-z][a-z0-9-]*)\s*[,(]""")
        val opener = Regex("""^\s*(enum|flags|variant)\b[^{]*\{""")
        var inCases = false
        for (raw in text.lines()) {
            val line = raw.substringBefore("//")
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (Regex("""^(package|use|import|export|include)\b""").containsMatchIn(trimmed)) continue
            named.findAll(line).forEach { if (it.groupValues[1] in WIT_KEYWORDS) out += it.groupValues[1] }
            beforeColon.findAll(line).forEach { if (it.groupValues[1] in WIT_KEYWORDS) out += it.groupValues[1] }
            if (inCases) {
                caseName.find(line)?.let { if (it.groupValues[1].isEmpty() && it.groupValues[2] in WIT_KEYWORDS) out += it.groupValues[2] }
                if ("}" in line) inCases = false
            }
            if (opener.containsMatchIn(line) && "}" !in line.substringAfter("{")) inCases = true
        }
        return out
    }

    private companion object {
        /**
         * WIT のキーワード（wit-parser の字句解析 `crates/wit-parser/src/ast/lex.rs` の `keyword`）。
         * 識別子に使うときは `%` を付ける（例: `events.wit` の `%s64`）
         */
        val WIT_KEYWORDS: Set<String> = setOf(
            "use", "type", "func", "from", "as", "with", "include", "static", "constructor", "async",
            "interface", "world", "package", "import", "export", "resource", "record", "variant", "enum", "flags",
            "own", "borrow", "list", "option", "result", "tuple", "future", "stream", "error-context",
            "string", "char", "bool", "u8", "u16", "u32", "u64", "s8", "s16", "s32", "s64", "f32", "f64", "float32", "float64", "_",
        )
    }
}
