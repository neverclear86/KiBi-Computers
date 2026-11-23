package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessRuntime
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.StackHeadroom
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.process.crashReason
import pro.ikili.kibicomputers.core.runtime.FakeContext
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.runtime.FakeModule
import pro.ikili.kibicomputers.core.runtime.FakeInstance
import pro.ikili.kibicomputers.core.runtime.WasmFunctionType
import pro.ikili.kibicomputers.core.runtime.WasmValueType
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * ゲストの深い再帰でスタックが尽きる位置が、カーネルのロック（マシンのロック・CPU・許可）の途中に落ちても、
 * ロック・CPU・許可が壊れないことを確かめる（#828）。
 *
 * スタックを 256 KiB に縮めたカーネルのスレッドで、1 段ごとにホストの関数（`ctx.hostFunction` の包み）か燃料切れの処理を呼ぶ再帰を、
 * 尽きる位置をずらしながら多数回動かす。毎回の後に、ほかのスレッド（テストのスレッド）からマシンのロックが取れること、
 * 同じ台と別の台のプロセスが進み続けることを確かめ、最後に止めて CPU と許可が元に戻ったことを確かめる。
 * 同じワーカー・同じ種類の専用スレッドで何度もスタックを使い切らせる（2 回目以降に JVM ごと落ちないことも見る）
 */
class StackExhaustionLockTest {
    /**
     * 1 つの掃引の上限（止まったときの最後の砦）。止まったことは、待ちごとの見分け（[awaitOrClassify]）が様子を付けて先に落とす
     * （tick は [Ticker] の別のスレッドで回すので、tick の中でロックが残って止まる形でも、テストのスレッドは止まらずに見分けに届く）。
     * 単独では 10〜20 秒で終わるが、CPU を取り合う負荷の下では 1 回あたり数秒になることがある（#868 で 60 回に約 210 秒を測った）
     */
    private val limit = Duration.ofSeconds(300)

    /** 1 段ごとの呼び方 */
    private enum class Pattern {
        HOST_EVERY_FRAME,
        FUEL_EVERY_FRAME,
        MIXED,

        /**
         * 再帰を `callGuest` の中で動かし、1 段ごとに止まりうる関数の中の燃料切れ（`onFuelExhaustedUnwindable`）とホストの関数を交互に呼ぶ。
         * ホストの関数の中から呼んだゲストの中は巻き戻せないので、`onFuelExhaustedUnwindable` → `onFuelExhausted`（スレッドを持ったまま待つ）の道を通る
         */
        UNWINDABLE_FUEL_IN_GUEST_CALL,

        /**
         * ホストの関数の再入: 1 段ごとにホストの関数 `g` を呼び、`g` の本体が `callGuest` の中でゲスト役の次の段を呼ぶ
         * （ホスト → ゲスト → ホスト …と入れ子で深くなる）。3 段に 1 回、燃料切れの処理も呼ぶ
         */
        NESTED_HOST,
    }

    /**
     * ホストの関数の本体がすること。包みの確かめ（[StackHeadroom.HOST_CALL_FRAMES]）が足りるかは、本体の奥でロックを取る道
     * （CPU・許可を返して取り直す、条件変数で待つ、カーネルロックの中で VFS を引く）が、確かめた余裕の中に収まるかで決まる
     */
    private enum class Body {
        /** 何もしない（包みのロックの区間と燃料切れの処理だけを確かめる） */
        EMPTY,

        /** 完了済みの future を待つ（`awaitHost`。`releasePermitAndCpu` → `acquireCpu` → CPU・許可の取り直し。ブロッキング型だけ） */
        AWAIT_HOST,

        /**
         * まだ読めないストリームを `block` で待つ（`releasePermitAndCpu` → 条件変数の待ち → `acquireCpu` の `precheck`。ブロッキング型だけ）。
         * テストのスレッドが、待ちに入ったのを見て 1 バイト書いて起こし、本体は読んでから戻る
         */
        BLOCK,

        /** カーネルロックの中で VFS を引く（`withKernelLock` → `Vfs.stat`） */
        KERNEL_VFS,
    }

    /** 1 回分の深い再帰で、ホストの関数の本体が使うもの */
    private class Round(val stream: ByteStream?) {
        val context = AtomicReference<ProcessContext>()
    }

    private fun runBody(ctx: ProcessContext, body: Body, round: Round) {
        when (body) {
            Body.EMPTY -> Unit
            Body.AWAIT_HOST -> ctx.awaitHost(CompletableFuture.completedFuture(Unit))
            Body.BLOCK -> {
                val stream = round.stream!!
                ctx.block(listOf(WaitKey(stream.id, WaitCondition.READABLE)))
                ctx.withKernelLock { stream.read(ByteArray(1), 0, 1, ctx.wakeSink) }
            }
            Body.KERNEL_VFS -> ctx.withKernelLock { ctx.vfs.stat(VfsPath.parse("/")) }
        }
    }

    /** 再帰で尽きた位置（SOE の一番上のフレーム）がロックの中だった回数を数えるための記録 */
    private class Tally {
        val crashed = AtomicInteger()
        val inLocks = AtomicInteger()
        val inProbe = AtomicInteger()
    }

    /** 尽きる位置をずらすための詰め物（[pad] 段だけ積んでから [body] を呼ぶ） */
    private fun padThen(pad: Int, body: () -> Long): Long = if (pad <= 0) body() else padThen(pad - 1, body) + 1

