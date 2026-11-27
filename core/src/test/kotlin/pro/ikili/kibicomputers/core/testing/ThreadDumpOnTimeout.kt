package pro.ikili.kibicomputers.core.testing

import org.junit.jupiter.api.extension.AfterTestExecutionCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.PreInterruptCallback
import org.junit.jupiter.api.extension.PreInterruptContext
import java.io.OutputStream
import java.io.PrintStream
import java.lang.management.ManagementFactory
import java.lang.management.ThreadInfo
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 止まったテストのスレッドダンプを残す JUnit の拡張（#927）。
 *
 * `junit-platform.properties` の自動登録（ServiceLoader。`META-INF/services/org.junit.jupiter.api.extension.Extension`）で、
 * core のすべてのテストに付く。次の 2 つの時点で、JVM の全スレッドのダンプ（全フレームと、ロックの持ち主）を撮る。
 *
 * - 保険のタイムアウト（`junit.jupiter.execution.timeout.default`・`@Timeout`。#916）がテストのスレッドを割り込む直前
 *   （[beforeThreadInterrupt]。JUnit の見張りのスレッドで呼ばれ、止まった状態のまま撮れる）
 * - `assertTimeoutPreemptively` が時間切れで落ちたあと（[afterTestExecution]）。JUnit は時間切れのスレッド 1 本のスタックしか
 *   失敗に付けないので、ほかのスレッド（止まった `KiBi-` のスレッドなど）をここで撮る。その時点では、JUnit が実行していたスレッドを
 *   既に割り込み、`use {}` の後始末（`Harness.close` など）も走ったあとなので、撮れるのは後始末の途中の状態になる
 *
 * 全文はファイル（既定は作業ディレクトリからの `build/thread-dumps/other`。Gradle の core のテストのタスクは、設定パラメーター
 * [DIR_PROPERTY] で `build/thread-dumps/<タスク>` を渡す）に書き、標準出力には [MARKER] で始まる短い要約だけを出す
 * （Gradle の core のテストのタスクが、その頭の行をコンソールと CI のログに写す）。
 *
 * どちらの時点でも、例外を外に出さず、カーネルのロックを取る処理を呼ばない（`ThreadMXBean` から読むだけ）。
 * 撮れないとき（書き出しの失敗など）は標準エラーに 1 行出して続ける。
 *
 * `@TestFactory` の動的テスト・クラスの初期化・Gradle の worker そのものの止まりは JUnit から拾えないので、CI の側で `jcmd` の
 * 見張り（`.github/scripts/thread-dump-watchdog.sh`）が受け持つ。
 */
class ThreadDumpOnTimeout : PreInterruptCallback, AfterTestExecutionCallback {
    override fun beforeThreadInterrupt(preInterruptContext: PreInterruptContext, extensionContext: ExtensionContext) {
        val target = preInterruptContext.threadToInterrupt
        dumpSafely(extensionContext, "保険のタイムアウトが \"${target.name}\" を割り込む直前", target)
    }

    override fun afterTestExecution(context: ExtensionContext) {
        val failure = context.executionException.orElse(null) ?: return
        if (!isPreemptiveTimeout(failure)) return
        dumpSafely(context, "assertTimeoutPreemptively の時間切れ（後始末のあと）", null)
    }

    private fun dumpSafely(context: ExtensionContext, reason: String, target: Thread?) {
        try {
            val dir = context.getConfigurationParameter(DIR_PROPERTY).orElse(DEFAULT_DIR)
            val label = context.testMethod.map { "${context.requiredTestClass.name}#${it.name}" }
                .orElseGet { context.testClass.map { it.name }.orElse(context.uniqueId) }
            val quiet = context.getConfigurationParameter(SUMMARY_PROPERTY).orElse("on") == "off"
            ThreadDumps.dump(Paths.get(dir), label, reason, target, if (quiet) PrintStream(OutputStream.nullOutputStream()) else System.out)
        } catch (t: Throwable) {
            if (t is VirtualMachineError && t !is StackOverflowError) throw t
            try {
                System.err.println("$MARKER: スレッドダンプを撮れなかった: $t")
            } catch (_: Throwable) {
            }
        }
    }

    internal companion object {
        /** スレッドダンプの要約の行の頭。core の `test`・`testSerial` タスクが、この頭の行をコンソールに写す */
        const val MARKER = "#927 ダンプ"

        /** ダンプのファイルを書くディレクトリの設定パラメーター（システムプロパティでも渡せる。相対パスは作業ディレクトリから） */
        const val DIR_PROPERTY = "kibi.test.threadDumpDir"

        const val DEFAULT_DIR = "build/thread-dumps/other"

        /** `off` で標準出力の要約を出さない（ThreadDumpOnTimeoutTest の入れ子の起動が、通った回の CI のログに要約を出さないため） */
        const val SUMMARY_PROPERTY = "kibi.test.threadDumpSummary"

        /** `assertTimeoutPreemptively` の時間切れの失敗か（JUnit は原因に非公開の `ExecutionTimeoutException` を付ける） */
        fun isPreemptiveTimeout(failure: Throwable): Boolean {
            var t: Throwable? = failure
            var depth = 0
            while (t != null && depth < 16) {
                if (t.javaClass.name.endsWith("ExecutionTimeoutException")) return true
                t = t.cause
                depth++
            }
            return false
        }
    }
}

