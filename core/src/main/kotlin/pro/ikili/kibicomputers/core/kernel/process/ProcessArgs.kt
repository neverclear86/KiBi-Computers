package pro.ikili.kibicomputers.core.kernel.process

import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.fsError

/**
 * プロセスの引数・環境変数の検査と上限（#45）。
 *
 * WASI（p1 の `args_get`・`environ_get`、p2 の `wasi:cli/environment`）へ写すのは ABI 層。カーネルは文字列として持つ。
 */
internal object ProcessArgs {
    /**
     * 引数と環境変数を合わせた大きさの上限（バイト。仮の値。確定は #251）。
     * 数え方は WASI p1 の `args_sizes_get`・`environ_sizes_get` と同じ（UTF-8 のバイト数と、終わりの NUL 1 バイト。環境変数は `名前=値`）
     */
    const val MAX_BYTES: Int = 64 * 1024

    /**
     * [args] と [env] を確かめる。NUL を含む、環境変数の名前が空・`=` を含むなら `INVAL`。
     * 合わせて [MAX_BYTES] を超えたら `TOOBIG`（POSIX の `E2BIG`）
     */
    fun check(args: List<String>, env: Map<String, String>) {
        var total = 0L
        for (a in args) {
            if (a.indexOf('\u0000') >= 0) fsError(Errno.INVAL, "引数に NUL がある")
            total += utf8Length(a) + 1
        }
        for ((name, value) in env) {
            if (name.isEmpty()) fsError(Errno.INVAL, "環境変数の名前が空")
            if (name.indexOf('=') >= 0) fsError(Errno.INVAL, "環境変数の名前に = がある: $name")
            if (name.indexOf('\u0000') >= 0 || value.indexOf('\u0000') >= 0) fsError(Errno.INVAL, "環境変数に NUL がある")
            total += utf8Length(name) + 1 + utf8Length(value) + 1
        }
        if (total > MAX_BYTES) fsError(Errno.TOOBIG, "引数と環境変数が大きすぎる（$total バイト。上限 $MAX_BYTES）")
    }

    /** [s] を UTF-8 にしたときのバイト数（対になっていないサロゲートは `?` の1バイトになるのに合わせる） */
    fun utf8Length(s: String): Int = s.toByteArray(Charsets.UTF_8).size
}
