package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
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
import pro.ikili.kibicomputers.core.wasi.p2.KibiP2WitTool
import pro.ikili.kibicomputers.core.wasi.p2.P2Testing
import pro.ikili.kibicomputers.core.wasi.p2.StreamEntry
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

    // ---- 巻き戻して待つ、ほかの stream の関数（#757。アダプターを通らないゲストで 1 つずつ動かす） ----

    /**
     * stdin・stdout の stream を直接使う p2 のコマンド。[run] は `run` の本体（ローカル `$in`・`$out`・`$p` が使える）。
     * 戻り値の領域は 64 以降（結果の tag は +0、`u64` は +8、`list<u8>` の ptr・len は +4・+8）、データは 1024 の `ABCDEFGHIJKLMNOP`、
     * `$mark` は stdout に 1 文字を書く。`run` は 0（ok）で終わる。[start] があれば、core モジュールの start 関数の本体にする
     * （`run` と同じローカルが使える。インスタンス化の中、`LOADING` で動く）
     */
    private fun streamsGuest(run: String, start: String = ""): ByteArray {
        val s = "$"
        val streams = "wasi:io/streams@0.2.12"
        val init = if (start.isEmpty()) {
            ""
        } else {
            "(func ${s}init (local ${s}in i32) (local ${s}out i32) (local ${s}p i32) " +
                "(local.set ${s}in (call ${s}stdin)) (local.set ${s}out (call ${s}stdout)) $start) (start ${s}init)"
        }
        return KibiP2WitTool.component(
            """
            import wasi:cli/stdin@0.2.12;
            import wasi:cli/stdout@0.2.12;
            import wasi:io/streams@0.2.12;
            import wasi:io/poll@0.2.12;
            export wasi:cli/run@0.2.12;
            """.trimIndent(),
            """
            (module
              (import "wasi:cli/stdin@0.2.12" "get-stdin" (func ${s}stdin (result i32)))
              (import "wasi:cli/stdout@0.2.12" "get-stdout" (func ${s}stdout (result i32)))
              (import "$streams" "[method]input-stream.blocking-read" (func ${s}bread (param i32 i64 i32)))
              (import "$streams" "[method]input-stream.blocking-skip" (func ${s}bskip (param i32 i64 i32)))
              (import "$streams" "[method]input-stream.subscribe" (func ${s}isub (param i32) (result i32)))
              (import "$streams" "[method]output-stream.check-write" (func ${s}cw (param i32 i32)))
              (import "$streams" "[method]output-stream.write" (func ${s}write (param i32 i32 i32 i32)))
              (import "$streams" "[method]output-stream.blocking-write-and-flush" (func ${s}bwrite (param i32 i32 i32 i32)))
              (import "$streams" "[method]output-stream.write-zeroes" (func ${s}wz (param i32 i64 i32)))
              (import "$streams" "[method]output-stream.blocking-splice" (func ${s}bsplice (param i32 i32 i64 i32)))
              (import "wasi:io/poll@0.2.12" "[method]pollable.block" (func ${s}pblock (param i32)))
              (import "wasi:io/poll@0.2.12" "[resource-drop]pollable" (func ${s}pdrop (param i32)))
              (import "$streams" "[resource-drop]input-stream" (func ${s}isdrop (param i32)))
              (memory (export "memory") 1)
              (func (export "cabi_realloc") (param i32 i32 i32 i32) (result i32) (i32.const 2048))
              (data (i32.const 1024) "ABCDEFGHIJKLMNOP")
              (func ${s}mark (param ${s}out i32) (param ${s}c i32)
                (i32.store8 (i32.const 3000) (local.get ${s}c))
                (call ${s}bwrite (local.get ${s}out) (i32.const 3000) (i32.const 1) (i32.const 3008)))
              $init
              (func (export "wasi:cli/run@0.2.12#run") (result i32)
                (local ${s}in i32) (local ${s}out i32) (local ${s}p i32)
                (local.set ${s}in (call ${s}stdin))
                (local.set ${s}out (call ${s}stdout))
                $run
                (i32.const 0)))
            """.trimIndent(),
        )
    }

    /** プロセスが入力・出力・時計などを待って、スレッドを持たずに待機点にいる */
    private fun parked(s: Session) {
        s.tickUntil("待たない") { s.m.stateOf(s.pid) == ProcessState.WAITING_EVENT }
        eventually(5_000, "スレッドが外れない") { s.rt.thread == null }
        assertEquals(ProcessKind.WAIT_POINT, s.process.kind)
    }

    private fun inputAvailable(s: Session): Int = s.m.lock.withLockValue { (s.m.kernel.objects[s.input] as ByteStream).available }

    private fun outputAvailable(s: Session): Int = s.m.lock.withLockValue { (s.m.kernel.objects[s.output] as ByteStream).available }

    /** 出力の pipe に [text] を直接書く（ゲストが書く場所を埋める） */
    private fun fill(s: Session, text: String) = s.m.lock.withLockValue {
        val b = text.toByteArray()
        (s.m.kernel.objects[s.output] as ByteStream).write(b, 0, b.size, s.m.wakeSink)
        Unit
    }

    /** 出力の pipe から先頭の [n] バイトだけ読む（ゲストが書き続ける空きを作る） */
    private fun readSome(s: Session, n: Int): String = s.m.lock.withLockValue {
        val buf = ByteArray(n)
        val r = (s.m.kernel.objects[s.output] as ByteStream).read(buf, 0, n, s.m.wakeSink)
        check(r is ReadResult.Bytes && r.count == n) { "出力から $n バイト読めない: $r" }
        String(buf, Charsets.UTF_8)
    }

    private fun finish(s: Session) {
        s.tickUntil("終わらない") { s.m.stateOf(s.pid).isTerminal }
        assertEquals(ProcessState.EXITED, s.m.stateOf(s.pid), "${s.rt.lastError}")
        assertEquals(0, s.process.exitCode)
    }

    /** 出力の stream の項目（ゲストが `get-stdout` で得たもの） */
    private fun outputEntry(s: Session): StreamEntry = s.m.lock.withLockValue {
        var found: StreamEntry? = null
        s.process.hostResources.forEach { _, e -> if (e is StreamEntry && !e.input) found = e }
        checkNotNull(found) { "出力の stream がない" }
    }

    @Test
    fun `writeとwrite-zeroesは、巻き直したら許可量をもう一度引かず、残りだけを順に書く`() = timeout(limit) {
        // check-write で許可量 16 をもらった後、入力を待つ間に出力の pipe が埋まる（古い許可量のまま書く）。write は書けないので巻き戻し、
        // 空いた分だけ書いては巻き戻す。許可量を巻き直しでもう一度引けば（16 - 16 の残りを超えて）トラップする
        val run = """
            (call ${'$'}cw (local.get ${'$'}out) (i32.const 64))
            (call ${'$'}bread (local.get ${'$'}in) (i64.const 1) (i32.const 80))
            (call ${'$'}write (local.get ${'$'}out) (i32.const 1024) (i32.const 16) (i32.const 96))
            (if (i32.load8_u (i32.const 96)) (then (return (i32.const 1))))
            (call ${'$'}bread (local.get ${'$'}in) (i64.const 1) (i32.const 80))
            (call ${'$'}cw (local.get ${'$'}out) (i32.const 64))
            (call ${'$'}bread (local.get ${'$'}in) (i64.const 1) (i32.const 80))
            (call ${'$'}wz (local.get ${'$'}out) (i64.const 16) (i32.const 96))
            (if (i32.load8_u (i32.const 96)) (then (return (i32.const 2))))
        """
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val s = Session(h, h.machine(fuelPerTick = 10_000_000), streamsGuest(run), emptyList(), outCapacity = 16)
            val seen = StringBuilder()
            parked(s)
            // write: 出力が埋まっている
            fill(s, "0123456789abcdef")
            s.send("1")
            s.tickUntil("write で待たない") { inputAvailable(s) == 0 && s.m.stateOf(s.pid) == ProcessState.WAITING_EVENT }
            eventually(5_000, "スレッドが外れない") { s.rt.thread == null }
            assertEquals(0, s.m.lock.withLockValue { outputEntry(s).writeProgress })
            seen.append(readSome(s, 8))
            s.tickUntil("空いた分を書かない") { outputAvailable(s) == 16 && s.m.stateOf(s.pid) == ProcessState.WAITING_EVENT }
            assertEquals(8, s.m.lock.withLockValue { outputEntry(s).writeProgress }, "書いた数を持っていない")
            seen.append(readSome(s, 8))
            // 書き終えて、次の入力を待つ
            s.tickUntil("書き終えない") { outputAvailable(s) == 16 && s.m.stateOf(s.pid) == ProcessState.WAITING_EVENT }
            assertEquals(0, s.m.lock.withLockValue { outputEntry(s).writeProgress })
            seen.append(readSome(s, 16))
            assertEquals("0123456789abcdefABCDEFGHIJKLMNOP", seen.toString(), "二重に書いたか、順が違う")

            // write-zeroes: 出力が空になってから許可量をもらい、入力を待つ間にまた埋まる
            s.send("2")
            s.tickUntil("入力を待たない") { inputAvailable(s) == 0 && s.m.stateOf(s.pid) == ProcessState.WAITING_EVENT }
            fill(s, "FEDCBA9876543210")
            s.send("3")
            s.tickUntil("write-zeroes で待たない") { inputAvailable(s) == 0 && s.m.stateOf(s.pid) == ProcessState.WAITING_EVENT }
            eventually(5_000, "スレッドが外れない") { s.rt.thread == null }
            seen.setLength(0)
            seen.append(readSome(s, 8))
            s.tickUntil("空いた分を書かない") { outputAvailable(s) == 16 && s.m.stateOf(s.pid) == ProcessState.WAITING_EVENT }
            assertEquals(8, s.m.lock.withLockValue { outputEntry(s).writeProgress }, "書いた数を持っていない")
            seen.append(readSome(s, 8))
            finish(s)
            seen.append(readSome(s, 16))
            assertEquals("FEDCBA9876543210" + "\u0000".repeat(16), seen.toString(), "二重に書いたか、順が違う")
        }
    }

    @Test
    fun `blocking-skipとpollable-blockは入力を待つ間巻き戻す`() = timeout(limit) {
        val run = """
            (call ${'$'}bskip (local.get ${'$'}in) (i64.const 3) (i32.const 64))
            (call ${'$'}mark (local.get ${'$'}out) (i32.const 49))
            (local.set ${'$'}p (call ${'$'}isub (local.get ${'$'}in)))
            (call ${'$'}pblock (local.get ${'$'}p))
            (call ${'$'}mark (local.get ${'$'}out) (i32.const 50))
            (call ${'$'}bread (local.get ${'$'}in) (i64.const 1) (i32.const 80))
            (call ${'$'}bwrite (local.get ${'$'}out) (i32.load (i32.const 84)) (i32.load (i32.const 88)) (i32.const 96))
            (call ${'$'}mark (local.get ${'$'}out) (i32.add (i32.const 65) (i32.wrap_i64 (i64.load (i32.const 72)))))
        """
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val s = Session(h, h.machine(fuelPerTick = 10_000_000), streamsGuest(run), emptyList())
            // 空の入力を skip で待つ
            parked(s)
            assertEquals("", s.drain())
            assertEquals(0, h.scheduler.blockingProcesses, "ブロッキング型の枠を使っている")
            // 3 バイト skip して、次は pollable.block で待つ
            s.send("abc")
            s.tickUntil("pollable.block で待たない") { s.drain() == "1" && s.m.stateOf(s.pid) == ProcessState.WAITING_EVENT }
            eventually(5_000, "スレッドが外れない") { s.rt.thread == null }
            assertEquals(0, h.scheduler.blockingProcesses, "ブロッキング型の枠を使っている")
            s.send("x")
            finish(s)
            assertEquals("12xD", s.drain())
        }
    }

    @Test
    fun `blocking-spliceは入力待ちでも出力の空き待ちでも、巻き直して最初からやり直す`() = timeout(limit) {
        val run = """
            (call ${'$'}bsplice (local.get ${'$'}out) (local.get ${'$'}in) (i64.const 100) (i32.const 64))
            (if (i32.load8_u (i32.const 64)) (then (return (i32.const 1))))
            (call ${'$'}mark (local.get ${'$'}out) (i32.add (i32.const 65) (i32.wrap_i64 (i64.load (i32.const 72)))))
        """
        // 入力が空: 読めるまで待つ。着いたら、出力の許可量を取り直して読み、書く
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val s = Session(h, h.machine(fuelPerTick = 10_000_000), streamsGuest(run), emptyList())
            parked(s)
            assertEquals(0, h.scheduler.blockingProcesses, "ブロッキング型の枠を使っている")
            assertEquals(null, s.m.lock.withLockValue { outputEntry(s).pendingSplice }, "書いていないのに読んだバイトを持っている")
            s.send("hello")
            finish(s)
            assertEquals("helloF", s.drain())
        }
        // 出力が一杯: 空きができるまで待つ（入力は読まない）。空いたら、取り直した許可量で読んで書く
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val s = Session(h, h.machine(fuelPerTick = 10_000_000), streamsGuest(run), emptyList(), outCapacity = 16)
            fill(s, "0123456789abcdef")
            s.send("hello")
            parked(s)
            assertEquals(5, inputAvailable(s), "出力に空きがないのに入力を読んだ")
            assertEquals(null, s.m.lock.withLockValue { outputEntry(s).pendingSplice })
            assertEquals("0123456789abcdef", readSome(s, 16))
            finish(s)
            assertEquals("helloF", s.drain())
        }
    }

    @Test
    fun `巻き戻した書き込みに読んだバイトが残っていれば、巻き直しで入力を読まずにそれを書く`() = timeout(limit) {
        // splice が入力から読んだ後に書けずに巻き戻したときの状態（StreamEntry.pendingSplice）を、入力を待っている間に置く。
        // 巻き直したら、入力を読まずに残りのバイトを書き、その数を返す
        val run = """
            (call ${'$'}bsplice (local.get ${'$'}out) (local.get ${'$'}in) (i64.const 100) (i32.const 64))
            (if (i32.load8_u (i32.const 64)) (then (return (i32.const 1))))
            (call ${'$'}mark (local.get ${'$'}out) (i32.add (i32.const 65) (i32.wrap_i64 (i64.load (i32.const 72)))))
        """
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val s = Session(h, h.machine(fuelPerTick = 10_000_000), streamsGuest(run), emptyList())
            parked(s)
            s.m.lock.withLockValue { outputEntry(s).pendingSplice = "XYZ".toByteArray() }
            s.send("hello")
            finish(s)
            assertEquals("XYZD", s.drain())
            assertEquals(5, inputAvailable(s), "入力を読んでしまった")
        }
    }

    @Test
    fun `WAIT_POINTのコンポーネントでも、start関数の中の待ちは巻き戻さずスレッドを持って待つ`() = timeout(limit) {
        // start 関数は LOADING で動くので巻き戻せない（ComponentSuspend.canUnwind が偽）。pollable.block は ctx.block で待ち
        // （await の巻き戻さない側）、run に入ってからの読みは巻き戻して待つ。start の中で呼べるのは、メモリを使わない lower だけ
        // （wit-component はメモリ・realloc を使う lower を、main のインスタンスの後に fixup が埋める表を通すので、start からは呼べない）
        val start = """
            (local.set ${'$'}p (call ${'$'}isub (local.get ${'$'}in)))
            (call ${'$'}pblock (local.get ${'$'}p))
            (call ${'$'}pdrop (local.get ${'$'}p))
            (call ${'$'}isdrop (local.get ${'$'}in))
        """
        val run = """
            (call ${'$'}bread (local.get ${'$'}in) (i64.const 1) (i32.const 80))
            (call ${'$'}bwrite (local.get ${'$'}out) (i32.load (i32.const 84)) (i32.load (i32.const 88)) (i32.const 96))
            (call ${'$'}bread (local.get ${'$'}in) (i64.const 1) (i32.const 80))
            (call ${'$'}mark (local.get ${'$'}out) (i32.const 82))
        """
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val s = Session(h, h.machine(fuelPerTick = 10_000_000), streamsGuest(run, start), emptyList())
            fun state() = s.m.stateOf(s.pid)
            s.tickUntil("start で pollable.block を待たない: ${state()} ${s.rt.lastError}") {
                state() == ProcessState.WAITING_EVENT && s.rt.thread != null
            }
            assertEquals(ProcessKind.WAIT_POINT, s.process.kind)
            assertTrue(s.rt.program.component!!.waitPoints, "${s.rt.program.component!!.waitPointRejection}")
            assertTrue(s.m.lock.withLockValue { s.process.asyncStack!!.isEmpty }, "start の中で巻き戻した")
            assertNull(s.m.lock.withLockValue { s.process.asyncWait })
            // 入力が来たら start を終え、run の読みで 1 バイト読んで書き、次の読みは巻き戻して待つ
            s.send("Q")
            s.tickUntil("run で巻き戻して待たない: ${state()} ${s.rt.lastError}") {
                s.drain() == "Q" && state() == ProcessState.WAITING_EVENT && s.rt.thread == null
            }
            assertTrue(s.m.lock.withLockValue { s.process.asyncStack!!.size } > 0, "退避した値がない")
            s.send("!")
            finish(s)
            assertEquals("QR", s.drain())
        }
    }

    /** [m] の写し [copy] の中の [pid] が殺され、待機点の続き（退避した値・やり直しの値・一時的なタイマー・ホストの resource）が残っていない */
    private fun assertKilledInCopy(m: Machine, copy: pro.ikili.kibicomputers.core.persist.KernelStateCopy, pid: Int, timersBefore: Int) {
        assertTrue(pid in copy.killedPids, "写しの中で殺していない")
        copy.processes[pid]?.let { p ->
            assertEquals(ProcessState.KILLED, p.state)
            assertTrue(p.asyncStack?.isEmpty != false, "写しに退避した値が残った")
            assertNull(p.asyncWait, "写しにやり直しの値が残った")
            assertTrue(p.waitTimers.isEmpty, "写しに一時的なタイマーが残った")
            assertEquals(0, p.hostResources.size, "写しにホストの resource が残った")
        }
        var timers = 0
        copy.objects.forEachObject { if (it is Timer) timers++ }
        assertEquals(timersBefore, timers, "写しに一時的なタイマーが残った")
        assertTrue(copy.abiCaptures[pid] == null, "殺したプロセスの ABI 層を写した")
    }

    @Test
    fun `待機点型のコンポーネントが時計で待っている台の写しでは、プロセスを殺し続きを残さず、元は動き続ける`() = timeout(limit) {
        manual { h, timers, m ->
            val before = timerCount(m)
            val s = Session(h, m, prompt, listOf("sleep", "100"))
            parked(s)
            assertNotNull(m.lock.withLockValue { s.process.asyncWait })
            assertEquals(before + 1, timerCount(m))
            // コンポーネントは静的に保存できない（UNSAVEABLE）ので、待機点型でも写しの中で殺す
            val copy = m.captureCopy(pro.ikili.kibicomputers.core.persist.CapturePolicy.KILL_TRANSIENT)!!
            assertKilledInCopy(m, copy, s.pid, before)
            // 元のプロセスはそのまま待ち、期限で終わる
            assertTrue(m.lock.withLockValue { s.process.asyncStack!!.size } > 0, "元の退避した値を消した")
            timers.advance(100 * ms)
            s.tickUntil("終わらない") { m.stateOf(s.pid).isTerminal }
            assertEquals(ProcessState.EXITED, m.stateOf(s.pid), "${s.rt.lastError}")
            assertEquals("slept\n", s.drain())
            assertEquals(before, timerCount(m))
        }
    }

    @Test
    fun `書きかけのspliceを持って待つ台の写しでは、読んだバイトを写しに残さない`() = timeout(limit) {
        val run = """
            (call ${'$'}bsplice (local.get ${'$'}out) (local.get ${'$'}in) (i64.const 100) (i32.const 64))
            (if (i32.load8_u (i32.const 64)) (then (return (i32.const 1))))
            (call ${'$'}mark (local.get ${'$'}out) (i32.add (i32.const 65) (i32.wrap_i64 (i64.load (i32.const 72)))))
        """
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = h.machine(fuelPerTick = 10_000_000)
            val before = timerCount(m)
            val s = Session(h, m, streamsGuest(run), emptyList())
            parked(s)
            s.m.lock.withLockValue { outputEntry(s).pendingSplice = "XYZ".toByteArray() }
            val copy = m.captureCopy(pro.ikili.kibicomputers.core.persist.CapturePolicy.KILL_TRANSIENT)!!
            assertKilledInCopy(m, copy, s.pid, before)
            // 元の書きかけはそのまま
            assertEquals("XYZ", s.m.lock.withLockValue { outputEntry(s).pendingSplice?.toString(Charsets.UTF_8) })
            s.send("hello")
            finish(s)
            assertEquals("XYZD", s.drain())
        }
    }
}
