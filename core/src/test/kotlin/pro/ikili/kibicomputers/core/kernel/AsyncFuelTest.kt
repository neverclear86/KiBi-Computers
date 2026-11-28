package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.io.ReadResult
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.FUEL_OVERDRAFT_LIMIT
import pro.ikili.kibicomputers.core.kernel.process.FuelYieldRejectionLog
import pro.ikili.kibicomputers.core.kernel.process.KillSignal
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.vfs.ImageFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.WasmBinary
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.runtime.fuel.FuelCosts
import pro.ikili.kibicomputers.core.wasi.p1.WasiP1
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import pro.ikili.kibicomputers.core.testing.bodyTimeout as timeout

/**
 * 変換した p1 のプロセスで、step の途中の燃料切れも待ち点にする（#339）。実際の Endive と `Machine.exec` で動かす。
 *
 * `fuel-spin-wasi.wasm`（`core/src/test/wasm/fuel_spin_wasi.c`）は、止まりうる関数（main）の中か、葉の関数の中で計算を続ける CLI。
 */
class AsyncFuelTest {
    private val limit = Duration.ofSeconds(120)

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/$name")) { "$name がリソースにない" }.use { it.readBytes() }

    private fun Machine.install() = run {
        vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
        vfs.createDirectory(VfsPath.parse("/bin"))
        val bytes = resource("fuel-spin-wasi.wasm")
        val f = vfs.open(VfsPath.parse("/bin/spin"), OpenOptions(read = true, write = true, create = true, truncate = true))
        vfs.write(f, bytes, 0, bytes.size)
    }

    /** [install] 済みのマシンに、実行ファイルを足す */
    private fun Machine.put(path: String, bytes: ByteArray) {
        val f = vfs.open(VfsPath.parse(path), OpenOptions(read = true, write = true, create = true, truncate = true))
        vfs.write(f, bytes, 0, bytes.size)
    }

    private inner class Spin(val h: Harness, val m: Machine, args: List<String>, install: Boolean = true) {
        val output: Int = m.lock.withLockValue { ByteStream(64 * 1024).also { m.kernel.objects.add(it) }.id }
        val pid: Int
        private val out = StringBuilder()

        init {
            if (install) m.install()
            val stdio = StdioSpec(StdioSource.Closed, StdioSource.Object(output, HandleRights.WRITE), StdioSource.Closed)
            pid = m.exec("/bin/spin", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("spin") + args, stdio = stdio))
        }

        val rt get() = m.rt(pid)
        val process get() = m.lock.withLockValue { m.kernel.processes[pid]!! }

        fun drain(): String {
            m.lock.withLockValue {
                val s = m.kernel.objects[output] as ByteStream
                val buf = ByteArray(s.available)
                s.read(buf, 0, buf.size, m.wakeSink) as? ReadResult.Bytes
                out.append(String(buf, Charsets.UTF_8))
            }
            return out.toString()
        }

        /** スレッドを持たずに燃料を待っている（燃料切れで巻き戻した） */
        fun waitingFuelThreadless(): Boolean = m.lock.withLockValue {
            val p = m.kernel.processes[pid]!!
            p.state == ProcessState.RUNNABLE && rt.thread == null && !rt.stackActive && rt.waitSet == null && p.wait == null &&
                p.asyncStack?.isEmpty == false
        }

