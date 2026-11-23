package pro.ikili.kibicomputers.core.runtime.asyncify

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.runtime.Instrumentation
import pro.ikili.kibicomputers.core.runtime.TestWasiP1
import pro.ikili.kibicomputers.core.runtime.WasmAsyncStack
import pro.ikili.kibicomputers.core.runtime.WasmAsyncState
import pro.ikili.kibicomputers.core.runtime.WasmBinary
import pro.ikili.kibicomputers.core.runtime.WasmFuelHandler
import pro.ikili.kibicomputers.core.runtime.WasmFunctionType
import pro.ikili.kibicomputers.core.runtime.WasmInstance
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmModule
import pro.ikili.kibicomputers.core.runtime.WasmValueType
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.runtime.fuel.FuelInjector
import pro.ikili.kibicomputers.core.wasi.p1.WasiP1

/**
 * 生きているローカルだけを退避する変換（#338）。退避しないローカルにごみを入れる形（[AsyncifyDebug.POISON_UNSAVED]）でも、
 * すべてを退避する形（[AsyncifyDebug.SAVE_ALL]）と同じ結果になることで、生存解析の漏れがないことを確かめる
 */
class AsyncifyLivenessTest {
    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/$name")) { "$name がリソースにない" }.use { it.readBytes() }

    private class Stack : WasmAsyncStack {
        val values = ArrayList<Long>()
        var max = 0

        override fun push(value: Long) {
            values += value
            max = maxOf(max, values.size)
        }

        override fun pop(): Long = values.removeAt(values.size - 1)
    }

    /** 燃料が切れるたびに巻き戻す（巻き戻せないところでは補充して続ける） */
    private class Unwinder(val grant: Long) : WasmFuelHandler {
        var unwinds = 0

        override fun onFuelExhausted(instance: WasmInstance, remaining: Long): Long = grant

        override fun onFuelExhaustedUnwindable(instance: WasmInstance, remaining: Long): Long {
            if (instance.asyncState == WasmAsyncState.REWINDING) {
                instance.asyncState = WasmAsyncState.NORMAL
                return instance.fuel
            }
            unwinds++
            instance.asyncState = WasmAsyncState.UNWINDING
            return remaining
        }
    }

    /** 例外（pcall・error）・コルーチン・コールバック・ループ・文字列の処理を通る Lua の式 */
    private val script = "local s = 0 for i = 1, 20000 do s = s + i % 7 end " +
        "local t = {} for i = 1, 2000 do t[i] = (i * 7919) % 1009 end table.sort(t, function(a, b) return a > b end) " +
        "local u = '' for i = 1, 300 do u = string.format('%s%d', string.sub(u, -20), i) end " +
        "local e = 0 for i = 1, 200 do local ok, m = pcall(error, {i}) if not ok then e = e + m[1] end end " +
        "local co = coroutine.wrap(function() for i = 1, 100 do coroutine.yield(i * 1.5) end end) local c = 0 for i = 1, 100 do c = c + co() end " +
        "local g = ('abc'):rep(50):gsub('b', function(x) return x:upper() end) " +
        "print(s, t[1], t[2000], #u, u, e, c, g, string.format('%.3f', math.sqrt(s)))"

    /** `lua -e <script>` を、燃料を [grant] ずつ渡して切れるたびに巻き戻しながら動かし、(標準出力, 巻き戻した回数, 退避した値の数の最大) を返す */
    private fun runUnwinding(module: WasmModule, grant: Long): Triple<String, Int, Int> {
        val wasi = TestWasiP1(listOf("lua", "-e", script))
        val stack = Stack()
        val handler = Unwinder(grant)
        var failure: Throwable? = null
        val thread = Thread(null, {
            try {
                module.instantiate(wasi.config(module) { asyncStack(stack); fuel(grant, handler) }).use { instance ->
                    val code = try {
                        instance.call("_start")
                        while (instance.asyncState == WasmAsyncState.UNWINDING) {
                            instance.asyncState = WasmAsyncState.REWINDING
                            instance.fuel = grant
                            instance.call("_start")
                        }
                        0
                    } catch (e: TestWasiP1.Exit) {
                        e.code
                    }
                    assertEquals(0, code, wasi.stderr.toString(Charsets.UTF_8))
                }
            } catch (e: Throwable) {
                failure = e
            }
        }, "asyncify-liveness-test", 16L * 1024 * 1024)
        thread.start()
        thread.join()
        failure?.let { throw it }
        return Triple(wasi.stdout.toString(Charsets.UTF_8), handler.unwinds, stack.max)
    }

