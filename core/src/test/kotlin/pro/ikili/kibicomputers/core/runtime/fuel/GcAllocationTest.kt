package pro.ikili.kibicomputers.core.runtime.fuel

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.MemoryAccount
import pro.ikili.kibicomputers.core.kernel.MemoryRefusal
import pro.ikili.kibicomputers.core.kernel.ProcessMemory
import pro.ikili.kibicomputers.core.runtime.Instrumentation
import pro.ikili.kibicomputers.core.runtime.WasmAsyncStack
import pro.ikili.kibicomputers.core.runtime.WasmAsyncState
import pro.ikili.kibicomputers.core.runtime.WasmFunctionType
import pro.ikili.kibicomputers.core.runtime.WasmInstance
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmLinkException
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.WasmModule
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind
import pro.ikili.kibicomputers.core.runtime.WasmValueType
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.wasi.kibi.WitTool

/**
 * GC のオブジェクト（struct・array）の確保を RAM と燃料に数える（#939。ADR 0048 の「GC のオブジェクト」、ADR 0028）。
 * どれもコンパイルありとインタプリタだけの両方で確かめる
 */
class GcAllocationTest {
    private val runtimes = listOf("コンパイルあり" to EndiveRuntime(), "インタプリタだけ" to EndiveRuntime(compile = false))

    /** プロセスの上限 1 MiB（16 ページ）の勘定 */
    private fun budget(pages: Int = 16): ProcessMemory {
        val server = MemoryAccount(Long.MAX_VALUE / 4)
        val machine = MemoryAccount(1L shl 40, server)
        return machine.process(pages)
    }

    private fun config(budget: ProcessMemory?): WasmInstanceConfig =
        WasmInstanceConfig.builder().fuel(1L shl 40, null).tableLimit(Int.MAX_VALUE, budget).build()

    private fun metered(wat: String): ByteArray = FuelInjector.inject(WitTool.wat(wat)).bytes

    private fun forEachRuntime(bytes: ByteArray, block: (String, WasmModule) -> Unit) {
        for ((name, rt) in runtimes) block(name, rt.load(bytes))
    }

    private val arrays = """
        (module
          (type ${'$'}b (array (mut i8)))
          (type ${'$'}r (array (mut anyref)))
          (func (export "bytes") (param i32) (result i32) (array.len (array.new_default ${'$'}b (local.get 0))))
          (func (export "refs") (param i32) (result i32) (array.len (array.new ${'$'}r (ref.null any) (local.get 0))))
          (func (export "fill") (param i32) (result i32)
            (local ${'$'}a (ref null ${'$'}b))
            (local.set ${'$'}a (array.new_default ${'$'}b (i32.const 200000)))
            (array.fill ${'$'}b (local.get ${'$'}a) (i32.const 0) (i32.const 7) (local.get 0))
            (array.get_u ${'$'}b (local.get ${'$'}a) (i32.const 0))))
    """.trimIndent()

    @Test
    fun `上限を超えるarray_newは、作る前にOUT_OF_MEMORYのトラップで止まり、RAMに数えない`() {
        val bytes = metered(arrays)
        forEachRuntime(bytes) { name, module ->
            for (export in listOf("bytes", "refs")) {
                val memory = budget()
                module.instantiate(config(memory)).use { inst ->
                    // 8 バイト × 1,000,000（refs は 16 バイト）は 1 MiB を超える
                    val e = assertThrows<WasmTrapException>("$name $export") { inst.call(export, 1_000_000) }
                    assertEquals(WasmTrapKind.OUT_OF_MEMORY, e.kind, "$name $export: ${e.message}")
                    assertEquals(0L, memory.usedBytes, "$name $export")
                    assertEquals(MemoryRefusal.PROCESS, memory.lastRefusal, "$name $export")
                }
                assertEquals(0L, memory.usedBytes, "$name $export")
            }
        }
    }

    @Test
    fun `収まるarray_newは数えて、閉じたら返す`() {
        val bytes = metered(arrays)
        forEachRuntime(bytes) { name, module ->
            val memory = budget()
            module.instantiate(config(memory)).use { inst ->
                assertEquals(1000L, inst.call("bytes", 1000).single(), name)
                // 64 + 8 × 1,000 バイトを、64 KiB の前借りで数える
                assertEquals(64L * 1024, memory.usedBytes, name)
                // 前借りの残りで足りる間は増えない
                assertEquals(10L, inst.call("refs", 10).single(), name)
                assertEquals(64L * 1024, memory.usedBytes, name)
                // 前借りの残りより大きい確保は、要る分を足す
                assertEquals(100_000L, inst.call("bytes", 100_000).single(), name)
                assertTrue(memory.usedBytes >= 64L + 8 * 1000 + 64 + 16 * 10 + 64 + 8 * 100_000, "$name ${memory.usedBytes}")
                assertNull(memory.lastRefusal, name)
            }
            assertEquals(0L, memory.usedBytes, name)
        }
    }

