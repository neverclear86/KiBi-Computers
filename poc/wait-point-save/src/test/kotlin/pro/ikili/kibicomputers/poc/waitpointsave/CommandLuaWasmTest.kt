package pro.ikili.kibicomputers.poc.waitpointsave

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** #91 の lua.wasm（コマンド形式）は、待つのがホスト関数の中なので待機点に来ない（ブロッキング型）。 */
class CommandLuaWasmTest {
    /** [args] で `_start` を動かし、標準入力を待つたびに [inputs] を1つずつ渡す（尽きたら EOF）。保存を試みた結果も返す */
    private fun run(args: List<String>, inputs: List<String>): Pair<Process, List<Throwable?>> {
        val p = Process.start(WasmProgram.LUA_COMMAND, Mode.INTERPRETER, args)
        val attempts = mutableListOf<Throwable?>()
        val queue = ArrayDeque(inputs)
        p.kernel.hostWaitHook = HostWaitHook { reason ->
            assertEquals("wasi_snapshot_preview1.fd_read", reason)
            attempts += runCatching { Snapshot.capture(p) }.exceptionOrNull()
            // 比べるための測定用のスナップショットは取れる（復元には使えない）
            val m = Snapshot.measureOnly(p)
            assertTrue(!m.restorable && m.pages > 0)
            val next = queue.removeFirstOrNull()
            if (next == null) p.kernel.stdinClosed = true else p.kernel.feedStdin(next.toByteArray())
        }
        p.runCommand()
        return p to attempts
    }

    @Test
    fun `REPL は fd_read の中で待ち、そこでは保存できない`() {
        val (p, attempts) = run(listOf("lua"), listOf("print(1 + 1)\n"))
        assertEquals(ProcessState.EXITED, p.kernel.state)
        assertEquals(0, p.kernel.exitCode)
        assertEquals(2, attempts.size)
        assertTrue(attempts.all { it is NotAtWaitPointException }, attempts.toString())
        val out = p.kernel.stdoutText()
        assertTrue(out.startsWith("Lua 5.4"), out)
        assertTrue("> 2\n> " in out, out)
        assertEquals(emptyList<String>(), p.wasi.unimplemented.filter { it.startsWith("kibi") })
    }

    @Test
    fun `io read の中で待ち、そこでは保存できない。入力を渡すと続きが動く`() {
        val (p, attempts) = run(listOf("lua", "-e", "local l = io.read(); print(l)"), listOf("hello\n"))
        assertEquals(1, attempts.size)
        assertTrue(attempts[0] is NotAtWaitPointException)
        assertEquals("hello\n", p.kernel.stdoutText())
        assertEquals(ProcessState.EXITED, p.kernel.state)
    }

    @Test
    fun `export は _start と memory だけで、途中から入り直す口がない`() {
        val section = WasmProgram.LUA_COMMAND.module.exportSection()
        val names = (0 until section.exportCount()).map { section.getExport(it).name() }.sorted()
        assertEquals(listOf("_start", "memory"), names)
    }
}
