package pro.ikili.kibicomputers.core.wasi.p1

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.MachineEnvironment
import pro.ikili.kibicomputers.core.kernel.ProcessKilledException
import pro.ikili.kibicomputers.core.kernel.TestDriver
import pro.ikili.kibicomputers.core.kernel.event.EventFilter
import pro.ikili.kibicomputers.core.kernel.event.EventQueue
import pro.ikili.kibicomputers.core.kernel.event.KernelEvent
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.stateOf
import pro.ikili.kibicomputers.core.kernel.tickUntilTrue
import pro.ikili.kibicomputers.core.kernel.time.ManualClock
import pro.ikili.kibicomputers.core.kernel.time.ManualTimerService
import pro.ikili.kibicomputers.core.kernel.time.Timer
import pro.ikili.kibicomputers.core.kernel.time.TimerLimits
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.kernel.withLockValue
import pro.ikili.kibicomputers.core.runtime.FakeModule
import pro.ikili.kibicomputers.core.runtime.WasmMemory
import java.time.Duration
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** WASI p1 の `poll_oneoff`（#72）。ダミーのモジュールから import を直接呼び、メモリの中身で確かめる。時計は手で進める */
class WasiP1PollTest {
    private val limit = Duration.ofSeconds(30)
    private val ms = 1_000_000L

    private val inPtr = 1_000
    private val outPtr = 8_000
    private val neventsPtr = 16_000

    /** 結果の event */
    private data class Ev(val userdata: Long, val error: Int, val type: Int, val nbytes: Long = 0, val flags: Int = 0)

    private class Env {
        val clock = ManualClock()
        val timers = ManualTimerService(clock)
        val h = Harness(timers = timers)
        val env: MachineEnvironment = MachineEnvironment.builder().clock(clock).build()
        val m: Machine = h.machine(fuelPerTick = 1_000_000, environment = env)

        /** 本体が次に待つ段の番号（本体が待つ前に上げる） */
        val stage = AtomicInteger()

        /** 本体が段 [n] で待つまで進める */
        fun awaitWaiting(pid: Int, n: Int) {
            h.tickUntilTrue("段 $n で待たない") { stage.get() >= n && m.stateOf(pid) == ProcessState.WAITING_EVENT }
        }

        fun finish(pid: Int) = h.tickUntilTrue("終わらない") { m.stateOf(pid).isTerminal }
    }

    private fun env(block: (Env) -> Unit) {
        val e = Env()
        e.h.use { block(e) }
    }

    private fun module(): FakeModule {
        val type = WasiP1.typeOf("poll_oneoff")!!
        return FakeModule.builder().fuelMetered().memory(1)
            .import(P1.MODULE, "poll_oneoff", type)
            .export("poll_oneoff", type) { c, args -> c.callImport(P1.MODULE, "poll_oneoff", *args) }
            .build()
    }

    /** 購読を組み立てて書く */
    private class Subs(private val mem: WasmMemory, private val at: Int) {
        var count = 0
            private set

        private fun base(userdata: Long, tag: Int): Int {
            val p = at + count * P1.SUBSCRIPTION_SIZE
            mem.write(p, ByteArray(P1.SUBSCRIPTION_SIZE))
            mem.writeI64(p, userdata)
            mem.writeU8(p + 8, tag)
            count++
            return p
        }

        fun clock(userdata: Long, id: Int, timeout: Long, flags: Int = 0) = apply {
            val p = base(userdata, P1.EVENTTYPE_CLOCK)
            mem.writeI32(p + 16, id)
            mem.writeI64(p + 24, timeout)
            mem.writeI64(p + 32, 1_000)
            mem.writeU8(p + 40, flags and 0xff)
            mem.writeU8(p + 41, flags ushr 8)
        }

        fun read(userdata: Long, fd: Int) = apply { mem.writeI32(base(userdata, P1.EVENTTYPE_FD_READ) + 16, fd) }

        fun write(userdata: Long, fd: Int) = apply { mem.writeI32(base(userdata, P1.EVENTTYPE_FD_WRITE) + 16, fd) }

