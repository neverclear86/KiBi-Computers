package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessRuntime
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.process.StackHeadroom
import pro.ikili.kibicomputers.core.kernel.process.crashReason
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * 実 Endive の WASI p1 のプログラム（`stack-deep-wasi`）で、スタックを使い切るまで再帰させ、尽きる位置をずらしても、
 * カーネルのロック・CPU・許可が壊れないことを確かめる（#858）。
 *
 * `StackExhaustionLockTest` は `FakeModule`（待機点への変換ができない）で燃料切れの処理を直接呼ぶ。ここでは本番と同じく、
 * 燃料の計測と待機点への変換（WAIT_POINT のとき）をかけた実 Endive のコードから、次の道に入る:
 * - 止まりうる関数の中の燃料切れ（`kibi.async_fuel` → `onFuelExhaustedUnwindable`）。巻き戻せるので巻き戻して燃料を待ち、巻き直して深い再帰を続ける
 * - 葉の関数の中の燃料切れ（`kibi.fuel` → `onFuelExhaustedPlain`）。前借り（#477）と、上限に届いたときのスレッドを持って待つ道（`onFuelExhausted`）
 * - BLOCKING（変換しない）では、どちらも `onFuelExhausted`
 *
 * 1 段ごとに少し計算し、ホストの関数（`clock_time_get`）を呼ぶ。スライスは小さく、同じ台に燃料を取り合うプロセスを置く（譲る道・前借りに入る）。
 * 尽きる位置は、再帰の前に積む詰め物の段数（引数）でずらす
 */
class P1StackExhaustionLockTest {
    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/$name")) { "$name がリソースにない" }.use { it.readBytes() }

    private fun Machine.install() {
        vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
        vfs.createDirectory(VfsPath.parse("/bin"))
        val bytes = resource("stack-deep-wasi.wasm")
        val f = vfs.open(VfsPath.parse("/bin/deep"), OpenOptions(read = true, write = true, create = true, truncate = true))
        vfs.write(f, bytes, 0, bytes.size)
        vfs.close(f)
    }

    private fun soe(rt: ProcessRuntime): StackOverflowError? {
        var t: Throwable? = rt.lastError
        while (t != null && t !is StackOverflowError) t = t.cause
        return t as StackOverflowError?
    }

