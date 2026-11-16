package pro.ikili.kibicomputers.forge.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Configurator
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.kernel.BootConfig
import pro.ikili.kibicomputers.core.kernel.BootFailure
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.MachineConfig
import pro.ikili.kibicomputers.core.kernel.MachineHost
import pro.ikili.kibicomputers.core.kernel.MachineScheduler
import pro.ikili.kibicomputers.core.kernel.MachineState
import pro.ikili.kibicomputers.forge.CoreLogBridge
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
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

    private const val TIMEOUT_TICKS = 200

    /** [Machine] のロガー名（`Machine.kt` の `LOG`） */
    private const val MACHINE_LOG = "kibicomputers.machine"

    /** 起動の失敗を記録してから投げる接続層（#674） */
    private class ThrowingHost(private val error: RuntimeException) : MachineHost {
        @Volatile
        var failure: BootFailure? = null

        // 状態の通知では投げない（WARNING は起動の失敗の1件だけにする）
        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit

        override fun onBootFailed(machine: Machine, failure: BootFailure, detail: String) {
            this.failure = failure
            throw error
        }
    }

    /**
     * 接続層の通知が投げた例外を `Machine.safely` が捨てるとき、`kibicomputers.machine` の WARNING が log4j の root
     * （`latest.log` の出口）まで届く（#674・#673）。マシンは止まったまま動き続ける。
     *
     * 1.21.1 の接続層（`ServerMachines`）を通すと、`ManagedHost` が接続層の例外を先に捕まえて ERROR に出すので、
     * `Machine.safely` まで届かない。ここでは自前のスケジューラでマシンを作り、包まない [MachineHost] を直に渡す。
     * スケジューラを分けるので、スタックトレースを付けた場所の記録（`claimHostFailureTrace`）もほかのテストと共有しない。
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = TIMEOUT_TICKS)
    fun machineHostFailureReachesLog4j(helper: GameTestHelper) {
        val mark = UUID.randomUUID().toString()
        val events = Collections.synchronizedList(ArrayList<Triple<Log4jLevel, String, Throwable?>>())
        @Suppress("DEPRECATION")
        val appender = object : AbstractAppender("kibicomputers-logtest-${UUID.randomUUID()}", null, null, true) {
            override fun append(event: LogEvent) {
                // ほかのテストのマシンのログは、印で除く。溜めるのはこのテストの行だけ
                if (event.loggerName != MACHINE_LOG) return
                val message = event.message.formattedMessage
                if (message.contains(mark)) events += Triple(event.level, message, event.thrown)
            }
        }
        appender.start()
        val rootConfig = LoggerContext.getContext(false).configuration.rootLogger
        rootConfig.addAppender(appender, null, null)
        val scheduler = MachineScheduler()
        // 成功・失敗・タイムアウトのどれでも1回だけ走らせる。判定が通らないままタイムアウトしたときも、
        // スケジューラのスレッドとアペンダーを残さない（`LoadGameTests` が `KiBi-` のスレッドの数を数えるため）
        val cleanedUp = AtomicBoolean(false)
        val cleanUp = {
            if (cleanedUp.compareAndSet(false, true)) {
                try {
                    scheduler.close()
                } finally {
                    rootConfig.removeAppender(appender.name)
                    appender.stop()
                }
            }
        }
        // 1.20.1 の `GameTestHelper` は `testInfo` を隠していて、終わりを知らせるリスナーを付けられない。
        // 成功の経路では、判定がすべて通ったところ（`succeedWhen` の最後）で片付ける。テストが終わると
        // 予約した処理は走らない（`GameTestInfo.tick` は終わった後に `runAtTickTimeMap` を処理しない）ため。
        // 判定が通らないままのときは、タイムアウトの少し前に片付け、そのままタイムアウトで失敗させる
        helper.runAfterDelay(TIMEOUT_TICKS - 10L) { cleanUp() }
        try {
            val error = RuntimeException("CoreLogGameTests host failure (not a bug) $mark")
            val host = ThrowingHost(error)
            val machine = scheduler.createMachine(host, MachineConfig.DEFAULT)
            helper.assertTrue(machine.boot(BootConfig.builder().firmwarePresent(false).addBootDisk("any").build()), "boot should be accepted")
            helper.succeedWhen {
                // 通知はこのスケジューラの tick で配る（サーバーの tick ごとに1回）
                scheduler.tick()
                helper.assertTrue(host.failure == BootFailure.NO_FIRMWARE, "the host should get the failure: ${host.failure}")
                val warn = synchronized(events) { events.filter { it.first == Log4jLevel.WARN } }
                helper.assertTrue(warn.size == 1, "the swallowed exception should reach log4j once as WARN: $events")
                helper.assertTrue(warn[0].third === error, "the first WARNING should carry the stack trace: ${warn[0]}")
                helper.assertTrue(machine.state == MachineState.STOPPED, "the machine should keep running as stopped")
                // ここまで通れば成功する（この関数はもう呼ばれない）
                cleanUp()
            }
        } catch (t: Throwable) {
            cleanUp()
            throw t
        }
    }
}
