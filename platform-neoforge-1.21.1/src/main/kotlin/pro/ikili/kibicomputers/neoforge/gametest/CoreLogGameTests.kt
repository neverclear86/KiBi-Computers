package pro.ikili.kibicomputers.neoforge.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.gametest.framework.GameTestInfo
import net.minecraft.gametest.framework.GameTestListener
import net.minecraft.gametest.framework.GameTestRunner
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
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
import pro.ikili.kibicomputers.core.log.CoreLogRouting
import pro.ikili.kibicomputers.core.runtime.RuntimeSelfCheck
import pro.ikili.kibicomputers.neoforge.CoreLogBridge
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
     * Endive の `System.Logger`（名前 `endive`）の WARNING も、log4j の root まで1回だけ WARN で届く（#680）。
     * JUL 側の `endive` のレベルは log4j の設定を写すので、TRACE の判定も log4j と一致する（Endive の `WasiPreview1` の
     * TRACE の行の書式を、出さないのに作らない）。
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun endiveSystemLoggerReachesLog4j(helper: GameTestHelper) {
        val name = CoreLogRouting.ENDIVE_ROOT
        val mark = UUID.randomUUID().toString()
        val events = Collections.synchronizedList(ArrayList<Pair<Log4jLevel, String>>())
        @Suppress("DEPRECATION")
        val appender = object : AbstractAppender("kibicomputers-logtest-${UUID.randomUUID()}", null, null, true) {
            override fun append(event: LogEvent) {
                val message = event.message.formattedMessage
                if (event.loggerName == name && message.endsWith(mark)) events += event.level to message
            }
        }
        appender.start()
        val rootConfig = LoggerContext.getContext(false).configuration.rootLogger
        rootConfig.addAppender(appender, null, null)
        val log = System.getLogger(name)
        try {
            log.log(System.Logger.Level.WARNING, "CoreLogGameTests endive test line (not a bug) $mark")
        } finally {
            rootConfig.removeAppender(appender.name)
            appender.stop()
        }
        helper.assertTrue(events.count { it.first == Log4jLevel.WARN } == 1, "the endive WARNING should reach log4j once as WARN: $events")
        helper.assertTrue(
            log.isLoggable(System.Logger.Level.TRACE) == LogManager.getLogger(name).isTraceEnabled,
            "the endive TRACE level in JUL should follow log4j",
        )
        helper.succeed()
    }

    /**
     * JVM のメソッドの上限を超える関数をインタプリタに回しても、Endive が標準エラーに直に書かない（#680）。
     * GameTest のサーバーでは `System.err` はプロセスの標準エラーのまま（`latest.log` に出ず、`InterpreterFallback.WARN` の行は
     * Gradle の出力にだけ出た）。MC が `System.err` を log4j に流す環境（`[STDERR]` の INFO の行）もあるので、両方を見る:
     * 読み込みの間だけ `System.err` を、元にも書く写し（tee）に差し替え、root に付けたアペンダーでも Endive の文言を探す。
     * `System.err` はプロセス全体の状態なので、元にも書き（ほかのスレッドの行を落とさない）、必ず戻す。
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun tooLargeFunctionDoesNotWriteToStderr(helper: GameTestHelper) {
        val lines = Collections.synchronizedList(ArrayList<String>())
        @Suppress("DEPRECATION")
        val appender = object : AbstractAppender("kibicomputers-logtest-${UUID.randomUUID()}", null, null, true) {
            override fun append(event: LogEvent) {
                val message = event.message.formattedMessage
                if (message.contains("using interpreted mode")) lines += "${event.loggerName}: $message"
            }
        }
        appender.start()
        val rootConfig = LoggerContext.getContext(false).configuration.rootLogger
        rootConfig.addAppender(appender, null, null)
        val captured = java.io.ByteArrayOutputStream()
        val original = System.err
        val tee = java.io.PrintStream(
            object : java.io.OutputStream() {
                override fun write(b: Int) {
                    synchronized(captured) { captured.write(b) }
                    original.write(b)
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    synchronized(captured) { captured.write(b, off, len) }
                    original.write(b, off, len)
                }

                override fun flush() = original.flush()
            },
            true,
        )
        val compiled = try {
            System.setErr(tee)
            RuntimeSelfCheck.compilesTooLargeFunction()
        } finally {
            System.setErr(original)
            rootConfig.removeAppender(appender.name)
            appender.stop()
        }
        val stderr = synchronized(captured) { captured.toString(Charsets.UTF_8) }
        helper.assertTrue(compiled, "only the too-large function should run in the interpreter")
        helper.assertTrue(!stderr.contains("using interpreted mode"), "Endive should not print the interpreter fallback to stderr: $stderr")
        helper.assertTrue(lines.isEmpty(), "Endive should not print the interpreter fallback to stderr (log4j): $lines")
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

    /**
     * [machineHostFailureReachesLog4j] の tick 数の上限。待つ相手は壁時計で進む起動用スレッド（`KiBi-boot-*`）の2往復で、
     * GameTest のサーバーは tick の間で待たないので、普通の上限（100 tick）は tick が軽いと数十ミリ秒で過ぎてしまう。
     * 起動を待つほかのテスト（`TestComputerGameTests`）と同じ大きさにする。条件がそろえばすぐ終わるので、大きくしても遅くはならない
     */
    private const val HOST_FAILURE_TIMEOUT_TICKS = 12_000

    /** [Machine] のロガー名（`Machine.kt` の `LOG`） */
    private const val MACHINE_LOG = "kibicomputers.machine"

    /** 起動の失敗を記録してから投げる接続層（#674） */
    private class ThrowingHost(private val error: RuntimeException) : MachineHost {
        @Volatile
        var failure: BootFailure? = null

        /** 起動の失敗の通知を受けた回数（投げた回数） */
        val failures = AtomicInteger()

        // 状態の通知では投げない（WARNING は起動の失敗の1件だけにする）
        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit

        override fun onBootFailed(machine: Machine, failure: BootFailure, detail: String) {
            this.failure = failure
            failures.incrementAndGet()
            throw error
        }
    }

    /**
     * 接続層の通知が投げた例外を `Machine.safely` が捨てるとき、`kibicomputers.machine` の WARNING が log4j の root
     * （`latest.log` の出口）まで届く（#674・#673）。捨てた後も、マシンは次の起動を受け付け、その失敗の通知もまた配られる（同じ例外の2回目は DEBUG なので WARNING は1件のまま）。
     *
     * `ServerMachines`（`MachineManager`）を通すと、`ManagedHost` が接続層の例外を先に捕まえて ERROR に出すので、
     * `Machine.safely` まで届かない。ここでは自前のスケジューラでマシンを作り、包まない [MachineHost] を直に渡す。
     * スケジューラを分けるので、スタックトレースを付けた場所の記録（`claimHostFailureTrace`）もほかのテストと共有しない。
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = HOST_FAILURE_TIMEOUT_TICKS)
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
        helper.testInfo.addListener(
            object : GameTestListener {
                override fun testStructureLoaded(info: GameTestInfo) {}

                override fun testPassed(info: GameTestInfo, runner: GameTestRunner) = cleanUp()

                override fun testFailed(info: GameTestInfo, runner: GameTestRunner) = cleanUp()

                override fun testAddedForRerun(old: GameTestInfo, new: GameTestInfo, runner: GameTestRunner) {}
            },
        )
        try {
            val error = RuntimeException("CoreLogGameTests host failure (not a bug) $mark")
            val host = ThrowingHost(error)
            val machine = scheduler.createMachine(host, MachineConfig.DEFAULT)
            fun boot() = machine.boot(BootConfig.builder().firmwarePresent(false).addBootDisk("any").build())
            helper.assertTrue(boot(), "boot should be accepted")
            var rebooted = false
            helper.succeedWhen {
                // 通知はこのスケジューラの tick で配る（サーバーの tick ごとに1回）
                scheduler.tick()
                helper.assertTrue(host.failure == BootFailure.NO_FIRMWARE, "the host should get the failure: ${host.failure}")
                val warn = synchronized(events) { events.filter { it.first == Log4jLevel.WARN } }
                helper.assertTrue(warn.size == 1, "the swallowed exception should reach log4j once as WARN: $events")
                helper.assertTrue(warn[0].third === error, "the first WARNING should carry the stack trace: ${warn[0]}")
                helper.assertTrue(machine.state == MachineState.STOPPED, "the machine should be stopped after the failure: ${machine.state}")
                // 例外を捨てた後も、マシンが次の起動を受け付けて通知を配り続けることを見る（捨てずに伝えていたら、ここまで来ない）
                if (!rebooted) {
                    rebooted = true
                    helper.assertTrue(boot(), "the machine should accept another boot after the swallowed failure")
                }
                helper.assertTrue(host.failures.get() == 2, "the later notification should still be delivered: ${host.failures.get()}")
                helper.assertTrue(synchronized(events) { events.count { it.first == Log4jLevel.WARN } } == 1, "a repeat of the same exception should not warn again: $events")
            }
        } catch (t: Throwable) {
            cleanUp()
            throw t
        }
    }
}
