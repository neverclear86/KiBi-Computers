package pro.ikili.kibicomputers.core.display

/**
 * 画面バッファの描画命令の記録（ADR 0031 の方式 B）。[ScreenSyncSender] が、見ているプレイヤーがいる間だけ
 * バッファにつなぐ。クライアントは同じ命令を同じ `core` の描画関数で再生する（[ScreenSyncReceiver]）。
 *
 * 記録が [limit] バイトを超えたら、それ以上は記録せずに捨てる（[overflow]）。そのときは画面全体
 * （キーフレーム）を送るほうが小さいので、命令は要らない。
 */
internal class DrawLog(var limit: Int) {
    val out = SyncWriter()

    /** 記録が [limit] を超えて、命令を捨てたか。 */
    var overflow: Boolean = false
        private set

    val size: Int
        get() = out.size

    inline fun record(block: SyncWriter.() -> Unit) {
        if (overflow) return
        out.block()
        if (out.size > limit) {
            overflow = true
            out.reset()
        }
    }

    fun reset() {
        out.reset()
        overflow = false
    }
}

/** 描画命令の番号。文字画面とピクセル画面で範囲を分ける。 */
internal object SyncOps {
    const val TEXT_SET = 1
    const val TEXT_WRITE = 2
    const val TEXT_FILL = 3
    const val TEXT_COPY = 4
    const val TEXT_RESIZE = 5
    const val TEXT_CURSOR = 6

    const val PIXEL_SET = 16
    const val PIXEL_FILL = 17
    const val PIXEL_COPY = 18
    const val PIXEL_IMAGE = 19
    const val PIXEL_CLEAR = 20
    const val PIXEL_RESIZE = 21

    /** メッセージの種類: 描画命令。 */
    const val KIND_COMMANDS = 0

    /** メッセージの種類: 画面全体（キーフレーム）。 */
    const val KIND_KEYFRAME = 1
}
