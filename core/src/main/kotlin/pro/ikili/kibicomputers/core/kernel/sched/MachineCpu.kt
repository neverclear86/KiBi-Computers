package pro.ikili.kibicomputers.core.kernel.sched

import pro.ikili.kibicomputers.core.kernel.process.ProcessRuntime
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock

/**
 * マシンの CPU。マシンの中で WASM を実行できるのは同時に1プロセスで、FIFO で手渡す。
 *
 * 待ち手は「止まっているスレッド」（[acquire]）と「スレッドを持たない待機点型のプロセス」（[enqueueThreadless]）の2種類。
 * 返すときに先頭へ渡す（スレッドなら起こし、スレッドを持たないものなら [onThreadlessGranted] でワーカーに渡す）。
 *
 * 中のロックは末端のロックで、持ったまま他のロックを取らない（カーネルロックの中から呼んでよい）。
 * [onThreadlessGranted] はこのロックの中で呼ぶので、ブロックしてはいけない（ワーカーへの `execute` だけ）。
 */
internal class MachineCpu(private val onThreadlessGranted: (ProcessRuntime) -> Unit) {
    private class Node(val rt: ProcessRuntime, val condition: Condition?) {
        var granted = false
    }

    private val lock = ReentrantLock()
    private val queue = ArrayDeque<Node>()
    private var owner: ProcessRuntime? = null

    /** 今の持ち主（テスト用） */
    val currentOwner: ProcessRuntime? get() = lock.withLockValue { owner }

    /** 待っている数（テスト用） */
    val waiting: Int get() = lock.withLockValue { queue.size }

    /** スレッドで CPU を取る。取れるまで止まる。割り込まれたら [InterruptedException] */
    @Throws(InterruptedException::class)
    fun acquire(rt: ProcessRuntime) {
        lock.lockInterruptibly()
        try {
            if (owner == null && queue.isEmpty()) {
                owner = rt
                return
            }
            val node = Node(rt, lock.newCondition())
            queue.addLast(node)
            try {
                while (!node.granted) node.condition!!.await()
            } catch (e: InterruptedException) {
                // 渡された直後に割り込まれたら、次へ渡し直す
                if (node.granted) handOffLocked() else queue.remove(node)
                throw e
            }
        } finally {
            lock.unlock()
        }
    }

    /** CPU を返して、次の待ち手へ渡す */
    fun release(rt: ProcessRuntime) {
        lock.lock()
        try {
            check(owner === rt) { "CPU を持っていないプロセスが返そうとした: pid ${rt.pid}" }
            handOffLocked()
        } finally {
            lock.unlock()
        }
    }

    /** スレッドを持たない待ち手として並ぶ。空いていればすぐ渡す */
    fun enqueueThreadless(rt: ProcessRuntime) {
        lock.lock()
        try {
            if (owner === rt || queue.any { it.rt === rt }) return
            if (owner == null && queue.isEmpty()) {
                owner = rt
                onThreadlessGranted(rt)
            } else {
                queue.addLast(Node(rt, null))
            }
        } finally {
            lock.unlock()
        }
    }

    /** スレッドを持たない待ち手を外す（止めたとき）。渡した直後でまだ動いていなければ、次へ渡す */
    fun removeThreadless(rt: ProcessRuntime) {
        lock.lock()
        try {
            queue.removeAll { it.rt === rt && it.condition == null }
            if (owner === rt) handOffLocked()
        } finally {
            lock.unlock()
        }
    }

    private fun handOffLocked() {
        val next = queue.removeFirstOrNull()
        if (next == null) {
            owner = null
            return
        }
        owner = next.rt
        next.granted = true
        if (next.condition != null) next.condition.signal() else onThreadlessGranted(next.rt)
    }
}

internal inline fun <T> ReentrantLock.withLockValue(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
