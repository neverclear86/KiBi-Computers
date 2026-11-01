package pro.ikili.kibicomputers.core.kernel.wait

/** 待てる条件。種類は後続の Issue で足す（タイマー #64、プロセスの終了 #46、イベントキュー #63 など）。 */
internal enum class WaitCondition {
    /** 読める（読めるバイトがある、または書く側が閉じた） */
    READABLE,

    /** 書ける（空きがある、または読む側が閉じた） */
    WRITABLE,
}

/**
 * 待ちの単位。（オブジェクトID、条件）の組。保存できる値（番号と列挙）だけを持つ。
 *
 * p1 の `poll_oneoff`、p2 の `pollable`、p3 の waitable-set は、どれもこの並びで待つ。
 */
internal data class WaitKey(val objectId: Int, val condition: WaitCondition)

/**
 * 保存の対象の側（[pro.ikili.kibicomputers.core.kernel.obj.KernelObject]）から、実行時の待ち手へ知らせる口。
 *
 * カーネルロックの中で呼ぶ。オブジェクトはこれをフィールドに持たない（操作のたびに引数で受け取る）。
 */
internal interface WakeSink {
    /** [key] の条件がそろったかもしれない */
    fun wake(key: WaitKey)

    /** オブジェクト [objectId] の条件すべてがそろったかもしれない（閉じられたときなど） */
    fun wakeObject(objectId: Int)

    /**
     * オブジェクト [objectId] に、ホストへ配る出力がたまった（待ち手は起こさない。#60）。バイトを積むたびに呼ぶ。
     * 既定は何もしない（[pro.ikili.kibicomputers.core.kernel.Machine] の知らせ口だけが、コンソールの出力を配る印を付ける）
     */
    fun outputPending(objectId: Int) {}

    companion object {
        /** 待ち手のないところ（テスト、復元の途中）用。何もしない */
        val NONE: WakeSink = object : WakeSink {
            override fun wake(key: WaitKey) {}

            override fun wakeObject(objectId: Int) {}
        }
    }
}
