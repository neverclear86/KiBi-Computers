package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.boot.BootVolume
import pro.ikili.kibicomputers.core.kernel.boot.BootVolumeOpener
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.AsyncStackOverflowException
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.KillSignal
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.time.ManualClock
import pro.ikili.kibicomputers.core.kernel.time.ManualTimerService
import pro.ikili.kibicomputers.core.kernel.time.Timer
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.WasmBinary
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.wasi.p1.WasiP1
import pro.ikili.kibicomputers.core.wasi.p1.WasiP1Driver
import java.time.Duration
import java.util.Collections
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * 読み込み時の待機点への変換（#329）をかけた p1 のコマンドを、実際の Endive と `Machine.exec` で動かす。
 *
 * `prompt-wasi.wasm`（`core/src/test/wasm/prompt_wasi.c`）は fgets で1行ずつ待つ CLI。
 */
class AsyncP1Test {
    private val limit = Duration.ofSeconds(120)
    private val ms = 1_000_000L

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/$name")) { "$name がリソースにない" }.use { it.readBytes() }

    private fun Machine.installPrompt() = lock.withLockValue {
        vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
        vfs.createDirectory(VfsPath.parse("/bin"))
        val bytes = resource("prompt-wasi.wasm")
        val f = vfs.open(VfsPath.parse("/bin/prompt"), OpenOptions(read = true, write = true, create = true, truncate = true))
        vfs.write(f, bytes, 0, bytes.size)
    }

    /** 標準入力・標準出力のパイプをつないだ1プロセス */
    private inner class Session(val h: Harness, val m: Machine, args: List<String>, outCapacity: Int = 64 * 1024) {
        val input: Int
        val output: Int
        val pid: Int
        private val out = StringBuilder()

        init {
            m.installPrompt()
            val ids = m.lock.withLockValue {
                ByteStream(4096).also { m.kernel.objects.add(it) }.id to ByteStream(outCapacity).also { m.kernel.objects.add(it) }.id
            }
            input = ids.first
            output = ids.second
            val stdio = StdioSpec(StdioSource.Object(input, HandleRights.READ), StdioSource.Object(output, HandleRights.WRITE), StdioSource.Closed)
            pid = m.exec("/bin/prompt", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("prompt") + args, stdio = stdio))
        }

        val rt get() = m.rt(pid)
        val process get() = m.lock.withLockValue { m.kernel.processes[pid]!! }

        /** 出力を読み足して返す */
        fun drain(): String {
            m.lock.withLockValue {
                val s = m.kernel.objects[output] as ByteStream
                val buf = ByteArray(s.available)
                s.read(buf, 0, buf.size, m.wakeSink)
                out.append(String(buf, Charsets.UTF_8))
            }
            return out.toString()
        }

        fun send(text: String) = m.lock.withLockValue {
            val b = text.toByteArray()
            (m.kernel.objects[input] as ByteStream).write(b, 0, b.size, m.wakeSink)
            Unit
        }

        fun closeInput() = m.lock.withLockValue { (m.kernel.objects[input] as ByteStream).closeWrite(m.wakeSink) }

        fun tickUntil(message: String, condition: () -> Boolean) = eventually(60_000, message) {
            h.scheduler.tick()
            condition()
        }

        /** 入力を待つところ（プロンプトを出して WAITING_EVENT）まで進める */
        fun awaitPrompt() = tickUntil("プロンプトで待たない: ${drain()}") {
            drain().endsWith("> ") && m.stateOf(pid) == ProcessState.WAITING_EVENT
        }

