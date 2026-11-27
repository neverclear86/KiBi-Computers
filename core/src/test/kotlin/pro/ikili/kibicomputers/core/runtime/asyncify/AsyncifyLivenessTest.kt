package pro.ikili.kibicomputers.core.runtime.asyncify

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.runtime.Instrumentation
import pro.ikili.kibicomputers.core.runtime.TestWasiP1
import pro.ikili.kibicomputers.core.runtime.WasmAsyncStack
import pro.ikili.kibicomputers.core.runtime.WasmAsyncState
import pro.ikili.kibicomputers.core.runtime.WasmBinary
import pro.ikili.kibicomputers.core.runtime.WasmFuelHandler
import pro.ikili.kibicomputers.core.runtime.WasmFunctionType
import pro.ikili.kibicomputers.core.runtime.WasmHostFunction
import pro.ikili.kibicomputers.core.runtime.WasmInstance
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.WasmModule
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.WasmValueType
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.runtime.fuel.FuelInjector
import pro.ikili.kibicomputers.core.wasi.kibi.WitTool
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
        val r = Asyncify.transform(metered.bytes, WasiP1.WAIT_IMPORTS, Instrumentation.YIELD_IMPORT, metered::originalFunc, fuelSites = metered.sites)
        val all = Asyncify.transform(
            metered.bytes, WasiP1.WAIT_IMPORTS, Instrumentation.YIELD_IMPORT, metered::originalFunc, debug = AsyncifyDebug.SAVE_ALL, fuelSites = metered.sites,
        )
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

    /** `w.wait() -> i32` を import し、i32 のローカルを [locals] 個持つ `run() -> i32`（本体は [code]。`end` を含めない）を export するモジュール */
    private fun runModule(code: ByteArray, tag: Boolean = false, locals: Int = 1): ByteArray {
        val types = vec(listOf(bytes(0x60, 0x00, 0x01, 0x7F), bytes(0x60, 0x00, 0x00)))
        val body = bytes(0x01) + WasmBinary.uleb(locals.toLong()) + bytes(0x7F) + code + bytes(0x0B)
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

    @Test
    fun `入れ子のtry_tableでは、外側のcatch先だけで読むローカルも退避する`() {
        // local 0 = 42; block $A (block $B (try_table (catch_all $A) (try_table (catch_all $B) call wait; drop)) i32.const 7; return) <最後に読む>
        fun nested(last: ByteArray) = runModule(
            bytes(
                0x41, 0x2A, 0x21, 0x00,
                0x02, 0x40,
                0x02, 0x40,
                0x1F, 0x40, 0x01, 0x02, 0x01,
                0x1F, 0x40, 0x01, 0x02, 0x01,
                0x10, 0x00, 0x1A,
                0x0B,
                0x0B,
                0x0B,
                0x41, 0x07, 0x0F,
                0x0B,
            ) + last,
        )
        val reads = nested(bytes(0x20, 0x00))
        for (debug in AsyncifyDebug.entries) assertEquals(7L, runWithWait(reads, debug), "$debug")
        assertEquals(1, Asyncify.transform(reads, setOf("w.wait")).maxLocals)
        // 外側の catch 先で読まなければ、退避しない
        assertEquals(0, Asyncify.transform(nested(bytes(0x41, 0x09)), setOf("w.wait")).maxLocals)
    }

    /** `w.wait` を import し、funcref のローカル `$r` に [init] を入れてから待ち、[after] を足して返す `run`（`$r` 自体も ref.func できる） */
    private fun refModule(init: String, after: String): ByteArray = WitTool.wat(
        """
        (module
          (import "w" "wait" (func ${'$'}wait (result i32)))
          (elem declare func ${'$'}run)
          (func ${'$'}run (export "run") (result i32)
            (local ${'$'}r funcref)
            (local.set ${'$'}r $init)
            (call ${'$'}wait)
            $after
            i32.add))
        """.trimIndent(),
    )

    @Test
    fun `待ち点の後で読まない参照型のローカルは、nullでなくても積まないので巻き戻せる`() {
        // 参照は退避できない（巻き戻しで null でなければ trap。ADR 0329）。生きていなければ確かめもしない
        val dead = refModule("(ref.func ${'$'}run)", "(i32.const 1)")
        assertEquals(0, Asyncify.transform(dead, setOf("w.wait")).maxLocals)
        for (debug in listOf(AsyncifyDebug.NONE, AsyncifyDebug.POISON_UNSAVED)) assertEquals(42L, runWithWait(dead, debug), "$debug")
        // すべて退避する今までの形では、生きていなくても null でない参照で trap する
        assertThrows<WasmTrapException> { runWithWait(dead, AsyncifyDebug.SAVE_ALL) }
    }

    @Test
    fun `待ち点の後で読む参照型のローカルは、nullなら戻し、nullでなければ巻き戻しでtrapする`() {
        val liveNull = refModule("(ref.null func)", "(ref.is_null (local.get ${'$'}r))")
        assertEquals(1, Asyncify.transform(liveNull, setOf("w.wait")).maxLocals)
        for (debug in AsyncifyDebug.entries) assertEquals(42L, runWithWait(liveNull, debug), "$debug")

        val liveFunc = refModule("(ref.func ${'$'}run)", "(ref.is_null (local.get ${'$'}r))")
        for (debug in AsyncifyDebug.entries) assertThrows<WasmTrapException>("$debug") { runWithWait(liveFunc, debug) }
    }

    // --- call_ref（#923） ---

    /** `w.wait` を import し、型 `$v_i`（`() -> i32`。止まりうる関数の型）と `$v_l`（`() -> i64`。止まりうる関数の型でない）を持つモジュール */
    private fun callRefModule(funcs: String): ByteArray = WitTool.wat(
        """
        (module
          (type ${'$'}v_i (func (result i32)))
          (type ${'$'}v_l (func (result i64)))
          (import "w" "wait" (func ${'$'}wait (type ${'$'}v_i)))
          (table 1 funcref)
          (elem declare func ${'$'}wait ${'$'}seven)
          (func ${'$'}seven (type ${'$'}v_l) (i64.const 7))
          $funcs)
        """.trimIndent(),
    )

    private fun assertRejected(wasm: ByteArray, vararg parts: String) {
        for (debug in AsyncifyDebug.entries) {
            val e = assertThrows<AsyncifyUnsupported>("$debug") { Asyncify.transform(wasm, setOf("w.wait"), debug = debug) }
            for (part in parts) assertTrue(e.message!!.contains(part), "$debug: ${e.message}")
        }
    }

    @Test
    fun `止まりうるcall_refは、変換で断る`() {
        // Issue の例。参照を一時ローカルに移すと funcref になって検証に通らず、参照の型にしても巻き戻しで必ず trap する（null でない参照は退避できない）
        val wasm = callRefModule("(func (export \"run\") (result i32) (call_ref ${'$'}v_i (ref.func ${'$'}wait)))")
        EndiveRuntime().load(wasm)
        assertRejected(wasm, "止まりうる関数 #2（export run） の中", "止まりうる call_ref")
        // 燃料の計測コードの挿入と燃料切れの待ち点化を通す読み込み（exec の経路）も、変換できない理由を持って断る
        val e = assertThrows<WasmLoadException> { Program.loadMetered(EndiveRuntime(), wasm, null, setOf("w.wait")) }
        val reason = Program.unsuspendableReason(e)
        assertTrue(reason?.contains("call_ref") == true, e.message)
        // 止まりうる import がなければ、止まりうる関数がないので断らない（#384）
        assertEquals(0, Asyncify.transform(wasm, emptySet()).instrumented)
    }

    @Test
    fun `外の型のcall_refも、変換で断る`() {
        // 型 $v_l は、このモジュールの中では止まりうる関数の型でない。外の型（#757）に入れると、止まりうる call_ref になる
        val wasm = callRefModule("(func (export \"run\") (result i64) (call_ref ${'$'}v_l (ref.func ${'$'}seven)))")
        val external = setOf(Asyncify.Signature(emptyList(), listOf(0x7E)))
        val prepared = Asyncify.prepare(wasm)
        // 解析は断らない（コンポーネントは解析を不動点まで回す）
        assertEquals(setOf("run"), prepared.analyze(emptySet(), external).suspendableExports)
        val e = assertThrows<AsyncifyUnsupported> { prepared.transform(emptySet(), external) }
        assertTrue(e.message!!.contains("止まりうる関数 #2（export run） の中") && e.message!!.contains("止まりうる call_ref"), e.message)
        // 外の型がなければ、止まりうる関数がない
        assertEquals(0, prepared.transform(emptySet()).instrumented)
    }

    @Test
    fun `関数の参照を持ったまま待ってからcall_refで呼ぶ形は、止まりうる型でなくても断る`() {
        // ref.func → 待ち点 → call_ref。参照が funcref の一時ローカルを通るので、出力が検証に通らない（run の型は () -> i32 なので、$v_l は止まりうる型でない）
        val crossing = callRefModule(
            """
            (func (export "run") (result i32)
              (ref.func ${'$'}seven)
              (drop (call ${'$'}wait))
              (call_ref ${'$'}v_l)
              i32.wrap_i64)
            """.trimIndent(),
        )
        EndiveRuntime().load(crossing)
        assertRejected(crossing, "止まりうる関数 #2（export run） の中", "call_ref で呼ぶこと")
        // ref.as_non_null を通しても、型は戻らない
        val asNonNull = callRefModule(
            """
            (func (export "run") (result i32)
              (ref.func ${'$'}seven)
              (drop (call ${'$'}wait))
              (ref.as_non_null)
              (call_ref ${'$'}v_l)
              i32.wrap_i64)
            """.trimIndent(),
        )
        EndiveRuntime().load(asNonNull)
        assertRejected(asNonNull, "call_ref で呼ぶこと")
    }

    @Test
    fun `関数の参照を持ったまま待ってからreturn_call_refで呼ぶ形も、止まりうる型でなくても断る`() {
        // i32.const 1; ref.func $id; 待ち点; return_call_ref $i_i。参照が funcref の一時ローカルを通るので、call_ref と同じく出力が検証に通らない
        val module = { body: String ->
            WitTool.wat(
                """
                (module
                  (type ${'$'}v_i (func (result i32)))
                  (type ${'$'}i_i (func (param i32) (result i32)))
                  (import "w" "wait" (func ${'$'}wait (type ${'$'}v_i)))
                  (elem declare func ${'$'}id)
                  (func ${'$'}id (type ${'$'}i_i) (local.get 0))
                  $body)
                """.trimIndent(),
            )
        }
        val crossing = module(
            """
            (func (export "run") (result i32)
              (i32.const 1)
              (ref.func ${'$'}id)
              (drop (call ${'$'}wait))
              (return_call_ref ${'$'}i_i))
            """.trimIndent(),
        )
        EndiveRuntime().load(crossing)
        assertRejected(crossing, "止まりうる関数 #2（export run） の中", "return_call_ref で呼ぶこと")
        // 待ち点をまたがなければ、今までどおり変換する
        val direct = module(
            """
            (func (export "run") (result i32)
              (drop (call ${'$'}wait))
              (i32.const 1)
              (ref.func ${'$'}id)
              (return_call_ref ${'$'}i_i))
            """.trimIndent(),
        )
        for (debug in AsyncifyDebug.entries) assertEquals(1, Asyncify.transform(direct, setOf("w.wait"), debug = debug).instrumented, "$debug")
    }

    @Test
    fun `到達できないところの止まりうるcall_refも、デバッグの切り替えやローカルの数によらず断る`() {
        // return の後ろの call_ref。引数のある形・ない形（保存するローカルの数が 0 だと解析を飛ばす）と、すべての切り替えで同じ結果にする
        for (params in listOf("", "(param i32)")) {
            val wasm = callRefModule(
                """
                (func (export "run") $params (result i32)
                  (call ${'$'}wait)
                  (return)
                  (call_ref ${'$'}v_i (ref.func ${'$'}wait)))
                """.trimIndent(),
            )
            EndiveRuntime().load(wasm)
            assertRejected(wasm, "止まりうる関数 #2（export run） の中", "止まりうる call_ref")
        }
        // 構造の中の unreachable の後ろも同じ
        val inBlock = callRefModule(
            """
            (func (export "run") (result i32)
              (block (result i32)
                (call ${'$'}wait)
                (br 0)
                (call_ref ${'$'}v_i (ref.func ${'$'}wait))))
            """.trimIndent(),
        )
        EndiveRuntime().load(inBlock)
        assertRejected(inBlock, "止まりうる call_ref")
    }

    @Test
    fun `関数の参照を持ったまま待ってから型の番号の型付きselectに渡す形も、断る`() {
        // ref.func ×2; 待ち点; select (result (ref null $v_l)); call_ref。参照が funcref の一時ローカルを通るので、select の入力が型の合わない出力になる
        val crossing = callRefModule(
            """
            (func (export "run") (result i32)
              (ref.func ${'$'}seven)
              (ref.func ${'$'}seven)
              (call ${'$'}wait)
              (select (result (ref null ${'$'}v_l)))
              (call_ref ${'$'}v_l)
              i32.wrap_i64)
            """.trimIndent(),
        )
        EndiveRuntime().load(crossing)
        assertRejected(crossing, "止まりうる関数 #2（export run） の中", "型付きの select")
        // 待ち点をまたがなければ、今までどおり変換する
        val direct = callRefModule(
            """
            (func (export "run") (result i32)
              (drop (call ${'$'}wait))
              (ref.func ${'$'}seven)
              (ref.func ${'$'}seven)
              (i32.const 1)
              (select (result (ref null ${'$'}v_l)))
              (call_ref ${'$'}v_l)
              i32.wrap_i64)
            """.trimIndent(),
        )
        for (debug in AsyncifyDebug.entries) assertEquals(1, Asyncify.transform(direct, setOf("w.wait"), debug = debug).instrumented, "$debug")
        // 結果が funcref の select は、funcref の一時ローカルのままで検証に通るので、今までどおり変換する
        val funcrefSelect = callRefModule(
            """
            (func (export "run") (result i32)
              (ref.func ${'$'}seven)
              (ref.func ${'$'}seven)
              (call ${'$'}wait)
              (select (result funcref))
              (drop)
              (i32.const 1))
            """.trimIndent(),
        )
        assertTrue(EndiveRuntime().load(Asyncify.transform(funcrefSelect, setOf("w.wait")).bytes).isAsyncified)
    }

    @Test
    fun `待ち点をまたがない止まりうる型でないcall_refは、今までどおり変換して巻き戻せる`() {
        // wait() + i32.wrap(call_ref $v_l (ref.func $seven))。待ち点の後で ref.func を積むので、参照は一時ローカルを通らない
        val wasm = callRefModule(
            """
            (func (export "run") (result i32)
              (call ${'$'}wait)
              (i32.wrap_i64 (call_ref ${'$'}v_l (ref.func ${'$'}seven)))
              i32.add)
            """.trimIndent(),
        )
        assertTrue(EndiveRuntime().load(Asyncify.transform(wasm, setOf("w.wait")).bytes).isAsyncified)
        for (debug in AsyncifyDebug.entries) assertEquals(48L, runWithWait(wasm, debug), "$debug")
    }

    @Test
    fun `関数の参照を持ったまま待ってからcall_ref以外に渡す形は、今までどおり変換する`() {
        // i32.const 0; ref.func; 待ち点; table.set。funcref の一時ローカルのままで検証に通る（巻き戻せば、null でない参照で trap する）
        val wasm = callRefModule(
            """
            (func (export "run") (result i32)
              (i32.const 0)
              (ref.func ${'$'}seven)
              (drop (call ${'$'}wait))
              (table.set 0)
              (i32.const 1))
            """.trimIndent(),
        )
        val r = Asyncify.transform(wasm, setOf("w.wait"))
        assertEquals(1, r.instrumented)
        assertTrue(EndiveRuntime().load(r.bytes).isAsyncified)
    }

    /**
     * lua.wasm にない形の待ち点を並べた `run() -> i32`（一時ローカルの選別 [Asyncify] の `savedTemps` を、[AsyncifyDebug.POISON_UNSAVED] で確かめる）:
     * - 待ち点の前にスタックに残る値（keep）
     * - 引数を持つ block・loop（先頭への分岐で引数を渡す）・if・try_table（#425 の専用の一時ローカル）
     * - 引数のある止まりうる import（`w.waitv`。巻き直しで引数を渡してやり直す）
     * - 引数のある定義済みの関数への直接の call（引数は呼ばれた側が戻す）・call_indirect
     * - 外から止まりうるとみなす型（`externalSuspendTypes`。#757）の、export したテーブルへの call_indirect（`w.ext`。この型の止まりうる関数はモジュールの中にない）
     *
     * 止まりうる `call_ref` は、変換で断る（参照は待ち点をまたいで退避できない。#923）ので、ここには入れない（上の call_ref のテスト）
     */
    private val shapes by lazy {
        WitTool.wat(
            """
            (module
              (type ${'$'}v_i (func (result i32)))
              (type ${'$'}iii_i (func (param i32 i32 i32) (result i32)))
              (type ${'$'}l_l (func (param i64) (result i64)))
              (import "w" "wait" (func ${'$'}wait (type ${'$'}v_i)))
              (import "w" "waitv" (func ${'$'}waitv (param i32 i32) (result i32)))
              (import "w" "ext" (func ${'$'}ext (type ${'$'}l_l)))
              (table (export "t") 3 funcref)
              (elem (i32.const 0) ${'$'}mix ${'$'}wait ${'$'}ext)
              (func ${'$'}mix (type ${'$'}iii_i)
                (call ${'$'}wait)
                (i32.mul (local.get 0) (i32.const 100))
                i32.add
                (i32.mul (local.get 1) (i32.const 10))
                i32.add
                (local.get 2)
                i32.add)
              (func (export "run") (result i32)
                (local ${'$'}c i32)
                i32.const 1000
                ;; 引数 2 つの block
                i32.const 3
                i32.const 4
                (block (param i32 i32) (result i32)
                  call ${'$'}wait
                  i32.add
                  i32.mul)
                i32.add
                ;; 引数 2 つの loop
                i32.const 3
                (loop ${'$'}l (param i32 i32) (result i32)
                  local.set ${'$'}c
                  call ${'$'}wait
                  i32.add
                  local.get ${'$'}c
                  i32.const 1
                  i32.sub
                  local.tee ${'$'}c
                  local.get ${'$'}c
                  br_if ${'$'}l
                  drop)
                ;; 引数 1 つの if
                i32.const 5
                i32.const 1
                (if (param i32) (result i32)
                  (then call ${'$'}wait i32.mul)
                  (else))
                i32.add
                ;; 引数 1 つの try_table
                i32.const 9
                (try_table (param i32) (result i32)
                  call ${'$'}wait
                  i32.sub)
                i32.add
                ;; 引数のある止まりうる import
                i32.const 7
                i32.const 8
                call ${'$'}waitv
                i32.add
                ;; 定義済みの関数への直接の call
                i32.const 1
                i32.const 2
                i32.const 3
                call ${'$'}mix
                i32.add
                ;; call_indirect（表の 0 は mix、1 は wait、2 は ext）
                i32.const 4
                i32.const 5
                i32.const 6
                i32.const 0
                call_indirect (type ${'$'}iii_i)
                i32.add
                i32.const 1
                call_indirect (type ${'$'}v_i)
                i32.add
                ;; 外の型の call_indirect
                i64.const 21
                i32.const 2
                call_indirect (type ${'$'}l_l)
                i32.wrap_i64
                i32.add))
            """.trimIndent(),
        )
    }

    private val extTypes = setOf(Asyncify.Signature(listOf(0x7E), listOf(0x7E)))

    /** 止まった数（import ごと）と燃料切れで巻き戻した数 */
    private class ShapeCounts {
        val unwinds = HashMap<String, Int>()
        var fuelUnwinds = 0
        var fuelBlocking = 0
    }

    /**
     * [shapes] の `run` を動かす。import は呼ばれるたびに 1, 2, 3, … を使って値を返す（`waitv(a, b)` は `a * 10 + b + n`、`ext(x)` は `x * 2 + n`）。
     * [suspend] なら、毎回いったん巻き戻してから、巻き直しで着いたときに返す（巻き直しで渡された引数で値を作る）。[fuel] があれば、燃料切れでも巻き戻す
     */
    private fun driveShapes(module: WasmModule, suspend: Boolean, fuel: Long? = null): Pair<Long, ShapeCounts> {
        val counts = ShapeCounts()
        val stack = Stack()
        var next = 0L
        fun host(name: String, value: (LongArray, Long) -> Long) = WasmHostFunction { instance, args ->
            // 止めないとき（変換していないモジュールも）は、巻き直しで import に着くことはない（燃料切れの呼び出しだけで止まる）
            if (!suspend) {
                longArrayOf(value(args, ++next))
            } else if (instance.asyncState == WasmAsyncState.REWINDING) {
                assertTrue(stack.values.isEmpty(), "巻き直しで着いたのに、退避した値が残っている")
                instance.asyncState = WasmAsyncState.NORMAL
                longArrayOf(value(args, ++next))
            } else {
                counts.unwinds.merge(name, 1, Int::plus)
                instance.asyncState = WasmAsyncState.UNWINDING
                longArrayOf(0)
            }
        }
        val i32 = WasmValueType.I32
        val i64 = WasmValueType.I64
        val builder = WasmInstanceConfig.builder()
            .hostFunction("w", "wait", WasmFunctionType.of(emptyList(), listOf(i32)), host("wait") { _, n -> n })
            .hostFunction("w", "waitv", WasmFunctionType.of(listOf(i32, i32), listOf(i32)), host("waitv") { a, n -> a[0] * 10 + a[1] + n })
            .hostFunction("w", "ext", WasmFunctionType.of(listOf(i64), listOf(i64)), host("ext") { a, n -> a[0] * 2 + n })
        if (module.isAsyncified) builder.asyncStack(stack)
        if (fuel != null) {
            builder.fuel(
                fuel,
                object : WasmFuelHandler {
                    override fun onFuelExhausted(instance: WasmInstance, remaining: Long): Long {
                        counts.fuelBlocking++
                        return fuel
                    }

                    override fun onFuelExhaustedUnwindable(instance: WasmInstance, remaining: Long): Long {
                        if (instance.asyncState == WasmAsyncState.REWINDING) {
                            instance.asyncState = WasmAsyncState.NORMAL
                            return instance.fuel
                        }
                        counts.fuelUnwinds++
                        instance.asyncState = WasmAsyncState.UNWINDING
                        return remaining
                    }
                },
            )
        }
        return module.instantiate(builder.build()).use { instance ->
            var result = instance.call("run")
            var rounds = 0
            while (module.isAsyncified && instance.asyncState == WasmAsyncState.UNWINDING) {
                assertTrue(++rounds < 100_000, "終わらない")
                instance.asyncState = WasmAsyncState.REWINDING
                if (fuel != null) instance.fuel = fuel
                result = instance.call("run")
            }
            assertTrue(stack.values.isEmpty())
            result[0] to counts
        }
    }

    @Test
    fun `引数を持つ構造と呼び出しの待ち点でも、戻さない一時ローカルにごみを入れて結果が同じ`() {
        val expected = driveShapes(EndiveRuntime(compile = false).load(shapes), suspend = false).first
        for (debug in AsyncifyDebug.entries) {
            val r = Asyncify.prepare(shapes).transform(setOf("w.wait", "w.waitv"), extTypes, debug = debug)
            assertNull(WitTool.validate(r.bytes), "$debug")
            if (debug != AsyncifyDebug.SAVE_ALL) assertTrue(r.savedLocals < r.allLocals, "$debug: ${r.savedLocals} / ${r.allLocals}")
            val (got, counts) = driveShapes(EndiveRuntime(compile = false).load(r.bytes), suspend = true)
            assertEquals(expected, got, "$debug")
            assertEquals(setOf("wait", "waitv", "ext"), counts.unwinds.keys, "$debug")
        }
    }

    @Test
    fun `引数を持つ構造と呼び出しの待ち点で燃料切れでも巻き戻し、戻さない一時ローカルにごみを入れて結果が同じ`() {
        val expected = driveShapes(EndiveRuntime(compile = false).load(shapes), suspend = false).first
        val metered = FuelInjector.inject(shapes)
        for (debug in AsyncifyDebug.entries) {
            val r = Asyncify.prepare(metered.bytes, originalFunc = metered::originalFunc)
                .transform(setOf("w.wait", "w.waitv"), extTypes, Instrumentation.YIELD_IMPORT, metered::originalFunc, debug)
            assertTrue(r.yieldSites > 0, "$debug")
            val module = EndiveRuntime(compile = false).load(r.bytes)
            for (suspend in listOf(false, true)) {
                val (got, counts) = driveShapes(module, suspend, fuel = 3)
                assertEquals(expected, got, "$debug, suspend = $suspend")
                assertTrue(counts.fuelUnwinds > 0, "$debug: 燃料切れで巻き戻していない")
                assertEquals(0, counts.fuelBlocking, "$debug: 燃料切れでスレッドを持って待った")
            }
        }
    }

    @Test
    fun `追えない分岐のbr_on_nullは、解析に届く前に変換を断る`() {
        // call wait; ref.null func; br_on_null 0; drop（ADR 0329。GC の br_on_* を持つ止まりうる関数は読み込めない）
        val wasm = runModule(bytes(0x10, 0x00, 0xD0, 0x70, 0xD5, 0x00, 0x1A))
        for (debug in AsyncifyDebug.entries) {
            assertThrows<AsyncifyUnsupported>("$debug") { Asyncify.transform(wasm, setOf("w.wait"), debug = debug) }
        }
    }

    /** ローカルが [locals] 個、空のブロックが [blocks] 組ある関数（解析の大きさは、ほぼ blocks × 2 × (locals / 64)） */
    private fun bigFunction(locals: Int, blocks: Int): ByteArray {
        val empty = bytes(0x02, 0x40, 0x0B)
        var code = bytes(0x41, 0x2A, 0x21, 0x00, 0x10, 0x00, 0x1A)
        repeat(blocks) { code += empty }
        return runModule(code + bytes(0x20, 0x00), locals = locals)
    }

    @Test
    fun `解析の上限を超えた関数は、すべて退避する今までの形で変換され、巻き戻して正しく動く`() {
        // ローカル 20,000 個 = 313 語。上限（2^20 語）は約 3,350 ブロック
        val small = bigFunction(locals = 20_000, blocks = 100)
        val over = bigFunction(locals = 20_000, blocks = 3_000)
        val live = Asyncify.transform(small, setOf("w.wait"))
        val all = Asyncify.transform(small, setOf("w.wait"), debug = AsyncifyDebug.SAVE_ALL)
        assertEquals(1, live.maxLocals)
        assertTrue(all.maxLocals >= 20_000, "${all.maxLocals}")

        val overLive = Asyncify.transform(over, setOf("w.wait"))
        val overAll = Asyncify.transform(over, setOf("w.wait"), debug = AsyncifyDebug.SAVE_ALL)
        assertEquals(overAll.maxLocals, overLive.maxLocals)
        assertEquals(overAll.savedLocals, overLive.savedLocals)
        // 切り替えは入力だけで決まり、出力は決定的で、すべて退避する形と同じバイト列になる
        assertArrayEquals(overAll.bytes, overLive.bytes)
        assertArrayEquals(overLive.bytes, Asyncify.transform(over, setOf("w.wait")).bytes)
        for (debug in AsyncifyDebug.entries) assertEquals(42L, runWithWait(over, debug), "$debug")
        for (debug in AsyncifyDebug.entries) assertEquals(42L, runWithWait(small, debug), "$debug")
    }

    @Test
    fun `深く入れ子にしたtry_tableの中に呼び出しが多くても、解析の確保は命令の数に比例する`() {
        // try_table (catch_all) を depth 段入れ子にして、その中に空の関数への call を calls 個並べる
        // （命令ごとに囲むすべての catch 先の配列を作る形だと、確保が depth × calls になる）
        val depth = 1_000
        val calls = 100_000
        val types = vec(listOf(bytes(0x60, 0x00, 0x01, 0x7F), bytes(0x60, 0x00, 0x00)))
        var code = bytes(0x41, 0x2A, 0x21, 0x00, 0x10, 0x00, 0x1A, 0x02, 0x40)
        repeat(depth) { code += bytes(0x1F, 0x40, 0x01, 0x02, 0x00) }
        repeat(calls) { code += bytes(0x10, 0x02) }
        repeat(depth) { code += bytes(0x0B) }
        code += bytes(0x0B, 0x20, 0x00)
        val run = bytes(0x01, 0x01, 0x7F) + code + bytes(0x0B)
        val nop = bytes(0x00, 0x0B)
        val wasm = module(
            1 to types,
            2 to vec(listOf(name("w") + name("wait") + bytes(0x00, 0x00))),
            3 to vec(listOf(bytes(0x00), bytes(0x01))),
            7 to vec(listOf(name("run") + bytes(0x00, 0x01))),
            10 to vec(listOf(WasmBinary.uleb(run.size.toLong()) + run, WasmBinary.uleb(nop.size.toLong()) + nop)),
        )
        val threads = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        fun allocated(debug: AsyncifyDebug): Long {
            val before = threads.getThreadAllocatedBytes(Thread.currentThread().id)
            Asyncify.transform(wasm, setOf("w.wait"), debug = debug)
            return threads.getThreadAllocatedBytes(Thread.currentThread().id) - before
        }
        val all = allocated(AsyncifyDebug.SAVE_ALL)
        val live = allocated(AsyncifyDebug.NONE)
        // 解析の分の確保は、すべて退避する形よりわずかしか増えない（命令ごとに全部の catch 先の配列を作ると、約 400 MB 増える）
        assertTrue(live < all + 96L * 1024 * 1024, "SAVE_ALL $all, 生存解析 $live")
        assertEquals(42L, runWithWait(wasm, AsyncifyDebug.NONE))
    }
}
