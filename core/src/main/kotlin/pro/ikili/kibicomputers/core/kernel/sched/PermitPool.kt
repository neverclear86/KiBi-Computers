package pro.ikili.kibicomputers.core.kernel.sched

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
 */
internal class PermitPool(permits: Int, private val workers: WorkerPool) {
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

    /** 今のスレッドが中のロックを持っている数（#828。step の入口と出口を比べ、スタックが尽きてロックが残ったのを見つける） */
    val lockHoldCount: Int get() = lock.holdCount

    /** 残った中のロックを 1 つ放す（#828。[lockHoldCount] が入口より多いときに、浅いところで呼ぶ） */
    fun unlockLeaked() = lock.unlock()

    /** 空いている許可の数（テスト用） */
    fun availablePermits(): Int = lock.withLockValue { available }

    /** 許可を待っている数（テスト用） */
    val waiting: Int get() = lock.withLockValue { queue.size }

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
            dispatch?.let { workers.execute(it) }
        }
    }

    /**
     * [thread] が [acquire] の待ちの途中で抜けて（スタックが尽きたなど）残した待ち手を外す（#828。`ProcessRunner` の安全網が、
     * そのスレッドが [acquire] の外にいる浅いところで呼ぶ）。残っていたら `true`。
     *
     * まだ渡されていなければ、待ち行列から外す（残すと、後で返された許可が、起こす相手のいない待ち手に渡って、サーバー全体の許可が
     * 1 つ減ったままになる）。もう渡されていれば、受け取る者のいない許可なので、次の待ち手へ渡す
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
            dispatch?.let { workers.execute(it) }
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
     * ワーカーに渡せずに投げたら（スレッドを作れないとき）、取った許可は返してから投げ直す
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
            // ワーカーのスレッドを作れないときなど。仕事は動かず、取った許可を返す者がいないので、ここで返して投げ直す（#629）
            try {
                release()
            } catch (e: Throwable) {
                if (e !== t) t.addSuppressed(e)
            }
            throw t
        }
    }

    /** まだ許可を受け取っていない、[key] の仕事を外す（止めたとき）。外したら `true` */
    fun remove(key: Any): Boolean = lock.withLockValue { queue.removeAll { it.key === key } }

    /**
     * 許可を1つ返して、次の待ち手へ渡す。
     *
     * @param inline 次がスレッドを持たない仕事なら、今のワーカーの続きにする（ワーカーの中で、step の終わりにだけ使う）
     */
    fun release(inline: Boolean = false) {
        val dispatch = lock.withLockValue { handOffLocked(inline) }
        dispatch?.let { workers.execute(it) }
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
