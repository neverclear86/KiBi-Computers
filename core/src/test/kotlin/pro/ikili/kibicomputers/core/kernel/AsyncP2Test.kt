package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.KillSignal
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.time.ManualClock
import pro.ikili.kibicomputers.core.kernel.time.ManualTimerService
import pro.ikili.kibicomputers.core.kernel.time.Timer
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.wasi.p2.P2Testing
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * p2 のコマンド（コンポーネント）を、待機点への変換（#757。ADR 0757）をかけて `Machine.exec` で動かす。
 *
 * `prompt-wasi.wasm`（`core/src/test/wasm/prompt_wasi.c`。p1 の C の CLI）を、アダプターで p2 のコマンドにして使う。
 * 標準入力は `blocking-read`、出力は `blocking-write-and-flush`、`nanosleep` は時計の pollable の `poll` で待つ。
 */
class AsyncP2Test {
    private val limit = Duration.ofSeconds(180)
    private val ms = 1_000_000L

    private val prompt: ByteArray by lazy { P2Testing.wrap("prompt-wasi") }
    private val promptP1: ByteArray by lazy { P2Testing.resource("/kibicomputers/wasm/prompt-wasi.wasm") }

    /** 標準入力・標準出力のパイプをつないだ1プロセス */
    private class Session(val h: Harness, val m: Machine, bytes: ByteArray, args: List<String>, outCapacity: Int = 64 * 1024) {
        val input: Int
        val output: Int
        val pid: Int
        private val out = StringBuilder()

