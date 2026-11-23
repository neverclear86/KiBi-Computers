package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.fail
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/*
 * スタックの余裕の確かめ（#828・#858）の 3 つのテスト（`StackExhaustionLockTest`・`ComponentStackExhaustionLockTest`・
 * `P1StackExhaustionLockTest`）が共有する、待ちの見分けと失敗の説明（#868・#888 で `StackExhaustionLockTest` に入れたもの）。
 *
 * 固定の待ち（`eventually`・`Harness.tickUntilTrue` の 10 秒）で落とすと、負荷の下の遅れを停止と見誤る。また、失敗の説明で
 * CPU・許可・マシンのロックを上限なしで読むと、本当にロックが残ったときに説明を作るところで止まり、テスト全体の上限でしか落ちない。
 * どちらも、ここの見分け（[awaitOrClassify]）と上限付きの読み（[peek]）を通す
 */

/** 進み具合の最初の待ち */
internal const val HEADROOM_FIRST_WAIT_MILLIS = 10_000L

/** 最初の待ちで進まなかったときに、遅れか停止かを見分けるために観察を続ける長さ */
internal const val HEADROOM_GRACE_MILLIS = 30_000L

/** 1 回の tick の終わりを待つ長さ。これを過ぎた tick は、終わるまで新しく回さない */
internal const val HEADROOM_TICK_WAIT_MILLIS = 100L

/** 遅れの行の頭（`core/build.gradle.kts` の `test` タスクが、この行を Gradle のコンソールに写す） */
internal const val HEADROOM_SLOWDOWN_MARKER = "#868 遅れ"

/**
 * tick（`MachineScheduler.tick`）を、テストのスレッドとは別のスレッドで回す（#868）。tick の中でロックが残って止まっても
 * （tick の先頭の `dispatcher.signal()` は、ディスパッチャーの中のロックを上限なしで取る）、テストのスレッドは止まらず、
 * 待ちごとの見分け（[awaitOrClassify]）が様子（このスレッドのスタックを含む）を付けて落とせる。tick は重ねない
 */
