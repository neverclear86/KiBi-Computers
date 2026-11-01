package pro.ikili.kibicomputers.poc.threadlesswait

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AsyncifyTest {
    private val lines = listOf("hello", "world", "", "kibi computers", "exit")

    @Test
    fun transformOnlyTouchesFunctionsThatMayWait() {
        for (p in WasmProgram.entries) {
            val r = p.asyncified
            assertTrue(r.instrumented in 1 until r.functions, "${p.name}: ${r.instrumented} / ${r.functions}")
            assertTrue(r.sites > 0)
        }
    }

    @Test
    fun rejectsReservedImport() {
        // 変換済みのモジュールは kibi_async を import しているので、もう一度は変換できない
        assertThrows(AsyncifyException::class.java) { Asyncify.transform(WasmProgram.PROMPT.asyncified.bytes, P1Kernel.ASYNC_IMPORTS) }
    }

    @Test
    fun waitingProcessHoldsNoWasmStack() {
        // 待っている間、WASM のスタックは空で、続きは退避した値（カーネルの状態）だけにある。呼んだスレッドはすぐ戻る
        val p = AsyncProcess(WasmProgram.PROMPT, Mode.INTERPRETER, listOf("prompt", "deep", "10"))
        assertEquals(ProcState.WAITING, p.step())
        assertTrue(p.kernel.frameTop > 0)
        assertEquals(Asyncify.NORMAL.toLong(), p.kernel.asyncState!!.getValue())
        assertEquals(ProcState.WAITING, p.feed("a\n"))
        assertTrue(p.kernel.stdoutText().contains("1 a "))
    }

    /** 変換しないプログラムを普通に（ブロッキングで）動かした出力 */
    private fun blockingOutput(program: WasmProgram, mode: Mode, args: List<String>, input: List<String>): String {
        val p = BlockingProcess(program, mode, args, ThreadKind.PLATFORM)
        input.forEach { p.feed("$it\n") }
        p.kernel.closeStdin()
        p.start()
        p.thread!!.join(60_000)
        assertEquals(ProcState.EXITED, p.state, p.failure?.toString())
        return p.kernel.stdoutText()
    }

    /** 変換したプログラムを、1行ごとに待機点まで戻しながら動かした出力 */
    private fun asyncOutput(program: WasmProgram, mode: Mode, args: List<String>, input: List<String>, move: Boolean = false): Pair<String, Int> {
        val p = AsyncProcess(program, mode, args)
        p.step()
        for (line in input) {
            if (move) p.moveToNewInstance()
            if (p.state != ProcState.WAITING) break
            p.feed("$line\n")
        }
        if (p.state == ProcState.WAITING) {
            p.kernel.closeStdin()
            p.step()
        }
        assertEquals(ProcState.EXITED, p.state)
        return p.kernel.stdoutText() to p.kernel.waits
    }

    @ParameterizedTest
    @EnumSource(Mode::class)
    fun promptMatchesBlocking(mode: Mode) {
        val expected = blockingOutput(WasmProgram.PROMPT, mode, listOf("prompt"), lines)
        val (actual, waits) = asyncOutput(WasmProgram.PROMPT, mode, listOf("prompt"), lines)
        assertEquals(expected, actual)
        assertTrue(waits >= lines.size, "待った回数 $waits")
    }

    @ParameterizedTest
    @EnumSource(Mode::class)
    fun deepRecursionMatchesBlocking(mode: Mode) {
        val args = listOf("prompt", "deep", "200")
        assertEquals(blockingOutput(WasmProgram.PROMPT, mode, args, lines), asyncOutput(WasmProgram.PROMPT, mode, args, lines).first)
    }

    @ParameterizedTest
    @EnumSource(Mode::class)
    fun benchMatchesOriginal(mode: Mode) {
        val args = listOf("prompt", "bench", "20000")
        assertEquals(blockingOutput(WasmProgram.PROMPT, mode, args, emptyList()), asyncOutput(WasmProgram.PROMPT, mode, args, emptyList()).first)
    }

    private val luaLines = listOf(
        "x = 0",
        "for i = 1, 100 do x = x + i end",
        "print(x)",
        "t = {} for i = 1, 1000 do t[i] = tostring(i) end",
        "print(#t, t[500])",
        "print(pcall(error, 'boom'))",
        "co = coroutine.wrap(function(a) local b = coroutine.yield(a + 1) return b * 2 end)",
        "print(co(1), co(21))",
        "print(io.read())",
        "read by io.read",
        "local ok, e = pcall(function() error({code = 7}) end) print(ok, e.code)",
        "os.exit(3)",
    )

    @ParameterizedTest
    @EnumSource(Mode::class)
    fun luaReplMatchesBlocking(mode: Mode) {
        val args = listOf("lua", "-i")
        val (actual, waits) = asyncOutput(WasmProgram.LUA, mode, args, luaLines)
        assertEquals(blockingOutput(WasmProgram.LUA, mode, args, luaLines), actual)
        assertTrue(waits >= luaLines.size, "待った回数 $waits")
    }

    @ParameterizedTest
    @EnumSource(Mode::class)
    fun luaSurvivesMoveToNewInstance(mode: Mode) {
        val args = listOf("lua", "-i")
        val expected = blockingOutput(WasmProgram.LUA, mode, args, luaLines)
        assertEquals(expected, asyncOutput(WasmProgram.LUA, mode, args, luaLines, move = true).first)
    }

    @ParameterizedTest
    @EnumSource(Mode::class)
    fun promptSurvivesMoveToNewInstance(mode: Mode) {
        val args = listOf("prompt", "deep", "50")
        assertEquals(blockingOutput(WasmProgram.PROMPT, mode, args, lines), asyncOutput(WasmProgram.PROMPT, mode, args, lines, move = true).first)
    }

    @ParameterizedTest
    @EnumSource(Mode::class)
    fun virtualThreadsWaitWithoutPinning(mode: Mode) {
        if (!ThreadKind.virtualAvailable) return
        val n = 50
        val latch = CountDownLatch(n)
        val procs = (0 until n).map { BlockingProcess(WasmProgram.PROMPT, mode, listOf("prompt"), ThreadKind.VIRTUAL) }
        procs.forEach { p ->
            p.kernel.onWait = { latch.countDown() }
            p.start()
        }
        assertTrue(latch.await(60, TimeUnit.SECONDS))
        procs.forEach { it.feed("exit\n") }
        procs.forEach { it.thread!!.join(30_000) }
        procs.forEach { assertEquals(ProcState.EXITED, it.state) }
    }
}
