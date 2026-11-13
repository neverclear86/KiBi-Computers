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
 * [CoreLogRouting.install] で取り付けた経路（#673）。閉じると取り付けを解き、JUL の設定を元に戻す。
 */
@ApiStatus.Internal
public interface CoreLogRoute : AutoCloseable {
    /**
     * ロガー名ごとの、sink に流すレベルを置き換える。JUL のロガーのレベルにそのまま写すので、
     * 名前の木の継承（いちばん近い祖先の設定が効く）は log4j の `LoggerConfig` と同じになる。
     *
     * - 取り付けた根の名前が [levels] になければ、[CoreLogRouting.install] の `minLevel` を使う。
     * - 根の木の外の名前は無視する。
     * - 前に渡して今回なくなった子の名前は、取り付ける前のレベルに戻す。
     * - 取り付けが解けた後に呼んでも、何もしない。
     */
    public fun setLevels(levels: Map<String, CoreLogLevel>)

    override fun close()
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
 * - レベルは接続層のロガーの設定を写したもの（[CoreLogRoute.setLevels]）で決める。`isLoggable` を必要以上に
 *   真にしないため。取り付けている間は、`logging.properties` などで JUL の `kibicomputers` の木に付けたレベルより、
 *   接続層の設定が優先する（出口が接続層のロガーなので、そちらの設定に合わせる）。
 * - 実行中に JUL が設定し直されて（`LogManager.reset()` / `readConfiguration()`）ハンドラーが外されたら、
 *   取り付けを解いて親に流す設定を戻す。以後のログは標準エラーなど親の出力先に出る（sink には来ない）。
 * - JUL の `LogManager` が JDK のものでない環境（log4j-jul などに差し替えた環境）では、何もしない。
 * - JUL のロガーは弱い参照でしか持たれないので、取り付けたロガーはここで強く持つ。
 */
@ApiStatus.Internal
public object CoreLogRouting {
    /** core のロガー名の根 */
    public const val ROOT: String = "kibicomputers"

    private val installed = ConcurrentHashMap<String, Installation>()

    /**
     * [root] 以下のロガーの行を [sink] に流す。最初は木全体を [minLevel] 以上にし、子ごとのレベルは
     * 返した経路の [CoreLogRoute.setLevels] で付ける。
     *
     * @return 取り付けたら、その経路。既に取り付け済み、または JUL が JDK のものでなく取り付けなかったら `null`
     */
    @JvmStatic
    @JvmOverloads
    public fun install(sink: CoreLogSink, root: String = ROOT, minLevel: CoreLogLevel = CoreLogLevel.TRACE): CoreLogRoute? {
        if (LogManager.getLogManager().javaClass != LogManager::class.java) return null
        val logger = Logger.getLogger(root)
        val installation = Installation(root, logger, sink, minLevel)
        if (installed.putIfAbsent(root, installation) != null) return null
        return try {
            installation.attach()
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
        private val defaultLevel: CoreLogLevel,
    ) : CoreLogRoute {
        private val handler = SinkHandler(sink) { close() }
        private var previousLevel: Level? = null
        private var previousUseParent: Boolean = true

        // レベルを付けた子のロガー（強い参照）と、付ける前のレベル
        private val children = HashMap<String, Pair<Logger, Level?>>()
        private var closed = false

        @Synchronized
        fun attach() {
            previousLevel = logger.level
            previousUseParent = logger.useParentHandlers
            logger.addHandler(handler)
            try {
                logger.level = CoreLogLevel.julThreshold(defaultLevel)
                logger.useParentHandlers = false
            } catch (e: RuntimeException) {
                restore()
                throw e
            }
        }

        @Synchronized
        override fun setLevels(levels: Map<String, CoreLogLevel>) {
            if (closed) return
            logger.level = CoreLogLevel.julThreshold(levels[root] ?: defaultLevel)
            val wanted = levels.filterKeys { it.startsWith("$root.") }
            val iterator = children.entries.iterator()
            while (iterator.hasNext()) {
                val (name, entry) = iterator.next()
                if (name !in wanted) {
                    entry.first.level = entry.second
                    iterator.remove()
                }
            }
            for ((name, level) in wanted) {
                val entry = children.getOrPut(name) {
                    val child = Logger.getLogger(name)
                    child to child.level
                }
                entry.first.level = CoreLogLevel.julThreshold(level)
            }
        }

        override fun close() {
            if (!installed.remove(root, this)) return
            restore()
        }

        @Synchronized
        private fun restore() {
            closed = true
            logger.removeHandler(handler)
            logger.level = previousLevel
            logger.useParentHandlers = previousUseParent
            for ((child, level) in children.values) child.level = level
            children.clear()
        }
    }

    private class SinkHandler(private val sink: CoreLogSink, private val onClose: () -> Unit) : Handler() {
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

        /**
         * `LogManager.reset()` / `readConfiguration()` は、ロガーからハンドラーを外すときに `close()` を呼ぶ。
         * そのとき `useParentHandlers` は元に戻らない（reset が消すのはハンドラーとレベルだけ）ので、ここで取り付けを
         * 解いて親に流す設定を戻す。戻さないと、別の MOD やライブラリが実行中に JUL を設定し直したときに、
         * ログが sink にも親にも流れず、黙って消える。
         */
        override fun close(): Unit = onClose()
    }
}