    private fun sweep(kind: ProcessKind, rounds: Int) = timeout(stackHeadroomTestLimit()) {
        val config = SchedulerConfig.builder().maxConcurrentMachines(2).maxBlockingProcesses(64).threadStackBytes(256L * 1024).build()
        val tops = ConcurrentHashMap<String, AtomicInteger>()
        var inLocks = 0
        var inProbe = 0
        Harness(config, execRuntime = EndiveRuntime(), asyncify = kind == ProcessKind.WAIT_POINT).use { h ->
            val m = h.machine(fuelPerTick = 50_000_000, fuelSlice = 3_000)
            val other = h.machine(fuelPerTick = 50_000_000, fuelSlice = 3_000)
            m.install()
            val sameProgress = AtomicInteger()
            val otherProgress = AtomicInteger()
            m.spawnTest(h.program(spinForever(sameProgress)), runDriver(kind))
            other.spawnTest(h.program(spinForever(otherProgress)), runDriver(kind))
            h.tickUntilTrue("spin が動かない") { sameProgress.get() > 0 && otherProgress.get() > 0 }
            val overdrafts0 = h.scheduler.fuelOverdrafts.get()
            val unwinds0 = h.scheduler.fuelUnwinds.get()
            val yields0 = h.scheduler.fuelYields.get()
            val blocking0 = h.scheduler.fuelBlockingWaits.get()
            val unwindCost0 = h.scheduler.fuelUnwindCost.get()
            val modes = HashMap<String, Int>()
            for (i in 0 until rounds) {
                val mode = if (i % 2 == 0) "a" else "l"
                val pad = (i / 2 * 3) % 61
                val stdio = StdioSpec(StdioSource.Closed, StdioSource.Closed, StdioSource.Closed)
                val pid = m.exec("/bin/deep", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("deep", mode, pad.toString()), stdio = stdio))
                val rt = m.rt(pid)
                assertEquals(kind, rt.process.kind)
                eventuallyDescribe(stackHeadroomWait(30_000), { "深い再帰が終わらない（$i 回目、$mode、pad $pad）: ${m.stateOf(pid)} ${soe(rt)?.stackTrace?.take(8)?.joinToString(" <- ")}" }) {
                    h.scheduler.tick()
                    m.stateOf(pid).isTerminal || m.state == MachineState.CRASHED
                }
                assertEquals(MachineState.RUNNING, m.state, "マシンが壊れた（$i 回目）: ${rt.lastError}")
                assertEquals(ProcessState.CRASHED, m.stateOf(pid), "${rt.lastError}")
                val e = rt.lastError
                assertTrue(e is StackOverflowError || (e is WasmTrapException && e.kind == WasmTrapKind.STACK_OVERFLOW), "$e")
                assertEquals("call stack exhausted", crashReason(e!!), "${rt.lastError}")
                modes.merge(mode, 1, Int::plus)
                soe(rt)?.stackTrace?.firstOrNull()?.let { top ->
                    tops.computeIfAbsent(top.className.substringAfterLast('.')) { AtomicInteger() }.incrementAndGet()
                    if (top.className.startsWith("java.util.concurrent.locks.")) inLocks++
                    if (top.className == StackHeadroom::class.java.name) inProbe++
                }
                assertTrue(m.lock.tryLock(5, TimeUnit.SECONDS), "マシンのロックが取れない（$i 回目、$mode、pad $pad）: ${m.lock}")
                m.lock.unlock()
                val same = sameProgress.get()
                val otherBefore = otherProgress.get()
                eventually(stackHeadroomWait(10_000), "同じ台のプロセスが止まった（$i 回目、$mode、pad $pad）") {
                    h.scheduler.tick()
                    sameProgress.get() > same + 2
                }
                h.tickUntilTrue("別の台のプロセスが止まった（$i 回目、$mode、pad $pad）") { otherProgress.get() > otherBefore + 2 }
            }
            val overdrafts = h.scheduler.fuelOverdrafts.get() - overdrafts0
            val unwinds = h.scheduler.fuelUnwinds.get() - unwinds0
            val yields = h.scheduler.fuelYields.get() - yields0
            val blocking = h.scheduler.fuelBlockingWaits.get() - blocking0
            val unwindCost = h.scheduler.fuelUnwindCost.get() - unwindCost0
            println(
                "#858 p1 $kind: CRASHED $rounds 回（$modes）、尽きた位置がロックの中 $inLocks 回、入口の確かめ $inProbe 回、" +
                    "一番上: ${tops.entries.sortedByDescending { it.value.get() }.take(8).joinToString { "${it.key}=${it.value.get()}" }}、" +
                    "前借り $overdrafts 回、巻き戻して燃料を待った $unwinds 回、譲った（巻き戻して譲ったのを含む） $yields 回、巻き戻しの費用 $unwindCost、スレッドを持って燃料を待った $blocking 回",
            )
            // 本番の燃料切れの道を通った（WAIT_POINT は前借り、BLOCKING は譲る道）
            if (kind == ProcessKind.WAIT_POINT) {
                assertTrue(overdrafts > 0, "前借りしていない")
                assertTrue(unwindCost > 0, "止まりうる関数の中の燃料切れで巻き戻していない")
            }
            assertTrue(yields + unwinds + blocking > 0, "燃料切れで譲っていない")
            m.stop()
            other.stop()
            h.tickUntilTrue("止まらない") { m.state == MachineState.STOPPED && other.state == MachineState.STOPPED }
            eventually { m.cpu.currentOwner == null && m.cpu.waiting == 0 }
            eventually(message = "許可が戻らない: ${h.scheduler.permits.availablePermits()}") {
                h.scheduler.permits.availablePermits() == config.maxConcurrentMachines
            }
            assertEquals(0, h.scheduler.permits.waiting)
            assertTrue(!m.lock.isLocked && !m.lock.hasQueuedThreads(), "${m.lock}")
        }
        assertEquals(0, inLocks, "スタックが JDK のロックの中で尽きた")
    }

    @Test
    fun `BLOCKINGのp1のプログラムが燃料切れの処理を呼びながら深く再帰して尽きる位置をずらしても、ロック・CPU・許可は壊れない`() =
        sweep(ProcessKind.BLOCKING, rounds = 40)

    @Test
    fun `WAIT_POINTのp1のプログラムが巻き戻し・前借り・待つ道を通りながら深く再帰して尽きる位置をずらしても、ロック・CPU・許可は壊れない`() =
        sweep(ProcessKind.WAIT_POINT, rounds = 40)
}
