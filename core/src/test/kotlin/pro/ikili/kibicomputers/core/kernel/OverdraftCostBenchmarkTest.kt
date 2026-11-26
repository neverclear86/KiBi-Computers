package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessRuntime
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.process.StackHeadroom
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.WasmFuelHandler
import pro.ikili.kibicomputers.core.runtime.WasmInstance
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime

/**
 * 前借り（#477）の間に燃料切れの処理が呼ばれ続ける道の費用を測る（#858。`docs/load-test.md` の「#858 の結果」）。
 * ふつうの `:core:test` では動かさない（`-Pkibi.overdraftBenchmark=true` のときだけ）。
 *
 * 前借りの間は、葉の関数の loop の先頭ごとに燃料切れの処理が呼ばれ、そのたびに入口の確かめ（[StackHeadroom.FUEL_HANDLER_FRAMES] 段）と
 * カーネルロックの区間を払う。1 回あたりの µs だけでは 1,000 台の負荷が分からないので、「1 回の前借りで何回呼ばれるか」を数える。
 *
 * 待機点型の `fuel-spin-wasi`（`leaf`。葉の関数の中のループで計算し続ける）を 1 台に 2 本置き（前借りは譲る相手がいるときだけ起きる）、
 * 燃料切れの処理を数える包みに替えて（プロセスを作った直後、最初の step の前に `ProcessRuntime.fuelHandler` を替える。本番の道には何も足さない）、
 * 次を出す:
 * - 前借りの回数・その場で足した回数・譲った回数・スレッドを持って待った回数（スケジューラの数え口。#887）と、tick の数と実時間
 * - 前借りの間（処理が負の残りを返した）の呼び出しの回数・時間
 * - 入口の確かめ 1 回（`ensure(32)`）の時間（同じ JVM で別に測る）
 */
@EnabledIfSystemProperty(named = "kibi.overdraftBenchmark", matches = "true")
class OverdraftCostBenchmarkTest {
    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/$name")) { "$name がリソースにない" }.use { it.readBytes() }

    /** 燃料切れの処理を数える包み（プロセスのスレッドだけが触るので、素の欄にする） */
    private class Counting(private val inner: WasmFuelHandler, private val rt: ProcessRuntime) : WasmFuelHandler {
        var calls = 0L
        var overdraftCalls = 0L
        var overdraftNanos = 0L
        var otherNanos = 0L

        private inline fun measure(remaining: Long, call: () -> Long): Long {
            val t0 = System.nanoTime()
            val out = call()
            val dt = System.nanoTime() - t0
            calls++
            // 負の残りをそのまま返した: 前借りの間の 1 回
            if (out == remaining && remaining < 0 && rt.fuelOverdraft) {
                overdraftCalls++
                overdraftNanos += dt
            } else {
                otherNanos += dt
            }
            return out
        }

        override fun onFuelExhausted(instance: WasmInstance, remaining: Long): Long =
            measure(remaining) { inner.onFuelExhausted(instance, remaining) }

        override fun onFuelExhaustedUnwindable(instance: WasmInstance, remaining: Long): Long =
            measure(remaining) { inner.onFuelExhaustedUnwindable(instance, remaining) }
    }

    /** `ensure(frames)` 1 回の時間（ns）。JIT が温まるまで回してから測る */
    private fun ensureNanos(frames: Int): Double {
        repeat(200_000) { StackHeadroom.ensure(frames) }
        val n = 2_000_000
        val t0 = System.nanoTime()
        repeat(n) { StackHeadroom.ensure(frames) }
        return (System.nanoTime() - t0).toDouble() / n
    }

