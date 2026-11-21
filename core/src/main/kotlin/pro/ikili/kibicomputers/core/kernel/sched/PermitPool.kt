package pro.ikili.kibicomputers.core.kernel.sched

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock

/**
 * サーバー全体の許可（同時に WASM を実行するマシンの数の上限。#350）。公平な FIFO で手渡す。
 *
 * 待ち手は「止まっているスレッド」（[acquire]。ブロッキング型の専用スレッドと、step の途中で CPU を取り直すワーカー）と、
 * 「スレッドを持たない仕事」（[submit]。CPU を受け取った待機点型のプロセスの step）の2種類。
 * スレッドを持たない仕事は、許可を渡してから [workers] に渡す。許可を待つ間は、スレッドを持たない。
 *
 * [release] で `inline` を指定すると、次の仕事を今のワーカーの続き（[WorkerPool.offerContinuation]）にする。
 * step の終わりで返した許可の次の仕事を、新しいスレッドを作らずに、返したワーカーで動かすため。
 *
 * 中のロックは末端のロックで、持ったまま他のロックを取らない（CPU のロック・カーネルロックの中から呼んでよい）。
 * ワーカーへの `execute` は、このロックの外で呼ぶ。
 *
 * 許可を渡し終えた仕事をワーカーに渡せなかったとき（スレッドを作れないときなど。#870）は、呼び手に投げない。
 * その仕事は許可を持ったまま「取り残しの列」に入り、[retryStranded]（毎 tick と、次にワーカーへ渡せたとき）で前から渡し直す。
 * ワーカーが遅れて動き出すのと同じ扱いなので、動き出した step が今の道筋どおりに許可を1回だけ返す
 * （止められていれば WASM を動かさずに返す）。渡し直しは仕事の順（先に取り残したものが先）を保つ。
 * 運用の側へは、取り残しの始まり（スタックトレース付きの WARNING 1本）、続いている間の要約（[summaryIntervalTicks] tick ごとの WARNING）、
 * 復旧（全部渡し直せたときの INFO 1本）を出す。復旧した後にまた起きれば、もう一度スタックトレース付きで出す。
 * 呼び手に投げるのは [submit] の直接の道（許可を取ったその場で渡すとき）だけで、そのときは取った許可を返してから投げる（#629）。
 */
