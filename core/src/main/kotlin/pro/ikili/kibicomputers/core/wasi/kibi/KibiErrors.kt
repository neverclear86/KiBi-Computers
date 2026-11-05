package pro.ikili.kibicomputers.core.wasi.kibi

import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException

/**
 * 独自拡張の共通のエラー `kibi:kernel/types.error-code`（#73。ADR 0073 の Q4-A）と、カーネルの [Errno] の写し方。
 *
 * `error-code` は p1 の errno から success を除いた名前を同じ順に並べたもので、判別子 = p1 の errno - 1。
 * マシン全体の上限（`ResourceLimitException`）は `nfile`、プロセスのハンドル表が満杯なら `mfile`（カーネルが `MFILE` で投げる）。
 */
internal object KibiErrors {
    /**
     * `error-code` の WIT の名前（判別子の順）。[Errno] の `SUCCESS` を除いた並びと1対1に対応する
     * （`KibiErrorsTest` が `types.wit` と突き合わせる）
     */
    val NAMES: List<String> = listOf(
        "too-big", "access", "addr-in-use", "addr-not-avail", "af-not-supported", "again", "already", "bad-descriptor",
        "bad-message", "busy", "canceled", "child", "conn-aborted", "conn-refused", "conn-reset", "deadlock",
        "dest-addr-required", "domain", "quota", "exist", "fault", "file-too-large", "host-unreachable", "identifier-removed",
        "illegal-byte-sequence", "in-progress", "interrupted", "invalid", "io", "is-connected", "is-directory", "loop",
        "mfile", "too-many-links", "message-size", "multihop", "name-too-long", "net-down", "net-reset", "net-unreachable",
        "nfile", "no-buffers", "no-device", "no-entry", "no-exec", "no-lock", "no-link", "no-memory",
        "no-message", "no-protocol-option", "no-space", "no-sys", "not-connected", "not-directory", "not-empty", "not-recoverable",
        "not-socket", "not-supported", "no-tty", "no-such-device", "overflow", "owner-dead", "not-permitted", "pipe",
        "protocol", "protocol-not-supported", "protocol-type", "range", "read-only", "invalid-seek", "no-such-process", "stale",
        "timed-out", "text-file-busy", "cross-device", "not-capable",
    )

    /** [errno]（`SUCCESS` 以外）の `error-code` の判別子 */
    fun code(errno: Errno): Int {
        require(errno != Errno.SUCCESS) { "SUCCESS は error-code にない" }
        return errno.code - 1
    }

    /** [e] の `error-code` の判別子 */
    fun code(e: FsException): Int = code(e.errno)
}
