package pro.ikili.kibicomputers.poc.execthread

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
 * ゲームのワールドの代わり。MC のワールドと同じく、メインスレッド（[owner]）からしか触れない。
 * ほかのスレッドから触ると例外にして、どの方式でもワールド操作がメインスレッドで行われていることを確かめる。
 */
class World(private val owner: Thread) {
    private val cells = IntArray(16)

    /** これまでに行われた操作の数 */
    var ops: Long = 0
        private set

    fun add(key: Int, delta: Int): Int {
        check(Thread.currentThread() === owner) { "ワールドをメインスレッド以外（${Thread.currentThread().name}）から触った" }
        ops++
        cells[key and 15] += delta
        return cells[key and 15]
    }

    fun sum(): Long = cells.sumOf { it.toLong() }
}

/** 伸びる long の配列（ワールド操作の待ち時間を記録する）。書くのは1つのスレッドだけ */
class LongBuffer {
    private var values = LongArray(1024)
    var size: Int = 0
        private set

    fun add(value: Long) {
        if (size == values.size) {
            if (size >= MAX) return
            values = values.copyOf(size * 2)
        }
        values[size++] = value
    }

    fun addAll(other: LongBuffer) {
        for (i in 0 until other.size) add(other.values[i])
    }

    /** [p]（0〜1）の分位点。空なら 0 */
    fun percentile(p: Double): Long {
        if (size == 0) return 0
        val sorted = values.copyOf(size).also { it.sort() }
        return sorted[((size - 1) * p).toInt()]
    }

    fun mean(): Double = if (size == 0) 0.0 else (0 until size).sumOf { values[it] }.toDouble() / size

    private companion object {
        const val MAX = 1 shl 22
    }
}

/** 計算の途中でスケジューラが止めたことを、コンピューターのスレッドに伝える */
class StopComputer : RuntimeException("stopped", null, false, false)

/**
 * WASM を動かす1台のコンピューター。どの方式でも、1台に1つのスレッドを使う。
 *
 * Endive は計算の途中でスタックを保存して抜けられない（継続がない）ので、燃料が尽きて「次の tick まで止める」には、
 * そのコンピューターのスタックを持ったスレッドを止めておくしかない。方式 A でもこれは同じ（違うのは、動いている間メインスレッドが待つかどうか）。
 */
abstract class Computer(val id: Int, private val program: Program, stackSize: Long) : Host {
    val thread: Thread = Thread(null, ::body, "computer-$id", stackSize).apply { isDaemon = true }

    @Volatile var stopping: Boolean = false

    /** 測定中か。測定中だけ統計を取る */
    @Volatile var recording: Boolean = false

    /** 使った燃料の合計。書くのはこのコンピューターのスレッドだけ */
    @Volatile var consumed: Long = 0

    /** ワールド操作を頼んでから結果が返るまでの時間（ナノ秒） */
    val opLatencies = LongBuffer()

    /** 止める前に起きた例外 */
    @Volatile var failure: Throwable? = null

    private fun body() {
        try {
            val instance = Workload.instantiate(this)
            awaitStart()
            program.run(instance)
        } catch (e: Throwable) {
            if (!stopping) failure = e
        } finally {
            onExit()
        }
    }

    protected fun checkStop() {
        if (stopping) throw StopComputer()
    }

    protected fun recordLatency(nanos: Long) {
        if (recording) opLatencies.add(nanos)
    }

    /** 最初に動いてよくなるまで待つ */
    protected abstract fun awaitStart()

    /** プログラムが終わった（例外を含む）ときに呼ばれる */
    protected open fun onExit() {}
}

/** 比べる方式で、メインスレッドから見た口。 */
interface Scheduler {
    val computers: List<Computer>

    /** コンピューターのスレッドを作って動かし始める。メインスレッドで呼ぶ */
    fun start(world: World)

    /** tick の中の処理。メインスレッドで呼ぶ */
    fun tick()

    /** 次の tick（[deadline]、`System.nanoTime()` の値）まで待つ。メインスレッドで呼ぶ */
    fun idle(deadline: Long) = sleepUntil(deadline)

    /** メインスレッドがワールド操作に使った時間の合計（ナノ秒） */
    val mainOpNanos: Long

    /** 1台が 1 tick に使える燃料（上限で削られた後の値） */
    val fuelPerComputer: Long

