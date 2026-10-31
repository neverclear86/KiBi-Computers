package pro.ikili.kibicomputers.core.runtime.endive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import pro.ikili.kibicomputers.core.runtime.TestWasiP1
import pro.ikili.kibicomputers.core.runtime.WasmExternKind
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmModule

/**
 * zig cc でビルドした実際のプログラムを、ランタイムの抽象（[EndiveRuntime]）を通して動かす（#35）。
 *
 * モジュールの読み込みとインスタンス化、ホストの関数（WASI p1 の最小の実装 [TestWasiP1]）、
 * 線形メモリの読み書き、export の関数の呼び出しを、手で組んだ小さなモジュールでなく実際のプログラムで確かめる。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EndiveProgramTest {
    private val runtime = EndiveRuntime()

    private fun load(name: String): WasmModule {
        val bytes = javaClass.getResourceAsStream("/kibicomputers/wasm/$name.wasm")?.use { it.readBytes() }
        assertNotNull(bytes, "$name.wasm がリソースにない")
        return runtime.load(bytes!!)
    }

    private val lua: WasmModule by lazy { load("lua") }

    private class Result(val exitCode: Int, val stdout: String, val stderr: String)

    private fun lua(vararg args: String, stdin: String = ""): Result {
        val wasi = TestWasiP1(listOf("lua", *args), stdin)
        val code = wasi.run(lua)
        return Result(code, wasi.stdout.toString(Charsets.UTF_8), wasi.stderr.toString(Charsets.UTF_8))
    }

    @Test
    fun `importのないプログラムのexportを呼べる`() {
        val fib = load("fib")
        assertTrue(fib.exports.any { it.name == "fib" && it.kind == WasmExternKind.FUNCTION }, fib.exports.toString())
        fib.instantiate(WasmInstanceConfig.EMPTY).use { instance ->
            assertEquals(55L, instance.call("fib", 10)[0])
            assertEquals(6765L, instance.call("fib", 20)[0])
        }
    }

    @Test
    fun `wasi-libcのプログラムがホストの関数で標準出力に書ける`() {
        val module = load("hello-wasi")
        assertTrue(module.imports.all { it.module == TestWasiP1.MODULE }, module.imports.toString())
        val wasi = TestWasiP1(listOf("hello"))
        assertEquals(0, wasi.run(module))
        assertEquals("Hello from WASI\n", wasi.stdout.toString(Charsets.UTF_8))
    }

    @Test
    fun `Luaを動かせる`() {
        val r = lua("-e", "local t = {} for i = 1, 5 do t[#t + 1] = i * i end print(_VERSION, table.concat(t, ','))")
        assertEquals(0, r.exitCode, r.stderr)
        assertEquals("Lua 5.4\t1,4,9,16,25\n", r.stdout)
    }

    @Test
    fun `LuaのエラーをWASMの例外処理で捕まえられる`() {
        // pcall は setjmp/longjmp（exnref）で戻る。ホストの関数を包む例外と混ざらないことを確かめる
        val r = lua("-e", "print(pcall(error, 'boom'))")
        assertEquals(0, r.exitCode, r.stderr)
        assertEquals("false\tboom\n", r.stdout)
    }

    @Test
    fun `proc_exitのホストの例外で終了コードが分かる`() {
        assertEquals(3, lua("-e", "os.exit(3)").exitCode)
        val r = lua("-e", "error('uncaught')")
        assertEquals(1, r.exitCode)
        assertTrue("uncaught" in r.stderr, r.stderr)
    }

    @Test
    fun `標準入力をホストの関数で読める`() {
        val r = lua("-e", "print(io.read('n') * 2, io.read('l'), io.read('l'))", stdin = "21\nhello\n")
        assertEquals(0, r.exitCode, r.stderr)
        assertEquals("42\t\thello\n", r.stdout)
    }
}
