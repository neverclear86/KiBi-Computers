package pro.ikili.kibicomputers.core.persist

import org.jetbrains.annotations.ApiStatus
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 保存の仕事（世代のファイルの符号化と書き込み・掃除・ディレクトリの削除）を順に走らせる口（#84、ADR 0084）。
 * 入れた順に走らせる（同じ ID の順序は列の順で守る）。
 *
 * テストは呼んだスレッドでその場で走らせる [DIRECT] や、手で進める実装を使う。サーバーは [SaveWriter]（スレッド1本）を使う。
 */
@ApiStatus.Internal
public interface SaveExecutor : AutoCloseable {
    /**
     * [task] を列に入れる。[id] は順序の単位（今は1本の列。振り分けに使う）。
     * 閉じた後なら走らせずに `false`（呼び出し側が失敗で返す）
     */
    public fun execute(id: UUID, task: Runnable): Boolean

    /** 入れた仕事がすべて終わるまで、最大 [timeoutMillis] ミリ秒待つ。終われば `true` */
    @Throws(InterruptedException::class)
    public fun flush(timeoutMillis: Long): Boolean

    /** 新しい仕事を断り、入っている仕事を終えてスレッドを止める（最大 [timeoutMillis] ミリ秒待つ）。終われば `true` */
    public fun close(timeoutMillis: Long): Boolean

    /** [close]（上限 30 秒） */
    override fun close() {
        close(MachineSaveConfig.DEFAULT_WRITER_FLUSH_MILLIS)
    }

    public companion object {
        /** 呼んだスレッドでその場で走らせる（閉じない。テストと、スレッドを使わない置き場の既定） */
        @JvmField
        public val DIRECT: SaveExecutor = object : SaveExecutor {
            override fun execute(id: UUID, task: Runnable): Boolean {
                task.run()
                return true
            }

            override fun flush(timeoutMillis: Long): Boolean = true

            override fun close(timeoutMillis: Long): Boolean = true

            override fun toString(): String = "SaveExecutor.DIRECT"
        }
    }
}

/**
 * 書き込みのスレッド1本（#84、ADR 0084）。デーモンのスレッドで、入れた順に仕事を走らせる。
 * 仕事の例外は捕まえて warn し、スレッドは止めない（置き場の仕事は自分で例外を捕まえて失敗の完了にするので、ここに来るのは想定外のもの）。
 *
 * @param threadName スレッドの名前
 */
@ApiStatus.Internal
public class SaveWriter @JvmOverloads constructor(threadName: String = DEFAULT_THREAD_NAME) : SaveExecutor {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val queue = ArrayDeque<Runnable>()

    /** 仕事を走らせている */
    private var running = false
    private var closed = false

    private val thread = Thread(::loop, threadName).apply {
        isDaemon = true
        start()
    }

    override fun execute(id: UUID, task: Runnable): Boolean = lock.withLock {
        if (closed) return false
        queue.addLast(task)
        changed.signalAll()
        true
    }

    @Throws(InterruptedException::class)
    override fun flush(timeoutMillis: Long): Boolean = lock.withLock {
        var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis.coerceAtLeast(0))
        while (queue.isNotEmpty() || running) {
            if (Thread.currentThread() === thread) return false
            if (remaining <= 0) return false
            remaining = changed.awaitNanos(remaining)
        }
        true
    }

    override fun close(timeoutMillis: Long): Boolean {
        lock.withLock {
            closed = true
            changed.signalAll()
        }
        if (Thread.currentThread() === thread) return false
        try {
            thread.join(timeoutMillis.coerceAtLeast(1))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return !thread.isAlive
    }

    /** 列に残っている仕事の数（走らせている仕事は含めない。数え口） */
    public val queued: Int get() = lock.withLock { queue.size }

    private fun loop() {
        while (true) {
            val task = lock.withLock {
                while (queue.isEmpty() && !closed) {
                    try {
                        changed.await()
                    } catch (_: InterruptedException) {
                        // 閉じるまで止めない（割り込みは無視する）
                    }
                }
                if (queue.isEmpty()) return
                running = true
                queue.removeFirst()
            }
            try {
                task.run()
            } catch (e: Throwable) {
                LOG.log(System.Logger.Level.WARNING, "A save task failed unexpectedly", e)
            } finally {
                lock.withLock {
                    running = false
                    changed.signalAll()
                }
            }
        }
    }

    override fun toString(): String = "SaveWriter(${thread.name})"

    public companion object {
        /** スレッドの既定の名前 */
        public const val DEFAULT_THREAD_NAME: String = "KiBi Computers save writer"

        private val LOG: System.Logger = System.getLogger("kibicomputers.persist")
    }
}
