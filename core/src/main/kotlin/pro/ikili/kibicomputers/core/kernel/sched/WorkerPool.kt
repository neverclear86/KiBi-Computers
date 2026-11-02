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
 * 上限を付けないのは、step の途中で燃料待ち・ホスト呼び出し待ちのワーカーが仕事を塞いでデッドロックしないため。
 *
 * step は許可（[PermitPool]）を受け取ってから渡すので、許可を持って動くワーカーは許可の数まで（#350）。
 * step の終わりで返した許可の次の step は、[offerContinuation] で同じワーカーの続きにする
 * （返した直後のワーカーはまだプールに戻っていないので、`execute` すると新しいスレッドができるため）。
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

    private val inWorker = ThreadLocal.withInitial { false }
    private val continuation = ThreadLocal<Runnable?>()

    /** step を動かしているワーカーの数（数え口） */
    val activeSteps: AtomicInteger = AtomicInteger()

    /** 渡した step の数（数え口） */
    val submitted: AtomicLong = AtomicLong()

    /** プールのスレッドの数（差し替えたときは -1） */
    val poolSize: Int get() = pool?.poolSize ?: -1

    /** step を渡す。ブロックしない。閉じた後なら、その場限りのスレッドで動かす（step は停止の印を見て終わる） */
    fun execute(task: Runnable) {
        submitted.incrementAndGet()
        val wrapped = Runnable { runLoop(task) }
        try {
            executor.execute(wrapped)
        } catch (e: RejectedExecutionException) {
            KernelThread("KiBi-worker-late-${counter.incrementAndGet()}", stack, wrapped).start()
        }
    }

    /**
     * 今の仕事が終わったら、続けて同じワーカーで [task] を動かす。ワーカーの中でない、またはもう続きがあるなら `false`
     * （呼んだ側が [execute] で渡す）。今の仕事の残りは、止まる待ちに入らないこと（step の終わりにだけ使う）
     */
    fun offerContinuation(task: Runnable): Boolean {
        if (!inWorker.get() || continuation.get() != null) return false
        submitted.incrementAndGet()
        continuation.set(task)
        return true
    }

    private fun runLoop(first: Runnable) {
        inWorker.set(true)
        var current: Runnable? = first
        try {
            while (current != null) {
                runOne(current)
                current = takeContinuation()
            }
        } finally {
            inWorker.set(false)
            // 例外で抜けたときも、置かれた続き（許可を持っている）を捨てない
            takeContinuation()?.let { execute(it) }
        }
    }

    private fun takeContinuation(): Runnable? = continuation.get()?.also { continuation.remove() }

    private fun runOne(task: Runnable) {
        activeSteps.incrementAndGet()
        KernelThread.markStep(true)
        try {
            task.run()
        } finally {
            KernelThread.markStep(false)
            activeSteps.decrementAndGet()
        }
    }

    fun shutdown() = executor.shutdown()

    @Throws(InterruptedException::class)
    fun awaitTermination(timeoutMillis: Long): Boolean = executor.awaitTermination(timeoutMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
}
