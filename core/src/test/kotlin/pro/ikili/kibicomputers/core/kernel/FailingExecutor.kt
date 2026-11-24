package pro.ikili.kibicomputers.core.kernel

import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger

/** 指定した回数だけ `execute` を失敗させるワーカーの置き場（`unable to create native thread` を再現する。#870、#875） */
internal class FailingExecutor(private val delegate: ExecutorService) : ExecutorService by delegate {
    val failuresLeft = AtomicInteger()

    @Volatile
    var alwaysFail = false

    override fun execute(command: Runnable) {
        if (alwaysFail || failuresLeft.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
            throw OutOfMemoryError("unable to create native thread")
        }
        delegate.execute(command)
    }
}
