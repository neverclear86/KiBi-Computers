package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.process.FUEL_OVERDRAFT_LIMIT
import pro.ikili.kibicomputers.core.kernel.process.FUEL_OVERDRAFT_RECHECK
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.KillSignal
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessRuntime
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.WasmFuelHandler
import pro.ikili.kibicomputers.core.runtime.WasmInstance
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * 前借り（#477）の間の燃料切れの処理の速い道（#887）。前借りを続けてよいと分かっている間は、カーネルロックを取らずに負の残りを返し、
 * 遅い道（カーネルロックの中の確かめ・その場で足すのを試す）は [FUEL_OVERDRAFT_RECHECK] ごとにだけ通ること、速い道の間でも kill をすぐ拾うことを確かめる。
 *
 * `fuel-spin-wasi`（`leaf`。葉の関数の中のループで計算し続ける）を 1 台に 2 本置く（前借りは譲る相手がいるときだけ起きる）。
 */
class OverdraftFastPathTest {
    private val limit = Duration.ofSeconds(120)

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/$name")) { "$name がリソースにない" }.use { it.readBytes() }

    /** 燃料切れの処理を数える包み（プロセスのスレッドだけが数える） */
    private open class Counting(private val inner: WasmFuelHandler, protected val rt: ProcessRuntime) : WasmFuelHandler {
        @Volatile var calls = 0L

        /** 前借りの間（負の残りをそのまま返した）の呼び出し */
        @Volatile var overdraftCalls = 0L

        protected open fun afterCall(remaining: Long, out: Long) {}

        override fun onFuelExhausted(instance: WasmInstance, remaining: Long): Long {
            calls++
            val out = inner.onFuelExhausted(instance, remaining)
            if (out == remaining && remaining < 0 && rt.fuelOverdraft) overdraftCalls++
            afterCall(remaining, out)
            return out
        }

        override fun onFuelExhaustedUnwindable(instance: WasmInstance, remaining: Long): Long {
            calls++
            return inner.onFuelExhaustedUnwindable(instance, remaining)
        }
    }

    private fun Machine.installSpin() {
        vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
        vfs.createDirectory(VfsPath.parse("/bin"))
        val bytes = resource("fuel-spin-wasi.wasm")
        val f = vfs.open(VfsPath.parse("/bin/spin"), OpenOptions(read = true, write = true, create = true, truncate = true))
        vfs.write(f, bytes, 0, bytes.size)
        vfs.close(f)
    }

    private fun Machine.spawnSpin(args: List<String>): Int {
        val stdio = StdioSpec(StdioSource.Closed, StdioSource.Closed, StdioSource.Closed)
        val pid = exec("/bin/spin", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("spin") + args, stdio = stdio))
        assertEquals(ProcessKind.WAIT_POINT, rt(pid).process.kind)
        return pid
    }

    @Test
    fun `前借りの間は、遅い道を上限までに数回だけ通り、残りの呼び出しはロックを取らずに抜ける`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            // 葉の 1 回の呼び出しは前借りの上限より大きい（200,000 回 × 十数命令）ので、前借りの多くは上限まで続く
            val m = h.machine(fuelPerTick = 400_000, fuelSlice = 40_000)
            m.installSpin()
            val pids = List(2) { m.spawnSpin(listOf("leaf", "6", "200000")) }
            val rts = pids.map { m.rt(it) }
            // 最初の step の前（読み込みで燃料切れの処理をインスタンスに渡す前）に替える
            val counters = rts.map { rt -> Counting(rt.fuelHandler, rt).also { rt.fuelHandler = it } }
            val overdrafts0 = h.scheduler.fuelOverdrafts.get()
            eventually(60_000, "終わらない") {
                h.scheduler.tick()
                pids.all { m.stateOf(it).isTerminal }
            }
            for ((i, pid) in pids.withIndex()) assertEquals(ProcessState.EXITED, m.stateOf(pid), "${rts[i].lastError}")
            val overdrafts = h.scheduler.fuelOverdrafts.get() - overdrafts0
            val slow = rts.sumOf { it.fuelOverdraftSlowChecks }
            val overdraftCalls = counters.sumOf { it.overdraftCalls }
            println("前借り $overdrafts 回、前借りの間の呼び出し $overdraftCalls 回、うち遅い道 $slow 回")
            assertTrue(overdrafts > 0, "前借りしていない")
            // 1 回の前借りで遅い道を通るのは、始まりの 1 回と、FUEL_OVERDRAFT_RECHECK ごと（上限まで）
            val perOverdraft = FUEL_OVERDRAFT_LIMIT / FUEL_OVERDRAFT_RECHECK + 1
            assertTrue(slow <= overdrafts * perOverdraft, "遅い道を通りすぎる: $slow 回（前借り $overdrafts 回）")
            // 残りは速い道（以前は、前借りの間の呼び出しのすべてがカーネルロックを取っていた）
            assertTrue(overdraftCalls > slow * 10, "速い道を通っていない: 前借りの間 $overdraftCalls 回、遅い道 $slow 回")
        }
    }

    @Test
    fun `前借りの速い道の間にkillすると、次の燃料切れの処理でKILLEDになる`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = h.machine(fuelPerTick = 400_000, fuelSlice = 40_000)
            m.installSpin()
            val victim = m.spawnSpin(listOf("leaf", "1000", "200000"))
            val other = m.spawnSpin(listOf("leaf", "1000", "200000"))
            val rt = m.rt(victim)

            // 前借りの間の呼び出しを 1 回見たら、プロセスのスレッドをそこで止め（ゲストに戻る前）、テストの側が kill し終えるのを待つ
            class Pausing(inner: WasmFuelHandler) : Counting(inner, rt) {
                @Volatile var paused = false

                @Volatile var released = false

                @Volatile var callsAtRelease = -1L

                override fun afterCall(remaining: Long, out: Long) {
                    if (paused || !(out == remaining && remaining < 0 && rt.fuelOverdraft)) return
                    // 速い道を通っている（遅い道で前借りを続けると決めた後の呼び出し）ところで止める
                    if (rt.fuelOverdraftRecheckAt >= remaining) return
                    paused = true
                    while (!released) Thread.onSpinWait()
                    callsAtRelease = calls
                }
            }
            val pausing = Pausing(rt.fuelHandler)
            rt.fuelHandler = pausing
            eventually(60_000, "前借りの速い道に入らない") {
                h.scheduler.tick()
                pausing.paused
            }
            val blocking0 = h.scheduler.fuelBlockingWaits.get()
            m.lock.withLockValue { m.killLocked(victim, KillSignal.KILL) }
            pausing.released = true
            eventually(30_000, "kill で止まらない") {
                h.scheduler.tick()
                m.stateOf(victim).isTerminal
            }
            assertEquals(ProcessState.KILLED, m.stateOf(victim))
            // 止めた後の最初の燃料切れの処理で止まる（速い道が killed を見て遅い道に回し、遅い道が投げる。
            // 先にホストの関数を呼べば、そちらの包みが止める）
            assertTrue(pausing.calls - pausing.callsAtRelease <= 1, "kill の後も燃料切れの処理を通り抜けた: ${pausing.calls - pausing.callsAtRelease} 回")
            assertEquals(blocking0, h.scheduler.fuelBlockingWaits.get(), "kill の後にスレッドを持って燃料を待った")
            m.lock.withLockValue { m.killLocked(other, KillSignal.KILL) }
            Unit
        }
    }
}