    private fun reference(): String {
        val wasi = TestWasiP1(listOf("lua", "-e", script))
        var out: String? = null
        val thread = Thread(null, {
            assertEquals(0, wasi.run(EndiveRuntime(compile = false).load(resource("lua.wasm"))))
            out = wasi.stdout.toString(Charsets.UTF_8)
        }, "asyncify-liveness-ref", 16L * 1024 * 1024)
        thread.start()
        thread.join()
        return requireNotNull(out) { wasi.stderr.toString(Charsets.UTF_8) }
    }

    @Test
    fun `lua_wasmを燃料切れのたびに巻き戻しても、戻さないローカルにごみを入れても、出力は巻き戻さないときと同じ`() {
        val expected = reference()
        assertTrue(expected.startsWith("59998\t1008\t0\t"), expected)
        val metered = FuelInjector.inject(resource("lua.wasm"))
        val results = HashMap<AsyncifyDebug, Triple<String, Int, Int>>()
        for (debug in AsyncifyDebug.entries) {
            val r = Asyncify.transform(metered.bytes, WasiP1.WAIT_IMPORTS, Instrumentation.YIELD_IMPORT, metered::originalFunc, debug = debug)
            if (debug == AsyncifyDebug.SAVE_ALL) assertEquals(r.allLocals, r.savedLocals) else assertTrue(r.savedLocals < r.allLocals)
            val out = runUnwinding(EndiveRuntime(compile = false).load(r.bytes), grant = 20_000)
            assertEquals(expected, out.first, "$debug")
            assertTrue(out.second > 50, "$debug: 巻き戻しが少ない（${out.second}）")
            results[debug] = out
            println("$debug: 巻き戻し ${out.second} 回、退避した値の数の最大 ${out.third}")
        }
        // 生きているものだけを退避すると、退避する値が減る
        assertTrue(results[AsyncifyDebug.NONE]!!.third < results[AsyncifyDebug.SAVE_ALL]!!.third, "$results")
    }

    @Test
    fun `本番の読み込みは生きているローカルだけを退避する`() {
        val metered = FuelInjector.inject(resource("lua.wasm"))
        val r = Asyncify.transform(metered.bytes, WasiP1.WAIT_IMPORTS, Instrumentation.YIELD_IMPORT, metered::originalFunc)
        val all = Asyncify.transform(metered.bytes, WasiP1.WAIT_IMPORTS, Instrumentation.YIELD_IMPORT, metered::originalFunc, debug = AsyncifyDebug.SAVE_ALL)
        assertArrayEquals(Instrumentation.apply(resource("lua.wasm"), WasiP1.WAIT_IMPORTS), r.bytes)
        assertTrue(r.codeAfter < all.codeAfter, "${r.codeAfter} / ${all.codeAfter}")
        println("lua.wasm: 退避するローカル ${r.savedLocals} / ${r.allLocals}、関数あたりの最大 ${r.maxLocals} / ${all.maxLocals}、コード ${r.codeAfter} / ${all.codeAfter}（変換前 ${r.codeBefore}）")
    }

    // ---- 手で組み立てるモジュール ----

    private fun bytes(vararg b: Int): ByteArray = ByteArray(b.size) { b[it].toByte() }

    private fun vec(items: List<ByteArray>): ByteArray = items.fold(WasmBinary.uleb(items.size.toLong())) { acc, b -> acc + b }

    private fun name(s: String): ByteArray = s.toByteArray().let { WasmBinary.uleb(it.size.toLong()) + it }

