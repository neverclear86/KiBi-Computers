package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.fd.FdTable
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.obj.HandleEntry
import pro.ikili.kibicomputers.core.kernel.process.DEFAULT_HANDLE_LIMIT
import pro.ikili.kibicomputers.core.kernel.process.ModuleHash
import pro.ikili.kibicomputers.core.kernel.process.ProcessArgs
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.ProcessDriverFactory
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import java.time.Duration
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** プロセスの生成: 実行ファイルの読み込み、引数・環境変数・作業ディレクトリ、fd の引き継ぎ、親子関係（#45） */
class ProcessSpawnTest {
    private val limit = Duration.ofSeconds(30)

    /** プロセスから見えたもの */
    private data class Seen(val pid: Int, val parentPid: Int, val args: List<String>, val env: Map<String, String>, val cwd: String)

    private fun ProcessContext.seen() = Seen(pid, parentPid, args, env, cwd.toString())

    /** 見えたものを [out] に入れて終わるドライバー */
    private fun recorder(out: AtomicReference<Seen>, kind: ProcessKind = ProcessKind.BLOCKING) = TestDriver(kind) { ctx ->
        out.set(ctx.seen())
        StepResult.Exit(0)
    }

    /** 止められるまで待ち続けるドライバー（[onStart] を先に実行する） */
    private fun Machine.waiter(onStart: (ProcessContext) -> Unit = {}): TestDriver {
        val never = lock.withLockValue { kernel.objects.add(ByteStream(1)) }
        return TestDriver(ProcessKind.BLOCKING) { ctx ->
            onStart(ctx)
            ctx.block(listOf(WaitKey(never, WaitCondition.READABLE)))
            StepResult.Exit(0)
        }
    }

    private fun Harness.tickUntil(message: String = "条件がそろわない", condition: () -> Boolean) = eventually(10_000, message) {
        scheduler.tick()
        Thread.sleep(1)
        condition()
    }

    /** `/` にメモリのファイルシステムをつなぎ、[dirs] を作る */
    private fun Machine.mountRoot(vararg dirs: String) = lock.withLockValue {
        vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
        for (d in dirs) vfs.createDirectory(VfsPath.parse(d))
    }

    private fun Machine.writeFile(path: String, bytes: ByteArray) = lock.withLockValue {
        val f = vfs.open(VfsPath.parse(path), OpenOptions(read = true, write = true, create = true, truncate = true))
        vfs.write(f, bytes, 0, bytes.size)
    }

    private fun Machine.processCount(): Int = lock.withLockValue { kernel.processes.all().size }

    private fun assertErrno(errno: Errno, block: () -> Unit) {
        val e = assertThrows<FsException> { block() }
        assertEquals(errno, e.errno, e.message)
    }

    @Test
    fun `引数・環境変数・作業ディレクトリを渡せて、親がなければ既定は空と根`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            m.mountRoot("/home")
            val program = h.program(emptyModule())
            val plain = AtomicReference<Seen>()
            val p1 = m.spawn(program, recorder(plain))
            h.tickUntil { m.stateOf(p1).isTerminal }
            assertEquals(Seen(p1, 0, emptyList(), emptyMap(), "/"), plain.get())

