package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
 * 標準 OS のシェル `/bin/sh`（#101）を、カーネルの上で動かして確かめる。
 * 外のコマンドには、イメージの `echo`・`true`・`false` と、`os/test/shprobe.zig`（`cat`・`args`・`envget`・`exitwith`・`errout`・`hang`・`nap`）を使う
 */
class ShellTest {
    private val limit = Duration.ofSeconds(180)

    private class ConsoleHost : MachineHost {
        private val out = ByteArrayOutputStream()

        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit

        override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
            synchronized(out) { out.write(bytes, 0, bytes.size) }
        }

        fun text(): String = synchronized(out) { out.toString(Charsets.UTF_8).replace("\r\n", "\n") }
    }

    private class Result(val console: String, val exitCode: Int?)

    private val image: ImageFileSystem by lazy { requireNotNull(ImageFileSystem.bundled("os")) { "os.zip がリソースにない" } }

    private val probe: ByteArray by lazy {
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/os-shprobe.wasm")) { "os-shprobe.wasm がリソースにない" }
            .use { it.readBytes() }
    }

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

    /** 書けるメモリの FS に、イメージの `/bin` のプログラムと試しのコマンド、[files] を置く */
    private fun root(m: Machine, files: Map<String, String>) {
        m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
        for (d in listOf("/bin", "/tmp", "/home", "/home/sub")) m.vfs.createDirectory(VfsPath.parse(d))
        for (name in listOf("sh", "echo", "true", "false", "kill")) m.put("/bin/$name", imageFile("/bin/$name"))
        for (name in listOf("cat", "args", "envget", "exitwith", "errout", "hang", "nap")) m.put("/bin/$name", probe)
        for ((path, text) in files) m.put(path, text.toByteArray())
    }

    /**
     * `/bin/sh` を [args] で動かして終わるまで待つ。[input] はコンソールに打つ行（プロンプトが出るたびに 1 行）。
     * [after] は、シェルが終わった後に、同じマシンで確かめること。
     * [ready] は、[input] の i 行目を打ってよいか（偽の間は、プロンプトが出ていても待つ）
     */
    private fun run(
        args: List<String>,
        files: Map<String, String> = emptyMap(),
        input: List<String> = emptyList(),
        ready: (Int, Machine) -> Boolean = { _, _ -> true },
        after: (Harness, Machine) -> Unit = { _, _ -> },
    ): Result = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val host = ConsoleHost()
            val m = h.machine(fuelPerTick = 10_000_000, host = host, memoryBytes = 16L * 1024 * 1024)
            root(m, files)
            val env = mapOf("PATH" to "/bin", "HOME" to "/home")
            val pid = m.exec("/bin/sh", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("sh") + args, env = env, cwd = "/", fds = SpawnOptions.ROOT_PREOPEN))
            var next = 0
            var seen = 0
            eventuallyDescribe(150_000, { "終わらない: ${host.text()}" }) {
                h.scheduler.tick()
                val text = host.text()
                if (next < input.size && text.length > seen && (text.endsWith("$ ") || text.endsWith("> ")) && ready(next, m)) {
                    seen = text.length
                    m.offerConsoleInput((input[next++] + "\n").toByteArray())
                }
                Thread.sleep(1)
                m.stateOf(pid).isTerminal
            }
            val exitCode = m.lock.withLockValue { m.kernel.processes[pid]!!.exitCode }
            assertEquals(ProcessState.EXITED, m.stateOf(pid), host.text())
            repeat(5) { h.scheduler.tick() }
            after(h, m)
            Result(host.text(), exitCode)
        }
    }

    private fun sh(script: String, files: Map<String, String> = emptyMap()): Result = run(listOf("-c", script.trimIndent()), files)

    private fun assertOutput(expected: String, script: String, files: Map<String, String> = emptyMap()) {
        val r = sh(script, files)
        assertEquals(expected.trimIndent() + "\n", r.console, "script:\n${script.trimIndent()}")
    }

    @Test
    fun `同梱イメージosにbin_shが入る`() {
        assertEquals(FileType.REGULAR_FILE, image.stat(VfsPath.parse("/bin/sh")).type)
    }

    @Test
    fun `コマンドの実行と引数と引用`() {
        assertOutput(
            """
            <a> <b c> <d e> <f g> <h"i> <$\> <x>
            <> <a b>
            <*> <~> </home/x> <a~>
            hello, world
            built-in and
            external
            """,
            """
            args a "b c" 'd e' f\ g 'h"i' "\$\\" x
            args "" "a ""b"
            args '*' "~" ~/x a~
            echo hello, world
            echo built-in and; /bin/echo external
            """,
        )
    }

    @Test
    fun `パイプとリダイレクト`() {
        assertOutput(
            """
            one
            one
            two
            to stderr
            via pipe
            three stages
            from file
            here 42
            literal ${'$'}x
            	keep tab
            strip
            in group
            after
            """,
            """
            echo one >/tmp/a
            cat /tmp/a
            echo two >>/tmp/a
            cat </tmp/a
            errout to stderr 2>&1 | cat
            echo via pipe | cat | cat
            /bin/echo three stages | cat | cat
            echo from file >/tmp/b; cat /tmp/b | cat
            x=42
            cat <<EOF
            here ${'$'}x
            EOF
            cat <<'EOF'
            literal ${'$'}x
            EOF
            cat <<EOF
            	keep tab
            EOF
            cat <<-EOF
            		strip
            	EOF
            { echo in group; } >/tmp/c
            cat /tmp/c
            echo hidden >/tmp/null 2>&1 && echo after
            """,
        )
    }

    @Test
    fun `変数と環境変数と終了コード`() {
        assertOutput(
            """
            v=1 w=
            1 0 127
            FOO=bar
            ONLY=temp
            ONLY unset
            NOT unset
            def set alt
            3 file.tar gz file
            7 15 1
            2 <a b> <c>
            2 c
            0
            exit 5
            """,
            """
            v=1; echo "v=${'$'}v w=${'$'}w"
            false; a=${'$'}?; true; b=${'$'}?; nosuchcmd 2>/tmp/null; echo ${'$'}a ${'$'}b ${'$'}?
            export FOO=bar; envget FOO
            ONLY=temp envget ONLY; envget ONLY
            NOT=1; envget NOT
            x=set; echo ${'$'}{none:-def} ${'$'}{x:-def} ${'$'}{x:+alt}
            f=file.tar.gz; echo ${'$'}{#x} ${'$'}{f%.*} ${'$'}{f##*.} ${'$'}{f%%.*}
            echo ${'$'}((1+2*3)) ${'$'}(( (1+2) * 5 )) ${'$'}((10 > 3 && 2 != 3))
            set -- "a b" c; echo ${'$'}# "<${'$'}1>" "<${'$'}2>"
            shift; echo ${'$'}((${'$'}# + 1)) ${'$'}1
            unset x; echo ${'$'}{#x}
            exitwith 5; echo exit ${'$'}?
            """,
        )
    }

    @Test
    fun `if・for・while・case・関数`() {
        assertOutput(
            """
            two
            a-1 b-1 c-1
            0 1 2
            3 2 1
            fruit veg other
            hello Ann 2
            120
            local inner
            after outer
            1 3
            ret 4
            """,
            """
            n=2
            if [ ${'$'}n -eq 1 ]; then echo one
            elif [ ${'$'}n = 2 ]; then echo two
            else echo many; fi
            s=; for x in a b c; do s="${'$'}s ${'$'}x-1"; done; echo ${'$'}s
            s=; i=0; while [ ${'$'}i -lt 3 ]; do s="${'$'}s ${'$'}i"; i=${'$'}((i+1)); done; echo ${'$'}s
            s=; until [ ${'$'}i -eq 0 ]; do s="${'$'}s ${'$'}i"; i=${'$'}((i-1)); done; echo ${'$'}s
            s=
            for w in apple carrot rock; do
              case ${'$'}w in
                apple|pear) s="${'$'}s fruit" ;;
                car*) s="${'$'}s veg" ;;
                *) s="${'$'}s other"
              esac
            done
            echo ${'$'}s
            greet() { echo "hello ${'$'}1 ${'$'}#"; }
            greet Ann x
            fact() {
              if [ ${'$'}1 -le 1 ]; then echo 1; return; fi
              echo ${'$'}(( ${'$'}1 * ${'$'}(fact ${'$'}((${'$'}1 - 1))) ))
            }
            fact 5
            v=outer; f() { local v=inner; echo local ${'$'}v; }; f; echo after ${'$'}v
            s=; for i in 1 2 3 4; do [ ${'$'}i = 2 ] && continue; s="${'$'}s ${'$'}i"; [ ${'$'}i = 3 ] && break; done; echo ${'$'}s
            r() { return 4; }; r; echo ret ${'$'}?
            """,
        )
    }

    @Test
    fun `コマンド置換とサブシェル`() {
        assertOutput(
            """
            [inner] [/home/sub]
            nested: x-y
            back tick
            sub 1 /home
            out=
            hidden=secret
            multi
            line
            status 1
            same
            fn x
            y=1 z=2
            """,
            """
            cd /home/sub
            echo "[${'$'}(echo inner)]" "[${'$'}(pwd)]"
            cd ..
            echo "nested: ${'$'}(echo ${'$'}(echo x)-y)"
            echo `echo back` tick
            (cd /tmp; x=1; echo sub ${'$'}x ${'$'}OLDPWD); echo "out=${'$'}x"
            s=secret; echo "hidden=${'$'}(echo ${'$'}s | cat)"
            printf 'multi\nline\n' | cat
            false; (echo status ${'$'}?) | cat; [ ${'$'}${'$'} = ${'$'}(echo ${'$'}${'$'} | cat) ] && echo same
            f() { echo fn ${'$'}1; }; f x | cat
            y=1; export z=2; { echo y=${'$'}y z=${'$'}z; } | cat
            """,
        )
    }

    @Test
    fun `スクリプトの実行`() {
        val files = mapOf(
            "/home/s.sh" to "echo script \"\$0\" \"\$1\" \$#\nexit 3\n",
            "/home/run" to "#!/bin/sh\necho shebang \$1\n",
            "/home/plain" to "echo plain \$*\n",
            "/home/lib.sh" to "LIBVAR=loaded\nlibf() { echo libf \$1; }\n",
        )
        assertOutput(
            """
            script /home/s.sh a 2
            status 3
            shebang one
            plain p q
            loaded
            libf z
            """,
            """
            sh /home/s.sh a b; echo status ${'$'}?
            /home/run one
            cd /home; ./plain p q
            . /home/lib.sh; echo ${'$'}LIBVAR; libf z
            """,
            files,
        )
        // ファイルを引数にしたシェルと、終了コード
        val r = run(listOf("/home/s.sh", "x"), files)
        assertEquals("script /home/s.sh x 1\n", r.console)
        assertEquals(3, r.exitCode)
        // set -e は失敗で止まる
        val e = sh("set -e; echo before; false; echo after")
        assertEquals("before\n", e.console)
        assertEquals(1, e.exitCode)
    }

    @Test
    fun `誤り`() {
        val nf = sh("nosuch arg; echo \$?")
        assertEquals("sh: nosuch: not found\n127\n", nf.console)
        val syn = sh("echo ok; if then")
        assertTrue(syn.console.contains("syntax error"), syn.console)
        assertEquals(2, syn.exitCode)
        val unset = sh("echo \${nope:?missing}; echo not reached")
        assertEquals("sh: nope: missing\n", unset.console)
        assertEquals(1, unset.exitCode)
    }

    @Test
    fun `パス名の展開と後ろのジョブ`() {
        val files = mapOf("/tmp/a.txt" to "", "/tmp/b.txt" to "", "/tmp/c.log" to "")
        assertOutput(
            """
            /tmp/a.txt /tmp/b.txt
            /tmp/*.none
            a.txt b.txt c.log
            *.txt
            bg done
            """,
            """
            echo /tmp/*.txt
            echo /tmp/*.none
            cd /tmp; echo *
            echo "*.txt"
            echo bg >/tmp/bg & wait; cat /tmp/bg | { read v; echo ${'$'}v done; }
            """,
            files,
        )
    }

    @Test
    fun `jobsは後ろのジョブの番号・状態・コマンドを書く`() {
        val d = "${'$'}"
        val long = "echo " + "x".repeat(100)
        assertOutput(
            """
            [1] Exit 3  exitwith 3
            [2] Done  true
            [3] Running  hang &
            [4] Done  ${"echo " + "x".repeat(72)}...
            again
            [1] Exit 3  exitwith 3
            pids ok
            long ok
            sh: jobs: %1: job specs are not supported
            2
            dashdash 0
            sh: jobs: -l: job specs are not supported
            2
            """,
            """
            exitwith 3 &
            true &
            hang & h=$d!
            $long >/tmp/out &
            while :; do
                jobs >/tmp/j; n=0
                while read l; do case ${d}l in *Running*) n=${d}((n+1));; esac; done </tmp/j
                [ ${d}n = 1 ] && break
            done
            cat /tmp/j
            echo again
            jobs >/tmp/j
            read first </tmp/j; echo "${d}first"
            jobs -p >/tmp/p; { read a; read b; read c; } </tmp/p; [ ${d}c = ${d}h ] && echo pids ok
            jobs -l >/tmp/l; while read l; do case ${d}l in "[3] ${d}h Running  hang &") echo long ok;; esac; done </tmp/l
            jobs %1; echo ${d}?
            jobs -- >/tmp/j; echo dashdash ${d}?
            jobs -- -l; echo ${d}?
            kill ${d}h; wait ${d}h
            """,
        )
    }

    @Test
    fun `会話的なシェルのjobsは、終わったと書いたジョブを片付ける`() {
        // #492: 同じ行の中で終わったジョブを使う（wait で表を空にしない。プロンプトでの知らせも挟まらない）。
        // Done と書くまで jobs を回し、書いた後の jobs が何も書かないことを確かめる
        val d = "${'$'}"
        val line = "true & while :; do jobs >/tmp/j; read l </tmp/j; case ${d}l in *Done*) break;; esac; done; " +
            "cat /tmp/j; jobs; jobs; echo end"
        val r = run(emptyList(), input = listOf("hang &", "jobs", "kill ${d}!; wait", line, "exit"))
        val text = r.console.replace(Regex(" *\r\u001b\\[K\r\u001b\\[J"), "")
        assertTrue(text.contains("[1] Running  hang &\n"), text)
        assertTrue(text.contains("\n[1] Done  true\nend\n$ exit"), text)
        // 片付けたものは、プロンプトの前にもう一度知らせない
        assertEquals(1, Regex("Done  true").findAll(text).count(), text)
    }

    @Test
    fun `会話的なシェルは、終わったジョブをコマンド付きでプロンプトの前に知らせる`() {
        // #492: 知らせも jobs と同じ形にする。
        // #497: ジョブが終わったのをカーネルで確かめてから次の行を打つ（時間に頼らない）
        val exited = { i: Int, m: Machine ->
            i != 1 || m.lock.withLockValue {
                m.kernel.processes.all().none { it.args.firstOrNull() == "exitwith" && !it.state.isTerminal }
            }
        }
        val r = run(emptyList(), input = listOf("exitwith 3 &", "echo end", "exit"), ready = exited)
        val text = r.console.replace(Regex(" *\r\u001b\\[K\r\u001b\\[J"), "")
        assertTrue(text.contains("[1] Exit 3  exitwith 3\n"), text)
    }

    @Test
    fun `会話的なシェルでは、Ctrl+Cは前景の子だけを止める`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val host = ConsoleHost()
            val m = h.machine(fuelPerTick = 10_000_000, host = host, memoryBytes = 16L * 1024 * 1024)
            root(m, emptyMap())
            val pid = m.exec("/bin/sh", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("sh"), env = mapOf("PATH" to "/bin"), fds = SpawnOptions.ROOT_PREOPEN))
            var step = 0
            var lastSent = 0L
            eventuallyDescribe(150_000, { "終わらない: ${host.text()}" }) {
                h.scheduler.tick()
                Thread.sleep(1)
                val text = host.text()
                val now = System.nanoTime()
                when {
                    step == 0 && text.endsWith("$ ") -> {
                        m.offerConsoleInput("cat\n".toByteArray())
                        step = 1
                    }
                    // cat が前景になるまで、Ctrl+C を何度か送る（シェルは無視する）
                    step == 1 && !text.endsWith("$ ") && now - lastSent > 200_000_000 -> {
                        m.offerConsoleInput(byteArrayOf(3))
                        lastSent = now
                    }
                    step == 1 && text.endsWith("$ ") -> {
                        m.offerConsoleInput("echo after ${'$'}?\n".toByteArray())
                        step = 2
                    }
                    step == 2 && text.contains("after ") && text.endsWith("$ ") -> {
                        m.offerConsoleInput("exit\n".toByteArray())
                        step = 3
                    }
                }
                m.stateOf(pid).isTerminal
            }
            assertTrue(host.text().contains("after 130\n"), host.text())
        }
    }

    @Test
    fun `パイプの大きさを超えるヒアドキュメントでも、待ち合わずに渡る`() {
        // パイプ（4 KiB）2 つ分と cat の読み込み先を十分に超える。-c の引数には大きすぎるので、スクリプトのファイルにする
        val body = (1..200).joinToString("\n") { i -> "line $i ".padEnd(99, 'x') }
        val len = body.length
        val script = "cat <<EOF | cat | cat >/tmp/o\n$body\nEOF\n" +
            "x=${'$'}(cat /tmp/o); echo ${'$'}{#x}\n" +
            "y=${'$'}(cat <<EOF\n$body\nEOF\n); echo ${'$'}{#y}\n" +
            "cat <<EOF >/tmp/p &\n$body\nEOF\n" +
            "wait; z=${'$'}(cat /tmp/p); echo ${'$'}{#z}\n"
        val r = run(listOf("/home/t.sh"), mapOf("/home/t.sh" to script))
        assertEquals("$len\n$len\n$len\n", r.console)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `算術の割り算の代入`() {
        assertOutput(
            """
            -9223372036854775808
            0
            0 1 unset
            """,
            """
            x=-9223372036854775808; : ${'$'}((x/=-1)); echo ${'$'}x
            x=-9223372036854775808; : ${'$'}((x%=-1)); echo ${'$'}x
            echo ${'$'}((0 && (y/=0))) ${'$'}((1 || (y%=0))) ${'$'}{y:-unset}
            """,
        )
        val r = sh("x=1; : \$((x/=0)); echo not reached")
        assertTrue(r.console.contains("division by zero"), r.console)
    }

    @Test
    fun `ドットで読むファイルの大きさ`() {
        val files = mapOf("/home/empty" to "", "/home/big" to "#".repeat(300 * 1024) + "\n")
        assertOutput(
            """
            empty 0
            sh: .: file too large
            big 1
            """,
            """
            . /home/empty; . /home/empty; echo empty ${'$'}?
            . /home/big; echo big ${'$'}?
            """,
            files,
        )
    }

    @Test
    fun `ドットで読むファイルが残る領域に入らなければ、知らせて1を返し、シェルは続く`() {
        // 残る領域（512 KiB）に 128 KiB の変数を 3 つ置くと、上限（256 KiB）より小さい 200 KiB のファイルも入らない
        val files = mapOf("/home/big" to "#".repeat(200 * 1024) + "\n")
        assertOutput(
            """
            sh: .: /home/big: not enough memory to read the file
            dot 1
            again 0
            """,
            """
            x=a; for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17; do x=${'$'}x${'$'}x; done
            y=a; for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17; do y=${'$'}y${'$'}y; done
            z=${'$'}x
            . /home/big; echo dot ${'$'}?
            unset x y z; . /home/big; echo again ${'$'}?
            """,
            files,
        )
    }

    @Test
    fun `パイプラインの途中の組み込みは、大きい出力も切らずに渡す`() {
        // 128 KiB の変数を、途中の段の echo・printf で流す（全部ためると、変数と合わせて残る領域に入らない）
        assertOutput(
            """
            131073
            131072
            2
            """,
            """
            x=a; for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17; do x=${'$'}x${'$'}x; done
            echo "${'$'}x" | cat | cat >/tmp/o
            printf %s "${'$'}x" | cat >/tmp/p
            unset x
            y=${'$'}(cat /tmp/o); echo ${'$'}((${'$'}{#y} + 1))
            y=${'$'}(cat /tmp/p); echo ${'$'}{#y}
            unset y; x=a; for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17; do x=${'$'}x${'$'}x; done
            echo "${'$'}x" | exitwith 2; echo ${'$'}?
            """,
        )
    }

    @Test
    fun `ヒアドキュメント付きの後ろのジョブも、killで本来のコマンドが止まる`() {
        // パイプ（4 KiB）に入りきるものと、入りきらないもの。書くだけの子のシェルは % や \ を解かずに渡す
        val big = (1..100).joinToString("\n") { i -> "line $i % \\ ".padEnd(99, 'x') }
        val script = "hang <<EOF &\nsmall\nEOF\n" +
            "kill ${'$'}!; wait ${'$'}!; echo small ${'$'}?\n" +
            "hang <<EOF &\n$big\nEOF\n" +
            "kill ${'$'}!; wait ${'$'}!; echo big ${'$'}?\n" +
            "cat <<'EOF' >/tmp/q &\n$big\nEOF\n" +
            "wait; a=${'$'}(cat /tmp/q); b=${'$'}(cat <<'EOF'\n$big\nEOF\n); [ \"${'$'}a\" = \"${'$'}b\" ] && echo same\n"
        val r = run(listOf("/home/t.sh"), mapOf("/home/t.sh" to script)) { h, m ->
            // 止めた hang も、書くだけの子のシェルも残らない
            fun live() = m.lock.withLockValue { m.kernel.processes.all().filter { !it.state.isTerminal }.map { it.pid } }
            eventuallyDescribe(30_000, { "残っている: ${live()}" }) {
                h.scheduler.tick()
                Thread.sleep(1)
                live().isEmpty()
            }
        }
        assertEquals("small 143\nbig 143\nsame\n", r.console)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `括弧に包んだ外のコマンド1つの後ろのジョブも、killで本来のコマンドが止まる`() {
        // `( cmd ) &` はサブシェルを挟まずに起こすので、`$!` は cmd を指す（#471）。括弧のリダイレクトは外から順に重ねる
        val script = "( hang ) &\n" +
            "kill ${'$'}!; wait ${'$'}!; echo paren ${'$'}?\n" +
            "( ( hang ) ) >/tmp/o 2>&1 &\n" +
            "kill ${'$'}!; wait ${'$'}!; echo nested ${'$'}?\n" +
            "( hang <<EOF\nsmall\nEOF\n) &\n" +
            "kill ${'$'}!; wait ${'$'}!; echo heredoc ${'$'}?\n" +
            "echo in >/tmp/i; ( cat >/tmp/c ) </tmp/i & wait ${'$'}!; cat /tmp/c\n" +
            "( exitwith 3 ) & wait ${'$'}!; echo exit ${'$'}?\n" +
            // 親の状態を変えうる展開があれば、今までどおりサブシェルで（親の x は変わらない）
            "( args ${'$'}{x:=set} ) & wait; echo \"x=${'$'}x\"\n"
        val r = run(listOf("/home/t.sh"), mapOf("/home/t.sh" to script)) { h, m ->
            fun live() = m.lock.withLockValue { m.kernel.processes.all().filter { !it.state.isTerminal }.map { it.pid } }
            eventuallyDescribe(30_000, { "残っている: ${live()}" }) {
                h.scheduler.tick()
                Thread.sleep(1)
                live().isEmpty()
            }
        }
        assertEquals("paren 143\nnested 143\nheredoc 143\nin\nexit 3\n<set>\nx=\n", r.console)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `括弧の中の展開の誤りは、括弧だけを終えて親のスクリプトは続く`() {
        // 括弧を外して起こしても、`${x?msg}`・`set -u`・bad substitution は括弧の中のエラー（POSIX）。親は止まらない
        val d = '$'
        val script = "( args ${d}{x?unset} ) & wait; echo bg\n" +
            "( args ${d}{x?unset} ); echo fg ${d}?\n" +
            "set -u; ( args ${d}undef ); echo nounset ${d}?\n" +
            "( args ${d}{x@@} ); echo bad ${d}?\n" +
            "echo ${d}( ( args ${d}{x?unset} ) ) end\n" +
            "echo after\n"
        val r = run(listOf("/home/t.sh"), mapOf("/home/t.sh" to script))
        assertEquals(
            "sh: x: unset\nbg\nsh: x: unset\nfg 1\nsh: undef: parameter not set\nnounset 1\n" +
                "sh: x@@: bad substitution\nbad 1\nsh: x: unset\nend\nafter\n",
            r.console,
        )
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `途中の括弧のリダイレクトに親の状態を変えうる展開があれば、サブシェルで起こす`() {
        // 一番内側のコマンドだけでなく、途中の段のリダイレクト・引用なしのヒアドキュメントも見る（#471）
        val d = '$'
        val script = "( ( args a ) >/tmp/${d}{x:=f} ) & wait; echo \"x=${d}x\"\n" +
            "( ( cat ) <<EOF\n${d}{y:=h}\nEOF\n) | cat; echo \"y=${d}y\"\n"
        val r = run(listOf("/home/t.sh"), mapOf("/home/t.sh" to script))
        assertEquals("x=\nh\ny=\n", r.console)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `ヒアドキュメントを渡したサブシェルの出力の前に空行が入らない`() {
        // #478。括弧を外して起こすもの・サブシェルで起こすもの・パイプの段・後ろのジョブ
        val d = '$'
        val script = "( cat <<EOF\na\nEOF\n) & wait\n" +
            "( ( cat ) <<EOF\nb\nEOF\n) | cat\n" +
            "( cat <<EOF; echo c2\nc\nEOF\n) & wait\n" +
            "( ( cat ) <<EOF\n${d}{y:=d}\nEOF\n) | cat\n" +
            "( cat <<EOF\ne\nEOF\n)\n" +
            "( : ${d}{z:=1}; cat <<EOF\nf\nEOF\n) & wait\n"
        val r = run(listOf("/home/t.sh"), mapOf("/home/t.sh" to script))
        assertEquals("a\nb\nc\nc2\nd\ne\nf\n", r.console)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `set -xの代入の跡は、名前と=と値を1語にして出す`() {
        // #504: bash・dash と同じく `+ s=1`（`+ s = 1` ではない）。値は展開したもの
        val d = '$'
        val script = "set -x\n" +
            "s=1\n" +
            "t=${d}s u=v${d}{s}w\n" +
            "e=\n" +
            "x=1 args c\n" +
            "set +x\n" +
            "echo ${d}s ${d}t ${d}u ${d}x.\n"
        val r = run(listOf("/home/t.sh"), mapOf("/home/t.sh" to script))
        // コマンドの前の代入（`x=1 cmd`）も、bash・dash と同じく `+ x=1 args c` と跡に出す（#508）
        assertEquals("+ s=1\n+ t=1\n+ u=v1w\n+ e=\n+ x=1 args c\n<c>\n+ set +x\n1 1 v1w .\n", r.console)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `set -xのコマンドの前の代入は、展開を1回だけして跡に値を出す`() {
        // #508: 跡のためにもう一度展開しない。コマンド置換は動くたびに /tmp/n に1バイト足す
        // （置換の中の跡は /tmp/j へ）。外のプログラム・組み込み・特別な組み込み・関数・command・パイプの段・括弧
        val d = '$'
        val sub = "${d}({ set +x; echo >>/tmp/n; echo v; } 2>/tmp/j)"
        val script = "f() { :; }\n" +
            "set -x\n" +
            "x=$sub args c\n" +
            "x=$sub y=${d}((1+1)) echo e\n" +
            "x=$sub :\n" +
            "x=$sub f a\n" +
            "x=$sub command args d\n" +
            "x=$sub args p | cat\n" +
            "( x=$sub args q )\n" +
            "set +x\n"
        var count = -1L
        val r = run(
            listOf("/home/t.sh"),
            mapOf("/home/t.sh" to script),
            after = { _, m -> count = m.vfs.stat(VfsPath.parse("/tmp/n")).size },
        )
        assertEquals(
            "+ x=v args c\n<c>\n+ x=v y=2 echo e\ne\n+ x=v :\n+ x=v f a\n+ :\n" +
                "+ x=v command args d\n<d>\n+ x=v args p\n+ cat\n<p>\n+ x=v args q\n<q>\n+ set +x\n",
            r.console,
        )
        assertEquals(7L, count)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `set -xの代入の跡を書くために、代入が領域不足で失敗しない`() {
        // #510: 跡のために名前と値を構文木などの領域（`mem.ast`、512 KiB）でつないでいたので、
        // 括弧の中の構文木で領域が埋まっていると、大きな値の代入が跡のせいで失敗した。
        // 跡は名前・`=`・値を続けて書くので、つなぐ領域は要らない。
        // 構文木は、動かさない `if false; then : a a ...; fi` の語（3 万語）で埋める
        val d = '$'
        val half = "v".repeat(100 * 1024)
        val filler = List(30000) { "a" }.joinToString(" ")
        val script = "y=${d}(cat /home/big)\n" +
            "set -x\n" +
            "{ x=${d}y${d}y; if false; then : $filler; fi; } 2>/tmp/t\n" +
            "set +x\n" +
            "echo len ${d}{#x}\n"
        var traced = -1L
        val r = run(
            listOf("/home/t.sh"),
            mapOf("/home/t.sh" to script, "/home/big" to half),
            after = { _, m -> traced = m.vfs.stat(VfsPath.parse("/tmp/t")).size },
        )
        // 跡は `+ x=<値>`・`+ false` の 2 行
        val len = half.length * 2
        val trace = "+ x=\n".length + len + "+ false\n".length
        assertEquals("+ set +x\nlen $len\n", r.console)
        assertEquals(trace.toLong(), traced)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `括弧を外して起こすコマンドのset -xの跡は、括弧のリダイレクトに従う`() {
        // サブシェルで起こしていたときと同じ出先（#478）。コマンド自身のリダイレクトは効かない
        val script = "set -x\n" +
            "( args a ) 2>/tmp/e\n" +
            "( ( args b ) 2>/tmp/f )\n" +
            "( args c 2>/tmp/g )\n" +
            "( args d ) >/tmp/o 2>/tmp/h & wait\n" +
            "( args e ) 2>&1 | cat >/tmp/p\n" +
            "( args f; : ) 2>/tmp/i\n" +
            "set +x\n" +
            "cat /tmp/e /tmp/f /tmp/g /tmp/h /tmp/p /tmp/i\n"
        val r = run(listOf("/home/t.sh"), mapOf("/home/t.sh" to script))
        assertEquals(
            "<a>\n<b>\n+ args c\n<c>\n+ wait\n+ cat\n<f>\n+ set +x\n" +
                "+ args a\n+ args b\n+ args d\n+ args e\n<e>\n+ args f\n+ :\n",
            r.console,
        )
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `括弧を外して起こすコマンドの長いset -xの跡は、語が多くてもパイプに丸ごと入る`() {
        // #500: パイプの容量（4096）を超える跡を、パターンの展開で出た多くの語で作る（#497 のテストは長い語1つ）
        val names = (100 until 300).map { "/home/sub/file-with-a-long-name-$it" }
        val script = "set -x\n" +
            "( args /home/sub/* ) 2>&1 | cat >/tmp/p\n" +
            "set +x\n" +
            "cat /tmp/p\n"
        val r = run(listOf("/home/t.sh"), mapOf("/home/t.sh" to script) + names.associateWith { "" })
        val trace = "+ args " + names.joinToString(" ") + "\n"
        assertTrue(trace.length > 4096)
        val out = names.joinToString(" ") { "<$it>" } + "\n"
        assertEquals("+ cat\n+ set +x\n$trace$out", r.console)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `括弧を外して起こすコマンドが起こせなくても、長いset -xの跡はパイプに丸ごと入る`() {
        // #500 で見つけた形: 引数が多すぎて起こせない（`ls -d /bin/*` を何度も並べたとき）。
        // 跡（4096 を超える）と起こせなかった知らせが、どちらも括弧の 2>&1 の先に出る。
        // 起こせないのは、引数の数（argv[0] を足して 4,201）が `process.MAX_SPAWN_ARGS`（4,096。#501）を超えるため。
        // 上限を変えたら、この数もそれを超えるように直す
        val d = '$'
        val words = (10000 until 14200).map { "w$it" }
        val script = "x='${words.joinToString(" ")}'\n" +
            "set -x\n" +
            "( args ${d}x ) 2>&1 | cat >/tmp/p\n" +
            "set +x\n" +
            "cat /tmp/p\n"
        val r = run(listOf("/home/t.sh"), mapOf("/home/t.sh" to script))
        val trace = "+ args " + words.joinToString(" ") + "\n"
        assertTrue(trace.length > 4096)
        assertEquals("+ cat\n+ set +x\n${trace}sh: args: Argument list too long\n", r.console)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `引数が300個あっても、シェルが起こして子が受け取れる`() {
        // #501: 標準 OS のライブラリの上限が 256 だった（`process.MAX_SPAWN_ARGS`・`process.MAX_ARGS`）。
        // 起こす側（sh の spawn）と受け取る側（shprobe の `args` の `process.args()`）の両方を通す
        val names = (100 until 400).map { "/home/sub/f$it" }
        val r = run(listOf("-c", "args /home/sub/*; echo status $?"), names.associateWith { "" })
        assertEquals(names.joinToString(" ") { "<$it>" } + "\nstatus 0\n", r.console)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `引数はカーネルの上限の4096個（argv0を含む）まで渡せて、それを超えると起こせない`() {
        // #501: 上限は `process.MAX_SPAWN_ARGS`・`process.MAX_ARGS`（カーネルの `KibiProcess.MAX_ARGS` と同じ 4,096）
        val d = '$'
        val words = (10000 until 14095).map { "w$it" }
        val script = "x='${words.joinToString(" ")}'\n" +
            "args ${d}x; echo status ${d}?\n" +
            "args ${d}x extra; echo status ${d}?\n"
        val r = run(listOf("/home/t.sh"), mapOf("/home/t.sh" to script))
        val out = words.joinToString(" ") { "<$it>" } + "\n"
        assertEquals("${out}status 0\nsh: args: Argument list too long\nstatus 126\n", r.console)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `括弧を外して起こすコマンドのset -xの跡がパイプに入りきらなくても、シェルは止まらない`() {
        // #497: 跡の出先が、読む側をまだ起こしていないパイプの段の書く端（`( cmd ) 2>&1 | cat`）。
        // 跡がパイプの容量（4096）を超えても、親が書き込みで止まらず、跡も欠けない
        val d = '$'
        val word = "a".repeat(16 * 512)
        val script = "v=aaaaaaaaaaaaaaaa\n" +
            "for i in 1 2 3 4 5 6 7 8 9; do v=${d}v${d}v; done\n" +
            "set -x\n" +
            "( args ${d}v ) 2>&1 | cat >/tmp/p\n" +
            "( ( args ${d}v ) 2>&1 ) | cat >/tmp/q\n" +
            "set +x\n" +
            "cat /tmp/p /tmp/q\n"
        val r = run(listOf("/home/t.sh"), mapOf("/home/t.sh" to script))
        val pair = "+ args $word\n<$word>\n"
        assertEquals("+ cat\n+ cat\n+ set +x\n$pair$pair", r.console)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `括弧を外して起こすコマンドのset -xの跡は、括弧でfd2を閉じたときと途中の括弧が失敗したときは書かない`() {
        // #495 の設計メモのとおり。閉じた fd 2 には書かず、途中の括弧のリダイレクトが失敗したらコマンドまで進まない。
        // 途中の括弧が失敗した括弧自身の終了コードは 1（#499。`set +x` の前に取っておく）
        val d = '$'
        val script = "set -x\n" +
            "( args a ) 2>&-\n" +
            "( ( args b ) </nonexistent ) 2>/tmp/e || echo status ${d}?\n" +
            "set +x\n" +
            "cat /tmp/e\n"
        val r = run(listOf("/home/t.sh"), mapOf("/home/t.sh" to script))
        assertEquals("<a>\n+ echo status 1\nstatus 1\n+ set +x\nsh: /nonexistent: No such file or directory\n", r.console)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `括弧の中のコマンド自身のリダイレクトの誤りは、括弧のリダイレクトの先に出る`() {
        // #498 から、POSIX のサブシェルと同じく、括弧の 2> の先に出る（シェルの stderr には出ない）
        val d = '$'
        val script = "( cat </nonexistent ) 2>/tmp/e\n" +
            "echo status ${d}?\n" +
            "cat /tmp/e\n"
        val r = run(listOf("/home/t.sh"), mapOf("/home/t.sh" to script))
        assertEquals("status 1\nsh: /nonexistent: No such file or directory\n", r.console)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `一番外の括弧のリダイレクトの展開の誤りは、括弧の中身によらず同じに扱う`() {
        // 一番外のリダイレクトは括弧を外す前も親で展開していた。中身がコマンド1つでも複合コマンドでも同じ（#471）
        val d = '$'
        // 中の展開も誤るときも、一番外のリダイレクトが先
        val pairs = listOf(
            "( args a ) >${d}{x?u}" to "( args a; args b ) >${d}{x?u}",
            "( args ${d}{y?v} ) >${d}{x?u}" to "( args ${d}{y?v}; : ) >${d}{x?u}",
        )
        for ((single, compound) in pairs) {
            val results = listOf(single, compound).map { cmd ->
                val r = run(listOf("/home/t.sh"), mapOf("/home/t.sh" to "$cmd\necho after ${d}?\n"))
                r.console to r.exitCode
            }
            assertEquals(results[1], results[0], single)
            assertFalse(results[0].first.contains("after"), results[0].first)
        }
        // 途中の括弧のリダイレクトなら、その括弧だけを終える
        val r = run(listOf("/home/t.sh"), mapOf("/home/t.sh" to "( ( args a ) >${d}{x?u} ); echo after ${d}?\n"))
        assertEquals("sh: x: u\nafter 1\n", r.console)
        assertEquals(0, r.exitCode)
        // 括弧の中の展開の誤りの知らせは、一番外のリダイレクトに従う
        val quiet = run(listOf("/home/t.sh"), mapOf("/home/t.sh" to "( args ${d}{x?u} ) 2>/tmp/e; echo after ${d}?; cat /tmp/e\n"))
        assertEquals("after 1\nsh: x: u\n", quiet.console)
    }

    @Test
    fun `括弧のコマンドを起こせなかったときも、set -eでスクリプトが止まる`() {
        // 括弧を外して親から起こしても、起こせなかった括弧は失敗したコマンド（#471）
        val d = '$'
        val cases = listOf(
            "( nosuchcmd )" to 127,
            "( cat </nonexistent )" to 1,
            "( args ${d}{x?u} )" to 1,
            "( : ) </nonexistent" to 1,
        )
        for ((cmd, code) in cases) {
            val r = run(listOf("/home/t.sh"), mapOf("/home/t.sh" to "set -e\n$cmd\necho after\n"))
            assertFalse(r.console.contains("after"), "$cmd: ${r.console}")
            assertEquals(code, r.exitCode, cmd)
        }
        // 条件の中では止まらない
        val r = run(listOf("/home/t.sh"), mapOf("/home/t.sh" to "set -e\n( nosuchcmd ) || echo or\necho after\n"))
        assertTrue(r.console.endsWith("or\nafter\n"), r.console)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `会話的なシェルでは、シェルの中で動かす段があってもCtrl+Cが届く`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val host = ConsoleHost()
            val m = h.machine(fuelPerTick = 10_000_000, host = host, memoryBytes = 16L * 1024 * 1024)
            root(m, emptyMap())
            val pid = m.exec("/bin/sh", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("sh"), env = mapOf("PATH" to "/bin"), fds = SpawnOptions.ROOT_PREOPEN))
            // (打つ行, プロンプトに戻るまで Ctrl+C を送るか)
            val steps = listOf(
                "x=a; for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14; do x=${'$'}x${'$'}x; done" to false,
                // echo の出力はパイプより大きく、hang は読まない
                "echo \"${'$'}x\" | hang" to true,
                "echo after ${'$'}?" to false,
                // ためる上限（64 KiB）より大きく、空きを待つ間に Ctrl+C を受ける
                "y=${'$'}x${'$'}x${'$'}x${'$'}x${'$'}x${'$'}x${'$'}x${'$'}x; echo \"${'$'}y\" | hang" to true,
                "echo big ${'$'}?" to false,
                // 3 段: 前景の cat が Ctrl+C で止まったら、読まない途中の hang も止めて、空きを待つのをやめる
                "echo \"${'$'}y\" | hang | cat" to true,
                "echo three ${'$'}?" to false,
                // 前景にした最後の段（外のコマンド nap）が先に自分で終わったら、動いている hang に前景を移す。
                // 移っていれば nap の出力の後の Ctrl+C が hang に届き、移っていなければ届かず終わらない（空きを待つ間と、子を待つ間）
                "echo \"${'$'}y\" | hang | nap" to true,
                "echo moved ${'$'}?" to false,
                "hang | nap" to true,
                "echo waited ${'$'}?" to false,
                // 最後の段がシェルの中で動く
                "hang | echo last" to true,
                "echo second ${'$'}?" to false,
                "exit" to false,
            )
            var next = 0
            var interrupting = false
            // nap の出力が出てから Ctrl+C を送る（nap が自分で終わって、前景が移った後に送るため）
            var awaited: String? = null
            var lineStart = 0
            var seen = 0
            var lastSent = 0L
            eventuallyDescribe(150_000, { "終わらない: ${host.text()}" }) {
                h.scheduler.tick()
                Thread.sleep(1)
                val text = host.text()
                val now = System.nanoTime()
                val atPrompt = text.length > seen && text.endsWith("$ ")
                when {
                    atPrompt && next < steps.size -> {
                        seen = text.length
                        val (line, ctrlC) = steps[next++]
                        awaited = if (line.endsWith("nap")) "napped" else null
                        lineStart = text.length
                        m.offerConsoleInput((line + "\n").toByteArray())
                        interrupting = ctrlC
                    }
                    interrupting && !atPrompt && (awaited == null || text.substring(lineStart).contains(awaited!!)) && now - lastSent > 200_000_000 -> {
                        m.offerConsoleInput(byteArrayOf(3))
                        lastSent = now
                    }
                }
                m.stateOf(pid).isTerminal
            }
            val text = host.text()
            assertTrue(text.contains("after 130\n"), text)
            assertTrue(text.contains("big 130\n"), text)
            assertTrue(text.contains("three 130\n"), text)
            assertTrue(text.contains("moved 0\n"), text)
            assertTrue(text.contains("waited 0\n"), text)
            assertTrue(text.contains("last\n"), text)
            assertTrue(text.contains("second 0\n"), text)
        }
    }

    @Test
    fun `TTYからは会話的に読み、プロンプトを出す`() {
        val r = run(emptyList(), input = listOf("echo hi", "if true", "then echo multi", "fi", "exit 7"))
        assertEquals(7, r.exitCode)
        // 入力のエコーとプロンプト（$ と >）、出力。行の編集（#102）がプロンプトの前に書く空白と制御は除いて見る
        val text = r.console.replace(Regex(" *\r\u001b\\[K\r\u001b\\[J"), "")
        assertTrue(text.contains("$ echo hi\nhi\n"), text)
        assertTrue(text.contains("> then echo multi\n> fi\nmulti\n"), text)
    }

    @Test
    fun `1000語ほどのコマンド行でも、echoとforが動く`() {
        // #502: 語ごとにほかのものと交互に積まれる列（argv・for の値）が、語の数の 2 乗で領域を使っていた
        val words = (1..1000).map { "w$it" }
        val line = words.joinToString(" ")
        val r = sh("echo $line; n=0; for i in $line; do n=${'$'}((n+1)); done; echo ${'$'}n ${'$'}i")
        assertEquals("$line\n1000 w1000\n", r.console)
        assertEquals(0, r.exitCode)
    }
}
