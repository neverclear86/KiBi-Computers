package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.runtime.WasmBinary
import pro.ikili.kibicomputers.core.runtime.WasmBinary.Companion.I32
import pro.ikili.kibicomputers.core.runtime.WasmBinary.Companion.code
import pro.ikili.kibicomputers.core.runtime.WasmInstance
import pro.ikili.kibicomputers.core.runtime.WasmMemory
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.runtime.validation.ModuleLimits
import java.time.Duration
import java.util.Collections
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * カーネルがテーブルに上限をかける配線（#361、#385）。本物のプロセスで、要素数の上限が
 * [ModuleLimits.maxTableElements]（スケジューラの上限が `null` なら [ModuleLimits.DEFAULT]）になり、
 * 要素の分がプロセスと RAM に数えられることを、最初のインスタンスと [ProcessContext.instantiate] の両方で確かめる。
 */
class TableLimitWiringTest {
    private val limit = Duration.ofSeconds(60)
    private val page = WasmMemory.PAGE_SIZE.toLong()

    private fun Harness.tickUntil(message: String = "条件がそろわない", condition: () -> Boolean) = eventually(30_000, message) {
        scheduler.tick()
        Thread.sleep(1)
        condition()
    }

    /** funcref のテーブル（1 要素、上限の宣言なし）と、grow(n)・size() を export する本物の WASM（燃料の計測なし） */
    private fun tableProgram(): Program {
        val wasm = WasmBinary().run {
            table(1)
            // grow(n) = table.grow 0 (ref.null func, n)
            exportFunc("grow", func(listOf(I32), listOf(I32), code(0xd0, 0x70, 0x20, 0, 0xfc, 0x0f, 0)))
            // size() = table.size 0
            exportFunc("size", func(emptyList(), listOf(I32), code(0xfc, 0x10, 0)))
            build()
        }
        return Program.load(EndiveRuntime(), wasm)
    }

    private fun WasmInstance.grow(n: Int): Int = call("grow", n.toLong()).single().toInt()

    private fun WasmInstance.size(): Int = call("size").single().toInt()

    /** プロセスの中で見た値 */
    private class Seen {
        val results: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        val machineBytes: MutableList<Long> = Collections.synchronizedList(mutableListOf())
        val processBytes: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    }

    /** [body] を動かして終わるプロセスを作り、終わるまで回す */
    private fun Harness.runProcess(m: Machine, program: Program, body: (ProcessContext) -> Unit): Int {
        val pid = m.spawnTest(
            program,
            TestDriver(ProcessKind.BLOCKING) { ctx ->
                body(ctx)
                StepResult.Exit(0)
            },
            unmetered = true,
        )
        tickUntil("終わらない") { m.stateOf(pid).isTerminal }
        assertEquals(ProcessState.EXITED, m.stateOf(pid), "pid $pid: ${m.rt(pid).lastError}")
        return pid
    }

    private fun Seen.record(m: Machine, ctx: ProcessContext) {
        machineBytes += m.memoryAccount.usedBytes
        processBytes += m.rt(ctx.pid).memory.usedBytes
    }

    @Test
    fun `最初のインスタンスのテーブルに、読み込みの上限がかかりRAMに数えられる`() = timeout(limit) {
        Harness(moduleLimits = ModuleLimits(maxTableElements = 50)).use { h ->
            val m = h.machine(memoryBytes = 16 * page)
            val seen = Seen()
            h.runProcess(m, tableProgram()) { ctx ->
                val inst = ctx.instance
                seen.record(m, ctx)
                seen.results += inst.grow(49)
                seen.record(m, ctx)
                seen.results += inst.grow(1)
                seen.results += inst.size()
                seen.record(m, ctx)
            }
            assertEquals(listOf(1, -1, 50), seen.results.toList(), "上限 50 を超える grow は -1")
            val perElement = seen.machineBytes[0]
            assertTrue(perElement > 0, "最初の要素の分も RAM に数える")
            assertEquals(listOf(perElement, 50 * perElement, 50 * perElement), seen.machineBytes.toList())
            assertEquals(seen.machineBytes.toList(), seen.processBytes.toList(), "プロセスの勘定にも同じだけ数える")
            assertEquals(0L, m.memoryAccount.usedBytes, "終わったら返す")
        }
    }

