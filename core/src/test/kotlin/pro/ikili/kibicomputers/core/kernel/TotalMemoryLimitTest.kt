package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TmpFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.runtime.FakeModule
import pro.ikili.kibicomputers.core.runtime.WasmLinkException
import pro.ikili.kibicomputers.core.runtime.WasmMemory
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** サーバー全体の RAM の上限（#362）: 各マシンの勘定は、スケジューラの全体の勘定を親に持つ */
class TotalMemoryLimitTest {
    private val limit = Duration.ofSeconds(60)
    private val page = WasmMemory.PAGE_SIZE.toLong()

    private fun Harness.tickUntil(message: String = "条件がそろわない", condition: () -> Boolean) = eventually(30_000, message) {
        scheduler.tick()
        Thread.sleep(1)
        condition()
    }

    private fun memoryModule(pages: Int): FakeModule = FakeModule.builder().fuelMetered().memory(pages).build()

    private fun harness(totalPages: Int): Harness =
        Harness(SchedulerConfig.builder().maxConcurrentMachines(4).maxBlockingProcesses(64).totalMemoryBytes(totalPages * page).build())

    @Test
    fun `親の勘定に収まらない確保は断り、自分の分も数えない`() {
        val total = MemoryAccount(3 * page)
        val a = MemoryAccount(4, total)
        val b = MemoryAccount(4, total)
        assertTrue(a.tryReserve(2))
        assertFalse(b.tryReserve(2), "全体の残りは 1 ページ")
        assertEquals(0L, b.usedBytes, "断られた分は数えない")
        assertTrue(b.tryReserveBytes(page))
        assertFalse(b.tryReserveBytes(1))
        assertEquals(3 * page, total.usedBytes)
        a.release(2)
        assertEquals(page, total.usedBytes, "返した分は全体からも引く")
        assertTrue(b.tryReserve(2))
        b.release(2)
        b.releaseBytes(page)
        assertEquals(0L, total.usedBytes)
        // 自分の上限が先に来るときは、全体には数えない
        assertFalse(a.tryReserve(5))
        assertEquals(0L, total.usedBytes)
    }

    @Test
    fun `全体の上限で、別のマシンのmemory_growが断られる`() = timeout(limit) {
        harness(totalPages = 6).use { h ->
            assertEquals(6 * page, h.scheduler.memoryAccount.limitBytes)
            val a = h.machine(memoryBytes = 16 * page)
            val b = h.machine(memoryBytes = 16 * page)
            val holding = CountDownLatch(1)
            val never = a.lock.withLockValue { a.kernel.objects.add(ByteStream(1)) }
            val first = a.spawnTest(
                h.program(memoryModule(1)),
                TestDriver(ProcessKind.BLOCKING) { ctx ->
                    ctx.instance.memory!!.grow(3)
                    holding.countDown()
                    // メモリを持ったまま待ち続ける
                    ctx.block(listOf(WaitKey(never, WaitCondition.READABLE)))
                    StepResult.Exit(0)
                },
            )
            h.tickUntil("最初のプロセスがメモリを取らない") { holding.count == 0L }
            assertEquals(4 * page, h.scheduler.memoryAccount.usedBytes)

            // B の RAM（16 ページ）には余裕があっても、全体の残りは 2 ページ
            val results = mutableListOf<Int>()
            val grower = b.spawnTest(
                h.program(memoryModule(1)),
                TestDriver(ProcessKind.BLOCKING) { ctx ->
                    val memory = ctx.instance.memory!!
                    synchronized(results) {
                        results += memory.grow(2)
                        results += memory.grow(1)
                    }
                    StepResult.Exit(0)
                },
            )
            h.tickUntil("grow のプロセスが終わらない") { b.stateOf(grower).isTerminal }
            assertEquals(ProcessState.EXITED, b.stateOf(grower))
            assertEquals(listOf(-1, 1), synchronized(results) { results.toList() })
            assertEquals(0, b.memoryAccount.usedPages)
            assertEquals(4 * page, h.scheduler.memoryAccount.usedBytes)

            // 最初のメモリが全体に入らないプロセスは作れない
            val big = b.spawnTest(h.program(memoryModule(3)), TestDriver(ProcessKind.BLOCKING) { StepResult.Exit(0) })
            h.tickUntil("3 ページのプロセスが終わらない") { b.stateOf(big).isTerminal }
            assertEquals(ProcessState.CRASHED, b.stateOf(big))
            assertInstanceOf(WasmLinkException::class.java, b.rt(big).lastError)

            // A が終わると、全体の分が戻り、B も同じ大きさを取れる
            a.stop()
            h.tickUntil("最初のプロセスが終わらない") { a.stateOf(first).isTerminal }
            assertEquals(0L, h.scheduler.memoryAccount.usedBytes)
            val growAfter = AtomicInteger(0)
            val again = b.spawnTest(
                h.program(memoryModule(1)),
                TestDriver(ProcessKind.BLOCKING) { ctx ->
                    growAfter.set(ctx.instance.memory!!.grow(3))
                    StepResult.Exit(0)
                },
            )
            h.tickUntil("2回目が終わらない") { b.stateOf(again).isTerminal }
            assertEquals(1, growAfter.get())
            assertEquals(0L, h.scheduler.memoryAccount.usedBytes)
        }
    }

