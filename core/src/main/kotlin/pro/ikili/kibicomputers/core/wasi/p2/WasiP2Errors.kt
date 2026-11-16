package pro.ikili.kibicomputers.core.wasi.p2

import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonValue

/**
 * カーネルの [Errno] → `wasi:filesystem/types.error-code` の写し（#267。ADR 0267 の表）。ゲストに見えるので、表を変えるときは
 * ADR も直す。表にない errno は `io`
 */
internal object WasiP2Errors {
    /** errno → `error-code` の名前 */
    val TABLE: Map<Errno, String> = linkedMapOf(
        Errno.ACCES to "access",
        Errno.AGAIN to "would-block",
        Errno.ALREADY to "already",
        Errno.BADF to "bad-descriptor",
        Errno.BUSY to "busy",
        Errno.DEADLK to "deadlock",
        Errno.DQUOT to "quota",
        Errno.EXIST to "exist",
        Errno.FBIG to "file-too-large",
        Errno.ILSEQ to "illegal-byte-sequence",
        Errno.INPROGRESS to "in-progress",
        Errno.INTR to "interrupted",
        Errno.INVAL to "invalid",
        Errno.TOOBIG to "invalid",
        Errno.FAULT to "invalid",
        Errno.IO to "io",
        Errno.ISDIR to "is-directory",
        Errno.LOOP to "loop",
        Errno.MLINK to "too-many-links",
        Errno.MSGSIZE to "message-size",
        Errno.NAMETOOLONG to "name-too-long",
        Errno.NODEV to "no-device",
        Errno.NOENT to "no-entry",
        Errno.NOLCK to "no-lock",
        Errno.NOMEM to "insufficient-memory",
        Errno.MFILE to "insufficient-memory",
        Errno.NFILE to "insufficient-memory",
        Errno.NOSPC to "insufficient-space",
        Errno.NOTDIR to "not-directory",
        Errno.NOTEMPTY to "not-empty",
        Errno.NOTRECOVERABLE to "not-recoverable",
        Errno.NOTSUP to "unsupported",
        Errno.NOSYS to "unsupported",
        Errno.NOEXEC to "unsupported",
        Errno.NOTTY to "no-tty",
        Errno.NXIO to "no-such-device",
        Errno.OVERFLOW to "overflow",
        Errno.PERM to "not-permitted",
        Errno.NOTCAPABLE to "not-permitted",
        Errno.PIPE to "pipe",
        Errno.ROFS to "read-only",
        Errno.SPIPE to "invalid-seek",
        Errno.TXTBSY to "text-file-busy",
        Errno.XDEV to "cross-device",
    )

    /** [errno] の `error-code` の名前 */
    fun name(errno: Errno): String = TABLE[errno] ?: "io"

    /** [errno] の `error-code` の値 */
    fun code(t: WasiP2Types, errno: Errno): CanonValue = t[FS].type<CanonType.Enum>("error-code").case(name(errno))

    const val FS: String = "wasi:filesystem/types"
}
