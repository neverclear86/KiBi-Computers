package pro.ikili.kibicomputers.core.runtime

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * どの [WasmRuntime] の実装も満たす振る舞い。実装ごとにこのクラスを継承してテストする。
 *
 * カーネルはこの振る舞いだけを当てにする。
 */
abstract class WasmRuntimeContractTest {
    abstract val runtime: WasmRuntime

    private fun instantiate(bytes: ByteArray, config: WasmInstanceConfig = WasmInstanceConfig.EMPTY): WasmInstance = runtime.load(bytes).instantiate(config)

    private class HostError : RuntimeException("host")

    @Test
    fun `exportの関数を呼べる`() {
        instantiate(TestModules.ADD).use { assertEquals(42L, it.call("add", 40, 2)[0]) }
    }

    @Test
    fun `importとexportの一覧が分かる`() {
        val add = runtime.load(TestModules.ADD)
        assertEquals(listOf(WasmExportDescriptor("add", WasmExternKind.FUNCTION, WasmFunctionType.of(listOf(WasmValueType.I32, WasmValueType.I32), listOf(WasmValueType.I32)))), add.exports)
        assertEquals(emptyList<WasmImportDescriptor>(), add.imports)
        assertFalse(add.isFuelMetered)

        val host = runtime.load(TestModules.HOST)
        assertEquals(listOf(WasmImportDescriptor("env", "twice", WasmExternKind.FUNCTION, TestModules.TWICE_TYPE)), host.imports)
    }

    @Test
    fun `ホストの関数を呼べる`() {
        val config = WasmInstanceConfig.builder()
            .hostFunction("env", "twice", TestModules.TWICE_TYPE) { _, args -> longArrayOf(args[0] * 2) }
            .build()
        instantiate(TestModules.HOST, config).use { assertEquals(21L, it.call("run", 10)[0]) }
    }

    @Test
    fun `ホストの関数の例外はそのまま外に出る`() {
        val error = HostError()
        val config = WasmInstanceConfig.builder()
            .hostFunction("env", "twice", TestModules.TWICE_TYPE) { _, _ -> throw error }
            .build()
        instantiate(TestModules.HOST, config).use { instance ->
            assertSame(error, assertThrows<HostError> { instance.call("run", 1) })
        }
    }

    @Test
    fun `ホストの関数からメモリを読める`() {
        var printed: String? = null
        val config = WasmInstanceConfig.builder()
            .hostFunction("env", "print", TestModules.PRINT_TYPE) { instance, args ->
                printed = String(instance.memory!!.read(args[0].toInt(), args[1].toInt()))
                LongArray(0)
            }
            .build()
        instantiate(TestModules.MEMORY, config).use { instance ->
            assertArrayEquals(LongArray(0), instance.call("greet"))
            assertEquals("hello", printed)
        }
    }

    @Test
    fun `ホストからメモリを読み書きできる`() {
        val config = WasmInstanceConfig.builder().hostFunction("env", "print", TestModules.PRINT_TYPE) { _, _ -> LongArray(0) }.build()
        instantiate(TestModules.MEMORY, config).use { instance ->
            val memory = instance.memory!!
            assertEquals(1, memory.pages)
            assertEquals(65536L, memory.byteSize)
            memory.write(100, byteArrayOf(1, 2, 3, 4))
            assertEquals(10L, instance.call("sum", 100, 4)[0])
            memory.writeI32(200, 0x12345678)
            assertEquals(0x78, memory.readU8(200))
            assertEquals(0x12345678, memory.readI32(200))
            memory.writeI64(300, -2L)
            assertEquals(-2L, memory.readI64(300))
            memory.writeU8(400, 0xff)
            assertEquals(0xffL, instance.call("load", 400)[0])
            val buf = ByteArray(7)
            memory.read(16, buf, 1, 5)
            assertEquals("hello", String(buf, 1, 5))

            assertEquals(1, memory.grow(1))
            assertEquals(2, memory.pages)
            memory.writeU8(65536 + 10, 7)
            assertEquals(7L, instance.call("load", 65536 + 10)[0])
        }
    }

    @Test
    fun `範囲外のメモリをホストが読むとWasmMemoryAccessException`() {
        val config = WasmInstanceConfig.builder().hostFunction("env", "print", TestModules.PRINT_TYPE) { _, _ -> LongArray(0) }.build()
        instantiate(TestModules.MEMORY, config).use { instance ->
            val memory = instance.memory!!
            assertThrows<WasmMemoryAccessException> { memory.read(65530, 10) }
            assertThrows<WasmMemoryAccessException> { memory.readI32(65533) }
            // 負の int は 2GiB より上の番地
            assertThrows<WasmMemoryAccessException> { memory.readU8(-1) }
            assertThrows<WasmMemoryAccessException> { memory.write(65536, byteArrayOf(1)) }
        }
    }

    @Test
    fun `メモリを持たないモジュールのmemoryはnull`() {
        instantiate(TestModules.ADD).use { assertNull(it.memory) }
    }

    @Test
    fun `トラップの種類が分かる`() {
        instantiate(TestModules.TRAPS).use { instance ->
            assertEquals(WasmTrapKind.UNREACHABLE, assertThrows<WasmTrapException> { instance.call("boom") }.kind)
            assertEquals(WasmTrapKind.INTEGER_DIVIDE_BY_ZERO, assertThrows<WasmTrapException> { instance.call("div", 1, 0) }.kind)
            assertEquals(WasmTrapKind.INTEGER_OVERFLOW, assertThrows<WasmTrapException> { instance.call("div", Int.MIN_VALUE.toLong(), -1) }.kind)
            assertEquals(WasmTrapKind.STACK_OVERFLOW, assertThrows<WasmTrapException> { instance.call("recurse") }.kind)
            // トラップの後も同じインスタンスで呼べる
            assertEquals(3L, instance.call("div", 7, 2)[0])
        }
    }