        fun raw(userdata: Long, tag: Int) = apply { base(userdata, tag) }
    }

    /** 本体の中で使う口 */
    private inner class Guest(val ctx: ProcessContext, val mem: WasmMemory) {
        fun subs(): Subs = Subs(mem, inPtr)

        /** 呼んで errno を返す */
        fun call(s: Subs, input: Int = inPtr, output: Int = outPtr, nevents: Int = neventsPtr, n: Int = s.count): Int =
            ctx.instance.call("poll_oneoff", input.toLong(), output.toLong(), n.toLong(), nevents.toLong())[0].toInt()

        /** 呼んで、成功を確かめて結果を返す */
        fun poll(s: Subs): List<Ev> {
            mem.writeI32(neventsPtr, -1)
            assertEquals(0, call(s))
            val n = mem.readI32(neventsPtr)
            return (0 until n).map { i ->
                val p = outPtr + i * P1.EVENT_SIZE
                Ev(
                    mem.readI64(p),
                    mem.readU8(p + 8) or (mem.readU8(p + 9) shl 8),
                    mem.readU8(p + 10),
                    mem.readI64(p + 16),
                    mem.readU8(p + 24) or (mem.readU8(p + 25) shl 8),
                )
            }
        }
    }

    /** p1 の層をつないだプロセスを動かし始める。[body] の中の失敗は [errors] に入れる */
    private fun Env.start(
        options: SpawnOptions = SpawnOptions(),
        blocking: Boolean = true,
        errors: MutableList<Throwable> = Collections.synchronizedList(ArrayList()),
        body: Guest.() -> Unit,
    ): Pair<Int, MutableList<Throwable>> {
        val mod = module()
        val driver = TestDriver(
            if (blocking) ProcessKind.BLOCKING else ProcessKind.WAIT_POINT,
            onConfigure = { ctx, b -> WasiP1.register(ctx, b, mod, blocking) },
        ) { ctx ->
            try {
                Guest(ctx, ctx.instance.memory!!).body()
            } catch (e: ProcessKilledException) {
                throw e
            } catch (t: Throwable) {
                errors += t
            }
            StepResult.Exit(0)
        }
        return m.spawn(h.program(mod), driver, options) to errors
    }

    private fun Env.run(options: SpawnOptions = SpawnOptions(), blocking: Boolean = true, body: Guest.() -> Unit) {
        val (pid, errors) = start(options, blocking, body = body)
        finish(pid)
        errors.firstOrNull()?.let { throw it }
        assertEquals(ProcessState.EXITED, m.stateOf(pid))
    }

    private fun Env.stream(capacity: Int = 64): ByteStream = m.lock.withLockValue { ByteStream(capacity).also { m.kernel.objects.add(it) } }

    private fun Env.write(s: ByteStream, text: String) = m.lock.withLockValue {
        s.write(text.toByteArray(), 0, text.length, m.wakeSink)
        Unit
    }

    /** 一時的なタイマーが残っていない（予定もオブジェクトも） */
    private fun Env.assertNoWaitTimers(handleTimers: Int = 0) {
        var timers = 0
        m.lock.withLockValue { m.kernel.objects.forEachObject { if (it is Timer) timers++ } }
        assertEquals(handleTimers, timers, "一時的なタイマーは閉じる")
    }

    private val clockReady = { ud: Long -> Ev(ud, 0, P1.EVENTTYPE_CLOCK) }