    fun close() {
        for (c in computers) c.stopping = true
        for (c in computers) {
            c.thread.interrupt()
            LockSupport.unpark(c.thread)
        }
        for (c in computers) c.thread.join(5000)
    }
}

/** [deadline] まで眠る。Windows のタイマーは粗いので、最後の 2 ms は回って待つ */
fun sleepUntil(deadline: Long) {
    while (true) {
        val left = deadline - System.nanoTime()
        if (left <= 0) return
        if (left > 2_000_000) LockSupport.parkNanos(left - 2_000_000) else Thread.onSpinWait()
    }
}

/**
 * 方式 A: メインスレッドの tick の中で、1台ずつ燃料の分だけ実行する。
 *
 * コンピューターのスタックは別のスレッドにあるが、動いている間メインスレッドは待つ（実行の手番をやり取りするだけ）。
 * なので TPS から見ると、メインスレッドで直接実行するのと同じ。ワールド操作は、手番をメインスレッドに戻して行う
 * （MC のワールドはメインスレッド以外から触れない。止まっているメインスレッドに頼むとデッドロックする）。
 *
 * [fuelCapPerTick] があれば、全台で 1 tick に使う燃料をそこまでに抑える（1台あたり `cap / 台数`）。
 */
class LockstepScheduler(
    count: Int,
    private val fuelPerTick: Long,
    private val fuelCapPerTick: Long?,
    program: Program,
    stackSize: Long = 0,
) : Scheduler {
    override val computers: List<C> = (0 until count).map { C(it, program, stackSize) }
    private lateinit var world: World
    override var mainOpNanos: Long = 0
        private set
    override val fuelPerComputer: Long = if (fuelCapPerTick == null) fuelPerTick else minOf(fuelPerTick, maxOf(1, fuelCapPerTick / count))

    inner class C(id: Int, program: Program, stackSize: Long) : Computer(id, program, stackSize) {
        /** 手番。true ならコンピューター、false ならメインスレッド */
        @Volatile var computerTurn: Boolean = false

        /** コンピューターからメインスレッドへの依頼 */
        @Volatile var request: Int = NONE
        var fuelLeft: Long = 0
        var opKey: Int = 0
        var opDelta: Int = 0
        var opResult: Int = 0
        lateinit var main: Thread

        private fun waitTurn() {
            while (!computerTurn && !stopping) LockSupport.park(this)
            checkStop()
        }

        private fun toMain(req: Int) {
            request = req
            computerTurn = false
            LockSupport.unpark(main)
            waitTurn()
        }

        override fun awaitStart() = waitTurn()

        override fun fuel(units: Int) {
            consumed += units
            fuelLeft -= units
            if (fuelLeft <= 0) toMain(YIELD)
        }

        override fun worldAdd(key: Int, delta: Int): Int {
            val start = System.nanoTime()
            opKey = key
            opDelta = delta
            toMain(OP)
            recordLatency(System.nanoTime() - start)
            return opResult
        }

        override fun onExit() {
            request = EXITED
            computerTurn = false
            if (this::main.isInitialized) LockSupport.unpark(main)
        }
    }

    override fun start(world: World) {
        this.world = world
        for (c in computers) c.thread.start()
    }

    override fun tick() {
        val main = Thread.currentThread()
        for (c in computers) {
            if (c.request == EXITED) continue
            c.main = main
            c.fuelLeft = fuelPerComputer
            c.request = NONE
            c.computerTurn = true
            LockSupport.unpark(c.thread)
            while (true) {
                while (c.computerTurn) LockSupport.park(this)
                if (c.request != OP) break
                val start = System.nanoTime()
                c.opResult = world.add(c.opKey, c.opDelta)
                mainOpNanos += System.nanoTime() - start
                c.computerTurn = true
                LockSupport.unpark(c.thread)
            }
        }
    }

    private companion object {
        const val NONE = 0
        const val YIELD = 1
        const val OP = 2
        const val EXITED = 3
    }
}

/** 方式 B でワールド操作をいつ処理するか。 */
enum class OpTiming(val label: String) {
    /** tick の先頭でまとめて処理する */
    TICK_HEAD("tick の先頭"),

    /** メインスレッドの空き時間にすぐ処理する（MC の `server.execute()` は tick の間の待ち時間にタスクを処理する） */
    IMMEDIATE("空き時間にすぐ"),
}