    @Test
    fun `WASMの中の範囲外アクセスはトラップ`() {
        val config = WasmInstanceConfig.builder().hostFunction("env", "print", TestModules.PRINT_TYPE) { _, _ -> LongArray(0) }.build()
        instantiate(TestModules.MEMORY, config).use { instance ->
            assertEquals(WasmTrapKind.MEMORY_OUT_OF_BOUNDS, assertThrows<WasmTrapException> { instance.call("load", 70000) }.kind)
        }
    }

    @Test
    fun `exportされていない関数や引数の数の違いはIllegalArgumentException`() {
        instantiate(TestModules.ADD).use { instance ->
            assertThrows<IllegalArgumentException> { instance.call("sub", 1, 2) }
            assertThrows<IllegalArgumentException> { instance.call("add", 1) }
        }
    }

    @Test
    fun `importがないとWasmLinkException`() {
        assertThrows<WasmLinkException> { instantiate(TestModules.HOST) }
    }

    @Test
    fun `importの型が違うとWasmLinkException`() {
        val config = WasmInstanceConfig.builder().hostFunction("env", "twice", WasmFunctionType.EMPTY) { _, _ -> LongArray(0) }.build()
        assertThrows<WasmLinkException> { instantiate(TestModules.HOST, config) }
    }

    @Test
    fun `WASMでないとWasmLoadException`() {
        assertThrows<WasmLoadException> { runtime.load(TestModules.MALFORMED) }
    }

    @Test
    fun `start関数はインスタンスを作るときに実行される`() {
        var calls = 0
        val config = WasmInstanceConfig.builder().hostFunction("env", "started", WasmFunctionType.EMPTY) { _, _ ->
            calls++
            LongArray(0)
        }.build()
        instantiate(TestModules.START, config).close()
        assertEquals(1, calls)
    }

    @Test
    fun `start関数がトラップするとインスタンスを作るときWasmTrapException`() {
        val e = assertThrows<WasmTrapException> { instantiate(TestModules.START_TRAP) }
        assertEquals(WasmTrapKind.UNREACHABLE, e.kind)
    }

    @Test
    fun `start関数の中のホストの関数の例外はインスタンスを作るときそのまま出る`() {
        val config = WasmInstanceConfig.builder().hostFunction("env", "started", WasmFunctionType.EMPTY) { _, _ -> throw HostError() }.build()
        assertThrows<HostError> { instantiate(TestModules.START, config) }
    }

    @Test
    fun `燃料を計測するモジュールは燃料の名前を隠す`() {
        val module = runtime.load(TestModules.FUEL)
        assertTrue(module.isFuelMetered)
        assertEquals(listOf("env.charge"), module.imports.map { "${it.module}.${it.name}" })
        assertEquals(listOf("spin", "charge"), module.exports.map { it.name })
    }

    private fun fuelConfig(initial: Long, handler: WasmFuelHandler?): WasmInstanceConfig = WasmInstanceConfig.builder()
        .hostFunction("env", "charge", WasmFunctionType.EMPTY) { instance, _ ->
            instance.fuel -= 5
            LongArray(0)
        }
        .fuel(initial, handler)
        .build()

    @Test
    fun `燃料が切れると処理が呼ばれ配った分で続く`() {
        val seen = mutableListOf<Long>()
        val config = fuelConfig(10) { _, remaining ->
            seen += remaining
            remaining + 10
        }
        instantiate(TestModules.FUEL, config).use { instance ->
            assertEquals(10L, instance.fuel)
            instance.call("spin", 25)
            assertEquals(listOf(-1L, -1L), seen)
            assertEquals(10L + 20 - 25, instance.fuel)
        }
    }

    @Test
    fun `燃料切れの処理が例外を投げると実行が止まる`() {
        val error = HostError()
        instantiate(TestModules.FUEL, fuelConfig(3) { _, _ -> throw error }).use { instance ->
            assertSame(error, assertThrows<HostError> { instance.call("spin", 1_000_000) })
            assertEquals(-1L, instance.fuel)
        }
    }

    @Test
    fun `燃料切れの処理がなければFUEL_EXHAUSTEDのトラップ`() {
        instantiate(TestModules.FUEL, fuelConfig(0, null)).use { instance ->
            assertEquals(WasmTrapKind.FUEL_EXHAUSTED, assertThrows<WasmTrapException> { instance.call("spin", 1) }.kind)
        }
    }

    @Test
    fun `ホストの関数から燃料を課せる`() {
        instantiate(TestModules.FUEL, fuelConfig(100, null)).use { instance ->
            instance.call("charge")
            assertEquals(95L, instance.fuel)
            instance.fuel = 7
            instance.call("spin", 2)
            assertEquals(5L, instance.fuel)
        }
    }

    @Test
    fun `燃料を計測しないモジュールのfuelはIllegalStateException`() {
        instantiate(TestModules.ADD).use { instance ->
            assertThrows<IllegalStateException> { instance.fuel }
            assertThrows<IllegalStateException> { instance.fuel = 1 }
        }
    }

    @Test
    fun `同じモジュールから別々のインスタンスを作れる`() {
        val module = runtime.load(TestModules.FUEL)
        module.instantiate(fuelConfig(10, null)).use { a ->
            module.instantiate(fuelConfig(20, null)).use { b ->
                a.call("spin", 3)
                assertEquals(7L, a.fuel)
                assertEquals(20L, b.fuel)
            }
        }
    }
}
