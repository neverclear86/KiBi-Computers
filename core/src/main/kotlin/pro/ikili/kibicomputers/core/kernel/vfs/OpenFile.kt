package pro.ikili.kibicomputers.core.kernel.vfs

import pro.ikili.kibicomputers.core.kernel.obj.KernelObject
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition

/** [Vfs.open] の指定。WASI p1 の `oflags`・`fdflags` と、読み書きの権利に当たる */
internal data class OpenOptions(
    /** 読む */
    val read: Boolean = true,
    /** 書く */
    val write: Boolean = false,
    /** 書くときは常に終わりに足す */
    val append: Boolean = false,
    /** なければ作る（`O_CREAT`） */
    val create: Boolean = false,
    /** [create] のとき、あれば `EXIST`（`O_EXCL`） */
    val exclusive: Boolean = false,
    /** 大きさを 0 にする（`O_TRUNC`。[write] が要る） */
    val truncate: Boolean = false,
    /** ディレクトリでなければ `NOTDIR`（`O_DIRECTORY`） */
    val directory: Boolean = false,
)

/** [Vfs.seek] の起点 */
internal enum class Whence {
    /** 先頭から */
    SET,

    /** 今の位置から */
    CUR,

    /** 終わりから */
    END,
}

/**
 * 開いたファイル（POSIX の「オープンファイル記述」。保存の対象）。[pro.ikili.kibicomputers.core.kernel.obj.ObjectTable] に置き、
 * fd（ハンドル）から指す。fd を複製すると、読み書きの位置を共有する。
 *
 * ファイルシステムは参照せず、`(mountId, node)` で指す（操作は [Vfs] を通す）。マウントが外れたら、操作は `IO` になる。
 * ファイルシステムに「閉じる」を伝えないので、最後の参照が外れても特別な処理は要らない。
 */
internal class OpenFile(
    /** 属するマウントの ID（マウントの祖先の、どの FS にもないディレクトリは 0） */
    val mountId: Int,
    /** ファイルシステムの中の node（[mountId] が 0 なら 0） */
    val node: Long,
    /** 開いたときの種類 */
    val type: FileType,
    /**
     * 開いたときの VFS の絶対パス。ディレクトリの fd から辿るとき（WASI の `path_open` など）と、
     * ディレクトリの中身にマウントの位置を足すときに使う。開いた後に名前が変わっても追わない
     */
    val path: VfsPath,
    /** 読める */
    val readable: Boolean,
    /** 書ける */
    val writable: Boolean,
    /** 書くときは常に終わりに足す */
    val append: Boolean,
) : KernelObject() {
    /** 読み書きの位置（バイト） */
    var position: Long = 0
        internal set

    /** ディレクトリか */
    val isDirectory: Boolean get() = type == FileType.DIRECTORY

    // 普通のファイルは、いつでも読める・書ける（POSIX の poll と同じ）。デバイスファイルは #54 で別の型にする
    override fun canWait(condition: WaitCondition): Boolean = !isDirectory

    override fun isReady(condition: WaitCondition): Boolean = !isDirectory
}