    @Test
    fun `長さが2の31乗以上でも、Endiveに負の長さを渡さずOUT_OF_MEMORYで止まる`() {
        val bytes = metered(arrays)
        forEachRuntime(bytes) { name, module ->
            for (budget in listOf(budget(), null)) {
                module.instantiate(config(budget)).use { inst ->
                    for (len in listOf(0x8000_0000L, 0xFFFF_FFFFL)) {
                        val e = assertThrows<WasmTrapException>("$name $len budget=${budget != null}") { inst.call("bytes", len) }
                        assertEquals(WasmTrapKind.OUT_OF_MEMORY, e.kind, "$name $len: ${e.message}")
                    }
                }
                assertEquals(0L, budget?.usedBytes ?: 0L, name)
            }
        }
    }

    @Test
    fun `struct_newを作り続けると、RAMの上限で止まる`() {
        val bytes = metered(
            """
            (module
              (type ${'$'}s (struct (field (mut i32)) (field (mut anyref))))
              (global ${'$'}keep (mut anyref) (ref.null any))
              (func (export "f")
                (loop ${'$'}l
                  (global.set ${'$'}keep (struct.new ${'$'}s (i32.const 1) (global.get ${'$'}keep)))
                  (br ${'$'}l))))
            """.trimIndent(),
        )
        forEachRuntime(bytes) { name, module ->
            val memory = budget()
            module.instantiate(config(memory)).use { inst ->
                val e = assertThrows<WasmTrapException>(name) { inst.call("f") }
                assertEquals(WasmTrapKind.OUT_OF_MEMORY, e.kind, "$name: ${e.message}")
                assertTrue(memory.usedBytes <= memory.limitBytes, name)
                assertTrue(memory.usedBytes > memory.limitBytes - 64 * 1024 - 96, "$name ${memory.usedBytes}")
            }
            assertEquals(0L, memory.usedBytes, name)
        }
    }

    @Test
    fun `array_fillとarray_new_defaultは、長さに比例した燃料を引く`() {
        val bytes = metered(arrays)
        forEachRuntime(bytes) { name, module ->
            module.instantiate(config(budget(256))).use { inst ->
                fun used(export: String, n: Long): Long {
                    val before = inst.fuel
                    inst.call(export, n)
                    return before - inst.fuel
                }
                assertEquals(100_000L - 10, used("fill", 100_000) - used("fill", 10), name)
                assertEquals(50_000L - 10, used("bytes", 50_000) - used("bytes", 10), name)
            }
        }
    }

    @Test
    fun `大きな配列を初期値に持つグローバルは、インスタンスを作れず、数えかけた分も残らない`() {
        for (len in listOf("(i32.const 0x7fffffff)", "(i32.add (global.get ${'$'}n) (i32.const 0x7fffff00))")) {
            val bytes = metered(
                """
                (module
                  (type ${'$'}a (array i32))
                  (global ${'$'}n i32 (i32.const 16))
                  (global ${'$'}g (ref ${'$'}a) (array.new_default ${'$'}a $len))
                  (func (export "f") (result i32) (array.len (global.get ${'$'}g))))
                """.trimIndent(),
            )
            forEachRuntime(bytes) { name, module ->
                val memory = budget()
                val e = assertThrows<WasmLinkException>("$name $len") { module.instantiate(config(memory)) }
                assertTrue(e.message!!.contains("初期値の GC のオブジェクト"), e.message)
                assertEquals(0L, memory.usedBytes, "$name $len")
                assertEquals(MemoryRefusal.PROCESS, memory.lastRefusal, "$name $len")
            }
        }
    }

    @Test
    fun `小さな配列を初期値に持つグローバルは、作るときに数えて動く`() {
        val bytes = metered(
            """
            (module
              (type ${'$'}a (array i32))
              (global ${'$'}g (ref ${'$'}a) (array.new_default ${'$'}a (i32.const 3)))
              (func (export "f") (result i32) (array.len (global.get ${'$'}g))))
            """.trimIndent(),
        )
        forEachRuntime(bytes) { name, module ->
            val memory = budget()
            module.instantiate(config(memory)).use { inst ->
                assertTrue(memory.usedBytes > 0, name)
                assertEquals(3L, inst.call("f").single(), name)
            }
            assertEquals(0L, memory.usedBytes, name)
        }
    }

