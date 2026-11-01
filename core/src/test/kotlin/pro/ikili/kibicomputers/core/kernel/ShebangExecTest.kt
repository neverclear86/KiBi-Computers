package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.process.ModuleHash
import pro.ikili.kibicomputers.core.kernel.process.ProcessDriverFactory
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.Shebang
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import java.time.Duration
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** シバン付きのスクリプトの exec（#93）。インタプリタは偽のランタイムの WASM */
class ShebangExecTest {
    private val limit = Duration.ofSeconds(30)

    private fun parse(text: String): Shebang.Line? {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return Shebang.parse(bytes, bytes.size)
    }

    private fun assertErrno(errno: Errno, block: () -> Unit) {
        val e = assertThrows<FsException> { block() }
        assertEquals(errno, e.errno, e.message)
    }

    @Test
    fun `シバンの行の解釈はLinuxに合わせる`() {
        assertEquals(Shebang.Line("/bin/lua", null), parse("#!/bin/lua\nprint(1)\n"))
        assertEquals(Shebang.Line("/bin/lua", null), parse("#! \t/bin/lua  \r\nprint(1)\r\n"))
        // 残りはまるごと1つの引数（空白で分けない）
        assertEquals(Shebang.Line("/bin/env", "lua -e x"), parse("#!/bin/env  lua -e x \n"))
        // 改行がなくても、ファイルの終わりまでが1行
        assertEquals(Shebang.Line("/bin/lua", null), parse("#!/bin/lua"))
        assertNull(parse("print(1)\n"))
        assertNull(parse("#"))
        assertNull(parse(""))
        assertErrno(Errno.NOEXEC) { parse("#!\n") }
        assertErrno(Errno.NOEXEC) { parse("#!   \nprint(1)") }
        assertErrno(Errno.NOEXEC) { parse("#!/bin/lua\u0000\n") }
        assertErrno(Errno.NOEXEC) { parse("#!/" + "a".repeat(Shebang.MAX_LINE_BYTES) + "\n") }
        val broken = byteArrayOf('#'.code.toByte(), '!'.code.toByte(), '/'.code.toByte(), 0xff.toByte(), '\n'.code.toByte())
        assertErrno(Errno.NOEXEC) { Shebang.parse(broken, broken.size) }
    }

    private fun Harness.tickUntil(condition: () -> Boolean) = eventually(10_000, "条件がそろわない") {
        scheduler.tick()
        Thread.sleep(1)
        condition()
    }

    private fun Machine.mountRoot(vararg dirs: String) = lock.withLockValue {
        vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
        for (d in dirs) vfs.createDirectory(VfsPath.parse(d))
    }

    private fun Machine.writeFile(path: String, text: String) = writeFile(path, text.toByteArray(Charsets.UTF_8))

    private fun Machine.writeFile(path: String, bytes: ByteArray) = lock.withLockValue {
        val f = vfs.open(VfsPath.parse(path), OpenOptions(read = true, write = true, create = true, truncate = true))
        vfs.write(f, bytes, 0, bytes.size)
    }

    /** 偽のインタプリタ（`/bin/interp`）と、それが受け取った argv を記録するドライバー */
    private inner class Recorded(h: Harness, m: Machine) {
        val bytes = "fake-interpreter".toByteArray()
        val argvs: MutableList<List<String>> = Collections.synchronizedList(ArrayList())
        val programs: MutableList<ModuleHash> = Collections.synchronizedList(ArrayList())
        val drivers = ProcessDriverFactory { program ->
            programs += program.hash
            TestDriver(ProcessKind.BLOCKING) { ctx ->
                argvs += ctx.args
                StepResult.Exit(0)
            }
        }

        init {
            h.runtime.define(bytes, emptyModule())
            m.writeFile("/bin/interp", bytes)
        }
    }

