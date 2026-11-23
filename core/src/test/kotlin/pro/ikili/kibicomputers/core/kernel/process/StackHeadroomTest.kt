package pro.ikili.kibicomputers.core.kernel.process

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.sched.KernelThread

/** [StackHeadroom]（#828） */
class StackHeadroomTest {
    /**
     * 256 KiB のスタックのスレッドで、1 回の再帰の中で、各段で「[StackHeadroom.HOST_CALL_FRAMES] 段」「[StackHeadroom.FUEL_YIELD_FRAMES] 段」の
     * 確かめを試し、初めて断られた深さと、実際にスタックが尽きた深さを返す（順に fuel・host・尽きた深さ）。
     *
     * 別々の再帰の深さを比べると、JIT のコンパイルが途中で終わった回（インタプリタのフレームは大きい）が混ざって順序が入れ替わる。
     * 1 回の再帰の中なら、残りのスタックは深くなるほど単調に減るので、必要量が大きい確かめほど先に断られる（JIT の状態に頼らない）
     */
    private fun firstRefusals(): Triple<Int, Int, Int> {
        var fuel = -1
        var host = -1
        var last = 0
        fun recurse(n: Int): Int {
            last = n
            if (fuel < 0) {
                try {
                    StackHeadroom.ensure(StackHeadroom.FUEL_YIELD_FRAMES)
                } catch (_: StackOverflowError) {
                    fuel = n
                }
            }
            if (host < 0) {
                try {
                    StackHeadroom.ensure(StackHeadroom.HOST_CALL_FRAMES)
                } catch (_: StackOverflowError) {
                    host = n
                }
            }
            return recurse(n + 1) + 1
        }
        val t = KernelThread("KiBi-test-headroom", 256L * 1024) {
            try {
                recurse(0)
            } catch (_: StackOverflowError) {
            }
        }
        t.start()
        t.join()
        return Triple(fuel, host, last)
    }

    @Test
    fun `確かめの分だけ、尽きる深さが浅くなる（大きい確かめほど先に断られる）`() {
        repeat(5) {
            val (fuel, host, last) = firstRefusals()
            assertTrue(fuel in 0 until host, "fuel $fuel, host $host")
            assertTrue(host in 0 until last, "host $host, last $last")
        }
    }

    @Test
    fun `余裕があれば何もせず、足りなければStackOverflowErrorを投げる`() {
        StackHeadroom.ensure(StackHeadroom.FUEL_HANDLER_FRAMES)
        val thrown = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val t = KernelThread("KiBi-test-headroom", 256L * 1024) {
            thrown.set(runCatching { StackHeadroom.ensure(1_000_000) }.exceptionOrNull())
        }
        t.start()
        t.join()
        assertTrue(thrown.get() is StackOverflowError, "${thrown.get()}")
    }
}
