package pro.ikili.kibicomputers.core.kernel.vfs

/**
 * ホストのディレクトリのディスク（[DiskFileSystem]。#52）で使える名前の規則。どの OS のサーバーでも同じに振る舞うよう、
 * Windows で使えない名前は、Linux・macOS のサーバーでも使わない（ADR 0052）。
 *
 * - 使えない名前: Windows の予約名（`CON`・`PRN`・`AUX`・`NUL`・`COM0`〜`COM9`・`LPT0`〜`LPT9`、上付きの `¹²³` も、
 *   `CONIN$`・`CONOUT$`・`CLOCK$`。大文字小文字によらず、拡張子付き・拡張子の前の空白付きも）、使えない文字
 *   （`< > : " \ | ? *`、U+0000〜U+001F）を含む名前、末尾が `.`・空白の名前、Windows の 8.3 形式の短い名前の形
 *   （[looksLikeShortName]。`ABCDEF~1`・`ab12~1.txt` など）
 * - 大文字小文字: 1文字ずつ [Character.toUpperCase] でそろえて比べる（`ß` などの複数文字への変換はしない。長さは変わらない）
 */
internal object DiskNames {
    private val RESERVED = buildSet {
        addAll(listOf("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$", "CLOCK$"))
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
        if (fold(stem) in RESERVED) return false
        return !looksLikeShortName(name)
    }

    /**
     * Windows の 8.3 形式の短い名前の形か（#433）。Windows では、この形の名前が長い名前のファイルの別名として当たることがある
     * （`abcdefghij.txt` の別名 `ABCDEF~1.TXT`）ので、どの OS でも使えない名前にする。
     * 形: 最初の `.` の前が 2〜8 文字で `~` と1つ以上の数字で終わり、その後は無いか、`.` と 3 文字以下（`.` を含まない）
     */
    fun looksLikeShortName(name: String): Boolean {
        val dot = name.indexOf('.')
        val baseEnd = if (dot < 0) name.length else dot
        if (dot >= 0) {
            val extLen = name.length - dot - 1
            if (extLen > 3 || name.indexOf('.', dot + 1) >= 0) return false
        }
        if (baseEnd !in 2..8) return false
        val tilde = name.lastIndexOf('~', baseEnd - 1)
        if (tilde < 0 || tilde == baseEnd - 1) return false
        for (i in tilde + 1 until baseEnd) if (name[i] !in '0'..'9') return false
        return true
    }

    /** 名前が使えなければ `INVAL`（作る・名前を変える先） */
    fun check(name: String) {
        if (!isValid(name)) fsError(Errno.INVAL, "ディスクに使えない名前: $name")
    }

    /** 大文字小文字をそろえると同じ名前か（[fold] して比べるのと同じ。文字列を作らない） */
    fun sameName(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        for (i in a.indices) {
            val x = a[i]
            val y = b[i]
            if (x != y && Character.toUpperCase(x) != Character.toUpperCase(y)) return false
        }
        return true
    }

    /** 大文字小文字をそろえた名前（比べるため） */
    fun fold(name: String): String {
        val sb = StringBuilder(name.length)
        for (ch in name) sb.append(Character.toUpperCase(ch))
        return sb.toString()
    }
}