            val given = AtomicReference<Seen>()
            val env = linkedMapOf("PATH" to "/bin", "HOME" to "/home", "EMPTY" to "")
            val p2 = m.spawn(program, recorder(given), SpawnOptions(args = listOf("prog", "-v", "日本語"), env = env, cwd = "/home"))
            h.tickUntil { m.stateOf(p2).isTerminal }
            assertEquals(Seen(p2, 0, listOf("prog", "-v", "日本語"), env, "/home"), given.get())
            assertEquals(listOf("PATH", "HOME", "EMPTY"), given.get().env.keys.toList(), "並びを保つ")
        }
    }

    @Test
    fun `子は親の環境変数と作業ディレクトリを引き継ぎ、指定で差し替えられる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            m.mountRoot("/home", "/home/user", "/tmp")
            val parent = m.spawn(h.program(emptyModule()), m.waiter(), SpawnOptions(env = mapOf("A" to "1"), cwd = "/home"))
            val program = h.program(emptyModule())

            val inherit = AtomicReference<Seen>()
            val c1 = m.spawn(program, recorder(inherit), SpawnOptions(args = listOf("c1")), parent)
            val replaced = AtomicReference<Seen>()
            // 相対パスは親の作業ディレクトリから辿る
            val c2 = m.spawn(program, recorder(replaced), SpawnOptions(env = mapOf("B" to "2"), cwd = "user"), parent)
            val absolute = AtomicReference<Seen>()
            val c3 = m.spawn(program, recorder(absolute), SpawnOptions(env = emptyMap(), cwd = "/tmp"), parent)
            h.tickUntil { listOf(c1, c2, c3).all { m.stateOf(it).isTerminal } }
            assertEquals(Seen(c1, parent, listOf("c1"), mapOf("A" to "1"), "/home"), inherit.get())
            assertEquals(Seen(c2, parent, emptyList(), mapOf("B" to "2"), "/home/user"), replaced.get())
            assertEquals(Seen(c3, parent, emptyList(), emptyMap(), "/tmp"), absolute.get())
            m.stop()
            assertTrue(m.awaitTermination(5_000))
        }
    }

    @Test
    fun `正しくない引数・環境変数・作業ディレクトリは失敗し、プロセスを作らない`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            m.mountRoot("/dir")
            m.writeFile("/file", byteArrayOf(1))
            val program = h.program(emptyModule())
            fun spawn(options: SpawnOptions) = m.spawn(program, TestDriver(ProcessKind.BLOCKING) { StepResult.Exit(0) }, options)

            assertErrno(Errno.INVAL) { spawn(SpawnOptions(args = listOf("a\u0000b"))) }
            assertErrno(Errno.INVAL) { spawn(SpawnOptions(env = mapOf("" to "x"))) }
            assertErrno(Errno.INVAL) { spawn(SpawnOptions(env = mapOf("A=B" to "x"))) }
            assertErrno(Errno.INVAL) { spawn(SpawnOptions(env = mapOf("A" to "x\u0000"))) }
            // 上限ちょうどは通り、1 バイト超えると TOOBIG（"x" * n と NUL）
            val exact = "x".repeat(ProcessArgs.MAX_BYTES - 1)
            assertErrno(Errno.TOOBIG) { spawn(SpawnOptions(args = listOf(exact, ""))) }
            assertErrno(Errno.TOOBIG) { spawn(SpawnOptions(args = listOf("x".repeat(ProcessArgs.MAX_BYTES / 2)), env = mapOf("E" to "y".repeat(ProcessArgs.MAX_BYTES / 2)))) }
            assertErrno(Errno.NOENT) { spawn(SpawnOptions(cwd = "/nothing")) }
            assertErrno(Errno.NOTDIR) { spawn(SpawnOptions(cwd = "/file")) }
            assertEquals(0, m.processCount())
            assertEquals(MachineState.STOPPED, m.state)

            val ok = spawn(SpawnOptions(args = listOf(exact), cwd = "/dir"))
            h.tickUntil { m.stateOf(ok).isTerminal }
            assertState(ProcessState.EXITED, m, ok)
        }
    }

    @Test
    fun `fd 3以降を親から渡せる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val ids = IntArray(2)
            val parent = m.spawn(h.program(emptyModule()), m.waiter())
            m.lock.withLockValue {
                val fds = FdTable(m.kernel.processes[parent]!!, m.kernel.objects, m.wakeSink)
                assertEquals(3, fds.open(ByteStream(16)))
                assertEquals(4, fds.open(ByteStream(16)))
                ids[0] = fds.entry(3).objectId
                ids[1] = fds.entry(4).objectId
            }
            val program = h.program(emptyModule())
            val child = m.spawn(
                program,
                m.waiter(),
                SpawnOptions(fds = mapOf(7 to StdioSource.ParentFd(3), 4 to StdioSource.Inherit, 5 to StdioSource.Inherit)),
                parent,
            )
            val entries = m.lock.withLockValue { m.kernel.processes[child]!!.handles.entries() }
            assertEquals(HandleEntry(ids[0]), entries[7])
            assertEquals(HandleEntry(ids[1]), entries[4])
            assertEquals(null, entries[5], "親の閉じている fd は渡さない")
            assertEquals(null, entries[3], "指定しない fd 3 以降は渡さない")
            m.lock.withLockValue { assertEquals(2, m.kernel.objects.refCount(ids[0])) }

            val before = m.processCount()
            assertErrno(Errno.BADF) { m.spawn(program, m.waiter(), SpawnOptions(fds = mapOf(2 to StdioSource.Closed)), parent) }
            assertErrno(Errno.BADF) { m.spawn(program, m.waiter(), SpawnOptions(fds = mapOf(DEFAULT_LIMIT to StdioSource.Closed)), parent) }
            assertErrno(Errno.BADF) { m.spawn(program, m.waiter(), SpawnOptions(fds = mapOf(3 to StdioSource.ParentFd(9))), parent) }
            assertEquals(before, m.processCount())

            // 親がなければ fd 3 以降の Inherit は何も渡さない
            val orphan = m.spawn(program, m.waiter(), SpawnOptions(fds = mapOf(3 to StdioSource.Inherit)))
            assertEquals(setOf(0, 1, 2), m.lock.withLockValue { m.kernel.processes[orphan]!!.handles.entries().keys })
            m.stop()
            assertTrue(m.awaitTermination(5_000))
        }
    }

    @Test
    fun `VFSの実行ファイルからプロセスを作る`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            m.mountRoot("/bin", "/home")
            val bytes = "fake-exec-hello".toByteArray()
            h.runtime.define(bytes, emptyModule())
            m.writeFile("/bin/hello", bytes)
            val programs = Collections.synchronizedList(ArrayList<ModuleHash>())
            val seen = Collections.synchronizedList(ArrayList<Seen>())
            val drivers = ProcessDriverFactory { program ->
                programs += program.hash
                TestDriver(ProcessKind.BLOCKING) { ctx ->
                    seen += ctx.seen()
                    StepResult.Exit(3)
                }
            }

            val p1 = m.exec("/bin/hello", drivers)
            h.tickUntil { m.stateOf(p1).isTerminal }
            assertState(ProcessState.EXITED, m, p1)
            assertEquals(ModuleHash.of(bytes), programs.single())
            assertEquals(Seen(p1, 0, listOf("/bin/hello"), emptyMap(), "/"), seen.single())
            assertEquals(ModuleHash.of(bytes), m.lock.withLockValue { m.kernel.processes[p1]!!.moduleHash })

            // 親のプロセスから: 相対パスは親の作業ディレクトリから辿り、args を渡せばそのまま
            val child = AtomicReference<Int>()
            val parent = m.spawn(h.program(emptyModule()), m.waiter { ctx ->
                ctx.changeDirectory("/bin")
                child.set(ctx.exec("hello", drivers, SpawnOptions(args = listOf("hi", "there"), env = mapOf("K" to "V"))))
            })
            h.tickUntil { child.get() != null && m.stateOf(child.get()).isTerminal }
            assertEquals(Seen(child.get(), parent, listOf("hi", "there"), mapOf("K" to "V"), "/bin"), seen.last())
            assertEquals(listOf(child.get()), m.children(parent))
            m.stop()
            assertTrue(m.awaitTermination(5_000))
        }
    }

    @Test
    fun `実行できないファイルはerrnoで失敗する`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            m.mountRoot("/bin")
            m.writeFile("/bin/garbage", "not-registered".toByteArray())
            val drivers = ProcessDriverFactory { TestDriver(ProcessKind.BLOCKING) { StepResult.Exit(0) } }
            assertErrno(Errno.NOENT) { m.exec("/bin/none", drivers) }
            assertErrno(Errno.ACCES) { m.exec("/bin", drivers) }
            assertErrno(Errno.NOEXEC) { m.exec("/bin/garbage", drivers) }
            // ドライバーの側で断る
            val bytes = "fake-exec-unsupported".toByteArray()
            h.runtime.define(bytes, emptyModule())
            m.writeFile("/bin/unsupported", bytes)
            assertErrno(Errno.NOEXEC) { m.exec("/bin/unsupported", ProcessDriverFactory { throw FsException(Errno.NOEXEC) }) }
            assertEquals(0, m.processCount())
        }
    }

    @Test
    fun `子の一覧と親のpid、終わった親からは作れない`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val program = h.program(emptyModule())
            val kids = AtomicReference<List<Int>>()
            val childSeen = Collections.synchronizedList(ArrayList<Seen>())
            val childDriver = {
                TestDriver(ProcessKind.WAIT_POINT) { ctx ->
                    childSeen += ctx.seen()
                    StepResult.Exit(0)
                }
            }
            val parent = m.spawn(program, m.waiter { ctx ->
                ctx.spawn(program, childDriver())
                ctx.spawn(program, childDriver())
                kids.set(ctx.children())
            })
            h.tickUntil { kids.get() != null && childSeen.size == 2 }
            assertEquals(2, kids.get().size)
            assertEquals(kids.get(), m.children(parent))
            assertEquals(setOf(parent), childSeen.map { it.parentPid }.toSet())
            assertEquals(kids.get().toSet(), childSeen.map { it.pid }.toSet())
            assertEquals(emptyList<Int>(), m.children(kids.get()[0]))

            val done = m.spawn(program, TestDriver(ProcessKind.BLOCKING) { StepResult.Exit(0) })
            h.tickUntil { m.stateOf(done).isTerminal }
            assertThrows<IllegalArgumentException> { m.spawn(program, m.waiter(), SpawnOptions(), done) }
            m.stop()
            assertTrue(m.awaitTermination(5_000))
        }
    }

    @Test
    fun `作業ディレクトリを変える`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            m.mountRoot("/a", "/a/b")
            m.writeFile("/a/f", byteArrayOf(1))
            val results = Collections.synchronizedList(ArrayList<Any>())
            val pid = m.spawn(h.program(emptyModule()), TestDriver(ProcessKind.BLOCKING) { ctx ->
                ctx.changeDirectory("/a")
                results += ctx.cwd.toString()
                ctx.changeDirectory("b")
                results += ctx.cwd.toString()
                ctx.changeDirectory("..")
                results += ctx.cwd.toString()
                for (bad in listOf("/none", "f")) {
                    try {
                        ctx.changeDirectory(bad)
                    } catch (e: FsException) {
                        results += e.errno
                    }
                }
                results += ctx.cwd.toString()
                StepResult.Exit(0)
            })
            h.tickUntil { m.stateOf(pid).isTerminal }
            assertState(ProcessState.EXITED, m, pid)
            assertEquals(listOf<Any>("/a", "/a/b", "/a", Errno.NOENT, Errno.NOTDIR, "/a"), results.toList())
        }
    }

    private companion object {
        const val DEFAULT_LIMIT = DEFAULT_HANDLE_LIMIT
    }
}
