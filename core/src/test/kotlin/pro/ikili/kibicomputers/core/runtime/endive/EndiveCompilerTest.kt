package pro.ikili.kibicomputers.core.runtime.endive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
    fun `燃料の計測をかけたlua_wasmはコンパイルし、待機点への変換もかけるとコンパイルできずインタプリタで動かす`() {
        val lua = resource("lua.wasm")
        assertTrue(isCompiled(EndiveRuntime().load(FuelInjector.inject(lua).bytes)))
        // 両方かけると luaV_execute が JVM のメソッドの上限を超え（ASM が落ちる）、例外処理を使うモジュールなので
        // 関数ごとに混ぜず、全体をインタプリタで動かす（#37 で見つけた）
        val module = EndiveRuntime().load(FuelInjector.inject(Asyncify.transform(lua, WasiP1.WAIT_IMPORTS).bytes).bytes)
        assertFalse(isCompiled(module))
        assertTrue(module.isAsyncified && module.isFuelMetered)
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
