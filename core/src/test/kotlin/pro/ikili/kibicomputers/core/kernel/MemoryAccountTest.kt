package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.runtime.WasmMemory

/** RAM の勘定の「残す量」つきの確保と、残りの RAM（`kibi:computer/memory`。#408） */
class MemoryAccountTest {
    private val page = WasmMemory.PAGE_SIZE.toLong()

    @Test
    fun `マシンの勘定はkeepを見て、サーバー全体の勘定にはkeepをかけない`() {
        val total = MemoryAccount(2 * page)
        val machine = MemoryAccount(4, total)
        // マシンの残りは 4 ページ。1 ページ数えると 3 ページ残る
        assertFalse(machine.tryReserveBytes(page, 3 * page + 1))
        assertEquals(0L, machine.usedBytes)
        assertTrue(machine.tryReserveBytes(page, 3 * page))
        // サーバー全体の残りは 1 ページ。keep はマシンの残り（3 ページ）にだけかけるので数えられる
        assertTrue(machine.tryReserveBytes(page, 2 * page))
        assertEquals(2 * page, total.usedBytes)
        // サーバー全体で断られたら、マシンの分も戻す
        assertFalse(machine.tryReserveBytes(1, 0))
        assertEquals(2 * page, machine.usedBytes)
        machine.releaseBytes(2 * page)
        assertEquals(0L, total.usedBytes)
    }

    @Test
    fun `上限より大きいkeepと、2の63乗以上のkeepは断る`() {
        val machine = MemoryAccount(4)
        assertFalse(machine.tryReserveBytes(1, 4 * page + 1))
        assertFalse(machine.tryReserveBytes(1, -1L))
        assertFalse(machine.tryReserveBytes(1, Long.MIN_VALUE))
        assertTrue(machine.tryReserveBytes(page, 3 * page))
        assertEquals(page, machine.usedBytes)
    }

    @Test
    fun `プロセスの確保はプロセスとマシンのそれぞれでkeepを見る`() {
        val machine = MemoryAccount(4)
        val a = machine.process(2)
        val b = machine.process(8)
        // プロセスの上限は 2 ページ。1 ページ数えると 1 ページ残る
        assertFalse(a.tryReserveBytes(page, page + 1))
        assertTrue(a.tryReserveBytes(page, page))
        assertEquals(page, a.usedBytes)
        // b のプロセスの残りは 8 ページだが、マシンの残りは 3 ページ
        assertFalse(b.tryReserveBytes(page, 2 * page + 1))
        assertEquals(0L, b.usedBytes, "マシンで断られたら、プロセスの分も戻す")
        assertEquals(page, machine.usedBytes)
        assertTrue(b.tryReserveBytes(page, 2 * page))
        assertEquals(2 * page, machine.usedBytes)
    }

    @Test
    fun `残りのRAMはプロセスとマシンのそれぞれの残りのうち小さい方`() {
        val total = MemoryAccount(page)
        val machine = MemoryAccount(4, total)
        val a = machine.process(2)
        val b = machine.process(8)
        assertEquals(2 * page, a.remainingBytes)
        assertEquals(4 * page, b.remainingBytes, "サーバー全体の勘定（1 ページ）は入れない")
        assertTrue(a.tryReserveBytes(page / 2))
        assertEquals(page + page / 2, a.remainingBytes)
        assertEquals(3 * page + page / 2, b.remainingBytes)
        // マシンのほかの使い道（/tmp など）も、マシンの残りから引く
        assertTrue(machine.tryReserveBytes(page / 2))
        assertEquals(page, total.usedBytes)
        assertEquals(page + page / 2, a.remainingBytes)
        assertEquals(3 * page, b.remainingBytes)
    }
}
