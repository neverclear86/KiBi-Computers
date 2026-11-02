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
            val node = Node(null, null, lock.newCondition())
            queue.addLast(node)
            try {
                while (!node.granted) node.condition!!.await()
            } catch (e: InterruptedException) {
                // 渡された直後に割り込まれたら、次へ渡し直す
                if (node.granted) dispatch = handOffLocked(inline = false) else queue.remove(node)
                throw e
            }
        } finally {
            lock.unlock()
            dispatch?.let { workers.execute(it) }
        }
    }

    /**
     * スレッドを持たない仕事 [task] を並べる。許可が空いていれば、すぐワーカーに渡す。
     * [task] は許可を持って動き、自分で [release] する。[key] は [remove] で外すときの目印
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
        workers.execute(task)
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
