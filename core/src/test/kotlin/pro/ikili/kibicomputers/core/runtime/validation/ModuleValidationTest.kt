package pro.ikili.kibicomputers.core.runtime.validation

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.runtime.TestModules
import pro.ikili.kibicomputers.core.runtime.WasmBinary
import pro.ikili.kibicomputers.core.runtime.WasmBinary.Companion.I32
import pro.ikili.kibicomputers.core.runtime.WasmBinary.Companion.code
import pro.ikili.kibicomputers.core.runtime.WasmBinary.Companion.uleb
import pro.ikili.kibicomputers.core.runtime.WasmException
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.io.ByteArrayOutputStream
import java.util.Random

/** サーバー側での WASM モジュールの検証（#40）: 構造の上限、壊れたモジュール、時間の上限 */
class ModuleValidationTest {
    private val runtime = EndiveRuntime()

    private val lua: ByteArray by lazy { resource("lua.wasm") }

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/$name")) { "$name がリソースにない" }.use { it.readBytes() }

    /** ヘッダーと、(id, 中身) のセクションを並べたモジュール */
    private fun module(vararg sections: Pair<Int, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00))
        for ((id, body) in sections) {
            out.write(id)
            out.write(uleb(body.size.toLong()))
            out.write(body)
        }
        return out.toByteArray()
    }

    private fun bytes(vararg parts: Any): ByteArray = code(*parts)

    /** `() -> ()` の型が1つ */
    private val voidType = 1 to bytes(1, 0x60, 0, 0)

    private fun rejects(wasm: ByteArray, limits: ModuleLimits = ModuleLimits.DEFAULT): WasmLoadException =
        assertThrows<WasmLoadException> { ModulePrescan.check(wasm, limits, null) }

    @Test
    fun `普通のモジュールとlua_wasmは通る`() {
        for (wasm in listOf(TestModules.ADD, TestModules.HOST, TestModules.MEMORY, TestModules.TRAPS, TestModules.START, lua)) {
            assertDoesNotThrow { ModulePrescan.check(wasm, ModuleLimits.DEFAULT, null) }
        }
        assertDoesNotThrow { Program.loadMetered(runtime, lua, ModuleLimits.DEFAULT) }
        assertDoesNotThrow { Program.load(runtime, TestModules.ADD, ModuleLimits.DEFAULT) }
    }

    @Test
    fun `大きすぎるモジュールは断る`() {
        val e = rejects(TestModules.ADD, ModuleLimits(maxBytes = TestModules.ADD.size - 1))
        assertTrue(e.message!!.contains("大きすぎる"), e.message)
    }

    @Test
    fun `ヘッダーが違うものは断る`() {
        rejects(TestModules.MALFORMED)
        rejects(ByteArray(0))
        // Component Model のバイナリ（version 0x0d、layer 1）
        rejects(byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x0d, 0x00, 0x01, 0x00))
    }

    @Test
    fun `セクションの大きさが残りを超えるものと途中で終わるものは断る`() {
        rejects(module(voidType).copyOf(module(voidType).size - 1))
        rejects(bytes(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00, 1, 0xff, 0xff, 0xff, 0xff, 0x0f))
        rejects(bytes(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00, 1))
    }

    @Test
    fun `知らないセクションは断る`() {
        rejects(module(99 to ByteArray(0)))
    }

    @Test
    fun `数がセクションの大きさに合わないベクターは断る`() {
        // 型の数が 40 億なのに、中身は 4 バイト
        rejects(module(1 to bytes(0xff, 0xff, 0xff, 0xff, 0x0f, 0x60, 0, 0)))
        // 5 バイトを超える LEB128
        rejects(module(1 to bytes(0x80, 0x80, 0x80, 0x80, 0x80, 0x00)))
    }

    @Test
    fun `数の上限を超えるものは断る`() {
        val small = ModuleLimits(maxTypes = 1, maxImports = 1, maxFunctions = 1, maxExports = 1)
        val twoTypes = module(1 to bytes(2, 0x60, 0, 0, 0x60, 1, I32, 0))
        assertTrue(rejects(twoTypes, small).message!!.contains("型の数"))
        val wasm = WasmBinary().run {
            func(emptyList(), emptyList(), code())
            func(emptyList(), emptyList(), code())
            build()
        }
        assertTrue(rejects(wasm, small).message!!.contains("関数の数"))
        assertDoesNotThrow { ModulePrescan.check(wasm, ModuleLimits(maxFunctions = 2), null) }
    }

    @Test
    fun `ローカル変数を大量に宣言する関数は断る`() {
        // 本体: ローカルの宣言が1グループ（40 億個の i32）、end
        val body = bytes(1, 0xff, 0xff, 0xff, 0xff, 0x0f, I32, 0x0b)
        val wasm = module(voidType, 3 to bytes(1, 0), 10 to (bytes(1) + uleb(body.size.toLong()) + body))
        assertTrue(rejects(wasm).message!!.contains("ローカル変数が多すぎる"))
        // グループを分けても合計で数える
        val split = bytes(2, 0xa0, 0x9c, 0x01, I32, 0xa0, 0x9c, 0x01, I32, 0x0b) // 20,000 × 2 は通る
        assertDoesNotThrow { ModulePrescan.check(module(voidType, 3 to bytes(1, 0), 10 to (bytes(1) + uleb(split.size.toLong()) + split)), ModuleLimits.DEFAULT, null) }
        assertTrue(rejects(module(voidType, 3 to bytes(1, 0), 10 to (bytes(1) + uleb(split.size.toLong()) + split)), ModuleLimits(maxLocalsPerFunction = 39_999)).message!!.contains("ローカル変数"))
    }

    @Test
    fun `大きすぎる関数の本体は断る`() {
        val wasm = WasmBinary().run {
            func(emptyList(), emptyList(), ByteArray(100) { 0x01 }) // nop × 100
            build()
        }
        assertDoesNotThrow { ModulePrescan.check(wasm, ModuleLimits.DEFAULT, null) }
        assertTrue(rejects(wasm, ModuleLimits(maxFunctionBodyBytes = 50)).message!!.contains("本体が大きすぎる"))
    }

    @Test
    fun `最初の要素数が多すぎるテーブルは断る`() {
        val big = WasmBinary().table(200_000).build()
        assertTrue(rejects(big).message!!.contains("テーブルの最初の要素数"))
        assertDoesNotThrow { ModulePrescan.check(WasmBinary().table(10).build(), ModuleLimits.DEFAULT, null) }
        // テーブルの数
        rejects(module(4 to bytes(2, 0x70, 0, 1, 0x70, 0, 1)), ModuleLimits(maxTables = 1))
        // 初期値の式を持つテーブル
        rejects(module(4 to bytes(1, 0x40, 0x00, 0x70, 0, 1, 0xd0, 0x70, 0x0b)))
    }

    @Test
    fun `型の合わない関数の本体はランタイムの検証で断る`() {
        // () -> () の関数が i32 を残して終わる
        val wasm = WasmBinary().run {
            func(emptyList(), emptyList(), code(0x41, 1))
            build()
        }
        assertDoesNotThrow { ModulePrescan.check(wasm, ModuleLimits.DEFAULT, null) }
        assertThrows<WasmLoadException> { Program.load(runtime, wasm, ModuleLimits.DEFAULT) }
        assertThrows<WasmLoadException> { Program.loadMetered(runtime, wasm, ModuleLimits.DEFAULT) }
    }

    @Test
    fun `壊したモジュールは抽象の例外で断るか、読み込める`() {
        val random = Random(40)
        for (source in listOf(TestModules.MEMORY, TestModules.TRAPS, lua.copyOf(4096))) {
            repeat(300) {
                val wasm = source.copyOf()
                repeat(1 + random.nextInt(4)) { wasm[8 + random.nextInt(wasm.size - 8)] = random.nextInt(256).toByte() }
                val program = try {
                    Program.loadMetered(runtime, wasm, ModuleLimits.DEFAULT)
                } catch (e: WasmLoadException) {
                    return@repeat // 断った
                }
                // 読み込めたものは、インスタンスを作っても抽象の例外（import がない、など）にしかならない
                try {
                    program.module.instantiate(WasmInstanceConfig.builder().fuel(1_000_000, null).build()).close()
                } catch (e: WasmException) {
                    // 断った
                }
            }
        }
    }

    @Test
    fun `検証が時間の上限を超えたら断る`() {
        val e = assertThrows<WasmLoadException> { Program.loadMetered(runtime, lua, ModuleLimits(timeLimitNanos = 1)) }
        assertTrue(e.message!!.contains("時間の上限"), e.message)
        assertThrows<WasmLoadException> { Program.load(runtime, lua, ModuleLimits(timeLimitNanos = 1)) }
    }

    @Test
    fun `lua_wasmの検証は既定の時間の上限に十分収まる`() {
        Program.loadMetered(runtime, lua, ModuleLimits.DEFAULT) // 温める
        val deadline = LoadDeadline(Long.MAX_VALUE)
        Program.loadMetered(runtime, lua, ModuleLimits.DEFAULT)
        val ms = deadline.elapsedNanos / 1_000_000
        println("lua.wasm の検証: $ms ms（CPU 時間）")
        assertTrue(deadline.elapsedNanos < ModuleLimits.DEFAULT_TIME_LIMIT_NANOS / 2, "lua.wasm の検証が遅い: $ms ms")
    }
}
