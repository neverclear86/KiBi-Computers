package pro.ikili.kibicomputers.core.runtime.endive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig
import pro.ikili.kibicomputers.core.runtime.WasmMemoryBudget
import pro.ikili.kibicomputers.core.runtime.WasmTableBudget
import run.endive.wasm.types.MemoryLimits
import run.endive.wasm.types.Table
import run.endive.wasm.types.TableLimits
import run.endive.wasm.types.ValType

/**
 * テーブル・メモリを作った後の登録（`created` への追加）が失敗したときに、数えた分を返すこと（#946）。
 *
 * 登録できないと、インスタンスを作る catch が数えた分を返せない（返す相手のリストに入っていない）ので、
 * `createTable`・`createMemory` が自分で返してから同じ例外を投げ直す。返し忘れ（RAM が残り続ける）も、
 * 二重に返す（勘定が負になる）のも、ここで検出する。
 */
class CreateRegistrationFailureTest {
    /** 本物（ProcessMemory）と同じく、返せるのは正のバイト数だけ。返しすぎは `check` で落ちる */
    private class Budget : WasmTableBudget {
        var used = 0L

        override fun tryReserveBytes(bytes: Long): Boolean = tryReserveBytes(bytes, 0)

        override fun tryReserveBytes(bytes: Long, keepBytes: Long): Boolean {
            used += bytes
            return true
        }

        override fun releaseBytes(bytes: Long) {
            require(bytes > 0) { "バイト数は正: $bytes" }
            used -= bytes
            check(used >= 0) { "返しすぎ: $used" }
        }
    }

    /** ページ数の勘定 */
    private class PageBudget : WasmMemoryBudget {
        var used = 0

        override fun tryReserve(pages: Int): Boolean {
            used += pages
            return true
        }

        override fun release(pages: Int) {
            require(pages > 0) { "ページ数は正: $pages" }
            used -= pages
            check(used >= 0) { "返しすぎ: $used" }
        }
    }

    /** 足そうとすると [failure] を投げるリスト */
    private class FailingList<E>(private val failure: Throwable) : ArrayList<E>() {
        override fun add(element: E): Boolean = throw failure
    }

    private fun funcTable() = Table(ValType.FuncRef, TableLimits(4, 16), emptyList())

    @Test
    fun `テーブルの登録が落ちたら、数えた分を返して同じ例外を投げる`() {
        val budget = Budget()
        val config = WasmInstanceConfig.builder().tableLimit(100, budget).build()
        val failure = IllegalStateException("登録の失敗")
        val e = assertThrows<IllegalStateException> { createTable(funcTable(), -1, config, FailingList(failure)) }
        assertSame(failure, e, "包まずに投げ直す")
        assertEquals(0L, budget.used)
    }

    @Test
    fun `テーブルを登録できたときは数えたままで、閉じたときに1回だけ返る`() {
        val budget = Budget()
        val config = WasmInstanceConfig.builder().tableLimit(100, budget).build()
        val created = ArrayList<BudgetedTable>()
        createTable(funcTable(), -1, config, created)
        assertEquals(1, created.size)
        assertTrue(budget.used > 0, "最初の 4 要素を数えた: ${budget.used}")
        created.single().releaseAll()
        created.single().releaseAll()
        assertEquals(0L, budget.used)
    }

    @Test
    fun `書き込んだ区画で数えるメモリの登録が落ちたら、区画の表の分を返して同じ例外を投げる`() {
        val budget = Budget()
        val config = WasmInstanceConfig.builder().writtenMemoryLimit(8, budget).build()
        val failure = OutOfMemoryError("登録の失敗")
        val e = try {
            createMemory(MemoryLimits(2, 8, false), config, FailingList(failure))
            null
        } catch (e: OutOfMemoryError) {
            // JUnit の assertThrows は OutOfMemoryError を投げ直すので、自分で捕まえる
            e
        }
        assertSame(failure, e, "包まずに投げ直す")
        assertEquals(0L, budget.used)
    }

    @Test
    fun `書き込んだ区画で数えるメモリを登録できたときは、区画の表を数えたままで、閉じたときに1回だけ返る`() {
        val budget = Budget()
        val config = WasmInstanceConfig.builder().writtenMemoryLimit(8, budget).build()
        val created = ArrayList<ReleasableMemory>()
        createMemory(MemoryLimits(2, 8, false), config, created)
        assertEquals(1, created.size)
        assertEquals(2 * WrittenPagesMemory.TABLE_BYTES_PER_PAGE, budget.used)
        created.single().releaseAll()
        created.single().releaseAll()
        assertEquals(0L, budget.used)
    }

    @Test
    fun `ページで数えるメモリの登録が落ちたら、最初のページを返して同じ例外を投げる`() {
        val budget = PageBudget()
        val config = WasmInstanceConfig.builder().memoryLimit(8, budget).build()
        val failure = IllegalStateException("登録の失敗")
        val e = assertThrows<IllegalStateException> { createMemory(MemoryLimits(3, 8, false), config, FailingList(failure)) }
        assertSame(failure, e, "包まずに投げ直す")
        assertEquals(0, budget.used)
    }

    @Test
    fun `ページで数えるメモリを登録できたときは数えたままで、閉じたときに1回だけ返る`() {
        val budget = PageBudget()
        val config = WasmInstanceConfig.builder().memoryLimit(8, budget).build()
        val created = ArrayList<ReleasableMemory>()
        createMemory(MemoryLimits(3, 8, false), config, created)
        assertEquals(1, created.size)
        assertEquals(3, budget.used)
        created.single().releaseAll()
        created.single().releaseAll()
        assertEquals(0, budget.used)
    }

    @Test
    fun `最初のページが0のメモリは、数えていないので登録が落ちても何も返さない`() {
        // 0 を返すと releaseBytes・release の require で IllegalArgumentException になり、元の例外を隠す
        val pages = PageBudget()
        val failure = IllegalStateException("登録の失敗")
        val e = assertThrows<IllegalStateException> {
            createMemory(MemoryLimits(0, 8, false), WasmInstanceConfig.builder().memoryLimit(8, pages).build(), FailingList(failure))
        }
        assertSame(failure, e)
        assertEquals(0, pages.used)

        val bytes = Budget()
        val e2 = assertThrows<IllegalStateException> {
            createMemory(MemoryLimits(0, 8, false), WasmInstanceConfig.builder().writtenMemoryLimit(8, bytes).build(), FailingList(failure))
        }
        assertSame(failure, e2)
        assertEquals(0L, bytes.used)
    }
}
