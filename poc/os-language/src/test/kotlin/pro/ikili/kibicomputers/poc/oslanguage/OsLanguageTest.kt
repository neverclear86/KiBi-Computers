package pro.ikili.kibicomputers.poc.oslanguage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime

/** 両方の言語のプログラムが同じ振る舞いをし、カーネルの上で待機点型として動くこと（#32）。測定の表も出す */
class OsLanguageTest {
    private val required = System.getProperty("kibi.moonbit.required")?.toBoolean() ?: false

    private fun languages(): List<Language> {
        val available = availableLanguages()
        assertTrue(Language.ZIG in available, "Zig のプログラムがない")
        if (required) assertTrue(Language.MOONBIT in available, "MoonBit をビルドしたはずなのに、MoonBit のプログラムがない")
        return available
    }

    @Test
    fun `どのシナリオも両方の言語で同じ出力と終了コードになる`() {
        val langs = languages()
        val runtime = EndiveRuntime()
        for (s in Bench.scenarios) {
            val results = langs.associateWith { l ->
                val module = MiniWasi.load(runtime, wasm(s.program, l)!!)
                val host = MiniWasi(s.args, s.stdin, s.files)
                host.run(module).also { assertEquals(emptyList<String>(), host.missingImports, "${l.label} ${s.label}") }
            }
            val zig = results.getValue(Language.ZIG)
            assertEquals(0, zig.exitCode, s.label)
            for ((l, r) in results) {
                assertEquals(zig.exitCode, r.exitCode, "${l.label} ${s.label}")
                assertEquals(String(zig.stdout), String(r.stdout), "${l.label} ${s.label}")
                assertEquals(String(zig.stderr), String(r.stderr), "${l.label} ${s.label}")
            }
        }
    }

    @Test
    fun `出力の中身が期待どおり`() {
        val runtime = EndiveRuntime()
        for (l in languages()) {
            fun run(program: String, args: List<String>, stdin: String = "", files: Map<String, ByteArray> = emptyMap()) =
                MiniWasi(listOf(program) + args, stdin.toByteArray(), files).run(MiniWasi.load(runtime, wasm(program, l)!!))
            assertEquals("a b  c\n", String(run("echo", listOf("a", "b ", "c")).stdout), l.label)
            assertEquals("> [a][b]\n> \n> [x]\n> ", String(run("shell", emptyList(), "a  b\n\n\tx").stdout), l.label)
            assertEquals("hi\n", String(run("cat", listOf("/f"), files = mapOf("f" to "hi\n".toByteArray())).stdout), l.label)
            val missing = run("cat", listOf("/nope"))
            assertEquals(1, missing.exitCode, l.label)
            assertEquals("cat: cannot open /nope\n", String(missing.stderr), l.label)
            assertEquals("a\nb\n", String(run("ls", emptyList(), files = mapOf("a" to ByteArray(0), "b" to ByteArray(0))).stdout), l.label)
        }
    }

    @Test
    fun `カーネルの上で待機点型として動き、入力を待てる`() {
        val langs = languages()
        KernelRun().use { k ->
            for (l in langs) {
                val w = k.runUntilInput(wasm("shell", l)!!, "x y\n")
                assertEquals(ProcessKind.WAIT_POINT, w.kind, l.label)
                assertTrue("[x][y]" in w.afterInput, "${l.label}: ${w.afterInput}")
                assertTrue(w.writtenChunks > 0, l.label)
                val r = k.run(wasm("kmem", l)!!, emptyList())
                assertEquals(ProcessState.EXITED, r.state, "${l.label}: ${r.console}")
                assertEquals(0, r.exitCode, "${l.label}: ${r.console}")
                assertTrue("kibi ok" in r.console, "${l.label}: ${r.console}")
                val ls = k.run(wasm("ls", l)!!, listOf("/d"), mapOf("a" to ByteArray(1)))
                assertEquals(0, ls.exitCode, "${l.label}: ${ls.console}")
                assertTrue("a\n" in ls.console, "${l.label}: ${ls.console}")
            }
        }
    }

    @Test
    fun `測定の表を出す`() {
        assumeTrue(availableLanguages().isNotEmpty())
        println(Bench.run(rounds = 3, languages = languages()))
    }
}
