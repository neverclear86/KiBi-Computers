package pro.ikili.kibicomputers.core.runtime.asyncify

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.runtime.Instrumentation
import pro.ikili.kibicomputers.core.runtime.WasmAsync
import pro.ikili.kibicomputers.core.runtime.WasmAsyncStack
import pro.ikili.kibicomputers.core.runtime.WasmAsyncState
import pro.ikili.kibicomputers.core.runtime.WasmBinary
import pro.ikili.kibicomputers.core.runtime.WasmFunctionType
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmLinkException
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.WasmValueType
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.wasi.p1.WasiP1

/** 読み込み時の待機点への変換（#329）と、変換したモジュールを Endive で動かす境界 */
class AsyncifyTest {
    private val i32 = 0x7F

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/$name")) { "$name がリソースにない" }.use { it.readBytes() }

    /** `w.wait() -> i32` を呼んで 1 を足す `run() -> i32` */
    private fun waitModule(): ByteArray {
        val b = WasmBinary()
        val wait = b.importFunc("w", "wait", emptyList(), listOf(i32))
        b.mutableI64Global(0)
        val run = b.func(emptyList(), listOf(i32), WasmBinary.code(0x10, wait, 0x41, 1, 0x6A))
        b.exportFunc("run", run)
        return b.build()
    }

    private class Stack : WasmAsyncStack {
        val values = ArrayList<Long>()

        override fun push(value: Long) {
            values += value
        }

        override fun pop(): Long = values.removeAt(values.size - 1)
    }

    @Test
    fun `lua_wasmとCのCLIは、止まりうる関数だけを変換し、Endiveで読み込める`() {
        for (name in listOf("lua.wasm", "prompt-wasi.wasm")) {
            val r = Asyncify.transform(resource(name), WasiP1.WAIT_IMPORTS)
            assertTrue(r.instrumented > 0 && r.instrumented < r.functions, "$name: ${r.instrumented} / ${r.functions}")
            assertTrue(r.sites > 0)
            val module = EndiveRuntime().load(Instrumentation.apply(resource(name), WasiP1.WAIT_IMPORTS))
            assertTrue(module.isAsyncified, name)
            assertTrue(module.isFuelMetered, name)
            assertTrue(module.imports.none { it.module == WasmAsync.IMPORT_MODULE }, "$name: ${module.imports}")
            assertTrue(module.exports.none { it.name == WasmAsync.STATE_EXPORT }, name)
            WasiP1.checkImports(module)
        }
    }

    @Test
    fun `巻き戻してexportから戻り、巻き直して同じ呼び出しをやり直す`() {
        val module = EndiveRuntime().load(Asyncify.transform(waitModule(), setOf("w.wait")).bytes)
        assertTrue(module.isAsyncified)
        assertFalse(module.isFuelMetered)
        assertEquals(listOf("w.wait"), module.imports.map { "${it.module}.${it.name}" })
        assertEquals(listOf("run"), module.exports.map { it.name })
        var calls = 0
        val stack = Stack()
        val config = WasmInstanceConfig.builder()
            .hostFunction("w", "wait", WasmFunctionType.of(emptyList(), listOf(WasmValueType.I32))) { instance, _ ->
                calls++
                if (instance.asyncState == WasmAsyncState.REWINDING) {
                    // 止まった呼び出しに着いた: 退避した値はすべて戻っている
                    assertTrue(stack.values.isEmpty())
                    instance.asyncState = WasmAsyncState.NORMAL
                    longArrayOf(41)
                } else {
                    instance.asyncState = WasmAsyncState.UNWINDING
                    longArrayOf(0)
                }
            }
            .asyncStack(stack)
            .build()
        module.instantiate(config).use { instance ->
            assertEquals(WasmAsyncState.NORMAL, instance.asyncState)
            instance.call("run")
            assertEquals(WasmAsyncState.UNWINDING, instance.asyncState)
            assertTrue(stack.values.isNotEmpty(), "退避した値がない")
            instance.asyncState = WasmAsyncState.REWINDING
            assertArrayEquals(longArrayOf(42), instance.call("run"))
            assertEquals(WasmAsyncState.NORMAL, instance.asyncState)
            assertEquals(2, calls)
        }
    }

    @Test
    fun `挿入してから変換すると、巻き戻して巻き直しても燃料は巻き戻さないときと同じだけ引く`() {
        // 燃料を引くコードは if (state == NORMAL) の中に入るので、巻き直しの間は引かない。変換が足したコードも数えない（#341）
        val module = EndiveRuntime().load(Instrumentation.apply(waitModule(), setOf("w.wait")))
        assertTrue(module.isAsyncified && module.isFuelMetered)
        val initial = 1_000_000L
        fun used(suspend: Boolean): Long {
            val stack = Stack()
            val config = WasmInstanceConfig.builder()
                .hostFunction("w", "wait", WasmFunctionType.of(emptyList(), listOf(WasmValueType.I32))) { instance, _ ->
                    if (!suspend || instance.asyncState == WasmAsyncState.REWINDING) {
                        instance.asyncState = WasmAsyncState.NORMAL
                        longArrayOf(41)
                    } else {
                        instance.asyncState = WasmAsyncState.UNWINDING
                        longArrayOf(0)
                    }
                }
                .asyncStack(stack)
                .fuel(initial, null)
                .build()
            return module.instantiate(config).use { instance ->
                instance.call("run")
                if (suspend) {
                    assertEquals(WasmAsyncState.UNWINDING, instance.asyncState)
                    instance.asyncState = WasmAsyncState.REWINDING
                }
                assertArrayEquals(longArrayOf(42), instance.call("run"))
                initial - instance.fuel
            }
        }
        val plain = used(false)
        assertTrue(plain > 0)
        // 巻き戻さないときは run を2回呼んでいるので、1回分は半分
        assertEquals(plain / 2, used(true))
    }

