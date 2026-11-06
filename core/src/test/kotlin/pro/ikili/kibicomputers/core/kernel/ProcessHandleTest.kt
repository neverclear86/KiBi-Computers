package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.fd.FdIo
import pro.ikili.kibicomputers.core.kernel.fd.FdReadiness
import pro.ikili.kibicomputers.core.kernel.fd.FdTable
import pro.ikili.kibicomputers.core.kernel.fd.IoResult
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.ExitReason
import pro.ikili.kibicomputers.core.kernel.process.ExitStatus
import pro.ikili.kibicomputers.core.kernel.process.KillResult
import pro.ikili.kibicomputers.core.kernel.process.KillSignal
import pro.ikili.kibicomputers.core.kernel.process.ProcessDriver
import pro.ikili.kibicomputers.core.kernel.process.ProcessHandle
import pro.ikili.kibicomputers.core.kernel.process.ProcessObject
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** 子のハンドル（`kibi:process` の `process` resource。#74）: fd への置き方、status・kill・閉じたときの親なし、pid の使い回し */
class ProcessHandleTest {
    private class Env(val h: Harness, val m: Machine, val program: Program) {
        val parentGate = Gate(m)
        val parent: Int = m.spawnTest(program, gated(parentGate))

        fun spawnChild(driver: ProcessDriver, holder: Int = parent): ProcessHandle = m.spawnWithHandle(program, driver, SpawnOptions(), holder)

        fun table(pid: Int = parent): FdTable = FdTable(m.kernel.processes[pid]!!, m.kernel.objects, m.wakeSink)

        fun objOf(fd: Int, pid: Int = parent): ProcessObject = m.lock.withLockValue { table(pid).objectOf(fd) as ProcessObject }

        fun status(fd: Int, pid: Int = parent): ExitStatus? = m.lock.withLockValue {
            m.lifecycle.processHandleStatusLocked(m.kernel.processes[pid]!!, objOf(fd, pid))
        }

        fun parentPidOf(pid: Int): Int? = m.process(pid)?.parentPid

        fun waitTerminal(pid: Int) = h.tickUntilTrue("$pid が終わらない") { m.processState(pid)?.isTerminal != false }
    }

    private fun env(block: Env.() -> Unit) = timeout(Duration.ofSeconds(30)) {
        Harness().use { h ->
            val m = h.machine()
            val env = Env(h, m, h.program(emptyModule()))
            try {
                env.block()
            } finally {
                env.parentGate.open()
            }
        }
    }

    @Test
    fun `spawnは親の表に読むだけのハンドルを置き、pollは終わるまで待ち、終わればready`() = env {
        val gate = Gate(m)
        val (pid, fd) = spawnChild(gated(gate))
        m.lock.withLockValue {
            val entry = table().entry(fd)
            assertEquals(HandleRights.READ, entry.rights)
            assertEquals(m.kernel.processes[pid]!!.objectId, entry.objectId)
            // 子の表には置かない
            assertTrue(table(pid).entries().values.none { it.objectId == entry.objectId })
            assertEquals(
                FdReadiness.Pending(WaitKey(entry.objectId, WaitCondition.EXITED)),
                FdIo.pollLocked(m.kernel, m.vfs, parent, fd, WaitCondition.READABLE),
            )
            assertEquals(FdReadiness.Error(Errno.BADF), FdIo.pollLocked(m.kernel, m.vfs, parent, fd, WaitCondition.WRITABLE))
            // ハンドルは読めない（fd_read は invalid）
            assertEquals(IoResult.Error(Errno.INVAL), FdIo.readLocked(m.kernel, m.vfs, m.wakeSink, parent, fd, ByteArray(4), 0, 4))
        }
        assertNull(status(fd))
        gate.open()
        waitTerminal(pid)
        m.lock.withLockValue { assertEquals(FdReadiness.Ready(0), FdIo.pollLocked(m.kernel, m.vfs, parent, fd, WaitCondition.READABLE)) }
    }

    @Test
    fun `書く権利のあるProcessObjectのfdは、読みはできず書きの待ちはinvalid`() = env {
        val (pid, _) = spawnChild(exiting(0))
        val fd = m.lock.withLockValue { table().install(m.kernel.processes[pid]!!.objectId.also { m.kernel.objects.retain(it) }, HandleRights.ALL) }
        m.lock.withLockValue { assertEquals(FdReadiness.Error(Errno.INVAL), FdIo.pollLocked(m.kernel, m.vfs, parent, fd, WaitCondition.WRITABLE)) }
    }

    @Test
    fun `親のfd表が満杯ならmfileで、子を作らない`() = env {
        m.lock.withLockValue {
            val t = table()
            while (!t.isFull) t.open(ByteStream(1))
        }
        val before = m.lock.withLockValue { m.kernel.processes.size }
        assertEquals(Errno.MFILE, assertThrows<FsException> { spawnChild(exiting(0)) }.errno)
        assertEquals(Errno.MFILE, assertThrows<FsException> { m.checkHandleRoom(parent) }.errno)
        assertEquals(before, m.lock.withLockValue { m.kernel.processes.size })
    }

    @Test
    fun `statusは親が受け取ったときだけ刈り取り、刈り取った後も同じ値を返す`() = env {
        val (pid, fd) = spawnChild(exiting(7))
        waitTerminal(pid)
        // 親でないプロセスが同じオブジェクトを持っても、刈り取らない
        val other = m.spawnTest(program, gated(Gate(m)))
        val otherFd = m.lock.withLockValue { table(other).install(m.kernel.processes[pid]!!.objectId.also { m.kernel.objects.retain(it) }, HandleRights.READ) }
        val expected = ExitStatus(pid, ExitReason.EXITED, 7, null)
        assertEquals(expected, status(otherFd, other))
        assertTrue(m.process(pid) != null, "親でないのに刈り取った")
        assertEquals(expected, status(fd))
        assertNull(m.process(pid), "親が受け取ったのに刈り取らない")
        assertEquals(expected, status(fd))
        assertEquals(expected, status(otherFd, other))
    }

