package pro.ikili.kibicomputers.core.kernel.vfs

/**
 * ホストのディレクトリのディスク（[DiskFileSystem]。#52）で使える名前の規則。どの OS のサーバーでも同じに振る舞うよう、
 * Windows で使えない名前は、Linux・macOS のサーバーでも使わない（ADR 0052）。
 *
 * - 使えない名前: Windows の予約名（`CON`・`PRN`・`AUX`・`NUL`・`COM0`〜`COM9`・`LPT0`〜`LPT9`、上付きの `¹²³` も。
 *   大文字小文字によらず、拡張子付き・拡張子の前の空白付きも）、使えない文字（`< > : " \ | ? *`、U+0000〜U+001F）を含む名前、
 *   末尾が `.`・空白の名前
 * - 大文字小文字: 1文字ずつ [Character.toUpperCase] でそろえて比べる（`ß` などの複数文字への変換はしない）
 */
internal object DiskNames {
    private val RESERVED = buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL"))
        for (c in listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "¹", "²", "³")) {
            add("COM$c")
            add("LPT$c")
        }
    }

    /** ディスクに置いてよい名前か */
    fun isValid(name: String): Boolean {
        if (name.isEmpty() || name == "." || name == "..") return false
        for (ch in name) {
            if (ch.code < 0x20) return false
            when (ch) {
                '<', '>', ':', '"', '\\', '|', '?', '*', '/' -> return false
            }
        }
        val last = name.last()
        if (last == '.' || last == ' ') return false
        // 予約名は、最初の `.` の前（末尾の空白を除く）で見る（`NUL.txt`・`nul .txt` も予約名）
        val stem = name.substringBefore('.').trimEnd(' ')
        return fold(stem) !in RESERVED
    }

    /** 名前が使えなければ `INVAL`（作る・名前を変える先） */
    fun check(name: String) {
        if (!isValid(name)) fsError(Errno.INVAL, "ディスクに使えない名前: $name")
    }

    /** 大文字小文字をそろえた名前（比べるため） */
    fun fold(name: String): String {
        val sb = StringBuilder(name.length)
        for (ch in name) sb.append(Character.toUpperCase(ch))
        return sb.toString()
    }
}
