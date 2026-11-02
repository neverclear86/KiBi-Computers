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
import pro.ikili.kibicomputers.core.runtime.WasmFuelHandler
import pro.ikili.kibicomputers.core.runtime.WasmFunctionType
import pro.ikili.kibicomputers.core.runtime.WasmInstance
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmLinkException
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.WasmValueType
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.runtime.fuel.FuelInjector
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
        assertWaitCycle(module)
    }

    /** `run` を呼んで `w.wait` で巻き戻し、巻き直して 42 が返ることを確かめる */
    private fun assertWaitCycle(module: pro.ikili.kibicomputers.core.runtime.WasmModule) {
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
        // 燃料を引くコードは if (state == NORMAL) の中に入るので、巻き直しの間は引かない（#341）。
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

    /**
     * `w.wait() -> i32` を呼んでから、その値の回数だけ回る loop（止まりうる関数 `run` の中）と、
     * 50 回まわる葉の関数 `leaf`（止まりうる関数を呼ばない）を通る `run() -> i32`。戻り値は 7
     */
    private fun loopModule(): ByteArray {
        val b = WasmBinary()
        val wait = b.importFunc("w", "wait", emptyList(), listOf(i32))
        b.mutableI64Global(0)
        // (local.get 0) が 0 になるまで 1 ずつ減らす loop
        val countDown = WasmBinary.code(0x02, 0x40, 0x03, 0x40, 0x20, 0, 0x45, 0x0D, 1, 0x20, 0, 0x41, 1, 0x6B, 0x21, 0, 0x0C, 0, 0x0B, 0x0B)
        val leaf = b.func(listOf(i32), listOf(i32), countDown + WasmBinary.code(0x41, 7))
        val run = b.func(
            emptyList(),
            listOf(i32),
            WasmBinary.code(0x10, wait, 0x21, 0) + countDown + WasmBinary.code(0x41, 50, 0x10, leaf),
            locals = listOf(i32),
        )
        b.exportFunc("run", run)
        return b.build()
    }

    /** 燃料切れの処理: 巻き戻せるところでは巻き戻し、巻き直しで着いたら [refill] を返す。巻き戻せないところでは [refill] を返す */
    private class FuelCounter(val refill: Long) : WasmFuelHandler {
        var blocking = 0
        var unwinds = 0
        var arrivals = 0

        override fun onFuelExhausted(instance: WasmInstance, remaining: Long): Long {
            blocking++
            return refill
        }

        override fun onFuelExhaustedUnwindable(instance: WasmInstance, remaining: Long): Long {
            if (instance.asyncState == WasmAsyncState.REWINDING) {
                arrivals++
                instance.asyncState = WasmAsyncState.NORMAL
                return instance.fuel
            }
            unwinds++
            instance.asyncState = WasmAsyncState.UNWINDING
            return remaining
        }
    }

    private fun loopConfig(handler: WasmFuelHandler, stack: Stack, initial: Long): WasmInstanceConfig = WasmInstanceConfig.builder()
        .hostFunction("w", "wait", WasmFunctionType.of(emptyList(), listOf(WasmValueType.I32))) { _, _ -> longArrayOf(100) }
        .asyncStack(stack)
        .fuel(initial, handler)
        .build()

    @Test
    fun `止まりうる関数の中の燃料切れだけを待ち点にし、巻き戻して巻き直すと続きから動く`() {
        val applied = Instrumentation.applyDetailed(loopModule(), setOf("w.wait"))
        assertTrue(applied.fuelYield)
        val r = Asyncify.transform(FuelInjector.inject(loopModule()).bytes, setOf("w.wait"), "kibi.fuel")
        // run の入口と loop の先頭の2つ（葉の関数の中のものはそのまま）
        assertEquals(2, r.yieldSites)
        assertEquals(1, r.instrumented)
        val module = EndiveRuntime().load(applied.bytes)
        assertTrue(module.isAsyncified && module.isFuelMetered)
        assertTrue(module.imports.none { it.module == WasmAsync.IMPORT_MODULE }, "${module.imports}")

        // run の loop の途中で燃料が切れるようにする（100 回まわると数百の燃料を使う）
        val handler = FuelCounter(refill = 1_000_000)
        val stack = Stack()
        module.instantiate(loopConfig(handler, stack, initial = 100)).use { instance ->
            instance.call("run")
            assertEquals(WasmAsyncState.UNWINDING, instance.asyncState)
            assertEquals(1, handler.unwinds)
            assertTrue(stack.values.isNotEmpty())
            instance.asyncState = WasmAsyncState.REWINDING
            instance.fuel = 1_000_000
            assertArrayEquals(longArrayOf(7), instance.call("run"))
            assertEquals(WasmAsyncState.NORMAL, instance.asyncState)
            assertEquals(1, handler.arrivals)
            assertTrue(stack.values.isEmpty())
        }

        // 葉の関数の中で切れたときは、巻き戻さない口が呼ばれる（毎回 1 だけ補充して、何度も切れるようにする）
        val leafOnly = FuelCounter(refill = 1)
        val noUnwind = object : WasmFuelHandler {
            override fun onFuelExhausted(instance: WasmInstance, remaining: Long): Long = leafOnly.onFuelExhausted(instance, remaining)

            override fun onFuelExhaustedUnwindable(instance: WasmInstance, remaining: Long): Long {
                leafOnly.unwinds++
                return 1
            }
        }
        module.instantiate(loopConfig(noUnwind, Stack(), initial = 1)).use { instance ->
            assertArrayEquals(longArrayOf(7), instance.call("run"))
        }
        assertTrue(leafOnly.blocking > 0, "葉の関数で切れていない: blocking=${leafOnly.blocking} unwinds=${leafOnly.unwinds}")
        assertTrue(leafOnly.unwinds > 0, "止まりうる関数で切れていない")
    }

    @Test
    fun `kibi_async_fuelは予約した名前で、変換して燃料も計測したモジュールだけが持てる`() {
        val importsFuel = WasmBinary().apply {
            importFunc(WasmAsync.IMPORT_MODULE, WasmAsync.FUEL, listOf(WasmBinary.I64), listOf(WasmBinary.I64))
            mutableI64Global(0)
            exportFunc("f", func(emptyList(), emptyList(), ByteArray(0)))
        }.build()
        assertThrows<WasmLoadException> { Asyncify.transform(importsFuel, emptySet()) }
        assertThrows<WasmLoadException> { EndiveRuntime().load(importsFuel) }
    }

    @Test
    fun `止まりうる関数がないモジュールは、セクションがなくても本体を書き換えずに変換する`() {
        // 型・import・グローバル・export のどのセクションもない（ヘッダーだけ）
        val header = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
        val empty = Asyncify.transform(header, WasiP1.WAIT_IMPORTS)
        assertEquals(0, empty.functions)
        assertTrue(EndiveRuntime().load(empty.bytes).isAsyncified)

        // グローバル・export のセクションがなく、止まりうる import を使わない関数だけ
        val plain = WasmBinary().apply { func(listOf(i32), listOf(i32), WasmBinary.code(0x20, 0, 0x41, 1, 0x6A)) }.build()
        val r = Asyncify.transform(plain, setOf("w.wait"))
        assertEquals(1, r.functions)
        assertEquals(0, r.instrumented)
        assertEquals(r.codeBefore, r.codeAfter)
        assertTrue(EndiveRuntime().load(r.bytes).isAsyncified)
    }

    @Test
    fun `グローバルのセクションがなくても、止まりうる関数を変換して巻き戻せる`() {
        val b = WasmBinary()
        val wait = b.importFunc("w", "wait", emptyList(), listOf(i32))
        b.exportFunc("run", b.func(emptyList(), listOf(i32), WasmBinary.code(0x10, wait, 0x41, 1, 0x6A)))
        val r = Asyncify.transform(b.build(), setOf("w.wait"))
        assertEquals(1, r.instrumented)
        assertWaitCycle(EndiveRuntime().load(r.bytes))
    }

    @Test
    fun `関数でないimportがあっても変換し、グローバルの番号はimportしたものから数える`() {
        val b = WasmBinary()
        b.importMemory("env", "memory", 1)
        val g = b.importGlobal("env", "g", i32)
        val wait = b.importFunc("w", "wait", emptyList(), listOf(i32))
        b.mutableI64Global(0)
        // global.get g + wait()
        b.exportFunc("run", b.func(emptyList(), listOf(i32), WasmBinary.code(0x23, g, 0x10, wait, 0x6A)))
        val r = Asyncify.transform(b.build(), setOf("w.wait"))
        assertEquals(1, r.instrumented)
        // 状態のグローバルは、import した1つと定義した1つの後（番号 2）
        val parsed = run.endive.wasm.Parser.parse(r.bytes).exportSection()
        val state = (0 until parsed.exportCount()).map(parsed::getExport).single { it.name() == WasmAsync.STATE_EXPORT }
        assertEquals(2, state.index())
        // 検証が通る（変換したコードのグローバルの型も合っている）
        val module = EndiveRuntime().load(r.bytes)
        assertTrue(module.isAsyncified)
    }

    @Test
    fun `断る理由に、どの関数のどの機能かが入る`() {
        val b = WasmBinary()
        val wait = b.importFunc("w", "wait", emptyList(), listOf(i32))
        b.exportFunc("run", b.func(emptyList(), listOf(i32), WasmBinary.code(0x12, wait)))
        val e = assertThrows<AsyncifyUnsupported> { Asyncify.transform(b.build(), setOf("w.wait")) }
        assertTrue(e.message!!.contains("止まりうる関数 #1（export run）") && e.message!!.contains("末尾呼び出し"), e.message)
    }
}
