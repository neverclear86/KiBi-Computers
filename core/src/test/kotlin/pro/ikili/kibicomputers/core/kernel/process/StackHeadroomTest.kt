package pro.ikili.kibicomputers.core.kernel.process

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.sched.KernelThread
import java.util.concurrent.atomic.AtomicInteger

/** [StackHeadroom]（#828） */
class StackHeadroomTest {
    /** 256 KiB のスタックのスレッドで、[frames] があれば 1 段ごとにその段数の確かめをしながら、尽きるまで再帰した深さ */
    private fun depth(frames: Int?): Int {
        val depth = AtomicInteger()
        fun recurse(n: Int): Int {
            depth.set(n)
            if (frames != null) StackHeadroom.ensure(frames)
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
        return depth.get()
    }

    @Test
    fun `確かめの分だけ、尽きる深さが浅くなる（JIT が再帰をたたんでいない）`() {
        // 温めてから、3 通りを交互に何回か測り、それぞれの最大を比べる。
        // 全体のテストで CPU が混むと、JIT のコンパイルが測定の途中で終わり、インタプリタのフレーム（大きい）で測った回が混ざって浅くなる。
        // 最大を取れば、コンパイル後の（フレームが小さい）回どうしの比べになる
        repeat(3) { depth(null); depth(StackHeadroom.HOST_CALL_FRAMES); depth(StackHeadroom.FUEL_YIELD_FRAMES) }
        var plain = 0
        var host = 0
        var fuel = 0
        repeat(15) {
            plain = maxOf(plain, depth(null))
            host = maxOf(host, depth(StackHeadroom.HOST_CALL_FRAMES))
            fuel = maxOf(fuel, depth(StackHeadroom.FUEL_YIELD_FRAMES))
        }
        assertTrue(host < plain, "host $host, plain $plain")
        assertTrue(fuel < host, "fuel $fuel, host $host")
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
