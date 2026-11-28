package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.process.FUEL_OVERDRAFT_LIMIT
import pro.ikili.kibicomputers.core.kernel.process.FUEL_OVERDRAFT_RECHECK
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.KillSignal
import pro.ikili.kibicomputers.core.kernel.process.LoadingFrame
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
import pro.ikili.kibicomputers.core.testing.bodyTimeout as timeout

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
    internal open class Counting(private val inner: WasmFuelHandler, protected val rt: ProcessRuntime) : WasmFuelHandler {
        @Volatile var calls = 0L

        /** 前借りの間（負の残りをそのまま返した）の呼び出し */
        @Volatile var overdraftCalls = 0L

        /** [fast]: 速い道を通った呼び出し（前借りの間の呼び出しで、遅い道が前借りを続けると決めた回数が増えなかった） */
        protected open fun afterCall(remaining: Long, out: Long, fast: Boolean) {}

        override fun onFuelExhausted(instance: WasmInstance, remaining: Long): Long {
            calls++
            val slowBefore = rt.fuelOverdraftSlowChecks
            val out = inner.onFuelExhausted(instance, remaining)
            val overdraft = out == remaining && remaining < 0 && rt.fuelOverdraft
            if (overdraft) overdraftCalls++
            afterCall(remaining, out, overdraft && rt.fuelOverdraftSlowChecks == slowBefore)
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
            // 1 回の前借りで、前借りを続けると決める遅い道は、始まりの 1 回と FUEL_OVERDRAFT_RECHECK ごと（上限の手前まで）の最大 8 回。
            // 上限に届いて待つ回は、続けると決めた回に数えない
            val perOverdraft = FUEL_OVERDRAFT_LIMIT / FUEL_OVERDRAFT_RECHECK
            assertTrue(slow <= overdrafts * perOverdraft, "遅い道を通りすぎる: $slow 回（前借り $overdrafts 回）")
            // 残りは速い道（以前は、前借りの間の呼び出しのすべてがカーネルロックを取っていた）
            assertTrue(overdraftCalls > slow * 10, "速い道を通っていない: 前借りの間 $overdraftCalls 回、遅い道 $slow 回")
        }
    }

    /** 速い道を通った呼び出しのところで、プロセスのスレッドを止める（ゲストに戻る前）。テストの側が [released] にするまで待つ */
    internal class Pausing(inner: WasmFuelHandler, rt: ProcessRuntime) : Counting(inner, rt) {
        @Volatile var paused = false

        @Volatile var released = false

        @Volatile var callsAtRelease = -1L

        override fun afterCall(remaining: Long, out: Long, fast: Boolean) {
            if (paused || !fast) return
            paused = true
            while (!released) Thread.onSpinWait()
            callsAtRelease = calls
        }
    }

    internal class PausedVictim(val m: Machine, val victim: Int, val other: Int, val rt: ProcessRuntime, val pausing: Pausing)

    /**
     * 2 本の葉の計算を走らせ、1 本目（victim）が前借りの速い道を通った呼び出しのところでスレッドを止めた状態で [body] を呼ぶ
     * （victim は CPU を持ったまま止まるので、other は動かない）。[body] の後、両方を kill してから victim を放し、終わりを待つ
     */
    private fun withVictimPausedOnFastPath(h: Harness, body: (PausedVictim) -> Unit): PausedVictim {
        val m = h.machine(fuelPerTick = 400_000, fuelSlice = 40_000)
        m.installSpin()
        val victim = m.spawnSpin(listOf("leaf", "1000", "200000"))
        val other = m.spawnSpin(listOf("leaf", "1000", "200000"))
        val rt = m.rt(victim)
        // 最初の step の前（読み込みで燃料切れの処理をインスタンスに渡す前）に替える
        val pausing = Pausing(rt.fuelHandler, rt)
        rt.fuelHandler = pausing
        eventually(60_000, "前借りの速い道に入らない") {
            h.scheduler.tick()
            pausing.paused
        }
        val paused = PausedVictim(m, victim, other, rt, pausing)
        try {
            body(paused)
        } finally {
            // other も止めてから放す（放した後に other が燃料待ちに入って、victim と関係なく数え口を増やすのを防ぐ）
            m.lock.withLockValue {
                m.killLocked(victim, KillSignal.KILL)
                m.killLocked(other, KillSignal.KILL)
            }
            pausing.released = true
            eventually(30_000, "kill で止まらない") {
                h.scheduler.tick()
                m.stateOf(victim).isTerminal && m.stateOf(other).isTerminal
            }
        }
        return paused
    }

    @Test
    fun `前借りの速い道の間にkillすると、次の燃料切れの処理でKILLEDになる`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            var blockingBefore = -1L
            val p = withVictimPausedOnFastPath(h) {
                // victim が CPU を持って止まっている間は other が動かない。ここから先に数え口を増やせるのは victim だけ
                // （スケジューラ全体の数え口を見るので、other の正常な燃料待ちが混ざらないようにする）
                blockingBefore = h.scheduler.fuelBlockingWaits.get()
            }
            assertEquals(ProcessState.KILLED, p.m.stateOf(p.victim))
            // 止めた後の最初の燃料切れの処理で止まる（速い道が killed を見て遅い道に回し、遅い道が投げる。
            // 先にホストの関数を呼べば、そちらの包みが止める）
            val after = p.pausing.calls - p.pausing.callsAtRelease
            assertTrue(after <= 1, "kill の後も燃料切れの処理を通り抜けた: $after 回")
            assertEquals(blockingBefore, h.scheduler.fuelBlockingWaits.get(), "kill の後にスレッドを持って燃料を待った")
        }
    }

    /** 速い道の条件のうち、どれが外れても遅い道に回る（#887）。止めた victim の状態を 1 つずつ変えて、条件の関数を直に呼ぶ */
    @Test
    fun `速い道は、条件が1つでも外れると通らない`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            withVictimPausedOnFastPath(h) { p ->
                val rt = p.rt
                val instance = checkNotNull(rt.fuelOverdraftInstance) { "前借りの控えがない" }
                val recheckAt = rt.fuelOverdraftRecheckAt
                val remaining = recheckAt + 1
                assertTrue(rt.canSkipOverdraftCheck(instance, remaining), "止めたときの状態で速い道を通らない")

                // 遅い道に入る残りに届いた
                assertFalse(rt.canSkipOverdraftCheck(instance, recheckAt), "控えの残りに届いたのに速い道を通る")
                // 前借りの最中でない（印を下ろすと控えも消える）
                rt.fuelOverdraft = false
                assertFalse(rt.canSkipOverdraftCheck(instance, remaining), "前借りの最中でないのに速い道を通る")
                assertNull(rt.fuelOverdraftInstance, "印を下ろしても控えが残っている")
                rt.fuelOverdraft = true
                // 控えと違うインスタンス（控えがないものを含む）
                assertFalse(rt.canSkipOverdraftCheck(instance, remaining), "控えがないのに速い道を通る")
                rt.fuelOverdraftInstance = instance
                assertTrue(rt.canSkipOverdraftCheck(instance, remaining))
                // kill
                rt.killed = true
                assertFalse(rt.canSkipOverdraftCheck(instance, remaining), "kill されたのに速い道を通る")
                rt.killed = false
                // 作っている途中
                rt.loading = LoadingFrame(null, null)
                assertFalse(rt.canSkipOverdraftCheck(instance, remaining), "作っている途中なのに速い道を通る")
                rt.loading = null
                // ホストの関数の中からのゲストの呼び出し
                rt.guestCallDepth = 1
                assertFalse(rt.canSkipOverdraftCheck(instance, remaining), "ホストの関数の中からのゲストの呼び出しなのに速い道を通る")
                rt.guestCallDepth = 0
                assertTrue(rt.canSkipOverdraftCheck(instance, remaining), "状態を戻しても速い道を通らない")
            }
            Unit
        }
    }

    @Test
    fun `新しい手持ちを渡すと、前借りの印と速い道の控えが消える`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            withVictimPausedOnFastPath(h) { p ->
                val rt = p.rt
                val instance = checkNotNull(rt.fuelOverdraftInstance) { "前借りの控えがない" }
                val remaining = rt.fuelOverdraftRecheckAt + 1
                assertTrue(rt.canSkipOverdraftCheck(instance, remaining))
                p.m.lock.withLockValue { p.m.runner.setHolderFuelLocked(rt, 1_000) }
                assertFalse(rt.fuelOverdraft, "新しい手持ちを渡しても前借りの印が残っている")
                assertNull(rt.fuelOverdraftInstance, "新しい手持ちを渡しても控えが残っている")
                assertFalse(rt.canSkipOverdraftCheck(instance, remaining), "新しい手持ちを渡した後に速い道を通る")
            }
            Unit
        }
    }
}
