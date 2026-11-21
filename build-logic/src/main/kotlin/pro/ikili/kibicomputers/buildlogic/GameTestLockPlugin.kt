package pro.ikili.kibicomputers.buildlogic

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.build.event.BuildEventsListenerRegistry
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener
import org.gradle.tooling.events.task.TaskFinishEvent
import java.io.IOException
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/**
 * GameTest のサーバー（`runGameTestServer`・`runJarGameTestServer`・`runLoadTestServer`）を、マシンの中で同時に 1 本だけにする
 * プラグイン（`kibicomputers.gametest-lock`、#855）。ルートのプロジェクトに適用すると、全サブプロジェクトの該当タスクに掛かる。
 *
 * - 同じ Gradle の起動の中では、共有の [GameTestLockService]（`maxParallelUsages = 1`）で並びを止める
 *   （`org.gradle.parallel` で両バージョンの GameTest が同時に走らない）。
 * - 起動・worktree をまたぐ分は、Gradle のユーザーホームの `kibicomputers/locks/gametest.lock` の OS のロックで止める。
 *   持ち主（worktree・タスク・PID・時刻）は `gametest.owner` に書き、待つ側が 30 秒ごとにログへ出す。
 * - ロックはタスクの開始（`doFirst`）で取り、そのタスクが終わったとき（成功・失敗とも）と、ビルドの終わりに外す。
 *
 * 待ちの上限は既定で 90 分（`-PgameTestLockTimeoutMinutes=<分>`）。`-PgameTestLock=false` でロックを使わない。
 */
abstract class GameTestLockPlugin @Inject constructor(
    private val events: BuildEventsListenerRegistry,
) : Plugin<Project> {
    override fun apply(project: Project) {
        require(project == project.rootProject) { "kibicomputers.gametest-lock must be applied to the root project" }
        val locks = project.gradle.gradleUserHomeDir.resolve("kibicomputers/locks")
        val providers = project.providers
        val service = project.gradle.sharedServices.registerIfAbsent(SERVICE_NAME, GameTestLockService::class.java) {
            maxParallelUsages.set(1)
            parameters.lockFile.set(locks.resolve("gametest.lock"))
            parameters.ownerFile.set(locks.resolve("gametest.owner"))
            parameters.timeoutMinutes.set(
                providers.gradleProperty("gameTestLockTimeoutMinutes").map { it.toLong() }.orElse(DEFAULT_TIMEOUT_MINUTES),
            )
            parameters.enabled.set(providers.gradleProperty("gameTestLock").map { it.toBoolean() }.orElse(true))
        }
        events.onTaskCompletion(service)
        val rootDir = project.rootDir.absolutePath
        project.allprojects {
            tasks.named { it in TASK_NAMES }.configureEach {
                usesService(service)
                val path = this.path
                doFirst { service.get().acquire(path, rootDir) }
            }
        }
    }

    companion object {
        const val SERVICE_NAME: String = "kibicomputersGameTestLock"
        const val DEFAULT_TIMEOUT_MINUTES: Long = 90

        /** ロックを掛けるタスクの名前（ModDevGradle の run タスク）。 */
        @JvmField
        val TASK_NAMES: Set<String> = setOf("runGameTestServer", "runJarGameTestServer", "runLoadTestServer")
    }
}

/**
 * [GameTestLockPlugin] の共有のサービス。ビルドの間 1 つだけ作られ、ロックの持ち主（タスクのパス）を覚える。
 *
 * 同じ起動の中のタスクは `maxParallelUsages = 1` で直列になるので、前のタスクの終わりの通知（[onFinish]）より先に次のタスクが
 * [acquire] を呼ぶことがある。そのときは持ち主を付け替え、遅れて届いた前のタスクの通知では外さない。
 */
abstract class GameTestLockService : BuildService<GameTestLockService.Params>, OperationCompletionListener, AutoCloseable {
    interface Params : BuildServiceParameters {
        val lockFile: RegularFileProperty
        val ownerFile: RegularFileProperty
        val timeoutMinutes: Property<Long>
        val enabled: Property<Boolean>
    }

    private val logger = Logging.getLogger(GameTestLockService::class.java)
    private val lock = GameTestFileLock(
        parameters.lockFile.get().asFile.toPath(),
        parameters.ownerFile.get().asFile.toPath(),
    )
    private var holder: String? = null

    /**
     * [taskPath] のためにロックを取る。待っている間はモニターを持たない（ほかのタスクの終わりの通知を止めないため）。
     * このメソッドに同時に入るタスクは、`maxParallelUsages = 1` で 1 つだけ。
     */
    fun acquire(taskPath: String, rootDir: String) {
        if (!parameters.enabled.get()) {
            logger.lifecycle("GameTest lock is disabled (-PgameTestLock=false); $taskPath runs without waiting")
            return
        }
        val pid = ProcessHandle.current().pid()
        val owner = "$taskPath in $rootDir (Gradle pid $pid, since ${Instant.now()})"
        synchronized(this) {
            if (lock.isHeld) {
                // 同じ起動の前のタスクが持ったまま（終わりの通知がまだ）。直列なので、外さずに引き継ぐ。
                // 持ち主と保持時間の起点は取り直し、「after holding it for N s」が 1 タスク分になるようにする
                val previous = holder
                val held = lock.handOver(owner)
                holder = taskPath
                logger.lifecycle("GameTest lock released by $previous after holding it for ${held.seconds} s")
                logger.lifecycle("GameTest lock acquired by $taskPath after waiting 0 s (taken over within this build)")
                return
            }
        }
        val waited = try {
            lock.acquire(owner, Duration.ofMinutes(parameters.timeoutMinutes.get()), { logger.lifecycle(it) })
        } catch (e: IOException) {
            throw GradleException(e.message ?: "Failed to take the GameTest lock", e)
        }
        synchronized(this) { holder = taskPath }
        logger.lifecycle("GameTest lock acquired by $taskPath after waiting ${waited.seconds} s")
    }

    @Synchronized
    override fun onFinish(event: FinishEvent) {
        if (event is TaskFinishEvent && event.descriptor.taskPath == holder) release()
    }

    @Synchronized
    override fun close() {
        release()
    }

    private fun release() {
        val by = holder
        holder = null
        val held = lock.release() ?: return
        logger.lifecycle("GameTest lock released by $by after holding it for ${held.seconds} s")
    }
}
