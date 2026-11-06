package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.fd.FdIo
import pro.ikili.kibicomputers.core.kernel.fd.FdTable
import pro.ikili.kibicomputers.core.kernel.fd.IoResult
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.vfs.FileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** 同じ開いたファイル（受け継いだ fd など）を、いくつものスレッドが同時に読み書きする（#431。ADR 0052） */
class SharedOpenFileTest {
    private val limit = Duration.ofSeconds(60)

    /** 書き込みを遅くして、位置を読んでから進めるまでの間を広げるファイルシステム */
    private class SlowFileSystem(private val inner: FileSystem = TestMemoryFileSystem()) : FileSystem by inner {
        override fun write(node: Long, position: Long, src: ByteArray, off: Int, len: Int): Int {
            Thread.sleep(1)
            return inner.write(node, position, src, off, len)
        }
    }

    private fun Harness.tickUntil(message: String = "条件がそろわない", condition: () -> Boolean) = eventuallyDescribe(20_000, { message + dump() }) {
        scheduler.tick()
        Thread.sleep(1)
        condition()
    }

    private fun dump(): String = Thread.getAllStackTraces().filterKeys { it.name.startsWith("KiBi") }.entries
        .joinToString("\n") { (t, s) -> t.name + " " + t.state + "\n  " + s.take(14).joinToString("\n  ") }

    // オブジェクト [id] の参照を1つ取り、このプロセスの fd に入れる
    private fun ProcessContext.installShared(id: Int): Int = withKernelLock {
        kernel.objects.retain(id)
        FdTable(kernel.processes[pid]!!, kernel.objects, wakeSink).install(id)
    }

    // [threads] 本のスレッドで、同じ ctx（同じ fd）から同時に [body] を行う（ふつうのスレッドは CPU を持たないので、本当に重なる）
    private fun ProcessContext.concurrently(threads: Int, body: (Int) -> Unit) {
        val start = CountDownLatch(1)
        val errors = Collections.synchronizedList(ArrayList<Throwable>())
        val ts = (0 until threads).map { t ->
            Thread {
                try {
                    start.await()
                    body(t)
                } catch (e: Throwable) {
                    errors += e
                }
            }.also { it.start() }
        }
        start.countDown()
        for (t in ts) t.join(30_000)
        errors.firstOrNull()?.let { throw it }
    }

    @Test
    fun `同じ開いたファイルへの同時の書き込みでバイトが失われない`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            m.vfs.mount(VfsPath.ROOT, SlowFileSystem(), readOnly = false, source = "test:slow")
            // append でない開いたファイル（親と子が、受け継いだ stdout を同じファイルにリダイレクトした形）
            val file = m.vfs.open(VfsPath.parse("/out"), OpenOptions(read = true, write = true, create = true))
            val id = m.lock.withLockValue { m.kernel.objects.add(file) }
            val writers = 3
            val rounds = 60
            val failures = Collections.synchronizedList(ArrayList<IoResult>())
            val pid = m.spawnTest(h.program(emptyModule()), TestDriver(ProcessKind.BLOCKING) { ctx ->
                val fd = ctx.installShared(id)
                ctx.concurrently(writers) { w ->
                    val bytes = "w$w;".toByteArray()
                    repeat(rounds) {
                        val r = FdIo.writeBlocking(ctx, fd, bytes, 0, bytes.size)
                        if (r != IoResult.Bytes(bytes.size)) failures += r
                    }
                }
                StepResult.Exit(0)
            })
            h.tickUntil { m.stateOf(pid).isTerminal }
            assertState(ProcessState.EXITED, m, pid)
            assertEquals(emptyList<IoResult>(), failures.toList())
            val total = writers * rounds * 3
            assertEquals(total.toLong(), m.vfs.size(file), "上書きされて消えたバイトがある")
            assertEquals(total.toLong(), m.lock.withLockValue { file.position })
            val buf = ByteArray(total)
            m.vfs.pread(file, 0, buf, 0, total)
            val counts = String(buf).split(';').filter { it.isNotEmpty() }.groupingBy { it }.eachCount()
            assertEquals((0 until writers).associate { "w$it" to rounds }, counts, "書き込みが混ざった")
        }
    }

    @Test
    fun `同じ開いたファイルを同時に読んでも、同じ範囲を二度読まない`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
            val file = m.vfs.open(VfsPath.parse("/in"), OpenOptions(read = true, write = true, create = true))
            val total = 4000
            val data = ByteArray(total) { (it % 251).toByte() }
            m.vfs.pwrite(file, 0, data, 0, total)
            val id = m.lock.withLockValue { m.kernel.objects.add(file) }
            val read = Collections.synchronizedList(ArrayList<Int>())
            val pid = m.spawnTest(h.program(emptyModule()), TestDriver(ProcessKind.BLOCKING) { ctx ->
                val fd = ctx.installShared(id)
                ctx.concurrently(3) {
                    val buf = ByteArray(7)
                    while (true) {
                        val r = FdIo.readBlocking(ctx, fd, buf, 0, buf.size)
                        if (r !is IoResult.Bytes) break
                        read += r.count
                    }
                }
                StepResult.Exit(0)
            })
            h.tickUntil { m.stateOf(pid).isTerminal }
            assertState(ProcessState.EXITED, m, pid)
            assertEquals(total, read.sum(), "同じ範囲を二度読んだか、読み落とした")
        }
    }
}
