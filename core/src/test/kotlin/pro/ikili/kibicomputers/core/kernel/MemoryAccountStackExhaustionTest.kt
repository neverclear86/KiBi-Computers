package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.runtime.endive.WrittenPagesMemory
import run.endive.wasm.types.MemoryLimits
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * ゲストの深い再帰でスタックが尽きる位置が、メモリの勘定の段（プロセス → マシン → サーバー全体の CAS）の途中に落ちても、
 * 勘定がずれないことを確かめる（#856）。
 *
 * 本番のカーネルのプロセスと同じく、サーバー全体の [MemoryAccount] → マシンの [MemoryAccount] → [ProcessMemory] →
 * [WrittenPagesMemory] を組み、256 KiB のスタックのスレッドで、1 段ごとに新しい 4 KiB の区画へ書く（初めて書いたときの勘定に入る）か、
 * `memory.grow` する再帰を、尽きる位置をずらしながら多数回動かす。毎回、尽きたら [WrittenPagesMemory.releaseAll]（インスタンスを閉じる
 * ときと同じ）で返し、プロセス・マシン・サーバー全体の使用量が 0 に戻ることを確かめる。
 * 合否は「ずれが 0」で決める（尽きる位置の分布はフレームの大きさで OS・JIT ごとに変わるので、数では決めない）
 */
class MemoryAccountStackExhaustionTest {
    private val stackBytes = 256L * 1024

    /** 1 段ごとにすること */
    private enum class Pattern {
        /** 新しい 4 KiB の区画に 1 バイト書く（`WrittenPagesMemory.commit` → `ProcessMemory.tryReserveBytes`） */
        WRITE,

        /** 1 ページ増やす（`WrittenPagesMemory.grow` → 端の区画と区画の表を 1 回で数える） */
        GROW,

        /**
         * [ProcessMemory.tryReserveBytes] を直接呼び、数えられたら呼び手の記録（[Reserved]）に足す
         * （`table.grow` を数える `BudgetedTable` と同じ形。呼び手の後処理が短いので、勘定の段の中で尽きやすい）
         */
        RESERVE,
    }

    /** [Pattern.RESERVE] の呼び手が数えたバイト数（閉じるときに返す分） */
    private class Reserved {
        var bytes = 0L
    }

    /** 尽きる位置をずらすための詰め物（[pad] 段だけ積んでから [body] を呼ぶ） */
    private fun padThen(pad: Int, body: () -> Long): Long = if (pad <= 0) body() else padThen(pad - 1, body) + 1

    private fun recurse(memory: WrittenPagesMemory, process: ProcessMemory, reserved: Reserved, pattern: Pattern, n: Int): Long {
        when (pattern) {
            Pattern.WRITE -> memory.writeByte(n * WrittenPagesMemory.CHUNK_SIZE, 1)
            Pattern.GROW -> check(memory.grow(1) >= 0) { "grow を断られた" }
            Pattern.RESERVE -> {
                check(process.tryReserveBytes(RESERVE_BYTES)) { "確保を断られた" }
                reserved.bytes += RESERVE_BYTES
            }
        }
        return recurse(memory, process, reserved, pattern, n + 1) + n
    }

    private class Tally {
        var rounds = 0
        var drifted = 0
        val tops = HashMap<String, Int>()
    }

    private fun sweep(pattern: Pattern, rounds: Int, initialPages: Int, maxPages: Int) = timeout(Duration.ofSeconds(240)) {
        var server = MemoryAccount(Long.MAX_VALUE / 4)
        var machine = MemoryAccount(1L shl 40, server)
        val tally = Tally()
        val drifts = ArrayList<String>()
        try {
            for (i in 0 until rounds) {
                val pad = (i * 7) % 211
                val process = machine.process(Int.MAX_VALUE / 2)
                val memory = WrittenPagesMemory(MemoryLimits(initialPages, maxPages), process)
                val reserved = Reserved()
                val failure = AtomicReference<Throwable>()
                val thread = Thread(null, {
                    try {
                        padThen(pad) { recurse(memory, process, reserved, pattern, 0) }
                    } catch (t: Throwable) {
                        failure.set(t)
                    }
                }, "memory-account-sweep", stackBytes)
                thread.start()
                thread.join()
                val error = failure.get()
                assertTrue(error is StackOverflowError, "スタックが尽きずに終わった（$i 回目）: $error")
                val top = error!!.stackTrace.firstOrNull()
                val key = top?.let { "${it.className.substringAfterLast('.')}.${it.methodName}" } ?: "?"
                tally.tops.merge(key, 1, Int::plus)
                // インスタンスを閉じたとき（finishLocked）と同じく、数えた分をまとめて返す
                memory.releaseAll()
                if (reserved.bytes > 0) process.releaseBytes(reserved.bytes)
                tally.rounds++
                if (process.usedBytes != 0L || machine.usedBytes != 0L || server.usedBytes != 0L) {
                    tally.drifted++
                    drifts += "$i 回目（pad $pad、尽きた位置 $key）: プロセス ${process.usedBytes}、マシン ${machine.usedBytes}、サーバー ${server.usedBytes}"
                    // 次の回は、ずれていない勘定で始める（どの段がずれたかだけを記録する）
                    server = MemoryAccount(Long.MAX_VALUE / 4)
                    machine = MemoryAccount(1L shl 40, server)
                }
            }
        } finally {
            println("#856 $pattern: ${tally.rounds} 回、ずれた ${tally.drifted} 回、尽きた位置 ${tally.tops}")
            drifts.take(10).forEach { println("#856 $pattern: $it") }
        }
        assertEquals(0, tally.drifted, "勘定がずれた: ${drifts.take(5)}")
    }

    @Test
    fun `1段ごとに新しい区画へ書く再帰で尽きる位置をずらしても、プロセス・マシン・サーバー全体の勘定はずれない`() =
        sweep(Pattern.WRITE, rounds = 400, initialPages = 1024, maxPages = 1024)

    @Test
    fun `1段ごとにmemory_growする再帰で尽きる位置をずらしても、プロセス・マシン・サーバー全体の勘定はずれない`() =
        sweep(Pattern.GROW, rounds = 150, initialPages = 1, maxPages = 65536)

    @Test
    fun `1段ごとに勘定を直接数える再帰で尽きる位置をずらしても、プロセス・マシン・サーバー全体の勘定はずれない`() =
        sweep(Pattern.RESERVE, rounds = 400, initialPages = 1, maxPages = 1)

    private companion object {
        const val RESERVE_BYTES = 64L
    }
}