internal class HeadroomTicker(private val h: Harness) : AutoCloseable {
    private val threadRef = AtomicReference<Thread>()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "StackHeadroom-tick").apply { isDaemon = true }.also { threadRef.set(it) }
    }
    private var pending: Future<*>? = null

    /** tick を 1 回回し、[HEADROOM_TICK_WAIT_MILLIS] まで終わりを待つ。前の tick が終わっていなければ、新しく回さずにそれを待つ */
    fun tick() {
        val running = pending?.takeUnless { it.isDone } ?: executor.submit { h.scheduler.tick() }.also { pending = it }
        try {
            running.get(HEADROOM_TICK_WAIT_MILLIS, TimeUnit.MILLISECONDS)
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
internal fun peek(lock: ReentrantLock, read: () -> Any?): Any? {
    if (!lock.tryLock(200, TimeUnit.MILLISECONDS)) return "（ロックが取れない: $lock）"
    try {
        return read()
    } finally {
        lock.unlock()
    }
}

/** [owner]（`MachineCpu`・`PermitPool`・`Dispatcher`）の中のロック。上限付きで読む（[peek]）のに使う */
internal fun innerLock(owner: Any): ReentrantLock =
    owner.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(owner) as ReentrantLock

/**
 * 台の状態の説明（#868）。CPU・許可の中のロックと、マシンのロックは、すべて待ちの上限付きで取る（[peek]）ので、
 * ロックが残っていても止まらない
 */
internal fun describeMachineState(h: Harness, m: Machine): String {
    val permits = h.scheduler.permits
    val cpu = innerLock(m.cpu)
    val inner = innerLock(permits)
    return "CPU ${peek(cpu) { m.cpu.currentOwner?.pid }} 待ち ${peek(cpu) { m.cpu.waiting }}、" +
        "許可 ${peek(inner) { permits.availablePermits() }} 待ち ${peek(inner) { permits.waiting }}、lock ${m.lock}"
}

/** [m] の CPU を誰も持っておらず、待ち手もいない（中のロックを上限付きで取る。取れなければ偽） */
internal fun cpuIsIdle(m: Machine): Boolean = peek(innerLock(m.cpu)) { m.cpu.currentOwner == null && m.cpu.waiting == 0 } == true

/** 許可が [expected] 個そろい、待ち手がいない（中のロックを上限付きで取る。取れなければ偽） */
internal fun permitsAreBack(h: Harness, expected: Int): Boolean =
    peek(innerLock(h.scheduler.permits)) { h.scheduler.permits.availablePermits() == expected && h.scheduler.permits.waiting == 0 } == true

/** 進み具合を待つ（#868）。[progress] が今より 3 以上進むまで、tick を回して待つ（[awaitOrClassify]） */
internal fun awaitProgress(ticker: HeadroomTicker, what: String, progress: AtomicInteger, describe: () -> String) {
    val before = progress.get()
    awaitOrClassify(ticker, "$what が進む", describe) { progress.get() > before + 2 }
}

/**
 * [condition] がそろうまで tick を回して待つ（#868）。[HEADROOM_FIRST_WAIT_MILLIS]（[stackHeadroomWait] で延びる）でそろわなければ、
 * その時点の様子（[describe]）を取り、さらに [HEADROOM_GRACE_MILLIS] 観察を続けて、遅れか停止かを見分ける。
 *
 * - 遅れ（後でそろった）: 様子を標準出力に [HEADROOM_SLOWDOWN_MARKER] で始まる行として出して続ける。core の `test` タスクがこの行を
 *   Gradle のコンソール（CI のログ）に写すので、通った回でも見える（`core/build.gradle.kts`）。全体のテストを回した負荷の高い状態
 *   （CPU の取り合い、GC、プロセスごと止まった後の再開）では、正しく動いていても 10 秒を超えることがある。確かめたいのは
 *   「CPU・許可が渡り続ける」ことなので、遅れでは落とさない。許可が失われたことは、最後の許可の数の確かめで見る
 * - 停止（そろわないまま）: 最初と終わりの時点の様子を付けて落とす（失敗の本文は、`test` タスクの `exceptionFormat = FULL` で
 *   CI のログに出る）
 *
 * tick は [ticker] の別のスレッドで回す。tick の中で止まっても（ディスパッチャーの中のロックが残ったなど）、ここは止まらない
 */
internal fun awaitOrClassify(ticker: HeadroomTicker, what: String, describe: () -> String, condition: () -> Boolean) {
    val start = System.nanoTime()
    val reached = {
        ticker.tick()
        condition()
    }
    if (pollUntil(stackHeadroomWait(HEADROOM_FIRST_WAIT_MILLIS), reached)) {
        val elapsed = (System.nanoTime() - start) / 1_000_000
        if (elapsed > 1_000) println("#868 遅い: $what まで $elapsed ms")
        return
    }
    val atFirst = describe()
    val resumed = pollUntil(stackHeadroomWait(HEADROOM_GRACE_MILLIS), reached)
    val elapsed = (System.nanoTime() - start) / 1_000_000
    if (resumed) {
        // Gradle は標準出力を行ごとに受け取るので、様子の行にもすべて頭を付ける（写すのは頭の付いた行だけ）
        val text = "$what まで $elapsed ms かかった（$HEADROOM_FIRST_WAIT_MILLIS ms の時点: $atFirst）"
        println(text.lines().joinToString("\n") { "$HEADROOM_SLOWDOWN_MARKER: $it" })
    } else {
        fail("停止: $what が $elapsed ms そろわない（$HEADROOM_FIRST_WAIT_MILLIS ms の時点: $atFirst）\n終わりの時点: ${describe()}")
    }
}

/** [timeoutMillis] まで [condition] を 1 ms ごとに確かめる。そろえば `true` */
internal fun pollUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000
    while (!condition()) {
        if (System.nanoTime() > deadline) return false
        Thread.sleep(1)
    }
    return true
}
