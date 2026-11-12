package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.vfs.DevFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.FileType
import pro.ikili.kibicomputers.core.kernel.vfs.ImageFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.io.ByteArrayOutputStream
import java.nio.file.Path
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
        // #475
        "sleep", "env", "printenv", "date", "seq", "yes", "sort", "uniq", "tee", "xargs", "basename", "dirname", "which",
        // #454
        "ps", "df", "mount", "umount", "reboot", "shutdown",
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
    private fun sh(
        script: String,
        files: Map<String, String> = emptyMap(),
        environment: MachineEnvironment = MachineEnvironment.DEFAULT,
    ): String = run(script, files, environment).let { r ->
        assertEquals(ProcessState.EXITED, r.shell, r.text)
        r.text
    }

    /** [run] の結果: コンソールの出力、シェルの終わり方、終わった後のマシンの状態 */
    private class Run(val text: String, val shell: ProcessState?, val machine: MachineState)

    /** `/bin/sh -c [script]` を動かし、シェルが終わる（かマシンが止まる）まで待つ */
    private fun run(
        script: String,
        files: Map<String, String> = emptyMap(),
        environment: MachineEnvironment = MachineEnvironment.DEFAULT,
        maxProcesses: Int = 64,
        memoryBytes: Long = 32L * 1024 * 1024,
    ): Run = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val host = ConsoleHost()
            val m = h.machine(fuelPerTick = 10_000_000, host = host, maxProcesses = maxProcesses, memoryBytes = memoryBytes, environment = environment)
            m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
            for (d in listOf("/bin", "/tmp", "/home", "/mnt")) m.vfs.createDirectory(VfsPath.parse(d))
            m.vfs.mount(VfsPath.parse("/mnt"), TestMemoryFileSystem(), readOnly = false, source = "test:mnt")
            m.vfs.mount(VfsPath.parse("/dev"), DevFileSystem(), readOnly = false, source = "dev")
            for (name in listOf("sh", "echo", "true", "false") + commands) m.put("/bin/$name", imageFile("/bin/$name"))
            for ((path, text) in files) m.put(path, text.toByteArray())
            val env = mapOf("PATH" to "/bin", "HOME" to "/home")
            val pid = m.exec(
                "/bin/sh",
                KernelDrivers.DEFAULT,
                SpawnOptions(args = listOf("sh", "-c", script.trimIndent()), env = env, cwd = "/", fds = SpawnOptions.ROOT_PREOPEN),
            )
            val shell = m.runtime(pid)
            eventuallyDescribe(200_000, { "終わらない: ${host.text()}" }) {
                h.scheduler.tick()
                Thread.sleep(1)
                m.state == MachineState.STOPPED || m.stateOf(pid).isTerminal
            }
            repeat(5) { h.scheduler.tick() }
            Run(host.text(), shell?.process?.state, m.state)
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
    fun `ls -dはディレクトリの中ではなく、ディレクトリ自体を書く`() {
        // #513: `-d` がなかった（`ls: unknown option -d`）
        val out = sh(
            """
            cd /tmp
            mkdir dir
            touch dir/f a
            ls -d dir a | cat
            echo --
            ls -dF dir /tmp/dir a | cat
            echo --
            ls -d | cat
            ls -dl dir
            ls -z 2>&1; echo ${'$'}?
            """,
        )
        val lines = out.lines()
        assertEquals(listOf("a", "dir", "--", "/tmp/dir/", "a", "dir/", "--", "."), lines.take(8), out)
        assertTrue(Regex("^d +\\d+ \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2} dir$").matches(lines[8]), out)
        assertEquals(listOf("ls: unknown option -z", "usage: ls [-1adlF] [file...]", "2"), lines.subList(9, 12), out)
    }

    @Test
    fun `lsはパスに並べたファイルが多すぎると、書ける分を書いてから知らせ、1で終わる`() {
        // #510: `ls` の項目の上限（`MAX_ENTRIES`、2,048）を超えた分を、黙って落として 0 で終わっていた
        assertOutput(
            """
            ls: too many files (listing the first ones)
            status 1
            2048
            /tmp/s/10000
            /tmp/s/12047
            """,
            """
            mkdir /tmp/s; cd /tmp/s; touch ${'$'}(seq 10000 12099)
            ls /tmp/s/* >/tmp/o; echo status ${'$'}?
            wc -l </tmp/o; head -n 1 /tmp/o; tail -n 1 /tmp/o
            """,
        )
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
    fun `head -c`() {
        val text = (1..3).joinToString("") { "line $it\n" }
        assertOutput(
            """
            line 1
            l
            lin
            line 1
            0
            ==> /tmp/t <==
            li
            ==> /tmp/s <==
            no
            no newline
            """,
            """
            head -c 8 /tmp/t; echo
            head -n 1 -c 3 /tmp/t; echo
            head -c 3 -n 1 /tmp/t
            head -c 0 /tmp/t | wc -c
            head -c 2 /tmp/t /tmp/s; echo
            head -c 100 /tmp/s; echo
            """,
            mapOf("/tmp/t" to text, "/tmp/s" to "no newline"),
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

    // ---- #475 ----

    @Test
    fun `sleep`() {
        val start = System.nanoTime()
        assertOutput(
            """
            0
            0
            sleep: missing operand
            usage: sleep seconds[s|m|h|d]...
            2
            sleep: x: invalid time interval
            2
            sleep: 1.2.3: invalid time interval
            2
            sleep: unknown option -1
            usage: sleep seconds[s|m|h|d]...
            2
            """,
            """
            sleep 0.5; echo ${'$'}?
            sleep 0 .1s 0.0m; echo ${'$'}?
            sleep 2>&1; echo ${'$'}?
            sleep x 2>&1; echo ${'$'}?
            sleep 1.2.3 2>&1; echo ${'$'}?
            sleep -1 2>&1; echo ${'$'}?
            """,
        )
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(elapsedMs >= 600, "sleep が待っていない: ${elapsedMs}ms")
    }

    @Test
    fun `envとprintenv`() {
        assertOutput(
            """
            A=1
            B=2
            A=3
            1
            1
            /bin
            /home
            5
            /bin x
            PWD=/
            0
            env: nothing: No such file or directory
            127
            /home
            1
            2
            env: unknown option -z
            usage: env [-i] [-u name]... [name=value]... [command [arg...]]
            2
            """,
            """
            env -i A=1 B=2
            env - A=1 A=3
            env -i A=1 /bin/printenv A
            env -u HOME printenv HOME; echo ${'$'}?
            printenv PATH HOME
            env X=5 printenv X
            env -i PATH=/bin sh -c 'echo ${'$'}PATH x'
            env -i printenv; echo ${'$'}?
            env nothing 2>&1; echo ${'$'}?
            printenv NOPE HOME; echo ${'$'}?
            env sh -c 'exit 2'; echo ${'$'}?
            env -z 2>&1; echo ${'$'}?
            """,
        )
    }

    private fun dateAt(iso: String, script: String): String {
        val instant = java.time.Instant.parse(iso)
        val clock = object : MachineClock {
            override fun realtimeNanos(): Long = instant.epochSecond * 1_000_000_000L + instant.nano

            override fun monotonicNanos(): Long = System.nanoTime()

            override fun worldTicks(): Long = 0
        }
        return sh(script, environment = MachineEnvironment.builder().clock(clock).build())
    }

    @Test
    fun `date`() {
        val iso = "2024-02-29T13:05:09.123456789Z"
        val epoch = java.time.Instant.parse(iso).epochSecond
        assertEquals(
            """
            Thu Feb 29 13:05:09 UTC 2024
            2024-02-29 13:05:09 060 Thu Thursday Feb February 29 24 20 01 PM 4 4 UTC +0000 123456789 02/29/24 13:05 %|%Q
            $epoch
            date: 0101: setting the date is not supported
            1
            date: unknown option -x
            usage: date [-u] [+format]
            2
            """.trimIndent() + "\n",
            dateAt(
                iso,
                """
                date
                date -u '+%F %T %j %a %A %b %B %e %y %C %I %p %u %w %Z %z %N %D %R %%|%Q'
                date +%s
                date 0101 2>&1; echo ${'$'}?
                date -x 2>&1; echo ${'$'}?
                """,
            ),
        )
        assertEquals("1970-01-01 00:00:00 001 Thu 12 AM| 1\n", dateAt("1970-01-01T00:00:00Z", "date '+%F %T %j %a %I %p|%e'"))
        assertEquals("2000-12-31 23:59:59 366 Sun 7 Dec 31\n", dateAt("2000-12-31T23:59:59Z", "date '+%F %T %j %a %u %b %e'"))
    }

    @Test
    fun `seqとyes`() {
        assertOutput(
            """
            1
            2
            3
            -1 0 1
            0.0,0.5,1.0,1.5,2.0
            08
            09
            10
            5
            3
            1
            1.5
            -5
            -2
            01
            done
            y
            y
            a b
            a b
            seq: 0: invalid zero increment
            2
            seq: x: invalid number
            2
            seq: missing operand
            usage: seq [-w] [-s separator] [first [increment]] last
            2
            """,
            """
            seq 3
            seq -s ' ' -1 1
            seq -s, 0 0.5 2
            seq -w 8 10
            seq 5 -2 1
            seq 1.5 1.7
            seq -w -5 3 1
            seq 2 1; echo done
            yes | head -n 2
            yes a b | head -n 2
            seq 1 0 2 2>&1; echo ${'$'}?
            seq x 2>&1; echo ${'$'}?
            seq 2>&1; echo ${'$'}?
            """,
        )
    }

    @Test
    fun `sortとuniq`() {
        val words = "pear\nApple\nbanana\napple\npear\n10 x\n9 y\n-3 z\n"
        assertOutput(
            """
            -3 z
            10 x
            9 y
            Apple
            apple
            banana
            pear
            pear
            --
            pear
            pear
            banana
            apple
            Apple
            9 y
            10 x
            -3 z
            --
            -3 z
            Apple
            apple
            banana
            pear
            9 y
            10 x
            --
            Apple
            banana
            pear
            --
            b:1
            c:2
            a:3
            --
            3 a
            1 b
            2 c
            --
            2.5
            10
            --
                  1 Apple
                  1 apple
                  1 banana
                  2 pear
            --
            pear
            --
            Apple
            banana
            --
                  2 Apple
            --
            no newline
            z
            --
            a
            b
            c
            """,
            """
            sort /tmp/w
            echo --
            sort -r /tmp/w
            echo --
            sort -n /tmp/w | uniq
            echo --
            grep -v ' ' /tmp/w | sort -u -f
            echo --
            sort -t: -k2 -n /tmp/k
            echo --
            sort -k2 /tmp/f
            echo --
            printf '10\n2.5\n' | sort -n
            echo --
            grep -v ' ' /tmp/w | sort | uniq -c
            echo --
            grep -v ' ' /tmp/w | sort | uniq -d
            echo --
            grep -v ' ' /tmp/w | sort | uniq -u | grep -v apple
            echo --
            grep -v ' ' /tmp/w | sort -f | grep -i apple | uniq -c -i
            echo --
            printf 'z\nno newline' | sort
            echo --
            printf 'c\nb\na\n' >/tmp/s; sort -o /tmp/s /tmp/s; cat /tmp/s
            """,
            mapOf("/tmp/w" to words, "/tmp/k" to "a:3\nc:2\nb:1\n", "/tmp/f" to "2 c\n3 a\n1 b\n"),
        )
    }

    @Test
    fun `sortで多くの行を並べる`() {
        assertOutput(
            """
            1
            2
            9999
            20000
            """,
            """
            seq 20000 -1 1 | sort -n | head -n 2
            seq 20000 | sort | tail -n 1
            seq 20000 | sort -r -n | head -n 1
            """,
        )
    }

    @Test
    fun `teeとxargs`() {
        assertOutput(
            """
            hi
            hi
            hi
            again
            a b c d
            a b
            c d
            x-1-x
            x-2 3-x
            echo p q
            p q
            a b
            c
            1
            2
            123
            0
            xargs: nothing: No such file or directory
            127
            xargs: unmatched quote
            1
            nul ok
            """,
            """
            echo hi | tee /tmp/a /tmp/b
            cat /tmp/a /tmp/b
            echo again | tee -a /tmp/a >/tmp/null; tail -n 1 /tmp/a
            printf 'a b\nc\n  d\n' | xargs
            printf 'a b c d' | xargs -n 2
            printf '1\n  2 3\n' | xargs -I {} echo x-{}-x
            echo p q | xargs -t echo 2>&1
            printf '"a b" c\n' | xargs -n 1 echo
            printf '1\n2\n' | xargs -n 1 sh -c 'echo ${'$'}0; exit 1'; echo ${'$'}?
            printf '' | xargs -r false; echo ${'$'}?
            echo a | xargs nothing 2>&1; echo ${'$'}?
            echo "'a" | xargs 2>&1; echo ${'$'}?
            printf 'nul\0ok\0' | xargs -0 echo
            """,
        )
    }

    @Test
    fun `xargsが読むのがパイプでも、Ctrl+Cで動いている子が止まる`() = timeout(limit) {
        // #482: xargs の標準入力はパイプなので、前景を渡すかを標準エラー（TTY）で決める。渡さないと sleep が孤児として残る
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val host = ConsoleHost()
            val m = h.machine(fuelPerTick = 10_000_000, host = host, memoryBytes = 32L * 1024 * 1024)
            m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
            for (d in listOf("/bin", "/tmp", "/home")) m.vfs.createDirectory(VfsPath.parse(d))
            for (name in listOf("sh", "echo", "true", "false") + commands) m.put("/bin/$name", imageFile("/bin/$name"))
            val pid = m.exec("/bin/sh", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("sh"), env = mapOf("PATH" to "/bin"), fds = SpawnOptions.ROOT_PREOPEN))

            fun live() = m.lock.withLockValue { m.kernel.processes.all().filter { !it.state.isTerminal }.map { it.pid to it.args } }
            fun step(describe: () -> String, millis: Long = 150_000, done: () -> Boolean) = eventuallyDescribe(millis, describe) {
                h.scheduler.tick()
                Thread.sleep(1)
                done()
            }

            step({ "プロンプトが出ない: ${host.text()}" }) { host.text().endsWith("$ ") }
            var seen = host.text().length
            m.offerConsoleInput("seq 3 | xargs sleep 100\n".toByteArray())
            // #492: Ctrl+C は sleep が前景になってから1回だけ送る。プロンプトでの Ctrl+C も $? を 130 にするので、
            // 余分に送ると status 130 の検査が退行を捕まえられない
            fun foreground() = m.lock.withLockValue { m.consoleTtyLocked().foregroundPid }
            fun sleepPid() = live().firstOrNull { it.second.firstOrNull() == "sleep" }?.first
            step({ "sleep が前景にならない: ${live()} / ${foreground()}" }) { sleepPid().let { it != null && it == foreground() } }
            m.offerConsoleInput(byteArrayOf(3))
            step({ "プロンプトに戻らない: ${host.text()}" }) { host.text().let { it.length > seen && it.endsWith("$ ") } }
            // xargs だけでなく、走っていた sleep も止まる
            step({ "残っている: ${live()}" }, 20_000) { live().map { it.first } == listOf(pid) }
            seen = host.text().length
            m.offerConsoleInput("echo status ${'$'}?\n".toByteArray())
            step({ "終わらない: ${host.text()}" }) { host.text().length > seen && host.text().endsWith("$ ") }
            m.offerConsoleInput("exit\n".toByteArray())
            step({ "終わらない: ${host.text()}" }) { m.stateOf(pid).isTerminal }
            assertTrue(host.text().contains("status 130\n"), host.text())
        }
    }

    @Test
    fun `basenameとdirnameとwhich`() {
        assertOutput(
            """
            c
            c
            /
            c
            a.tar
            file.txt
            a
            b
            a/b
            a
            .
            /
            /
            /bin/ls
            /bin/sh
            1
            /bin/ls
            0
            """,
            """
            basename /a/b/c
            basename /a/b/c/
            basename /
            basename c.txt .txt
            basename a.tar.gz .gz
            basename file.txt file.txt
            basename -a -s .c a.c b
            dirname a/b/c a/b/
            dirname c
            dirname /a //a
            which ls sh
            which nothing; echo ${'$'}?
            which /bin/ls; echo ${'$'}?
            """,
        )
    }

    @Test
    fun `devのnull・zero・tty`() {
        assertOutput(
            """
            null
            tty
            zero
            0
            0
            4
            8
            hi
            ok
            12
            /dev/null
            """,
            """
            ls /dev | cat
            echo x >/dev/null; echo ${'$'}?
            cat /dev/null | wc -c
            head -c 4 /dev/zero | wc -c
            head -c 8 /dev/zero | cat >/dev/null; head -c 8 /dev/zero | wc -c
            echo hi >/dev/tty
            ls /nothing 2>/dev/null || echo ok
            head -c 12 /dev/zero >/tmp/z; wc -c </tmp/z
            ls /dev/null
            """,
        )
    }

    // ---- #454: ps・df・mount・umount・reboot・shutdown ----

    @Test
    fun `psはpidの昇順にPID・PPID・STAT・CMDを書き、知らないオプションは2`() {
        val out = sh(
            """
            ps
            echo ${'$'}?
            ps -x 2>&1; echo ${'$'}?
            ps a 2>&1; echo ${'$'}?
            """,
        )
        val lines = out.lines()
        assertEquals("PID PPID STAT CMD", lines[0].trim().replace(Regex(" +"), " "), out)
        val rows = lines.drop(1).takeWhile { Regex("""^ *\d+ """).containsMatchIn(it) }
        val pids = rows.map { it.trim().split(Regex(" +"))[0].toInt() }
        assertEquals(pids.sorted(), pids, "pid の昇順: $out")
        assertTrue(rows.any { Regex("""^ *\d+ +0 S +sh -c """).containsMatchIn(it) }, "sh が待っている: $out")
        assertTrue(rows.any { Regex("""^ *\d+ +\d+ R +ps$""").matches(it) }, "ps 自身が動いている: $out")
        assertEquals(
            listOf("0", "ps: unknown option -x", "usage: ps [-e|-A]", "2", "ps: extra operand", "usage: ps [-e|-A]", "2"),
            lines.drop(1 + rows.size).filter { it.isNotEmpty() },
            out,
        )
    }

    /**
     * 親なしのゾンビを [count] 個作るスクリプト（`sh -c 'true &'` の子は親が先に終わるのでゾンビで残る）。
     * ゾンビの CMD は `sh -c <スクリプト>` で、スクリプトに 255 バイトを超える注釈を付けてあるので、1 行が約 335 バイトになる。
     * [wait] なら、`ps` で数えて [count] 個できるまで待つ（`ps` が動けない RAM の上限では使えない）
     */
    private fun zombieScript(count: Int, wait: Boolean = true): String =
        "for i in \$(seq $count); do sh -c 'true & # ${"x".repeat(300)}'; done\n" +
            if (wait) {
                "for i in \$(seq 400); do [ \$(ps -e | grep -c '<defunct>\$') -ge $count ] && break; sleep 0.05; done\n"
            } else {
                "sleep 1\n"
            }

    @Test
    fun `psの結果が静的な領域を溢れても、ページの残りを使って全部書く`() {
        // 600 件で約 200 KiB（静的な 64 KiB の外に約 140 KiB）。割り当ては 1 件に 3 回あり、割り当てのたびにページを増やすと
        // 数百ページの RAM が要るので、RAM の上限 512 KiB で見分けられる
        val r = run(
            zombieScript(600) + "ps -e | grep -c '<defunct>\$'\n",
            maxProcesses = 2000,
            memoryBytes = 512L * 1024,
        )
        assertEquals(ProcessState.EXITED, r.shell, r.text)
        assertTrue((r.text.lines().lastOrNull { it.isNotEmpty() }?.toIntOrNull() ?: 0) >= 600, r.text)
    }

    @Test
    fun `psは増やせないとき、cabi_reallocが線形メモリの外を返してトラップし、シェルは動き続ける`() {
        // RAM の上限 192 KiB。1,200 件は約 400 KiB で、静的な 64 KiB の領域とページを増やしても足りない。ps は OOM で落ち、シェルは次へ進む
        val r = run(
            zombieScript(1200, wait = false) + "ps -e >/dev/null; echo status \$?\necho end\n",
            maxProcesses = 2000,
            memoryBytes = 250_000L,
        )
        assertEquals(ProcessState.EXITED, r.shell, r.text)
        assertTrue(Regex("""kibi: ps \(pid \d+\) crashed: out of memory""").containsMatchIn(r.text), r.text)
        assertEquals(listOf("status 139", "end"), r.text.lines().filter { it.startsWith("status") || it == "end" }, r.text)
    }

    @Test
    fun `psは親なしのゾンビを既定で隠し、-eと-Aで出す`() {
        val out = sh(
            """
            sh -c 'true &'
            for i in ${'$'}(seq 400); do ps -e | grep -q 'Z    .*<defunct>${'$'}' && break; sleep 0.05; done
            ps | grep -c '<defunct>${'$'}'
            ps -e | grep -c 'Z    .*<defunct>${'$'}'
            ps -A | grep -c 'Z    .*<defunct>${'$'}'
            ps -e | grep -c ' 0 Z    .*<defunct>${'$'}'
            """,
        )
        // `&` の子（サブシェル）は、親の sh -c が先に終わるので親なしのゾンビになる。grep は sh の引数（スクリプト）に合わないよう行末で合わせる
        assertEquals(listOf("0", "1", "1", "1", ""), out.lines(), out)
    }

    @Test
    fun `dfは容量のないマウントを-にし、devは0で使用率は-`() {
        val out = sh(
            """
            df
            df -i /dev
            df -h /mnt/nothing 2>&1; echo ${'$'}?
            df -h /dev / | tail -n 2
            df -x 2>&1; echo ${'$'}?
            """,
        )
        assertEquals(
            """
            Filesystem 1K-blocks Used Available Use% Mounted on
            test:root          -    -         -    - /
            test:mnt           -    -         -    - /mnt
            dev                0    0         0    - /dev
            Filesystem Inodes IUsed IFree IUse% Mounted on
            dev             0     0     0     - /dev
            df: /mnt/nothing: No such file or directory
            1
            dev           0    0     0    - /dev
            test:root     -    -     -    - /
            df: unknown option -x
            usage: df [-h|-k] [-i] [file...]
            2
            """.trimIndent().lines() + "",
            out.lines(),
        )
    }

    private fun diskEnvironment(dir: Path, vararg ids: String): MachineEnvironment =
        MachineEnvironment.builder().disks(DiskSnapshot.of(ids.associateWith { ResolvedDisk(dir.resolve(it), 1L shl 20, 64) })).build()

    @Test
    fun `df -hは切り上げて次の単位に届く値を次の単位で書く`(@TempDir dir: Path) {
        // 容量 1048575 バイト（1 MiB の 1 バイト下）は、1024K ではなく 1.0M になる
        val env = MachineEnvironment.builder().disks(DiskSnapshot.of(mapOf("d1" to ResolvedDisk(dir.resolve("d1"), 1048575L, 64)))).build()
        val out = sh(
            """
            mount d1 /a
            for i in ${'$'}(seq 400); do [ ${'$'}(mount -l | grep -c ' /a') = 1 ] && break; sleep 0.05; done
            df -h /a | tail -n 1
            """,
            environment = env,
        )
        val cols = out.lines().first { it.isNotEmpty() }.trim().split(Regex(" +"))
        assertEquals("1.0M", cols[1], out)
    }

    @Test
    fun `mountでディスクをマウントし、dfとumountで扱う`(@TempDir dir: Path) {
        val out = sh(
            """
            mount -l
            mount d1 /a/b
            for i in ${'$'}(seq 400); do [ ${'$'}(mount -l | grep -c ' /mnt/d') = 2 ] && break; sleep 0.05; done
            mount -l
            umount /mnt/d2 /mnt/d3
            mount -r disk:d2 /mnt/two
            mount
            mount -l
            echo hi >/a/b/f; cat /a/b/f
            touch /mnt/two/f 2>/dev/null || echo ro
            df /a/b/f | tail -n 1
            df -i /a/b | tail -n 1
            cd /a/b; df . | tail -n 1; cd /
            df /a | tail -n 1
            mount disk:d1 /c 2>&1; echo ${'$'}?
            mount disk:d3 /a/b 2>&1; echo ${'$'}?
            mount disk:nope /y 2>&1; echo ${'$'}?
            mount disk:image:os /y 2>&1; echo ${'$'}?
            umount /a/b/ /mnt/two; echo ${'$'}?
            mount -l | tail -n 3
            umount /a/b 2>&1; echo ${'$'}?
            umount / /dev 2>&1; echo ${'$'}?
            mount disk:d1 2>&1; echo ${'$'}?
            umount 2>&1; echo ${'$'}?
            """,
            environment = diskEnvironment(dir, "d1", "d2", "d3"),
        )
        val lines = out.lines()
        assertEquals(
            listOf(
                "Disk    1K-blocks Entries Mounted on",
                "disk:d1      1024      64 -",
                "disk:d2      1024      64 -",
                "disk:d3      1024      64 -",
                // mount の後始末が自動マウントの仕事を積み直し、まだ扱っていない挿し込みを /mnt/<名前> にマウントする（ADR 0056）
                "Disk    1K-blocks Entries Mounted on",
                "disk:d1      1024      64 /a/b",
                "disk:d2      1024      64 /mnt/d2",
                "disk:d3      1024      64 /mnt/d3",
                "test:root on / type disk (rw)",
                "test:mnt on /mnt type disk (rw)",
                "dev on /dev type dev (rw)",
                "disk:d1 on /a/b type disk (rw)",
                "disk:d2 on /mnt/two type disk (ro)",
                "Disk    1K-blocks Entries Mounted on",
                "disk:d1      1024      64 /a/b",
                "disk:d2      1024      64 /mnt/two",
                "disk:d3      1024      64 -",
                "hi",
                "ro",
            ),
            lines.take(19),
            out,
        )
        assertTrue(Regex("""^disk:d1 +1024 +\d+ +\d+ +\d+% /a/b$""").matches(lines[19]), out)
        assertTrue(Regex("""^disk:d1 +64 +1 +63 +2% /a/b$""").matches(lines[20]), out)
        assertEquals(lines[19], lines[21], "cwd の . も同じマウント: $out")
        assertTrue(Regex("^test:root +- +- +- +- /$").matches(lines[22]), "合成のディレクトリは位置の最も長い一致: $out")
        assertEquals(
            listOf(
                "mount: /c: Device or resource busy", "1",
                "mount: /a/b: Device or resource busy", "1",
                "mount: disk:nope: No such file or directory", "1",
                "mount: disk:image:os: No such file or directory", "1",
                "0",
                "disk:d1      1024      64 -",
                "disk:d2      1024      64 -",
                "disk:d3      1024      64 -",
                "umount: /a/b: Invalid argument", "1",
                "umount: /: Device or resource busy",
                "umount: /dev: Device or resource busy", "1",
                "mount: missing operand", "usage: mount [-l] | mount [-r|-w] disk dir", "2",
                "umount: missing operand", "usage: umount dir...", "2",
            ),
            lines.drop(23).filter { it.isNotEmpty() },
            out,
        )
    }

    @Test
    fun `起動を通らないマシンではrebootとshutdown -rはNot supportedで1`() {
        assertOutput(
            """
            reboot: Not supported
            1
            shutdown: Not supported
            1
            shutdown: only 'now' is supported
            usage: shutdown [-h|-r] [now]
            2
            reboot: extra operand
            usage: reboot
            2
            """,
            """
            reboot 2>&1; echo ${'$'}?
            shutdown -r now 2>&1; echo ${'$'}?
            shutdown +5 2>&1; echo ${'$'}?
            reboot now 2>&1; echo ${'$'}?
            """,
        )
    }

    @Test
    fun `shutdownはマシンを止め、後ろのコマンドは動かない`() {
        val r = run(
            """
            echo before
            shutdown -h now
            echo after
            """,
        )
        assertEquals(listOf("before", ""), r.text.lines())
        assertEquals(MachineState.STOPPED, r.machine)
        assertEquals(ProcessState.KILLED, r.shell)
    }
}
