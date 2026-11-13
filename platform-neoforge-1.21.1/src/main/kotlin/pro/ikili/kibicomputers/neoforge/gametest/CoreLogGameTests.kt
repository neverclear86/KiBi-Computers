package pro.ikili.kibicomputers.neoforge.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Configurator
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.neoforge.CoreLogBridge
import java.util.Collections
import java.util.UUID
import org.apache.logging.log4j.Level as Log4jLevel

/** core の `System.getLogger` のログが、ローダーの log4j（`latest.log`・`debug.log` の出口）に届く（#673）。 */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object CoreLogGameTests {
    // テスト専用の名前。アペンダーはこの名前の行だけを拾う
    private const val NAME = "kibicomputers.logtest"

    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun coreSystemLoggerReachesLog4j(helper: GameTestHelper) {
        val events = Collections.synchronizedList(ArrayList<Triple<Log4jLevel, String, Throwable?>>())
        // 1.20.1 の log4j には Property を取る形がないので、両方にある古い形を使う
        @Suppress("DEPRECATION")
        val appender = object : AbstractAppender("kibicomputers-logtest-${UUID.randomUUID()}", null, null, true) {
            override fun append(event: LogEvent) {
                if (event.loggerName == NAME) events += Triple(event.level, event.message.formattedMessage, event.thrown)
            }
        }
        appender.start()
        // root の設定（latest.log・debug.log のアペンダーがあるところ）に付け、そこまで届くことを見る。名前で絞るので、ほかのテストのログは拾わない
        val rootConfig = LoggerContext.getContext(false).configuration.rootLogger
        rootConfig.addAppender(appender, null, null)
        val mark = UUID.randomUUID().toString()
        val error = RuntimeException("CoreLogGameTests test throwable (not a bug) $mark")
        try {
            val log = System.getLogger(NAME)
            log.log(System.Logger.Level.WARNING, "CoreLogGameTests test line (not a bug) {} $mark", error)
            log.log(System.Logger.Level.DEBUG, "CoreLogGameTests debug line $mark")
        } finally {
            rootConfig.removeAppender(appender.name)
            appender.stop()
        }
        val warn = events.filter { it.second.endsWith(mark) && it.first == Log4jLevel.WARN }
        helper.assertTrue(warn.size == 1, "the core WARNING should reach log4j once as WARN: $events")
        helper.assertTrue(warn[0].second == "CoreLogGameTests test line (not a bug) {} $mark", "the message should be passed as is, not as a format: ${warn[0].second}")
        helper.assertTrue(warn[0].third === error, "the throwable should be kept")
        if (LogManager.getLogger(NAME).isDebugEnabled) {
            helper.assertTrue(
                events.any { it.first == Log4jLevel.DEBUG && it.second == "CoreLogGameTests debug line $mark" },
                "the core DEBUG should reach log4j as DEBUG (debug.log): $events",
            )
        }
        helper.succeed()
    }

    /**
     * log4j の設定で子のロガーだけを DEBUG にしたら、その子の DEBUG だけが JUL を通って届く。
     * 兄弟のロガーは、log4j の判定と JUL の判定が一致したまま（#681 のレビュー）。
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun childOnlyLog4jLevelIsMirroredToCoreLogger(helper: GameTestHelper) {
        val only = "$NAME.only"
        val other = "$NAME.other"
        val context = LoggerContext.getContext(false)
        helper.assertTrue(context.configuration.loggers[only] == null, "the test logger config should not exist yet")
        val events = Collections.synchronizedList(ArrayList<Pair<String, Log4jLevel>>())
        @Suppress("DEPRECATION")
        val appender = object : AbstractAppender("kibicomputers-logtest-${UUID.randomUUID()}", null, null, true) {
            override fun append(event: LogEvent) {
                if (event.loggerName == only || event.loggerName == other) events += event.loggerName to event.level
            }
        }
        appender.start()
        val rootConfig = context.configuration.rootLogger
        rootConfig.addAppender(appender, null, null)
        val mark = UUID.randomUUID().toString()
        try {
            Configurator.setLevel(only, Log4jLevel.DEBUG)
            CoreLogBridge.refresh()
            val onlyLog = System.getLogger(only)
            val otherLog = System.getLogger(other)
            helper.assertTrue(onlyLog.isLoggable(System.Logger.Level.DEBUG), "the child set to DEBUG in log4j should be DEBUG in JUL")
            helper.assertTrue(
                otherLog.isLoggable(System.Logger.Level.DEBUG) == LogManager.getLogger(other).isDebugEnabled,
                "a sibling should follow log4j's level for it",
            )
            onlyLog.log(System.Logger.Level.DEBUG, "CoreLogGameTests child debug line $mark")
            helper.assertTrue(events.contains(only to Log4jLevel.DEBUG), "the child DEBUG should reach log4j: $events")
        } finally {
            context.configuration.removeLogger(only)
            context.updateLoggers()
            CoreLogBridge.refresh()
            rootConfig.removeAppender(appender.name)
            appender.stop()
        }
        helper.assertTrue(
            System.getLogger(only).isLoggable(System.Logger.Level.DEBUG) == LogManager.getLogger(only).isDebugEnabled,
            "after the config is removed, the child should follow log4j again",
        )
        helper.succeed()
    }
}
