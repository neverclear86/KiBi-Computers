package pro.ikili.kibicomputers.core.kernel.wait

import pro.ikili.kibicomputers.core.kernel.process.Process
import pro.ikili.kibicomputers.core.kernel.process.ProcessObject
import pro.ikili.kibicomputers.core.kernel.vfs.OpenFile

/**
 * 何もしない知らせ口（保存の写しの中で使う。#81、ADR 0081）。元のマシンの待ち手を起こさず、タイマーの予定・デバイスの呼び出し・
 * ファイル・子のハンドルの後始末も知らせない（写しの中の後始末が、ホストの資源と元のマシンに届かないように）。
 */
internal object NoopWakeSink : WakeSink {
    override fun wake(key: WaitKey) {}

    override fun wakeObject(objectId: Int) {}

    override fun outputPending(objectId: Int) {}

    override fun timerClosed(objectId: Int) {}

    override fun deviceCallClosed(objectId: Int) {}

    override fun fileClosed(file: OpenFile) {}

    override fun processHandleClosed(holder: Process, obj: ProcessObject) {}
}
