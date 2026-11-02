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

    // ---- 手で組み立てるモジュール（WasmBinary で書けない型・テーブル・タグ・要素） ----

    private fun bytes(vararg b: Int): ByteArray = ByteArray(b.size) { b[it].toByte() }

    private fun vec(items: List<ByteArray>): ByteArray = items.fold(WasmBinary.uleb(items.size.toLong())) { acc, b -> acc + b }

    private fun name(s: String): ByteArray = s.toByteArray().let { WasmBinary.uleb(it.size.toLong()) + it }

    /** ローカル変数のない関数の本体（[code] は `end` を含めない） */
    private fun body(code: ByteArray): ByteArray = (bytes(0) + code + bytes(0x0B)).let { WasmBinary.uleb(it.size.toLong()) + it }

    /** セクション（id と中身）を並べた順に書いたモジュール */
    private fun module(vararg sections: Pair<Int, ByteArray>): ByteArray {
        var out = bytes(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
        for ((id, content) in sections) out += bytes(id) + WasmBinary.uleb(content.size.toLong()) + content
        return out
    }

    /** モジュールのセクションの id と中身を、並びの順に読む */
    private fun sections(wasm: ByteArray): List<Pair<Int, ByteArray>> {
        val list = ArrayList<Pair<Int, ByteArray>>()
        var pos = 8
        while (pos < wasm.size) {
            val id = wasm[pos++].toInt()
            var size = 0
            var shift = 0
            while (true) {
                val b = wasm[pos++].toInt() and 0xFF
                size = size or ((b and 0x7F) shl shift)
                shift += 7
                if (b and 0x80 == 0) break
            }
            list += id to wasm.copyOfRange(pos, pos + size)
            pos += size
        }
        return list
    }

    /** `() -> i32` の型 */
    private val typeRunI32 = bytes(0x60, 0x00, 0x01, 0x7F)

    /**
     * 型付きの参照型（`(ref 0)`・`(ref null 0)`）を [where]（`type`・`global`・`table`）に持ち、`run() -> i32` を export するモジュール。
     * [import] なら `w.wait` を import し、[callWait] なら `run` がそれを呼ぶ（止まりうる関数になる）
     */
    private fun typedRefModule(where: String, import: Boolean, callWait: Boolean): ByteArray {
        val types = mutableListOf(typeRunI32)
        // (func (param (ref 0)))
        if (where == "type") types += bytes(0x60, 0x01, 0x64, 0x00, 0x00)
        val sections = mutableListOf(1 to vec(types))
        if (import) sections += 2 to vec(listOf(name("w") + name("wait") + bytes(0x00, 0x00)))
        sections += 3 to vec(listOf(bytes(0x00)))
        // (table 1 (ref null 0))
        if (where == "table") sections += 4 to vec(listOf(bytes(0x63, 0x00, 0x00, 0x01)))
        // (global (ref null 0) (ref.null 0))
        if (where == "global") sections += 6 to vec(listOf(bytes(0x63, 0x00, 0x00, 0xD0, 0x00, 0x0B)))
        val run = if (import) 1 else 0
        sections += 7 to vec(listOf(name("run") + bytes(0x00, run)))
        sections += 10 to vec(listOf(body(if (callWait) bytes(0x10, 0x00) else bytes(0x41, 0x01))))
        return module(*sections.toTypedArray())
    }

    @Test
    fun `型付きの参照型は、止まりうる関数がなければ通り、あれば断る`() {
        for (where in listOf("type", "global", "table")) {
            // 止まりうる import がない／あっても呼ぶ関数がない: 本体を書き換えずに通す
            for (import in listOf(false, true)) {
                val r = Asyncify.transform(typedRefModule(where, import, callWait = false), setOf("w.wait"))
                assertEquals(0, r.instrumented, where)
                assertEquals(r.codeBefore, r.codeAfter, where)
                run.endive.wasm.Parser.parse(r.bytes)
            }
            // 止まりうる関数がある: 型を追えないので断る
            val e = assertThrows<AsyncifyUnsupported>(where) { Asyncify.transform(typedRefModule(where, import = true, callWait = true), setOf("w.wait")) }
            assertTrue(e.message!!.contains("型付きの参照型") && e.message!!.contains("止まりうる関数があるので"), e.message)
        }
    }

    @Test
    fun `テーブルとタグのimportがあると、table_getとthrowの型はimportしたものから数える`() {
        // 型: 0 = () -> i32、1 = (i32 i32) -> ()（import するタグ）、2 = (i64) -> ()（定義するタグ）
        val types = vec(listOf(typeRunI32, bytes(0x60, 0x02, 0x7F, 0x7F, 0x00), bytes(0x60, 0x01, 0x7E, 0x00)))
        val imports = vec(
            listOf(
                name("w") + name("wait") + bytes(0x00, 0x00), // 関数 0
                name("env") + name("t") + bytes(0x01, 0x70, 0x00, 0x01), // テーブル 0（funcref）
                name("env") + name("e") + bytes(0x04, 0x00, 0x01), // タグ 0（i32 i32）
            ),
        )
        // run:
        //   i32.const 0  table.get 1（定義した externref のテーブル）  call wait  drop  drop
        //   block  call wait  i32.eqz  br_if 0  i64.const 5  throw 1（定義したタグ。引数は i64 の1つ）  end
        //   i32.const 7
        val code = bytes(
            0x41, 0x00, 0x25, 0x01, 0x10, 0x00, 0x1A, 0x1A,
            0x02, 0x40, 0x10, 0x00, 0x45, 0x0D, 0x00, 0x42, 0x05, 0x08, 0x01, 0x0B,
            0x41, 0x07,
        )
        val wasm = module(
            1 to types,
            2 to imports,
            3 to vec(listOf(bytes(0x00))),
            4 to vec(listOf(bytes(0x6F, 0x00, 0x01))), // テーブル 1（externref）
            13 to vec(listOf(bytes(0x00, 0x02))), // タグ 1（i64）
            7 to vec(listOf(name("run") + bytes(0x00, 0x01))),
            10 to vec(listOf(body(code))),
        )
        EndiveRuntime().load(wasm)
        val r = Asyncify.transform(wasm, setOf("w.wait"))
        assertEquals(1, r.instrumented)
        assertEquals(2, r.sites)
        // 検証が通る（呼び出しをまたいで退避した externref の型と、throw で取り出す値の数が合っている）
        assertTrue(EndiveRuntime().load(r.bytes).isAsyncified)
    }

    @Test
    fun `足したグローバルとexportのセクションは、startと要素のセクションより前に入れる`() {
        // 型: 0 = () -> i32、1 = () -> ()。関数: 0 = w.wait、1 = init（start）、2 = run（要素からも指す）
        val wasm = module(
            1 to vec(listOf(typeRunI32, bytes(0x60, 0x00, 0x00))),
            2 to vec(listOf(name("w") + name("wait") + bytes(0x00, 0x00))),
            3 to vec(listOf(bytes(0x01), bytes(0x00))),
            4 to vec(listOf(bytes(0x70, 0x00, 0x01))),
            8 to bytes(0x01),
            // (elem (i32.const 0) func 2)
            9 to vec(listOf(bytes(0x00, 0x41, 0x00, 0x0B, 0x01, 0x02))),
            // init: なにもしない。run: wait() + call_indirect (type 0) 0
            10 to vec(listOf(body(bytes()), body(bytes(0x10, 0x00, 0x41, 0x00, 0x11, 0x00, 0x00, 0x6A)))),
        )
        EndiveRuntime().load(wasm)
        val r = Asyncify.transform(wasm, setOf("w.wait"))
        assertEquals(1, r.instrumented)
        val out = sections(r.bytes)
        assertEquals(listOf(1, 2, 3, 4, 6, 7, 8, 9, 10), out.map { it.first })
        // 定義済みの関数の番号は、足した import 2つの分ずれる（init は 3、run は 4）
        assertArrayEquals(bytes(0x03), out.single { it.first == 8 }.second)
        assertArrayEquals(bytes(0x01, 0x00, 0x41, 0x00, 0x0B, 0x01, 0x04), out.single { it.first == 9 }.second)
        assertTrue(EndiveRuntime().load(r.bytes).isAsyncified)
    }
}
