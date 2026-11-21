package pro.ikili.kibicomputers.buildlogic

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration

/**
 * GameTest のサーバーを、マシンの中で同時に 1 本だけにするためのファイルのロック（#855）。
 *
 * OS のロック（[FileChannel.tryLock]）を使うので、持っているプロセスが異常終了しても OS が外す（ファイルの有無では判定しない）。
 * Windows ではロックが強制で、ロックした領域は他のプロセスから読めないため、持ち主の情報は別のファイル（[ownerFile]）に書く。
 * 待っている側は、[reportInterval] ごとにその中身を出す。
 *
 * 同じ JVM の中で別の [FileChannel] が同じファイルをロックしていると [OverlappingFileLockException] になる
 * （前のビルドで外せなかったときなど）。これも「ほかが持っている」として待つ。
 */
class GameTestFileLock(
    private val lockFile: Path,
    private val ownerFile: Path,
    /** 経過時間を測る時計（テストで差し替える）。 */
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var channel: FileChannel? = null
    private var lock: FileLock? = null
    private var acquiredAtNanos: Long = 0

    /** 今このインスタンスがロックを持っているか。 */
    val isHeld: Boolean
        get() = lock?.isValid == true

    /**
     * ロックを取る。ほかが持っていれば、空くまで [pollInterval] ごとに取り直し、[reportInterval] ごとに [log] へ持ち主を出す。
     * [timeout] を過ぎても取れなければ [IOException] を投げる（持ち主の情報を含める）。
     *
     * @param owner 持ち主の情報として [ownerFile] に書く文字列（worktree のパス、タスク名、PID など）
     * @return 待った時間
     */
    @Throws(IOException::class, InterruptedException::class)
    fun acquire(
        owner: String,
        timeout: Duration,
        log: (String) -> Unit,
        pollInterval: Duration = Duration.ofSeconds(2),
        reportInterval: Duration = Duration.ofSeconds(30),
    ): Duration {
        check(!isHeld) { "the GameTest lock is already held by this instance" }
        Files.createDirectories(lockFile.toAbsolutePath().parent)
        val start = nanoTime()
        var lastReport = Long.MIN_VALUE
        while (true) {
            if (tryAcquire()) {
                acquiredAtNanos = nanoTime()
                writeOwner(owner)
                return Duration.ofNanos(acquiredAtNanos - start)
            }
            val now = nanoTime()
            val waited = Duration.ofNanos(now - start)
            if (waited >= timeout) {
                throw IOException(
                    "Timed out after ${waited.seconds} s waiting for the GameTest lock $lockFile. Held by: ${readOwner()}. " +
                        "Raise -PgameTestLockTimeoutMinutes, or run with -PgameTestLock=false to skip the lock.",
                )
            }
            if (lastReport == Long.MIN_VALUE || now - lastReport >= reportInterval.toNanos()) {
                lastReport = now
                log("Waiting for the GameTest lock $lockFile (${waited.seconds} s so far). Held by: ${readOwner()}")
            }
            Thread.sleep(pollInterval.toMillis())
        }
    }

    /** 1 回だけ取ってみる。取れたら true。 */
    @Throws(IOException::class)
    fun tryAcquire(): Boolean {
        check(!isHeld) { "the GameTest lock is already held by this instance" }
        Files.createDirectories(lockFile.toAbsolutePath().parent)
        val ch = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val l = try {
            ch.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        } catch (e: IOException) {
            ch.close()
            throw e
        }
        if (l == null) {
            ch.close()
            return false
        }
        channel = ch
        lock = l
        acquiredAtNanos = nanoTime()
        return true
    }

    /**
     * 持っているロックを、外さずに次の持ち主へ引き継ぐ（同じ起動の中で直列に並んだ次のタスクへ渡すとき）。
     * 持ち主のファイルを [owner] で書き直し、持っている時間の起点を取り直す。前の持ち主が持っていた時間を返す。
     */
    fun handOver(owner: String): Duration {
        check(isHeld) { "the GameTest lock is not held by this instance" }
        val now = nanoTime()
        val held = Duration.ofNanos(now - acquiredAtNanos)
        acquiredAtNanos = now
        writeOwner(owner)
        return held
    }

    /** ロックを外す。持っていなければ何もしない。持っていた時間を返す（持っていなければ null）。 */
    fun release(): Duration? {
        val l = lock ?: return null
        val held = Duration.ofNanos(nanoTime() - acquiredAtNanos)
        // 持ち主のファイルは、ロックを持っている間に消す（外したあとに消すと、次の持ち主が書いたものを消しうる）
        try {
            Files.deleteIfExists(ownerFile)
        } catch (_: IOException) {
            // 残っても、次の持ち主が上書きする
        }
        try {
            l.release()
        } catch (_: IOException) {
            // チャンネルを閉じれば外れる
        }
        try {
            channel?.close()
        } catch (_: IOException) {
        }
        lock = null
        channel = null
        return held
    }

    /** 持ち主のファイルの中身。なければ「unknown」。 */
    fun readOwner(): String = try {
        Files.readString(ownerFile, StandardCharsets.UTF_8).trim().ifEmpty { "unknown" }
    } catch (_: IOException) {
        "unknown"
    }

    private fun writeOwner(owner: String) {
        try {
            Files.writeString(ownerFile, owner + System.lineSeparator(), StandardCharsets.UTF_8)
        } catch (_: IOException) {
            // 持ち主の情報は待つ側への案内だけなので、書けなくてもロックは使える
        }
    }
}