    @Test
    fun `killはハンドルの子に届き、終わった子にはalready-exited`() = env {
        val (pid, fd) = spawnChild(gated(Gate(m)))
        val obj = objOf(fd)
        assertEquals(KillResult.DELIVERED, m.lock.withLockValue { m.lifecycle.killProcessHandleLocked(obj, KillSignal.TERMINATE) })
        waitTerminal(pid)
        assertEquals(ExitStatus(pid, ExitReason.KILLED, 0, KillSignal.TERMINATE), status(fd))
        assertEquals(KillResult.ALREADY_EXITED, m.lock.withLockValue { m.lifecycle.killProcessHandleLocked(obj, KillSignal.KILL) })
    }

    /** 子 A を刈り取ってから、同じ pid の子 B を作る。A のハンドルの fd と B の pid を返す */
    private fun Env.reusedPid(): Pair<Int, Int> {
        val a = spawnChild(exiting(3))
        waitTerminal(a.pid)
        assertEquals(ExitStatus(a.pid, ExitReason.EXITED, 3, null), status(a.fd))
        m.lock.withLockValue { m.kernel.processes.setNextPidForTest(a.pid) }
        val b = m.spawnTest(program, gated(Gate(m)), parentPid = parent)
        assertEquals(a.pid, b, "pid を使い回していない")
        return a.fd to b
    }

    @Test
    fun `pidを使い回しても、古いハンドルのstatusは古い子の値で、新しい子を刈り取らない`() = env {
        val (fd, b) = reusedPid()
        assertEquals(ExitStatus(b, ExitReason.EXITED, 3, null), status(fd))
        assertTrue(!m.stateOf(b).isTerminal)
        assertEquals(parent, parentPidOf(b))
    }

    @Test
    fun `pidを使い回しても、古いハンドルのkillは新しい子に届かない`() = env {
        val (fd, b) = reusedPid()
        assertEquals(KillResult.ALREADY_EXITED, m.lock.withLockValue { m.lifecycle.killProcessHandleLocked(objOf(fd), KillSignal.KILL) })
        repeat(20) { h.scheduler.tick() }
        assertTrue(!m.stateOf(b).isTerminal)
    }

    @Test
    fun `pidを使い回しても、古いハンドルを閉じて新しい子が親なしにならない`() = env {
        val (fd, b) = reusedPid()
        m.lock.withLockValue { table().close(fd) }
        assertEquals(parent, parentPidOf(b))
    }

    @Test
    fun `ハンドルをclose・dup2・renumberで消すと、子は親なしになる`() = env {
        for (how in listOf("close", "dup2", "renumber")) {
            val (pid, fd) = spawnChild(gated(Gate(m)))
            m.lock.withLockValue {
                val t = table()
                when (how) {
                    "close" -> t.close(fd)
                    "dup2" -> t.dup2(t.open(ByteStream(1)), fd)
                    else -> t.renumber(t.open(ByteStream(1)), fd)
                }
            }
            assertEquals(0, parentPidOf(pid), how)
        }
    }

    @Test
    fun `終わった子のハンドルを閉じると、刈り取る`() = env {
        val (pid, fd) = spawnChild(exiting(0))
        waitTerminal(pid)
        m.lock.withLockValue { table().close(fd) }
        assertNull(m.process(pid))
    }

    @Test
    fun `dupが残っていれば親なしにせず、同じオブジェクトへのdup2も親なしにしない`() = env {
        val (pid, fd) = spawnChild(gated(Gate(m)))
        val copy = m.lock.withLockValue { table().dup(fd) }
        m.lock.withLockValue { table().close(fd) }
        assertEquals(parent, parentPidOf(pid))
        val again = m.lock.withLockValue { table().dup(copy) }
        m.lock.withLockValue { table().dup2(copy, again) }
        assertEquals(parent, parentPidOf(pid))
        m.lock.withLockValue {
            table().close(copy)
            table().close(again)
        }
        assertEquals(0, parentPidOf(pid))
    }

    @Test
    fun `親でないプロセスがハンドルを閉じても、何もしない`() = env {
        val (pid, _) = spawnChild(gated(Gate(m)))
        val other = m.spawnTest(program, gated(Gate(m)))
        val otherFd = m.lock.withLockValue { table(other).install(m.kernel.processes[pid]!!.objectId.also { m.kernel.objects.retain(it) }, HandleRights.READ) }
        m.lock.withLockValue { table(other).close(otherFd) }
        assertEquals(parent, parentPidOf(pid))
    }

    @Test
    fun `親が終われば、ハンドルを閉じたときと同じく子は親なしになる`() = env {
        val (pid, _) = spawnChild(gated(Gate(m)))
        val (done, _) = spawnChild(exiting(0))
        waitTerminal(done)
        parentGate.open()
        waitTerminal(parent)
        assertEquals(0, parentPidOf(pid))
        // 終わっていた子は、親なしのゾンビにならずに片付く（刈り取る親がいない）
        assertNotEquals(parent, parentPidOf(done))
    }

    @Test
    fun `setNextPidForTestは1以上だけを受け、次のpidにする`() = env {
        m.lock.withLockValue {
            val t = m.kernel.processes
            assertThrows<IllegalArgumentException> { t.setNextPidForTest(0) }
            assertThrows<IllegalArgumentException> { t.setNextPidForTest(-1) }
            t.setNextPidForTest(500)
            assertEquals(500, t.allocatePid())
        }
    }
}