/**
 * 方式 B: 別スレッドで実行し、ワールド操作だけメインスレッドに渡す。
 *
 * 燃料は tick ごとにメインスレッドが配る（使い切ったら次の tick まで止まる）。同時に動く台数は [permits] 個の許可で抑える
 * （サーバーの CPU を使い切らないため）。燃料切れ・ワールド操作の待ち・保存のための一時停止の間は、許可を返す。
 */
class ThreadedScheduler(
    count: Int,
    private val fuelPerTick: Long,
    val permits: Int,
    private val opTiming: OpTiming,
    program: Program,
    stackSize: Long = 0,
) : Scheduler {
    override val computers: List<C> = (0 until count).map { C(it, program, stackSize) }
    private lateinit var world: World
    private val semaphore = Semaphore(permits, true)
    private val queue = LinkedBlockingQueue<C>()
    private val mainOps = AtomicLong()
    override val mainOpNanos: Long get() = mainOps.get()
    override val fuelPerComputer: Long get() = fuelPerTick

    /** 保存のための一時停止を頼まれているか */
    @Volatile var pauseRequested: Boolean = false
    private val started = java.util.concurrent.CountDownLatch(1)

    inner class C(id: Int, program: Program, stackSize: Long) : Computer(id, program, stackSize) {
        val fuel = AtomicLong(0)

        /** WASM を実行中か（許可を持っているか） */
        @Volatile var executing: Boolean = false

        @Volatile var opDone: Boolean = false
        var opKey: Int = 0
        var opDelta: Int = 0
        var opResult: Int = 0

        /** 許可を取る。一時停止中なら、解けるまで待つ（[pauseAll] が返った後に動き出さないよう、取った後にもう一度確かめる） */
        private fun acquire() {
            while (true) {
                while (pauseRequested && !stopping) LockSupport.park(this)
                checkStop()
                semaphore.acquire()
                executing = true
                if (!pauseRequested) return
                release()
            }
        }

        private fun release() {
            executing = false
            semaphore.release()
        }

        override fun awaitStart() {
            started.await()
            acquire()
        }

        override fun fuel(units: Int) {
            consumed += units
            if (fuel.addAndGet(-units.toLong()) <= 0 || pauseRequested) {
                release()
                while ((fuel.get() <= 0 || pauseRequested) && !stopping) LockSupport.park(this)
                checkStop()
                acquire()
            }
        }

        override fun worldAdd(key: Int, delta: Int): Int {
            val start = System.nanoTime()
            opKey = key
            opDelta = delta
            opDone = false
            release()
            queue.add(this)
            while (!opDone && !stopping) LockSupport.park(this)
            checkStop()
            recordLatency(System.nanoTime() - start)
            acquire()
            return opResult
        }

        override fun onExit() {
            if (executing) release()
        }
    }

    override fun start(world: World) {
        this.world = world
        for (c in computers) c.thread.start()
        started.countDown()
    }

    private fun serve(c: C) {
        val start = System.nanoTime()
        c.opResult = world.add(c.opKey, c.opDelta)
        c.opDone = true
        LockSupport.unpark(c.thread)
        mainOps.addAndGet(System.nanoTime() - start)
    }

    override fun tick() {
        if (opTiming == OpTiming.TICK_HEAD) {
            // 先頭の時点で来ていた分だけ（処理した後にすぐ来た次の依頼は、次の tick に回す）
            repeat(queue.size) { serve(queue.poll() ?: return@repeat) }
        }
        for (c in computers) {
            c.fuel.set(fuelPerTick)
            LockSupport.unpark(c.thread)
        }
    }

    override fun idle(deadline: Long) {
        if (opTiming != OpTiming.IMMEDIATE) return sleepUntil(deadline)
        // Windows のタイマーは粗いので、最後の 2 ms は回って待つ（sleepUntil と同じ）
        while (true) {
            val left = deadline - System.nanoTime()
            if (left <= 0) return
            val c = if (left > 2_000_000) queue.poll(left - 2_000_000, TimeUnit.NANOSECONDS) else queue.poll()
            if (c != null) serve(c) else if (left <= 2_000_000) Thread.onSpinWait()
        }
    }

    /** 全台を止める（保存の前に行う）。全台が実行をやめるまでの時間（ナノ秒）を返す */
    fun pauseAll(): Long {
        val start = System.nanoTime()
        pauseRequested = true
        while (computers.any { it.executing }) Thread.onSpinWait()
        return System.nanoTime() - start
    }

    fun resumeAll() {
        pauseRequested = false
        for (c in computers) LockSupport.unpark(c.thread)
    }
}