    @Test
    fun `入口の誤りはINVALかFAULTで、何も書かない`() = timeout(limit) {
        env { e ->
            e.run {
                val s = subs().clock(1, P1.CLOCK_MONOTONIC, 0)
                mem.writeI32(neventsPtr, 77)
                assertEquals(Errno.INVAL.code, call(s, n = 0))
                assertEquals(Errno.INVAL.code, call(s, n = WasiP1Poll.MAX_SUBSCRIPTIONS + 1))
                assertEquals(Errno.INVAL.code, call(s, n = -1))
                assertEquals(Errno.FAULT.code, call(s, input = WasmMemory.PAGE_SIZE - 40))
                assertEquals(Errno.FAULT.code, call(s, output = WasmMemory.PAGE_SIZE - 16))
                assertEquals(Errno.FAULT.code, call(s, nevents = WasmMemory.PAGE_SIZE - 2))
                assertEquals(77, mem.readI32(neventsPtr))
                // 知らないタグは、ほかの購読がそろっていても呼び出し全体の INVAL
                val bad = subs().clock(1, P1.CLOCK_MONOTONIC, 0).raw(2, 3)
                assertEquals(Errno.INVAL.code, call(bad))
                assertEquals(77, mem.readI32(neventsPtr))
            }
        }
    }

    @Test
    fun `購読ごとの誤りはエラーのイベントになり、購読の順に返る`() = timeout(limit) {
        env { e ->
            val input = e.stream()
            val opts = SpawnOptions(stdio = StdioSpec(StdioSource.Object(input.id, HandleRights.READ), StdioSource.Closed, StdioSource.Closed))
            e.run(opts) {
                val s = subs()
                    .read(0x1122_3344_5566_7788, 99)
                    .clock(-2, 9, 0)
                    .clock(3, P1.CLOCK_MONOTONIC, 0, flags = 2)
                    .write(4, 0)
                    .read(5, 0) // 空のパイプ: まだ
                assertEquals(
                    listOf(
                        Ev(0x1122_3344_5566_7788, Errno.BADF.code, P1.EVENTTYPE_FD_READ),
                        Ev(-2, Errno.INVAL.code, P1.EVENTTYPE_CLOCK),
                        Ev(3, Errno.INVAL.code, P1.EVENTTYPE_CLOCK),
                        Ev(4, Errno.BADF.code, P1.EVENTTYPE_FD_WRITE),
                    ),
                    poll(s),
                )
            }
        }
    }

    @Test
    fun `パイプは読めるバイトと空きを返し、相手が閉じるとHANGUP`() = timeout(limit) {
        env { e ->
            val input = e.stream()
            val output = e.stream(capacity = 8)
            e.write(input, "abc")
            e.write(output, "xyz")
            val opts = SpawnOptions(
                stdio = StdioSpec(StdioSource.Object(input.id, HandleRights.READ), StdioSource.Object(output.id, HandleRights.WRITE), StdioSource.Closed),
            )
            val closed = java.util.concurrent.CountDownLatch(1)
            val (pid, errors) = e.start(opts) {
                assertEquals(
                    listOf(Ev(1, 0, P1.EVENTTYPE_FD_READ, 3), Ev(2, 0, P1.EVENTTYPE_FD_WRITE, 5)),
                    poll(subs().read(1, 0).write(2, 1)),
                )
                e.stage.set(1)
                closed.await()
                // 読む側の相手（書く側）が閉じた。残りのバイトは nbytes に出る
                assertEquals(listOf(Ev(3, 0, P1.EVENTTYPE_FD_READ, 3, P1.EVENTRWFLAGS_FD_READWRITE_HANGUP)), poll(subs().read(3, 0)))
            }
            e.h.tickUntilTrue { e.stage.get() >= 1 }
            e.m.lock.withLockValue { input.closeWrite(e.m.wakeSink) }
            closed.countDown()
            e.finish(pid)
            errors.firstOrNull()?.let { throw it }
        }
    }

    @Test
    fun `書く側の空きがなければ待ち、読む側が閉じるとHANGUP`() = timeout(limit) {
        env { e ->
            val output = e.stream(capacity = 4)
            e.write(output, "full")
            val opts = SpawnOptions(stdio = StdioSpec(StdioSource.Closed, StdioSource.Object(output.id, HandleRights.WRITE), StdioSource.Closed))
            val (pid, errors) = e.start(opts) {
                e.stage.set(1)
                assertEquals(listOf(Ev(1, 0, P1.EVENTTYPE_FD_WRITE, 0, P1.EVENTRWFLAGS_FD_READWRITE_HANGUP)), poll(subs().write(1, 1)))
            }
            e.awaitWaiting(pid, 1)
            e.m.lock.withLockValue { output.closeRead(e.m.wakeSink) }
            e.finish(pid)
            errors.firstOrNull()?.let { throw it }
            e.assertNoWaitTimers()
        }
    }