    private fun recurse(ctx: FakeContext, pattern: Pattern, n: Int): Long {
        when (pattern) {
            Pattern.HOST_EVERY_FRAME -> ctx.callImport("env", "h")
            Pattern.FUEL_EVERY_FRAME -> ctx.callFuelHandler(0)
            Pattern.MIXED -> if (n % 3 == 0) ctx.callFuelHandler(0) else ctx.callImport("env", "h")
            Pattern.UNWINDABLE_FUEL_IN_GUEST_CALL -> if (n % 2 == 0) ctx.callUnwindableFuelHandler(0) else ctx.callImport("env", "h")
            Pattern.NESTED_HOST -> {
                if (n % 3 == 0) ctx.callFuelHandler(0)
                // 戻らない（`g` の本体が、callGuest の中で次の段を呼び続ける）
                ctx.callImport("env", "g", n.toLong())
            }
        }
        return recurse(ctx, pattern, n + 1) + n
    }

    private val i64ToVoid = WasmFunctionType(listOf(WasmValueType.I64), emptyList())

    private fun victim(pattern: Pattern, pad: Int, round: Round): FakeModule = FakeModule.builder()
        .fuelMetered()
        .import("env", "h", VOID)
        .import("env", "g", i64ToVoid)
        .export("run", VOID) { ctx, _ ->
            if (pattern == Pattern.UNWINDABLE_FUEL_IN_GUEST_CALL) {
                round.context.get().callGuest { padThen(pad) { recurse(ctx, pattern, 0) } }
            } else {
                padThen(pad) { recurse(ctx, pattern, 0) }
            }
            LongArray(0)
        }
        .build()

    private val basePatterns = listOf(Pattern.HOST_EVERY_FRAME, Pattern.FUEL_EVERY_FRAME, Pattern.MIXED)

