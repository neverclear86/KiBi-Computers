package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.io.ReadResult
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.runtime.FakeModule
import pro.ikili.kibicomputers.core.runtime.WasmLinkException
import pro.ikili.kibicomputers.core.runtime.WasmMemory
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** 線形メモリの上限（#39）: プロセスごとの上限と、コンピューター全体の RAM */
class MemoryLimitTest {
    private val limit = Duration.ofSeconds(60)
    private val page = WasmMemory.PAGE_SIZE.toLong()

    private fun Harness.tickUntil(message: String = "条件がそろわない", condition: () -> Boolean) = eventually(30_000, message) {
        scheduler.tick()
        Thread.sleep(1)
        condition()
    }

    private fun memoryModule(pages: Int): FakeModule = FakeModule.builder().fuelMetered().memory(pages).build()

    @Test
    fun `プロセスの上限を超えるgrowは失敗し、終わると使った分が戻る`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(memoryBytes = 16 * page, maxProcessMemoryBytes = 4 * page)
            val results = Collections.synchronizedList(mutableListOf<Int>())
            val inside = AtomicInteger()
            val pid = m.spawnTest(
                h.program(memoryModule(1)),
                TestDriver(ProcessKind.BLOCKING) { ctx ->
                    val memory = ctx.instance.memory!!
                    results += memory.grow(3)
                    results += memory.grow(1)
                    inside.set(m.memoryAccount.usedPages)
                    StepResult.Exit(0)
                },
            )
            h.tickUntil("終わらない") { m.stateOf(pid).isTerminal }
            assertEquals(ProcessState.EXITED, m.stateOf(pid))
            assertEquals(listOf(1, -1), results.toList())
            assertEquals(4, inside.get())
            assertEquals(0, m.memoryAccount.usedPages)
        }
    }

    @Test
    fun `RAMはプロセスの合計で数え、足りないプロセスは作れない`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(memoryBytes = 6 * page)
            val holding = CountDownLatch(1)
            val never = m.lock.withLockValue { m.kernel.objects.add(ByteStream(1)) }
            val growAfterFull = AtomicInteger(0)
            val first = m.spawnTest(
                h.program(memoryModule(1)),
                TestDriver(ProcessKind.BLOCKING) { ctx ->
                    ctx.instance.memory!!.grow(3)
                    holding.countDown()
                    // CPU を返して、メモリを持ったまま待ち続ける
                    ctx.block(listOf(WaitKey(never, WaitCondition.READABLE)))
                    StepResult.Exit(0)
                },
            )
            h.tickUntil("最初のプロセスがメモリを取らない") { holding.count == 0L }
            assertEquals(4, m.memoryAccount.usedPages)

            // 残り 2 ページに、最初から 3 ページのプロセスは入らない
            val second = m.spawnTest(h.program(memoryModule(3)), TestDriver(ProcessKind.BLOCKING) { StepResult.Exit(0) })
            h.tickUntil("2つめが終わらない") { m.stateOf(second).isTerminal }
            assertEquals(ProcessState.CRASHED, m.stateOf(second))
            assertInstanceOf(WasmLinkException::class.java, m.rt(second).lastError)

            // 2 ページなら入り、そこで RAM がいっぱいになる
            val third = m.spawnTest(
                h.program(memoryModule(2)),
                TestDriver(ProcessKind.BLOCKING) { ctx ->
                    growAfterFull.set(ctx.instance.memory!!.grow(1))
                    StepResult.Exit(0)
                },
            )
            h.tickUntil("3つめが終わらない") { m.stateOf(third).isTerminal }
            assertEquals(ProcessState.EXITED, m.stateOf(third))
            assertEquals(-1, growAfterFull.get())
            assertEquals(4, m.memoryAccount.usedPages)

            m.stop()
            h.tickUntil("最初のプロセスが終わらない") { m.stateOf(first).isTerminal }
            assertEquals(0, m.memoryAccount.usedPages)
        }
    }

    @Test
    fun `ABI層はメモリの上限を設定できない`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val pid = m.spawnTest(
                h.program(memoryModule(1)),
                TestDriver(ProcessKind.BLOCKING, onConfigure = { _, b -> b.memoryLimit(1, null) }) { StepResult.Exit(0) },
            )
            h.tickUntil("終わらない") { m.stateOf(pid).isTerminal }
            assertEquals(ProcessState.CRASHED, m.stateOf(pid))
            assertInstanceOf(IllegalStateException::class.java, m.rt(pid).lastError)
        }
    }

    @Test
    fun `RAMの設定はページ単位に切り捨て、1ページより小さいと受け付けない`() {
        val config = MachineConfig.builder().memoryBytes(5 * page + 100).maxProcessMemoryBytes(2 * page + 1).build()
        assertEquals(5, config.memoryPages)
        assertEquals(2, config.maxProcessMemoryPages)
        assertEquals(MachineConfig.DEFAULT.memoryBytes, MachineConfig.DEFAULT.maxProcessMemoryBytes)
        assertThrows<IllegalArgumentException> { MachineConfig.builder().memoryBytes(page - 1) }
        assertThrows<IllegalArgumentException> { MachineConfig.builder().maxProcessMemoryBytes(0) }
    }

    @Test
    fun `LuaはRAMを超える確保でエラーになり、プロセスは落ちない`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = h.machine(fuelPerTick = 5_000_000, memoryBytes = 8L * 1024 * 1024)
            val bytes = requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/lua.wasm")) { "lua.wasm がリソースにない" }.use { it.readBytes() }
            val output = m.lock.withLockValue {
                m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
                m.vfs.createDirectory(VfsPath.parse("/bin"))
                val f = m.vfs.open(VfsPath.parse("/bin/lua"), OpenOptions(read = true, write = true, create = true, truncate = true))
                m.vfs.write(f, bytes, 0, bytes.size)
                ByteStream(64 * 1024).also { m.kernel.objects.add(it) }.id
            }
            val stdio = StdioSpec(StdioSource.Closed, StdioSource.Object(output, HandleRights.WRITE), StdioSource.Closed)
            val script = "local ok, err = pcall(string.rep, 'x', 16 * 1024 * 1024) print(ok, err) print(#string.rep('y', 1024 * 1024))"
            val pid = m.exec("/bin/lua", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("lua", "-e", script), stdio = stdio))
            h.tickUntil("終わらない") { m.stateOf(pid).isTerminal }
            val text = m.lock.withLockValue {
                val s = m.kernel.objects[output] as ByteStream
                val buf = ByteArray(s.available)
                s.read(buf, 0, buf.size, m.wakeSink) as? ReadResult.Bytes
                String(buf, Charsets.UTF_8)
            }
            assertEquals(ProcessState.EXITED, m.stateOf(pid), text)
            assertTrue(text.startsWith("false\tnot enough memory\n"), text)
            assertTrue(text.endsWith("1048576\n"), text)
            assertEquals(0, m.memoryAccount.usedPages)
        }
    }
}
