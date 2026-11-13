package pro.ikili.kibicomputers.core.log

import org.jetbrains.annotations.ApiStatus
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogManager
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.logging.SimpleFormatter

/** core のログを受け取る側（接続層）に渡すときのレベル（#673）。 */
@ApiStatus.Internal
public enum class CoreLogLevel {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    ;

    public companion object {
        /**
         * JUL のレベルを、しきい値（`intValue()`）で対応させる。独自のレベルにも効く。
         * SEVERE→ERROR、WARNING→WARN、INFO・CONFIG→INFO、FINE→DEBUG、FINER・FINEST→TRACE。
         */
        @JvmStatic
        public fun of(level: Level): CoreLogLevel {
            val value = level.intValue()
            return when {
                value >= Level.SEVERE.intValue() -> ERROR
                value >= Level.WARNING.intValue() -> WARN
                value >= Level.CONFIG.intValue() -> INFO
                value >= Level.FINE.intValue() -> DEBUG
                else -> TRACE
            }
        }

        /** [level] 以上を通す JUL のレベル */
        internal fun julThreshold(level: CoreLogLevel): Level = when (level) {
            TRACE -> Level.ALL
            DEBUG -> Level.FINE
            INFO -> Level.CONFIG
            WARN -> Level.WARNING
            ERROR -> Level.SEVERE
        }
    }
}

/**
 * core のログの行を1つずつ受け取る口（#673）。呼び出し元のスレッドのまま、同期で呼ばれる。
 * [message] は引数を埋め込んだ後の本文で、書式として扱わないこと。
 */
@ApiStatus.Internal
public fun interface CoreLogSink {
    public fun log(name: String, level: CoreLogLevel, message: String, thrown: Throwable?)
}

/**
 * core が `System.getLogger` で出すログを、接続層のロガー（SLF4J / log4j）に流す（#673）。
 *
 * core のロガーは JDK 既定の `System.LoggerFinder`（`java.util.logging`）に流れるので、
 * `kibicomputers` の JUL ロガーにハンドラーを付けて、受け取った行を [CoreLogSink] に渡す。
 * `System.LoggerFinder` の差し替え（log4j-jpl や `META-INF/services`）は、システムのクラスローダーからしか
 * 探されず、MOD の jar の中からは効かないので使わない。
 *
 * - 親（root の `ConsoleHandler`）には流さない（同じ行が標準エラーに二重に出ないように）。
 * - JUL の `LogManager` が JDK のものでない環境（log4j-jul などに差し替えた環境）では、何もしない。
 * - JUL のロガーは弱い参照でしか持たれないので、取り付けたロガーはここで強く持つ。
 */
@ApiStatus.Internal
public object CoreLogRouting {
    /** core のロガー名の根 */
    public const val ROOT: String = "kibicomputers"

    private val installed = ConcurrentHashMap<String, Installation>()

    /**
     * [root] 以下のロガーの [minLevel] 以上の行を [sink] に流す。
     *
     * @return 取り付けたら、外す口。既に取り付け済み、または JUL が JDK のものでなく取り付けなかったら `null`
     */
    @JvmStatic
    @JvmOverloads
    public fun install(sink: CoreLogSink, root: String = ROOT, minLevel: CoreLogLevel = CoreLogLevel.TRACE): AutoCloseable? {
        if (LogManager.getLogManager().javaClass != LogManager::class.java) return null
        val logger = Logger.getLogger(root)
        val installation = Installation(root, logger, sink)
        if (installed.putIfAbsent(root, installation) != null) return null
        return try {
            installation.attach(CoreLogLevel.julThreshold(minLevel))
            installation
        } catch (e: RuntimeException) {
            // 設定を変えられない環境（SecurityException など）。MOD の読み込みは止めない
            installed.remove(root, installation)
            null
        }
    }

    private class Installation(
        private val root: String,
        // 強い参照。手放すと JUL のロガーが回収され、子は黙って root の ConsoleHandler 行きに戻る
        private val logger: Logger,
        sink: CoreLogSink,
    ) : AutoCloseable {
        private val handler = SinkHandler(sink)
        private var previousLevel: Level? = null
        private var previousUseParent: Boolean = true

        fun attach(level: Level) {
            previousLevel = logger.level
            previousUseParent = logger.useParentHandlers
            logger.addHandler(handler)
            try {
                logger.level = level
                logger.useParentHandlers = false
            } catch (e: RuntimeException) {
                restore()
                throw e
            }
        }

        override fun close() {
            if (!installed.remove(root, this)) return
            restore()
        }

        private fun restore() {
            logger.removeHandler(handler)
            logger.level = previousLevel
            logger.useParentHandlers = previousUseParent
        }
    }

    private class SinkHandler(private val sink: CoreLogSink) : Handler() {
        private val formatter = SimpleFormatter()

        init {
            level = Level.ALL
        }

        override fun publish(record: LogRecord?) {
            if (record == null || !isLoggable(record)) return
            try {
                // getSourceClassName は呼ばない（呼び出し元を探すためにスタックを辿る）
                val params = record.parameters
                val message = if (params != null && params.isNotEmpty()) formatter.formatMessage(record) else record.message
                sink.log(record.loggerName ?: "", CoreLogLevel.of(record.level), message ?: "", record.thrown)
            } catch (e: Exception) {
                reportError(null, e, java.util.logging.ErrorManager.WRITE_FAILURE)
            }
        }

        override fun flush(): Unit = Unit

        override fun close(): Unit = Unit
    }
}
