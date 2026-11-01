package pro.ikili.kibicomputers.core.display.terminal

import pro.ikili.kibicomputers.core.display.CharWidth
import pro.ikili.kibicomputers.core.display.TextBuffer

/** [AnsiTerminal] からの知らせ。どれも既定では何もしない。 */
internal interface TerminalListener {
    /** 端末からの応答（DSR・DA への答え）。TTY の入力として、プログラムに読ませる。 */
    fun reply(bytes: ByteArray) {}

    /** BEL を受け取った。 */
    fun bell() {}

    companion object {
        /** 何もしない。 */
        val NONE: TerminalListener = object : TerminalListener {}
    }
}

/**
 * ANSI エスケープシーケンス（VT100 互換のサブセットと、よく使う xterm の拡張）を解釈して、
 * 文字画面のバッファ [buffer] に書く（#61）。対応する範囲は `docs/terminal.md`。
 *
 * TTY（ADR 0060）の出力のバイト列（UTF-8）をそのまま [write] に渡す。LF は「1行下へ」だけで、行の先頭へは戻らない
 * （TTY が LF を CR LF にしている）。バイト列は途中で切れていてよい（UTF-8 の文字やエスケープシーケンスの途中でも、
 * 次の [write] で続きを読む）。
 *
 * 色は [maxColors]（画面と GPU の色数の低い方）に合わせて丸める（[TerminalColors]）。
 * 代替画面（エディタ用）に入っている間は、元の画面の中身を写しとして持ち、出るときに [buffer] に書き戻す。
 *
 * 状態は番号・真偽・配列だけで持つ。スレッドセーフではない。
 *
 * @param buffer 書き込む先。外から大きさを変えてもよい（次の [write] で合わせる）
 * @param maxColors 画面の色数（2・16・256 など）
 * @param listener 応答・BEL の知らせ先
 */
