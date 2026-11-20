package pro.ikili.kibicomputers.neoforge.gametest

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 負荷試験の間に、メインスレッドが書いたログの行をロガーとレベルごとに数える（#834）。
 *
 * log4j の root の設定（`LoggerConfig`）にアペンダーを付ける（AGENTS.md「つまずきやすい点」。`Logger.addAppender` だと、
 * その名前の行がファイルに出なくなる）。数えるのは [threadName] のスレッドの行だけで、ファイルへの書き込みは変えない
 * （ほかのアペンダーはそのまま）。数えるのはロガーの名前とレベルの組の数え口を1つ足すだけで、台数によらない。
 * [stop] で外す。
 */
internal class MainThreadLogCounter(private val threadName: String) {
    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    @Volatile
    private var counting = false

    private val appender = object : AbstractAppender(APPENDER_NAME, null, null, true, Property.EMPTY_ARRAY) {
        override fun append(event: LogEvent) {
            if (!counting || event.threadName != threadName) return
            counts.computeIfAbsent("${event.level} ${event.loggerName}") { AtomicInteger() }.incrementAndGet()
        }
    }

    private var installed = false

    fun start() {
        if (installed) return
        val context = LogManager.getContext(false) as? LoggerContext ?: return
        appender.start()
        context.configuration.rootLogger.addAppender(appender, null, null)
        context.updateLoggers()
        installed = true
    }

    /** 数えはじめる（それまでの数は捨てる） */
    fun begin() {
        counts.clear()
        counting = true
    }

    /** 数えるのをやめ、ここまでの数（「レベル ロガー名」→ 行数。多い順）を返す */
    fun end(): List<Pair<String, Int>> {
        counting = false
        return counts.map { it.key to it.value.get() }.sortedByDescending { it.second }
    }

    fun stop() {
        counting = false
        if (!installed) return
        installed = false
        val context = LogManager.getContext(false) as? LoggerContext ?: return
        context.configuration.rootLogger.removeAppender(APPENDER_NAME)
        context.updateLoggers()
        appender.stop()
    }

    companion object {
        private const val APPENDER_NAME = "KiBiLoadTestMainThreadLogCounter"
    }
}
