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
 * 開くと、fd がファイルではなく別のカーネルオブジェクトを指すファイル（[FileSystem.special]。`/dev` の `tty`・`device/<address>`。
 * #485・#54、ADR 0054）。差し替えは `FdTable.openFile` で行う
 */
internal sealed interface SpecialFile {
    /** 制御端末（`/dev/tty`）。fd はプロセスの端末（`Tty`）を指す */
    object Terminal : SpecialFile

    /** つないでいるデバイス（`/dev/device/<address>`）。fd はそのデバイスのハンドル（`DeviceHandle(address, kind)`）を指す */
    data class Device(val address: String, val kind: String) : SpecialFile

    /** デバイスのファイルだが、開く途中でデバイスが外れた（`NOENT`） */
    object Detached : SpecialFile
}

/**
 * ファイルシステムの抽象（ディスク #52、`/tmp` #53、`/dev` #54、読み取り専用のイメージ #55）。[Vfs] にマウントして使う。
 *
 * - パスは**このファイルシステムの根からの**正規化したパス（[VfsPath]）。マウントの位置は知らない
 * - 失敗は [FsException]（[Errno]）で知らせる
 * - 開いたファイルは [node]（[FileStat.node]）で指す。VFS は開いたファイルを `(マウントの ID, node)` で覚え、
 *   開く・閉じるの対応をファイルシステムに求めない（開いたファイルの状態を保存できる値だけにするため）。
 *   ホストの資源（チャンネルなど）を開いたままにするなら、ファイルシステムの側で持ち、自分で閉じる
 *   （[tracksOpenFiles] なら、開いた・閉じたを [retain]・[release] で知らせる。知らせのない node の操作も受け付ける）
 * - 消されたファイルの [node] を使ったら `STALE`（開いたまま消したファイルを読み続けられるかは、ファイルシステムが決める）
 * - 呼ばれるのは、マシンのカーネルロックの**外**（#52。ADR 0051・0052）。同じマシンのいくつものスレッド（プロセスのスレッド、
 *   起動用のスレッド）から同時に呼ばれるので、ファイルシステムが自分で排他する。自分の I/O と自分の排他のほかに待たない
 *   （ほかのマシン・メインスレッド・カーネルロックを待たない）
 * - カーネルロックの中から呼ばれるのは [ignoresCase]・[sizeHint]・[revoke]・[onUnmounted]・[special]・[pathHint] だけ。この6つは I/O をせず、
 *   自分のロックも長く待たずに、すぐ返す
 */
internal interface FileSystem {
    /** 書けないファイルシステムか（書く操作の前に VFS が `ROFS` にする） */
    val readOnly: Boolean get() = false

    /**
     * 名前の大文字小文字を区別しないか（ディスク。ADR 0052）。[Vfs] がマウントの位置と名前を比べるのに使う（#606）。
     * マウントするとき（カーネルロックの中）に読むので、I/O をしない定数にする
     */
    val ignoresCase: Boolean get() = false

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

    /**
     * 名前を変える（POSIX の `rename` と同じ規則。あれば置き換える）。
     * [requireDirectory] なら、元がディレクトリでなければ `NOTDIR`（末尾の `/`。確かめと名前の変更を、自分の排他の中で不可分に行う）
     */
    fun rename(from: VfsPath, to: VfsPath, requireDirectory: Boolean = false)

    /** [node] の [position] から最大 [len] バイトを読む。読めた数を返す（終わりなら 0） */
    fun read(node: Long, position: Long, dst: ByteArray, off: Int, len: Int): Int

    /** [node] の [position] に最大 [len] バイトを書く。書けた数を返す。容量が尽きたら `NOSPC` */
    fun write(node: Long, position: Long, src: ByteArray, off: Int, len: Int): Int

    /** [node] の大きさを [size] にする（伸ばした分は 0） */
    fun setSize(node: Long, size: Long)

    /**
     * [node] の終わりに最大 [len] バイトを足す（追記）。大きさを読んでから書くまでを不可分に行う（同時の追記が重ならない）。
     * 書いた位置と数を返す
     */
    fun append(node: Long, src: ByteArray, off: Int, len: Int): Written

    /** [node] が [size] より小さければ [size] まで伸ばす（0 で埋める）。大きければ何もしない（縮めない）。不可分に行う */
    fun allocate(node: Long, size: Long)

    /**
     * [node] の大きさの目安（`poll` の `nbytes`）。カーネルロックの中から呼ばれるので、I/O をせず、長く待たない。
     * 分からなければ 0
     */
    fun sizeHint(node: Long): Long

    /** ディレクトリ [node] の中身。ディレクトリでなければ `NOTDIR` */
    fun readDirectory(node: Long): List<DirEntry>