        /** 1行ずつ渡し、最後に入力を閉じて終わるまで動かす。出力を返す */
        fun run(lines: List<String>): String {
            awaitPrompt()
            for (line in lines) {
                val mark = drain().length
                send("$line\n")
                // 次のプロンプトが出て、また待つまで
                tickUntil("行を読まない") { drain().length > mark && drain().endsWith("> ") && m.stateOf(pid) == ProcessState.WAITING_EVENT }
            }
            closeInput()
            tickUntil("終わらない") { drain(); m.stateOf(pid).isTerminal }
            return drain()
        }
    }

    private fun timerCount(m: Machine): Int = m.lock.withLockValue {
        var n = 0
        m.kernel.objects.forEachObject { if (it is Timer) n++ }
        n
    }

    private val lines = listOf("hello", "world", "", "日本語の行", "x".repeat(200))

    private fun runPrompt(asyncify: Boolean, args: List<String>, fuelPerTick: Long): Pair<String, ProcessKind> =
        Harness(execRuntime = EndiveRuntime(), asyncify = asyncify).use { h ->
            val s = Session(h, h.machine(fuelPerTick = fuelPerTick), args)
            val out = s.run(lines)
            assertEquals(ProcessState.EXITED, s.m.stateOf(s.pid), "${s.rt.lastError}")
            out to s.process.kind
        }

    @Test
    fun `入力を待つ間はWAIT_POINTで、スレッドを持たず待機点にある`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val s = Session(h, h.machine(fuelPerTick = 10_000_000), emptyList())
            s.awaitPrompt()
            assertEquals(ProcessKind.WAIT_POINT, s.process.kind)
            assertTrue(s.rt.program.module.isAsyncified)
            eventually(5_000, "スレッドが外れない") { s.rt.thread == null }
            assertTrue(h.scheduler.dedicatedThreads.isEmpty(), "専用スレッドがある")
            assertEquals(0, h.scheduler.blockingProcesses, "ブロッキング型の枠を使っている")
            val stack = s.m.lock.withLockValue { s.process.asyncStack!!.size }
            assertTrue(stack > 0, "退避した値がない")
            assertTrue(s.m.isAtWaitPoint, "待機点でない")
            assertEquals("> ", s.drain())

            s.send("abc\n")
            s.awaitPrompt()
            assertTrue(s.drain().startsWith("> 1 abc "), s.drain())
            s.send("exit\n")
            s.tickUntil("終わらない") { s.m.stateOf(s.pid).isTerminal }
            assertEquals(ProcessState.EXITED, s.m.stateOf(s.pid))
            assertEquals(0, s.process.exitCode)
            assertTrue(s.m.lock.withLockValue { s.process.asyncStack!!.isEmpty })
        }
    }

    @Test
    fun `出力がブロッキングで動かしたときと一致する（普通・再帰の底・qsort）`() = timeout(limit) {
        for (args in listOf(emptyList(), listOf("deep", "100"), listOf("sort"))) {
            val (blocking, blockingKind) = runPrompt(asyncify = false, args, fuelPerTick = 10_000_000)
            val (async, asyncKind) = runPrompt(asyncify = true, args, fuelPerTick = 10_000_000)
            assertEquals(ProcessKind.BLOCKING, blockingKind)
            assertEquals(ProcessKind.WAIT_POINT, asyncKind)
            assertEquals(blocking, async, "引数 $args")
            assertTrue(async.contains("5 ${"x".repeat(200)} "), async)
        }
    }

    @Test
    fun `燃料切れの待ちと巻き戻しが両方起きても出力が同じ`() = timeout(limit) {
        val (blocking, _) = runPrompt(asyncify = false, listOf("sort"), fuelPerTick = 10_000_000)
        // 1 tick の燃料を小さくして、step の途中で燃料が尽きるようにする
        val (async, kind) = runPrompt(asyncify = true, listOf("sort"), fuelPerTick = 3_000)
        assertEquals(ProcessKind.WAIT_POINT, kind)
        assertEquals(blocking, async)
    }

    /** 手で進める時計のある Harness */
    private fun manual(block: (Harness, ManualTimerService, Machine) -> Unit) {
        val clock = ManualClock()
        val timers = ManualTimerService(clock)
        Harness(execRuntime = EndiveRuntime(), timers = timers).use { h ->
            val env = MachineEnvironment.builder().clock(clock).build()
            block(h, timers, h.machine(fuelPerTick = 10_000_000, environment = env))
        }
    }

    @Test
    fun `poll_oneoffの相対の時計は、巻き戻してやり直しても期限が延びない`() = timeout(limit) {
        manual { h, timers, m ->
            val s = Session(h, m, listOf("sleep", "100"))
            s.tickUntil("時計で待たない") { m.stateOf(s.pid) == ProcessState.WAITING_EVENT }
            assertEquals(ProcessKind.WAIT_POINT, s.process.kind)
            val wait = m.lock.withLockValue { s.process.asyncWait }
            assertNotNull(wait, "やり直しの値がない")
            val timerKey = wait!!.timer!!
            assertTrue(m.lock.withLockValue { s.process.waitTimers.contains(timerKey.objectId) })
            val deadline = m.lock.withLockValue { (m.kernel.objects[timerKey.objectId] as Timer).deadlineNanos }

            // 60 ms 進めてから、タイマーを今すぐ鳴らす（期限の前に起こされた）。やり直しても 100 ms の期限のまま
            timers.advance(60 * ms)
            m.lock.withLockValue { m.armTimerLocked(m.kernel.objects[timerKey.objectId] as Timer, m.monotonicNanosLocked(), 0) }
            timers.runDue()
            s.tickUntil("やり直して待ち直さない") {
                m.stateOf(s.pid) == ProcessState.WAITING_EVENT &&
                    m.lock.withLockValue { (m.kernel.objects[timerKey.objectId] as? Timer)?.deadlineNanos == deadline && s.process.asyncWait != null }
            }
            assertEquals("", s.drain())
            assertEquals(1, timerCount(m), "タイマーは引き継ぐ")

            // 残りの 40 ms で終わる
            timers.advance(39 * ms)
            repeat(20) { h.scheduler.tick() }
            assertEquals("", s.drain(), "期限の前に終わった")
            timers.advance(1 * ms)
            s.tickUntil("終わらない") { m.stateOf(s.pid).isTerminal }
            assertEquals(ProcessState.EXITED, m.stateOf(s.pid), "${s.rt.lastError}")
            assertEquals("slept\n", s.drain())
            assertEquals(0, timerCount(m), "一時的なタイマーが残った")
            assertTrue(m.lock.withLockValue { s.process.waitTimers.isEmpty })
        }
    }

    @Test
    fun `時計で待っている間にContextを作り直しても、やり直しでタイマーを合わせ直せて残らない`() = timeout(limit) {
        manual { h, timers, m ->
            val s = Session(h, m, listOf("sleep", "100"))
            s.tickUntil("時計で待たない") { m.stateOf(s.pid) == ProcessState.WAITING_EVENT }
            val timerKey = m.lock.withLockValue { s.process.asyncWait!!.timer!! }
            // 復元の代わり: Context を作り直す。Context は状態を持たないので、新しい Context からも同じタイマーを合わせ直せる
            val old = s.rt.context
            m.runner.initRuntime(s.rt)
            assertFalse(old === s.rt.context)
            val deadline = m.lock.withLockValue { (m.kernel.objects[timerKey.objectId] as Timer).deadlineNanos }
            s.rt.context.resetWaitTimer(timerKey, deadline)
            timers.advance(100 * ms)
            s.tickUntil("終わらない") { m.stateOf(s.pid).isTerminal }
            assertEquals(ProcessState.EXITED, m.stateOf(s.pid), "${s.rt.lastError}")
            assertEquals("slept\n", s.drain())
            assertTrue(m.lock.withLockValue { s.process.waitTimers.isEmpty })
            assertEquals(0, timerCount(m))
        }
    }

    @Test
    fun `待機点型が時計で待っている間にkillすると、一時的なタイマーが残らない`() = timeout(limit) {
        manual { h, _, m ->
            val before = timerCount(m)
            val s = Session(h, m, listOf("sleep", "100000"))
            s.tickUntil("時計で待たない") { m.stateOf(s.pid) == ProcessState.WAITING_EVENT }
            assertEquals(before + 1, timerCount(m))
            assertNull(s.rt.thread)
            m.lock.withLockValue { m.killLocked(s.pid, KillSignal.KILL) }
            s.tickUntil("止まらない") { m.stateOf(s.pid).isTerminal }
            assertEquals(ProcessState.KILLED, m.stateOf(s.pid))
            m.lock.withLockValue {
                assertTrue(s.process.waitTimers.isEmpty)
                assertNull(s.process.asyncWait)
                assertTrue(s.process.asyncStack!!.isEmpty)
            }
            assertEquals(before, timerCount(m), "一時的なタイマーが残った")
        }
    }

    @Test
    fun `ブロッキング型のsleep中にkillすると、タイマーを1回だけ閉じて残らない`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime(), asyncify = false).use { h ->
            val m = h.machine(fuelPerTick = 10_000_000)
            val before = timerCount(m)
            val s = Session(h, m, listOf("sleep", "100000"))
            s.tickUntil("時計で待たない") { m.stateOf(s.pid) == ProcessState.WAITING_EVENT }
            assertEquals(ProcessKind.BLOCKING, s.process.kind)
            assertEquals(before + 1, timerCount(m))
            m.lock.withLockValue { m.killLocked(s.pid, KillSignal.KILL) }
            s.tickUntil("止まらない") { m.stateOf(s.pid).isTerminal }
            assertEquals(ProcessState.KILLED, m.stateOf(s.pid))
            assertTrue(m.lock.withLockValue { s.process.waitTimers.isEmpty })
            assertEquals(before, timerCount(m), "一時的なタイマーが残った")
        }
    }

    @Test
    fun `一時的なタイマーを閉じずに終わったプロセスは、終わるときにカーネルが閉じる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val before = timerCount(m)
            val driver = TestDriver(ProcessKind.WAIT_POINT) { ctx ->
                ctx.openWaitTimer(Long.MAX_VALUE)
                StepResult.Exit(0)
            }
            val pid = m.spawnTest(h.program(emptyModule()), driver)
            eventually(10_000, "終わらない") {
                h.scheduler.tick()
                m.stateOf(pid).isTerminal
            }
            assertEquals(ProcessState.EXITED, m.stateOf(pid))
            assertEquals(before, timerCount(m), "一時的なタイマーが残った")
        }
    }

    @Test
    fun `パイプがいっぱいのときのfd_writeは巻き戻して待ち、読む側が読んだら残りを書き終える`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val n = 5000
            val s = Session(h, h.machine(fuelPerTick = 10_000_000), listOf("write", n.toString()), outCapacity = 256)
            s.tickUntil("書き込みで待たない") { s.m.stateOf(s.pid) == ProcessState.WAITING_EVENT }
            assertEquals(ProcessKind.WAIT_POINT, s.process.kind)
            assertTrue(s.m.lock.withLockValue { s.process.asyncStack!!.size } > 0)
            assertNull(s.rt.thread)
            s.tickUntil("終わらない") {
                s.drain()
                s.m.stateOf(s.pid).isTerminal
            }
            assertEquals(ProcessState.EXITED, s.m.stateOf(s.pid), "${s.rt.lastError}")
            assertEquals(0, s.process.exitCode)
            val expected = String(CharArray(n) { 'a' + it % 26 }) + "\ndone\n"
            assertEquals(expected, s.drain())
        }
    }

    @Test
    fun `退避する値が上限を超えるとCRASHED`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val s = Session(h, h.machine(fuelPerTick = 100_000_000), listOf("deep", "20000"))
            s.tickUntil("終わらない") { s.m.stateOf(s.pid).isTerminal }
            assertEquals(ProcessState.CRASHED, s.m.stateOf(s.pid))
            assertTrue(s.rt.lastError is AsyncStackOverflowException, "${s.rt.lastError}")
        }
    }

    /** `_start` と、v128 のローカルを持ち fd_read を呼ぶ関数（変換できない作り）のモジュール */
    private fun simdCommand(): ByteArray {
        val i32 = 0x7F
        val b = WasmBinary()
        val fdRead = b.importFunc("wasi_snapshot_preview1", "fd_read", listOf(i32, i32, i32, i32), listOf(i32))
        b.memory(1)
        b.mutableI64Global(0)
        // fd_read(0, 0, 0, 16) を呼んで捨てる。v128 のローカルを1つ持つ
        val body = WasmBinary.code(0x41, 0, 0x41, 0, 0x41, 0, 0x41, 16, 0x10, fdRead, 0x1A)
        val start = b.func(emptyList(), emptyList(), body, locals = listOf(0x7B))
        b.exportFunc("_start", start)
        return b.build()
    }

    @Test
    fun `変換できない作りのモジュールは読み込みを断り、理由に断った機能が入る`() = timeout(limit) {
        val e = assertThrows<WasmLoadException> { Program.loadMetered(EndiveRuntime(), simdCommand(), null, WasiP1.WAIT_IMPORTS) }
        val reason = Program.unsuspendableReason(e)
        assertNotNull(reason, "${e.message}")
        assertTrue(reason!!.contains("v128"), reason)
        // 変換をかけない読み込み（テストの設定）は、これまでどおり BLOCKING で動かせる
        val unconverted = Program.loadMetered(EndiveRuntime(), simdCommand(), null)
        assertFalse(unconverted.module.isAsyncified)
        assertEquals(ProcessKind.BLOCKING, WasiP1Driver(unconverted.module).kind)
        // 変換できないのでなく、WASM として読めないものは理由を持たない
        val garbage = assertThrows<WasmLoadException> { Program.loadMetered(EndiveRuntime(), byteArrayOf(1, 2, 3), null, WasiP1.WAIT_IMPORTS) }
        assertNull(Program.unsuspendableReason(garbage))
    }

    private fun Machine.installSimd(path: String) = lock.withLockValue {
        vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
        val bytes = simdCommand()
        val f = vfs.open(VfsPath.parse(path), OpenOptions(read = true, write = true, create = true, truncate = true))
        vfs.write(f, bytes, 0, bytes.size)
    }

    @Test
    fun `変換できない作りのモジュールのexecはNOEXECで、プロセスもBLOCKINGの枠も使わない`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = h.machine(fuelPerTick = 10_000_000)
            m.installSimd("/simd")
            val closed = SpawnOptions(stdio = StdioSpec(StdioSource.Closed, StdioSource.Closed, StdioSource.Closed))
            // 2回目は、覚えている失敗（ProgramCache）から同じ理由で断る
            repeat(2) {
                val e = assertThrows<FsException> { m.exec("/simd", KernelDrivers.DEFAULT, closed) }
                assertEquals(Errno.NOEXEC, e.errno)
                assertTrue(e.message!!.contains("待機点に変換できない") && e.message!!.contains("v128"), e.message)
            }
            assertEquals(emptyList<Int>(), m.lock.withLockValue { m.kernel.processes.all().map { it.pid } })
            assertEquals(0, h.scheduler.blockingProcesses, "ブロッキング型の枠を使っている")
            assertTrue(h.scheduler.dedicatedThreads.isEmpty(), "専用スレッドがある")
        }
    }

    @Test
    fun `initが変換できない作りなら起動に失敗し、コンソールに理由を出す`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val console = StringBuffer()
            val failures = Collections.synchronizedList(ArrayList<BootFailure>())
            val host = object : MachineHost {
                override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) {}

                override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
                    console.append(String(bytes, Charsets.UTF_8))
                }

                override fun onBootFailed(machine: Machine, failure: BootFailure, detail: String) {
                    failures += failure
                }
            }
            val disk = TestMemoryFileSystem().also { fs ->
                fs.createDirectory(VfsPath.parse("/sbin"))
                val bytes = simdCommand()
                fs.write(fs.createFile(VfsPath.parse(BootConfig.INIT_PATH)), 0, bytes, 0, bytes.size)
            }
            h.scheduler.hooks.bootVolumes = BootVolumeOpener { id -> if (id == "simd") BootVolume(disk, readOnly = false) else null }
            val m = h.machine(fuelPerTick = 10_000_000, host = host)
            assertTrue(m.boot(BootConfig.builder().addBootDisk("simd").build()))
            eventually(30_000, "失敗が届かない") {
                h.scheduler.tick()
                failures.isNotEmpty()
            }
            assertEquals(listOf(BootFailure.INIT_NOT_EXECUTABLE), failures.toList())
            assertEquals(MachineState.STOPPED, m.state)
            val text = console.toString().replace("\r\n", "\n")
            assertTrue(text.contains("kibi: cannot start /sbin/init: NOEXEC\n"), text)
            assertTrue(text.contains("待機点に変換できない") && text.contains("v128"), text)
        }
    }
}
