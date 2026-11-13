package pro.ikili.kibicomputers.forge

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import pro.ikili.kibicomputers.core.log.CoreLogLevel
import pro.ikili.kibicomputers.core.log.CoreLogRouting
import pro.ikili.kibicomputers.core.log.CoreLogSink

/**
 * core の `System.getLogger` のログを、ローダーのログ（SLF4J → log4j。`latest.log`・`debug.log`）に流す（#673）。
 * 呼び出し元のスレッドのまま同期で渡すので、ログにはそのスレッドの名前が残る。
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

    /** 1回だけ取り付ける。開発クライアントで統合サーバーを立て直しても、外さない */
    fun install(log: Logger) {
        val root = LoggerFactory.getLogger(CoreLogRouting.ROOT)
        val minLevel = when {
            root.isTraceEnabled -> CoreLogLevel.TRACE
            root.isDebugEnabled -> CoreLogLevel.DEBUG
            else -> CoreLogLevel.INFO
        }
        if (CoreLogRouting.install(SINK, CoreLogRouting.ROOT, minLevel) != null) {
            log.debug("Routing core System.Logger output to SLF4J (from {})", minLevel)
        } else {
            log.debug("Core System.Logger output was not routed (already routed, or java.util.logging is not the JDK one)")
        }
    }
}
