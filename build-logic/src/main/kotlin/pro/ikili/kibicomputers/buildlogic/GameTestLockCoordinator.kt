package pro.ikili.kibicomputers.buildlogic

import org.gradle.api.GradleException
import java.io.IOException
import java.time.Duration
import java.time.Instant

/**
 * [GameTestLockService] の中身。ロックの持ち主（タスクのパス）を覚え、取る・引き継ぐ・外すを決める（#855）。
 * Gradle に依存しない形にしてあるので、単体テストで分岐を確かめられる。
 *
 * 同じ起動の中のタスクは `maxParallelUsages = 1` で直列になるので、前のタスクの終わりの通知（[onTaskFinished]）より先に次のタスクが
 * [acquire] を呼ぶことがある。そのときは持ち主を付け替え、遅れて届いた前のタスクの通知では外さない。
 *
 * @param enabled false なら（`-PgameTestLock=false`）何もせず、待たない
 * @param timeout 待ちの上限。超えたら [GradleException]
 */
class GameTestLockCoordinator(
    private val lock: GameTestFileLock,
    private val enabled: Boolean,
    private val timeout: Duration,
    private val log: (String) -> Unit,
    private val pollInterval: Duration = Duration.ofSeconds(2),
    private val reportInterval: Duration = Duration.ofSeconds(30),
    private val pid: Long = ProcessHandle.current().pid(),
) {
    private var holder: String? = null

    /** 今ロックを持っているタスクのパス（持っていなければ null）。 */
    @get:Synchronized
    val currentHolder: String?
        get() = holder

    /**
     * [taskPath] のためにロックを取る。待っている間はモニターを持たない（ほかのタスクの終わりの通知を止めないため）。
     * このメソッドに同時に入るタスクは、`maxParallelUsages = 1` で 1 つだけ。
     */
    fun acquire(taskPath: String, rootDir: String) {
        if (!enabled) {
            log("GameTest lock is disabled (-PgameTestLock=false); $taskPath runs without waiting")
            return
        }
        val owner = "$taskPath in $rootDir (Gradle pid $pid, since ${Instant.now()})"
        synchronized(this) {
            if (lock.isHeld) {
                // 同じ起動の前のタスクが持ったまま（終わりの通知がまだ）。直列なので、外さずに引き継ぐ。
                // 持ち主と保持時間の起点は取り直し、「after holding it for N s」が 1 タスク分になるようにする
                val previous = holder
                val held = lock.handOver(owner)
                holder = taskPath
                log("GameTest lock released by $previous after holding it for ${held.seconds} s")
                log("GameTest lock acquired by $taskPath after waiting 0 s (taken over within this build)")
                return
            }
        }
        val waited = try {
            lock.acquire(owner, timeout, log, pollInterval, reportInterval)
        } catch (e: IOException) {
            throw GradleException(e.message ?: "Failed to take the GameTest lock", e)
        }
        synchronized(this) { holder = taskPath }
        log("GameTest lock acquired by $taskPath after waiting ${waited.seconds} s")
    }

    /** タスク [taskPath] が終わった（成功・失敗とも）。それが今の持ち主なら外す。引き継がれたあとの、前のタスクの遅れた通知は無視する。 */
    @Synchronized
    fun onTaskFinished(taskPath: String) {
        if (taskPath == holder) release()
    }

    /** ビルドの終わり。持っていれば外す。 */
    @Synchronized
    fun close() {
        release()
    }

    private fun release() {
        val by = holder
        holder = null
        val held = lock.release() ?: return
        log("GameTest lock released by $by after holding it for ${held.seconds} s")
    }
}