    private fun sweep(
        kind: ProcessKind,
        rounds: Int,
        body: Body = Body.EMPTY,
        patterns: List<Pattern> = basePatterns,
    ) = timeout(limit) {
        val config = SchedulerConfig.builder().maxConcurrentMachines(2).maxBlockingProcesses(64).threadStackBytes(256L * 1024).build()
        val h = Harness(config)
        val ticker = Ticker(h)
        val tally = Tally()
        val stopWaker = AtomicBoolean(false)
        var waker: Thread? = null
        try {
            val m = h.machine(fuelPerTick = 50_000_000, fuelSlice = 10_000)
            val other = h.machine(fuelPerTick = 50_000_000, fuelSlice = 10_000)
            val sameProgress = AtomicInteger()
            val otherProgress = AtomicInteger()
            val sameSpin = m.spawnRt(h.program(spinForever(sameProgress)), runDriver(kind))
            val otherSpin = other.spawnRt(h.program(spinForever(otherProgress)), runDriver(kind))
            h.tickUntilTrue("spin が動かない") { sameProgress.get() > 0 && otherProgress.get() > 0 }
            val permits = config.maxConcurrentMachines
            // Body.BLOCK: 犠牲のプロセスが block で待ちに入ったら、1 バイト書いて起こす（待ちに入ったのを見てから書く。先に書くと、
            // block がすぐそろう道しか通らない）。
            // 犠牲がいない間（回と回の間・確かめの間）は寝る。犠牲がいる間も、待ちに入ったのをロックの外で見てから、マシンのロックを取って
            // 確かめ直して書く（#868。回るたびにロックを取ると、取り合いでほかのスレッドを遅らせ、空回りでコアを 1 つ使い続ける）
            val current = AtomicReference<Pair<ProcessRuntime, ByteStream>?>()
            if (body == Body.BLOCK) {
                waker = Thread {
                    while (!stopWaker.get()) {
                        val target = current.get()
                        if (target == null) {
                            LockSupport.parkNanos(1_000_000)
                            continue
                        }
                        val (rt, stream) = target
                        if (rt.process.state == ProcessState.WAITING_EVENT) {
                            m.lock.withLockValue {
                                if (rt.process.state == ProcessState.WAITING_EVENT && stream.available == 0) {
                                    stream.write(ByteArray(1), 0, 1, m.wakeSink)
                                }
                            }
                        }
                        Thread.onSpinWait()
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
            }
            for (i in 0 until rounds) {
                val pattern = patterns[i % patterns.size]
                // 尽きる位置を 1 段ずつずらす（詰め物の段数を変える）
                val pad = (i / patterns.size * 7) % 97
                val stream = if (body == Body.BLOCK) m.lock.withLockValue { ByteStream(1).also { m.kernel.objects.add(it) } } else null
                val round = Round(stream)
                val driver = runDriver(kind) { ctx, b ->
                    round.context.set(ctx)
                    b.host(ctx) { c, _, _ -> runBody(c, body, round).let { LongArray(0) } }
                    // NESTED_HOST: ゲスト役の次の段を、ホストの関数の中から callGuest で呼ぶ
                    b.hostFunction(
                        "env",
                        "g",
                        i64ToVoid,
                        ctx.hostFunction { c, caller, args ->
                            c.callGuest { recurse(FakeContext(caller as FakeInstance), pattern, args[0].toInt() + 1) }
                            LongArray(0)
                        },
                    )
                }
                val rt = m.spawnRt(h.program(victim(pattern, pad, round)), driver)
                if (stream != null) {
                    current.set(rt to stream)
                    LockSupport.unpark(waker)
                }
                val where = "$i 回目、$pattern、pad $pad"
                val describe = { diagnose(h, ticker, listOf(m to sameSpin, other to otherSpin), rt, tally) }
                awaitOrClassify(ticker, "深い再帰が終わる（$where）", describe) {
                    rt.process.state.isTerminal || m.state == MachineState.CRASHED
                }
                current.set(null)
                assertEquals(MachineState.RUNNING, m.state, "マシンが壊れた（$i 回目）: ${rt.lastError}")
                assertEquals(ProcessState.CRASHED, rt.process.state, "${rt.lastError}")
                assertEquals("call stack exhausted", crashReason(rt.lastError!!), "${rt.lastError}")
                record(tally, rt)
                // 尽きた位置が JDK のロックの中なら、その回で落とす（最後の集計を待たずに、回・pattern・pad を残す）。
                // 失敗の説明は、落とすときだけ作る（成功する回で `state` を評価しない。中のロックは待ちの上限付きで取る）
                if (soeTop(rt)?.className?.startsWith("java.util.concurrent.locks.") == true) {
                    fail("スタックが JDK のロックの中で尽きた（$where）: ${state(h, m, rt)}")
                }
                // ほかのスレッドから、マシンのロックが取れる（持ったまま死んだスレッドがいない）
                assertTrue(m.lock.tryLock(5, TimeUnit.SECONDS), "マシンのロックが取れない（$where）: ${m.lock}")
                m.lock.unlock()
                // 同じ台と別の台のプロセスが進み続ける（CPU・許可が渡り続ける）
                awaitProgress(ticker, "同じ台のプロセス（$where）", sameProgress, describe)
                awaitProgress(ticker, "別の台のプロセス（$where）", otherProgress, describe)
                // 許可が失われていないことは、毎回は見ない（待ち手の数の確かめは、tick を回さない間に燃料待ちへ移って 0 になるだけで、
                // 許可が 1 つ減っても見つけられない）。許可の数は、止めた後の最後の確かめ（availablePermits が元の数に戻る）で見る
            }
            m.stop()
            other.stop()
            awaitOrClassify(ticker, "マシンが止まる", { diagnose(h, ticker, listOf(m to sameSpin, other to otherSpin), null, tally) }) {
                m.state == MachineState.STOPPED && other.state == MachineState.STOPPED
            }
            // CPU は誰も持っておらず、待ち手もいない。許可はすべて戻った
            eventually { m.cpu.currentOwner == null && m.cpu.waiting == 0 }
            eventually(message = "許可が戻らない: ${h.scheduler.permits.availablePermits()}") { h.scheduler.permits.availablePermits() == permits }
            assertEquals(0, h.scheduler.permits.waiting)
            assertTrue(!m.lock.isLocked && !m.lock.hasQueuedThreads(), "${m.lock}")
        } finally {
            stopWaker.set(true)
            waker?.join(5_000)
            ticker.close()
            println("#828 $kind $body $patterns: CRASHED ${tally.crashed.get()} 回、尽きた位置がロックの中 ${tally.inLocks.get()} 回、入口の確かめ ${tally.inProbe.get()} 回")
            h.close()
        }
        // 尽きた位置が JDK のロックの中だったことはない（入口の余裕の確かめが、ロックを取る前に止める）
        assertEquals(0, tally.inLocks.get(), "スタックが JDK のロックの中で尽きた")
    }

    /** 進み具合を待つ（#868）。[progress] が今より 3 以上進むまで、tick を回して待つ（[awaitOrClassify]） */
    private fun awaitProgress(ticker: Ticker, what: String, progress: AtomicInteger, describe: () -> String) {
        val before = progress.get()
        awaitOrClassify(ticker, "$what が進む", describe) { progress.get() > before + 2 }
    }

    /**
     * [condition] がそろうまで tick を回して待つ（#868）。[FIRST_WAIT_MILLIS] でそろわなければ、その時点の様子（[describe]）を取り、
     * さらに [GRACE_MILLIS] 観察を続けて、遅れか停止かを見分ける。
     *
     * - 遅れ（後でそろった）: 様子を標準出力に [SLOWDOWN_MARKER] で始まる行として出して続ける。core の `test` タスクがこの行を
     *   Gradle のコンソール（CI のログ）に写すので、通った回でも見える（`core/build.gradle.kts`）。全体のテストを回した負荷の高い状態
     *   （CPU の取り合い、GC、プロセスごと止まった後の再開）では、正しく動いていても 10 秒を超えることがある。確かめたいのは
     *   「CPU・許可が渡り続ける」ことなので、遅れでは落とさない。許可が失われたことは、最後の許可の数の確かめで見る
     * - 停止（そろわないまま）: 最初と終わりの時点の様子を付けて落とす（失敗の本文は、`test` タスクの `exceptionFormat = FULL` で
     *   CI のログに出る）
     *
     * tick は [ticker] の別のスレッドで回す。tick の中で止まっても（ディスパッチャーの中のロックが残ったなど）、ここは止まらない
     */
    private fun awaitOrClassify(ticker: Ticker, what: String, describe: () -> String, condition: () -> Boolean) {
        val start = System.nanoTime()
        val reached = {
            ticker.tick()
            condition()
        }
        if (pollUntil(FIRST_WAIT_MILLIS, reached)) {
            val elapsed = (System.nanoTime() - start) / 1_000_000
            if (elapsed > 1_000) println("#868 遅い: $what まで $elapsed ms")
            return
        }
        val atFirst = describe()
        val resumed = pollUntil(GRACE_MILLIS, reached)
        val elapsed = (System.nanoTime() - start) / 1_000_000
        if (resumed) {
            // Gradle は標準出力を行ごとに受け取るので、様子の行にもすべて頭を付ける（写すのは頭の付いた行だけ）
            val text = "$what まで $elapsed ms かかった（$FIRST_WAIT_MILLIS ms の時点: $atFirst）"
            println(text.lines().joinToString("\n") { "$SLOWDOWN_MARKER: $it" })
        } else {
            fail("停止: $what が $elapsed ms そろわない（$FIRST_WAIT_MILLIS ms の時点: $atFirst）\n終わりの時点: ${describe()}")
        }
    }

    /** [timeoutMillis] まで [condition] を 1 ms ごとに確かめる。そろえば `true` */
    private fun pollUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (!condition()) {
            if (System.nanoTime() > deadline) return false
            Thread.sleep(1)
        }
        return true
    }

    /**
     * tick（`MachineScheduler.tick`）を、テストのスレッドとは別のスレッドで回す（#868）。tick の中でロックが残って止まっても
     * （tick の先頭の `dispatcher.signal()` は、ディスパッチャーの中のロックを上限なしで取る）、テストのスレッドは止まらず、
     * 待ちごとの見分け（[awaitOrClassify]）が様子（このスレッドのスタックを含む）を付けて落とせる。tick は重ねない
     */
    private class Ticker(private val h: Harness) : AutoCloseable {
        private val threadRef = AtomicReference<Thread>()
        private val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "StackExhaustionLockTest-tick").apply { isDaemon = true }.also { threadRef.set(it) }
        }
        private var pending: Future<*>? = null

