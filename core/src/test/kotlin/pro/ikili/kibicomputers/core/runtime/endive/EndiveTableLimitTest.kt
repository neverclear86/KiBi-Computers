package pro.ikili.kibicomputers.core.runtime.endive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import pro.ikili.kibicomputers.core.kernel.MemoryAccount
import pro.ikili.kibicomputers.core.runtime.WasmBinary
import pro.ikili.kibicomputers.core.runtime.WasmBinary.Companion.I32
import pro.ikili.kibicomputers.core.runtime.WasmBinary.Companion.code
import pro.ikili.kibicomputers.core.runtime.WasmInstance
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmLinkException
import pro.ikili.kibicomputers.core.runtime.WasmModule

/** テーブルの要素数の上限と、要素の分を RAM に数えること（#361） */
class EndiveTableLimitTest {
    /** funcref のテーブル（最初は [initial] 要素、上限の宣言なし）と、grow(n)・size() を export するモジュール */
    private fun module(compile: Boolean, initial: Int = 1): WasmModule {
        val wasm = WasmBinary().run {
            table(initial)
            // grow(n) = table.grow 0 (ref.null func, n)
            exportFunc("grow", func(listOf(I32), listOf(I32), code(0xd0, 0x70, 0x20, 0, 0xfc, 0x0f, 0)))
            // size() = table.size 0
            exportFunc("size", func(emptyList(), listOf(I32), code(0xfc, 0x10, 0)))
            build()
        }
        return EndiveRuntime(compile).load(wasm)
    }

    private fun WasmInstance.grow(n: Int): Int = call("grow", n.toLong()).single().toInt()

    private fun WasmInstance.size(): Int = call("size").single().toInt()

    private fun config(maxElements: Int, account: MemoryAccount, processPages: Int = account.limitPages): WasmInstanceConfig =
        WasmInstanceConfig.builder().apply { tableLimit(maxElements, account.process(processPages)) }.build()

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `上限を超えるtable_growは-1を返し、RAMの勘定は増えない`(compile: Boolean) {
        val account = MemoryAccount(16)
        module(compile).instantiate(config(100, account)).use { inst ->
            val initialBytes = account.usedBytes
            assertTrue(initialBytes > 0, "最初の要素の分も数える")
            val perElement = initialBytes

            assertEquals(1, inst.grow(9))
            assertEquals(10L * perElement, account.usedBytes, "増やした要素の分を数える")

            assertEquals(-1, inst.grow(91), "上限 100 を超える")
            assertEquals(-1, inst.grow(-1), "符号なしで 2^32-1")
            assertEquals(10, inst.size())
            assertEquals(10L * perElement, account.usedBytes, "失敗した grow は数えない")

            assertEquals(10, inst.grow(90), "ちょうど上限まで")
            assertEquals(100L * perElement, account.usedBytes)
            assertEquals(0, account.usedPages, "線形メモリのページには入らない")
        }
        assertEquals(0L, account.usedBytes, "閉じたら返す")
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `RAMが足りないtable_growは-1を返し、勘定は変わらない`(compile: Boolean) {
        // RAM は 1 ページ（64 KiB）。要素の上限は大きくして、RAM で断られるようにする
        val account = MemoryAccount(1)
        module(compile).instantiate(config(1_000_000, account)).use { inst ->
            val before = account.usedBytes
            assertEquals(-1, inst.grow(100_000))
            assertEquals(1, inst.size())
            assertEquals(before, account.usedBytes)
            // 少しなら増やせる
            assertEquals(1, inst.grow(10))
        }
        assertEquals(0L, account.usedBytes)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `プロセスの上限にもテーブルの分を数える`(compile: Boolean) {
        val account = MemoryAccount(16)
        val process = account.process(1)
        val config = WasmInstanceConfig.builder().apply { tableLimit(1_000_000, process) }.build()
        module(compile).instantiate(config).use { inst ->
            assertEquals(-1, inst.grow(100_000), "プロセスの上限（1 ページ）を超える")
            assertEquals(1, inst.grow(10))
            assertEquals(process.usedBytes, account.usedBytes)
        }
        assertEquals(0L, process.usedBytes)
        assertEquals(0L, account.usedBytes)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `最初の要素数が上限を超えるとインスタンスを作れず、勘定は戻る`(compile: Boolean) {
        val account = MemoryAccount(16)
        assertThrows<WasmLinkException> { module(compile, initial = 20).instantiate(config(10, account)) }
        assertEquals(0L, account.usedBytes)
        // RAM が足りないときも同じ
        val small = MemoryAccount(1)
        assertThrows<WasmLinkException> { module(compile, initial = 50_000).instantiate(config(100_000, small)) }
        assertEquals(0L, small.usedBytes)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `growしても同じモジュールから作る次のインスタンスの大きさは変わらない`(compile: Boolean) {
        val account = MemoryAccount(16)
        val module = module(compile)
        module.instantiate(config(100, account)).use { assertEquals(1, it.grow(50)) }
        module.instantiate(config(100, account)).use { inst ->
            assertEquals(1, inst.size())
            assertEquals(1, inst.grow(99), "上限まで増やせる")
        }
        assertEquals(0L, account.usedBytes)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `上限を設定しなければ数えない`(compile: Boolean) {
        module(compile).instantiate(WasmInstanceConfig.EMPTY).use { inst ->
            assertEquals(1, inst.grow(1000))
            assertEquals(1001, inst.size())
        }
    }
}
