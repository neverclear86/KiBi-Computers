package pro.ikili.kibicomputers.core.runtime.endive

import org.junit.jupiter.api.Assertions.assertEquals
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
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind

class EndiveRuntimeTest : WasmRuntimeContractTest() {
    override val runtime: WasmRuntime = EndiveRuntime()

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
