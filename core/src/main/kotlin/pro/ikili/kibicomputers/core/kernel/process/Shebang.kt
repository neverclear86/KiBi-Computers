package pro.ikili.kibicomputers.core.kernel.process

import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.fsError
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * 実行ファイルの先頭のシバン（`#!/bin/lua` など）の解釈（#93。#19 の決定）。
 *
 * カーネルの exec は、先頭が `#!` のファイルを、その行に書かれたインタプリタで起動する。
 * 解釈は Linux（`binfmt_script`）に合わせる。
 *
 * - `#!` の後の空白（スペース・タブ）を飛ばし、次の空白までをインタプリタのパスにする
 * - 残りは前後の空白を除いて、まるごと1つの引数にする（空なら引数なし。空白で分けない）
 * - 行の終わりの `\r` は空白と同じに扱う（CRLF で保存したスクリプトも動く）
 * - 行は [MAX_LINE_BYTES] バイト以内。改行なしでそれを超える・インタプリタがない・NUL を含む・UTF-8 として読めないなら `NOEXEC`
 *
 * カーネルは拡張子（`.lua` など）を見ない。
 */
internal object Shebang {
    /** シバンの行の上限（バイト。`#!` と改行を含む。Linux の `BINPRM_BUF_SIZE` と同じ） */
    const val MAX_LINE_BYTES: Int = 256

    /**
     * シバンをたどる深さの上限。インタプリタ自身がシバン付きのスクリプトでもよいが、この回数を超えたら `LOOP`
     * （Linux の `BINPRM_MAX_RECURSION` と同じ）
     */
    const val MAX_DEPTH: Int = 4

    /** 解釈したシバンの行。[arg] は省略されていれば `null` */
    data class Line(val interpreter: String, val arg: String?)

    /** [head]（ファイルの先頭 [length] バイト）が `#!` で始まるか */
    fun isScript(head: ByteArray, length: Int): Boolean = length >= 2 && head[0] == '#'.code.toByte() && head[1] == '!'.code.toByte()

    /**
     * [head]（ファイルの先頭 [length] バイト。[MAX_LINE_BYTES] バイト以上か、ファイルの全体）から、シバンの行を読む。
     * `#!` で始まらなければ `null`
     */
    fun parse(head: ByteArray, length: Int): Line? {
        if (!isScript(head, length)) return null
        val limit = minOf(length, MAX_LINE_BYTES)
        var end = -1
        for (i in 2 until limit) {
            if (head[i] == '\n'.code.toByte()) {
                end = i
                break
            }
        }
        if (end < 0) {
            // 改行がない: ファイルが上限より短ければ、終わりまでが1行
            if (length >= MAX_LINE_BYTES) fsError(Errno.NOEXEC, "シバンの行が長すぎる（上限 $MAX_LINE_BYTES バイト）")
            end = length
        }
        val text = decode(head, 2, end - 2)
        if (text.indexOf('\u0000') >= 0) fsError(Errno.NOEXEC, "シバンの行に NUL がある")
        val body = text.trim(::isBlank)
        val split = body.indexOfFirst(::isBlank)
        val interpreter = if (split < 0) body else body.substring(0, split)
        if (interpreter.isEmpty()) fsError(Errno.NOEXEC, "シバンにインタプリタがない")
        val arg = if (split < 0) null else body.substring(split).trim(::isBlank).ifEmpty { null }
        return Line(interpreter, arg)
    }

    private fun isBlank(c: Char): Boolean = c == ' ' || c == '\t' || c == '\r'

    private fun decode(bytes: ByteArray, off: Int, len: Int): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, off, len))
            .toString()
    } catch (e: CharacterCodingException) {
        fsError(Errno.NOEXEC, "シバンの行が UTF-8 として読めない")
    }
}
