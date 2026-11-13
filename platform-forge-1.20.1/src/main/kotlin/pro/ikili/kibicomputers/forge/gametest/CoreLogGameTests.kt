package pro.ikili.kibicomputers.forge.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.AbstractAppender
import pro.ikili.kibicomputers.core.KiBiComputers
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
}
