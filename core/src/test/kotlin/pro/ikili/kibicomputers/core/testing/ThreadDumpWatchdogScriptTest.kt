package pro.ikili.kibicomputers.core.testing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * CI の見張りのスクリプト `.github/scripts/with-thread-dump-watchdog.sh`（#927）の確かめ。
 * 本物の jcmd の代わりに、決まった出力を返す偽の jcmd（環境変数 `JCMD`）を渡して回す。
 * bash が見つからないマシンでは飛ばす（CI の Linux には必ずある）
 */
class ThreadDumpWatchdogScriptTest {
    @TempDir
    lateinit var dir: Path

    private val script: Path? = findScript()
    private val bash: String? = findBash()

    @Test
    fun `コマンドが遅延より先に終わったら、見張りは撮らずに止まり、終了コードを素通しする`() {
        val (jcmd, out, log) = prepare()
        val exit = run(jcmd, log, "1", out, "quick", "--", "bash", "-c", "exit 3")
        assertEquals(3, exit, log.readText())
        // 遅延（1 秒）を十分に過ぎるまで待っても、見張りが生き残って撮ることはない
        Thread.sleep(3_000)
        assertFalse(out.exists(), "ダンプのディレクトリができた: ${log.readText()}")
        assertFalse(log.readText().contains("スレッドダンプ"), log.readText())
    }

    @Test
    fun `遅延のあとに間隔を置いて指定の回数だけ撮り、jcmd 自身の行は飛ばし、終了コードを素通しする`() {
        val (jcmd, out, log) = prepare()
        // 2 回目の ::endgroup:: がログに出るまで動き続け、そのあと 7 で終わるコマンド（見張りが撮り終えてから抜ける）。
        // 引数に二重引用符を入れると Windows の起動で壊れるので、スクリプトのファイルにする
        val waiter = dir.resolve("wait-second-dump.sh")
        Files.writeString(
            waiter,
            """
            |until [ "${'$'}(grep -c '::endgroup::' "${'$'}1")" -ge 2 ]; do sleep 0.2; done
            |exit 7
            |
            """.trimMargin(),
        )
        val exit = run(jcmd, log, "1", out, "t", "--", "bash", unixPath(waiter), unixPath(log))
        val text = log.readText()
        assertEquals(7, exit, text)

        val files = Files.list(out).use { s -> s.map { it.fileName.toString() }.sorted().toList() }
        assertEquals(listOf("t-1-4242.txt", "t-2-4242.txt"), files, text)
        for (file in files) {
            val body = out.resolve(file).readText()
            assertTrue(body.contains("# pid 4242: org.example.FakeMain arg"), body)
            assertTrue(body.contains("FAKE-DUMP pid=4242 Thread.print -l"), body)
        }
        assertTrue(text.contains("1/2 回目"), text)
        assertTrue(text.contains("2/2 回目"), text)
        assertEquals(2, Regex("::group::").findAll(text).count(), text)
        assertFalse(text.contains("4343"), text)
    }

    @Test
    fun `引数が足りないと使い方を出して 2 で終わる`() {
        val (jcmd, _, log) = prepare()
        val exit = run(jcmd, log, "1", dir.resolve("o"), "x")
        assertEquals(2, exit, log.readText())
        assertTrue(log.readText().contains("usage:"), log.readText())
    }

    private data class Setup(val jcmd: Path, val out: Path, val log: Path)

    private fun prepare(): Setup {
        assumeTrue(script != null, "with-thread-dump-watchdog.sh が見つからない")
        assumeTrue(bash != null, "bash が見つからない")
        val jcmd = dir.resolve("fake-jcmd")
        Files.writeString(
            jcmd,
            """
            |#!/usr/bin/env bash
            |if [ "${'$'}1" = "-l" ]; then
            |  echo "4242 org.example.FakeMain arg"
            |  echo "4343 jdk.jcmd/sun.tools.jcmd.JCmd -l"
            |  exit 0
            |fi
            |echo "FAKE-DUMP pid=${'$'}1 ${'$'}2 ${'$'}3"
            |
            """.trimMargin(),
        )
        jcmd.toFile().setExecutable(true)
        return Setup(jcmd, dir.resolve("dumps"), dir.resolve("script.log"))
    }

    private fun run(jcmd: Path, log: Path, delay: String, out: Path, name: String, vararg rest: String): Int {
        val args = ArrayList<String>()
        args += bash!!
        args += unixPath(script!!)
        args += delay
        args += unixPath(out)
        args += name
        args += rest
        val pb = ProcessBuilder(args)
        pb.environment()["JCMD"] = unixPath(jcmd)
        pb.environment()["THREAD_DUMP_INTERVAL"] = "1"
        pb.environment()["THREAD_DUMP_ROUNDS"] = "2"
        pb.redirectErrorStream(true)
        pb.redirectOutput(log.toFile())
        // 終わりは待つ（止まったら保険のタイムアウトが落とす。#916）
        return pb.start().waitFor()
    }

    // Git Bash は D:/... の形を読める
    private fun unixPath(p: Path): String = p.toAbsolutePath().toString().replace('\\', '/')

    private companion object {
        fun findScript(): Path? {
            var d: Path? = Path.of("").toAbsolutePath()
            while (d != null) {
                val s = d.resolve(".github/scripts/with-thread-dump-watchdog.sh")
                if (Files.exists(s)) return s
                d = d.parent
            }
            return null
        }

        fun findBash(): String? {
            val dirs = (System.getenv("PATH") ?: "").split(File.pathSeparator).filter { it.isNotEmpty() }
            for (d in dirs) {
                val lower = d.lowercase()
                // Windows の WSL の bash（System32）は、Windows のパスを読めない
                if (lower.contains("system32") || lower.contains("windowsapps")) continue
                for (n in listOf("bash", "bash.exe")) {
                    val f = File(d, n)
                    if (f.isFile) return f.absolutePath
                }
            }
            return listOf("/usr/bin/bash", "/bin/bash").firstOrNull { File(it).isFile }
        }
    }
}
