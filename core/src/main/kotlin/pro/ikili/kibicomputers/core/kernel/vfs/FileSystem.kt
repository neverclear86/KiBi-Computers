package pro.ikili.kibicomputers.core.kernel.vfs

/** ファイルの種類（WASI p1 の `filetype` の一部。シンボリックリンクは扱わない） */
internal enum class FileType {
    /** 種類が分からない */
    UNKNOWN,

    /** キャラクターデバイス（`/dev/null`・`/dev/tty` など。#54） */
    CHARACTER_DEVICE,

    /** ディレクトリ */
    DIRECTORY,

    /** 普通のファイル */
    REGULAR_FILE,
}

/**
 * ファイルの属性。
 *
 * [node] はファイルシステムの中でファイルを指す番号（inode 相当）。ファイルシステムが割り当て、名前を変えても変わらない。
 * [device] は VFS が埋める（マウントの ID。マウントの祖先の、どの FS にもないディレクトリは 0）。
 */
internal data class FileStat(
    val node: Long,
    val type: FileType,
    val size: Long = 0,
    /** 最後に読んだ時刻（エポックからのナノ秒。分からなければ 0） */
    val accessedNanos: Long = 0,
    /** 最後に中身を変えた時刻 */
    val modifiedNanos: Long = 0,
    /** 最後に属性を変えた時刻 */
    val changedNanos: Long = 0,
    /** ハードリンクの数（ハードリンクは扱わないので 1） */
    val linkCount: Long = 1,
    val device: Long = 0,
)

/** ディレクトリの中身の1つ。`.` と `..` は含めない（要るなら ABI 層が足す） */
internal data class DirEntry(val name: String, val type: FileType, val node: Long)

/**
 * ファイルシステムの抽象（ディスク #52、`/tmp` #53、`/dev` #54、読み取り専用のイメージ #55）。[Vfs] にマウントして使う。
 *
 * - パスは**このファイルシステムの根からの**正規化したパス（[VfsPath]）。マウントの位置は知らない
 * - 失敗は [FsException]（[Errno]）で知らせる
 * - 開いたファイルは [node]（[FileStat.node]）で指す。VFS は開いたファイルを `(マウントの ID, node)` で覚え、
 *   開く・閉じるの対応をファイルシステムに求めない（開いたファイルの状態を保存できる値だけにするため）。
 *   ホストの資源（チャンネルなど）を開いたままにするなら、ファイルシステムの側で持ち、自分で閉じる
 * - 消されたファイルの [node] を使ったら `STALE`（開いたまま消したファイルを読み続けられるかは、ファイルシステムが決める）
 * - 呼ばれるのは CPU を持つプロセスのスレッドで、マシンのカーネルロックの中。ファイルの読み書きのほかに待たない
 *   （ほかのロック・条件変数・メインスレッドを待たない）
 */
internal interface FileSystem {
    /** 書けないファイルシステムか（書く操作の前に VFS が `ROFS` にする） */
    val readOnly: Boolean get() = false

    /** [path] の属性。なければ `NOENT`、途中がディレクトリでなければ `NOTDIR` */
    fun stat(path: VfsPath): FileStat

    /** [node] の属性。なければ `STALE` */
    fun statNode(node: Long): FileStat

    /** 空の普通のファイルを作り、その node を返す。あれば `EXIST`、親がなければ `NOENT` */
    fun createFile(path: VfsPath): Long

    /** ディレクトリを作る。あれば `EXIST`、親がなければ `NOENT` */
    fun createDirectory(path: VfsPath)

    /** 普通のファイルを消す。ディレクトリなら `ISDIR` */
    fun removeFile(path: VfsPath)

    /** 空のディレクトリを消す。空でなければ `NOTEMPTY`、ディレクトリでなければ `NOTDIR`。根は `BUSY` */
    fun removeDirectory(path: VfsPath)

    /** 名前を変える（POSIX の `rename` と同じ規則。あれば置き換える） */
    fun rename(from: VfsPath, to: VfsPath)

    /** [node] の [position] から最大 [len] バイトを読む。読めた数を返す（終わりなら 0） */
    fun read(node: Long, position: Long, dst: ByteArray, off: Int, len: Int): Int

    /** [node] の [position] に最大 [len] バイトを書く。書けた数を返す。容量が尽きたら `NOSPC` */
    fun write(node: Long, position: Long, src: ByteArray, off: Int, len: Int): Int

    /** [node] の大きさを [size] にする（伸ばした分は 0） */
    fun setSize(node: Long, size: Long)

    /** ディレクトリ [node] の中身。ディレクトリでなければ `NOTDIR` */
    fun readDirectory(node: Long): List<DirEntry>

    /**
     * [node] の時刻を変える。`null` の時刻は変えない（時刻はエポックからのナノ秒）。
     * 既定は `NOTSUP`（時刻を持たない・変えられないファイルシステム）
     */
    fun setTimes(node: Long, accessedNanos: Long?, modifiedNanos: Long?) {
        fsError(Errno.NOTSUP, "時刻を変えられないファイルシステム")
    }

    /** [node] の中身を記憶装置へ書き出す（既定は何もしない） */
    fun sync(node: Long) {}

    /**
     * [Vfs.unmount] で外された（既定は何もしない）。メモリ上のファイルシステム（`/tmp`）は、ここで中身を捨てて RAM を返す。
     * 同じファイルシステムを別の場所にマウントし直すことはない前提
     */
    fun onUnmounted() {}
}
