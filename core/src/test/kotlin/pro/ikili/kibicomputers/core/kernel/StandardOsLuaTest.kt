package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
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
 * 標準 OS のイメージの `/bin/lua`（#603）: イメージ（読み取り専用の `/`）に、書ける `/home`・`/tmp` を重ねた形
 * （ADR 0461 の実際の形に近いもの）で、シェルから `lua` と `#!/bin/lua` のスクリプトを動かす
 */
class StandardOsLuaTest {
    private val limit = Duration.ofSeconds(180)

    private class ConsoleHost : MachineHost {
        private val out = ByteArrayOutputStream()

        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit

        override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
            synchronized(out) { out.write(bytes, 0, bytes.size) }
        }

        fun text(): String = synchronized(out) { out.toString(Charsets.UTF_8).replace("\r\n", "\n") }
    }

    private class Result(val console: String, val state: ProcessState, val exitCode: Int?, val kind: ProcessKind)

    private val image: ImageFileSystem by lazy { requireNotNull(ImageFileSystem.bundled("os")) { "os.zip がリソースにない" } }

    private fun Machine.put(path: String, text: String) {
        val bytes = text.toByteArray()
        val f = vfs.open(VfsPath.parse(path), OpenOptions(read = true, write = true, create = true, truncate = true))
        vfs.write(f, bytes, 0, bytes.size)
    }

    /**
     * イメージ `os` を `/` に読み取り専用で、メモリの FS を `/home`・`/tmp` に書ける形でマウントし、[files] を置いて、
     * [path] を [args] で動かす。[input] はコンソールに打つ行（プロンプト `$ `・`> ` が出るたびに 1 行）
     */
    private fun run(
        path: String,
        args: List<String>,
        files: Map<String, String> = emptyMap(),
        input: List<String> = emptyList(),
    ): Result = Harness(execRuntime = EndiveRuntime()).use { h ->
        val host = ConsoleHost()
        // sh（スタック 256 KiB）と lua を同時に動かすので、ShellTest と同じ 16 MiB にする
        val m = h.machine(fuelPerTick = 10_000_000, host = host, memoryBytes = 16L * 1024 * 1024)
        m.vfs.mount(VfsPath.ROOT, image, readOnly = true, source = "disk:image:os")
        m.vfs.mount(VfsPath.parse("/home"), TestMemoryFileSystem(), readOnly = false, source = "test:home")
        m.vfs.mount(VfsPath.parse("/tmp"), TestMemoryFileSystem(), readOnly = false, source = "test:tmp")
        for ((p, text) in files) m.put(p, text)
        val env = mapOf("PATH" to "/bin", "HOME" to "/home")
        val pid = m.exec(path, KernelDrivers.DEFAULT, SpawnOptions(args = args, env = env, cwd = "/", fds = SpawnOptions.ROOT_PREOPEN))
        val kind = m.rt(pid).driver.kind
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
        // コンソールの出力は tick で配られるので、終わった後にも回して残りを受け取る
        repeat(5) { h.scheduler.tick() }
        Result(host.text(), m.stateOf(pid), exitCode, kind)
    }

    private fun sh(script: String, files: Map<String, String> = emptyMap()): Result =
        run("/bin/sh", listOf("sh", "-c", script.trimIndent()), files)

    private fun assertOutput(expected: String, script: String, files: Map<String, String> = emptyMap()) {
        val r = sh(script, files)
        assertEquals(expected.trimIndent() + "\n", r.console)
        assertEquals(ProcessState.EXITED, r.state, r.console)
        assertEquals(0, r.exitCode, r.console)
    }

    @Test
    fun `イメージosのbin_luaは、リソースのlua_wasmと同じバイト列で、根に余計なディレクトリを足さない`() {
        val stat = image.stat(VfsPath.parse("/bin/lua"))
        assertEquals(FileType.REGULAR_FILE, stat.type)
        val bytes = ByteArray(stat.size.toInt())
        image.read(stat.node, 0, bytes, 0, bytes.size)
        // testboot・リソースと同じビルドの出力（中身が同じなので、ProgramCache のコンパイルを共有する）
        val resource = requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/lua.wasm")) { "lua.wasm がリソースにない" }
            .use { it.readBytes() }
        assertArrayEquals(resource, bytes)
        // 根は bin・etc・home・sbin・tmp・usr（man ページ。#106）だけ（lua の元の置き場所 `kibicomputers/` の空のディレクトリが入らない）
        val root = image.readDirectory(image.stat(VfsPath.ROOT).node).map { it.name }.sorted()
        assertEquals(listOf("bin", "etc", "home", "sbin", "tmp", "usr"), root)
    }

    @Test
    fun `イメージのluaは-eの式を動かし、待機点型で動く`() = timeout(limit) {
        val r = run("/bin/lua", listOf("lua", "-e", "print(1+1)"))
        assertEquals("2\n", r.console)
        assertEquals(ProcessState.EXITED, r.state)
        assertEquals(0, r.exitCode)
        assertEquals(ProcessKind.WAIT_POINT, r.kind)
    }

    @Test
    fun `シェルからluaとシバンのスクリプトを動かす`() = timeout(limit) {
        val files = mapOf(
            "/home/s" to "#!/bin/lua\nprint('arg0 ' .. arg[0] .. ' ' .. #arg .. ' [' .. table.concat(arg, ',') .. ']')\nprint('second')\n",
            "/home/s.lua" to "print('rel ' .. arg[0])\n",
            "/home/in" to "abc\n",
        )
        assertOutput(
            """
            2
            arg0 /home/s 2 [a,b]
            second
            2
            arg0 /home/s 0 []
            second
            42
            ABC
            rel s.lua
            status 3
            """,
            """
            lua -e 'print(1+1)'
            /home/s a b
            /home/s | wc -l
            /home/s > /tmp/o; cat /tmp/o
            echo 'print(6*7)' | lua -
            lua -e 'io.write((io.read("a"):upper()))' < /home/in
            cd /home; lua s.lua
            lua -e 'os.exit(3)'; echo status ${'$'}?
            """,
            files,
        )
    }

    @Test
    fun `会話的なシェルから引数なしのluaを起こし、os_exitでシェルに戻る`() = timeout(limit) {
        val r = run("/bin/sh", listOf("sh"), input = listOf("lua", "print('lua' .. 6*7)", "os.exit()", "echo back", "exit"))
        assertEquals(ProcessState.EXITED, r.state, r.console)
        val lines = r.console.lines()
        assertEquals(true, "lua42" in lines, r.console)
        assertEquals(true, "back" in lines, r.console)
    }
}
