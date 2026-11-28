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
import pro.ikili.kibicomputers.core.runtime.WasmSharedFuel
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind
import pro.ikili.kibicomputers.core.runtime.WasmValueType
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.wasi.kibi.WitTool
import run.endive.wasm.Parser
import run.endive.wasm.types.OpCode

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
                    // 16 バイト × 1,000,000（refs は i31ref の分を足して 32 バイト）は 1 MiB を超える
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
                // 64 + 16 × 1,000 バイトを、64 KiB の前借りで数える
                assertEquals(64L * 1024, memory.usedBytes, name)
                // 前借りの残りで足りる間は増えない
                assertEquals(10L, inst.call("refs", 10).single(), name)
                assertEquals(64L * 1024, memory.usedBytes, name)
                // 前借りの残りより大きい確保は、要る分を足す
                assertEquals(50_000L, inst.call("bytes", 50_000).single(), name)
                assertTrue(memory.usedBytes >= 64L + 16 * 1000 + 64 + 32 * 10 + 64 + 16 * 50_000, "$name ${memory.usedBytes}")
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
                // struct 1 つは 64 + 16（i32）+ 32（anyref。i31ref の分を足す）= 112 バイト
                assertTrue(memory.usedBytes > memory.limitBytes - 64 * 1024 - 112, "$name ${memory.usedBytes}")
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

    // ---- ここから: 命令ごとの課金の道（#1004 のレビュー） ----

    /** `%` を `$` に直す（WAT の名前の `$` を Kotlin の文字列で書きやすくする） */
    private fun w(text: String): String = text.replace('%', '$')

    /** マシンの RAM が [machineBytes] しかない勘定（前借りの 64 KiB は断られ、要る分だけが数えられる） */
    private fun tight(machineBytes: Long = 5000): ProcessMemory {
        val server = MemoryAccount(Long.MAX_VALUE / 4)
        val machine = MemoryAccount(machineBytes, server)
        return machine.process(16)
    }

    /** 新しいインスタンスで [export] を呼び、勘定に数えたバイト数と、引いた燃料を返す */
    private fun measure(module: WasmModule, memory: ProcessMemory, export: String, vararg args: Long): Pair<Long, Long> {
        module.instantiate(config(memory)).use { inst ->
            val before = inst.fuel
            inst.call(export, *args)
            return memory.usedBytes to before - inst.fuel
        }
    }

    @Test
    fun `struct_new_defaultとarray_new_fixedは、命令だけで決まるバイト数と、フィールド・要素の数の燃料を数える`() {
        val bytes = metered(
            w(
                """
                (module
                  (type %s2 (struct (field i32) (field i32)))
                  (type %s5 (struct (field i32) (field i32) (field i32) (field i32) (field i32)))
                  (type %a (array i32))
                  (func (export "s2") (drop (struct.new_default %s2)))
                  (func (export "s5") (drop (struct.new_default %s5)))
                  (func (export "f3") (result i32)
                    (array.len (array.new_fixed %a 3 (i32.const 1) (i32.const 2) (i32.const 3))))
                  (func (export "f5") (result i32)
                    (array.len (array.new_fixed %a 5 (i32.const 1) (i32.const 2) (i32.const 3) (i32.const 4) (i32.const 5)))))
                """.trimIndent(),
            ),
        )
        forEachRuntime(bytes) { name, module ->
            val s2 = measure(module, tight(), "s2")
            val s5 = measure(module, tight(), "s5")
            val f3 = measure(module, tight(), "f3")
            val f5 = measure(module, tight(), "f5")
            // 64 + 16 × フィールド、64 + 16 × 要素（i32 の配列も、array.copy で後から Object[] が付きうるので 16）
            assertEquals(64L + 16 * 2, s2.first, "$name s2")
            assertEquals(64L + 16 * 5, s5.first, "$name s5")
            assertEquals(64L + 16 * 3, f3.first, "$name f3")
            assertEquals(64L + 16 * 5, f5.first, "$name f5")
            // フィールドが 3 つ増えると 3
            assertEquals(3L, s5.second - s2.second, name)
            // 要素が 2 つ増えると、i32.const が 2、array.new_fixed の要素の数の分が 2
            assertEquals(4L, f5.second - f3.second, name)
        }
    }

    @Test
    fun `array_new_dataは、長さに比例したバイト数を数えて、上限を超えるなら作る前に止まる`() {
        val bytes = metered(
            w(
                """
                (module
                  (type %b (array (mut i8)))
                  (data %d "0123456789")
                  (func (export "d") (param i32) (result i32)
                    (array.len (array.new_data %b %d (i32.const 0) (local.get 0)))))
                """.trimIndent(),
            ),
        )
        forEachRuntime(bytes) { name, module ->
            val ten = measure(module, tight(), "d", 10)
            val five = measure(module, tight(), "d", 5)
            assertEquals(64L + 16 * 10, ten.first, name)
            assertEquals(5L, ten.second - five.second, name)
            // データの範囲を超える長さでも、RAM を先に断る（Endive に大きな配列を作らせない）
            val memory = budget()
            module.instantiate(config(memory)).use { inst ->
                val e = assertThrows<WasmTrapException>(name) { inst.call("d", 1_000_000) }
                assertEquals(WasmTrapKind.OUT_OF_MEMORY, e.kind, "$name: ${e.message}")
                assertEquals(0L, memory.usedBytes, name)
            }
        }
    }

    @Test
    fun `array_copyとarray_init_dataは、長さに比例した燃料を引く`() {
        val data = "a".repeat(1000)
        val bytes = metered(
            w(
                """
                (module
                  (type %b (array (mut i8)))
                  (data %d "$data")
                  (func (export "copy") (param i32)
                    (local %a (ref null %b))
                    (local.set %a (array.new_default %b (i32.const 2000)))
                    (array.copy %b %b (local.get %a) (i32.const 0) (local.get %a) (i32.const 1000) (local.get 0)))
                  (func (export "initd") (param i32)
                    (local %a (ref null %b))
                    (local.set %a (array.new_default %b (i32.const 2000)))
                    (array.init_data %b %d (local.get %a) (i32.const 0) (i32.const 0) (local.get 0))))
                """.trimIndent(),
            ),
        )
        forEachRuntime(bytes) { name, module ->
            for (export in listOf("copy", "initd")) {
                val small = measure(module, budget(256), export, 10)
                val large = measure(module, budget(256), export, 900)
                assertEquals(890L, large.second - small.second, "$name $export")
            }
        }
    }

    /** `(elem)` の中身: 長さ 1 の式 [count] 個 */
    private fun funcItems(count: Int) = (1..count).joinToString(" ") { "%f" }

    /** 命令数が 2k + 2 の `i31ref` の定数式（拡張定数式の足し算を入れ子にしたもの） */
    private fun longItem(k: Int) = "(item (ref.i31 ${"(i32.add (i32.const 1) ".repeat(k)}(i32.const 1)${")".repeat(k)}))"

    @Test
    fun `array_new_elemは、要素の型に関係なく1要素16バイトで数え、array_new_elemとarray_init_elemは1要素4の燃料を引く`() {
        val bytes = metered(
            w(
                """
                (module
                  (type %fa (array (mut funcref)))
                  (func %f)
                  (elem %e func ${funcItems(1100)})
                  (func (export "new") (param i32) (result i32)
                    (array.len (array.new_elem %fa %e (i32.const 0) (local.get 0))))
                  (func (export "init") (param i32) (result i32)
                    (local %a (ref null %fa))
                    (local.set %a (array.new_default %fa (i32.const 1100)))
                    (array.init_elem %fa %e (local.get %a) (i32.const 0) (i32.const 0) (local.get 0))
                    (i32.const 0)))
                """.trimIndent(),
            ),
        )
        forEachRuntime(bytes) { name, module ->
            // funcref の配列でも、Endive は long[] と Object[] の両方を作る
            assertEquals(64L + 16 * 100, measure(module, tight(), "new", 100).first, name)
            for (export in listOf("new", "init")) {
                val small = measure(module, budget(256), export, 10)
                val large = measure(module, budget(256), export, 1000)
                assertEquals(4L * 990, large.second - small.second, "$name $export")
            }
        }
    }

    @Test
    fun `項目の式が長い要素セグメントは、array_new_elem・array_init_elem・table_initの燃料を式の長さに比例して増やす`() {
        // 式は 102 命令: 要素 1 つの燃料は、array.new_elem・array.init_elem が 4 + 101、table.init が 1 + 101
        val items = (1..12).joinToString(" ") { longItem(50) }
        val bytes = metered(
            w(
                """
                (module
                  (type %ia (array (mut i31ref)))
                  (table %t 16 i31ref)
                  (elem %e i31ref $items)
                  (func (export "new") (param i32) (result i32)
                    (array.len (array.new_elem %ia %e (i32.const 0) (local.get 0))))
                  (func (export "init") (param i32) (result i32)
                    (local %a (ref null %ia))
                    (local.set %a (array.new_default %ia (i32.const 16)))
                    (array.init_elem %ia %e (local.get %a) (i32.const 0) (i32.const 0) (local.get 0))
                    (i32.const 0))
                  (func (export "tinit") (param i32)
                    (table.init %t %e (i32.const 0) (i32.const 0) (local.get 0))))
                """.trimIndent(),
            ),
        )
        forEachRuntime(bytes) { name, module ->
            for ((export, weight) in listOf("new" to 105L, "init" to 105L, "tinit" to 102L)) {
                val one = measure(module, budget(256), export, 1)
                val ten = measure(module, budget(256), export, 10)
                assertEquals(weight * 9, ten.second - one.second, "$name $export")
            }
        }
    }

    @Test
    fun `GCのオブジェクトを作る項目の要素セグメントを使うarray_init_elemとtable_initは、使う数だけRAMに数える`() {
        val bytes = metered(
            w(
                """
                (module
                  (type %big (array i8))
                  (type %arr (array (mut (ref null %big))))
                  (table %t 4 (ref null %big))
                  (elem %e (ref null %big)
                    (item (array.new_default %big (i32.const 1000)))
                    (item (array.new_default %big (i32.const 1000))))
                  (func (export "init") (param i32) (result i32)
                    (local %a (ref null %arr))
                    (local.set %a (array.new_default %arr (i32.const 2)))
                    (array.init_elem %arr %e (local.get %a) (i32.const 0) (i32.const 0) (local.get 0))
                    (i32.const 0))
                  (func (export "tinit") (param i32)
                    (table.init %t %e (i32.const 0) (i32.const 0) (local.get 0))))
                """.trimIndent(),
            ),
        )
        forEachRuntime(bytes) { name, module ->
            // 項目 1 つは 64 + 16 × 1,000 = 16,064 バイト。マシンの RAM が 20,000 バイトなら 1 つまで
            val init = measure(module, tight(20_000), "init", 1)
            // テーブルの要素（4 つ。数十バイト）が加わる
            assertTrue(init.first in (96L + 16_064)..(96L + 16_064 + 100), "$name init ${init.first}")
            val tinit = measure(module, tight(20_000), "tinit", 1)
            assertTrue(tinit.first >= 16_064, "$name tinit ${tinit.first}")
            for (export in listOf("init", "tinit")) {
                val memory = tight(20_000)
                module.instantiate(config(memory)).use { inst ->
                    val e = assertThrows<WasmTrapException>("$name $export") { inst.call(export, 2) }
                    assertEquals(WasmTrapKind.OUT_OF_MEMORY, e.kind, "$name $export: ${e.message}")
                    assertTrue(memory.usedBytes < 16_064, "$name $export ${memory.usedBytes}")
                }
                assertEquals(0L, memory.usedBytes, "$name $export")
            }
        }
    }

    @Test
    fun `アクティブの要素の式が作るオブジェクトは、インスタンスを作る前に合計を数える`() {
        val small = metered(
            w(
                """
                (module
                  (type %big (array i8))
                  (table %t 2 (ref null %big))
                  (elem (table %t) (i32.const 0) (ref null %big)
                    (item (array.new_default %big (i32.const 100)))
                    (item (array.new_default %big (i32.const 100)))))
                """.trimIndent(),
            ),
        )
        forEachRuntime(small) { name, module ->
            val memory = tight()
            module.instantiate(config(memory)).use {
                // 2 つで 2 × (64 + 16 × 100)。テーブルの要素の分（2 つ。数十バイト）が加わる
                assertTrue(memory.usedBytes in 3328L..3428L, "$name ${memory.usedBytes}")
            }
            assertEquals(0L, memory.usedBytes, name)
        }
        val huge = metered(
            w(
                """
                (module
                  (type %big (array i8))
                  (table %t 1 (ref null %big))
                  (elem (table %t) (i32.const 0) (ref null %big) (item (array.new_default %big (i32.const 1000000)))))
                """.trimIndent(),
            ),
        )
        forEachRuntime(huge) { name, module ->
            val memory = budget()
            val e = assertThrows<WasmLinkException>(name) { module.instantiate(config(memory)) }
            assertTrue(e.message!!.contains("初期値の GC のオブジェクト"), e.message)
            assertEquals(0L, memory.usedBytes, name)
            val large = budget(1024)
            module.instantiate(config(large)).use { assertTrue(large.usedBytes >= 16_000_064L, "$name ${large.usedBytes}") }
            assertEquals(0L, large.usedBytes, name)
        }
    }

    @Test
    fun `前借りを断られても、要る分だけなら数えて断った記録を残さず、要る分も数えられなければ記録する`() {
        val bytes = metered(arrays)
        forEachRuntime(bytes) { name, module ->
            val memory = tight()
            module.instantiate(config(memory)).use { inst ->
                // 64 KiB の前借りはマシンの RAM（5,000 バイト）を超えるので断られるが、64 + 16 × 100 は数えられる
                assertEquals(100L, inst.call("bytes", 100).single(), name)
                assertEquals(64L + 16 * 100, memory.usedBytes, name)
                assertNull(memory.lastRefusal, name)
                assertEquals(0, memory.refusalCount, name)
                // 残りで足りないものは、前借りでなく本当の確保が断られたので記録する
                val e = assertThrows<WasmTrapException>(name) { inst.call("bytes", 1000) }
                assertEquals(WasmTrapKind.OUT_OF_MEMORY, e.kind, "$name: ${e.message}")
                assertEquals(MemoryRefusal.MACHINE, memory.lastRefusal, name)
                assertEquals(1, memory.refusalCount, name)
                assertEquals(64L + 16 * 100, memory.usedBytes, name)
            }
            assertEquals(0L, memory.usedBytes, name)
        }
    }

    @Test
    fun `インスタンスを閉じた後の確保は断る`() {
        val bytes = metered(
            w(
                """
                (module
                  (type %a (array i32))
                  (import "w" "close" (func %close))
                  (func (export "run") (result i32)
                    (call %close)
                    (array.len (array.new_default %a (i32.const 3)))))
                """.trimIndent(),
            ),
        )
        forEachRuntime(bytes) { name, module ->
            val memory = budget()
            val cfg = WasmInstanceConfig.builder()
                .hostFunction("w", "close", WasmFunctionType.of(emptyList(), emptyList())) { instance: WasmInstance, _ ->
                    instance.close()
                    longArrayOf()
                }
                .fuel(1L shl 40, null)
                .tableLimit(Int.MAX_VALUE, memory)
                .build()
            module.instantiate(cfg).use { inst ->
                val e = assertThrows<WasmTrapException>(name) { inst.call("run") }
                assertEquals(WasmTrapKind.OUT_OF_MEMORY, e.kind, "$name: ${e.message}")
            }
            // 閉じた後に数えると二度と返らない
            assertEquals(0L, memory.usedBytes, name)
        }
    }

    @Test
    fun `燃料を共有する形の挿入でも、GCの確保のimportを足し、関数とグローバルの番号を合わせて数える`() {
        val wat = w(
            """
            (module
              (type %a (array i32))
              (global %g (mut i32) (i32.const 3))
              (func %len (result i32) (global.get %g))
              (func (export "big") (global.set %g (i32.const 1000000)))
              (func (export "run") (result i32) (array.len (array.new_default %a (call %len)))))
            """.trimIndent(),
        )
        val injected = FuelInjector.inject(WitTool.wat(wat), sharedGlobal = true)
        assertEquals(0, injected.fuelFunc)
        assertEquals(1, injected.gcFunc)
        assertEquals(-1, injected.originalFunc(0))
        assertEquals(-1, injected.originalFunc(1))
        assertEquals(0, injected.originalFunc(2))
        assertNull(WitTool.validate(injected.bytes), "挿入した後のバイト列が正しくない")
        for ((name, rt) in runtimes) {
            val module = rt.load(injected.bytes)
            val memory = budget()
            val cfg = WasmInstanceConfig.builder().sharedFuel(WasmSharedFuel(1L shl 40)).tableLimit(Int.MAX_VALUE, memory).build()
            module.instantiate(cfg).use { inst ->
                assertEquals(3L, inst.call("run").single(), name)
                assertTrue(memory.usedBytes > 0, name)
                inst.call("big")
                val e = assertThrows<WasmTrapException>(name) { inst.call("run") }
                assertEquals(WasmTrapKind.OUT_OF_MEMORY, e.kind, "$name: ${e.message}")
            }
            assertEquals(0L, memory.usedBytes, name)
        }
    }

    @Test
    fun `配列の長さを読み込みのときに決められない定数式は、読み込みで断る`() {
        // import したグローバル（燃料を共有する形だけが許す）の値は、読み込みのときには分からない
        val global = w(
            """
            (module
              (type %a (array i32))
              (import "env" "n" (global %n i32))
              (global %g (ref %a) (array.new_default %a (global.get %n))))
            """.trimIndent(),
        )
        val e1 = assertThrows<WasmLoadException> { FuelInjector.inject(WitTool.wat(global), sharedGlobal = true) }
        assertTrue(e1.message!!.contains("グローバル"), e1.message)
        val element = w(
            """
            (module
              (type %big (array i8))
              (import "env" "n" (global %n i32))
              (elem %e (ref null %big) (item (array.new_default %big (global.get %n)))))
            """.trimIndent(),
        )
        val e2 = assertThrows<WasmLoadException> { FuelInjector.inject(WitTool.wat(element), sharedGlobal = true) }
        assertTrue(e2.message!!.contains("要素セグメント"), e2.message)
    }

    @Test
    fun `参照でない要素の配列も1要素16バイトで数え、定数式の配列からのarray_copyで後から付くObject配列の分を見込む`() {
        // 定数式で作った配列は long[] と Object[] の両方を持つ。それを元にした array.copy は、コンパイルありの経路で
        // コピー先（関数の本体で作った i32 の配列）にも全長の Object[] を作る
        val bytes = metered(
            w(
                """
                (module
                  (type %a (array (mut i32)))
                  (global %src (ref %a) (array.new_fixed %a 2 (i32.const 7) (i32.const 8)))
                  (func (export "copy") (param i32) (result i32)
                    (local %d (ref null %a))
                    (local.set %d (array.new_default %a (local.get 0)))
                    (array.copy %a %a (local.get %d) (i32.const 0) (global.get %src) (i32.const 0) (i32.const 1))
                    (array.get %a (local.get %d) (i32.const 0))))
                """.trimIndent(),
            ),
        )
        forEachRuntime(bytes) { name, module ->
            val memory = tight(20_000)
            module.instantiate(config(memory)).use { inst ->
                // グローバルの配列は 64 + 16 × 2
                assertEquals(64L + 16 * 2, memory.usedBytes, name)
                assertEquals(7L, inst.call("copy", 100).single(), name)
                // コピー先は 64 + 16 × 100（long[] と、後から付く Object[]）
                assertEquals(64L + 16 * 2 + 64 + 16 * 100, memory.usedBytes, name)
            }
        }
    }

    @Test
    fun `i31refを入れられるフィールド・要素・テーブルの要素は、WasmI31Refの分を足して数え、定数式のref_i31はその場で数える`() {
        val bytes = metered(
            w(
                """
                (module
                  (type %any (struct (field anyref)))
                  (type %eq (struct (field (ref null eq))))
                  (type %con (struct (field (ref null %any))))
                  (type %ia (array (mut i31ref)))
                  (type %ea (array (mut externref)))
                  (func (export "any") (drop (struct.new_default %any)))
                  (func (export "eq") (drop (struct.new_default %eq)))
                  (func (export "con") (drop (struct.new_default %con)))
                  (func (export "i31s") (param i32) (drop (array.new_default %ia (local.get 0))))
                  (func (export "externs") (param i32) (drop (array.new_default %ea (local.get 0)))))
                """.trimIndent(),
            ),
        )
        forEachRuntime(bytes) { name, module ->
            assertEquals(64L + 32, measure(module, tight(), "any").first, "$name any")
            assertEquals(64L + 32, measure(module, tight(), "eq").first, "$name eq")
            // 具体的な型の参照には i31ref を入れられない
            assertEquals(64L + 16, measure(module, tight(), "con").first, "$name con")
            assertEquals(64L + 32 * 10, measure(module, tight(), "i31s", 10).first, "$name i31s")
            // extern.convert_any は i31ref をそのまま渡すので、externref にも入る
            assertEquals(64L + 32 * 10, measure(module, tight(), "externs", 10).first, "$name externs")
        }
        // 定数式の ref.i31 と、anyref・funcref のテーブルの要素
        val consts = metered(
            w(
                """
                (module
                  (table %ta 2 anyref)
                  (table %tf 2 funcref)
                  (global %g i31ref (ref.i31 (i32.const 5))))
                """.trimIndent(),
            ),
        )
        forEachRuntime(consts) { name, module ->
            val memory = tight()
            module.instantiate(config(memory)).use {
                // ref.i31 が 16、anyref のテーブルの要素が (20 + 16) × 2、funcref のテーブルの要素が 12 × 2
                assertEquals(16L + 36 * 2 + 12 * 2, memory.usedBytes, name)
            }
        }
    }

    @Test
    fun `項目の式が長い要素セグメントの燃料は、基本の重みと別に、長い分だけを要素ごとの燃料にする`() {
        val module = WitTool.wat(
            w(
                """
                (module
                  (type %ia (array (mut i31ref)))
                  (table %t 4 i31ref)
                  (elem %short i31ref (item (ref.null i31)))
                  (elem %long i31ref ${longItem(5)})
                  (func (export "f")
                    (drop (array.new_elem %ia %long (i32.const 0) (i32.const 1)))
                    (drop (array.new_elem %ia %short (i32.const 0) (i32.const 1)))
                    (table.init %t %long (i32.const 0) (i32.const 0) (i32.const 1))))
                """.trimIndent(),
            ),
        )
        val plan = GcPlan(Parser.parse(module))
        val body = Parser.parse(module).codeSection().functionBodies().single().instructions()
        val newElems = body.filter { it.opcode() == OpCode.ARRAY_NEW_ELEM }
        val tableInit = body.single { it.opcode() == OpCode.TABLE_INIT }
        // 式は 12 命令（end を除く）。基本の重み（シフトの分）は含めず、長さ 1 を超える 11 だけ
        assertEquals(11L, plan.elementFuel(newElems[0]))
        assertNull(plan.elementFuel(newElems[1]))
        assertEquals(11L, plan.elementFuel(tableInit))
    }

    @Test
    fun `structのバイト数は型ごとに覚えた値でも、毎回計算した値と同じで、structでない型は毎回断る`() {
        val module = Parser.parse(
            WitTool.wat(
                w(
                    """
                    (module
                      (type %f (func))
                      (rec (type %a (struct (field i32) (field anyref))) (type %b (struct)))
                      (type %c (array i8)))
                    """.trimIndent(),
                ),
            ),
        )
        val types = module.typeSection()
        val structs = GcCosts.StructBytes(types)
        for (idx in listOf(1L, 2L, 1L, 2L)) assertEquals(GcCosts.structBytes(types, idx), structs[idx])
        assertEquals(GcCosts.OBJECT_BYTES + 2 * GcCosts.REF_ELEMENT_BYTES + GcCosts.I31_BYTES, structs[1])
        for (idx in listOf(0L, 3L, 0L, 3L, 4L, -1L)) assertThrows<WasmLoadException>("型 $idx") { structs[idx] }
    }
}
