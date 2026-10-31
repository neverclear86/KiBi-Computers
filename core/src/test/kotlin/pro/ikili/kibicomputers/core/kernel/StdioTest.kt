package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.fd.FdIo
import pro.ikili.kibicomputers.core.kernel.fd.FdTable
import pro.ikili.kibicomputers.core.kernel.fd.IoResult
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.obj.HandleEntry
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** 標準入出力（fd 0/1/2）の割り当てとコンソール（#58） */
class StdioTest {
    private val limit = Duration.ofSeconds(30)

    private class ConsoleHost : MachineHost {
        val out = ByteArrayOutputStream()
        val threads: MutableSet<Thread> = Collections.synchronizedSet(HashSet())

        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit

        override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
            threads += Thread.currentThread()
            synchronized(out) { out.write(bytes, 0, bytes.size) }
        }

        fun text(): String = synchronized(out) { out.toString(Charsets.UTF_8) }

        fun size(): Int = synchronized(out) { out.size() }
    }

    private fun Harness.tickUntil(message: String = "条件がそろわない", condition: () -> Boolean) = eventually(10_000, message) {
        scheduler.tick()
        Thread.sleep(1)
        condition()
    }

    private fun Machine.entries(pid: Int): Map<Int, HandleEntry> = lock.withLockValue { kernel.processes[pid]!!.handles.entries() }

    /** 止められるまで待ち続けるブロッキング型のプロセスを作る（fd を外から操作するための親） */
    private fun Harness.waitingParent(m: Machine): Int {
        val never = m.lock.withLockValue { m.kernel.objects.add(ByteStream(1)) }
        val pid = m.spawnTest(program(emptyModule()), TestDriver(ProcessKind.BLOCKING) { ctx ->
            ctx.block(listOf(WaitKey(never, WaitCondition.READABLE)))
            StepResult.Exit(0)
        })
        tickUntil { m.stateOf(pid) == ProcessState.WAITING_EVENT }
        return pid
    }

    private fun write(ctx: ProcessContext, fd: Int, text: String): IoResult {
        val bytes = text.toByteArray()
        return FdIo.writeBlocking(ctx, fd, bytes, 0, bytes.size)
    }

    @Test
    fun `親のないプロセスの標準入出力はコンソールにつながる`() = timeout(limit) {
        Harness().use { h ->
            val host = ConsoleHost()
            val m = h.machine(host = host)
            val results = Collections.synchronizedList(ArrayList<Any>())
            val pid = m.spawnTest(h.program(emptyModule()), TestDriver(ProcessKind.BLOCKING) { ctx ->
                results += write(ctx, 1, "hello ")
                results += write(ctx, 2, "err ")
                results += FdIo.write(ctx, 0, ByteArray(1), 0, 1)
                results += FdIo.read(ctx, 1, ByteArray(1), 0, 1)
                val buf = ByteArray(16)
                val r = FdIo.readBlocking(ctx, 0, buf, 0, buf.size)
                results += r
                if (r is IoResult.Bytes) write(ctx, 1, "got:" + String(buf, 0, r.count))
                StepResult.Exit(0)
            })
            val entries = m.entries(pid)
            m.lock.withLockValue {
                assertEquals(
                    mapOf(
                        0 to HandleEntry(m.kernel.consoleInputId, rights = HandleRights.READ),
                        1 to HandleEntry(m.kernel.consoleOutputId, rights = HandleRights.WRITE),
                        2 to HandleEntry(m.kernel.consoleOutputId, rights = HandleRights.WRITE),
                    ),
                    entries,
                )
            }
            h.tickUntil { m.stateOf(pid) == ProcessState.WAITING_EVENT && host.text() == "hello err " }
            assertEquals(3, m.offerConsoleInput("abc".toByteArray()))
            h.tickUntil { m.stateOf(pid).isTerminal && host.text() == "hello err got:abc" }
            assertEquals(
                listOf(IoResult.Bytes(6), IoResult.Bytes(4), IoResult.Error(Errno.BADF), IoResult.Error(Errno.BADF), IoResult.Bytes(3)),
                results.toList(),
            )
            assertEquals(setOf(Thread.currentThread()), host.threads.toSet(), "コンソールは tick の中で配る")
            // 終わったプロセスは fd を持たず、コンソールはカーネルの参照だけが残る
            assertTrue(m.entries(pid).isEmpty())
            m.lock.withLockValue {
                assertEquals(1, m.kernel.objects.refCount(m.kernel.consoleInputId))
                assertEquals(1, m.kernel.objects.refCount(m.kernel.consoleOutputId))
            }
        }
    }

    @Test
    fun `コンソールの出力がいっぱいなら書き手はtickで配られるまで待つ`() = timeout(limit) {
        Harness().use { h ->
            val host = ConsoleHost()
            val m = h.machine(host = host)
            val total = Machine.CONSOLE_OUTPUT_BYTES * 2 + 100
            val data = ByteArray(total) { ('a' + it % 26).code.toByte() }
            val pid = m.spawnTest(h.program(emptyModule()), TestDriver(ProcessKind.BLOCKING) { ctx ->
                var off = 0
                while (off < total) {
                    val r = FdIo.writeBlocking(ctx, 1, data, off, total - off) as IoResult.Bytes
                    off += r.count
                }
                StepResult.Exit(0)
            })
            // 燃料を入れて、tick を呼ばずに走らせる: バッファがいっぱいになったところで待つ
            m.lock.withLockValue { m.kernel.fuel = m.config.fuelPerTick }
            eventually { m.stateOf(pid) == ProcessState.WAITING_EVENT }
            assertEquals(0, host.size())
            h.tickUntil { m.stateOf(pid).isTerminal && host.size() == total }
            assertTrue(data.contentEquals(synchronized(host.out) { host.out.toByteArray() }))
        }
    }

    @Test
    fun `子は親の0・1・2を引き継ぎ、差し替え・閉じたままもできる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val parent = h.waitingParent(m)
            val pipe = m.lock.withLockValue {
                val p = m.kernel.processes[parent]!!
                val fds = FdTable(p, m.kernel.objects, m.wakeSink)
                val fd = fds.open(ByteStream(64))
                fds.dup2(fd, 1) // 親の標準出力をストリームに差し替える
                fds.entry(fd).objectId
            }
            val parentEntries = m.entries(parent)
            val program = h.program(emptyModule())
            val blocker = {
                TestDriver(ProcessKind.BLOCKING) { ctx ->
                    ctx.block(listOf(WaitKey(pipe, WaitCondition.READABLE)))
                    StepResult.Exit(0)
                }
            }

            val inherit = m.spawn(program, blocker(), SpawnOptions(), parent)
            assertEquals(parentEntries.filterKeys { it < 3 }, m.entries(inherit))

            val custom = m.spawn(
                program,
                blocker(),
                SpawnOptions(stdio = StdioSpec(StdioSource.ParentFd(3), StdioSource.Object(pipe, HandleRights.WRITE), StdioSource.Closed)),
                parent,
            )
            assertEquals(mapOf(0 to parentEntries[3], 1 to HandleEntry(pipe, rights = HandleRights.WRITE)), m.entries(custom))

            val closed = m.spawn(program, blocker(), SpawnOptions(stdio = StdioSpec.CLOSED), parent)
            assertTrue(m.entries(closed).isEmpty())

            // 親の fd 1・3 と、inherit の 1、custom の 0・1
            m.lock.withLockValue { assertEquals(5, m.kernel.objects.refCount(pipe)) }

            // 開いていない親の fd は BADF。プロセスは作らない
            val before = m.lock.withLockValue { m.kernel.processes.all().size }
            val e = assertThrows<FsException> {
                m.spawn(program, blocker(), SpawnOptions(stdio = StdioSpec(stdin = StdioSource.ParentFd(9))), parent)
            }
            assertEquals(Errno.BADF, e.errno)
            assertEquals(before, m.lock.withLockValue { m.kernel.processes.all().size })

            // 子が終わると fd を返す
            m.stop()
            h.tickUntil { m.state == MachineState.STOPPED }
            m.lock.withLockValue { assertEquals(null, m.kernel.objects[pipe]) }
        }
    }

    @Test
    fun `子からの書き込みは引き継いだストリームに入る`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val parent = h.waitingParent(m)
            val stream = AtomicReference<ByteStream>()
            m.lock.withLockValue {
                val fds = FdTable(m.kernel.processes[parent]!!, m.kernel.objects, m.wakeSink)
                val s = ByteStream(64).also { stream.set(it) }
                fds.close(1)
                assertEquals(1, fds.open(s))
            }
            val child = m.spawn(h.program(emptyModule()), TestDriver(ProcessKind.BLOCKING) { ctx ->
                write(ctx, 1, "from child")
                StepResult.Exit(0)
            }, SpawnOptions(), parent)
            h.tickUntil { m.stateOf(child).isTerminal }
            m.lock.withLockValue {
                assertEquals(10, stream.get().available)
                assertEquals(1, m.kernel.objects.refCount(stream.get().id), "子の分は返した")
            }
        }
    }
}
