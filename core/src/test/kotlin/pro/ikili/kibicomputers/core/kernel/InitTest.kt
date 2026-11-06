package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.boot.BootVolume
import pro.ikili.kibicomputers.core.kernel.boot.BootVolumeOpener
import pro.ikili.kibicomputers.core.kernel.vfs.FileType
import pro.ikili.kibicomputers.core.kernel.vfs.ImageFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * 標準 OS の `/sbin/init`（#100）を、起動の手順（#49）で pid 1 として動かして確かめる。
 * 子には `os/test/initprobe.zig`（テストのリソース `os-initprobe.wasm`）を使い、自分の様子（前景か・標準入力・TTY のモード・環境変数）を書かせる
 */
class InitTest {
    private val limit = Duration.ofSeconds(120)

    private class ConsoleHost : MachineHost {
        private val out = StringBuffer()
        private val partial = StringBuilder()

        /** 書き終わった行と、その行が届いた時刻（System.nanoTime） */
        val timed: MutableList<Pair<String, Long>> = java.util.Collections.synchronizedList(mutableListOf())

        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit

        override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
            val s = String(bytes, Charsets.UTF_8)
            out.append(s)
            val now = System.nanoTime()
            synchronized(partial) {
                for (c in s) {
                    if (c == '\n') {
                        val l = partial.toString().removeSuffix("\r")
                        if (l.isNotEmpty()) timed += l to now
                        partial.clear()
                    } else {
                        partial.append(c)
                    }
                }
            }
        }

        fun text(): String = out.toString().replace("\r\n", "\n")