    @Test
    fun `ファイルとディレクトリは常にそろい、ファイルの読みは残りのバイト`() = timeout(limit) {
        env { e ->
            val (fileId, dirId) = e.m.lock.withLockValue {
                e.m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
                val f = e.m.vfs.open(VfsPath.parse("/a.txt"), OpenOptions(read = true, write = true, create = true))
                e.m.vfs.write(f, "abcde".toByteArray(), 0, 5)
                f.position = 2
                val d = e.m.vfs.open(VfsPath.ROOT, OpenOptions(read = true))
                e.m.kernel.objects.add(f) to e.m.kernel.objects.add(d)
            }
            val opts = SpawnOptions(
                stdio = StdioSpec(StdioSource.Closed, StdioSource.Closed, StdioSource.Closed),
                fds = mapOf(3 to StdioSource.Object(dirId, HandleRights.READ), 4 to StdioSource.Object(fileId, HandleRights.ALL)),
            )
            e.run(opts) {
                assertEquals(
                    listOf(Ev(1, 0, P1.EVENTTYPE_FD_READ, 3), Ev(2, 0, P1.EVENTTYPE_FD_WRITE, 0), Ev(3, 0, P1.EVENTTYPE_FD_READ, 0)),
                    poll(subs().read(1, 4).write(2, 4).read(3, 3)),
                )
            }
        }
    }

    @Test
    fun `イベントキューのfdはFD_READで待て、受け取る種類のイベントで起き、判定では減らない`() = timeout(limit) {
        env { e ->
            val (pid, errors) = e.start {
                val q = ctx.openEventQueue(EventFilter(setOf("key")))
                assertEquals(listOf(Ev(9, Errno.BADF.code, P1.EVENTTYPE_FD_WRITE)), poll(subs().write(9, q)))
                e.stage.set(1)
                assertEquals(listOf(Ev(1, 0, P1.EVENTTYPE_FD_READ, 1)), poll(subs().read(1, q)))
                assertEquals(1, ctx.takeEvents(q).events.size, "判定では取り出さない")
                // あふれて捨てた知らせだけでもそろう
                ctx.withKernelLock {
                    val key = ctx.eventWaitKey(q)
                    (ctx.kernel.objects[key.objectId] as EventQueue).drop(ctx.wakeSink)
                }
                assertEquals(listOf(Ev(2, 0, P1.EVENTTYPE_FD_READ, 1)), poll(subs().read(2, q)))
                assertEquals(1L, ctx.takeEvents(q).dropped)
            }
            e.awaitWaiting(pid, 1)
            e.m.postEvent(KernelEvent("other"))
            repeat(5) { e.h.scheduler.tick() }
            assertEquals(ProcessState.WAITING_EVENT, e.m.stateOf(pid), "受け取らない種類では起きない")
            e.m.postEvent(KernelEvent("key"))
            e.finish(pid)
            errors.firstOrNull()?.let { throw it }
            assertEquals(ProcessState.EXITED, e.m.stateOf(pid))
        }
    }

    @Test
    fun `タイマーのfdはFD_READで待て、鳴ったら起きて回数は残る`() = timeout(limit) {
        env { e ->
            val (pid, errors) = e.start {
                val t = ctx.createTimer()
                ctx.setTimer(t, 100 * ms)
                e.stage.set(1)
                assertEquals(listOf(Ev(1, 0, P1.EVENTTYPE_FD_READ, 1)), poll(subs().read(1, t)))
                assertEquals(1L, ctx.readTimer(t))
            }
            e.awaitWaiting(pid, 1)
            e.timers.advance(100 * ms)
            e.finish(pid)
            errors.firstOrNull()?.let { throw it }
        }
    }