internal class AnsiTerminal(
    val buffer: TextBuffer,
    maxColors: Int,
    private val listener: TerminalListener = TerminalListener.NONE,
) {
    /** 画面の色数。変えると、それより後に書く文字から新しい丸め方になる。 */
    var maxColors: Int = maxColors
        set(value) {
            field = value
            colorMode = ColorMode.forColors(value)
            resolveColors()
        }

    private var colorMode = ColorMode.forColors(maxColors)

    private var columns = buffer.width
    private var rows = buffer.height

    /** カーソルの列（0 から）。 */
    var cursorX: Int = 0
        private set

    /** カーソルの行（0 から）。 */
    var cursorY: Int = 0
        private set

    /** カーソルを表示するか（DECTCEM）。 */
    var cursorVisible: Boolean = true
        private set

    /** 代替画面に入っているか。 */
    val alternateScreen: Boolean
        get() = savedMain != null

    // 右端に書いた直後で、次の文字を書く前に折り返す（DECAWM の保留）
    private var wrapPending = false

    // スクロール領域（どちらも含む）
    private var top = 0
    private var bottom = rows - 1

    // モード
    private var autoWrap = true
    private var originMode = false
    private var insertMode = false

    // 文字の属性
    private var fgSpec = TerminalColors.DEFAULT
    private var bgSpec = TerminalColors.DEFAULT
    private var attrs = 0

    // 属性から求めた色（書く色と、消すときの色）
    private var drawFg = TextBuffer.DEFAULT_FOREGROUND
    private var drawBg = TextBuffer.DEFAULT_BACKGROUND
    private var eraseFg = TextBuffer.DEFAULT_FOREGROUND
    private var eraseBg = TextBuffer.DEFAULT_BACKGROUND

    // 文字集合（G0・G1 が DEC の線画文字か、G1 を使っているか）
    private var g0Graphics = false
    private var g1Graphics = false
    private var shiftOut = false

    // タブ位置。null は既定（8 桁ごと）
    private var tabStops: BooleanArray? = null

    // DECSC で保存したカーソル。1049 で入ったときの保存は別に持つ
    private var saved: SavedCursor? = null
    private var savedFor1049: SavedCursor? = null

    // 代替画面に入っている間の、元の画面の中身
    private var savedMain: TextBuffer? = null

    // 解釈の状態
    private var state = GROUND
    private val params = IntArray(MAX_PARAMS)
    private var paramCount = 0
    private var colonMask = 0
    private var paramStarted = false
    private var prefix = 0
    private var intermediate = 0

    // UTF-8 の途中の文字
    private var utf8Code = 0
    private var utf8Need = 0
    private var utf8Min = 0

    // まだバッファに書いていない、同じ行・同じ色で続く文字
    private val run = StringBuilder()
    private var runX = 0
    private var runY = 0
    private var runCells = 0
    private var runFg = 0
    private var runBg = 0

    init {
        resolveColors()
    }

    /** UTF-8 のバイト列 [bytes] の [offset] から [length] バイトを解釈して書く。 */
    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) { "range out of bounds" }
        syncSize()
        for (i in offset until offset + length) decode(bytes[i].toInt() and 0xFF)
        flushRun()
        publishCursor()
    }

    /** 文字列 [text] を UTF-8 にして [write] する。 */
    fun write(text: String) {
        val b = text.encodeToByteArray()
        write(b, 0, b.size)
    }

    /** 画面の大きさを変える（[buffer] の大きさを変え、スクロール領域を画面全体に戻す）。 */
    fun resize(columns: Int, rows: Int) {
        buffer.resize(columns, rows)
        syncSize()
        publishCursor()
    }

    /** 端末を最初の状態に戻す（RIS と同じ。画面を消し、代替画面から出る）。 */
    fun reset() {
        syncSize()
        flushRun()
        fullReset()
        publishCursor()
    }

    // カーソルの位置と表示を、バッファに写す（画面の同期で、入力 GUI の描画 #348 に届く）
    private fun publishCursor() {
        buffer.setCursor(cursorX, cursorY, cursorVisible)
    }

    // ---- UTF-8 ----

    private fun decode(b: Int) {
        if (utf8Need > 0) {
            if (b and 0xC0 == 0x80) {
                utf8Code = (utf8Code shl 6) or (b and 0x3F)
                if (--utf8Need == 0) {
                    val cp = utf8Code
                    val ok = cp >= utf8Min && cp <= Character.MAX_CODE_POINT && cp !in SURROGATES
                    input(if (ok) cp else REPLACEMENT)
                }
                return
            }
            // 続きのバイトが来なかった: 途中の文字は U+FFFD にして、このバイトを読み直す
            utf8Need = 0
            input(REPLACEMENT)
        }
        when {
            b < 0x80 -> input(b)
            b and 0xE0 == 0xC0 -> startUtf8(b and 0x1F, 1, 0x80)
            b and 0xF0 == 0xE0 -> startUtf8(b and 0x0F, 2, 0x800)
            b and 0xF8 == 0xF0 -> startUtf8(b and 0x07, 3, 0x10000)
            else -> input(REPLACEMENT)
        }
    }

    private fun startUtf8(bits: Int, need: Int, min: Int) {
        utf8Code = bits
        utf8Need = need
        utf8Min = min
    }

    // ---- 解釈の状態機械（VT500 系の解釈器を簡略にしたもの） ----

    private fun input(cp: Int) {
        // どの状態でも効くもの
        when (cp) {
            CAN, SUB -> {
                state = GROUND
                return
            }
            ESC -> {
                state = ESCAPE
                intermediate = 0
                return
            }
            DEL -> return
        }
        when (state) {
            GROUND -> if (cp < 0x20) control(cp) else if (cp in 0x80..0x9F) Unit else print(cp)
            ESCAPE -> when {
                cp < 0x20 -> control(cp)
                cp in 0x20..0x2F -> {
                    intermediate = cp
                    state = ESCAPE_INTERMEDIATE
                }
                cp == '['.code -> startCsi()
                cp == ']'.code -> state = OSC_STRING
                cp == 'P'.code || cp == 'X'.code || cp == '^'.code || cp == '_'.code -> state = STRING_IGNORE
                cp in 0x30..0x7E -> {
                    state = GROUND
                    escDispatch(cp)
                }
                else -> state = GROUND
            }
            ESCAPE_INTERMEDIATE -> when {
                cp < 0x20 -> control(cp)
                cp in 0x20..0x2F -> intermediate = cp
                cp in 0x30..0x7E -> {
                    state = GROUND
                    escDispatch(cp)
                }
                else -> state = GROUND
            }
            CSI_PARAM -> csiInput(cp)
            CSI_IGNORE -> when {
                cp < 0x20 -> control(cp)
                cp in 0x40..0x7E -> state = GROUND
            }
            OSC_STRING -> if (cp == BEL) state = GROUND
            STRING_IGNORE -> Unit
        }
    }

    private fun startCsi() {
        state = CSI_PARAM
        paramCount = 0
        colonMask = 0
        paramStarted = false
        prefix = 0
        intermediate = 0
        params.fill(0)
    }

    private fun csiInput(cp: Int) {
        when {
            cp < 0x20 -> control(cp)
            cp in '0'.code..'9'.code -> {
                if (intermediate != 0) {
                    state = CSI_IGNORE
                    return
                }
                if (!paramStarted) {
                    paramStarted = true
                    if (paramCount == 0) paramCount = 1
                }
                val i = paramCount - 1
                if (i < MAX_PARAMS) params[i] = minOf(params[i] * 10 + (cp - '0'.code), MAX_PARAM_VALUE)
            }
            cp == ';'.code || cp == ':'.code -> {
                if (intermediate != 0) {
                    state = CSI_IGNORE
                    return
                }
                if (paramCount == 0) paramCount = 1
                paramCount++
                if (cp == ':'.code && paramCount - 1 < MAX_PARAMS) colonMask = colonMask or (1 shl (paramCount - 1))
                paramStarted = true
            }
            cp in 0x3C..0x3F -> {
                // 私用の印（? > = <）は最初の文字のときだけ
                if (paramCount == 0 && prefix == 0 && intermediate == 0) prefix = cp else state = CSI_IGNORE
            }
            cp in 0x20..0x2F -> intermediate = cp
            cp in 0x40..0x7E -> {
                state = GROUND
                if (paramCount > MAX_PARAMS) paramCount = MAX_PARAMS
                flushRun()
                csiDispatch(cp)
            }
            else -> state = CSI_IGNORE
        }
    }

    private fun param(i: Int, default: Int): Int = if (i < paramCount && params[i] != 0) params[i] else default

    // ---- 制御文字 ----

    private fun control(cp: Int) {
        when (cp) {
            BEL -> listener.bell()
            BS -> {
                flushRun()
                // 右端で折り返しを保留していても、1つ左へ動く（xterm と同じ）
                wrapPending = false
                if (cursorX > 0) cursorX--
            }
            HT -> {
                flushRun()
                cursorX = nextTab(cursorX)
                wrapPending = false
            }
            LF, VT, FF -> {
                flushRun()
                lineFeed()
            }
            CR -> {
                flushRun()
                cursorX = 0
                wrapPending = false
            }
            SO -> shiftOut = true
            SI -> shiftOut = false
        }
    }

    // ---- ESC ----

    private fun escDispatch(cp: Int) {
        flushRun()
        when (intermediate) {
            0 -> when (cp.toChar()) {
                '7' -> saveCursor()
                '8' -> restoreCursor()
                'D' -> lineFeed()
                'E' -> {
                    cursorX = 0
                    lineFeed()
                }
                'M' -> reverseIndex()
                'H' -> setTab(cursorX)
                'c' -> fullReset()
                else -> Unit // ESC = / ESC > （キーパッドのモード）など。何もしない
            }
            '('.code -> g0Graphics = cp == '0'.code
            ')'.code -> g1Graphics = cp == '0'.code
            '#'.code -> if (cp == '8'.code) alignmentTest()
        }
    }

    // ---- CSI ----

    private fun csiDispatch(cp: Int) {
        if (prefix == '?'.code) {
            when (cp.toChar()) {
                'h' -> for (i in 0 until maxOf(paramCount, 1)) setPrivateMode(params[i], true)
                'l' -> for (i in 0 until maxOf(paramCount, 1)) setPrivateMode(params[i], false)
            }
            return
        }
        if (prefix != 0) {
            // CSI > c（第2の DA）だけ答える
            if (prefix == '>'.code && cp == 'c'.code && param(0, 0) == 0) reply("\u001b[>0;0;0c")
            return
        }
        if (intermediate == '!'.code) {
            if (cp == 'p'.code) softReset()
            return
        }
        if (intermediate != 0) return
        when (cp.toChar()) {
            'A' -> moveUp(param(0, 1))
            'B' -> moveDown(param(0, 1))
            'C' -> setCursorX(cursorX + param(0, 1))
            'D' -> setCursorX(cursorX - param(0, 1))
            'E' -> {
                moveDown(param(0, 1))
                cursorX = 0
            }
            'F' -> {
                moveUp(param(0, 1))
                cursorX = 0
            }
            'G', '`' -> setCursorX(param(0, 1) - 1)
            'H', 'f' -> cursorPosition(param(0, 1), param(1, 1))
            'd' -> cursorPosition(param(0, 1), cursorX + 1)
            'I' -> {
                repeat(minOf(param(0, 1), columns)) { cursorX = nextTab(cursorX) }
                wrapPending = false
            }
            'Z' -> {
                repeat(minOf(param(0, 1), columns)) { cursorX = previousTab(cursorX) }
                wrapPending = false
            }
            'J' -> eraseInDisplay(param(0, 0))
            'K' -> eraseInLine(param(0, 0))
            'X' -> {
                fillRow(cursorY, cursorX, cursorX + param(0, 1))
                wrapPending = false
            }
            '@' -> insertChars(param(0, 1))
            'P' -> deleteChars(param(0, 1))
            'L' -> insertLines(param(0, 1))
            'M' -> deleteLines(param(0, 1))
            'S' -> scrollUp(param(0, 1))
            'T' -> scrollDown(param(0, 1))
            'g' -> clearTabs(param(0, 0))
            'm' -> sgr()
            'r' -> setMargins(param(0, 1), param(1, rows))
            's' -> saveCursor()
            'u' -> restoreCursor()
            'h' -> for (i in 0 until maxOf(paramCount, 1)) if (params[i] == 4) insertMode = true
            'l' -> for (i in 0 until maxOf(paramCount, 1)) if (params[i] == 4) insertMode = false
            'n' -> deviceStatus(param(0, 0))
            'c' -> if (param(0, 0) == 0) reply("\u001b[?1;2c")
            else -> Unit
        }
    }

    private fun setPrivateMode(mode: Int, on: Boolean) {
        when (mode) {
            6 -> {
                originMode = on
                cursorPosition(1, 1)
            }
            7 -> {
                autoWrap = on
                if (!on) wrapPending = false
            }
            25 -> cursorVisible = on
            47, 1047 -> if (on) enterAlternate() else leaveAlternate()
            1048 -> if (on) saveCursor() else restoreCursor()
            1049 -> if (on) {
                if (!alternateScreen) {
                    savedFor1049 = snapshotCursor()
                    enterAlternate()
                }
            } else if (alternateScreen) {
                leaveAlternate()
                savedFor1049?.let { applyCursor(it) }
                savedFor1049 = null
            }
        }
    }

    private fun deviceStatus(n: Int) {
        when (n) {
            5 -> reply("\u001b[0n")
            6 -> {
                val row = if (originMode) cursorY - top + 1 else cursorY + 1
                reply("\u001b[$row;${cursorX + 1}R")
            }
        }
    }

    private fun reply(text: String) {
        listener.reply(text.encodeToByteArray())
    }

    // ---- SGR ----

    private fun sgr() {
        if (paramCount == 0) {
            resetAttributes()
            resolveColors()
            return
        }
        var i = 0
        while (i < paramCount) {
            when (val p = params[i]) {
                0 -> resetAttributes()
                1 -> attrs = attrs or BOLD
                7 -> attrs = attrs or INVERSE
                8 -> attrs = attrs or CONCEAL
                22 -> attrs = attrs and BOLD.inv()
                27 -> attrs = attrs and INVERSE.inv()
                28 -> attrs = attrs and CONCEAL.inv()
                in 30..37 -> fgSpec = p - 30
                39 -> fgSpec = TerminalColors.DEFAULT
                in 40..47 -> bgSpec = p - 40
                49 -> bgSpec = TerminalColors.DEFAULT
                in 90..97 -> fgSpec = p - 90 + 8
                in 100..107 -> bgSpec = p - 100 + 8
                38, 48 -> {
                    val (spec, used) = extendedColor(i)
                    if (spec != NO_COLOR) {
                        if (p == 38) fgSpec = spec else bgSpec = spec
                    }
                    i += used
                }
                else -> Unit // 薄い・斜体・下線・点滅・取り消し線などは、画面が出せないので無視する
            }
            i++
        }
        resolveColors()
    }

    /**
     * `params[i]` が 38・48 のときの色の指定を読む。`;` で区切った `5;n`・`2;r;g;b` と、
     * `:` で区切った `5:n`・`2:r:g:b`・`2:色空間:r:g:b` を受け付ける。
     *
     * @return 色の指定（読めなければ [NO_COLOR]）と、続けて使ったパラメーターの数
     */
    private fun extendedColor(i: Int): Pair<Int, Int> {
        val colon = isColon(i + 1)
        if (colon) {
            // : の区切りは、続く : のパラメーターをまとめて1つの指定として読む
            var n = 0
            while (i + 1 + n < paramCount && isColon(i + 1 + n)) n++
            val kind = params[i + 1]
            val spec = when {
                kind == 5 && n >= 2 -> params[i + 2].takeIf { it <= 255 } ?: NO_COLOR
                kind == 2 && n >= 5 -> rgbSpec(params[i + 3], params[i + 4], params[i + 5])
                kind == 2 && n == 4 -> rgbSpec(params[i + 2], params[i + 3], params[i + 4])
                else -> NO_COLOR
            }
            return spec to n
        }
        if (i + 1 >= paramCount) return NO_COLOR to 0
        return when (params[i + 1]) {
            5 -> if (i + 2 < paramCount) (params[i + 2].takeIf { it <= 255 } ?: NO_COLOR) to 2 else NO_COLOR to 1
            2 -> if (i + 4 < paramCount) rgbSpec(params[i + 2], params[i + 3], params[i + 4]) to 4 else NO_COLOR to paramCount - i - 1
            else -> NO_COLOR to 1
        }
    }

    private fun isColon(i: Int): Boolean = i in 1 until minOf(paramCount, MAX_PARAMS) && colonMask and (1 shl i) != 0

    private fun rgbSpec(r: Int, g: Int, b: Int): Int =
        if (r > 255 || g > 255 || b > 255) NO_COLOR else TerminalColors.rgb((r shl 16) or (g shl 8) or b)

    private fun resetAttributes() {
        fgSpec = TerminalColors.DEFAULT
        bgSpec = TerminalColors.DEFAULT
        attrs = 0
    }

    private fun resolveColors() {
        val bold = attrs and BOLD != 0
        val draw = TerminalColors.resolve(fgSpec, bgSpec, bold, attrs and INVERSE != 0, attrs and CONCEAL != 0, colorMode)
        drawFg = (draw ushr 32).toInt()
        drawBg = draw.toInt()
        // 消すときは、反転・隠しを除いた背景色で消す（xterm と同じ）
        val erase = TerminalColors.resolve(fgSpec, bgSpec, bold, false, false, colorMode)
        eraseFg = (erase ushr 32).toInt()
        eraseBg = erase.toInt()
    }

    // ---- 文字を書く ----

    private fun print(codePoint: Int) {
        val cp = mapCharset(codePoint)
        if (isZeroWidth(cp)) return
        var width = CharWidth.of(cp)
        if (width > columns) width = 1
        if (wrapPending) {
            wrapPending = false
            if (autoWrap) {
                // スクロールの前に、この行に溜めた文字を書き出す
                flushRun()
                cursorX = 0
                lineFeed()
            }
        }
        if (width == 2 && cursorX == columns - 1) {
            if (autoWrap) {
                // 右端の1セルには入らないので、空けたまま次の行へ
                appendRun(cursorX, cursorY, TextBuffer.BLANK)
                flushRun()
                cursorX = 0
                lineFeed()
            } else {
                cursorX = columns - 2
            }
        }
        if (insertMode) {
            flushRun()
            buffer.copy(cursorX, cursorY, columns - cursorX - width, 1, width, 0)
        }
        appendRun(cursorX, cursorY, cp)
        if (width == 2) appendRun(cursorX + 1, cursorY, TextBuffer.BLANK)
        if (insertMode) flushRun()
        if (cursorX + width >= columns) {
            cursorX = columns - 1
            wrapPending = autoWrap
        } else {
            cursorX += width
        }
    }

    private fun mapCharset(cp: Int): Int {
        val graphics = if (shiftOut) g1Graphics else g0Graphics
        if (!graphics || cp !in 0x5F..0x7E) return cp
        return DEC_GRAPHICS[cp - 0x5F].code
    }

    private fun isZeroWidth(cp: Int): Boolean = when (Character.getType(cp)) {
        Character.NON_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(), Character.FORMAT.toInt() -> true
        else -> false
    }

    private fun appendRun(x: Int, y: Int, cp: Int) {
        if (runCells > 0 && (y != runY || x != runX + runCells || drawFg != runFg || drawBg != runBg)) flushRun()
        if (runCells == 0) {
            runX = x
            runY = y
            runFg = drawFg
            runBg = drawBg
        }
        run.appendCodePoint(cp)
        runCells++
    }

    private fun flushRun() {
        if (runCells == 0) return
        // 全角の文字の右半分に書くときは、左の全角の文字を消す（描画で右半分が隠れないように）
        if (runX > 0 && runX - 1 < buffer.width && runY < buffer.height) {
            val left = buffer.getCodePoint(runX - 1, runY)
            if (CharWidth.isWide(left)) {
                buffer.set(runX - 1, runY, TextBuffer.BLANK, buffer.getForeground(runX - 1, runY), buffer.getBackground(runX - 1, runY))
            }
        }
        buffer.write(runX, runY, run.toString(), runFg, runBg)
        run.setLength(0)
        runCells = 0
    }

    // ---- カーソル ----

    private fun setCursorX(x: Int) {
        cursorX = x.coerceIn(0, columns - 1)
        wrapPending = false
    }

    private fun cursorPosition(row: Int, column: Int) {
        cursorY = if (originMode) (top + row - 1).coerceIn(top, bottom) else (row - 1).coerceIn(0, rows - 1)
        setCursorX(column - 1)
    }

    private fun moveUp(n: Int) {
        val limit = if (cursorY >= top) top else 0
        cursorY = maxOf(limit, cursorY - n)
        wrapPending = false
    }

    private fun moveDown(n: Int) {
        val limit = if (cursorY <= bottom) bottom else rows - 1
        cursorY = minOf(limit, cursorY + n)
        wrapPending = false
    }

    private fun lineFeed() {
        wrapPending = false
        if (cursorY == bottom) scrollUp(1) else if (cursorY < rows - 1) cursorY++
    }

    private fun reverseIndex() {
        wrapPending = false
        if (cursorY == top) scrollDown(1) else if (cursorY > 0) cursorY--
    }

    private fun saveCursor() {
        saved = snapshotCursor()
    }

    private fun restoreCursor() {
        val s = saved
        if (s == null) {
            // 保存していなければ、左上と既定の属性に戻す（xterm と同じ）
            cursorX = 0
            cursorY = 0
            wrapPending = false
            originMode = false
            resetAttributes()
            g0Graphics = false
            g1Graphics = false
            shiftOut = false
            resolveColors()
        } else {
            applyCursor(s)
        }
    }

    private fun snapshotCursor() = SavedCursor(
        cursorX, cursorY, wrapPending, fgSpec, bgSpec, attrs, originMode, g0Graphics, g1Graphics, shiftOut,
    )

    private fun applyCursor(s: SavedCursor) {
        cursorX = s.x.coerceIn(0, columns - 1)
        cursorY = s.y.coerceIn(0, rows - 1)
        wrapPending = s.wrapPending && autoWrap
        fgSpec = s.fg
        bgSpec = s.bg
        attrs = s.attrs
        originMode = s.originMode
        g0Graphics = s.g0Graphics
        g1Graphics = s.g1Graphics
        shiftOut = s.shiftOut
        resolveColors()
    }

    // ---- 消去・編集 ----

    private fun fillRow(y: Int, from: Int, to: Int) {
        val x0 = from.coerceIn(0, columns)
        val x1 = to.coerceIn(0, columns)
        if (x0 < x1) buffer.fill(x0, y, x1 - x0, 1, TextBuffer.BLANK, eraseFg, eraseBg)
    }

    private fun fillRows(from: Int, to: Int) {
        if (from < to) buffer.fill(0, from, columns, to - from, TextBuffer.BLANK, eraseFg, eraseBg)
    }

    private fun eraseInDisplay(n: Int) {
        when (n) {
            0 -> {
                fillRow(cursorY, cursorX, columns)
                fillRows(cursorY + 1, rows)
            }
            1 -> {
                fillRows(0, cursorY)
                fillRow(cursorY, 0, cursorX + 1)
            }
            2, 3 -> fillRows(0, rows)
        }
        wrapPending = false
    }

    private fun eraseInLine(n: Int) {
        when (n) {
            0 -> fillRow(cursorY, cursorX, columns)
            1 -> fillRow(cursorY, 0, cursorX + 1)
            2 -> fillRow(cursorY, 0, columns)
        }
        wrapPending = false
    }

    private fun insertChars(n: Int) {
        val count = minOf(n, columns - cursorX)
        buffer.copy(cursorX, cursorY, columns - cursorX - count, 1, count, 0)
        fillRow(cursorY, cursorX, cursorX + count)
        wrapPending = false
    }

    private fun deleteChars(n: Int) {
        val count = minOf(n, columns - cursorX)
        buffer.copy(cursorX + count, cursorY, columns - cursorX - count, 1, -count, 0)
        fillRow(cursorY, columns - count, columns)
        wrapPending = false
    }

    private fun insertLines(n: Int) {
        if (cursorY !in top..bottom) return
        scrollRegionDown(cursorY, n)
        cursorX = 0
        wrapPending = false
    }

    private fun deleteLines(n: Int) {
        if (cursorY !in top..bottom) return
        scrollRegionUp(cursorY, n)
        cursorX = 0
        wrapPending = false
    }

    private fun scrollUp(n: Int) = scrollRegionUp(top, n)

    private fun scrollDown(n: Int) = scrollRegionDown(top, n)

    // 行 from〜bottom を n 行上へずらし、下に空いた行を消す
    private fun scrollRegionUp(from: Int, n: Int) {
        val height = bottom - from + 1
        val count = minOf(n, height)
        if (count < height) buffer.copy(0, from + count, columns, height - count, 0, -count)
        fillRows(bottom - count + 1, bottom + 1)
    }

    // 行 from〜bottom を n 行下へずらし、上に空いた行を消す
    private fun scrollRegionDown(from: Int, n: Int) {
        val height = bottom - from + 1
        val count = minOf(n, height)
        if (count < height) buffer.copy(0, from, columns, height - count, 0, count)
        fillRows(from, from + count)
    }

    private fun setMargins(t: Int, b: Int) {
        val newTop = t - 1
        val newBottom = minOf(b, rows) - 1
        if (newTop >= newBottom) return
        top = newTop
        bottom = newBottom
        cursorPosition(1, 1)
    }

    private fun alignmentTest() {
        top = 0
        bottom = rows - 1
        buffer.fill(0, 0, columns, rows, 'E'.code, TextBuffer.DEFAULT_FOREGROUND, TextBuffer.DEFAULT_BACKGROUND)
        cursorX = 0
        cursorY = 0
        wrapPending = false
    }

    // ---- タブ ----

    private fun isTab(x: Int): Boolean = tabStops?.get(x) ?: (x % TAB_WIDTH == 0)

    private fun nextTab(x: Int): Int {
        var i = x + 1
        while (i < columns - 1 && !isTab(i)) i++
        return minOf(i, columns - 1)
    }

    private fun previousTab(x: Int): Int {
        var i = x - 1
        while (i > 0 && !isTab(i)) i--
        return maxOf(i, 0)
    }

    private fun customTabs(): BooleanArray = tabStops ?: BooleanArray(columns) { it % TAB_WIDTH == 0 }.also { tabStops = it }

    private fun setTab(x: Int) {
        customTabs()[x] = true
    }

    private fun clearTabs(n: Int) {
        when (n) {
            0 -> customTabs()[cursorX] = false
            3 -> customTabs().fill(false)
        }
    }

    // ---- 代替画面 ----

    private fun enterAlternate() {
        if (alternateScreen) return
        savedMain = buffer.copyOf()
        fillRows(0, rows)
    }

    private fun leaveAlternate() {
        val main = savedMain ?: return
        savedMain = null
        restoreBuffer(main)
    }

    // 写しの中身を、同じ色が続くセルをまとめて書き戻す（描画命令の記録を小さくするため）
    private fun restoreBuffer(from: TextBuffer) {
        val sb = StringBuilder()
        for (y in 0 until minOf(rows, from.height)) {
            var x = 0
            val w = minOf(columns, from.width)
            while (x < w) {
                val fg = from.getForeground(x, y)
                val bg = from.getBackground(x, y)
                sb.setLength(0)
                var end = x
                while (end < w && from.getForeground(end, y) == fg && from.getBackground(end, y) == bg) {
                    sb.appendCodePoint(from.getCodePoint(end, y))
                    end++
                }
                buffer.write(x, y, sb.toString(), fg, bg)
                x = end
            }
        }
    }

    // ---- リセットと大きさ ----

    private fun softReset() {
        cursorVisible = true
        insertMode = false
        originMode = false
        autoWrap = true
        top = 0
        bottom = rows - 1
        resetAttributes()
        g0Graphics = false
        g1Graphics = false
        shiftOut = false
        saved = null
        wrapPending = false
        resolveColors()
    }

    private fun fullReset() {
        softReset()
        savedMain = null
        savedFor1049 = null
        tabStops = null
        cursorX = 0
        cursorY = 0
        state = GROUND
        buffer.clear()
    }

    // バッファの大きさが外から変わっていたら合わせる
    private fun syncSize() {
        if (buffer.width == columns && buffer.height == rows) return
        flushRun()
        columns = buffer.width
        rows = buffer.height
        top = 0
        bottom = rows - 1
        cursorX = cursorX.coerceIn(0, columns - 1)
        cursorY = cursorY.coerceIn(0, rows - 1)
        wrapPending = false
        tabStops = tabStops?.let { old -> BooleanArray(columns) { if (it < old.size) old[it] else it % TAB_WIDTH == 0 } }
        savedMain?.resize(columns, rows)
    }

    private class SavedCursor(
        val x: Int,
        val y: Int,
        val wrapPending: Boolean,
        val fg: Int,
        val bg: Int,
        val attrs: Int,
        val originMode: Boolean,
        val g0Graphics: Boolean,
        val g1Graphics: Boolean,
        val shiftOut: Boolean,
    )

    private companion object {
        const val GROUND = 0
        const val ESCAPE = 1
        const val ESCAPE_INTERMEDIATE = 2
        const val CSI_PARAM = 3
        const val CSI_IGNORE = 4
        const val OSC_STRING = 5
        const val STRING_IGNORE = 6

        const val MAX_PARAMS = 16
        const val MAX_PARAM_VALUE = 65535
        const val TAB_WIDTH = 8
        const val NO_COLOR = -2

        const val BOLD = 1
        const val INVERSE = 2
        const val CONCEAL = 4

        const val BEL = 0x07
        const val BS = 0x08
        const val HT = 0x09
        const val LF = 0x0A
        const val VT = 0x0B
        const val FF = 0x0C
        const val CR = 0x0D
        const val SO = 0x0E
        const val SI = 0x0F
        const val CAN = 0x18
        const val SUB = 0x1A
        const val ESC = 0x1B
        const val DEL = 0x7F
        const val REPLACEMENT = 0xFFFD
        val SURROGATES = 0xD800..0xDFFF

        // DEC の線画文字（0x5F〜0x7E）
        const val DEC_GRAPHICS = " ◆▒␉␌␍␊°±␤␋┘┐┌└┼⎺⎻─⎼⎽├┤┴┬│≤≥π≠£·"
    }
}
