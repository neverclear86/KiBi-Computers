package pro.ikili.kibicomputers.core.kernel.vfs

/**
 * VFS とファイルシステムのエラーコード。WASI p1（`wasi_snapshot_preview1`）の `errno` と同じ並びと値にする。
 *
 * [code] はそのまま p1 の戻り値に使える。p2・p3 の `wasi:filesystem/types.error-code` は名前で対応させる
 * （`ACCES` → `access`、`NOENT` → `no-entry` など。対応表は ABI 層で作る）。
 */
internal enum class Errno(val code: Int) {
    SUCCESS(0),
    TOOBIG(1),
    ACCES(2),
    ADDRINUSE(3),
    ADDRNOTAVAIL(4),
    AFNOSUPPORT(5),
    AGAIN(6),
    ALREADY(7),
    BADF(8),
    BADMSG(9),
    BUSY(10),
    CANCELED(11),
    CHILD(12),
    CONNABORTED(13),
    CONNREFUSED(14),
    CONNRESET(15),
    DEADLK(16),
    DESTADDRREQ(17),
    DOM(18),
    DQUOT(19),
    EXIST(20),
    FAULT(21),
    FBIG(22),
    HOSTUNREACH(23),
    IDRM(24),
    ILSEQ(25),
    INPROGRESS(26),
    INTR(27),
    INVAL(28),
    IO(29),
    ISCONN(30),
    ISDIR(31),
    LOOP(32),
    MFILE(33),
    MLINK(34),
    MSGSIZE(35),
    MULTIHOP(36),
    NAMETOOLONG(37),
    NETDOWN(38),
    NETRESET(39),
    NETUNREACH(40),
    NFILE(41),
    NOBUFS(42),
    NODEV(43),
    NOENT(44),
    NOEXEC(45),
    NOLCK(46),
    NOLINK(47),
    NOMEM(48),
    NOMSG(49),
    NOPROTOOPT(50),
    NOSPC(51),
    NOSYS(52),
    NOTCONN(53),
    NOTDIR(54),
    NOTEMPTY(55),
    NOTRECOVERABLE(56),
    NOTSOCK(57),
    NOTSUP(58),
    NOTTY(59),
    NXIO(60),
    OVERFLOW(61),
    OWNERDEAD(62),
    PERM(63),
    PIPE(64),
    PROTO(65),
    PROTONOSUPPORT(66),
    PROTOTYPE(67),
    RANGE(68),
    ROFS(69),
    SPIPE(70),
    SRCH(71),
    STALE(72),
    TIMEDOUT(73),
    TXTBSY(74),
    XDEV(75),
    NOTCAPABLE(76),
    ;

    companion object {
        private val byCode: Array<Errno> = entries.sortedBy { it.code }.toTypedArray()

        /** [code] の [Errno]。知らない値なら `null` */
        fun ofCode(code: Int): Errno? = byCode.getOrNull(code)
    }
}

/**
 * VFS・ファイルシステムの操作の失敗。ABI 層が捕まえて [errno] をプログラムに返す。
 *
 * カーネルの不整合ではない（プログラムの誤りや、ディスクの状態による失敗）。
 */
internal class FsException(val errno: Errno, message: String? = null) : Exception(message ?: errno.name) {
    init {
        require(errno != Errno.SUCCESS) { "SUCCESS は失敗ではない" }
    }
}

/** [errno] で失敗する */
internal fun fsError(errno: Errno, message: String? = null): Nothing = throw FsException(errno, message)