    /**
     * [node] の時刻を変える。`null` の時刻は変えない（時刻はエポックからのナノ秒）。
     * 既定は `NOTSUP`（時刻を持たない・変えられないファイルシステム）
     */
    fun setTimes(node: Long, accessedNanos: Long?, modifiedNanos: Long?) {
        fsError(Errno.NOTSUP, "時刻を変えられないファイルシステム")
    }

    /**
     * [node] を開いたときの差し替え先（[SpecialFile]。`/dev/tty`・`/dev/device/<address>`。#485・#54）。`null` でなければ、開いた fd は
     * このファイルではなく、差し替え先のオブジェクトを指す（`FdTable.openFile`）。カーネルロックの中から呼ばれるので、I/O をせず、
     * 長く待たない。既定は `null`（普通のファイル）
     */
    fun special(node: Long): SpecialFile? = null

    /**
     * [node] の今のパスの目安（このファイルシステムの根から）。保存の写しで、開いたファイルをパスで引き直せるように控える（#82。ADR 0082）。
     * カーネルロックの中から呼ばれるので、I/O をせず、ホストの I/O の間持たれるロックを取らない。分からなければ `null`（既定）。
     * 古いパスを返すことがある（戻すときに見つからなければ、その fd は `STALE`）
     */
    fun pathHint(node: Long): VfsPath? = null

    /**
     * 容量と使用量（`kibi:fs/mounts` の `list-mounts`。#453、ADR 0453）。分からなければ `null`（既定）。
     * カーネルロックの**外**で呼ばれる（ロックの中から呼んでよい6つには入らない）。I/O をせず、自分のロックも長く待たずに、すぐ返す
     * （ディスクを開いたり数え直したりしない。今持っている数を読むだけ）
     */
    fun usage(): FsUsage? = null

    /** [node] の中身を記憶装置へ書き出す（既定は何もしない） */
    fun sync(node: Long) {}

    /**
     * [Vfs.unmount] で外された（既定は何もしない）。メモリ上のファイルシステム（`/tmp`）は、ここで中身を捨てて RAM を返す。
     * 同じファイルシステムを別の場所にマウントし直すことはない前提。カーネルロックの中から呼ばれるので、I/O をしない
     * （ホストの資源は [retire] で閉じる）。復元の後段の失敗では、ロックの外からもう一度呼ばれることがある（2回目は何もしない。#576）
     */
    fun onUnmounted() {}

    /**
     * マシンが止まった（[Vfs.revokeAll]。既定は何もしない）。ホストのディレクトリを使うもの（#52）は、この後の操作を `IO` にする。
     * カーネルロックの中から呼ばれるので、I/O をしない（ホストの資源は [retire] で閉じる）
     */
    fun revoke() {}

    /** 開いたファイルを数えるか（`true` なら、VFS が [retain]・[release]・[retire] を呼ぶ。既定は `false`） */
    val tracksOpenFiles: Boolean get() = false

    /**
     * 開いたままにする枠を、ファイルを作る前に1つ取る（[Vfs.open] の `O_CREAT`。#434）。数の上限を超えたら `MFILE`・`NFILE`
     * （何も作らずに open を失敗させるため）。取った枠は、続く `retain(node, writable, reserved = true)` に渡すか、
     * 作るのに失敗したら [cancelOpen] で返す。[tracksOpenFiles] のときだけ呼ばれる（既定は何もしない）
     */
    fun reserveOpen() {}

    /** [reserveOpen] で取った枠を、使わずに返す（既定は何もしない） */
    fun cancelOpen() {}

    /**
     * 普通のファイル [node] を開いた（[Vfs.open]。[writable] は書くために開いたか）。ホストのファイルを開いたままにするもの（#52）は、
     * ここで開く。数の上限を超えたら `MFILE`（マシン）・`NFILE`（サーバー全体）。[tracksOpenFiles] のときだけ呼ばれる。
     * [reserved] なら [reserveOpen] で取った枠を使う（成功しても失敗しても、その枠はこの呼び出しが引き受ける。要らなければ返す）
     */
    fun retain(node: Long, writable: Boolean, reserved: Boolean = false) {}

    /** [retain] した [node] を閉じた（最後の参照が外れた）。カーネルロックの外で呼ばれる（I/O をしてよい） */
    fun release(node: Long) {}

    /**
     * [onUnmounted]・[revoke] の後に、カーネルロックの外で呼ばれる。ホストの資源（開いたままのファイル）を閉じる。
     * 重ねて呼ばれることがある（復元の後段の失敗など。#576）ので、2回目は何もしない
     */
    fun retire() {}
}

/** [FileSystem.append] の結果。[position] は書いた位置、[count] は書いた数 */
internal data class Written(val position: Long, val count: Int)