        fun lines(): List<String> = text().lines().filter { it.isNotEmpty() }
    }

    private val image: ImageFileSystem by lazy { requireNotNull(ImageFileSystem.bundled("os")) { "os.zip がリソースにない" } }

    private val probe: ByteArray by lazy {
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/os-initprobe.wasm")) { "os-initprobe.wasm がリソースにない" }
            .use { it.readBytes() }
    }

    private fun imageFile(path: String): ByteArray {
        val stat = image.stat(VfsPath.parse(path))
        val bytes = ByteArray(stat.size.toInt())
        image.read(stat.node, 0, bytes, 0, bytes.size)
        return bytes
    }

    /** イメージの `/sbin/init` と [files] を置いた、書けるディスク `d` */
    private class Disk(init: ByteArray, files: Map<String, ByteArray>, dirs: List<String>) : BootVolumeOpener {
        private val fs = TestMemoryFileSystem().also { fs ->
            for (d in listOf("/sbin", "/bin", "/etc") + dirs) fs.createDirectory(VfsPath.parse(d))
            for ((path, bytes) in files + (BootConfig.INIT_PATH to init)) {
                val node = fs.createFile(VfsPath.parse(path))
                fs.write(node, 0, bytes, 0, bytes.size)
            }
        }

        override fun open(diskId: String): BootVolume? = if (diskId == "d") BootVolume(fs, readOnly = false) else null
    }

    /** [files] を置いたディスクから起動し、[until] がそろうまで tick を回してから止める。コンソールの行を返す */
    private fun boot(files: Map<String, ByteArray>, dirs: List<String> = emptyList(), until: (List<String>) -> Boolean): List<String> =
        bootTimed(files, dirs, until).map { it.first }

    /** [boot] と同じだが、行が届いた時刻（System.nanoTime）も返す */
    private fun bootTimed(
        files: Map<String, ByteArray>,
        dirs: List<String> = emptyList(),
        until: (List<String>) -> Boolean,
    ): List<Pair<String, Long>> = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val host = ConsoleHost()
            h.scheduler.hooks.bootVolumes = Disk(imageFile(BootConfig.INIT_PATH), files, dirs)
            val m = h.machine(fuelPerTick = 10_000_000, host = host, memoryBytes = 8L * 1024 * 1024)
            assertTrue(m.boot(BootConfig.builder().bootDisks(listOf("d")).build()))
            eventuallyDescribe(90_000, { "そろわない: ${host.text()}" }) {
                h.scheduler.tick()
                Thread.sleep(1)
                until(host.lines())
            }
            m.stop()
            eventually(30_000, "止まらない") {
                h.scheduler.tick()
                Thread.sleep(1)
                m.state == MachineState.STOPPED
            }
            assertTrue(m.awaitTermination(5_000))
            synchronized(host.timed) { host.timed.toList() }
        }
    }

    private fun inittab(text: String): Pair<String, ByteArray> = "/etc/inittab" to text.trimIndent().toByteArray()

    @Test
    fun `同梱イメージosにはsbinのinitとetcのinittabが入る`() {
        assertEquals(FileType.REGULAR_FILE, image.stat(VfsPath.parse(BootConfig.INIT_PATH)).type)
        val tab = imageFile("/etc/inittab").toString(Charsets.UTF_8)
        assertTrue(tab.lines().contains("shell /bin/sh"), tab)
        assertTrue(tab.lines().contains("env HOME=/home"), tab)
        // HDD・RAM のファイルシステムをマウントする空のディレクトリ（#461）
        for (dir in listOf("/home", "/tmp")) {
            assertEquals(FileType.DIRECTORY, image.stat(VfsPath.parse(dir)).type, dir)
            assertEquals(0, image.readDirectory(image.stat(VfsPath.parse(dir)).node).size, dir)
        }
    }

    @Test
    fun `inittabがなければ、bin_shをログインなしで前景に起こし、終わったらcookedに戻して起こし直す`() {
        val lines = boot(mapOf("/bin/sh" to probe)) { l -> l.count { it.startsWith("sh ") } >= 2 }
        val shells = lines.filter { it.startsWith("sh ") }
        // 前景とモードと既定の環境変数。sh はすぐ終わるので、1 秒おいて起こし直される
        assertEquals("sh fg=self stdin=tty mode=cooked FOO=(none) HOME=/ args=1", shells[0], lines.toString())
        assertEquals(shells[0], shells[1])
        assertEquals("kibi: booting from disk d", lines.first())
    }

    @Test
    fun `イメージのinittabで、bin_shを起こす`() {
        val lines = boot(mapOf("/bin/sh" to probe, "/etc/inittab" to imageFile("/etc/inittab"))) { l -> l.any { it.startsWith("sh ") } }
        // HOME は HDD をマウントする /home（#461）
        assertTrue("sh fg=self stdin=tty mode=cooked FOO=(none) HOME=/home args=1" in lines, lines.toString())
    }

    @Test
    fun `runは順に待って前景で動かし、daemonとrespawnは標準入力なしで後ろ、shellは前景で起こし直すたびにcookedで始まる`() {
        val tab = inittab(
            """
            # コメント
            env FOO=bar

            run /bin/initprobe setup
            run /bin/initprobe setupfail 3
            run /bin/missing
            bogus /bin/initprobe
            env NOEQUALS
            daemon /bin/initprobe daemon forever
            respawn /bin/initprobe again
            shell /bin/initprobe shell raw
            shell /bin/initprobe second
            """,
        )
        val lines = boot(mapOf("/bin/initprobe" to probe, tab)) { l ->
            l.count { it.startsWith("shell ") } >= 2 && l.count { it.startsWith("again ") } >= 2
        }
        val text = lines.joinToString("\n")
        // 読んだ時点の警告（行番号は空行・コメントを含む）
        assertTrue("init: /etc/inittab:7: unknown entry" in lines, text)
        assertTrue("init: /etc/inittab:8: env takes one NAME=VALUE" in lines, text)
        assertTrue("init: /etc/inittab:12: only one shell is allowed" in lines, text)
        // run は書いた順に、前景で、終わるまで待つ
        val setup = lines.indexOf("setup fg=self stdin=tty mode=cooked FOO=bar HOME=/ args=2")
        val fail = lines.indexOf("setupfail fg=self stdin=tty mode=cooked FOO=bar HOME=/ args=3")
        val failExit = lines.indexOf("init: /bin/initprobe exited with code 3")
        val missing = lines.indexOf("init: cannot start /bin/missing: NOENT")
        assertTrue(setup in 0 until fail && fail < failExit && failExit < missing, text)
        // 後ろの子は run が全部終わってから。標準入力を持たず、前景でない
        val daemon = lines.indexOfFirst { it.startsWith("daemon fg=other stdin=none ") && it.endsWith(" FOO=bar HOME=/ args=3") }
        assertTrue(daemon > missing, text)
        assertTrue(lines.filter { it.startsWith("again ") }.all { it.startsWith("again fg=other stdin=none ") }, text)
        assertEquals(1, lines.count { it.startsWith("daemon ") }, "daemon は起こし直さない: $text")
        // shell は前景。1 回目が raw にして終わっても、2 回目は cooked で始まる
        val shells = lines.filter { it.startsWith("shell ") }
        assertTrue(shells.all { it == "shell fg=self stdin=tty mode=cooked FOO=bar HOME=/ args=3" }, text)
        assertTrue(lines.none { it.startsWith("second ") }, text)
    }

    @Test
    fun `動かす子がなくなったらinitは終わり、マシンが止まる`() {
        val lines = boot(mapOf("/bin/initprobe" to probe, inittab("run /bin/initprobe only"))) { l ->
            l.any { it.startsWith("kibi: init exited") }
        }
        assertEquals(
            listOf(
                "kibi: booting from disk d",
                "only fg=self stdin=tty mode=cooked FOO=(none) HOME=/ args=2",
                "init: nothing left to run",
                "kibi: init exited with code 0",
            ),
            lines,
        )
    }

    @Test
    fun `シェルがなければ、起こせないと書いて終わる`() {
        val lines = boot(emptyMap()) { l -> l.any { it.startsWith("kibi: init exited") } }
        assertEquals(
            listOf(
                "kibi: booting from disk d",
                "init: cannot start /bin/sh: NOENT",
                "init: nothing left to run",
                "kibi: init exited with code 0",
            ),
            lines,
        )
    }

    @Test
    fun `inittabが8KiBを超えたら、FBIGと書いて既定のbin_shで動く`() {
        val big = "# " + "x".repeat(MAX_CONF) + "\nshell /bin/initprobe big\n"
        val lines = boot(mapOf("/bin/sh" to probe, "/bin/initprobe" to probe, "/etc/inittab" to big.toByteArray())) { l ->
            l.any { it.startsWith("sh ") }
        }
        assertTrue("init: /etc/inittab: FBIG; using the default" in lines, lines.toString())
        assertTrue("sh fg=self stdin=tty mode=cooked FOO=(none) HOME=/ args=1" in lines, lines.toString())
        assertTrue(lines.none { it.startsWith("big ") }, lines.toString())
    }

    @Test
    fun `inittabが読めなければ、errnoを書いて既定のbin_shで動く`() {
        // ディレクトリはファイルとして読めない
        val lines = boot(mapOf("/bin/sh" to probe), dirs = listOf("/etc/inittab")) { l -> l.any { it.startsWith("sh ") } }
        val warning = lines.single { it.startsWith("init: /etc/inittab: ") }
        assertTrue(warning.endsWith("; using the default") && "NOENT" !in warning, lines.toString())
        assertTrue("sh fg=self stdin=tty mode=cooked FOO=(none) HOME=/ args=1" in lines, lines.toString())
    }

    @Test
    fun `ちょうど8192バイトのinittabは受け付ける`() {
        val body = "shell /bin/initprobe exact\n"
        val pad = "#" + "x".repeat(MAX_CONF - body.length - 2) + "\n"
        val text = pad + body
        assertEquals(MAX_CONF, text.toByteArray().size)
        val lines = boot(mapOf("/bin/sh" to probe, "/bin/initprobe" to probe, "/etc/inittab" to text.toByteArray())) { l ->
            l.any { it.startsWith("exact ") }
        }
        assertTrue(lines.none { it.startsWith("init: /etc/inittab") }, lines.toString())
        assertTrue(lines.none { it.startsWith("sh ") }, lines.toString())
    }

    @Test
    fun `語の数の上限を超えた行は、警告を1つだけ書いて捨てる`() {
        val many = List(MAX_WORDS + 10) { "a" }.joinToString(" ")
        val tab = inittab(
            """
            env $many
            run /bin/initprobe $many
            frob $many
            run /bin/initprobe ok
            """,
        )
        val lines = boot(mapOf("/bin/initprobe" to probe, tab)) { l -> l.any { it.startsWith("kibi: init exited") } }
        val warnings = lines.filter { it.startsWith("init: /etc/inittab:") }
        assertEquals(
            listOf(
                "init: /etc/inittab:1: too many words",
                "init: /etc/inittab:2: too many words",
                "init: /etc/inittab:3: unknown entry",
            ),
            warnings,
            lines.toString(),
        )
        assertTrue("ok fg=self stdin=tty mode=cooked FOO=(none) HOME=/ args=2" in lines, lines.toString())
    }

    @Test
    fun `すぐ終わるrespawnは、1秒おいてから起こし直す`() {
        val timed = bootTimed(mapOf("/bin/initprobe" to probe, inittab("respawn /bin/initprobe quick"))) { l ->
            l.count { it.startsWith("quick ") } >= 3
        }
        val times = timed.filter { it.first.startsWith("quick ") }.map { it.second }
        // 子は書いてからすぐ終わるので、次の行までは少なくとも 1 秒（届くまでの揺れを少し見込む）
        for (i in 1 until times.size) {
            val gapMs = (times[i] - times[i - 1]) / 1_000_000
            assertTrue(gapMs >= 900, "起こし直しが早すぎる: ${gapMs}ms ${timed.map { it.first }}")
        }
    }

    private companion object {
        /** init の inittab の大きさの上限（`os/sbin/init.zig` の MAX_CONF） */
        const val MAX_CONF = 8192

        /** init の全部の項目の語の数の上限（`os/sbin/init.zig` の MAX_WORDS） */
        const val MAX_WORDS = 512
    }
}
