package pro.ikili.kibicomputers.core.kernel.sched

import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * カーネルのスレッド（ブロッキング型の専用スレッド、ワーカー、ディスパッチャー）。プラットフォームスレッド・daemon・スタックの大きさを指定する。
 */
internal class KernelThread(name: String, stackBytes: Long, task: Runnable) : Thread(null, task, name, stackBytes) {
    init {
        isDaemon = true
    }

    companion object {
        private val inStep = ThreadLocal.withInitial { false }

        /** 今のスレッドがカーネルのスレッドか（テスト用に差し替えたワーカーで step を動かしているときも含む） */
        fun isCurrent(): Boolean = currentThread() is KernelThread || inStep.get()

        internal fun markStep(value: Boolean) = inStep.set(value)
    }
}

/**
 * 待機点型のプロセスの step（インスタンスを作って `start`、または `resume`）を動かすワーカーのプール。
 *
 * 必要に応じてスレッドを作り、仕事のないスレッドは [idleMillis] で終える（コア 0、上限なし、`SynchronousQueue`）。
 * 上限を付けないのは、step の途中で燃料待ち・許可待ちのワーカーが仕事を塞いでデッドロックしないため。
 * テストでは [override] で1本だけのワーカーなどに差し替える。
 */
internal class WorkerPool(stackBytes: Long, idleMillis: Long, override: ExecutorService?) {
    private val counter = AtomicInteger()
    private val stack = stackBytes

    private val factory = ThreadFactory { r -> KernelThread("KiBi-worker-${counter.incrementAndGet()}", stack, r) }

    private val pool: ThreadPoolExecutor? = if (override == null) {
        ThreadPoolExecutor(0, Int.MAX_VALUE, idleMillis, TimeUnit.MILLISECONDS, SynchronousQueue(), factory)
    } else {
        null
    }

    private val executor: ExecutorService = override ?: pool!!

    /** step を動かしているワーカーの数（数え口） */
    val activeSteps: AtomicInteger = AtomicInteger()

    /** 渡した step の数（数え口） */
    val submitted: AtomicLong = AtomicLong()

    /** プールのスレッドの数（差し替えたときは -1） */
    val poolSize: Int get() = pool?.poolSize ?: -1

    /** step を渡す。ブロックしない。閉じた後なら、その場限りのスレッドで動かす（step は停止の印を見て終わる） */
    fun execute(task: Runnable) {
        submitted.incrementAndGet()
        val wrapped = Runnable {
            activeSteps.incrementAndGet()
            KernelThread.markStep(true)
            try {
                task.run()
            } finally {
                KernelThread.markStep(false)
                activeSteps.decrementAndGet()
            }
        }
        try {
            executor.execute(wrapped)
        } catch (e: RejectedExecutionException) {
            KernelThread("KiBi-worker-late-${counter.incrementAndGet()}", stack, wrapped).start()
        }
    }

    fun shutdown() = executor.shutdown()

    @Throws(InterruptedException::class)
    fun awaitTermination(timeoutMillis: Long): Boolean = executor.awaitTermination(timeoutMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
}