    @Test
    fun `パッシブの要素の式が作るオブジェクトは、使うarray_new_elemの前に数える`() {
        val bytes = metered(
            """
            (module
              (type ${'$'}big (array i8))
              (type ${'$'}arr (array (ref null ${'$'}big)))
              (elem ${'$'}e (ref null ${'$'}big) (item (array.new_default ${'$'}big (i32.const 1000000))))
              (func (export "f") (result i32) (array.len (array.new_elem ${'$'}arr ${'$'}e (i32.const 0) (i32.const 1)))))
            """.trimIndent(),
        )
        forEachRuntime(bytes) { name, module ->
            val memory = budget()
            module.instantiate(config(memory)).use { inst ->
                val e = assertThrows<WasmTrapException>(name) { inst.call("f") }
                assertEquals(WasmTrapKind.OUT_OF_MEMORY, e.kind, "$name: ${e.message}")
                assertEquals(0L, memory.usedBytes, name)
            }
            // 上限が大きければ動く
            val large = budget(1024)
            module.instantiate(config(large)).use { inst -> assertEquals(1L, inst.call("f").single(), name) }
        }
    }

    @Test
    fun `予約したkibi_gc_allocをimportするモジュールは断る`() {
        val e = assertThrows<WasmLoadException> {
            FuelInjector.inject(WitTool.wat("""(module (import "kibi" "gc_alloc" (func (param i64) (result i64))))"""))
        }
        assertTrue(e.message!!.contains("gc_alloc"), e.message)
    }

    @Test
    fun `GCのオブジェクトを作る命令があるときだけimportを足し、関数の番号を2つずらす`() {
        val gc = FuelInjector.inject(WitTool.wat(arrays))
        assertEquals(0, gc.fuelFunc)
        assertEquals(1, gc.gcFunc)
        assertEquals(-1, gc.originalFunc(0))
        assertEquals(-1, gc.originalFunc(1))
        assertEquals(0, gc.originalFunc(2))
        assertNull(WitTool.validate(gc.bytes))
        val plain = FuelInjector.inject(WitTool.wat("""(module (func (export "f") (result i32) (i32.const 1)))"""))
        assertEquals(-1, plain.gcFunc)
        assertEquals(0, plain.originalFunc(1))
        // 決定的
        assertArrayEquals(gc.bytes, FuelInjector.inject(WitTool.wat(arrays)).bytes)
    }

    private class Stack : WasmAsyncStack {
        val values = ArrayList<Long>()

        override fun push(value: Long) {
            values += value
        }

        override fun pop(): Long = values.removeAt(values.size - 1)
    }

    @Test
    fun `止まりうる関数の、待ち点を含まない入れ子のブロックで作っても、巻き戻しと巻き直しで正しく動き、数える`() {
        val bytes = Instrumentation.apply(
            WitTool.wat(
                """
                (module
                  (type ${'$'}a (array (mut i32)))
                  (import "w" "wait" (func ${'$'}wait (result i32)))
                  (func (export "run") (result i32)
                    (local ${'$'}n i32)
                    (block
                      (local.set ${'$'}n (array.len (array.new_default ${'$'}a (i32.const 5)))))
                    (i32.add (i32.add (call ${'$'}wait) (call ${'$'}wait)) (local.get ${'$'}n))))
                """.trimIndent(),
            ),
            setOf("w.wait"),
        )
        assertNull(WitTool.validate(bytes))
        forEachRuntime(bytes) { name, module ->
            assertTrue(module.isAsyncified, name)
            val memory = budget()
            var next = 0L
            var unwinds = 0
            val stack = Stack()
            val cfg = WasmInstanceConfig.builder()
                .hostFunction("w", "wait", WasmFunctionType.of(emptyList(), listOf(WasmValueType.I32))) { instance: WasmInstance, _ ->
                    if (instance.asyncState == WasmAsyncState.REWINDING) {
                        instance.asyncState = WasmAsyncState.NORMAL
                        longArrayOf(++next)
                    } else {
                        unwinds++
                        instance.asyncState = WasmAsyncState.UNWINDING
                        longArrayOf(0)
                    }
                }
                .asyncStack(stack)
                .fuel(1L shl 40, null)
                .tableLimit(Int.MAX_VALUE, memory)
                .build()
            module.instantiate(cfg).use { inst ->
                var result = inst.call("run")
                while (inst.asyncState == WasmAsyncState.UNWINDING) {
                    inst.asyncState = WasmAsyncState.REWINDING
                    result = inst.call("run")
                }
                assertEquals(2, unwinds, name)
                // 1 + 2 + 5
                assertEquals(8L, result.single(), name)
                assertTrue(memory.usedBytes > 0, name)
            }
            assertEquals(0L, memory.usedBytes, name)
        }
    }
}