    @Test
    fun `全体の上限で、別のマシンのtmpへの書き込みはNOSPCになる`() = timeout(limit) {
        harness(totalPages = 2).use { h ->
            val a = h.machine(memoryBytes = 16 * page)
            val b = h.machine(memoryBytes = 16 * page)
            assertTrue(a.memoryAccount.tryReserve(2), "A が全体を使い切る")
            val fs = TmpFileSystem(b.memoryAccount) { 0L }
            val e = assertThrows<FsException> { fs.createFile(VfsPath.parse("/x")) }
            assertEquals(Errno.NOSPC, e.errno)
            assertEquals(0L, b.memoryAccount.usedBytes)
            a.memoryAccount.release(2)
            val node = fs.createFile(VfsPath.parse("/x"))
            assertEquals(3, fs.write(node, 0, byteArrayOf(1, 2, 3), 0, 3))
            assertTrue(h.scheduler.memoryAccount.usedBytes > 0)
            fs.removeFile(VfsPath.parse("/x"))
            assertEquals(0L, h.scheduler.memoryAccount.usedBytes)
        }
    }

    /** `/tmp` をマウントして [bytes] バイト書く（カーネルロックの中） */
    private fun fillTmp(m: Machine, bytes: Int) = m.lock.withLockValue {
        assertEquals(null, m.mountTmp())
        val file = m.vfs.open(VfsPath.parse("/tmp/x"), OpenOptions(read = false, write = true, create = true))
        assertEquals(bytes, m.vfs.write(file, ByteArray(bytes), 0, bytes))
    }

    @Test
    fun `止まったマシンの tmp は、次の起動を待たずに全体へ返す`() = timeout(limit) {
        harness(totalPages = 6).use { h ->
            val m = h.machine(memoryBytes = 16 * page)
            fillTmp(m, 100)
            assertTrue(h.scheduler.memoryAccount.usedBytes > 0)
            m.stop()
            assertEquals(0L, h.scheduler.memoryAccount.usedBytes)
            assertEquals(0L, m.memoryAccount.usedBytes)
        }
    }

    @Test
    fun `閉じたマシンの tmp は、プロセスが終わったら全体へ返す`() = timeout(limit) {
        harness(totalPages = 6).use { h ->
            val m = h.machine(memoryBytes = 16 * page)
            val never = m.lock.withLockValue { m.kernel.objects.add(ByteStream(1)) }
            val started = CountDownLatch(1)
            val pid = m.spawnTest(
                h.program(memoryModule(1)),
                TestDriver(ProcessKind.BLOCKING) { ctx ->
                    started.countDown()
                    ctx.block(listOf(WaitKey(never, WaitCondition.READABLE)))
                    StepResult.Exit(0)
                },
            )
            h.tickUntil("プロセスが始まらない") { started.count == 0L }
            fillTmp(m, 100)
            // チャンクのアンロードやブロックの破壊（MachineManager.stopEntry）と同じ: close() だけ呼ぶ
            m.close()
            h.tickUntil("プロセスが終わらない") { m.stateOf(pid).isTerminal }
            assertEquals(0L, h.scheduler.memoryAccount.usedBytes)
        }
    }

    @Test
    fun `全体の上限の既定はヒープの最大値の半分`() {
        assertEquals(Runtime.getRuntime().maxMemory() / 2, SchedulerConfig.DEFAULT.totalMemoryBytes)
        assertEquals(page, SchedulerConfig.builder().totalMemoryBytes(page).build().totalMemoryBytes)
        assertThrows<IllegalArgumentException> { SchedulerConfig.builder().totalMemoryBytes(-1) }
    }
}
