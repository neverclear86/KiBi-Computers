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
import pro.ikili.kibicomputers.core.kernel.process.FuelYieldRejectionLog
import pro.ikili.kibicomputers.core.kernel.process.KillSignal
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.WasmBinary
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.runtime.fuel.FuelCosts
import pro.ikili.kibicomputers.core.wasi.p1.WasiP1
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * 変換した p1 のプロセスで、step の途中の燃料切れも待ち点にする（#339）。実際の Endive と `Machine.exec` で動かす。
 *
 * `fuel-spin-wasi.wasm`（`core/src/test/wasm/fuel_spin_wasi.c`）は、止まりうる関数（main）の中か、葉の関数の中で計算を続ける CLI。
 */
class AsyncFuelTest {
    private val limit = Duration.ofSeconds(120)

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/$name")) { "$name がリソースにない" }.use { it.readBytes() }

    private fun Machine.install() = lock.withLockValue {
        vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
        vfs.createDirectory(VfsPath.parse("/bin"))
        val bytes = resource("fuel-spin-wasi.wasm")
        val f = vfs.open(VfsPath.parse("/bin/spin"), OpenOptions(read = true, write = true, create = true, truncate = true))
        vfs.write(f, bytes, 0, bytes.size)
    }

    private inner class Spin(val h: Harness, val m: Machine, args: List<String>) {
        val output: Int = m.lock.withLockValue { ByteStream(64 * 1024).also { m.kernel.objects.add(it) }.id }
        val pid: Int
        private val out = StringBuilder()

        init {
            m.install()
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
    fun `葉の関数の中の燃料切れは、今と同じくスレッドを持ったまま待つ`() = timeout(limit) {
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
            m.lock.withLockValue {
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
     * `_start` は、引数を持つブロック `(block (param i32) ...)` の中で [n] 回まわる loop と、通らない `poll_oneoff` の呼び出しを持つ。
     * `poll_oneoff` を呼びうるので `_start` は止まりうる関数になり、段1では loop の先頭の燃料の確かめが、引数を持つブロックの中の
     * 待ち点になって断られる。段2では、そのブロックの中に待ち点がないので変換できる
     */
    private fun fallbackModule(n: Int): ByteArray {
        val i32 = WasmBinary.I32
        val b = WasmBinary()
        val poll = b.importFunc("wasi_snapshot_preview1", "poll_oneoff", listOf(i32, i32, i32, i32), listOf(i32))
        b.memory(1)
        val withParam = b.typeIndex(listOf(i32), emptyList())
        val body = WasmBinary.code(
            0x41, WasmBinary.sleb(n.toLong()),
            0x02, withParam, 0x21, 0, // (block (param i32) local.set 0
            0x02, 0x40, 0x03, 0x40, 0x20, 0, 0x45, 0x0D, 1, 0x20, 0, 0x41, 1, 0x6B, 0x21, 0, 0x0C, 0, 0x0B, 0x0B, // 0 になるまで減らす loop
            0x0B, // )
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
            m.lock.withLockValue {
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
            assertTrue(reason.contains("引数を持つブロック"), reason)
            // 読み込んだときに、理由をもうログに出している（同じモジュールではもう出さない）
            assertFalse(FuelYieldRejectionLog.report(program.hash, reason))
            eventually(60_000, "終わらない") {
                h.scheduler.tick()
                m.stateOf(pid).isTerminal
            }
            assertEquals(ProcessState.EXITED, m.stateOf(pid), "${m.rt(pid).lastError}")
            assertTrue(h.scheduler.fuelBlockingWaits.get() > blocking0, "燃料切れでスレッドを持って待っていない")
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