    @Test
    fun `相対の時計は期限まで待ち、早い期限だけがそろい、一時的なタイマーは閉じる`() = timeout(limit) {
        env { e ->
            val (pid, errors) = e.start {
                e.stage.set(1)
                assertEquals(listOf(clockReady(1)), poll(subs().clock(1, P1.CLOCK_MONOTONIC, 100 * ms).clock(2, P1.CLOCK_REALTIME, 200 * ms)))
                e.stage.set(2)
                assertEquals(listOf(clockReady(3)), poll(subs().clock(3, P1.CLOCK_PROCESS_CPUTIME, 50 * ms)))
            }
            e.awaitWaiting(pid, 1)
            assertEquals(1, e.m.lock.withLockValue { e.m.timers.scheduledCount }, "時計の購読が2つでもタイマーは1つ")
            e.timers.advance(99 * ms)
            assertEquals(ProcessState.WAITING_EVENT, e.m.stateOf(pid))
            e.timers.advance(1 * ms)
            e.awaitWaiting(pid, 2)
            e.timers.advance(50 * ms)
            e.finish(pid)
            errors.firstOrNull()?.let { throw it }
            assertEquals(0, e.timers.pending)
            e.assertNoWaitTimers()
        }
    }

    @Test
    fun `絶対の時計はMONOTONICとREALTIMEで写し、REALTIMEは入った後の実時間の飛びを追わない`() = timeout(limit) {
        env { e ->
            val (pid, errors) = e.start {
                val mono = ctx.monotonicNanos()
                e.stage.set(1)
                assertEquals(listOf(clockReady(1)), poll(subs().clock(1, P1.CLOCK_MONOTONIC, mono + 30 * ms, P1.SUBCLOCKFLAGS_ABSTIME)))
                val real = ctx.environment.clock.realtimeNanos()
                e.stage.set(2)
                assertEquals(listOf(clockReady(2)), poll(subs().clock(2, P1.CLOCK_REALTIME, real + 50 * ms, P1.SUBCLOCKFLAGS_ABSTIME)))
                // 過去の時刻と 0 はすぐそろう。u64 の最大は飽和して待ち続ける
                assertEquals(
                    listOf(clockReady(4), clockReady(5), clockReady(6)),
                    poll(
                        subs().clock(3, P1.CLOCK_MONOTONIC, -1L)
                            .clock(4, P1.CLOCK_MONOTONIC, 0, P1.SUBCLOCKFLAGS_ABSTIME)
                            .clock(5, P1.CLOCK_REALTIME, 1, P1.SUBCLOCKFLAGS_ABSTIME)
                            .clock(6, P1.CLOCK_THREAD_CPUTIME, 0, P1.SUBCLOCKFLAGS_ABSTIME)
                            .clock(7, P1.CLOCK_REALTIME, -1L, P1.SUBCLOCKFLAGS_ABSTIME),
                    ),
                )
            }
            e.awaitWaiting(pid, 1)
            e.timers.advance(30 * ms)
            e.awaitWaiting(pid, 2)
            // 実時間だけが 1 時間進む（時刻合わせ）。待ちは入った時点の写しのまま
            e.clock.realtime.addAndGet(3_600_000 * ms)
            e.timers.runDue()
            repeat(3) { e.h.scheduler.tick() }
            assertEquals(ProcessState.WAITING_EVENT, e.m.stateOf(pid))
            e.timers.advance(49 * ms)
            assertEquals(ProcessState.WAITING_EVENT, e.m.stateOf(pid))
            e.timers.advance(1 * ms)
            e.finish(pid)
            errors.firstOrNull()?.let { throw it }
            e.assertNoWaitTimers()
        }
    }

