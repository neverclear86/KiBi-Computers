package pro.ikili.kibicomputers.core.kernel.process

import pro.ikili.kibicomputers.core.kernel.wait.WaitKey

/** 退避する値が上限（[AsyncStack.MAX_VALUES]）を超えた。プロセスは `CRASHED` になる */
internal class AsyncStackOverflowException(message: String) : RuntimeException(message)

/**
 * 待機点への変換をかけたプロセスが、巻き戻しで退避した値（#329。[Process.asyncStack]、`KernelState` の下）。
 *
 * i64 の並び（後入れ先出し）。待っている間は、ここと線形メモリ・可変のグローバルに続きがある（ADR 0316）。
 * 番号だけを持ち、ランタイムの型を参照しない（保存できる。書き出す形は #81・#82）。
 *
 * スレッド: プロセスを動かしているスレッド（WASM の実行の中）が読み書きする。スレッドを持たないときは、カーネルロックの中で読み書きしてよい。
 */
internal class AsyncStack {
    private var values = LongArray(INITIAL_CAPACITY)

    /** 積んでいる値の数 */
    var size: Int = 0
        private set

    val isEmpty: Boolean get() = size == 0

    /** [value] を積む。上限を超えたら [AsyncStackOverflowException] */
    fun push(value: Long) {
        if (size == values.size) {
            if (size >= MAX_VALUES) throw AsyncStackOverflowException("退避する値が上限（$MAX_VALUES 個）を超えた")
            values = values.copyOf(minOf(values.size * 2, MAX_VALUES))
        }
        values[size++] = value
    }

    /** 最後に積んだ値を取り出す。空なら [IllegalStateException] */
    fun pop(): Long {
        check(size > 0) { "退避した値がない" }
        return values[--size]
    }

    /** 積んでいる値の写し（下から順。テストと保存用） */
    fun toLongArray(): LongArray = values.copyOf(size)

    /** 空にする（プロセスが終わったとき） */
    fun clear() {
        size = 0
        if (values.size > INITIAL_CAPACITY) values = LongArray(INITIAL_CAPACITY)
    }

    companion object {
        /** 退避する値の数の上限（512 KiB。仮の値。確定は #251） */
        const val MAX_VALUES: Int = 65_536

        private const val INITIAL_CAPACITY = 64
    }
}

/**
 * 巻き戻しで待っている呼び出しを、やり直すときに要る値（#329。[Process.asyncWait]、`KernelState` の下）。
 * 待っている間だけ [Process.asyncWait] に入る。番号と時刻だけを持つ（保存できる）。
 *
 * @property timer 一時的なタイマーの key（p1 の `poll_oneoff` が時計で待つときだけ）。参照するだけで、閉じる責任は
 *   持たない（持ち主は [Process.waitTimers]）
 * @property monotonicBase 相対の期限の基準にした、マシンの単調時計の時刻
 * @property realtimeBase 実時間の期限を単調時計へ写すのに使った実時間の時刻（使っていなければ `null`）
 */
internal class AsyncWait(val timer: WaitKey?, val monotonicBase: Long, val realtimeBase: Long?)