/** スレッドダンプの組み立てと書き出し（#927）。[ThreadDumpOnTimeout] から呼ぶ。テストのためにファイルと要約の出し先を引数にしている */
internal object ThreadDumps {
    /** 要約に載せるスタックの行数の上限（ScaleTest のように台数の多いテストで、CI のログから本当の失敗を押し出さないため） */
    const val SUMMARY_LINE_LIMIT = 300

    private val FILE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")

    /**
     * JVM の全スレッドのダンプを [dir] の下のファイルに書き、[summary] に要約を出す。書いたファイルを返す。
     * [target] は割り込まれるスレッド（要約に必ず載せる）。
     */
    fun dump(dir: Path, label: String, reason: String, target: Thread?, summary: PrintStream): Path {
        val bean = ManagementFactory.getThreadMXBean()
        val infos = bean.dumpAllThreads(bean.isObjectMonitorUsageSupported, bean.isSynchronizerUsageSupported)
            .filterNotNull()
            .sortedBy { it.threadId }
        val deadlocked = (if (bean.isSynchronizerUsageSupported) bean.findDeadlockedThreads() else bean.findMonitorDeadlockedThreads())
            ?.toSet().orEmpty()
        val pid = ProcessHandle.current().pid()
        val header = "$label: $reason（pid $pid、スレッド ${infos.size} 本）"
        val deadlockLine = if (deadlocked.isEmpty()) {
            "デッドロック（findDeadlockedThreads）: なし"
        } else {
            "デッドロック（findDeadlockedThreads）: " + infos.filter { it.threadId in deadlocked }.joinToString { "\"${it.threadName}\"" }
        }

        val full = StringBuilder()
        full.append(header).append('\n').append(deadlockLine).append('\n').append('\n')
        for (info in infos) {
            appendThread(full, info)
            full.append('\n')
        }

        Files.createDirectories(dir)
        val name = "${sanitize(label)}-$pid-${LocalDateTime.now().format(FILE_TIME)}.txt"
        val file = dir.resolve(name)
        Files.write(file, full.toString().toByteArray(Charsets.UTF_8))

        // 要約: 割り込まれるスレッド・デッドロックのスレッド・KiBi- のスレッドのスタックだけ。全部の行に頭を付ける
        val picked = infos.filter { it.threadId == target?.id || it.threadId in deadlocked || it.threadName.startsWith("KiBi-") }
        val lines = ArrayList<String>()
        lines += header
        lines += deadlockLine
        lines += "全文: ${file.toAbsolutePath()}"
        val stacks = StringBuilder()
        for (info in picked) appendThread(stacks, info)
        val stackLines = stacks.lines().filter { it.isNotEmpty() }
        lines += stackLines.take(SUMMARY_LINE_LIMIT)
        if (stackLines.size > SUMMARY_LINE_LIMIT) lines += "（ほか ${stackLines.size - SUMMARY_LINE_LIMIT} 行は全文のファイルにある）"
        synchronized(summary) {
            for (line in lines) summary.println("${ThreadDumpOnTimeout.MARKER}: $line")
            summary.flush()
        }
        return file
    }

    /** jstack に近い形で 1 本を書く（`ThreadInfo.toString()` は 8 フレームで切れるので使わない） */
    private fun appendThread(out: StringBuilder, info: ThreadInfo) {
        out.append('"').append(info.threadName).append('"')
            .append(" #").append(info.threadId)
            .append(if (info.isDaemon) " daemon" else "")
            .append(" prio=").append(info.priority)
            .append(' ').append(info.threadState)
        if (info.isSuspended) out.append(" (suspended)")
        if (info.isInNative) out.append(" (in native)")
        out.append('\n')
        info.lockInfo?.let { lock ->
            out.append("    待っているロック: ").append(lock)
            info.lockOwnerName?.let { out.append("（持ち主 \"").append(it).append("\" #").append(info.lockOwnerId).append('）') }
            out.append('\n')
        }
        val monitors = info.lockedMonitors.groupBy { it.lockedStackDepth }
        info.stackTrace.forEachIndexed { depth, frame ->
            out.append("    at ").append(frame).append('\n')
            monitors[depth]?.forEach { out.append("    - 持っているモニター ").append(it).append('\n') }
        }
        val synchronizers = info.lockedSynchronizers
        if (synchronizers.isNotEmpty()) {
            out.append("    持っている同期器: ").append(synchronizers.joinToString()).append('\n')
        }
    }

    /** ファイル名に使えない文字を置き換え、長さを抑える（Windows の 260 文字の制限に掛からないように） */
    private fun sanitize(label: String): String {
        val simple = label.substringAfterLast('.')
        return simple.replace(Regex("[^A-Za-z0-9_#-]"), "_").replace('#', '-').take(120)
    }
}