    @Test
    fun `ProcessContext_instantiateで作るインスタンスのテーブルにも上限がかかりRAMに数えられる`() = timeout(limit) {
        Harness(moduleLimits = ModuleLimits(maxTableElements = 30)).use { h ->
            val m = h.machine(memoryBytes = 16 * page)
            val first = tableProgram()
            val second = tableProgram()
            val seen = Seen()
            h.runProcess(m, first) { ctx ->
                seen.record(m, ctx)
                val other = ctx.instantiate(second.module) { }
                seen.record(m, ctx)
                seen.results += other.grow(29)
                seen.results += other.grow(1)
                seen.results += other.size()
                seen.results += ctx.instance.size()
                seen.record(m, ctx)
            }
            assertEquals(listOf(1, -1, 30, 1), seen.results.toList(), "2つめのインスタンスも上限 30")
            val perElement = seen.machineBytes[0]
            assertEquals(listOf(perElement, 2 * perElement, 31 * perElement), seen.machineBytes.toList())
            assertEquals(seen.machineBytes.toList(), seen.processBytes.toList())
            assertEquals(0L, m.memoryAccount.usedBytes, "終わったら返す")
        }
    }

    @Test
    fun `読み込みの上限がnullなら既定の上限を使う`() = timeout(limit) {
        val max = ModuleLimits.DEFAULT.maxTableElements
        // 既定の Harness（Fake のランタイム）では、スケジューラの読み込みの上限は null
        Harness().use { h ->
            val m = h.machine(memoryBytes = 64 * page)
            val second = tableProgram()
            val seen = Seen()
            h.runProcess(m, tableProgram()) { ctx ->
                seen.results += ctx.instance.grow(max)
                seen.results += ctx.instance.grow(max - 1)
                seen.results += ctx.instance.grow(1)
                val other = ctx.instantiate(second.module) { }
                seen.results += other.grow(max)
                seen.results += other.grow(max - 1)
            }
            assertEquals(listOf(-1, 1, -1, -1, 1), seen.results.toList(), "既定の上限 $max まで")
            assertEquals(0L, m.memoryAccount.usedBytes)
        }
    }

    @Test
    fun `テーブルの要素はプロセスの上限とRAMで断られる`() = timeout(limit) {
        val max = ModuleLimits.DEFAULT.maxTableElements
        Harness().use { h ->
            // プロセスの上限は 1 ページ。要素数の上限より先に、メモリの上限で断られる
            val m = h.machine(memoryBytes = 64 * page, maxProcessMemoryBytes = page)
            val second = tableProgram()
            val seen = Seen()
            h.runProcess(m, tableProgram()) { ctx ->
                seen.results += ctx.instance.grow(max - 1)
                seen.results += ctx.instance.grow(10)
                val other = ctx.instantiate(second.module) { }
                seen.results += other.grow(max - 1)
                seen.results += other.grow(10)
            }
            assertEquals(listOf(-1, 1, -1, 1), seen.results.toList())
            assertEquals(0L, m.memoryAccount.usedBytes)
        }
        Harness().use { h ->
            // RAM が 1 ページ（プロセスの上限は大きい）
            val m = h.machine(memoryBytes = page, maxProcessMemoryBytes = 64 * page)
            val seen = Seen()
            h.runProcess(m, tableProgram()) { ctx ->
                seen.results += ctx.instance.grow(max - 1)
                seen.results += ctx.instance.grow(10)
            }
            assertEquals(listOf(-1, 1), seen.results.toList())
            assertEquals(0L, m.memoryAccount.usedBytes)
        }
    }
}