        init {
            m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
            m.vfs.createDirectory(VfsPath.parse("/bin"))
            val f = m.vfs.open(VfsPath.parse("/bin/prompt"), OpenOptions(read = true, write = true, create = true, truncate = true))
            m.vfs.write(f, bytes, 0, bytes.size)
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

        fun awaitPrompt() = tickUntil("プロンプトで待たない: ${drain()}") {
            drain().endsWith("> ") && m.stateOf(pid) == ProcessState.WAITING_EVENT
        }

        fun run(lines: List<String>): String {
            awaitPrompt()
            for (line in lines) {
                val mark = drain().length
                send("$line\n")
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

    private fun runPrompt(bytes: ByteArray, asyncify: Boolean, args: List<String>, fuelPerTick: Long): Pair<String, ProcessKind> =
        Harness(execRuntime = EndiveRuntime(), asyncify = asyncify).use { h ->
            val s = Session(h, h.machine(fuelPerTick = fuelPerTick), bytes, args)
            val out = s.run(lines)
            assertEquals(ProcessState.EXITED, s.m.stateOf(s.pid), "${s.rt.lastError}")
            out to s.process.kind
        }

    @Test
    fun `入力を待つ間はWAIT_POINTで、スレッドを持たず待機点にある`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val s = Session(h, h.machine(fuelPerTick = 10_000_000), prompt, emptyList())
            s.awaitPrompt()
            assertEquals(ProcessKind.WAIT_POINT, s.process.kind)
            assertTrue(s.rt.program.component!!.waitPoints, "${s.rt.program.component!!.waitPointRejection}")
            eventually(5_000, "スレッドが外れない") { s.rt.thread == null }
            assertTrue(h.scheduler.dedicatedThreads.isEmpty(), "専用スレッドがある")
            assertEquals(0, h.scheduler.blockingProcesses, "ブロッキング型の枠を使っている")
            assertTrue(s.m.lock.withLockValue { s.process.asyncStack!!.size } > 0, "退避した値がない")
            assertTrue(s.m.isAtWaitPoint, "待機点でない")
            assertEquals("> ", s.drain())

            s.send("abc\n")
            s.awaitPrompt()
            assertTrue(s.drain().startsWith("> 1 abc "), s.drain())
            s.send("exit\n")
            s.tickUntil("終わらない") { s.m.stateOf(s.pid).isTerminal }
            assertEquals(ProcessState.EXITED, s.m.stateOf(s.pid), "${s.rt.lastError}")
            assertEquals(0, s.process.exitCode)
            assertTrue(s.m.lock.withLockValue { s.process.asyncStack!!.isEmpty })
        }
    }

    @Test
    fun `出力がp1をブロッキングで動かしたときと一致する（普通・再帰の底・qsort）`() = timeout(limit) {
        for (args in listOf(emptyList(), listOf("deep", "100"), listOf("sort"))) {
            val (blocking, blockingKind) = runPrompt(promptP1, asyncify = false, args, fuelPerTick = 10_000_000)
            val (p2, p2Kind) = runPrompt(prompt, asyncify = true, args, fuelPerTick = 10_000_000)
            assertEquals(ProcessKind.BLOCKING, blockingKind)
            assertEquals(ProcessKind.WAIT_POINT, p2Kind)
            assertEquals(blocking, p2, "引数 $args")
            assertTrue(p2.contains("5 ${"x".repeat(200)} "), p2)
        }
    }

    @Test
    fun `燃料切れの待ちと巻き戻しが両方起きても出力が同じ`() = timeout(limit) {
        val (blocking, _) = runPrompt(promptP1, asyncify = false, listOf("sort"), fuelPerTick = 10_000_000)
        val (p2, kind) = runPrompt(prompt, asyncify = true, listOf("sort"), fuelPerTick = 3_000)
        assertEquals(ProcessKind.WAIT_POINT, kind)
        assertEquals(blocking, p2)
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
    fun `時計のpollは巻き戻して待ち、タイマーを引き継いで期限どおりに終わる`() = timeout(limit) {
        manual { h, timers, m ->
            val before = timerCount(m)
            val s = Session(h, m, prompt, listOf("sleep", "100"))
            s.tickUntil("時計で待たない") { m.stateOf(s.pid) == ProcessState.WAITING_EVENT }
            assertEquals(ProcessKind.WAIT_POINT, s.process.kind)
            eventually(5_000, "スレッドが外れない") { s.rt.thread == null }
            val wait = m.lock.withLockValue { s.process.asyncWait }
            assertNotNull(wait, "やり直しの値がない")
            val timerKey = wait!!.timer!!
            assertTrue(m.lock.withLockValue { s.process.waitTimers.contains(timerKey.objectId) })
            val deadline = m.lock.withLockValue { (m.kernel.objects[timerKey.objectId] as Timer).deadlineNanos }

            // 期限の前にタイマーを鳴らす（早く起こされた）: やり直して、同じ期限のタイマーで待ち直す
            timers.advance(60 * ms)
            m.lock.withLockValue { m.armTimerLocked(m.kernel.objects[timerKey.objectId] as Timer, m.monotonicNanosLocked(), 0) }
            timers.runDue()
            s.tickUntil("やり直して待ち直さない") {
                m.stateOf(s.pid) == ProcessState.WAITING_EVENT &&
                    m.lock.withLockValue { (m.kernel.objects[timerKey.objectId] as? Timer)?.deadlineNanos == deadline && s.process.asyncWait != null }
            }
            assertEquals("", s.drain())
            assertEquals(before + 1, timerCount(m), "タイマーは引き継ぐ")

            timers.advance(40 * ms)
            s.tickUntil("終わらない") { m.stateOf(s.pid).isTerminal }
            assertEquals(ProcessState.EXITED, m.stateOf(s.pid), "${s.rt.lastError}")
            assertEquals("slept\n", s.drain())
            assertEquals(before, timerCount(m), "一時的なタイマーが残った")
            assertTrue(m.lock.withLockValue { s.process.waitTimers.isEmpty })
        }
    }

    @Test
    fun `時計で待っている間にkillすると、タイマーも退避した値も残らない`() = timeout(limit) {
        manual { h, _, m ->
            val before = timerCount(m)
            val s = Session(h, m, prompt, listOf("sleep", "100000"))
            s.tickUntil("時計で待たない") { m.stateOf(s.pid) == ProcessState.WAITING_EVENT }
            assertEquals(before + 1, timerCount(m))
            eventually(5_000, "スレッドが外れない") { s.rt.thread == null }
            m.lock.withLockValue { m.killLocked(s.pid, KillSignal.KILL) }
            s.tickUntil("止まらない") { m.stateOf(s.pid).isTerminal }
            assertEquals(ProcessState.KILLED, m.stateOf(s.pid))
            m.lock.withLockValue {
                assertTrue(s.process.waitTimers.isEmpty)
                assertNull(s.process.asyncWait)
                assertTrue(s.process.asyncStack!!.isEmpty)
                assertEquals(0, s.process.hostResources.size, "ホストの resource が残った")
            }
            assertEquals(before, timerCount(m), "一時的なタイマーが残った")
        }
    }

    @Test
    fun `パイプがいっぱいのときの書き込みは巻き戻して待ち、重ねずに残りを書き終える`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val n = 20000
            val s = Session(h, h.machine(fuelPerTick = 10_000_000), prompt, listOf("write", n.toString()), outCapacity = 100)
            s.tickUntil("書き込みで待たない") { s.m.stateOf(s.pid) == ProcessState.WAITING_EVENT }
            assertEquals(ProcessKind.WAIT_POINT, s.process.kind)
            eventually(5_000, "スレッドが外れない") { s.rt.thread == null }
            assertEquals(0, h.scheduler.blockingProcesses, "ブロッキング型の枠を使っている")
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
}
