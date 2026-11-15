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
 * スレッドを持つ燃料待ちは、起こす側が [reserve] で先に位置を取る（#742、#737）。起こされたスレッドが [acquire] に着く前でも、
 * その位置で待っている者に数え（[waiting]）、順番が来たら渡しておく。スレッドは [acquire] で自分の予約を受け取る。
 * 着かずに終わるスレッドは [cancelReservation] で外す（渡した後なら次へ渡す）。
 *
 * 中のロックは末端のロックで、持ったまま他のロックを取らない（カーネルロックの中から呼んでよい）。
 * [onThreadlessGranted] はこのロックの中で呼ぶので、ブロックしてはいけない（許可の待ち行列に並べるだけ。#350）。
 * [onThreadlessRevoked] は、渡した後でまだ動いていない待ち手を外すときに、同じくロックの中で呼ぶ（許可の待ち行列から外す）。
 */
internal class MachineCpu(
    private val onThreadlessGranted: (ProcessRuntime) -> Unit,
    private val onThreadlessRevoked: (ProcessRuntime) -> Unit = {},
) {
    private class Node(val rt: ProcessRuntime, val condition: Condition?) {
        var granted = false
    }

    private val lock = ReentrantLock()
    private val queue = ArrayDeque<Node>()
    private var owner: ProcessRuntime? = null

    /** 予約したが、スレッドがまだ [acquire] で受け取っていない待ち手（渡した後のものも含む） */
    private val reserved = HashMap<ProcessRuntime, Node>()

    /** 今の持ち主（テスト用） */
    val currentOwner: ProcessRuntime? get() = lock.withLockValue { owner }

    /** 待っている数（スライスを使い切ったときに、譲るかを決めるのに使う。#47） */
    val waiting: Int get() = lock.withLockValue { queue.size }

    /**
     * スレッドで CPU を取る。取れるまで止まる。割り込まれたら [InterruptedException]（予約していたら、外すのは呼んだ側。[cancelReservation]）。
     * [reserve] で予約していれば、その位置で受け取る（もう渡されていれば待たない）
     */
    @Throws(InterruptedException::class)
    fun acquire(rt: ProcessRuntime) {
        lock.lockInterruptibly()
        try {
            val node = reserved.remove(rt) ?: run {
                if (owner == null && queue.isEmpty()) {
                    owner = rt
                    return
                }
                Node(rt, lock.newCondition()).also { queue.addLast(it) }
            }
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

    /**
     * スレッドを持つ待ち手の位置を、そのスレッドの代わりに取る（燃料待ちから起こすとき。カーネルロックの中から呼ぶ）。
     * 空いていればすぐ渡しておく。予約済みなら何もしない。スレッドは次の [acquire] で受け取るか、[cancelReservation] で外す
     */
    fun reserve(rt: ProcessRuntime) {
        lock.lock()
        try {
            if (reserved.containsKey(rt)) return
            val node = Node(rt, lock.newCondition())
            reserved[rt] = node
            if (owner == null && queue.isEmpty()) {
                owner = rt
                node.granted = true
            } else {
                queue.addLast(node)
            }
        } finally {
            lock.unlock()
        }
    }

    /** [reserve] した位置を外す（スレッドが [acquire] に着かずに終わるとき）。もう渡されていれば次へ渡す。予約がなければ何もしない */
    fun cancelReservation(rt: ProcessRuntime) {
        lock.lock()
        try {
            val node = reserved.remove(rt) ?: return
            if (node.granted) {
                check(owner === rt) { "予約で渡した CPU の持ち主が違う: pid ${rt.pid}" }
                handOffLocked()
            } else {
                queue.remove(node)
            }
        } finally {
            lock.unlock()
        }
    }

    /** 予約があるか（テスト用） */
    fun hasReservation(rt: ProcessRuntime): Boolean = lock.withLockValue { reserved.containsKey(rt) }

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
            if (owner === rt) {
                onThreadlessRevoked(rt)
                handOffLocked()
            }
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
