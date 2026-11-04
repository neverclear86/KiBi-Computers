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
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.process.formatBytes
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.runtime.FakeModule
import pro.ikili.kibicomputers.core.runtime.WasmMemory
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * 線形メモリの上限（#39・#407）: 初めて書き込んだ 4 KiB の区画を、プロセスごとの上限とコンピューター全体の RAM で数える。
 * メモリ不足で止まったら、コンソールにそう出す。ダミーのランタイム（同じ規則で数える）で確かめる
 */
class MemoryLimitTest {
    private val limit = Duration.ofSeconds(60)
    private val page = WasmMemory.PAGE_SIZE.toLong()
    private val chunk = 4096

    private class ConsoleRecorder : MachineHost {
        val console = StringBuffer()

        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) {}

        override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
            console.append(String(bytes, Charsets.UTF_8).replace("\r\n", "\n"))
        }
    }

    private fun Harness.tickUntil(message: String = "条件がそろわない", condition: () -> Boolean) = eventually(30_000, message) {
        scheduler.tick()
        Thread.sleep(1)
        condition()
    }

    private fun memoryModule(pages: Int): FakeModule = FakeModule.builder().fuelMetered().memory(pages).build()

    private fun Machine.spawnNamed(name: String, program: pro.ikili.kibicomputers.core.kernel.process.Program, driver: TestDriver): Int =
        spawn(program, driver, SpawnOptions(args = listOf(name)))

    /** [first] 番目から [count] 個の 4 KiB の区画に、1 バイトずつ書く */
    private fun WasmMemory.touch(first: Int, count: Int) {
        for (i in first until first + count) writeU8(i * chunk, 1)
    }

    @Test
    fun `宣言したページ数とgrowで増やした大きさは数えず、書いた区画だけを数える`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(memoryBytes = 2 * page)
            val usedAfterGrow = AtomicLong(-1)
            val usedAfterWrite = AtomicLong(-1)
            val growResult = AtomicInteger(0)
            // 最初から 8 ページ（RAM の 4 倍）を宣言しても作れる
            val pid = m.spawnTest(
                h.program(memoryModule(8)),
                TestDriver(ProcessKind.BLOCKING) { ctx ->
                    val memory = ctx.instance.memory!!
                    assertEquals(0L, m.memoryAccount.usedBytes, "宣言しただけでは数えない")
                    // 4 ページ増やす: 増やした所の最初と最後の区画だけを数える
                    growResult.set(memory.grow(4))
                    usedAfterGrow.set(m.memoryAccount.usedBytes)
                    // 同じ区画にもう一度書いても、数え直さない
                    memory.touch(0, 3)
                    memory.touch(0, 3)
                    usedAfterWrite.set(m.memoryAccount.usedBytes)
                    StepResult.Exit(0)
                },
            )
            h.tickUntil("終わらない") { m.stateOf(pid).isTerminal }
            assertEquals(ProcessState.EXITED, m.stateOf(pid))
            assertEquals(8, growResult.get())
            assertEquals(2L * chunk, usedAfterGrow.get())
            assertEquals(5L * chunk, usedAfterWrite.get())
            assertEquals(0L, m.memoryAccount.usedBytes, "終わったら返す")
        }
    }

    @Test
    fun `データセグメントで書いた区画はROMで、書き換えるまで数えない`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine(memoryBytes = page)
            val usedBefore = AtomicLong(-1)
            val usedAfter = AtomicLong(-1)
            val module = FakeModule.builder().fuelMetered().memory(1).data(0, ByteArray(3 * chunk) { 7 }).build()
            val pid = m.spawnTest(
                h.program(module),
                TestDriver(ProcessKind.BLOCKING) { ctx ->
                    val memory = ctx.instance.memory!!
                    assertEquals(7, memory.readU8(2 * chunk))
                    usedBefore.set(m.memoryAccount.usedBytes)
                    memory.writeU8(chunk, 8)
                    usedAfter.set(m.memoryAccount.usedBytes)
                    StepResult.Exit(0)
                },
            )
            h.tickUntil("終わらない") { m.stateOf(pid).isTerminal }
            assertEquals(ProcessState.EXITED, m.stateOf(pid))
            assertEquals(0L, usedBefore.get())
            assertEquals(chunk.toLong(), usedAfter.get())
        }
    }

    @Test
    fun `プロセスの上限を超える書き込みはトラップになり、メモリ不足と表示し、使った分が戻る`() = timeout(limit) {
        Harness().use { h ->
            val host = ConsoleRecorder()
            val m = h.machine(memoryBytes = 16 * page, maxProcessMemoryBytes = 4 * page, host = host)
            val inside = AtomicLong(-1)
            val pid = m.spawnNamed(
                "/bin/hog",
                h.program(memoryModule(8)),
                TestDriver(ProcessKind.BLOCKING) { ctx ->
                    val memory = ctx.instance.memory!!
                    memory.touch(0, 64)
                    inside.set(m.memoryAccount.usedBytes)
                    memory.touch(64, 1)
                    StepResult.Exit(0)
                },
            )
            h.tickUntil("終わらない") { m.stateOf(pid).isTerminal }
            assertEquals(ProcessState.CRASHED, m.stateOf(pid))
            assertEquals(4 * page, inside.get())
            assertEquals(WasmTrapKind.OUT_OF_MEMORY, (m.rt(pid).lastError as WasmTrapException).kind)
            h.tickUntil("表示が出ない: ${host.console}") { host.console.contains("kibi: /bin/hog (pid $pid) crashed: out of memory (RAM limit 256 KiB)\n") }
            assertEquals(0L, m.memoryAccount.usedBytes)
        }
    }

    @Test
    fun `RAMはプロセスの合計で数える`() = timeout(limit) {
        Harness().use { h ->
            val host = ConsoleRecorder()
            val m = h.machine(memoryBytes = 6 * page, host = host)
            val holding = CountDownLatch(1)
            val never = m.lock.withLockValue { m.kernel.objects.add(ByteStream(1)) }
            val first = m.spawnTest(
                h.program(memoryModule(4)),
                TestDriver(ProcessKind.BLOCKING) { ctx ->
                    ctx.instance.memory!!.touch(0, 64)
                    holding.countDown()
                    // CPU を返して、メモリを持ったまま待ち続ける
                    ctx.block(listOf(WaitKey(never, WaitCondition.READABLE)))
                    StepResult.Exit(0)
                },
            )
            h.tickUntil("最初のプロセスがメモリを取らない") { holding.count == 0L }
            assertEquals(4 * page, m.memoryAccount.usedBytes)

            // 残りは 2 ページ（32 区画）。それを超えて書くとトラップ
            val written = AtomicInteger()
            val second = m.spawnTest(
                h.program(memoryModule(4)),
                TestDriver(ProcessKind.BLOCKING) { ctx ->
                    val memory = ctx.instance.memory!!
                    for (i in 0 until 64) {
                        memory.touch(i, 1)
                        written.incrementAndGet()
                    }
                    StepResult.Exit(0)
                },
            )
            h.tickUntil("2つめが終わらない") { m.stateOf(second).isTerminal }
            assertEquals(ProcessState.CRASHED, m.stateOf(second))
            assertEquals(32, written.get())
            h.tickUntil("表示が出ない: ${host.console}") { host.console.contains("crashed: out of memory (RAM limit 384 KiB)\n") }
            assertEquals(4 * page, m.memoryAccount.usedBytes)

            m.stop()
            h.tickUntil("最初のプロセスが終わらない") { m.stateOf(first).isTerminal }
            assertEquals(0L, m.memoryAccount.usedBytes)
        }
    }

    /** RAM 1 ページで、15 区画を書いてから 1 ページ増やす（増やした所の端の 2 区画は数えられない）。その後 [after] で終わる */
    private fun runAfterRefusedGrow(after: (WasmMemory) -> StepResult): Triple<ProcessState, String, Int> = timeout(limit) {
        Harness().use { h ->
            val host = ConsoleRecorder()
            val m = h.machine(memoryBytes = page, host = host)
            val results = Collections.synchronizedList(mutableListOf<Int>())
            val pid = m.spawnNamed(
                "/bin/grow",
                h.program(memoryModule(1)),
                TestDriver(ProcessKind.BLOCKING) { ctx ->
                    val memory = ctx.instance.memory!!
                    memory.touch(0, 15)
                    results += memory.grow(1)
                    after(memory)
                },
            )
            h.tickUntil("終わらない") { m.stateOf(pid).isTerminal }
            assertEquals(listOf(-1), results.toList(), "端の区画を数えられないので grow は -1")
            // 表示はプロセスが終わるときに出る
            h.scheduler.tick()
            Triple(m.stateOf(pid), host.console.toString(), pid)
        }
    }

    @Test
    fun `growを断られた後に0以外で終わると、メモリ不足が原因だったと表示する`() {
        val (state, console, pid) = runAfterRefusedGrow { StepResult.Exit(1) }
        assertEquals(ProcessState.EXITED, state)
        assertTrue(
            console.contains("kibi: /bin/grow (pid $pid) exited with code 1 (memory.grow was refused: out of memory, RAM limit 64 KiB)\n"),
            console,
        )
    }

    @Test
    fun `growを断られた後にトラップで落ちると、理由にメモリ不足を添える`() {
        val (state, console, pid) = runAfterRefusedGrow { throw WasmTrapException(WasmTrapKind.UNREACHABLE, "abort") }
        assertEquals(ProcessState.CRASHED, state)
        assertTrue(
            console.contains("kibi: /bin/grow (pid $pid) crashed: unreachable executed (memory.grow was refused: out of memory, RAM limit 64 KiB)\n"),
            console,
        )
    }

    @Test
    fun `growを断られても自分で扱って正常に終われば、何も表示しない`() {
        val (state, console, _) = runAfterRefusedGrow { memory ->
            // 断られた後も、もう書いた区画は使える
            memory.touch(0, 15)
            StepResult.Exit(0)
        }
        assertEquals(ProcessState.EXITED, state)
        assertFalse(console.contains("kibi:"), console)
    }

    @Test
    fun `growを断られていなければ、0以外で終わっても何も表示しない`() = timeout(limit) {
        Harness().use { h ->
            val host = ConsoleRecorder()
            val m = h.machine(host = host)
            val pid = m.spawnTest(h.program(memoryModule(1)), TestDriver(ProcessKind.BLOCKING) { StepResult.Exit(3) })
            h.tickUntil("終わらない") { m.stateOf(pid).isTerminal }
            h.scheduler.tick()
            assertEquals(ProcessState.EXITED, m.stateOf(pid))
            assertFalse(host.console.contains("kibi:"), host.console.toString())
        }
    }

    @Test
    fun `ABI層はメモリの上限を設定できない`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            for (configure in listOf<(pro.ikili.kibicomputers.core.runtime.WasmInstanceConfig.Builder) -> Unit>(
                { it.memoryLimit(1, null) },
                { it.writtenMemoryLimit(1, null) },
            )) {
                val pid = m.spawnTest(
                    h.program(memoryModule(1)),
                    TestDriver(ProcessKind.BLOCKING, onConfigure = { _, b -> configure(b) }) { StepResult.Exit(0) },
                )
                h.tickUntil("終わらない") { m.stateOf(pid).isTerminal }
                assertEquals(ProcessState.CRASHED, m.stateOf(pid))
                assertInstanceOf(IllegalStateException::class.java, m.rt(pid).lastError)
            }
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
    fun `RAMの上限の表示は、MiBの倍数ならMiB、そうでなければKiB`() {
        assertEquals("128 KiB", formatBytes(128 * 1024))
        assertEquals("2 MiB", formatBytes(2 * 1024 * 1024))
        assertEquals("1088 KiB", formatBytes(1088 * 1024))
        assertEquals("100 bytes", formatBytes(100))
    }
}