    @Test
    fun `変換したモジュールをasyncStackなしで作るとWasmLinkException`() {
        val module = EndiveRuntime().load(Asyncify.transform(waitModule(), setOf("w.wait")).bytes)
        val config = WasmInstanceConfig.builder()
            .hostFunction("w", "wait", WasmFunctionType.of(emptyList(), listOf(WasmValueType.I32))) { _, _ -> longArrayOf(0) }
            .build()
        assertThrows<WasmLinkException> { module.instantiate(config) }
    }

    @Test
    fun `変換していないモジュールのasyncStateはIllegalStateException`() {
        val module = EndiveRuntime().load(resource("fib.wasm"))
        assertFalse(module.isAsyncified)
        module.instantiate(WasmInstanceConfig.EMPTY).use { instance ->
            assertThrows<IllegalStateException> { instance.asyncState }
            assertThrows<IllegalStateException> { instance.asyncState = WasmAsyncState.NORMAL }
        }
    }

    @Test
    fun `予約した名前を使うモジュールと、変換済みのモジュールは読み込みを断る`() {
        val once = Asyncify.transform(waitModule(), setOf("w.wait")).bytes
        assertThrows<WasmLoadException> { Asyncify.transform(once, setOf("w.wait")) }

        val exportsState = WasmBinary().apply {
            mutableI64Global(0, exportName = WasmAsync.STATE_EXPORT)
            exportFunc("f", func(emptyList(), emptyList(), ByteArray(0)))
        }.build()
        assertThrows<WasmLoadException> { Asyncify.transform(exportsState, emptySet()) }

        val importsPush = WasmBinary().apply {
            importFunc(WasmAsync.IMPORT_MODULE, WasmAsync.PUSH, listOf(WasmBinary.I64), emptyList())
            mutableI64Global(0)
            exportFunc("f", func(emptyList(), emptyList(), ByteArray(0)))
        }.build()
        assertThrows<WasmLoadException> { Asyncify.transform(importsPush, emptySet()) }
        // 変換の名前の一部しかないモジュールは、ランタイムも断る
        assertThrows<WasmLoadException> { EndiveRuntime().load(importsPush) }

        assertThrows<WasmLoadException> { Asyncify.transform(byteArrayOf(0, 1, 2, 3), emptySet()) }
    }

    @Test
    fun `対応しない作りはAsyncifyUnsupported`() {
        // 止まりうる関数の中の v128 のローカル
        val simd = WasmBinary().apply {
            val wait = importFunc("w", "wait", emptyList(), listOf(i32))
            mutableI64Global(0)
            exportFunc("run", func(emptyList(), listOf(i32), WasmBinary.code(0x10, wait), locals = listOf(0x7B)))
        }.build()
        assertThrows<AsyncifyUnsupported> { Asyncify.transform(simd, setOf("w.wait")) }

        // 止まりうる import への末尾呼び出し
        val tail = WasmBinary().apply {
            val wait = importFunc("w", "wait", emptyList(), listOf(i32))
            mutableI64Global(0)
            exportFunc("run", func(emptyList(), listOf(i32), WasmBinary.code(0x12, wait)))
        }.build()
        val e = assertThrows<AsyncifyUnsupported> { Asyncify.transform(tail, setOf("w.wait")) }
        assertTrue(e.message!!.contains("末尾呼び出し"), e.message)

        // 引数を持つブロックの中の止まりうる呼び出し: (block (param i32) (result i32) drop call wait)
        val blockParam = WasmBinary().apply {
            val wait = importFunc("w", "wait", emptyList(), listOf(i32))
            val bt = typeIndex(listOf(i32), listOf(i32))
            mutableI64Global(0)
            exportFunc("run", func(emptyList(), listOf(i32), WasmBinary.code(0x41, 0, 0x02, bt, 0x1A, 0x10, wait, 0x0B)))
        }.build()
        assertThrows<AsyncifyUnsupported> { Asyncify.transform(blockParam, setOf("w.wait")) }

        // 止まりうる関数を呼ばないなら、v128 のローカルがあってもよい（変換しない）
        val r = Asyncify.transform(simd, emptySet())
        assertEquals(0, r.instrumented)
        assertTrue(EndiveRuntime().load(r.bytes).isAsyncified)
    }

    @Test
    fun `checkpointが投げた時間の上限の例外は、そのまま外に出る`() {
        val e = assertThrows<WasmLoadException> {
            Asyncify.transform(resource("prompt-wasi.wasm"), WasiP1.WAIT_IMPORTS) { throw WasmLoadException("時間の上限") }
        }
        assertEquals("時間の上限", e.message)
    }
}
