package pro.ikili.kibicomputers.core.kernel

import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** 指定した回数だけ `execute` を失敗させるワーカーの置き場（`unable to create native thread` を再現する。#870、#875） */
internal class FailingExecutor(private val delegate: ExecutorService) : ExecutorService by delegate {
    val failuresLeft = AtomicInteger()

    @Volatile
    var alwaysFail = false

    /** 次に失敗させる直前に、1度だけ呼ぶ（失敗の間に別のスレッドが動いた形を、決まった順番で作る） */
    private val beforeFailure = AtomicReference<Runnable?>()

    fun onNextFailure(action: Runnable) {
        beforeFailure.set(action)
    }

    override fun execute(command: Runnable) {
        if (alwaysFail || failuresLeft.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
            beforeFailure.getAndSet(null)?.run()
            throw OutOfMemoryError("unable to create native thread")
        }
        delegate.execute(command)
    }
}
