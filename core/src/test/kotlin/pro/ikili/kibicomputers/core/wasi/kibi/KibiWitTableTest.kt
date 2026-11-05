package pro.ikili.kibicomputers.core.wasi.kibi

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.wasi.p1.WasiP1Kibi
import java.io.File

/**
 * p1 向けの import の表（[WasiP1Kibi]）が、`core/src/main/wit/` の WIT からずれていないこと（#73）。
 * 完全なパーサーではなく、`package`・`interface`・`resource`・関数の名前だけを読む。
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
        val funcRe = Regex("""^\s*%?([a-z0-9-]+)\s*:\s*(static\s+)?func\b""")
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
    fun `WITのkernel_eventsとcomputer_memoryの関数はすべてp1に出している`() {
        val wit = derived().filter { it.first in WasiP1Kibi.modules }.toSet()
        assertTrue(wit.isNotEmpty())
        for (fn in wit) assertTrue(fn in WasiP1Kibi.functions, "$fn を p1 に出していない")
    }
}
