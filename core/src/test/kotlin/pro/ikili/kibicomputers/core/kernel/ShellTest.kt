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
 * 標準 OS のシェル `/bin/sh`（#101）を、カーネルの上で動かして確かめる。
 * 外のコマンドには、イメージの `echo`・`true`・`false` と、`os/test/shprobe.zig`（`cat`・`args`・`envget`・`exitwith`・`errout`）を使う
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
        for (name in listOf("sh", "echo", "true", "false")) m.put("/bin/$name", imageFile("/bin/$name"))
        for (name in listOf("cat", "args", "envget", "exitwith", "errout")) m.put("/bin/$name", probe)
        for ((path, text) in files) m.put(path, text.toByteArray())
    }

    /** `/bin/sh` を [args] で動かして終わるまで待つ。[input] はコンソールに打つ行（プロンプトが出るたびに 1 行） */
    private fun run(args: List<String>, files: Map<String, String> = emptyMap(), input: List<String> = emptyList()): Result = timeout(limit) {
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
                if (next < input.size && text.length > seen && (text.endsWith("$ ") || text.endsWith("> "))) {
                    seen = text.length
                    m.offerConsoleInput((input[next++] + "\n").toByteArray())
                }
                Thread.sleep(1)
                m.stateOf(pid).isTerminal
            }
            val exitCode = m.lock.withLockValue { m.kernel.processes[pid]!!.exitCode }
            assertEquals(ProcessState.EXITED, m.stateOf(pid), host.text())
            repeat(5) { h.scheduler.tick() }
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
    fun `TTYからは会話的に読み、プロンプトを出す`() {
        val r = run(emptyList(), input = listOf("echo hi", "if true", "then echo multi", "fi", "exit 7"))
        assertEquals(7, r.exitCode)
        // 入力のエコーとプロンプト（$ と >）、出力。行の編集（#102）がプロンプトの前に書く空白と制御は除いて見る
        val text = r.console.replace(Regex(" *\r\u001b\\[K\r\u001b\\[J"), "")
        assertTrue(text.contains("$ echo hi\nhi\n"), text)
        assertTrue(text.contains("> then echo multi\n> fi\nmulti\n"), text)
    }
}
