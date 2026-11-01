package pro.ikili.kibicomputers.core.runtime.endive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.runtime.Instrumentation
import pro.ikili.kibicomputers.core.runtime.TestWasiP1
import pro.ikili.kibicomputers.core.runtime.WasmAsyncStack
import pro.ikili.kibicomputers.core.runtime.WasmBinary
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.asyncify.Asyncify
import pro.ikili.kibicomputers.core.runtime.fuel.FuelInjector
import pro.ikili.kibicomputers.core.wasi.p1.WasiP1

/** ランタイムコンパイラで動かす（#37。ADR 0029 の B） */
class EndiveCompilerTest {
    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/$name")) { "$name がリソースにない" }.use { it.readBytes() }

    @Test
    fun `既定ではコンパイルしたコードで動き、インタプリタも選べる`() {
        val bytes = resource("fib.wasm")
        val compiled = EndiveRuntime().load(bytes)
        val interpreted = EndiveRuntime(compile = false).load(bytes)
        assertTrue(isCompiled(compiled))
        assertFalse(isCompiled(interpreted))
        val a = compiled.instantiate(WasmInstanceConfig.EMPTY).use { it.call("fib", 20)[0] }
        val b = interpreted.instantiate(WasmInstanceConfig.EMPTY).use { it.call("fib", 20)[0] }
        assertEquals(6765L, a)
        assertEquals(b, a)
    }

    @Test
    fun `コンパイルしても使う燃料はインタプリタと同じ`() {
        val bytes = FuelInjector.inject(resource("fuel.wasm")).bytes
        val config = WasmInstanceConfig.builder().fuel(1_000_000_000, null).build()
        fun used(compile: Boolean): Pair<Long, Long> = EndiveRuntime(compile).load(bytes).instantiate(config).use { instance ->
            val result = instance.call("fib", 18)[0]
            result to (config.initialFuel - instance.fuel)
        }
        val compiled = used(true)
        val interpreted = used(false)
        assertTrue(compiled.second > 0)
        assertEquals(interpreted, compiled)
    }

    @Test
    fun `燃料の計測と待機点への変換をかけたlua_wasmもコンパイルする`() {
        val lua = resource("lua.wasm")
        assertTrue(isCompiled(EndiveRuntime().load(FuelInjector.inject(lua).bytes)))
        // exec と同じ順（挿入 → 変換）なら、luaV_execute が JVM のメソッドの上限に収まる（#341）
        val module = EndiveRuntime().load(Instrumentation.apply(lua, WasiP1.WAIT_IMPORTS))
        assertTrue(isCompiled(module))
        assertTrue(module.isAsyncified && module.isFuelMetered)
    }

    @Test
    fun `逆の順（変換 → 挿入）だとlua_wasmはコンパイルできず、モジュール全体をインタプリタで動かす`() {
        // 変換で足した細かい if の分だけ燃料のセグメントが増え、luaV_execute が JVM のメソッドの上限を超える（ASM が落ちる）。
        // 例外処理を使うモジュールなので、関数ごとに混ぜず全体をインタプリタにする（#37 で見つけた）
        val lua = resource("lua.wasm")
        val module = EndiveRuntime().load(FuelInjector.inject(Asyncify.transform(lua, WasiP1.WAIT_IMPORTS).bytes).bytes)
        assertFalse(isCompiled(module))
        assertTrue(module.isAsyncified && module.isFuelMetered)
    }

    @Test
    fun `燃料の計測と待機点への変換をかけたlua_wasmを、コンパイルしたコードで動かせる`() {
        // コルーチン（例外処理を使う）と、止まりうる fd_write を通る。関数ごとにインタプリタと混ぜたときは 2 だけになった（#341）
        val lua = Instrumentation.apply(resource("lua.wasm"), WasiP1.WAIT_IMPORTS)
        val script = "local co = coroutine.wrap(function(a) local b = coroutine.yield(a + 1) return b * 2 end) print(co(1), co(5))"
        val compiled = runLua(EndiveRuntime(compile = true), lua, script)
        assertEquals("2\t10\n", compiled.first)
    }

    /** `lua -e <script>` を動かし、(標準出力, 使った燃料) を返す。止まりうる import で巻き戻すことはしない */
    private fun runLua(runtime: EndiveRuntime, bytes: ByteArray, script: String): Pair<String, Long> {
        val module = runtime.load(bytes)
        val wasi = TestWasiP1(listOf("lua", "-e", script))
        val initial = 1_000_000_000L
        val config = wasi.config(module) {
            fuel(initial, null)
            asyncStack(
                object : WasmAsyncStack {
                    override fun push(value: Long) = throw AssertionError("巻き戻さないので積まない")

                    override fun pop(): Long = throw AssertionError("巻き戻さないので取り出さない")
                },
            )
        }
        val used = module.instantiate(config).use { instance ->
            val code = try {
                instance.call("_start")
                0
            } catch (e: TestWasiP1.Exit) {
                e.code
            }
            assertEquals(0, code, wasi.stderr.toString(Charsets.UTF_8))
            initial - instance.fuel
        }
        assertTrue(used > 0)
        return wasi.stdout.toString(Charsets.UTF_8) to used
    }

    @Test
    fun `コンパイラが対応しない作りはインタプリタで動かす`() {
        // v128 のローカル変数を持つ関数（Endive のコンパイラは扱えない）
        val bytes = WasmBinary().run {
            exportFunc("seven", func(emptyList(), listOf(WasmBinary.I32), WasmBinary.code(0x41, 7), locals = listOf(0x7B)))
            build()
        }
        val module = EndiveRuntime().load(bytes)
        assertFalse(isCompiled(module))
        assertEquals(7L, module.instantiate(WasmInstanceConfig.EMPTY).use { it.call("seven")[0] })
    }

    @Test
    fun `同じコンパイル結果から別々のインスタンスを作れる`() {
        val module = EndiveRuntime().load(resource("fib.wasm"))
        val results = (0 until 4).map { module.instantiate(WasmInstanceConfig.EMPTY) }.map { instance -> instance.use { it.call("fib", 10)[0] } }
        assertEquals(List(4) { 55L }, results)
    }
}