    @Test
    fun `前借りの間の燃料切れの処理の呼び出しの回数と費用を測る`() {
        val machines = Integer.getInteger("kibi.overdraftBenchmark.machines", 4)
        val inner = Integer.getInteger("kibi.overdraftBenchmark.inner", 200_000)
        val rounds = Integer.getInteger("kibi.overdraftBenchmark.rounds", 40)
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val counters = ArrayList<Counting>()
            val pids = ArrayList<Pair<Machine, Int>>()
            repeat(machines) {
                // 1 tick の燃料を数スライスに分け、2 本で取り合う（AsyncFuelTest の「前借りを挟んでも…」と同じ形）
                val m = h.machine(fuelPerTick = 400_000, fuelSlice = 40_000)
                m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
                m.vfs.createDirectory(VfsPath.parse("/bin"))
                val bytes = resource("fuel-spin-wasi.wasm")
                val f = m.vfs.open(VfsPath.parse("/bin/spin"), OpenOptions(read = true, write = true, create = true, truncate = true))
                m.vfs.write(f, bytes, 0, bytes.size)
                m.vfs.close(f)
                repeat(2) {
                    val stdio = StdioSpec(StdioSource.Closed, StdioSource.Closed, StdioSource.Closed)
                    val pid = m.exec("/bin/spin", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("spin", "leaf", "$rounds", "$inner"), stdio = stdio))
                    val rt = m.rt(pid)
                    assertEquals(ProcessKind.WAIT_POINT, rt.process.kind)
                    // 最初の step の前（読み込みで燃料切れの処理をインスタンスに渡す前）に替える
                    val c = Counting(rt.fuelHandler, rt)
                    rt.fuelHandler = c
                    counters += c
                    pids += m to pid
                }
            }
            val overdrafts0 = h.scheduler.fuelOverdrafts.get()
            val blocking0 = h.scheduler.fuelBlockingWaits.get()
            val renewals0 = h.scheduler.fuelSliceRenewals.get()
            val yields0 = h.scheduler.fuelYields.get()
            var ticks = 0L
            val t0 = System.nanoTime()
            eventually(600_000, "終わらない") {
                h.scheduler.tick()
                ticks++
                pids.all { (m, pid) -> m.stateOf(pid).isTerminal }
            }
            val wall = System.nanoTime() - t0
            for ((m, pid) in pids) assertEquals(ProcessState.EXITED, m.stateOf(pid), "${m.rt(pid).lastError}")
            val overdrafts = h.scheduler.fuelOverdrafts.get() - overdrafts0
            val blocking = h.scheduler.fuelBlockingWaits.get() - blocking0
            val renewals = h.scheduler.fuelSliceRenewals.get() - renewals0
            val yields = h.scheduler.fuelYields.get() - yields0
            val calls = counters.sumOf { it.calls }
            val overdraftCalls = counters.sumOf { it.overdraftCalls }
            val overdraftNanos = counters.sumOf { it.overdraftNanos }
            val otherNanos = counters.sumOf { it.otherNanos }
            val ensure32 = ensureNanos(StackHeadroom.FUEL_HANDLER_FRAMES)
            val ensure128 = ensureNanos(StackHeadroom.FUEL_YIELD_FRAMES)
            val perOverdraft = overdraftCalls.toDouble() / overdrafts
            val perCall = overdraftNanos.toDouble() / overdraftCalls
            println(
                """
                #858 前借りの費用（${System.getProperty("os.name")} ${System.getProperty("os.arch")}、JDK ${System.getProperty("java.version")}、
                  ${Runtime.getRuntime().availableProcessors()} スレッド、$machines 台 x 2 本、leaf $rounds x $inner、fuelPerTick 400000、fuelSlice 40000）
                  tick: $ticks 回、実時間 ${wall / 1_000_000} ms
                  前借り: $overdrafts 回、上限に届いてスレッドを持って待った: $blocking 回
                  その場で足した: $renewals 回、燃料が残っているのに譲った: $yields 回
                  燃料切れの処理の呼び出し: $calls 回（うち前借りの間 $overdraftCalls 回、1 回の前借りあたり ${"%.0f".format(perOverdraft)} 回）
                  前借りの間の 1 回: ${"%.3f".format(perCall / 1000)} µs（計時の nanoTime 2 回を含む）、1 回の前借りあたり ${"%.1f".format(perOverdraft * perCall / 1000)} µs
                  前借りの間の合計: ${overdraftNanos / 1_000_000} ms、それ以外の呼び出し（譲る・待つ道を含む）: ${otherNanos / 1_000_000} ms
                  ensure(${StackHeadroom.FUEL_HANDLER_FRAMES}) 1 回: ${"%.3f".format(ensure32 / 1000)} µs、ensure(${StackHeadroom.FUEL_YIELD_FRAMES}) 1 回: ${"%.3f".format(ensure128 / 1000)} µs
                  前借りの間の処理に占める ensure(${StackHeadroom.FUEL_HANDLER_FRAMES}) の割合: ${"%.0f".format(100 * ensure32 / perCall)} %
                """.trimIndent(),
            )
            assertTrue(overdrafts > 0, "前借りしていない")
            assertTrue(overdraftCalls > 0)
        }
    }
}
