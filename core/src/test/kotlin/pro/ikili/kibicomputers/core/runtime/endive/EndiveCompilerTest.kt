package pro.ikili.kibicomputers.core.runtime.endive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.runtime.Instrumentation
import pro.ikili.kibicomputers.core.runtime.TestWasiP1
import pro.ikili.kibicomputers.core.runtime.WasmAsyncStack
import pro.ikili.kibicomputers.core.runtime.WasmBinary
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind
import pro.ikili.kibicomputers.core.runtime.asyncify.Asyncify
import pro.ikili.kibicomputers.core.runtime.fuel.FuelInjector
import pro.ikili.kibicomputers.core.wasi.p1.WasiP1
import run.endive.wasm.Parser

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
    fun `v128のローカル変数を持つ関数だけをインタプリタで動かし、ほかはコンパイルする`() {
        // v128 のローカル変数を持つ関数（#0。Endive のコンパイラは扱えない）と、持たない関数（#1）
        val bytes = WasmBinary().run {
            exportFunc("seven", func(emptyList(), listOf(WasmBinary.I32), WasmBinary.code(0x41, 7), locals = listOf(0x7B)))
            exportFunc("eight", func(emptyList(), listOf(WasmBinary.I32), WasmBinary.code(0x41, 8)))
            build()
        }
        val module = EndiveRuntime().load(bytes)
        assertTrue(isCompiled(module))
        assertEquals(setOf(0), interpretedFunctions(module))
        module.instantiate(WasmInstanceConfig.EMPTY).use {
            assertEquals(7L, it.call("seven")[0])
            assertEquals(8L, it.call("eight")[0])
        }
    }

    @Test
    fun `SIMDを含むモジュールもコンパイルし、SIMDを使う関数だけをインタプリタで動かす`() {
        val bytes = resource("simd.wasm")
        val module = EndiveRuntime().load(bytes)
        assertTrue(isCompiled(module))
        val interpreted = interpretedFunctions(module)
        val simd = EndiveRuntime.simdFunctions(Parser.parse(bytes))!!
        assertEquals(simd, interpreted)
        // sum・fill_seq・dot4 は SIMD を使い、fib は使わない
        assertTrue(simd.size >= 3, "$simd")
        assertFalse(exportIndex(bytes, "fib") in simd)
        assertTrue(exportIndex(bytes, "dot4") in simd)
        val reference = EndiveRuntime(compile = false).load(bytes)
        fun results(m: pro.ikili.kibicomputers.core.runtime.WasmModule) = m.instantiate(WasmInstanceConfig.EMPTY).use { i ->
            listOf(i.call("run", 1000)[0], i.call("sum", 777)[0], i.call("dot4", 5, 7)[0], i.call("fib", 15)[0])
        }
        val expected = listOf(1000L * 999 / 2 + (1000 + 1001 + 1002 + 1003) * 3 + 55, 777L * 776 / 2, (5L + 6 + 7 + 8) * 7, 610L)
        assertEquals(expected, results(reference))
        assertEquals(expected, results(module))
    }

    @Test
    fun `SIMDを使う関数をインタプリタで動かしても、使う燃料と燃料切れはインタプリタと同じ`() {
        val bytes = FuelInjector.inject(resource("simd.wasm")).bytes
        assertTrue(isCompiled(EndiveRuntime().load(bytes)))
        assertTrue(interpretedFunctions(EndiveRuntime().load(bytes)).isNotEmpty())
        fun used(compile: Boolean): Pair<Long, Long> {
            val config = WasmInstanceConfig.builder().fuel(1_000_000_000, null).build()
            return EndiveRuntime(compile).load(bytes).instantiate(config).use { instance ->
                val result = instance.call("run", 5000)[0]
                result to (config.initialFuel - instance.fuel)
            }
        }
        val compiled = used(true)
        assertTrue(compiled.second > 0)
        assertEquals(used(false), compiled)

        // 少ない燃料を足しながら動かす。燃料切れの回数（SIMD を使う関数の中でも起きる）と結果がインタプリタと同じ
        fun refills(compile: Boolean): Pair<Long, Int> {
            var count = 0
            val config = WasmInstanceConfig.builder().fuel(1_000) { _, remaining ->
                count++
                remaining + 1_000
            }.build()
            val result = EndiveRuntime(compile).load(bytes).instantiate(config).use { it.call("run", 5000)[0] }
            return result to count
        }
        val refilled = refills(true)
        assertTrue(refilled.second > 10, "$refilled")
        assertEquals(refills(false), refilled)

        // 燃料切れで止まる
        val config = WasmInstanceConfig.builder().fuel(1_000, null).build()
        val e = assertThrows<WasmTrapException> { EndiveRuntime().load(bytes).instantiate(config).use { it.call("sum", 5000) } }
        assertEquals(WasmTrapKind.FUEL_EXHAUSTED, e.kind)
    }

    @Test
    fun `燃料の計測と待機点への変換をかけたSIMDのモジュールもコンパイルする`() {
        // 止まりうる関数がなければ変換で断らない。SIMD を使う関数だけインタプリタで動かす
        val module = EndiveRuntime().load(Instrumentation.apply(resource("simd.wasm"), WasiP1.WAIT_IMPORTS))
        assertTrue(module.isAsyncified && module.isFuelMetered)
        assertTrue(isCompiled(module))
        assertTrue(interpretedFunctions(module).isNotEmpty())
        val config = WasmInstanceConfig.builder().fuel(1_000_000_000, null).asyncStack(
            object : WasmAsyncStack {
                override fun push(value: Long) = throw AssertionError("巻き戻さないので積まない")

                override fun pop(): Long = throw AssertionError("巻き戻さないので取り出さない")
            },
        ).build()
        assertEquals(1L * 100 * 99 / 2 + (100 + 101 + 102 + 103) * 3 + 55, module.instantiate(config).use { it.call("run", 100)[0] })
    }

    @Test
    fun `関数の型にv128があるときは、モジュール全体をインタプリタで動かす`() {
        // (v128) -> i32 の関数は、呼び出しの橋渡しをコンパイルできない
        val bytes = WasmBinary().run {
            val takesV128 = func(listOf(0x7B), listOf(WasmBinary.I32), WasmBinary.code(0x20, 0, 0xFD, 0x1B, 2))
            // v128.const（i32x4 で 0, 0, 9, 0）を渡す
            val v128 = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 9, 0, 0, 0, 0, 0, 0, 0)
            exportFunc("nine", func(emptyList(), listOf(WasmBinary.I32), WasmBinary.code(0xFD, 0x0C, v128, 0x10, takesV128)))
            build()
        }
        assertEquals(null, EndiveRuntime.simdFunctions(Parser.parse(bytes)))
        val module = EndiveRuntime().load(bytes)
        assertFalse(isCompiled(module))
        assertEquals(9L, module.instantiate(WasmInstanceConfig.EMPTY).use { it.call("nine")[0] })
    }

    /** export された関数の番号（import を含めた番号） */
    private fun exportIndex(bytes: ByteArray, name: String): Int {
        val exports = Parser.parse(bytes).exportSection()
        return (0 until exports.exportCount()).map(exports::getExport).first { it.name() == name }.index().toInt()
    }

    @Test
    fun `同じコンパイル結果から別々のインスタンスを作れる`() {
        val module = EndiveRuntime().load(resource("fib.wasm"))
        val results = (0 until 4).map { module.instantiate(WasmInstanceConfig.EMPTY) }.map { instance -> instance.use { it.call("fib", 10)[0] } }
        assertEquals(List(4) { 55L }, results)
    }
}