    @Test
    fun `シバンのインタプリタで起動し、argvはインタプリタ・引数・スクリプトの絶対パス・元の引数`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            m.mountRoot("/bin", "/home")
            val r = Recorded(h, m)
            m.writeFile("/home/plain.lua", "#!/bin/interp\nprint(1)\n")
            m.writeFile("/home/witharg", "#!/bin/interp -x\n")

            val p1 = m.exec("/home/plain.lua", r.drivers)
            h.tickUntil { m.stateOf(p1).isTerminal }
            assertEquals(listOf("/bin/interp", "/home/plain.lua"), r.argvs.last())
            assertEquals(ModuleHash.of(r.bytes), r.programs.last())

            val p2 = m.exec("/home/witharg", r.drivers, SpawnOptions(args = listOf("witharg", "a", "b c")))
            h.tickUntil { m.stateOf(p2).isTerminal }
            assertEquals(listOf("/bin/interp", "-x", "/home/witharg", "a", "b c"), r.argvs.last())

            // 相対パスは作業ディレクトリから辿り、スクリプトは絶対パスで渡す（拡張子は見ない）
            val child = AtomicReference<Int>()
            val parent = m.spawn(h.program(emptyModule()), TestDriver(ProcessKind.BLOCKING) { ctx ->
                ctx.changeDirectory("/home")
                child.set(ctx.exec("./witharg", r.drivers, SpawnOptions()))
                StepResult.Exit(0)
            })
            h.tickUntil { child.get() != null && m.stateOf(child.get()).isTerminal && m.stateOf(parent).isTerminal }
            assertEquals(listOf("/bin/interp", "-x", "/home/witharg"), r.argvs.last())
        }
    }

    @Test
    fun `インタプリタもスクリプトならたどり、深すぎればLOOP`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            m.mountRoot("/bin", "/home")
            val r = Recorded(h, m)
            m.writeFile("/bin/wrap", "#!/bin/interp\n")
            m.writeFile("/home/s", "#!/bin/wrap\n")
            val p = m.exec("/home/s", r.drivers, SpawnOptions(args = listOf("s", "1")))
            h.tickUntil { m.stateOf(p).isTerminal }
            assertEquals(listOf("/bin/interp", "/bin/wrap", "/home/s", "1"), r.argvs.last())

            // 自分自身をインタプリタにしている
            m.writeFile("/home/self", "#!/home/self\n")
            assertErrno(Errno.LOOP) { m.exec("/home/self", r.drivers) }
            // ちょうど上限の深さまではたどれる
            var prev = "/bin/interp"
            for (i in 1..Shebang.MAX_DEPTH) {
                m.writeFile("/home/d$i", "#!$prev\n")
                prev = "/home/d$i"
            }
            val deep = m.exec(prev, r.drivers)
            h.tickUntil { m.stateOf(deep).isTerminal }
            assertEquals("/bin/interp", r.argvs.last().first())
            m.writeFile("/home/toodeep", "#!$prev\n")
            assertErrno(Errno.LOOP) { m.exec("/home/toodeep", r.drivers) }
        }
    }

    @Test
    fun `インタプリタがない・読めないシバンはerrnoで失敗する`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            m.mountRoot("/bin", "/home")
            val r = Recorded(h, m)
            m.writeFile("/home/missing", "#!/bin/none\n")
            m.writeFile("/home/dir", "#!/bin\n")
            m.writeFile("/home/empty", "#!\n")
            m.writeFile("/home/notwasm", "#!/home/plain\n")
            m.writeFile("/home/plain", "print(1)\n")
            assertErrno(Errno.NOENT) { m.exec("/home/missing", r.drivers) }
            assertErrno(Errno.ACCES) { m.exec("/home/dir", r.drivers) }
            assertErrno(Errno.NOEXEC) { m.exec("/home/empty", r.drivers) }
            assertErrno(Errno.NOEXEC) { m.exec("/home/notwasm", r.drivers) }
            assertEquals(0, m.lock.withLockValue { m.kernel.processes.all().size })
        }
    }
}
