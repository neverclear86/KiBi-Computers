package pro.ikili.kibicomputers.poc.waitpointsave

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.poc.waitpointsave.WaiterEvents.ALLOC
import pro.ikili.kibicomputers.poc.waitpointsave.WaiterEvents.BLOCK
import pro.ikili.kibicomputers.poc.waitpointsave.WaiterEvents.PUSH
import pro.ikili.kibicomputers.poc.waitpointsave.WaiterEvents.REPORT

class WaitPointSaveTest {
    private val waiterEvents = listOf(
        waiterEvent(PUSH, 1),
        waiterEvent(PUSH, 2),
        waiterEvent(ALLOC, 3 * 1024 * 1024),
        waiterEvent(PUSH, 3),
        waiterEvent(REPORT, 0),
        waiterEvent(PUSH, 40),
        waiterEvent(ALLOC, 512 * 1024),
        waiterEvent(REPORT, 0),
    )

    private fun assertSameRun(expected: RunResult, actual: RunResult) {
        assertEquals(expected.stdout, actual.stdout)
        assertEquals(expected.memorySha256, actual.memorySha256, "メモリの中身が保存しなかった実行と違う")
    }

    @Test
    fun `waiter - 途中で保存して戻しても、保存しない実行と同じになる`() {
        val plain = runEvents(boot(WasmProgram.WAITER, Mode.INTERPRETER), waiterEvents)
        assertTrue("[1][2][3] count=3 sum=6 walk=6 big=3145728" in plain.stdout, plain.stdout)

        // 途中（大きな確保のあと）で1回だけ保存して戻す
        val p = boot(WasmProgram.WAITER, Mode.INTERPRETER)
        waiterEvents.take(4).forEach { p.deliver(it) }
        val pages = p.instance.memory().pages()
        // [1][2][3] はまだ libc の stdout のバッファ（線形メモリ）の中にある
        assertEquals("", p.kernel.stdoutText())
        val restored = saveAndRestore(p, Mode.INTERPRETER)
        assertEquals(pages, restored.instance.memory().pages(), "memory.grow のあとのページ数が戻らない")
        assertTrue(pages > 48)
        val once = runEvents(restored, waiterEvents.drop(4))
        assertSameRun(plain, once)

        // 各イベントの前に毎回保存して戻す
        assertSameRun(plain, runEvents(boot(WasmProgram.WAITER, Mode.INTERPRETER), waiterEvents, { Mode.INTERPRETER }))
    }

    @Test
    fun `復元後に _initialize を呼ばなくても malloc のヒープと stdout のバッファが続きから使える`() {
        val p = boot(WasmProgram.WAITER, Mode.INTERPRETER)
        p.deliver(waiterEvent(PUSH, 7))
        p.deliver(waiterEvent(PUSH, 8))
        val restored = saveAndRestore(p, Mode.INTERPRETER, viaBytes = true)
        // 復元したものは _initialize を呼ばない（呼ぶと静的変数が初期化される）
        assertThrows(IllegalStateException::class.java) { restored.initialize() }
        restored.deliver(waiterEvent(PUSH, 9))
        restored.deliver(waiterEvent(REPORT, 0))
        assertEquals("[7][8][9] count=3 sum=24 walk=24 big=0 check=0\n", restored.kernel.stdoutText())
    }

    @Test
    fun `lua_host - 待つだけのシナリオで、毎回保存して戻しても結果が同じ`() {
        val events = "hello world 1 2 3".events()
        val plain = runEvents(boot(WasmProgram.LUA_HOST, Mode.INTERPRETER, Scripts.IDLE), events)
        assertEquals("event 1: hello\nevent 2: world\nevent 3: 1\nevent 4: 2\nevent 5: 3\n", plain.stdout)
        assertSameRun(plain, runEvents(boot(WasmProgram.LUA_HOST, Mode.INTERPRETER, Scripts.IDLE), events, { Mode.INTERPRETER }))
    }

    @Test
    fun `lua_host - 1000 個の子コルーチンが待っている状態から、保存と復元を経て全部再開できる`() {
        val script = Scripts.coroutines(1000)
        val events = "all:5 17:3 1000:9 all:1".events()
        val plain = runEvents(boot(WasmProgram.LUA_HOST, Mode.INTERPRETER, script), events)
        val lines = plain.stdout.lines().filter { it.startsWith("co ") }
        assertEquals(2002, lines.size)
        // 全部の子が2回目の all を受け取った（ローカル変数の count が続きから増えている）
        for (i in 1..1000) {
            val n = if (i == 17 || i == 1000) 3 else 2
            assertTrue(lines.any { it.startsWith("co $i acc=") && it.endsWith(" n=$n") }, "co $i")
        }
        assertSameRun(plain, runEvents(boot(WasmProgram.LUA_HOST, Mode.INTERPRETER, script), events, { Mode.INTERPRETER }, viaBytes = true))
    }

