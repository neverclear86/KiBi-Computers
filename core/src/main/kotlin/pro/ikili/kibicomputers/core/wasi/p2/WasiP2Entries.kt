package pro.ikili.kibicomputers.core.wasi.p2

import pro.ikili.kibicomputers.core.kernel.obj.HandleEntry
import pro.ikili.kibicomputers.core.kernel.process.HostResourceEntry
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition

// p2 のホストの resource の実体（#267。ADR 0267）。プロセスの `HostResourceTable` に rep で置く。
// 数値と明示的なデータだけを持つ（保存の対象。ADR 0260）。中身を変えるのはそのプロセスのスレッドだけで、カーネルロックの中で行う。

/** p2 の resource の実体の基底 */
internal sealed class P2Entry : HostResourceEntry() {
    /** 子（pollable）の数。子が残っている親は drop できない（トラップ。ADR 0267） */
    var children: Int = 0

    /** resource の種類の名前（誤ったハンドルのトラップの文言用） */
    abstract val label: String
}

/**
 * `input-stream`・`output-stream`。[handle] はカーネルのオブジェクト（パイプの端・`Tty`・`OpenFile` など）の参照と権利。
 * [handle] が `null` なら閉じた stream（閉じた fd 0/1/2 を取った。読み書きは `closed`）。
 *
 * ファイル（`OpenFile`）の stream は、共有の位置（`OpenFile.position`）ではなく、自分の [offset] で読み書きする
 * （`read-via-stream(offset)` など）。[append] なら書くたびにファイルの終わりへ足す。
 * [allowance] は、直前の `check-write` が許した残りのバイト数（超える `write` はトラップ）。
 * [shared] なら、ファイルでも fd と同じく共有の位置で読み書きする（`get-stdin` などで、fd 0/1/2 がファイルを指すとき）
 */
internal class StreamEntry(
    val handle: HandleEntry?,
    val input: Boolean,
    var offset: Long = 0,
    val append: Boolean = false,
    val shared: Boolean = false,
) : P2Entry() {
    var allowance: Long = 0

    /** 前の書き込みで閉じたと分かった（以後はすべて `closed`） */
    var closed: Boolean = false

    override val objectId: Int get() = handle?.objectId ?: 0
    override val label: String get() = if (input) "input-stream" else "output-stream"

    override fun copyForSnapshot(): HostResourceEntry = StreamEntry(handle, input, offset, append, shared).also {
        it.allowance = allowance
        it.closed = closed
        it.children = children
    }
}

/**
 * `descriptor`。[handle] は開いたファイル・ディレクトリ（`OpenFile`）か、差し替えたもの（`/dev/tty` の `Tty` など。ADR 0054）。
 * [flags] は `descriptor-flags` のビット（宣言の順。`read` が 1、`write` が 2、`mutate-directory` が 32）
 */
internal class DescriptorEntry(val handle: HandleEntry, val flags: Int) : P2Entry() {
    override val objectId: Int get() = handle.objectId
    override val label: String get() = "descriptor"

    override fun copyForSnapshot(): HostResourceEntry = DescriptorEntry(handle, flags).also { it.children = children }
}

/**
 * `directory-entry-stream`。`read-directory` で開いたディレクトリ（[objectId]）の参照を自分で持ち、中身の写しは持たない。
 * `read-directory-entry` のたびに VFS から読み直し、[last]（最後に返した名前。名前の順）より後の最初の 1 つを返す
 * （p1 の `fd_readdir` と同じく読み直す。ディレクトリが大きくても、ホストのヒープに留まるのはこの小さな値だけ。ADR 0267）
 */
internal class DirectoryStreamEntry(override val objectId: Int) : P2Entry() {
    /** 最後に返した名前（まだ返していなければ `null`） */
    var last: String? = null

    override val label: String get() = "directory-entry-stream"

    override fun copyForSnapshot(): HostResourceEntry = DirectoryStreamEntry(objectId).also {
        it.last = last
        it.children = children
    }
}

/**
 * `pollable`。カーネルのオブジェクトは持たない（時計の pollable のたびにタイマーを作らない。ADR 0267）。
 *
 * - [Deadline]: 単調時計（`ctx.monotonicNanos`）が [Deadline.nanos] に着いたらそろう
 * - [Stream]: 親の stream（rep [Stream.parent]）が [Stream.condition] でそろったら（親は子が残る間 drop できない）
 * - [Ready]: いつもそろっている（ファイルの stream、ソケットの口など）
 */
internal sealed class PollableEntry(val parent: Int) : P2Entry() {
    override val objectId: Int get() = 0
    override val label: String get() = "pollable"

    class Deadline(val nanos: Long) : PollableEntry(0) {
        override fun copyForSnapshot(): HostResourceEntry = this
    }

    class Stream(parent: Int, val condition: WaitCondition) : PollableEntry(parent) {
        override fun copyForSnapshot(): HostResourceEntry = this
    }

    class Ready(parent: Int) : PollableEntry(parent) {
        override fun copyForSnapshot(): HostResourceEntry = this
    }
}

/** `wasi:io/error` の `error`。失敗の errno だけを持つ（`filesystem-error-code` で戻す） */
internal class ErrorEntry(val errno: Errno) : P2Entry() {
    override val objectId: Int get() = 0
    override val label: String get() = "error"

    override fun copyForSnapshot(): HostResourceEntry = this
}

/** 中身のない resource（`terminal-input`・`terminal-output`・`network`）。[label] で種類を見分ける */
internal class MarkerEntry(override val label: String) : P2Entry() {
    override val objectId: Int get() = 0

    override fun copyForSnapshot(): HostResourceEntry = this
}
