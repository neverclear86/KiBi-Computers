package pro.ikili.kibicomputers.core.runtime.endive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.runtime.WasmBinary
import pro.ikili.kibicomputers.core.runtime.WasmBinary.Companion.I32
import pro.ikili.kibicomputers.core.runtime.WasmBinary.Companion.I64
import pro.ikili.kibicomputers.core.runtime.WasmBinary.Companion.code
import pro.ikili.kibicomputers.core.runtime.WasmFuel
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmLinkException
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.WasmRuntime
import pro.ikili.kibicomputers.core.runtime.WasmRuntimeContractTest
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind

class EndiveRuntimeTest : WasmRuntimeContractTest() {
    override val runtime: WasmRuntime = EndiveRuntime()

    @Test
    fun `割り込むとINTERRUPTEDのトラップで止まる`() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/fib.wasm")).use { it.readBytes() }
        val instance = runtime.load(bytes).instantiate(WasmInstanceConfig.EMPTY)
        val error = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val started = java.util.concurrent.CountDownLatch(1)
        val thread = Thread {
            started.countDown()
            try {
                instance.call("fib", 0x7fffffff)
            } catch (t: Throwable) {
                error.set(t)
            }
        }
        thread.start()
        started.await()
        Thread.sleep(50)
        thread.interrupt()
        thread.join(10_000)
        val e = error.get()
        assertTrue(e is WasmTrapException && e.kind == WasmTrapKind.INTERRUPTED, "$e")
    }

    @Test
    fun `テーブルと変換のトラップの種類が分かる`() {
        val bytes = WasmBinary().run {
            table(1)
            val void = typeIndex(emptyList(), emptyList())
            // ind(i): テーブルの i 番目を call_indirect で呼ぶ
            exportFunc("ind", func(listOf(I32), emptyList(), code(0x20, 0, 0x11, void, 0x00)))
            // conv(): NaN の f32 を i32.trunc_f32_s
            exportFunc("conv", func(emptyList(), listOf(I32), code(0x43, 0x00, 0x00, 0xc0, 0x7f, 0xa8)))
            build()
        }
        runtime.load(bytes).instantiate(WasmInstanceConfig.EMPTY).use { instance ->
            assertEquals(WasmTrapKind.INDIRECT_CALL, assertThrows<WasmTrapException> { instance.call("ind", 0) }.kind)
            assertEquals(WasmTrapKind.TABLE_OUT_OF_BOUNDS, assertThrows<WasmTrapException> { instance.call("ind", 5) }.kind)
            assertEquals(WasmTrapKind.INVALID_CONVERSION, assertThrows<WasmTrapException> { instance.call("conv") }.kind)
        }
    }

    @Test
    fun `燃料のimportとexportの片方しかないモジュールは読み込まない`() {
        val onlyImport = WasmBinary().run {
            val fuel = importFunc(WasmFuel.IMPORT_MODULE, WasmFuel.IMPORT_NAME, listOf(I64), listOf(I64))
            exportFunc("f", func(emptyList(), emptyList(), code(0x42, 0, 0x10, fuel, 0x1a)))
            build()
        }
        assertThrows<WasmLoadException> { runtime.load(onlyImport) }

        val onlyExport = WasmBinary().run {
            mutableI64Global(0, WasmFuel.EXPORT_GLOBAL)
            build()
        }
        assertThrows<WasmLoadException> { runtime.load(onlyExport) }
    }

    @Test
    fun `燃料のimportの型が違うモジュールは読み込まない`() {
        val bytes = WasmBinary().run {
            importFunc(WasmFuel.IMPORT_MODULE, WasmFuel.IMPORT_NAME, listOf(I32), listOf(I32))
            mutableI64Global(0, WasmFuel.EXPORT_GLOBAL)
            build()
        }
        assertThrows<WasmLoadException> { runtime.load(bytes) }
    }

    @Test
    fun `データセグメントが範囲に収まらないとWasmLinkException`() {
        val bytes = WasmBinary().memory(1).data(65535, byteArrayOf(1, 2)).build()
        assertThrows<WasmLinkException> { runtime.load(bytes).instantiate(WasmInstanceConfig.EMPTY) }
    }

    @Test
    fun `Endiveのトラップのメッセージから種類を決める`() {
        assertEquals(WasmTrapKind.UNREACHABLE, trapKind("Trapped on unreachable instruction"))
        assertEquals(WasmTrapKind.MEMORY_OUT_OF_BOUNDS, trapKind("out of bounds memory access: attempted to access address: 1"))
        assertEquals(WasmTrapKind.INTEGER_DIVIDE_BY_ZERO, trapKind("integer divide by zero"))
        assertEquals(WasmTrapKind.INTEGER_OVERFLOW, trapKind("integer overflow"))
        assertEquals(WasmTrapKind.INVALID_CONVERSION, trapKind("invalid conversion to integer"))
        assertEquals(WasmTrapKind.STACK_OVERFLOW, trapKind("call stack exhausted"))
        assertEquals(WasmTrapKind.INDIRECT_CALL, trapKind("indirect call type mismatch"))
        assertEquals(WasmTrapKind.OTHER, trapKind("something else"))
    }
}