    @Test
    fun `lua_host - pcall と coroutine wrap の入れ子の中で待っていた位置から続きが動く`() {
        val events = Scripts.NESTED_EVENTS.map { it.toByteArray() }
        val plain = runEvents(boot(WasmProgram.LUA_HOST, Mode.INTERPRETER, Scripts.NESTED), events)
        assertEquals(Scripts.NESTED_OUTPUT, plain.stdout)
        assertSameRun(plain, runEvents(boot(WasmProgram.LUA_HOST, Mode.INTERPRETER, Scripts.NESTED), events, { Mode.INTERPRETER }, viaBytes = true))
    }

    @Test
    fun `インタプリタで保存してランタイムコンパイラで戻しても、その逆でも同じ`() {
        val events = Scripts.NESTED_EVENTS.map { it.toByteArray() }
        val plain = runEvents(boot(WasmProgram.LUA_HOST, Mode.INTERPRETER, Scripts.NESTED), events)
        // 交互に方式を変えて戻す（インタプリタ → コンパイラ → インタプリタ ...）
        val alternating = runEvents(boot(WasmProgram.LUA_HOST, Mode.INTERPRETER, Scripts.NESTED), events, { if (it % 2 == 0) Mode.RUNTIME_COMPILER else Mode.INTERPRETER })
        assertSameRun(plain, alternating)
        val fromCompiler = runEvents(boot(WasmProgram.LUA_HOST, Mode.RUNTIME_COMPILER, Scripts.NESTED), events, { Mode.INTERPRETER })
        assertSameRun(plain, fromCompiler)

        val waiterPlain = runEvents(boot(WasmProgram.WAITER, Mode.RUNTIME_COMPILER), waiterEvents)
        assertSameRun(waiterPlain, runEvents(boot(WasmProgram.WAITER, Mode.RUNTIME_COMPILER), waiterEvents, { if (it % 2 == 0) Mode.INTERPRETER else Mode.RUNTIME_COMPILER }))
    }

    @Test
    fun `カーネルのイベントキューとタイマーも保存される`() {
        val p = boot(WasmProgram.LUA_HOST, Mode.INTERPRETER, Scripts.IDLE)
        p.kernel.post("a")
        p.kernel.post("b")
        p.kernel.addTimer(p.kernel.clockNanos + 1_000_000, "timer".toByteArray())
        p.deliver(p.kernel.events.removeFirst())
        val restored = saveAndRestore(p, Mode.INTERPRETER, viaBytes = true)
        assertEquals(1, restored.kernel.events.size)
        restored.kernel.advance(2_000_000)
        assertEquals(2, restored.pump())
        assertEquals("event 1: a\nevent 2: b\nevent 3: timer\n", restored.kernel.stdoutText())
    }

    @Test
    fun `別のモジュールには戻せない`() {
        val snap = Snapshot.capture(boot(WasmProgram.WAITER, Mode.INTERPRETER))
        assertThrows(ModuleMismatchException::class.java) { snap.restore(WasmProgram.LUA_HOST, Mode.INTERPRETER) }
        assertThrows(ModuleMismatchException::class.java) { Snapshot.fromBytes(snap.toBytes()).restore(WasmProgram.LUA_HOST, Mode.INTERPRETER) }
    }

    @Test
    fun `ホスト関数の中で待っているあいだは保存できない（ブロッキング型）`() {
        for ((process, event) in listOf(
            boot(WasmProgram.WAITER, Mode.INTERPRETER) to waiterEvent(BLOCK, 41),
            boot(WasmProgram.LUA_HOST, Mode.INTERPRETER, Scripts.IDLE) to "block".toByteArray(),
        )) {
            var failure: Throwable? = null
            process.kernel.hostWaitHook = HostWaitHook { reason ->
                assertEquals("kibi_poc.block", reason)
                failure = runCatching { Snapshot.capture(process) }.exceptionOrNull()
            }
            val r = process.deliver(event)
            assertTrue(failure is NotAtWaitPointException, "保存できてしまった: $failure")
            assertEquals(1, process.kernel.hostWaits)
            if (process.program == WasmProgram.WAITER) assertEquals(42, r)
            // 戻ったあとは待機点なので保存できる
            assertFalse(process.kernel.inWasm)
            Snapshot.capture(process)
        }
    }

    @Test
    fun `ゼロページを省いたバイト列からも同じに戻る`() {
        val p = boot(WasmProgram.LUA_HOST, Mode.INTERPRETER, Scripts.IDLE)
        p.deliver("x".toByteArray())
        val snap = Snapshot.capture(p)
        val sparse = snap.toBytesSkippingZeroPages()
        assertTrue(sparse.size < snap.toBytes().size)
        val restored = Snapshot.fromBytes(sparse).restore(WasmProgram.LUA_HOST, Mode.INTERPRETER)
        assertEquals(memorySha256(p), memorySha256(restored))
        restored.deliver("y".toByteArray())
        assertEquals("event 1: x\nevent 2: y\n", restored.kernel.stdoutText())
    }
}
