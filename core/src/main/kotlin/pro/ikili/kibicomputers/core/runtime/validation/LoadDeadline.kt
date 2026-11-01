package pro.ikili.kibicomputers.core.runtime.validation

import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import java.lang.management.ManagementFactory
import java.lang.management.ThreadMXBean

/**
 * モジュールの検証にかけてよい時間（#40）。作ったスレッドで [check] を呼ぶ。
 *
 * 時間は、作ったスレッドの CPU 時間で測る（測れない JVM では経過時間）。経過時間だと、サーバーが混んでいて
 * スレッドが待たされただけでも断ってしまうため。
 *
 * ランタイム（Endive）のパースと検証は途中で止められないので、その間は [check] を呼べない。
 * そこにかかる時間は、[ModuleLimits] の大きさの上限で抑え、終わった後の [check] で超えていれば断る。
 */
internal class LoadDeadline(private val limitNanos: Long) {
    private val clock: () -> Long = cpuClock() ?: System::nanoTime
    private val start = clock()

    /** 使った時間（ナノ秒） */
    val elapsedNanos: Long get() = clock() - start

    /** 上限を超えていたら [WasmLoadException] を投げる。[phase] はメッセージに入れる、今していること */
    @Throws(WasmLoadException::class)
    fun check(phase: String) {
        val elapsed = elapsedNanos
        if (elapsed > limitNanos) {
            throw WasmLoadException("WASM の検証が時間の上限（${limitNanos / 1_000_000} ms）を超えた（$phase、${elapsed / 1_000_000} ms）")
        }
    }

    private companion object {
        /** このスレッドの CPU 時間を測る時計。測れなければ `null` */
        fun cpuClock(): (() -> Long)? = try {
            val bean: ThreadMXBean = ManagementFactory.getThreadMXBean()
            if (bean.isCurrentThreadCpuTimeSupported && bean.isThreadCpuTimeEnabled) {
                { bean.currentThreadCpuTime }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } catch (e: LinkageError) {
            // java.management が無い実行環境
            null
        }
    }
}