        /** tick を 1 回回し、[TICK_WAIT_MILLIS] まで終わりを待つ。前の tick が終わっていなければ、新しく回さずにそれを待つ */
        fun tick() {
            val running = pending?.takeUnless { it.isDone } ?: executor.submit { h.scheduler.tick() }.also { pending = it }
            try {
                running.get(TICK_WAIT_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                // 終わらない tick は、見分けが様子（describe）に出す
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }

        /** tick のスレッドの様子 */
        fun describe(): String {
            val t = threadRef.get() ?: return "（まだ回していない）"
            val busy = if (pending?.isDone == false) " tick の途中" else ""
            return "${t.name} ${t.state}$busy: ${t.stackTrace.take(12).joinToString(" <- ")}"
        }

        /** 止まった tick のスレッドはデーモンなので、待たずに放す */
        override fun close() {
            executor.shutdown()
        }
    }

    /**
     * [lock] を待ちの上限付きで取ってから [read] する（#868。失敗の説明を作る処理が、残ったロックで止まらないように）。
     * 取れなければ、ロックの様子を返す
     */
    private fun peek(lock: ReentrantLock, read: () -> Any?): Any? {
        if (!lock.tryLock(200, TimeUnit.MILLISECONDS)) return "（ロックが取れない: $lock）"
        try {
            return read()
        } finally {
            lock.unlock()
        }
    }

    /**
     * 止まったときの説明（#868）。台ごとの CPU と spin の様子、許可、tick の数、カーネルのスレッドのスタック、ここまでの集計。
     * CPU・許可・ディスパッチャーの中のロックと、マシンのロックは、すべて待ちの上限付きで取る（[peek]。ディスパッチャーの列の長さ
     * `Dispatcher.size` は中のロックを上限なしで取るので、先に同じロックを上限付きで取ってから読む。再入できるロックなので止まらない）
     */
    private fun diagnose(h: Harness, ticker: Ticker, spins: List<Pair<Machine, ProcessRuntime>>, victim: ProcessRuntime?, tally: Tally): String {
        val permits = h.scheduler.permits
        val sb = StringBuilder()
        for ((machine, spin) in spins) {
            val cpu = innerLock(machine.cpu)
            sb.append("\n  台 ${machine.id}: ${machine.state}、lock ${machine.lock}、CPU ${peek(cpu) { machine.cpu.currentOwner?.pid }}")
                .append(" 待ち ${peek(cpu) { machine.cpu.waiting }}")
                .append("、spin pid ${spin.process.pid} ${spin.process.state} holdsCpu ${spin.holdsCpu} holdsPermit ${spin.holdsPermit}")
                .append("、燃料待ち ${peek(machine.lock) { machine.fuelWaiterCount }} 燃料 ${peek(machine.lock) { machine.kernel.fuel }}")
        }
        val dispatcher = h.scheduler.dispatcher
        sb.append("\n  ディスパッチャー: 列 ${peek(innerLock(dispatcher)) { dispatcher.size }} 起こした数 ${h.scheduler.dispatcher.woken.get()}")
        val inner = innerLock(permits)
        sb.append("\n  許可: 空き ${peek(inner) { permits.availablePermits() }} 待ち ${peek(inner) { permits.waiting }}")
            .append(" 取り残し ${peek(inner) { permits.strandedTasks }} 引き渡しの失敗 ${permits.handOffFailures.get()}")
            .append("、許可を取った回数 ${h.scheduler.permitAcquisitionCount}、tick ${h.scheduler.now}")
        if (victim != null) {
            sb.append("\n  犠牲: pid ${victim.process.pid} ${victim.process.state} holdsCpu ${victim.holdsCpu} holdsPermit ${victim.holdsPermit}")
                .append("、SOE: ${soeTop(victim, 8)}")
                .append("、スレッド: ${victim.thread?.let { "${it.name} ${it.state} ${it.stackTrace.take(16).joinToString(" <- ")}" }}")
        }
        sb.append("\n  tick: ${ticker.describe()}")
        sb.append("\n  ここまで: CRASHED ${tally.crashed.get()}、ロックの中 ${tally.inLocks.get()}、入口の確かめ ${tally.inProbe.get()}")
        for ((t, frames) in Thread.getAllStackTraces().entries.sortedBy { it.key.name }) {
            if (!t.name.startsWith("KiBi-")) continue
            sb.append("\n  ${t.name} ${t.state}: ${frames.take(8).joinToString(" <- ")}")
        }
        return sb.toString()
    }

    /** [rt] が尽きた位置（SOE の一番上のフレーム） */
    private fun soeTop(rt: ProcessRuntime): StackTraceElement? = soe(rt)?.stackTrace?.firstOrNull()

    /** [rt] が尽きた位置から [n] フレーム */
    private fun soeTop(rt: ProcessRuntime, n: Int): String? = soe(rt)?.stackTrace?.take(n)?.joinToString(" <- ")

    private fun soe(rt: ProcessRuntime): StackOverflowError? {
        var t: Throwable? = rt.lastError
        while (t != null && t !is StackOverflowError) t = t.cause
        return t as StackOverflowError?
    }

    /** 失敗の説明（落とすときだけ呼ぶ）。CPU・許可の中のロックは、待ちの上限付きで取る（[peek]） */
    private fun state(h: Harness, m: Machine, rt: ProcessRuntime): String {
        val permits = h.scheduler.permits
        val cpu = innerLock(m.cpu)
        val inner = innerLock(permits)
        return "CPU ${peek(cpu) { m.cpu.currentOwner?.pid }} 待ち ${peek(cpu) { m.cpu.waiting }}、" +
            "許可 ${peek(inner) { permits.availablePermits() }} 待ち ${peek(inner) { permits.waiting }}、" +
            "lock ${m.lock}、SOE: ${soeTop(rt, 8)}"
    }

    private fun record(tally: Tally, rt: ProcessRuntime) {
        tally.crashed.incrementAndGet()
        val top = soeTop(rt) ?: return
        if (top.className.startsWith("java.util.concurrent.locks.")) tally.inLocks.incrementAndGet()
        if (top.className == StackHeadroom::class.java.name) tally.inProbe.incrementAndGet()
    }

    @Test
    fun `BLOCKINGで尽きる位置をずらして深い再帰を繰り返しても、ロック・CPU・許可は壊れない`() = sweep(ProcessKind.BLOCKING, rounds = 120)

    @Test
    fun `WAIT_POINTで尽きる位置をずらして深い再帰を繰り返しても、ロック・CPU・許可は壊れない`() = sweep(ProcessKind.WAIT_POINT, rounds = 120)

    @Test
    fun `BLOCKINGでホストの関数の本体がawaitHostでCPUと許可を取り直しても、尽きる位置がロックの中にならない`() =
        sweep(ProcessKind.BLOCKING, rounds = 120, body = Body.AWAIT_HOST)

    @Test
    fun `BLOCKINGでホストの関数の本体がblockで待ってCPUと許可を取り直しても、尽きる位置がロックの中にならない`() =
        sweep(ProcessKind.BLOCKING, rounds = 60, body = Body.BLOCK)

    @Test
    fun `BLOCKINGで止まりうる関数の燃料切れの口とホストの関数の再入で深くなっても、ロック・CPU・許可は壊れない`() =
        sweep(ProcessKind.BLOCKING, rounds = 80, patterns = listOf(Pattern.UNWINDABLE_FUEL_IN_GUEST_CALL, Pattern.NESTED_HOST))

    @Test
    fun `WAIT_POINTで止まりうる関数の燃料切れの口とホストの関数の再入で深くなっても、ロック・CPU・許可は壊れない`() =
        sweep(ProcessKind.WAIT_POINT, rounds = 80, patterns = listOf(Pattern.UNWINDABLE_FUEL_IN_GUEST_CALL, Pattern.NESTED_HOST))

    @Test
    fun `WAIT_POINTでホストの関数の再入の本体がカーネルロックの中でVFSを引いても、尽きる位置がロックの中にならない`() =
        sweep(ProcessKind.WAIT_POINT, rounds = 60, body = Body.KERNEL_VFS, patterns = listOf(Pattern.NESTED_HOST))

    @Test
    fun `BLOCKINGでホストの関数の本体がカーネルロックの中でVFSを引いても、尽きる位置がロックの中にならない`() =
        sweep(ProcessKind.BLOCKING, rounds = 120, body = Body.KERNEL_VFS)

    @Test
    fun `WAIT_POINTでホストの関数の本体がカーネルロックの中でVFSを引いても、尽きる位置がロックの中にならない`() =
        sweep(ProcessKind.WAIT_POINT, rounds = 120, body = Body.KERNEL_VFS)

    /**
     * 安全網: 入口の確かめが漏れてロックが残ったときのまね。ドライバーがマシンのロックを取ったまま返ると、step・専用スレッドの終わりで
     * 放され、マシンが不整合で CRASHED になる（サーバーの tick がそのマシンのロックで止まらない）。ほかの台は動き続ける
     */
    private fun leakedLockIsRecovered(kind: ProcessKind) = timeout(Duration.ofSeconds(30)) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            val other = h.machine(fuelPerTick = 1_000_000)
            val progress = AtomicInteger()
            other.spawnTest(h.program(spinForever(progress)), runDriver())
            val leaker = TestDriver(kind) { _ ->
                m.lock.lock()
                StepResult.Exit(0)
            }
            m.spawnTest(h.program(emptyModule()), leaker)
            h.tickUntilTrue("マシンが CRASHED にならない") { m.state == MachineState.CRASHED }
            assertTrue(m.lock.tryLock(5, TimeUnit.SECONDS), "マシンのロックが放されていない: ${m.lock}")
            m.lock.unlock()
            val before = progress.get()
            h.tickUntilTrue("ほかの台が止まった") { progress.get() > before + 2 }
            assertEquals(MachineState.RUNNING, other.state)
            other.stop()
        }
    }

    @Test
    fun `BLOCKINGの専用スレッドがマシンのロックを持ったまま終わると、放してマシンをCRASHEDにする`() = leakedLockIsRecovered(ProcessKind.BLOCKING)

    @Test
    fun `WAIT_POINTのstepがマシンのロックを持ったまま終わると、放してマシンをCRASHEDにする`() = leakedLockIsRecovered(ProcessKind.WAIT_POINT)

    // ---- 安全網の枝（recoverLeaks）----

    /** [owner]（`MachineCpu`・`PermitPool`）の中のロック。テストで、ロックを持ったまま step を終えるまねをするのに使う */
    private fun innerLock(owner: Any): ReentrantLock =
        owner.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(owner) as ReentrantLock

    /** [t]（step・専用スレッドを動かしていたスレッド）が、`runStep`・`runBlocking`（安全網を含む）を抜けるまで待つ */
    private fun awaitOutOfRunner(t: Thread) =
        eventuallyDescribe(10_000, { "安全網を通り終えない: ${t.stackTrace.take(6).joinToString(" <- ")}" }) {
            t.stackTrace.none { it.methodName == "runStep" || it.methodName == "runBlocking" }
        }

    /**
     * [leak] で step の中の何かを漏らして終えたとき、安全網が戻し、[crashed] ならマシンを CRASHED にする（そうでなければ動き続ける）ことを確かめる。
     * 終わりに、ロック・CPU・許可がそろっていて、同じ台に新しいプロセスを足して終えられ、ほかの台が動き続けることも見る
     */
    private fun safetyNet(kind: ProcessKind, crashed: Boolean, leak: (Machine, ProcessRuntime) -> Unit) = timeout(Duration.ofSeconds(30)) {
        Harness().use { h ->
            val permits = h.scheduler.permits.availablePermits()
            val m = h.machine(fuelPerTick = 1_000_000)
            val other = h.machine(fuelPerTick = 1_000_000)
            val progress = AtomicInteger()
            other.spawnTest(h.program(spinForever(progress)), runDriver())
            val rtFuture = CompletableFuture<ProcessRuntime>()
            val worker = AtomicReference<Thread>()
            val leaker = TestDriver(kind) { _ ->
                worker.set(Thread.currentThread())
                leak(m, rtFuture.get())
                StepResult.Exit(0)
            }
            rtFuture.complete(m.spawnRt(h.program(emptyModule()), leaker))
            h.tickUntilTrue("step が始まらない") { worker.get() != null }
            awaitOutOfRunner(worker.get())
            if (crashed) {
                assertEquals(MachineState.CRASHED, m.state)
            } else {
                assertNotEquals(MachineState.CRASHED, m.state)
            }
            assertTrue(m.lock.tryLock(5, TimeUnit.SECONDS), "マシンのロックが放されていない: ${m.lock}")
            m.lock.unlock()
            for (inner in listOf(innerLock(m.cpu), innerLock(h.scheduler.permits))) {
                assertTrue(inner.tryLock(5, TimeUnit.SECONDS), "中のロックが放されていない: $inner")
                inner.unlock()
            }
            assertEquals(null, m.cpu.currentOwner, "CPU が返っていない")
            eventually(message = "許可が戻らない: ${h.scheduler.permits.availablePermits()}") { h.scheduler.permits.availablePermits() == permits }
            if (!crashed) {
                // 動き続ける台は、新しいプロセスを終えられる
                val pid = m.spawnTest(h.program(emptyModule()), runDriver(kind))
                h.tickUntilTrue("続きのプロセスが終わらない") { m.stateOf(pid).isTerminal }
                assertNotEquals(MachineState.CRASHED, m.state)
            }
            val before = progress.get()
            h.tickUntilTrue("ほかの台が止まった") { progress.get() > before + 2 }
            assertEquals(MachineState.RUNNING, other.state)
            other.stop()
        }
    }

    /** 終わったプロセスが CPU を持ったまま step を終える（`exit` が CPU を返さなかったまね） */
    private val dropCpuMark: (Machine, ProcessRuntime) -> Unit = { _, rt -> rt.holdsCpu = false }

    @Test
    fun `BLOCKINGで終わったプロセスがCPUを持ったままなら、返してマシンをCRASHEDにする`() =
        safetyNet(ProcessKind.BLOCKING, crashed = true, leak = dropCpuMark)

    @Test
    fun `WAIT_POINTで終わったプロセスがCPUを持ったままなら、返してマシンをCRASHEDにする`() =
        safetyNet(ProcessKind.WAIT_POINT, crashed = true, leak = dropCpuMark)

    @Test
    fun `BLOCKINGでCPUの中のロックを持ったまま終わると、放してマシンをCRASHEDにする`() =
        safetyNet(ProcessKind.BLOCKING, crashed = true) { m, _ -> innerLock(m.cpu).lock() }

    @Test
    fun `WAIT_POINTでCPUの中のロックを持ったまま終わると、放してマシンをCRASHEDにする`() =
        safetyNet(ProcessKind.WAIT_POINT, crashed = true) { m, _ -> innerLock(m.cpu).lock() }

    @Test
    fun `BLOCKINGで許可の中のロックだけを持ったまま終わると、放すだけでマシンはCRASHEDにしない`() =
        safetyNet(ProcessKind.BLOCKING, crashed = false) { m, _ -> innerLock(m.scheduler.permits).lock() }

    @Test
    fun `WAIT_POINTで許可の中のロックだけを持ったまま終わると、放すだけでマシンはCRASHEDにしない`() =
        safetyNet(ProcessKind.WAIT_POINT, crashed = false) { m, _ -> innerLock(m.scheduler.permits).lock() }

    /**
     * CPU の待ちの途中で抜けた（`MachineCpu.acquire` の `await` の中でスタックが尽きたなど）まね: まだ渡されていない待ち手を、step の
     * スレッドのものとして残す。step の中で残すと、プロセスが終わって CPU を返したときに、その待ち手に渡る（受け取る者のいない CPU）。
     * 安全網は、その CPU を次へ渡す。待ちの途中ではカーネルの状態を変えていないので、マシンは CRASHED にしない
     */
    @Test
    fun `BLOCKINGでCPUの待ち手を残して終わり、その待ち手にCPUが渡っても、次へ渡してマシンは動き続ける`() =
        safetyNet(ProcessKind.BLOCKING, crashed = false) { m, rt -> m.cpu.leaveWaiterForTest(rt, Thread.currentThread()) }

    @Test
    fun `WAIT_POINTでCPUの待ち手を残して終わり、その待ち手にCPUが渡っても、次へ渡してマシンは動き続ける`() =
        safetyNet(ProcessKind.WAIT_POINT, crashed = false) { m, rt -> m.cpu.leaveWaiterForTest(rt, Thread.currentThread()) }

    /**
     * 渡されないまま残った CPU の待ち手（CPU を返し終えた後、安全網の前に残すまね。`afterUnregister`）。安全網が外さなければ、
     * 持ち主のいない CPU の待ち行列の先頭に残り、後から来たプロセスが CPU を受け取れない（続きのプロセスが終わらない）
     */
    private fun waitingCpuWaiter(kind: ProcessKind) =
        safetyNet(kind, crashed = false) { m, rt ->
            m.scheduler.hooks.afterUnregister = { mm, _ ->
                if (mm === m) {
                    m.scheduler.hooks.afterUnregister = null
                    m.cpu.leaveWaiterForTest(rt, Thread.currentThread())
                }
            }
        }

    @Test
    fun `BLOCKINGで渡されていないCPUの待ち手を残して終わると、外してマシンは動き続ける`() = waitingCpuWaiter(ProcessKind.BLOCKING)

    @Test
    fun `WAIT_POINTで渡されていないCPUの待ち手を残して終わると、外してマシンは動き続ける`() = waitingCpuWaiter(ProcessKind.WAIT_POINT)

    /** 許可の待ちの途中で抜けたまね（渡されていない待ち手）。外すので、後で返された許可が待ち手に渡って減ったままにならない */
    @Test
    fun `BLOCKINGで許可の待ち手を残して終わると、外して許可が減らない`() =
        safetyNet(ProcessKind.BLOCKING, crashed = false) { m, _ -> m.scheduler.permits.leaveWaiterForTest(Thread.currentThread(), granted = false) }

    @Test
    fun `WAIT_POINTで許可の待ち手を残して終わると、外して許可が減らない`() =
        safetyNet(ProcessKind.WAIT_POINT, crashed = false) { m, _ -> m.scheduler.permits.leaveWaiterForTest(Thread.currentThread(), granted = false) }

    /** 許可を渡された後、`acquire` から戻る前に抜けたまね。受け取る者のいない許可を返す */
    @Test
    fun `WAIT_POINTで渡された許可の待ち手を残して終わると、許可を返す`() =
        safetyNet(ProcessKind.WAIT_POINT, crashed = false) { m, _ -> m.scheduler.permits.leaveWaiterForTest(Thread.currentThread(), granted = true) }

    /**
     * 安全網は、マシンのロックを持ったまま（再入して）マシンを CRASHED にしてから放す。放してから CRASHED にするまでの間に、
     * ほかのスレッドがマシンのロックを取って、途中までしか変わっていないかもしれない状態に入らない（#828 のレビュー）。
     * ロックを持ったまま step を終え、テストのスレッドはそのロックを待つ。取れたときには、もう CRASHED になっている
     */
    private fun crashedBeforeLockIsReleased(kind: ProcessKind) = timeout(Duration.ofSeconds(30)) {
        Harness().use { h ->
            val m = h.machine(fuelPerTick = 1_000_000)
            val stepDone = CountDownLatch(1)
            val leaker = TestDriver(kind) { _ ->
                m.lock.lock()
                stepDone.countDown()
                // テストのスレッドがロックを待ち始めるまで、持ったまま
                eventually(10_000, "テストのスレッドがロックを待たない") { m.lock.hasQueuedThreads() }
                StepResult.Exit(0)
            }
            m.spawnTest(h.program(emptyModule()), leaker)
            // step がロックを取ったら tick しない（テストのスレッドだけが、ロックを待つようにする）
            eventually(10_000, "step が始まらない") {
                stepDone.count == 0L || run {
                    h.scheduler.tick()
                    false
                }
            }
            assertTrue(m.lock.tryLock(10, TimeUnit.SECONDS), "マシンのロックが放されていない: ${m.lock}")
            val observed = try {
                m.state
            } finally {
                m.lock.unlock()
            }
            assertEquals(MachineState.CRASHED, observed, "ロックが取れたときに、まだ CRASHED になっていない")
        }
    }

    @Test
    fun `BLOCKINGでマシンのロックを持ったまま終わると、ほかのスレッドがロックを取る前にCRASHEDにする`() =
        crashedBeforeLockIsReleased(ProcessKind.BLOCKING)

    @Test
    fun `WAIT_POINTでマシンのロックを持ったまま終わると、ほかのスレッドがロックを取る前にCRASHEDにする`() =
        crashedBeforeLockIsReleased(ProcessKind.WAIT_POINT)

    /**
     * 誤検知しない: 同じプロセスの次の step が別のワーカーで終わった（`finishLocked` の後・CPU を返す前）間に、前の step のワーカーが
     * 安全網に着いても、マシンを CRASHED にしない（その間は、終わったプロセスが CPU の持ち主なのが正しい）。
     * 前の step のワーカーを登録を外した後で止め、次の step を終えさせて CPU を返す前で止め、そこで前のワーカーを進める。
     * 最後に次の step が CPU を返し、プロセスは EXITED で、CPU・許可がそろう
     */
    @Test
    fun `次のstepが終わってCPUを返す前に前のstepの安全網が走っても、CRASHEDにしない`() = timeout(Duration.ofSeconds(30)) {
        Harness().use { h ->
            val permits = h.scheduler.permits.availablePermits()
            val m = h.machine(fuelPerTick = 1000)
            val stream = m.lock.withLockValue { ByteStream(16).also { m.kernel.objects.add(it) } }
            val driver = TestDriver(
                ProcessKind.WAIT_POINT,
                onResume = { _, _ -> StepResult.Exit(0) },
            ) { StepResult.Wait(listOf(WaitKey(stream.id, WaitCondition.READABLE))) }
            h.scheduler.tick()
            val firstWorker = AtomicReference<Thread>()
            val atFirst = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val atFinish = CountDownLatch(1)
            val releaseFinish = CountDownLatch(1)
            h.scheduler.hooks.afterUnregister = { mm, _ ->
                if (mm === m && firstWorker.compareAndSet(null, Thread.currentThread())) {
                    atFirst.countDown()
                    releaseFirst.await(20, TimeUnit.SECONDS)
                }
            }
            h.scheduler.hooks.afterFinish = { mm, _ ->
                if (mm === m) {
                    atFinish.countDown()
                    releaseFinish.await(20, TimeUnit.SECONDS)
                }
            }
            val rt = m.spawnRt(h.program(emptyModule()), driver)
            assertTrue(atFirst.await(10, TimeUnit.SECONDS), "最初の step が終わらない")
            // 起こすと、次の step が別のワーカーで始まり、終わって（EXITED）CPU を返す前で止まる
            m.lock.withLockValue { stream.write(ByteArray(1), 0, 1, m.wakeSink) }
            assertTrue(atFinish.await(10, TimeUnit.SECONDS), "次の step が終わらない")
            assertEquals(ProcessState.EXITED, rt.process.state)
            assertTrue(m.cpu.currentOwner === rt, "終わったプロセスが CPU の持ち主のはず")
            // 前の step のワーカーが安全網を通る
            releaseFirst.countDown()
            awaitOutOfRunner(firstWorker.get())
            assertNotEquals(MachineState.CRASHED, m.state, "次の step が動いている間を、漏れと見誤った")
            releaseFinish.countDown()
            eventually { m.cpu.currentOwner == null }
            eventually(message = "許可が戻らない") { h.scheduler.permits.availablePermits() == permits }
            assertNotEquals(MachineState.CRASHED, m.state)
            assertEquals(ProcessState.EXITED, rt.process.state)
            m.stop()
        }
    }

    private companion object {
        /** 進み具合の最初の待ち */
        const val FIRST_WAIT_MILLIS = 10_000L

        /** 最初の待ちで進まなかったときに、遅れか停止かを見分けるために観察を続ける長さ */
        const val GRACE_MILLIS = 30_000L

        /** 1 回の tick の終わりを待つ長さ。これを過ぎた tick は、終わるまで新しく回さない */
        const val TICK_WAIT_MILLIS = 100L

        /** 遅れの行の頭（`core/build.gradle.kts` の `test` タスクが、この行を Gradle のコンソールに写す） */
        const val SLOWDOWN_MARKER = "#868 遅れ"
    }
}
