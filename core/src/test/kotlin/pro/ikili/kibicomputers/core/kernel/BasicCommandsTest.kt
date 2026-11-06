package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.vfs.FileType
import pro.ikili.kibicomputers.core.kernel.vfs.ImageFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.io.ByteArrayOutputStream
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * 標準 OS の基本コマンド（#103）を、カーネルの上でシェル `/bin/sh` から動かして確かめる。
 * 根は書けるメモリの FS（イメージの `/bin` を写す）、`/mnt` には別のメモリの FS をつなぐ（`mv` のファイルシステムをまたぐ場合）
 */
class BasicCommandsTest {
    private val limit = Duration.ofSeconds(240)

    private class ConsoleHost : MachineHost {
        private val out = ByteArrayOutputStream()

        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit

        override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
            synchronized(out) { out.write(bytes, 0, bytes.size) }
        }

        fun text(): String = synchronized(out) { out.toString(Charsets.UTF_8).replace("\r\n", "\n") }
    }

    private val image: ImageFileSystem by lazy { requireNotNull(ImageFileSystem.bundled("os")) { "os.zip がリソースにない" } }

    private val commands = listOf(
        "cat", "clear", "cp", "du", "grep", "head", "kill", "ls", "mkdir", "mv", "pwd", "rm", "rmdir", "tail", "touch", "wc",
    )

    private fun imageFile(path: String): ByteArray {
        val stat = image.stat(VfsPath.parse(path))
        val bytes = ByteArray(stat.size.toInt())
        image.read(stat.node, 0, bytes, 0, bytes.size)
        return bytes
    }

    private fun Machine.put(path: String, bytes: ByteArray) {
        val f = vfs.open(VfsPath.parse(path), OpenOptions(read = true, write = true, create = true, truncate = true))
        vfs.write(f, bytes, 0, bytes.size)
    }

    /** `/bin/sh -c [script]` を動かし、終わるまで待ってコンソールの出力を返す */
    private fun sh(script: String, files: Map<String, String> = emptyMap()): String = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val host = ConsoleHost()
            val m = h.machine(fuelPerTick = 10_000_000, host = host, memoryBytes = 32L * 1024 * 1024)
            m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
            for (d in listOf("/bin", "/tmp", "/home", "/mnt")) m.vfs.createDirectory(VfsPath.parse(d))
            m.vfs.mount(VfsPath.parse("/mnt"), TestMemoryFileSystem(), readOnly = false, source = "test:mnt")
            for (name in listOf("sh", "echo", "true", "false") + commands) m.put("/bin/$name", imageFile("/bin/$name"))
            for ((path, text) in files) m.put(path, text.toByteArray())
            val env = mapOf("PATH" to "/bin", "HOME" to "/home")
            val pid = m.exec(
                "/bin/sh",
                KernelDrivers.DEFAULT,
                SpawnOptions(args = listOf("sh", "-c", script.trimIndent()), env = env, cwd = "/", fds = SpawnOptions.ROOT_PREOPEN),
            )
            eventuallyDescribe(200_000, { "終わらない: ${host.text()}" }) {
                h.scheduler.tick()
                Thread.sleep(1)
                m.stateOf(pid).isTerminal
            }
            assertEquals(ProcessState.EXITED, m.stateOf(pid), host.text())
            repeat(5) { h.scheduler.tick() }
            host.text()
        }
    }

    private fun assertOutput(expected: String, script: String, files: Map<String, String> = emptyMap()) {
        assertEquals(expected.trimIndent() + "\n", sh(script, files), "script:\n${script.trimIndent()}")
    }

    @Test
    fun `同梱イメージosに基本コマンドが入る`() {
        for (name in commands) assertEquals(FileType.REGULAR_FILE, image.stat(VfsPath.parse("/bin/$name")).type, name)
    }

    @Test
    fun `ファイルとディレクトリの操作`() {
        assertOutput(
            """
            b
            f1
            b/
            f1
            hello
            f3
            hello
            a
            g
            z
            hello
            rm: a: Is a directory
            1
            a
            rmdir: a/b/c: Directory not empty
            1
            rmdir: a: Directory not empty
            1
            f1
            /tmp
            /tmp
            0
            mkdir: a: File exists
            1
            rm: nothing: No such file or directory
            1 0
            """,
            """
            cd /tmp
            mkdir -p a/b/c
            touch a/f1 a/b/f2
            echo hello >a/b/c/f3
            ls a | cat
            ls -F a | cat
            cp a/b/c/f3 g; cat g
            cp -r a z; ls z/b/c | cat
            cp g a/f1; cat a/f1
            ls | cat
            mv g h; cat h
            mv h a; rm a/h
            rm a 2>&1; echo ${'$'}?
            rm -r z; ls | cat
            rmdir a/b/c 2>&1; echo ${'$'}?
            rm a/b/f2 a/b/c/f3; rmdir -p a/b/c 2>&1; echo ${'$'}?
            ls a | cat
            pwd; /bin/pwd
            mkdir -p a; echo ${'$'}?
            mkdir a 2>&1; echo ${'$'}?
            rm nothing 2>&1; a=${'$'}?; rm -f nothing; echo ${'$'}a ${'$'}?
            """,
        )
    }

    @Test
    fun `cpとmvの細かい場合`() {
        assertOutput(
            """
            one
            two
            x
            y
            moved
            two
            cp: /tmp/d: cannot copy a directory into itself
            cp: /tmp/d: is a directory (not copied)
            cp: /tmp/x: source and destination are the same file
            cp: /tmp/nope: Not a directory
            /mnt/n/m:
            n1

            /mnt/n:
            m
            n2
            """,
            """
            cd /tmp
            echo one >x; echo two >y
            mkdir d
            cp x y d; cat d/x d/y
            ls d | cat
            mv d /mnt/d && ls /tmp/d 2>/tmp/null || echo moved; cat /mnt/d/y
            mkdir d
            cp -r /tmp/d /tmp/d/e 2>&1
            cp /tmp/d /tmp/e 2>&1
            cp /tmp/x /tmp/x 2>&1
            cp x y /tmp/nope 2>&1
            mkdir -p n/m; touch n/m/n1 n/n2; mv n /mnt/n; ls /mnt/n/m /mnt/n | cat
            """,
        )
    }

    @Test
    fun `lsの書き方`() {
        val out = sh(
            """
            cd /tmp
            touch .hidden b a c
            mkdir dir
            echo 12345 >dir/f
            ls | cat
            echo --
            ls -a | cat
            echo --
            ls -1 /tmp/dir
            ls -l dir
            ls dir/f /tmp/a | cat
            ls missing 2>&1; echo ${'$'}?
            ls
            """,
        )
        val lines = out.lines()
        assertEquals(listOf("a", "b", "c", "dir", "--", ".", "..", ".hidden", "a", "b", "c", "dir", "--", "f"), lines.take(14), out)
        assertTrue(Regex("^- +6 \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2} f$").matches(lines[14]), out)
        assertEquals(listOf("/tmp/a", "dir/f", "ls: missing: No such file or directory", "1"), lines.subList(15, 19), out)
        // TTY には段に並べる
        assertTrue(Regex("^a +b +c +dir$").matches(lines[19]), out)
    }

    @Test
    fun `headとtailとwc`() {
        val text = (1..15).joinToString("") { "line $it\n" }
        assertOutput(
            """
            line 1
            line 2
            line 1
            line 2
            line 3
            line 14
            line 15
            line 13
            line 14
            line 15
            line 14
            line 15
            ==> /tmp/t <==
            line 1

            ==> /tmp/s <==
            no newline
            line 15
             15  30 111 /tmp/t
            15
              0   2  10 /tmp/s
             15  32 121 total
            3
            head: /tmp/none: No such file or directory
            ==> /tmp/u <==
            u
            ==> /tmp/u <==
            u
            tail: /tmp/none: No such file or directory

            ==> /tmp/t <==
            line 15
            1
            """,
            """
            head -n 2 /tmp/t
            head -3 /tmp/t
            tail -n 2 /tmp/t
            tail -3 /tmp/t
            tail -n +14 /tmp/t
            head -n 1 /tmp/t /tmp/s; echo
            cat /tmp/t | tail -n 1
            wc /tmp/t
            wc -l </tmp/t
            wc /tmp/t /tmp/s | tail -n 2
            cat /tmp/t | head -n 3 | wc -l
            head -n 1 /tmp/none /tmp/u 2>&1
            tail -n 1 /tmp/u /tmp/none /tmp/t 2>&1; echo ${'$'}?
            """,
            mapOf("/tmp/t" to text, "/tmp/s" to "no newline", "/tmp/u" to "u\n"),
        )
    }

    @Test
    fun `grep`() {
        assertOutput(
            """
            apple pie
            Apple juice
            1:apple pie
            2
            banana
            cherry tart
            apple pie
            /tmp/f
            /tmp/f:banana
            /tmp/g:banana bread
            apple pie
            1 0 1 2
            apple pie
            Apple juice
            grep: \(a\): groups, alternation, intervals and back-references are not supported yet
            2
            b.n
            """,
            """
            grep -i apple /tmp/f
            grep -n '^apple' /tmp/f
            grep -c -i 'apple' /tmp/f
            grep -v -i apple /tmp/f
            grep -E 'e+ (p|t)' /tmp/f 2>/tmp/null; grep 'e\+ [pt]' /tmp/f
            grep -l banana /tmp/f /tmp/g /tmp/h 2>/tmp/null | head -n 1
            grep -h banana /tmp/f | grep -F -x banana >/tmp/null && grep banana /tmp/f /tmp/g
            grep -x 'apple pie' /tmp/f
            grep -q nothing /tmp/f; a=${'$'}?; grep -q apple /tmp/f; b=${'$'}?; grep -E 'x?z' /tmp/f; c=${'$'}?; grep -s x /tmp/none; echo ${'$'}a ${'$'}b ${'$'}c ${'$'}?
            grep -e '^a.*e$' -e 'J[[:lower:]]*' -i /tmp/f
            grep '\(a\)' /tmp/f 2>&1; echo ${'$'}?
            grep -F 'b.n' /tmp/g
            """,
            mapOf("/tmp/f" to "apple pie\nbanana\nApple juice\ncherry tart\n", "/tmp/g" to "banana bread\nb.n\n"),
        )
    }

    @Test
    fun `duとtouchとclear`() {
        val out = sh(
            """
            mkdir -p /tmp/d/e
            echo 0123456789 >/tmp/d/a
            echo 0123456789 >/tmp/d/e/b
            du -b /tmp/d
            du -s -b /tmp/d
            du -a -b /tmp/d/e
            du /tmp/d/a
            touch -c /tmp/none; ls /tmp
            touch /tmp/d/a; cat /tmp/d/a
            clear
            echo after
            """,
        )
        assertEquals(
            "11\t/tmp/d/e\n22\t/tmp/d\n22\t/tmp/d\n11\t/tmp/d/e/b\n11\t/tmp/d/e\n1\t/tmp/d/a\nd\n0123456789\n\u001b[H\u001b[2Jafter\n",
            out,
        )
    }

    @Test
    fun `kill`() {
        assertOutput(
            """
            INT
            QUIT
            KILL
            TERM
            143
            137
            130
            kill: 99999: No such process
            1
            kill: X: invalid signal
            2
            kill: bogus: invalid signal
            2
            """,
            """
            kill -l
            sh -c 'while true; do :; done' & kill ${'$'}!; wait ${'$'}!; echo ${'$'}?
            sh -c 'while true; do :; done' & kill -9 ${'$'}!; wait ${'$'}!; echo ${'$'}?
            sh -c 'while true; do :; done' & kill -s sigint ${'$'}!; wait ${'$'}!; echo ${'$'}?
            kill 99999 2>&1; echo ${'$'}?
            kill -X 1 2>&1; echo ${'$'}?
            kill -s bogus 1 2>&1; echo ${'$'}?
            """,
        )
    }
}
