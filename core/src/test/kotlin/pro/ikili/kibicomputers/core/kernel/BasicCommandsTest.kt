package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
    ): String = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val host = ConsoleHost()
            val m = h.machine(fuelPerTick = 10_000_000, host = host, memoryBytes = 32L * 1024 * 1024, environment = environment)
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
}