internal class PermitPool(
    permits: Int,
    private val workers: WorkerPool,
    private val summaryIntervalTicks: Long = SUMMARY_INTERVAL_TICKS,
    private val log: System.Logger = LOG,
) {
    private class Node(val task: Runnable?, val key: Any?, val condition: Condition?) {
        var granted = false
    }

    private val lock = ReentrantLock()
    private val queue = ArrayDeque<Node>()
    private var available = permits

    /**
     * [acquire] の中で待っているスレッドと、その待ち手（#828）。[acquire] を正しく抜けるときに外す。
     * 待ちの途中（`await` の中など）でスタックが尽きて抜けると残るので、[abandonWaiter] で見つけて外す
     */
    private val threadWaiters = HashMap<Thread, Node>()

    /**
     * 許可を渡し終えたのに、ワーカーに渡せなかった仕事（#870）。許可を持ったまま並び、[retryStranded] で前から渡し直す。
     * [remove] はここに触らない
     */
    private val stranded = ArrayDeque<Runnable>()

    /** [stranded] の数。取り残しがないときに、ロックを取らずに返すため */
    @Volatile
    private var strandedSize = 0

    /** [retryStranded] を同時に1つだけ動かす（取り残しの順番を保つ） */
    private val retrying = AtomicBoolean()

    /**
     * 取り残しが続いているか。最初の失敗でスタックトレース付きの WARNING を出して立て、取り残しを全部渡し直せたら下ろす
     * （続いている間の失敗は数えるだけ。下ろした後の再発は、もう一度スタックトレース付きで出す）
     */
    private val episode = AtomicBoolean()

    /** 今の取り残しが始まってからの失敗の数（復旧で数え直す） */
    private val episodeFailures = AtomicLong()

    /** 最後に [tick] が見た tick の番号（取り残しが始まった時を、前の要約の時にするため） */
    @Volatile
    private var lastTick = 0L

    /** 取り残しが続いている間の、前の要約（または始まり）の tick */
    private val lastReportTick = AtomicLong()

    /** 今の取り残しが始まった tick */
    @Volatile
    private var episodeStart = 0L

    /** 許可を渡し終えた後に、ワーカーへ渡せなかった回数（数え口。#870。渡し直しの失敗も数える） */
    val handOffFailures: AtomicLong = AtomicLong()

    init {
        workers.continuationFailure = WorkerPool.ContinuationFailure { task, t -> strand(task, t) }
    }

    /** 今のスレッドが中のロックを持っている数（#828。step の入口と出口を比べ、スタックが尽きてロックが残ったのを見つける） */
    val lockHoldCount: Int get() = lock.holdCount

    /** 残った中のロックを 1 つ放す（#828。[lockHoldCount] が入口より多いときに、浅いところで呼ぶ） */
    fun unlockLeaked() = lock.unlock()

    /** 空いている許可の数（テスト用） */
    fun availablePermits(): Int = lock.withLockValue { available }

    /** 許可を待っている数（テスト用） */
    val waiting: Int get() = lock.withLockValue { queue.size }

    /** 許可を持ったまま、ワーカーに渡すのを待っている仕事の数（テスト用。#870） */
    val strandedTasks: Int get() = lock.withLockValue { stranded.size }

    /** 中のロックを持ったまま [block] を動かす（テスト用。#870。割り込まれた待ち手がロックを取り直す前に渡す、の順番を作る） */
    fun <T> withLockForTest(block: () -> T): T = lock.withLockValue(block)

    /** [thread] が中のロックを待っているか（テスト用。#870） */
    fun hasQueuedThreadForTest(thread: Thread): Boolean = lock.hasQueuedThread(thread)

    /** スレッドで許可を取る。取れるまで止まる。割り込まれたら [InterruptedException] */
    @Throws(InterruptedException::class)
    fun acquire() {
        var dispatch: Runnable? = null
        lock.lockInterruptibly()
        try {
            if (available > 0 && queue.isEmpty()) {
                available--
                return
            }
            val thread = Thread.currentThread()
            val node = Node(null, null, lock.newCondition())
            threadWaiters[thread] = node
            queue.addLast(node)
            try {
                while (!node.granted) node.condition!!.await()
            } catch (e: InterruptedException) {
                threadWaiters.remove(thread)
                // 渡された直後に割り込まれたら、次へ渡し直す
                if (node.granted) dispatch = handOffLocked(inline = false) else queue.remove(node)
                throw e
            }
            threadWaiters.remove(thread)
        } finally {
            lock.unlock()
            // 渡せなくても投げない（割り込みの例外を置き換えない。#870）
            dispatch?.let { dispatchGranted(it) }
        }
    }

    /**
     * [thread] が [acquire] の待ちの途中で抜けて（スタックが尽きたなど）残した待ち手を外す（#828。`ProcessRunner` の安全網が、
     * そのスレッドが [acquire] の外にいる浅いところで呼ぶ）。残っていたら `true`。
     *
     * まだ渡されていなければ、待ち行列から外す（残すと、後で返された許可が、起こす相手のいない待ち手に渡って、サーバー全体の許可が
     * 1 つ減ったままになる）。もう渡されていれば、受け取る者のいない許可なので、次の待ち手へ渡す（渡せなくても投げない。#870）
     */
    fun abandonWaiter(thread: Thread): Boolean {
        var dispatch: Runnable? = null
        lock.lock()
        try {
            val node = threadWaiters.remove(thread) ?: return false
            if (node.granted) dispatch = handOffLocked(inline = false) else queue.remove(node)
            return true
        } finally {
            lock.unlock()
            dispatch?.let { dispatchGranted(it) }
        }
    }

    /** [thread] が [acquire] で待っている途中だったまねをする（テスト用。#828）。[granted] なら、許可を 1 つ渡し終えた待ち手にする */
    fun leaveWaiterForTest(thread: Thread, granted: Boolean) {
        lock.withLockValue {
            val node = Node(null, null, lock.newCondition())
            threadWaiters[thread] = node
            if (granted) {
                check(available > 0) { "空いている許可がない" }
                available--
                node.granted = true
            } else {
                queue.addLast(node)
            }
        }
    }

    /**
     * スレッドを持たない仕事 [task] を並べる。許可が空いていれば、すぐワーカーに渡す。
     * [task] は許可を持って動き、自分で [release] する。[key] は [remove] で外すときの目印。
     * すぐ渡すときにワーカーに渡せずに投げたら（スレッドを作れないとき）、取った許可は返してから、同じ例外を投げ直す（#629）。
     * 並べた仕事を後で渡すときに渡せなかったら、投げずに取り残しの列に入れる（#870）
     */
    fun submit(key: Any, task: Runnable) {
        lock.withLockValue {
            if (available > 0 && queue.isEmpty()) {
                available--
            } else {
                queue.addLast(Node(task, key, null))
                return
            }
        }
        try {
            workers.execute(task)
        } catch (t: Throwable) {
            // ワーカーのスレッドを作れないときなど。仕事は動かず、取った許可を返す者がいないので、ここで返して投げ直す（#629）。
            // 返した許可の次の仕事を渡せなくても、release は投げずに取り残しの列へ入れる（#870）
            try {
                release()
            } catch (e: Throwable) {
                if (e !== t) t.addSuppressed(e)
            }
            throw t
        }
        // ワーカーに渡せたので、取り残しがあれば渡し直す
        retryStranded()
    }

    /**
     * まだ許可を受け取っていない、[key] の仕事を外す（止めたとき）。外したら `true`。
     * 取り残しの列（許可を受け取り済み）には触らない。そこにある step は、後で動いたときに止められていると知り、許可を返して終わる
     */
    fun remove(key: Any): Boolean = lock.withLockValue { queue.removeAll { it.key === key } }

    /**
     * 許可を1つ返して、次の待ち手へ渡す。投げない（次の仕事をワーカーに渡せなければ、取り残しの列に入れる。#870）。
     *
     * @param inline 次がスレッドを持たない仕事なら、今のワーカーの続きにする（ワーカーの中で、step の終わりにだけ使う）
     */
    fun release(inline: Boolean = false) {
        val dispatch = lock.withLockValue { handOffLocked(inline) }
        if (dispatch == null || dispatchGranted(dispatch)) retryStranded()
    }

    /**
     * 毎 tick（`MachineScheduler.tick`）に呼ぶ。取り残しを渡し直し（[retryStranded]）、取り残しが続いていれば、
     * [summaryIntervalTicks] tick ごとに要約を WARNING に出す。取り残しがなければ、volatile と原子変数を読むだけ。投げない
     */
    fun tick(now: Long) {
        lastTick = now
        retryStranded()
        if (!episode.get()) return
        val last = lastReportTick.get()
        if (now - last < summaryIntervalTicks || !lastReportTick.compareAndSet(last, now)) return
        logSafely(System.Logger.Level.WARNING, null) {
            "許可を渡した仕事のワーカーへの引き渡しが、取り残しの始まりから ${now - episodeStart} tick 経っても戻らない" +
                "（取り残し $strandedSize 件、この間の失敗 ${episodeFailures.get()} 回、累計 ${handOffFailures.get()} 回）。" +
                "スレッドを作れない状態が続いているとみられる。毎 tick 渡し直している"
        }
    }

    /**
     * 取り残しの列の前から、ワーカーに渡し直す（#870）。[tick] と、ワーカーに渡せた後に呼ぶ。
     * 取り残しがなければ volatile を1回読むだけ。渡せなければ先頭に戻して打ち切る（その場で繰り返さない。次のきっかけを待つ）。
     * 渡し直して列が空になったら、復旧として1回だけ記録する。
     * ほかのスレッドが渡し直している途中なら、何もしない。投げない。中のロックの外で呼ぶ
     */
    fun retryStranded() {
        if (strandedSize == 0) return
        if (!retrying.compareAndSet(false, true)) return
        try {
            var delivered = false
            while (true) {
                val task = lock.withLockValue {
                    stranded.removeFirstOrNull().also { strandedSize = stranded.size }
                }
                if (task == null) {
                    if (delivered) recovered()
                    return
                }
                try {
                    workers.execute(task)
                    delivered = true
                } catch (t: Throwable) {
                    lock.withLockValue {
                        stranded.addFirst(task)
                        strandedSize = stranded.size
                    }
                    noteFailure(t)
                    return
                }
            }
        } finally {
            retrying.set(false)
        }
    }

    /**
     * 許可を渡し終えた [task] をワーカーに渡す（中のロックの外で呼ぶ）。渡せなければ取り残しの列に入れて `false`。投げない（#870）。
     * 呼び手は CPU のロック・カーネルロック（別のマシンのことがある）を持っていることがあるので、その場で動かしたり、
     * プロセスを終わらせたりはしない
     */
    private fun dispatchGranted(task: Runnable): Boolean {
        try {
            workers.execute(task)
            return true
        } catch (t: Throwable) {
            strand(task, t)
            return false
        }
    }

    /** 許可を持ったままワーカーに渡せなかった [task] を、取り残しの列の後ろに入れる。投げない */
    private fun strand(task: Runnable, failure: Throwable) {
        lock.withLockValue {
            stranded.addLast(task)
            strandedSize = stranded.size
        }
        noteFailure(failure)
    }

    /** ワーカーへの引き渡しの失敗を数え、取り残しの始まりなら（最初の失敗か、復旧の後の再発）スタックトレース付きで WARNING に出す */
    private fun noteFailure(failure: Throwable) {
        handOffFailures.incrementAndGet()
        episodeFailures.incrementAndGet()
        if (!episode.compareAndSet(false, true)) return
        episodeStart = lastTick
        lastReportTick.set(lastTick)
        logSafely(System.Logger.Level.WARNING, failure) {
            "許可を渡した仕事をワーカーに渡せない（スレッドを作れないなど）。仕事は許可を持ったまま取り残し、毎 tick 渡し直す。" +
                "続いている間は、この失敗を数えるだけにして、$summaryIntervalTicks tick ごとに要約を出す"
        }
    }

    /** 取り残しを全部渡し直せた。続いていたなら、復旧を INFO に出して、次の失敗をまた最初として扱う */
    private fun recovered() {
        if (strandedSize != 0 || !episode.compareAndSet(true, false)) return
        val failures = episodeFailures.getAndSet(0)
        logSafely(System.Logger.Level.INFO, null) {
            "許可を渡した仕事のワーカーへの引き渡しが復旧した（取り残しを全部渡し直した。この間の失敗 $failures 回、累計 ${handOffFailures.get()} 回）"
        }
    }

    /** ログを出せなくても、取り残しの列には入っている。投げない */
    private inline fun logSafely(level: System.Logger.Level, failure: Throwable?, message: () -> String) {
        try {
            if (failure == null) log.log(level, message()) else log.log(level, message(), failure)
        } catch (_: Throwable) {
        }
    }

    /** 先頭に許可を渡す。ワーカーに渡す仕事があれば返す（ロックの外で渡す） */
    private fun handOffLocked(inline: Boolean): Runnable? {
        val next = queue.removeFirstOrNull()
        if (next == null) {
            available++
            return null
        }
        next.granted = true
        if (next.condition != null) {
            next.condition.signal()
            return null
        }
        val task = next.task!!
        if (inline && workers.offerContinuation(task)) return null
        return task
    }
}

private val LOG: System.Logger = System.getLogger("kibicomputers.kernel")

/** 取り残しが続いているときの要約の間（1分 = 1,200 tick。運用の値。`DeviceFailureLog` の要約と同じ間） */
private const val SUMMARY_INTERVAL_TICKS: Long = 1200
