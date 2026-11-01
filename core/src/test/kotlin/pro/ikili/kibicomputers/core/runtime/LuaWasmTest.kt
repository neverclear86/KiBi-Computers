package pro.ikili.kibicomputers.core.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import run.endive.runtime.ImportValues
import run.endive.runtime.Instance
import run.endive.wasi.WasiExitException
import run.endive.wasi.WasiOptions
import run.endive.wasi.WasiPreview1
import run.endive.wasm.Parser
import run.endive.wasm.WasmModule
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** core に同梱する lua.wasm（Lua 5.4 を zig cc で WASI p1 向けにビルドしたもの、#91）を Endive で動かす。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LuaWasmTest {
    private lateinit var module: WasmModule

    private class Result(val exitCode: Int, val stdout: String, val stderr: String)

    @BeforeAll
    fun load() {
        val bytes = javaClass.getResourceAsStream("/kibicomputers/wasm/lua.wasm")?.use { it.readBytes() }
        assertNotNull(bytes, "lua.wasm が core のリソースにない")
        module = Parser.parse(bytes!!)
    }

    /** `lua <args>` を実行する。 */
    private fun lua(vararg args: String, stdin: String = ""): Result {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val options = WasiOptions.builder()
            .withArguments(listOf("lua", *args))
            .withStdin(ByteArrayInputStream(stdin.toByteArray()))
            .withStdout(stdout)
            .withStderr(stderr)
            .build()
        val exitCode = WasiPreview1.builder().withOptions(options).build().use { wasi ->
            val imports = ImportValues.builder().addFunction(*wasi.toHostFunctions()).build()
            try {
                Instance.builder(module).withImportValues(imports).build()
                0
            } catch (e: WasiExitException) {
                e.exitCode()
            }
        }
        return Result(exitCode, stdout.toString(Charsets.UTF_8), stderr.toString(Charsets.UTF_8))
    }

    @Test
    fun `Luaのバージョンが5_4`() {
        val r = lua("-e", "print(_VERSION)")
        assertEquals(0, r.exitCode, r.stderr)
        assertEquals("Lua 5.4\n", r.stdout)
    }

    @Test
    fun `式と標準ライブラリを実行できる`() {
        val script = """
            local t = {}
            for i = 1, 10 do t[#t + 1] = i * i end
            print(table.concat(t, ","))
            print(string.format("%5.2f|%d|%s", math.pi, 1 << 40, ("abc"):upper()))
            print(utf8.char(0x3042):len(), coroutine.wrap(function() coroutine.yield(42) end)())
        """.trimIndent()
        val r = lua("-e", script)
        assertEquals(0, r.exitCode, r.stderr)
        assertEquals("1,4,9,16,25,36,49,64,81,100\n 3.14|1099511627776|ABC\n3\t42\n", r.stdout)
    }

    @Test
    fun `エラーをpcallで捕まえられる`() {
        // Lua のエラーは longjmp（WASM の例外処理）で戻る
        val r = lua("-e", "print(pcall(error, 'boom')); print(pcall(function() return nil + 1 end))")
        assertEquals(0, r.exitCode, r.stderr)
        val lines = r.stdout.lines()
        assertEquals("false\tboom", lines[0])
        assertTrue(lines[1].startsWith("false\t") && "arithmetic" in lines[1], r.stdout)
    }

    @Test
    fun `Cのスタックを深く使う処理は、小さいスタックでもLuaのエラーになり、落ちない`() {
        // C のスタックは 256 KiB（#334）。LUAI_MAXCCALLS（200）の深さまで、C のスタックが足りること
        val script = """
            local function nest(n) if n == 0 then return 0 end return select(2, pcall(nest, n - 1)) + 1 end
            print(nest(150))
            local function deep() return pcall(deep) end
            print(pcall(deep))
            local src = string.rep("(", 180) .. "1" .. string.rep(")", 180)
            print(load("return " .. src)())
            print(pcall(load, "return " .. string.rep("(", 1000) .. "1" .. string.rep(")", 1000)))
            local s = string.rep("a", 150)
            print(#s:gsub("a", function(c) return (c:gsub("a", function(d) return d .. d end)) end))
            print(select(2, pcall(string.rep, "x", 100, string.rep(",", 10))):len())
        """.trimIndent()
        val r = lua("-e", script)
        assertEquals(0, r.exitCode, r.stderr)
        val lines = r.stdout.lines()
        assertEquals("150", lines[0], r.stdout)
        assertTrue("stack overflow" in lines[1], r.stdout)
        assertEquals("1", lines[2], r.stdout)
        assertTrue(lines[3].startsWith("true	nil	") || lines[3].startsWith("false	"), r.stdout)
        assertEquals("300", lines[4], r.stdout)
    }

    @Test
    fun `捕まえないエラーはstderrに出て終了コード1になる`() {
        val r = lua("-e", "error('uncaught')")
        assertEquals(1, r.exitCode)
        assertTrue("uncaught" in r.stderr, r.stderr)
    }

    @Test
    fun `標準入力のスクリプトを実行できる`() {
        val r = lua("-", stdin = "for i = 1, 3 do io.write(i, ' ') end\nprint(#arg)\n")
        assertEquals(0, r.exitCode, r.stderr)
        assertEquals("1 2 3 0\n", r.stdout)
    }

    @Test
    fun `標準入力を読める`() {
        val r = lua("-e", "local x = io.read('n'); print(x * 2, io.read('l'), io.read('l'))", stdin = "21\nhello\n")
        assertEquals(0, r.exitCode, r.stderr)
        assertEquals("42\t\thello\n", r.stdout)
    }

    @Test
    fun `osのexitで終了コードを返せる`() {
        assertEquals(3, lua("-e", "os.exit(3)").exitCode)
    }

    @Test
    fun `WASIで使えない関数は失敗を返す`() {
        val script = """
            print(os.execute())
            print(os.execute("ls"))
            print(pcall(io.popen, "ls"))
            print(pcall(os.tmpname))
            print(io.tmpfile())
            print(type(os.clock()), type(os.time()))
        """.trimIndent()
        val r = lua("-e", script)
        assertEquals(0, r.exitCode, r.stderr)
        val lines = r.stdout.lines()
        assertEquals("false", lines[0])
        assertTrue(lines[1].startsWith("nil\t") || lines[1].startsWith("fail"), lines[1])
        assertTrue(lines[2].startsWith("false\t") && "popen" in lines[2], lines[2])
        assertTrue(lines[3].startsWith("false\t") && "unique filename" in lines[3], lines[3])
        assertTrue(lines[4].startsWith("nil\t"), lines[4])
        assertEquals("number\tnumber", lines[5])
    }
}