    @Test
    fun `fdと時計を一緒に待つと、先にそろった方だけが返る`() = timeout(limit) {
        env { e ->
            val input = e.stream()
            val opts = SpawnOptions(stdio = StdioSpec(StdioSource.Object(input.id, HandleRights.READ), StdioSource.Closed, StdioSource.Closed))
            val (pid, errors) = e.start(opts) {
                e.stage.set(1)
                assertEquals(listOf(clockReady(2)), poll(subs().read(1, 0).clock(2, P1.CLOCK_MONOTONIC, 100 * ms)))
                e.stage.set(2)
                assertEquals(listOf(Ev(3, 0, P1.EVENTTYPE_FD_READ, 2)), poll(subs().read(3, 0).clock(4, P1.CLOCK_MONOTONIC, 1_000 * ms)))
            }
            e.awaitWaiting(pid, 1)
            e.timers.advance(100 * ms)
            e.awaitWaiting(pid, 2)
            e.write(input, "hi")
            e.finish(pid)
            errors.firstOrNull()?.let { throw it }
            assertEquals(0, e.timers.pending, "fd で返っても時計のタイマーは取り消す")
            e.assertNoWaitTimers()
        }
    }

    @Test
    fun `待っている間に止めるとKILLEDになり、一時的なタイマーは閉じる`() = timeout(limit) {
        env { e ->
            val (pid, _) = e.start {
                e.stage.set(1)
                poll(subs().clock(1, P1.CLOCK_MONOTONIC, 60_000 * ms))
            }
            e.awaitWaiting(pid, 1)
            assertEquals(1, e.timers.pending)
            e.m.stop()
            e.finish(pid)
            assertEquals(ProcessState.KILLED, e.m.stateOf(pid))
            assertEquals(0, e.timers.pending)
            e.assertNoWaitTimers()
        }
    }

    @Test
    fun `動いているタイマーが上限なら、時計で待つ呼び出しはAGAIN`() = timeout(limit) {
        env { e ->
            e.run {
                repeat(TimerLimits.MAX_ARMED_TIMERS) { ctx.setTimer(ctx.createTimer(), 1_000 * ms) }
                assertEquals(Errno.AGAIN.code, call(subs().clock(1, P1.CLOCK_MONOTONIC, 100 * ms)))
                // 待たずにそろうなら、タイマーは要らない
                assertEquals(listOf(clockReady(2)), poll(subs().clock(2, P1.CLOCK_MONOTONIC, 0)))
            }
            e.assertNoWaitTimers()
        }
    }

    @Test
    fun `WAIT_POINTのプロセスでは、そろっていなければ待たずにAGAIN`() = timeout(limit) {
        env { e ->
            val input = e.stream()
            val opts = SpawnOptions(stdio = StdioSpec(StdioSource.Object(input.id, HandleRights.READ), StdioSource.Closed, StdioSource.Closed))
            e.run(opts, blocking = false) {
                mem.writeI32(neventsPtr, 77)
                assertEquals(Errno.AGAIN.code, call(subs().read(1, 0).clock(2, P1.CLOCK_MONOTONIC, 100 * ms)))
                assertEquals(77, mem.readI32(neventsPtr))
                assertEquals(listOf(clockReady(3)), poll(subs().read(4, 0).clock(3, P1.CLOCK_MONOTONIC, 0)))
            }
            e.assertNoWaitTimers()
        }
    }

    @Test
    fun `燃料は購読の数に比例してかかる`() = timeout(limit) {
        env { e ->
            e.run {
                fun cost(s: Subs): Long {
                    val before = ctx.fuelHolder.fuel
                    poll(s)
                    return before - ctx.fuelHolder.fuel
                }
                val one = cost(subs().clock(1, P1.CLOCK_MONOTONIC, 0))
                val three = cost(subs().clock(1, P1.CLOCK_MONOTONIC, 0).clock(2, P1.CLOCK_MONOTONIC, 0).clock(3, P1.CLOCK_MONOTONIC, 0))
                assertTrue(one >= WasiP1Fd.CALL_FUEL + WasiP1Poll.SUBSCRIPTION_FUEL, "1つ: $one")
                assertEquals(2 * WasiP1Poll.SUBSCRIPTION_FUEL, three - one)
            }
        }
    }
}