    private fun module(vararg sections: Pair<Int, ByteArray>): ByteArray {
        var out = bytes(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
        for ((id, content) in sections) out += bytes(id) + WasmBinary.uleb(content.size.toLong()) + content
        return out
    }

    /** `w.wait() -> i32` を import し、i32 のローカルを1つ持つ `run() -> i32`（本体は [code]。`end` を含めない）を export するモジュール */
    private fun runModule(code: ByteArray, tag: Boolean = false): ByteArray {
        val types = vec(listOf(bytes(0x60, 0x00, 0x01, 0x7F), bytes(0x60, 0x00, 0x00)))
        val body = bytes(0x01, 0x01, 0x7F) + code + bytes(0x0B)
        val sections = mutableListOf(
            1 to types,
            2 to vec(listOf(name("w") + name("wait") + bytes(0x00, 0x00))),
            3 to vec(listOf(bytes(0x00))),
        )
        if (tag) sections += 13 to vec(listOf(bytes(0x00, 0x01)))
        sections += 7 to vec(listOf(name("run") + bytes(0x00, 0x01)))
        sections += 10 to vec(listOf(WasmBinary.uleb(body.size.toLong()) + body))
        return module(*sections.toTypedArray())
    }

    /** `run` を呼んで、最初の `w.wait` で巻き戻し、巻き直した結果を返す（wait はいつも 41 を返す） */
    private fun runWithWait(wasm: ByteArray, debug: AsyncifyDebug): Long {
        val module = EndiveRuntime(compile = false).load(Asyncify.transform(wasm, setOf("w.wait"), debug = debug).bytes)
        val stack = Stack()
        var unwound = false
        val config = WasmInstanceConfig.builder()
            .hostFunction("w", "wait", WasmFunctionType.of(emptyList(), listOf(WasmValueType.I32))) { instance, _ ->
                if (unwound && instance.asyncState == WasmAsyncState.NORMAL) {
                    longArrayOf(41)
                } else if (instance.asyncState == WasmAsyncState.REWINDING) {
                    assertTrue(stack.values.isEmpty())
                    instance.asyncState = WasmAsyncState.NORMAL
                    longArrayOf(41)
                } else {
                    unwound = true
                    instance.asyncState = WasmAsyncState.UNWINDING
                    longArrayOf(0)
                }
            }
            .asyncStack(stack)
            .build()
        return module.instantiate(config).use { instance ->
            instance.call("run")
            assertEquals(WasmAsyncState.UNWINDING, instance.asyncState)
            instance.asyncState = WasmAsyncState.REWINDING
            instance.call("run")[0].also { assertTrue(stack.values.isEmpty()) }
        }
    }

    @Test
    fun `catchの先でだけ読むローカルも退避する`() {
        // local 0 = 42; block (try_table (catch 0 0) call wait; drop; throw 0 end; i32.const 0; return) end; local.get 0
        val wasm = runModule(
            bytes(
                0x41, 0x2A, 0x21, 0x00,
                0x02, 0x40,
                0x1F, 0x40, 0x01, 0x00, 0x00, 0x00,
                0x10, 0x00, 0x1A, 0x08, 0x00,
                0x0B,
                0x41, 0x00, 0x0F,
                0x0B,
                0x20, 0x00,
            ),
            tag = true,
        )
        for (debug in AsyncifyDebug.entries) assertEquals(42L, runWithWait(wasm, debug), "$debug")
    }

    @Test
    fun `loopの後ろ向きの辺で読むローカルも退避し、待ち点の後で読まないローカルは退避しない`() {
        // local 0 = 3; loop: (wait の結果を捨てる) local 0 -= 1; local 0 != 0 なら先頭へ; end; wait() + local 0（0）
        val loop = runModule(
            bytes(
                0x41, 0x03, 0x21, 0x00,
                0x03, 0x40,
                0x10, 0x00, 0x1A,
                0x20, 0x00, 0x41, 0x01, 0x6B, 0x22, 0x00, 0x0D, 0x00,
                0x0B,
                0x10, 0x00, 0x20, 0x00, 0x6A,
            ),
        )
        for (debug in AsyncifyDebug.entries) assertEquals(41L, runWithWait(loop, debug), "$debug")
        assertEquals(1, Asyncify.transform(loop, setOf("w.wait")).maxLocals)

        // local 0 = 5; wait() + 1。local 0 は待ち点の後で読まない
        val dead = runModule(bytes(0x41, 0x05, 0x21, 0x00, 0x10, 0x00, 0x41, 0x01, 0x6A))
        for (debug in AsyncifyDebug.entries) assertEquals(42L, runWithWait(dead, debug), "$debug")
        assertEquals(0, Asyncify.transform(dead, setOf("w.wait")).maxLocals)
        assertEquals(2, Asyncify.transform(dead, setOf("w.wait"), debug = AsyncifyDebug.SAVE_ALL).maxLocals)
    }
}