        fun finish(): String {
            eventually(60_000, "終わらない") {
                h.scheduler.tick()
                drain()
                m.stateOf(pid).isTerminal
            }
            assertEquals(ProcessState.EXITED, m.stateOf(pid), "${rt.lastError}")
            return drain()
        }
    }

    private fun run(asyncify: Boolean, args: List<String>, fuelPerTick: Long): String =
        Harness(execRuntime = EndiveRuntime(), asyncify = asyncify).use { h ->
            Spin(h, h.machine(fuelPerTick = fuelPerTick), args).finish()
        }

    private fun expected(rounds: Int, inner: Long): String {
        var x = 1L
        val sb = StringBuilder()
        repeat(rounds) { r ->
            repeat(inner.toInt()) { x = (x * 1103515245L + 12345L + (x ushr 7)) and 0xFFFF_FFFFL }
            sb.append("$r $x\n")
        }
        return sb.toString()
    }

    @Test
    fun `止まりうる関数の中の燃料切れは巻き戻し、燃料を待つ間スレッドを持たず待機点にある`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = h.machine(fuelPerTick = 20_000)
            val s = Spin(h, m, listOf("main", "3", "100000"))
            assertEquals(ProcessKind.WAIT_POINT, s.process.kind)
            assertTrue(s.rt.program.fuelYield, "燃料切れを待ち点にしていない: ${s.rt.program.fuelYieldRejection}")
            val before = h.scheduler.fuelUnwinds.get()
            h.scheduler.tick()
            eventually(30_000, "燃料切れで巻き戻さない") { s.waitingFuelThreadless() }
            assertTrue(h.scheduler.fuelUnwinds.get() > before)
            assertTrue(h.scheduler.dedicatedThreads.isEmpty(), "専用スレッドがある")
            assertTrue(m.isAtWaitPoint, "待機点でない")
            m.lock.withLockValue {
                assertNull(s.process.asyncWait)
                // 巻き戻しの費用は、マシンの燃料の借りになる
                assertTrue(m.kernel.fuel <= -s.process.asyncStack!!.size * FuelCosts.ASYNC_VALUE, "借りがない: ${m.kernel.fuel}")
            }
            // tick を進めると、巻き直して続きから動き、最後まで進む
            val out = s.finish()
            assertEquals(expected(3, 100_000), out)
            assertTrue(m.lock.withLockValue { s.process.asyncStack!!.isEmpty })
        }
    }

    @Test
    fun `巻き戻しの費用は、退避した値の数 x ASYNC_VALUE だけ巻き戻しの数え口に足され、使った燃料にも入る`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = h.machine(fuelPerTick = 20_000)
            val s = Spin(h, m, listOf("main", "3", "100000"))
            val unwinds0 = h.scheduler.fuelUnwinds.get()
            val cost0 = h.scheduler.fuelUnwindCost.get()
            val consumed0 = h.scheduler.fuelConsumedTotal
            h.scheduler.tick()
            eventually(30_000, "燃料切れで巻き戻さない") { s.waitingFuelThreadless() }
            // 巻き戻した後は燃料が 0 以下で、次の tick まで巻き戻さない（1 tick に 1 回）
            assertEquals(1L, h.scheduler.fuelUnwinds.get() - unwinds0)
            m.lock.withLockValue {
                val cost = h.scheduler.fuelUnwindCost.get() - cost0
                assertEquals(s.process.asyncStack!!.size.toLong() * FuelCosts.ASYNC_VALUE, cost, "巻き戻しの費用が退避した値の数と合わない")
                assertTrue(cost > 0, "費用が 0")
                // 使った燃料 = 補充した 20,000 - 残り。残りは費用の分だけ借りになるので、費用も使ったに入る
                assertEquals(20_000L - m.kernel.fuel, h.scheduler.fuelConsumedTotal - consumed0)
                assertTrue(h.scheduler.fuelConsumedTotal - consumed0 >= cost, "使った燃料に費用が入っていない")
            }
            assertEquals(h.scheduler.fuelUnwindCost.get(), h.scheduler.fuelUnwindCostTotal)
            assertEquals(expected(3, 100_000), s.finish())
        }
    }

    @Test
    fun `スライスを使い切ると、計算し続ける2つのプロセスは巻き戻してスレッドを持たずに譲り合う`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            // スライスは 1 tick の 1/10。2つが同じ tick の中で交代する（#47）
            val m = h.machine(fuelPerTick = 200_000, fuelSlice = 20_000)
            val yields0 = h.scheduler.fuelYields.get()
            val blocking0 = h.scheduler.fuelBlockingWaits.get()
            val a = Spin(h, m, listOf("main", "4", "100000"))
            val b = Spin(h, m, listOf("main", "4", "100000"), install = false)
            eventually(60_000, "終わらない") {
                h.scheduler.tick()
                a.drain()
                b.drain()
                m.stateOf(a.pid).isTerminal && m.stateOf(b.pid).isTerminal
            }
            assertEquals(expected(4, 100_000), a.drain())
            assertEquals(expected(4, 100_000), b.drain())
            assertTrue(h.scheduler.fuelYields.get() - yields0 > 0, "譲っていない")
            assertEquals(blocking0, h.scheduler.fuelBlockingWaits.get(), "スレッドを持ったまま待った")
            assertTrue(h.scheduler.dedicatedThreads.isEmpty(), "専用スレッドがある")
        }
    }

    @Test
    fun `プロセスが1つだけなら、同じtickの中ではスライスを使い切っても巻き戻さずにその場で足す`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            // 1 tick の燃料は、最後まで計算してもまだ余るほど大きい（tick は最初の 1 回だけ進める）
            val m = h.machine(fuelPerTick = 100_000_000, fuelSlice = 20_000)
            val yields0 = h.scheduler.fuelYields.get()
            val renewals0 = h.scheduler.fuelSliceRenewals.get()
            val unwinds0 = h.scheduler.fuelUnwinds.get()
            val s = Spin(h, m, listOf("main", "3", "100000"))
            h.scheduler.tick()
            eventually(60_000, "終わらない") { s.drain(); m.stateOf(s.pid).isTerminal }
            assertEquals(ProcessState.EXITED, m.stateOf(s.pid), "${s.rt.lastError}")
            assertEquals(expected(3, 100_000), s.drain())
            assertTrue(h.scheduler.fuelSliceRenewals.get() - renewals0 > 10, "その場で足した回数: ${h.scheduler.fuelSliceRenewals.get() - renewals0}")
            assertEquals(unwinds0, h.scheduler.fuelUnwinds.get(), "巻き戻した")
            assertEquals(yields0, h.scheduler.fuelYields.get(), "譲る相手がいないのに譲った")
        }
    }

    @Test
    fun `プロセスが1tickのスライスの数より多くても、tickをまたいで全員が同じ回数ずつ動く`() = timeout(limit) {
        // 1 tick に 4 スライス。計算し続けるプロセスが 5 本（スライスの数より多い）
        assertRoundRobin(processes = 5, slicesPerTick = 4, ticks = 15)
    }

    @Test
    fun `プロセスの数が1tickのスライスの数-1を割り切っても、tickをまたいで全員が同じ回数ずつ動く`() = timeout(limit) {
        // 3 本・4 スライス（既定の設定）。最後のスライスを使い切った側が燃料待ちの先頭になると、毎 tick 同じ順で 2:1:1 に偏る
        assertRoundRobin(processes = 3, slicesPerTick = 4, ticks = 15)
    }

    @Test
    fun `巻き戻した側のスレッドが外れるのが遅くても、ほかの側はスライスをその場で足さずに譲る`() = timeout(limit) {
        // 巻き戻してから step のスレッドが外れるまでの間、その側は燃料待ちの列に残る（起こすのはスレッドが外れてから。#956）。
        // その間にスライスを使い切った側が、待つ者がいないと見てその場で足すと、巻き戻しが 1 回減ってスライスの数がずれた
        // （負荷の高い CI でまれに落ちていた）。スレッドが外れるのを遅らせて、その間を広げる。
        // #956 の前は、起こされた側が外れた順に CPU の待ち行列へ入り、順番がスレッドの外れ方でぶれた（ここでは 2:1:1 になった）。
        // 今は列の順に入るので、配分もそろう
        assertRoundRobin(processes = 3, slicesPerTick = 4, ticks = 6) { h ->
            // 止めるときの割り込みで投げないよう、sleep でなく時間まで回して待つ（投げると登録が外れずに残る）
            h.scheduler.hooks.beforeUnregister = { _, _ ->
                val until = System.nanoTime() + 20_000_000L
                while (System.nanoTime() < until) Thread.onSpinWait()
            }
        }
    }

    /**
     * 燃料がないと分かって燃料待ちに入った側のスレッドが外れる前に、次の tick が来ても（#956）、起こす順は燃料待ちの列の順のまま。
     * 燃料が尽きているときだけ、スレッドが外れるのを遅らせて、tick の境目のその間を広げる（CI の負荷で開いていた窓を、毎 tick 開く）
     */
    private fun delayUnregisterWithoutFuel(h: Harness) {
        h.scheduler.hooks.beforeUnregister = { m, _ ->
            if (m.lock.withLockValue { m.kernel.fuel <= 0 }) {
                // 止めるときの割り込みで投げないよう、sleep でなく時間まで回して待つ
                val until = System.nanoTime() + 5_000_000L
                while (System.nanoTime() < until) Thread.onSpinWait()
            }
        }
    }

    @Test
    fun `tickの境目で燃料待ちのスレッドが外れていなくても、5本・4スライスで全員が同じ回数ずつ動く`() = timeout(limit) {
        assertRoundRobin(processes = 5, slicesPerTick = 4, ticks = 15, setUp = ::delayUnregisterWithoutFuel)
    }

    @Test
    fun `tickの境目で燃料待ちのスレッドが外れていなくても、3本・4スライスで全員が同じ回数ずつ動く`() = timeout(limit) {
        assertRoundRobin(processes = 3, slicesPerTick = 4, ticks = 15, setUp = ::delayUnregisterWithoutFuel)
    }

    @Test
    fun `起こすのを止めた待ち手がスレッドの外れる前に止められても、後ろの待ち手はそのtickのうちに起こされる`() = timeout(limit) {
        // 燃料待ちを起こすのを止めた待ち手（#956）が、step のスレッドが外れる前に kill されたとき。スレッドが外れるとき
        // （ProcessRunner.unregister）は、止められたプロセスを終えた後に続きを起こす（終わった待ち手は列から外して飛ばす）。
        // ここが壊れると、後ろの待ち手は次の tick まで起こされない（tick は手で進めるので、進めない限り起こされない）
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val slice = 20_000L
            val m = h.machine(fuelPerTick = slice * 4, fuelSlice = slice)
            val runs = ConcurrentHashMap<Int, AtomicInteger>()
            h.scheduler.hooks.beforeFuelUnwound = { _, pid -> runs.computeIfAbsent(pid) { AtomicInteger() }.incrementAndGet() }
            val spins = List(3) { Spin(h, m, listOf("main", "1000000", "100"), install = it == 0) }
            eventually(30_000, "3 本とも燃料待ちにならない") { m.fuelWaiterCount == 3 }
            // 燃料が尽きてから最初にスレッドを外そうとした 1 本を、放すまでスレッドを付けたまま止める
            // （止めるときの割り込みで投げないよう、sleep でなく回して待つ）
            val armed = AtomicBoolean(false)
            val release = AtomicBoolean(false)
            val held = AtomicInteger(0)
            val afterKill = AtomicReference<Pair<Int, Int?>>()
            h.scheduler.hooks.beforeUnregister = { mm, pid ->
                if (armed.get() && mm.lock.withLockValue { mm.kernel.fuel <= 0 } && held.compareAndSet(0, pid)) {
                    while (!release.get()) Thread.onSpinWait()
                }
            }
            h.scheduler.hooks.afterUnregister = { mm, pid ->
                // 止めた側のスレッドが外れた直後の列（ディスパッチャーが割り込む前に読む）
                if (release.get() && pid == held.get()) {
                    afterKill.compareAndSet(null, mm.lock.withLockValue { mm.fuelWaiterCount to mm.fuelWakeBlockedPid })
                }
            }
            try {
                armed.set(true)
                // tick 1: 燃料を使い切ったところで 1 本がスレッドを付けたまま止まる
                h.scheduler.tick()
                awaitTickUsedUp(h, m, 1, 3)
                val victim = held.get()
                assertTrue(victim != 0, "スレッドを付けたまま止まった待ち手がいない")
                fun total() = spins.sumOf { runs[it.pid]?.get() ?: 0 }
                // 次の tick で、止まった 1 本のところで起こすのが止まる。前にいた待ち手は起こされて 1 スライス動き、譲って止まった 1 本の
                // 後ろに並ぶ（止まった 1 本が列にいるので、待つ者がいると見て譲る）。全員が列に戻り、止まった 1 本の後ろの 2 本が
                // まだその tick の燃料を残していれば、その形で止める。燃料が残っていなければ、次の tick（止まった 1 本が先頭）で同じことをする
                var k = 1
                while (true) {
                    k++
                    assertTrue(k <= 4, "止まった待ち手の後ろに、燃料の残った待ち手が並ばない")
                    val blocks0 = m.fuelWakeBlockCount
                    h.scheduler.tick()
                    eventually(30_000, "tick $k: 止まった待ち手で起こすのが止まらない") { m.fuelWakeBlockCount > blocks0 }
                    eventually(30_000, "tick $k: 止まった待ち手の後ろに全員が並ばない") {
                        m.lock.withLockValue { m.fuelWaiterCount == 3 && m.fuelWakeBlockedPid == victim }
                    }
                    if (m.lock.withLockValue { m.fuelAccount.estimate(h.scheduler.now) > 0 }) break
                }
                assertTrue(m.lock.withLockValue { m.rt(victim).thread != null }, "止まった待ち手のスレッドが外れている")
                val before = total()
                // スレッドが外れる前に止め、それから放す
                m.lock.withLockValue { m.killLocked(victim, KillSignal.KILL) }
                release.set(true)
                eventually(30_000, "止めた待ち手が終わらない") { m.stateOf(victim).isTerminal }
                assertEquals(ProcessState.KILLED, m.stateOf(victim))
                // tick を進めずに、後ろの 2 本がこの tick の残りの燃料を使い切る
                awaitTickUsedUp(h, m, k, 2)
                assertTrue(total() > before, "tick $k の残りの燃料で、後ろの待ち手が動いていない")
                // 起こしていなければ、止めた待ち手（終わったもの）と後ろの 2 本が列に残り、止めている待ち手もそのまま
                assertNotNull(afterKill.get(), "止めた待ち手のスレッドが外れたところを見ていない")
                val (waiting, blocked) = afterKill.get()
                assertNull(blocked, "スレッドが外れたときに、止めている待ち手を外していない")
                assertTrue(waiting < 3, "スレッドが外れたときに、後ろの待ち手を起こしていない（燃料待ち $waiting）")
            } finally {
                // 落ちたときも、止めたスレッドを放す（放さないと、ほかのテストにスレッドが残る）
                release.set(true)
            }
            h.scheduler.hooks.beforeUnregister = null
            h.scheduler.hooks.afterUnregister = null
            for (s in spins) m.lock.withLockValue { m.killLocked(s.pid, KillSignal.KILL) }
        }
    }

    @Test
    fun `スライスがfuelPerTickと同じでも、計算し続ける2本が交互に動く`() = timeout(limit) {
        // 1 tick に 1 スライス。使い切った側が毎 tick 先頭で起こされると、もう 1 本が動けない
        assertRoundRobin(processes = 2, slicesPerTick = 1, ticks = 10)
    }

    /**
     * 計算し続ける待機点型のプロセスを [processes] 本動かし、[ticks] tick の間に動いたスライスの数がそろうかを確かめる（#47）。
     * 燃料待ちは入った順に起こすが、スライスを使い切って巻き戻した側は CPU の待ち行列にいたプロセスの後ろに回るので、tick をまたいで順番が回る。
     * スライスを使い切って巻き戻す回数が、そのプロセスが動いたスライスの数
     */
    private fun assertRoundRobin(processes: Int, slicesPerTick: Int, ticks: Int, fair: Boolean = true, setUp: (Harness) -> Unit = {}) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            setUp(h)
            val slice = 20_000L
            val m = h.machine(fuelPerTick = slice * slicesPerTick, fuelSlice = slice)
            val runs = ConcurrentHashMap<Int, AtomicInteger>()
            h.scheduler.hooks.beforeFuelUnwound = { _, pid -> runs.computeIfAbsent(pid) { AtomicInteger() }.incrementAndGet() }
            val spins = List(processes) { Spin(h, m, listOf("main", "1000000", "100"), install = it == 0) }
            // 全員が、最初の燃料を待つところまで来てから始める（来る前に tick を進めると、起こす数がそろわない）
            eventually(30_000, "$processes 本とも燃料待ちにならない") { m.fuelWaiterCount == processes }
            val grants0 = m.fuelGrantsByProcess()
            for (k in 1..ticks) {
                h.scheduler.tick()
                // その tick の燃料を補充して使い切り、全員が燃料待ちに戻るまで待つ
                awaitTickUsedUp(h, m, k, processes)
            }
            val counts = spins.map { runs[it.pid]?.get() ?: 0 }
            val total = ticks * slicesPerTick
            // 全部のスライスを、全員で同じ回数ずつ。tick の頭の入り方で 1 回ぶれうる
            assertEquals(total, counts.sum(), "スライスの数: $counts")
            if (fair) {
                val each = total / processes
                assertTrue(counts.all { it in each - 1..each + 1 }, "全員が同じ回数動いていない: $counts")
            }
            // プロセスごとのスライスの数え口（接続層の負荷試験が読む。#740）は、動いたスライスの数と同じ（全員が燃料待ちの間に読む）
            val grants = m.fuelGrantsByProcess()
            assertEquals(counts, spins.map { (grants[it.pid] ?: 0L) - (grants0[it.pid] ?: 0L) }.map { it.toInt() }, "プロセスごとのスライスの数え口")
            // setUp で足した遅らせる口は、止める前に外す（止める間まで遅らせない）
            h.scheduler.hooks.beforeUnregister = null
            for (s in spins) m.lock.withLockValue { m.killLocked(s.pid, KillSignal.KILL) }
        }
    }

    @Test
    fun `計算し続ける待機点型2本と、スレッドを持って待つ型1本が混ざっても、取り分は偏らない`() = timeout(limit) {
        // 1 tick に 4 スライス。燃料待ちの全員を起こすとき、スレッドを持たない側（巻き戻して待つ側）はカーネルロックの中でその順に
        // CPU の待ち行列に並び、スレッドを持って待つ側も同じ順で CPU の位置を予約する（#737、#742、ADR 0047）。
        // 予約がないと、スレッドを持つ側は起きてから CPU に着くので、毎 tick スレッドを持たない側の後ろになり、取り分が 25% に偏っていた
        val ticks = 12
        val (counts, perTick) = mixed(waitPoints = 2, slicesPerTick = 4, ticks = ticks)
        val blockingSlices = perTick.sum()
        // 飢えない: 毎 tick 少なくとも 1 スライスは動く（3 本で 4 スライスを回すので、どの tick も 1 か 2）
        assertTrue(perTick.all { it >= 1 }, "スレッドを持って待つ側が動かなかった tick がある: $perTick（巻き戻す側 $counts）")
        assertTrue(counts.all { it >= ticks }, "巻き戻す側が飢えた: $counts（スレッドを持つ側 $perTick）")
        // 偏らない: 3 本とも 4/3 スライスずつ（12 tick で 16）に近い。tick の頭の入り方のぶれを見込む
        val all = counts + blockingSlices
        assertTrue(all.all { it in 13..19 }, "取り分が偏っている: 巻き戻す側 $counts、スレッドを持つ側 $blockingSlices（$perTick）")
    }

    @Test
    fun `tickの境目で燃料待ちのスレッドが外れていなくても、待機点型2本とスレッドを持って待つ型1本の取り分は偏らない`() = timeout(limit) {
        // 起こすのがスレッドを持たない待ち手で止まり（#956）、スレッドが外れたワーカーが続きを起こすとき、後ろにいるスレッドを持つ待ち手も
        // 列の順に CPU の位置を予約する（#742、#737）。燃料が尽きたときだけスレッドが外れるのを遅らせて、この道を毎 tick 通す
        val ticks = 12
        val (counts, perTick) = mixed(waitPoints = 2, slicesPerTick = 4, ticks = ticks, setUp = ::delayUnregisterWithoutFuel)
        val blockingSlices = perTick.sum()
        assertTrue(perTick.all { it >= 1 }, "スレッドを持って待つ側が動かなかった tick がある: $perTick（巻き戻す側 $counts）")
        assertTrue(counts.all { it >= ticks }, "巻き戻す側が飢えた: $counts（スレッドを持つ側 $perTick）")
        val all = counts + blockingSlices
        assertTrue(all.all { it in 13..19 }, "取り分が偏っている: 巻き戻す側 $counts、スレッドを持つ側 $blockingSlices（$perTick）")
    }

    @Test
    fun `スレッドを持たない待ち手が1tickのスライスの数以上いても、スレッドを持って待つ側は飢えない`() = timeout(limit) {
        // 4 スライスで巻き戻す側 4 本 + スレッドを持つ 1 本。予約がないと、巻き戻す側が先に 4 スライスを使い切り、スレッドを持つ側は
        // 毎 tick 燃料のない CPU しか取れなかった（#737）
        val ticks = 10
        val (counts, perTick) = mixed(waitPoints = 4, slicesPerTick = 4, ticks = ticks)
        // 5 本で 4 スライスを回すので、5 tick で 4 回ずつ。どこかで動いていれば飢えていない。
        assertTrue(perTick.sum() >= ticks * 4 / 5 - 2, "スレッドを持って待つ側が飢えた: $perTick（巻き戻す側 $counts）")
        assertTrue(counts.all { it >= ticks * 4 / 5 - 2 }, "巻き戻す側が飢えた: $counts（スレッドを持つ側 $perTick）")
    }

    @Test
    fun `スライスがfuelPerTickと同じ台で、待機点型1本とスレッドを持って待つ型1本が交互に動く`() = timeout(limit) {
        val ticks = 10
        val (counts, perTick) = mixed(waitPoints = 1, slicesPerTick = 1, ticks = ticks)
        assertTrue(perTick.sum() >= ticks / 2 - 1, "スレッドを持って待つ側が飢えた: $perTick（巻き戻す側 $counts）")
        assertTrue(counts.all { it >= ticks / 2 - 1 }, "巻き戻す側が飢えた: $counts（スレッドを持つ側 $perTick）")
    }

    /**
     * tick を進めた後、その tick の燃料を補充して使い切り、[processes] 本の全員が燃料待ちに戻るまで待つ。
     *
     * 補充したのがこの tick か（[FuelAccount.lastRefillTick]）で見るのは、進めた直後は補充の前（遅延補充）で、前の tick の終わりと同じ
     * 「全員が燃料待ちで燃料が 0 以下」に見えるため。ディスパッチャーが起こした数では見ない: 燃料待ちに入った step のスレッドが外れる前に
     * tick が来ると、起こすのはスレッドが外れるまで止まり（#956）、その間にほかの側がその tick の燃料を使い切れば、止まった側から後ろは
     * その tick には起こされない（列の前に残り、次の tick に先に起こされる）
     */
    private fun awaitTickUsedUp(h: Harness, m: Machine, k: Int, processes: Int) {
        eventuallyDescribe(30_000, {
            m.lock.withLockValue {
                "tick $k: 燃料を使い切らない（燃料待ち ${m.fuelWaiterCount}、燃料 ${m.kernel.fuel}、最後に補充した tick ${m.fuelAccount.lastRefillTick}、" +
                    "今の tick ${h.scheduler.now}、CPU の待ち ${m.cpu.waiting}）"
            }
        }) {
            // 3 つの条件は 1 回のカーネルロックの中で読む（別々に読むと、間に状態が進む。止めた待ち手のスレッドが外れるまでの間は、
            // 「全員が列にいて、その tick に補充済みで、燃料がまだ残っている」状態が tick の途中にできる）
            m.lock.withLockValue {
                m.fuelWaiterCount == processes && m.fuelAccount.lastRefillTick == h.scheduler.now && m.kernel.fuel <= 0
            }
        }
    }

    /**
     * 計算し続ける待機点型（巻き戻して待つ）[waitPoints] 本と、燃料切れを待ち点にできずスレッドを持って待つ 1 本を、1 tick に
     * [slicesPerTick] スライスの台で [ticks] tick 動かす。巻き戻す側それぞれが動いたスライスの数と、スレッドを持つ側が tick ごとに動いた
     * スライスの数（全部のスライス - 巻き戻す側の数）を返す
     */
    private fun mixed(
        waitPoints: Int,
        slicesPerTick: Int,
        ticks: Int,
        setUp: ((Harness) -> Unit)? = null,
    ): Pair<List<Int>, List<Int>> {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            setUp?.invoke(h)
            val slice = 20_000L
            val m = h.machine(fuelPerTick = slice * slicesPerTick, fuelSlice = slice)
            val runs = ConcurrentHashMap<Int, AtomicInteger>()
            h.scheduler.hooks.beforeFuelUnwound = { _, pid -> runs.computeIfAbsent(pid) { AtomicInteger() }.incrementAndGet() }
            val spins = List(waitPoints) { Spin(h, m, listOf("main", "1000000", "100"), install = it == 0) }
            m.put("/bin/fallback", fallbackModule(100_000_000))
            val stdio = StdioSpec(StdioSource.Closed, StdioSource.Closed, StdioSource.Closed)
            val blocking = m.exec("/bin/fallback", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("fallback"), stdio = stdio))
            assertFalse(m.rt(blocking).program.fuelYield, "燃料切れを待ち点にできるモジュールになっている")
            val processes = waitPoints + 1
            eventually(30_000, "$processes 本とも燃料待ちにならない") { m.fuelWaiterCount == processes }
            val perTick = ArrayList<Int>()
            var prevW = 0
            for (k in 1..ticks) {
                h.scheduler.tick()
                awaitTickUsedUp(h, m, k, processes)
                val w = spins.sumOf { runs[it.pid]?.get() ?: 0 }
                perTick += slicesPerTick - (w - prevW)
                prevW = w
            }
            val counts = spins.map { runs[it.pid]?.get() ?: 0 }
            assertEquals(ticks * slicesPerTick - counts.sum(), perTick.sum())
            // 遅らせる口を入れたときは、tick の境目で起こすのが待ち手で止まり、スレッドが外れてから続きを起こす道（#956）を通った
            if (setUp != null) assertTrue(m.fuelWakeBlockCount > 0, "起こすのが待ち手で止まらなかった")
            // setUp で足した遅らせる口は、止める前に外す
            h.scheduler.hooks.beforeUnregister = null
            for (pid in spins.map { it.pid } + blocking) m.lock.withLockValue { m.killLocked(pid, KillSignal.KILL) }
            return counts to perTick
        }
    }

    @Test
    fun `前借りを挟んでも、葉の関数で計算し続ける2つのプロセスは同じtickの中で交代して最後まで進む`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            // 葉の関数の中のスライス切れは、待つ者がいれば前借りして先へ進み、止まりうる関数（main）に戻ったところで巻き戻して譲る（#477、#47）
            val m = h.machine(fuelPerTick = 400_000, fuelSlice = 40_000)
            val overdrafts0 = h.scheduler.fuelOverdrafts.get()
            val yields0 = h.scheduler.fuelYields.get()
            val a = Spin(h, m, listOf("leaf", "6", "30000"))
            val b = Spin(h, m, listOf("leaf", "3", "30000"), install = false)
            eventually(60_000, "終わらない") {
                h.scheduler.tick()
                a.drain()
                b.drain()
                m.stateOf(a.pid).isTerminal && m.stateOf(b.pid).isTerminal
            }
            assertEquals(ProcessState.EXITED, m.stateOf(a.pid), "${a.rt.lastError}")
            assertEquals(ProcessState.EXITED, m.stateOf(b.pid), "${b.rt.lastError}")
            assertEquals(expected(6, 30_000), a.drain())
            assertEquals(expected(3, 30_000), b.drain())
            assertTrue(h.scheduler.fuelOverdrafts.get() > overdrafts0, "前借りしていない")
            assertTrue(h.scheduler.fuelYields.get() > yields0, "譲っていない")
            assertTrue(h.scheduler.dedicatedThreads.isEmpty(), "専用スレッドがある")
        }
    }

    @Test
    fun `計算し続ける待機点型の2本がいても、イベントで起きた待機点型のプロセスは同じtickの中で動く`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            // 1 tick の燃料は、起こされたプロセスが動くまでに使い切られないほど大きい（tick は最初の 1 回だけ進める）。
            // 「前借り → 巻き戻して譲る」道を通る葉の関数で計算し続ける 2 本と、標準 OS のシェルと同じ「入力を待つ待機点型」
            // （スレッドを持たず、イベントで CPU の待ち行列に並ぶ側）を同じ台に置く（#47）
            val m = h.machine(fuelPerTick = 1_000_000_000, fuelSlice = 40_000)
            val a = Spin(h, m, listOf("leaf", "1000000", "30000"))
            val b = Spin(h, m, listOf("leaf", "1000000", "30000"), install = false)
            m.put("/bin/prompt", resource("prompt-wasi.wasm"))
            val ids = m.lock.withLockValue {
                ByteStream(4096).also { m.kernel.objects.add(it) }.id to ByteStream(4096).also { m.kernel.objects.add(it) }.id
            }
            val stdio = StdioSpec(StdioSource.Object(ids.first, HandleRights.READ), StdioSource.Object(ids.second, HandleRights.WRITE), StdioSource.Closed)
            val prompt = m.exec("/bin/prompt", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("prompt"), stdio = stdio))
            val promptOut = StringBuilder()
            fun promptOutput(): String {
                m.lock.withLockValue {
                    val s = m.kernel.objects[ids.second] as ByteStream
                    val buf = ByteArray(s.available)
                    s.read(buf, 0, buf.size, m.wakeSink)
                    promptOut.append(String(buf, Charsets.UTF_8))
                }
                return promptOut.toString()
            }
            // 3 本とも動き始め、プロンプトが入力を待つところ（イベント待ち）まで進める。この後 tick は進めない
            h.scheduler.tick()
            val tick = h.scheduler.now
            eventually(60_000, "プロンプトで待たない: ${promptOutput()}") {
                promptOutput().endsWith("> ") && m.stateOf(prompt) == ProcessState.WAITING_EVENT
            }
            // 計算し続ける 2 本が、同じ tick の中で、スライスを使い切るたびに巻き戻して譲り合っている
            val yields0 = h.scheduler.fuelYields.get()
            val unwinds0 = h.scheduler.fuelUnwinds.get()
            eventually(60_000, "計算し続ける 2 本が譲り合わない") { h.scheduler.fuelYields.get() - yields0 >= 4 }
            assertEquals(unwinds0, h.scheduler.fuelUnwinds.get(), "tick の燃料を使い切った")
            m.lock.withLockValue { (m.kernel.objects[ids.first] as ByteStream).write("ping\n".toByteArray(), 0, 5, m.wakeSink) }
            // 入力が着いたら、tick を進めずに（燃料を待たずに）CPU の待ち行列に並んで、1 行を返す
            eventually(60_000, "同じ tick の中で動かない: ${promptOutput()}") { promptOutput().contains("1 ping ") }
            assertEquals(tick, h.scheduler.now, "tick が進んだ")
            assertEquals(unwinds0, h.scheduler.fuelUnwinds.get(), "tick の燃料を使い切ってから動いた")
            assertTrue(m.lock.withLockValue { m.kernel.fuel } > 0, "1 tick の燃料を使い切った")
            assertTrue(h.scheduler.dedicatedThreads.isEmpty(), "専用スレッドがある")
            for (pid in listOf(a.pid, b.pid, prompt)) m.lock.withLockValue { m.killLocked(pid, KillSignal.KILL) }
        }
    }

    @Test
    fun `出力が、変換せずブロッキングで動かしたときと一致する`() = timeout(limit) {
        for (mode in listOf("main", "leaf")) {
            val args = listOf(mode, "4", "30000")
            val blocking = run(asyncify = false, args, fuelPerTick = 7_000)
            val async = run(asyncify = true, args, fuelPerTick = 7_000)
            assertEquals(expected(4, 30_000), blocking, mode)
            assertEquals(blocking, async, mode)
        }
    }

    @Test
    fun `葉の関数の中の燃料切れは前借りで先へ進み、止まりうる関数に戻ったところで巻き戻す`() = timeout(limit) {
        // 葉の1回の呼び出しは前借りの上限より小さい（500 回 × 十数命令）
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = h.machine(fuelPerTick = 7_000)
            val unwinds0 = h.scheduler.fuelUnwinds.get()
            val blocking0 = h.scheduler.fuelBlockingWaits.get()
            val overdrafts0 = h.scheduler.fuelOverdrafts.get()
            val s = Spin(h, m, listOf("leaf", "300", "500"))
            assertEquals(expected(300, 500), s.finish())
            val overdrafts = h.scheduler.fuelOverdrafts.get() - overdrafts0
            assertTrue(overdrafts > 0, "前借りしていない")
            assertTrue(h.scheduler.fuelUnwinds.get() - unwinds0 >= overdrafts, "前借りの後に巻き戻していない")
            assertEquals(blocking0, h.scheduler.fuelBlockingWaits.get(), "スレッドを持ったまま燃料を待った")
            // 前借りした分は借りになり、後の補充で返す（使った燃料が配った燃料を大きく超えない）
            m.lock.withLockValue { assertTrue(m.kernel.fuel > -FUEL_OVERDRAFT_LIMIT * 2, "借りが大きすぎる: ${m.kernel.fuel}") }
        }
    }

    @Test
    fun `標準OSのシェルの計算と出力の燃料切れは、スレッドを持たずに待つ`() = timeout(limit) {
        val image = requireNotNull(ImageFileSystem.bundled("os")) { "os.zip がリソースにない" }
        val stat = image.stat(VfsPath.parse("/bin/sh"))
        val sh = ByteArray(stat.size.toInt()).also { image.read(stat.node, 0, it, 0, it.size) }
        val scripts = listOf(
            "i=0; while [ \$i -lt 2000 ]; do :; i=\$((i+1)); done",
            "i=0; while [ \$i -lt 200 ]; do i=\$((i+1)); echo \$i; done",
        )
        for (script in scripts) {
            Harness(execRuntime = EndiveRuntime()).use { h ->
                val m = h.machine(fuelPerTick = 50_000, memoryBytes = 16L * 1024 * 1024)
                m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
                m.vfs.createDirectory(VfsPath.parse("/bin"))
                val f = m.vfs.open(VfsPath.parse("/bin/sh"), OpenOptions(read = true, write = true, create = true, truncate = true))
                m.vfs.write(f, sh, 0, sh.size)
                val output = m.lock.withLockValue { ByteStream(64 * 1024).also { m.kernel.objects.add(it) }.id }
                val stdio = StdioSpec(StdioSource.Closed, StdioSource.Object(output, HandleRights.WRITE), StdioSource.Closed)
                val pid = m.exec("/bin/sh", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("sh", "-c", script), stdio = stdio))
                assertTrue(m.rt(pid).program.fuelYield, "${m.rt(pid).program.fuelYieldRejection}")
                val unwinds0 = h.scheduler.fuelUnwinds.get()
                val blocking0 = h.scheduler.fuelBlockingWaits.get()
                eventually(60_000, "終わらない: $script") {
                    h.scheduler.tick()
                    m.stateOf(pid).isTerminal
                }
                assertEquals(ProcessState.EXITED, m.stateOf(pid), "${m.rt(pid).lastError}")
                val unwinds = h.scheduler.fuelUnwinds.get() - unwinds0
                val blocking = h.scheduler.fuelBlockingWaits.get() - blocking0
                println("シェルの燃料切れ（$script）: 巻き戻し $unwinds 回、スレッドを持って待った $blocking 回")
                assertTrue(unwinds > 0, script)
                assertEquals(0, blocking, script)
            }
        }
    }

    @Test
    fun `葉の関数の中で前借りの上限を超えて計算し続けたら、スレッドを持ったまま待つ`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = h.machine(fuelPerTick = 20_000)
            val before = h.scheduler.fuelBlockingWaits.get()
            val s = Spin(h, m, listOf("leaf", "2", "100000"))
            h.scheduler.tick()
            eventually(30_000, "燃料切れで待たない") { m.stateOf(s.pid) == ProcessState.WAITING_FUEL }
            assertTrue(h.scheduler.fuelBlockingWaits.get() > before)
            assertFalse(m.isAtWaitPoint)
            assertEquals(expected(2, 100_000), s.finish())
        }
    }

    @Test
    fun `1回の燃料が巻き直しの費用より小さくても先へ進む`() = timeout(limit) {
        // 巻き直しの間は燃料を引かないので、grant が小さくても、止まった呼び出しまで着いてから先へ進む
        assertEquals(expected(2, 3_000), run(asyncify = true, listOf("main", "2", "3000"), fuelPerTick = 300))
    }

    @Test
    fun `燃料を待っている間にkillすると、その場でKILLEDになる`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = h.machine(fuelPerTick = 20_000)
            val s = Spin(h, m, listOf("main", "1000", "100000"))
            h.scheduler.tick()
            eventually(30_000, "燃料切れで巻き戻さない") { s.waitingFuelThreadless() }
            m.lock.withLockValue { m.killLocked(s.pid, KillSignal.KILL) }
            assertEquals(ProcessState.KILLED, m.stateOf(s.pid))
            assertTrue(m.lock.withLockValue { s.process.asyncStack!!.isEmpty })
        }
    }

    @Test
    fun `燃料を待っている間の一時停止はすぐ終わり、解いた後も続きから動く`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = h.machine(fuelPerTick = 20_000)
            val s = Spin(h, m, listOf("main", "2", "50000"))
            h.scheduler.tick()
            eventually(30_000, "燃料切れで巻き戻さない") { s.waitingFuelThreadless() }
            h.scheduler.pauseAll(5_000).use {
                assertTrue(m.isAtWaitPoint)
                repeat(5) { h.scheduler.tick() }
                assertTrue(s.waitingFuelThreadless(), "一時停止の間に動いた")
            }
            assertEquals(expected(2, 50_000), s.finish())
        }
    }

    @Test
    fun `Luaの計算の燃料切れも、ほとんどは巻き戻してスレッドを持たずに待つ`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = h.machine(fuelPerTick = 50_000)
            run {
                m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
                m.vfs.createDirectory(VfsPath.parse("/bin"))
                val bytes = resource("lua.wasm")
                val f = m.vfs.open(VfsPath.parse("/bin/lua"), OpenOptions(read = true, write = true, create = true, truncate = true))
                m.vfs.write(f, bytes, 0, bytes.size)
            }
            val output = m.lock.withLockValue { ByteStream(64 * 1024).also { m.kernel.objects.add(it) }.id }
            val stdio = StdioSpec(StdioSource.Closed, StdioSource.Object(output, HandleRights.WRITE), StdioSource.Closed)
            val script = "local s = 0 for i = 1, 20000 do s = s + i % 7 end " +
                "local t = {} for i = 1, 2000 do t[i] = (i * 7919) % 1009 end table.sort(t) " +
                "local u = '' for i = 1, 300 do u = string.format('%s%d', string.sub(u, -20), i) end print(s, t[1], #u)"
            val pid = m.exec("/bin/lua", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("lua", "-e", script), stdio = stdio))
            assertTrue(m.rt(pid).program.fuelYield, "${m.rt(pid).program.fuelYieldRejection}")
            val unwinds0 = h.scheduler.fuelUnwinds.get()
            val blocking0 = h.scheduler.fuelBlockingWaits.get()
            eventually(60_000, "終わらない") {
                h.scheduler.tick()
                m.stateOf(pid).isTerminal
            }
            assertEquals(ProcessState.EXITED, m.stateOf(pid), "${m.rt(pid).lastError}")
            val unwinds = h.scheduler.fuelUnwinds.get() - unwinds0
            val blocking = h.scheduler.fuelBlockingWaits.get() - blocking0
            println("Lua の燃料切れ: 巻き戻し $unwinds 回、スレッドを持って待った $blocking 回")
            assertTrue(unwinds > 0)
            assertTrue(unwinds >= blocking, "巻き戻し $unwinds 回、スレッドを持って待った $blocking 回")
        }
    }
    /**
     * 段1（燃料切れも待ち点にする）を断られ、段2（入力待ちだけ）で変換できる p1 のコマンド（#396）。
     *
     * `_start` は、SIMD の命令（`v128.const`・`i32x4.extract_lane`）を持ち [n] 回まわる loop と、通らない `poll_oneoff` の呼び出しを持つ。
     * `poll_oneoff` を呼びうるので `_start` は止まりうる関数になり、段1では loop の先頭の燃料の確かめで loop が待ち点になり、
     * その中の SIMD の命令の型を追えずに断られる。段2では、loop の中に待ち点がないので変換できる
     * （#425 の前は、引数を持つブロックで断られる形にしていた。今は引数を持つブロックは変換できる）
     */
    private fun fallbackModule(n: Int): ByteArray {
        val i32 = WasmBinary.I32
        val b = WasmBinary()
        val poll = b.importFunc("wasi_snapshot_preview1", "poll_oneoff", listOf(i32, i32, i32, i32), listOf(i32))
        b.memory(1)
        val simd = WasmBinary.code(0xFD, 0x0C, ByteArray(16), 0xFD, 0x1B, 0, 0x1A) // (drop (i32x4.extract_lane 0 (v128.const i32x4 0 0 0 0)))
        val body = WasmBinary.code(
            0x41, WasmBinary.sleb(n.toLong()), 0x21, 0,
            0x02, 0x40, 0x03, 0x40, simd, 0x20, 0, 0x45, 0x0D, 1, 0x20, 0, 0x41, 1, 0x6B, 0x21, 0, 0x0C, 0, 0x0B, 0x0B, // 0 になるまで減らす loop
            0x41, 0, 0x04, 0x40, 0x41, 0, 0x41, 0, 0x41, 0, 0x41, 0, 0x10, poll, 0x1A, 0x0B, // (if (i32.const 0) (drop (call poll_oneoff ...)))
        )
        b.exportFunc("_start", b.func(emptyList(), emptyList(), body, locals = listOf(i32)))
        return b.build()
    }

    @Test
    fun `段1を断られたモジュールは段2で変換し、燃料切れはスレッドを持って待つ待機点型として動く`() = timeout(limit) {
        val bytes = fallbackModule(200_000)
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = h.machine(fuelPerTick = 50_000)
            run {
                m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
                m.vfs.createDirectory(VfsPath.parse("/bin"))
                val f = m.vfs.open(VfsPath.parse("/bin/fallback"), OpenOptions(read = true, write = true, create = true, truncate = true))
                m.vfs.write(f, bytes, 0, bytes.size)
            }
            val unwinds0 = h.scheduler.fuelUnwinds.get()
            val blocking0 = h.scheduler.fuelBlockingWaits.get()
            val stdio = StdioSpec(StdioSource.Closed, StdioSource.Closed, StdioSource.Closed)
            val pid = m.exec("/bin/fallback", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("fallback"), stdio = stdio))
            val program = m.rt(pid).program
            assertEquals(ProcessKind.WAIT_POINT, m.lock.withLockValue { m.kernel.processes[pid]!!.kind })
            assertTrue(program.module.isAsyncified)
            assertFalse(program.fuelYield)
            assertNotNull(program.fuelYieldRejection)
            val reason = program.fuelYieldRejection!!
            assertTrue(reason.contains("SIMD"), reason)
            // 読み込んだときに、理由をもうログに出している（同じモジュールではもう出さない）
            assertFalse(FuelYieldRejectionLog.report(program.hash, reason))
            // 最初の燃料をこの tick で渡し、使い切って待つところまで tick を進めずに待つ（tick が進むと、待たずに譲って済むことがある）。
            // 200 ms 待っても来なければ、燃料待ちで止まっているとして、1 回だけ tick を進める
            h.scheduler.tick()
            var spins = 0
            eventually(60_000, "燃料切れでスレッドを持って待たない") {
                if (h.scheduler.fuelBlockingWaits.get() > blocking0 || m.stateOf(pid).isTerminal) return@eventually true
                Thread.sleep(1)
                if (++spins % 200 == 0) h.scheduler.tick()
                false
            }
            val waited = h.scheduler.fuelBlockingWaits.get() > blocking0
            eventually(60_000, "終わらない") {
                h.scheduler.tick()
                m.stateOf(pid).isTerminal
            }
            assertEquals(ProcessState.EXITED, m.stateOf(pid), "${m.rt(pid).lastError}")
            assertTrue(waited, "燃料切れでスレッドを持って待っていない")
            assertEquals(unwinds0, h.scheduler.fuelUnwinds.get(), "段2なのに燃料切れで巻き戻した")
        }
    }

    @Test
    fun `段1を断った理由のログは、同じモジュールで1回だけ出す`() {
        val bytes = fallbackModule(123_457)
        val hash = Program.loadMetered(EndiveRuntime(), bytes, waitImports = WasiP1.WAIT_IMPORTS).hash
        // 1回目の読み込みで出した。読み込み直しても、もう出さない
        assertFalse(FuelYieldRejectionLog.report(hash, "x"))
        val again = Program.loadMetered(EndiveRuntime(), bytes, waitImports = WasiP1.WAIT_IMPORTS)
        assertEquals(hash, again.hash)
        assertFalse(FuelYieldRejectionLog.report(hash, "x"))
        // 段1で変換できたモジュールは出さない
        val yielded = Program.loadMetered(EndiveRuntime(), resource("fuel-spin-wasi.wasm"), waitImports = WasiP1.WAIT_IMPORTS)
        assertTrue(yielded.fuelYield)
        assertTrue(FuelYieldRejectionLog.report(yielded.hash, "テストから"))
    }

    @Test
    fun `手持ちを渡した後にtickが進んでから巻き戻したら、その tick の補充で次の tick を待たずに再開する`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = h.machine(fuelPerTick = 20_000)
            val unwound = AtomicInteger()
            // 1回目の巻き戻しの直前に tick を進める（ディスパッチャーはこの tick の列を、巻き戻したプロセスを入れる前に見る）
            h.scheduler.hooks.beforeFuelUnwound = { _, _ -> if (unwound.incrementAndGet() == 1) h.scheduler.tick() }
            val s = Spin(h, m, listOf("main", "1000", "100000"))
            assertTrue(s.rt.program.fuelYield)
            val before = h.scheduler.fuelUnwinds.get()
            h.scheduler.tick()
            // 進めた tick の補充で巻き直して動き、また燃料が切れて巻き戻す（この間、テストからは tick を進めない）
            eventually(30_000, "進めた tick の補充で再開しない") { h.scheduler.fuelUnwinds.get() - before >= 2 && s.waitingFuelThreadless() }
            assertEquals(2, unwound.get())
            m.lock.withLockValue { m.killLocked(s.pid, KillSignal.KILL) }
            Unit
        }
    }
}
