package pro.ikili.kibicomputers.forge

import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.config.Configuration
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import pro.ikili.kibicomputers.core.log.CoreLogLevel
import pro.ikili.kibicomputers.core.log.CoreLogRoute
import pro.ikili.kibicomputers.core.log.CoreLogRouting
import pro.ikili.kibicomputers.core.log.CoreLogSink
import java.beans.PropertyChangeListener
import org.apache.logging.log4j.Level as Log4jLevel
import org.apache.logging.log4j.LogManager as Log4jManager

/**
 * core の `System.getLogger` のログを、ローダーのログ（SLF4J → log4j。`latest.log`・`debug.log`）に流す（#673）。
 * 呼び出し元のスレッドのまま同期で渡すので、ログにはそのスレッドの名前が残る。
 *
 * JUL 側のレベルは、log4j の設定の `kibicomputers` とその下の `LoggerConfig` を1つずつ写す（子のロガーだけ
 * DEBUG にした設定も効く）。設定の読み直し（設定ファイルの監視・`reconfigure`）には追従する。
 * `Configurator.setLevel` のような、設定を差し替えない実行中の変更は通知が来ないので、[refresh] を呼ぶまで追従しない。
 */
internal object CoreLogBridge {
    private val SINK = CoreLogSink { name, level, message, thrown ->
        val logger = LoggerFactory.getLogger(name)
        // message は書式として渡さない（`{}` を含みうる）。1引数・Throwable つきの形だけを使う
        when (level) {
            CoreLogLevel.ERROR -> if (thrown == null) logger.error(message) else logger.error(message, thrown)
            CoreLogLevel.WARN -> if (thrown == null) logger.warn(message) else logger.warn(message, thrown)
            CoreLogLevel.INFO -> if (thrown == null) logger.info(message) else logger.info(message, thrown)
            CoreLogLevel.DEBUG -> if (thrown == null) logger.debug(message) else logger.debug(message, thrown)
            CoreLogLevel.TRACE -> if (thrown == null) logger.trace(message) else logger.trace(message, thrown)
        }
    }

    @Volatile
    private var route: CoreLogRoute? = null

    /** 1回だけ取り付ける。開発クライアントで統合サーバーを立て直しても、外さない */
    fun install(log: Logger) {
        val root = LoggerFactory.getLogger(CoreLogRouting.ROOT)
        val minLevel = when {
            root.isTraceEnabled -> CoreLogLevel.TRACE
            root.isDebugEnabled -> CoreLogLevel.DEBUG
            else -> CoreLogLevel.INFO
        }
        val installed = CoreLogRouting.install(SINK, CoreLogRouting.ROOT, minLevel)
        if (installed == null) {
            log.debug("Core System.Logger output was not routed (already routed, or java.util.logging is not the JDK one)")
            return
        }
        route = installed
        val context = log4jContext()
        if (context != null) {
            refresh(context.configuration)
            try {
                context.addPropertyChangeListener(
                    PropertyChangeListener { event ->
                        if (event.propertyName == LoggerContext.PROPERTY_CONFIG) (event.newValue as? Configuration)?.let(::refresh)
                    },
                )
            } catch (e: LinkageError) {
                // java.beans が見えない環境。読み直しには追従しないが、取り付けた時点の設定は効いている
                log.debug("Core log levels will not follow log4j reconfiguration", e)
            }
            log.debug("Routing core System.Logger output to SLF4J (levels follow the log4j configuration)")
        } else {
            log.debug("Routing core System.Logger output to SLF4J (from {}; log4j-core not found, levels are fixed)", minLevel)
        }
    }

    /** log4j の今の設定から、JUL 側のレベルを付け直す（`Configurator.setLevel` などで設定を変えた後に呼ぶ） */
    fun refresh() {
        log4jContext()?.let { refresh(it.configuration) }
    }

    private fun refresh(configuration: Configuration) {
        val route = route ?: return
        val levels = HashMap<String, CoreLogLevel>()
        // 根は、いちばん近い LoggerConfig（なければ log4j の root）のレベル
        levels[CoreLogRouting.ROOT] = levelOf(configuration.getLoggerConfig(CoreLogRouting.ROOT).level)
        for ((name, config) in configuration.loggers) {
            if (name.startsWith(CoreLogRouting.ROOT + ".")) levels[name] = levelOf(config.level)
        }
        route.setLevels(levels)
    }

    private fun log4jContext(): LoggerContext? = try {
        Log4jManager.getContext(false) as? LoggerContext
    } catch (e: LinkageError) {
        null
    }

    // OFF・FATAL は ERROR に寄せる（JUL では SEVERE だけ通り、log4j 側で落ちる）
    private fun levelOf(level: Log4jLevel?): CoreLogLevel = when {
        level == null -> CoreLogLevel.INFO
        level.isLessSpecificThan(Log4jLevel.TRACE) -> CoreLogLevel.TRACE
        level.isLessSpecificThan(Log4jLevel.DEBUG) -> CoreLogLevel.DEBUG
        level.isLessSpecificThan(Log4jLevel.INFO) -> CoreLogLevel.INFO
        level.isLessSpecificThan(Log4jLevel.WARN) -> CoreLogLevel.WARN
        else -> CoreLogLevel.ERROR
    }
}
