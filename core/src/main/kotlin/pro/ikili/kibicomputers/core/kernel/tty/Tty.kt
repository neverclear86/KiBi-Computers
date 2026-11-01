package pro.ikili.kibicomputers.core.kernel.tty

import pro.ikili.kibicomputers.core.display.CharWidth
import pro.ikili.kibicomputers.core.kernel.io.ReadResult
import pro.ikili.kibicomputers.core.kernel.io.WriteResult
import pro.ikili.kibicomputers.core.kernel.obj.KernelObject
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.kernel.wait.WakeSink

/**
 * TTY（行編集の規則＝ line discipline）のカーネルオブジェクト（#60、ADR 0060）。マシンのコンソールはこれ1つで、
 * fd 0 が読む側、fd 1・2 が書く側を指す。
 *
 * - 入力: 接続層から来たキー入力（[input]）を、モード（[TtyMode]）のビットに従って処理する。canonical なら行編集をして、
 *   確定した行だけをプロセスが読める（[read]）。そうでなければ、そのまま読める
 * - 出力: プロセスが書いたバイト（[write]）とエコーをためる。`nlToCrNl` なら LF を CR LF にする。たまったことは
 *   [WakeSink.outputPending] で知らせ（待ち手は起こさない）、tick ごとに [drainOutput] で取り出す
 * - Ctrl+C・Ctrl+\ は [TtyInputResult.signals] で返す。画面の大きさ（[size]）とその世代（[sizeGeneration]）も持つ
 *
 * 状態はどれも番号・真偽・バイト列（保存の対象。書き出す形は #82）。カーネルロックの中で使う。
 *
 * @param inputCapacity 入力の上限（読める入力＋編集中の行の合計）
 * @param outputCapacity 配っていない出力の上限（エコーはこれを [ECHO_SLACK_BYTES] まで超えて積める）
 */
internal class Tty(val inputCapacity: Int, val outputCapacity: Int) : KernelObject() {
    init {
        require(inputCapacity >= 2) { "入力の上限は 2 以上" }
        require(outputCapacity >= 2) { "出力の上限は 2 以上" }
    }

    /** モード（既定は [TtyMode.COOKED]。[setMode] で変える） */
    var mode: TtyMode = TtyMode.COOKED
        private set

    private var columns = DEFAULT_COLUMNS
    private var rows = DEFAULT_ROWS

    /** 画面の大きさ */
    val size: TtySize get() = TtySize(columns, rows)

    /** 画面の大きさが変わった回数 */
    var sizeGeneration: Long = 0
        private set

    /** Ctrl+C を送る相手の pid（0 = なし）。プロセスが終わっても TTY は書き換えない */
    var foregroundPid: Int = 0
        set(value) {
            require(value >= 0) { "pid は 0 以上" }
            field = value
        }

    /** 読める入力（確定した行。非 canonical ではたまった全部） */
    private val input = TtyBytes(inputCapacity)

    /** 読める入力の区切り（canonical のときだけ。行ごとの長さ。0 は Ctrl+D の印） */
    private val lines = TtyLines()

    /** 編集中の行（canonical のときだけ） */
    private val line = TtyBytes(inputCapacity)

    /** 配っていない出力 */
    private val output = TtyBytes(outputCapacity + ECHO_SLACK_BYTES)

    /** 編集中の行が始まった出力の桁 */
    private var lineStartColumn = 0

    /** 出力の今の桁（出力した CR・LF・BS・タブ・文字の幅で数える） */
    private var column = 0

    /** 出力の UTF-8 の途中の文字（桁の数え用）: 組み立て中のコードポイントと、残りの続きのバイト数 */
    private var outCodePoint = 0
    private var outRemaining = 0

    /** あふれて捨てた入力のバイト数（数え口） */
    var droppedInputBytes: Long = 0
        private set

    /** 捨てたエコーのバイト数（数え口） */
    var droppedEchoBytes: Long = 0
        private set

    /** 読める入力のバイト数（編集中の行は含まない） */
    val inputAvailable: Int get() = input.length

    /** 編集中の行のバイト数 */
    val editingLength: Int get() = line.length

    /** 配っていない出力のバイト数 */
    val outputAvailable: Int get() = output.length

    /** 確保しているバッファの合計（テスト用） */
    internal val allocatedBytes: Int get() = input.allocatedBytes + line.allocatedBytes + output.allocatedBytes

    private val readable: Boolean get() = input.length > 0 || lines.count > 0

    private val outputSpace: Int get() = outputCapacity - output.length

    // 1回の input・write の中だけで使う（実行時だけの値ではなく、呼び出しの終わりに必ず戻す）
    private var outputAdded = false
    private var droppedInCall = 0

    override fun canWait(condition: WaitCondition): Boolean = condition == WaitCondition.READABLE || condition == WaitCondition.WRITABLE

    override fun isReady(condition: WaitCondition): Boolean = when (condition) {
        WaitCondition.READABLE -> readable
        // LF は CR LF の2バイトになるので、2バイト空いていれば必ず1バイトは書ける
        WaitCondition.WRITABLE -> outputSpace >= 2
    }

    /** 接続層からのキー入力。モードに従って全部処理する（途中で止めない）。エコーは出力に積む */
    fun input(src: ByteArray, off: Int, len: Int, sink: WakeSink): TtyInputResult {
        require(off >= 0 && len >= 0 && off + len <= src.size) { "src の範囲外" }
        val wasReadable = readable
        var flushed = false
        var signals: MutableList<TtySignal>? = null
        outputAdded = false
        droppedInCall = 0
        for (i in off until off + len) {
            var b = src[i].toInt() and 0xFF
            if (b == CR && mode.crToNl) b = LF
            if (mode.signals && (b == CTRL_C || b == CTRL_BACKSLASH)) {
                input.clear()
                lines.clear()
                line.clear()
                flushed = true
                echoChar(b)
                echoChar(LF)
                (signals ?: ArrayList<TtySignal>(1).also { signals = it }) += if (b == CTRL_C) TtySignal.INTERRUPT else TtySignal.QUIT
                continue
            }
            if (mode.canonical) canonicalInput(b) else rawInput(b)
        }
        if (readable && (!wasReadable || flushed)) sink.wake(WaitKey(id, WaitCondition.READABLE))
        if (outputAdded) sink.outputPending(id)
        val dropped = droppedInCall
        outputAdded = false
        droppedInCall = 0
        return TtyInputResult(signals ?: emptyList(), dropped)
    }

    /** プロセスが読む（fd 0）。canonical では1回に1行まで（行の境目を越えない） */
    fun read(dst: ByteArray, off: Int, len: Int, sink: WakeSink): ReadResult {
        require(off >= 0 && len >= 0 && off + len <= dst.size) { "dst の範囲外" }
        if (!readable) return ReadResult.WouldBlock
        if (len == 0) return ReadResult.Bytes(0)
        if (lines.count > 0) {
            val first = lines.first
            if (first == 0) {
                lines.removeFirst()
                return ReadResult.Eof
            }
            val n = minOf(len, first)
            input.take(dst, off, n)
            lines.consumeFirst(n)
            return ReadResult.Bytes(n)
        }
        val n = minOf(len, input.length)
        input.take(dst, off, n)
        return ReadResult.Bytes(n)
    }

    /** プロセスが書く（fd 1・2）。空いている分だけ書く（一部だけのことがある）。数は [src] の側のバイト数 */
    fun write(src: ByteArray, off: Int, len: Int, sink: WakeSink): WriteResult {
        require(off >= 0 && len >= 0 && off + len <= src.size) { "src の範囲外" }
        if (len == 0) return WriteResult.Bytes(0)
        var space = outputSpace
        var n = 0
        outputAdded = false
        while (n < len) {
            val b = src[off + n].toInt() and 0xFF
            val need = if (b == LF && mode.nlToCrNl) 2 else 1
            if (need > space) break
            emit(b)
            space -= need
            n++
        }
        if (outputAdded) sink.outputPending(id)
        outputAdded = false
        return if (n == 0) WriteResult.WouldBlock else WriteResult.Bytes(n)
    }

    /** たまった出力を全部取り出す。書けなかった状態から空いたら `(id, WRITABLE)` を知らせる。なければ `null` */
    fun drainOutput(sink: WakeSink): ByteArray? {
        if (output.length == 0) return null
        val wasBlocked = !isReady(WaitCondition.WRITABLE)
        val bytes = output.takeAll()
        if (wasBlocked) sink.wake(WaitKey(id, WaitCondition.WRITABLE))
        return bytes
    }

    /**
     * モードを変える。canonical → 非 canonical では、編集中の行を読める入力の後ろに移す（行の区切りと Ctrl+D の印は捨てる）。
     * 非 canonical → canonical では、たまった入力を確定した1つの塊として残す。読めるようになったら `(id, READABLE)` を知らせる
     */
    fun setMode(mode: TtyMode, sink: WakeSink) {
        val old = this.mode
        val wasReadable = readable
        if (old.canonical && !mode.canonical) {
            input.addAll(line)
            line.clear()
            lines.clear()
        } else if (!old.canonical && mode.canonical) {
            lines.clear()
            if (input.length > 0) lines.add(input.length)
        }
        this.mode = mode
        if (!wasReadable && readable) sink.wake(WaitKey(id, WaitCondition.READABLE))
    }

    /** 画面の大きさを変える。変わったら世代を1つ進めて `true`（待ち手は起こさない。待てる形の知らせは #63） */
    fun resize(size: TtySize): Boolean {
        if (size.columns == columns && size.rows == rows) return false
        columns = size.columns
        rows = size.rows
        sizeGeneration++
        return true
    }

    override fun onLastClose(sink: WakeSink) {
        input.clear()
        lines.clear()
        line.clear()
        output.clear()
    }

    // ---- 入力 ----

    private fun canonicalInput(b: Int) {
        when (b) {
            LF -> {
                // 行に文字があれば、確定の1バイトはいつも残してある
                if (line.length == 0 && input.length + 1 > inputCapacity) {
                    drop()
                    return
                }
                val n = line.length + 1
                input.addAll(line)
                input.add(LF.toByte())
                line.clear()
                lines.add(n)
                echoChar(LF)
            }
            DEL, BS -> {
                if (line.length == 0) return
                val starts = charStarts()
                eraseTo(starts[starts.size - 1])
            }
            CTRL_U -> eraseTo(0)
            CTRL_W -> {
                var end = line.length
                while (end > 0 && isBlank(line[end - 1].toInt())) end--
                while (end > 0 && !isBlank(line[end - 1].toInt())) end--
                eraseTo(end)
            }
            CTRL_D -> {
                if (line.length == 0) {
                    if (lines.count == 0 || lines.last != 0) lines.add(0)
                } else {
                    val n = line.length
                    input.addAll(line)
                    line.clear()
                    lines.add(n)
                }
            }
            else -> {
                if (input.length + line.length + 1 > inputCapacity - 1 || line.length + 1 > MAX_LINE_BYTES) {
                    drop()
                    return
                }
                if (line.length == 0) lineStartColumn = column
                line.add(b.toByte())
                echoChar(b)
            }
        }
    }

    private fun rawInput(b: Int) {
        if (input.length + 1 > inputCapacity) {
            drop()
            return
        }
        input.add(b.toByte())
        echoChar(b)
    }

    /** あふれた1バイトを捨てる。エコーするなら BEL を鳴らす */
    private fun drop() {
        droppedInCall++
        droppedInputBytes++
        if (mode.echo) echoBytes(BEL)
    }

    /** 編集中の行の、文字の始まりの位置（UTF-8 の1文字ずつ） */
    private fun charStarts(): IntArray {
        val out = IntArray(line.length)
        var count = 0
        var i = 0
        while (i < line.length) {
            out[count++] = i
            i += charAt(i, line.length).first
        }
        return out.copyOf(count)
    }

    /**
     * 編集中の行の [i] から始まる1文字の（バイト数、表示のセル数）。[end] で途切れた UTF-8 は、残り全部で幅 0。
     * 壊れた UTF-8 は1バイトで幅 1。タブの幅は桁によるので、ここでは -1 を返す
     */
    private fun charAt(i: Int, end: Int): Pair<Int, Int> {
        val b = line[i].toInt() and 0xFF
        if (b < 0x80) {
            return when {
                b == TAB -> 1 to -1
                b < 0x20 || b == DEL -> 1 to 2 // ^X
                else -> 1 to 1
            }
        }
        val n = utf8Length(b)
        if (n < 2) return 1 to 1
        var cp = b and (0xFF shr (n + 1))
        for (k in 1 until n) {
            if (i + k >= end) return (end - i) to 0
            val c = line[i + k].toInt() and 0xFF
            if (c and 0xC0 != 0x80) return 1 to 1
            cp = (cp shl 6) or (c and 0x3F)
        }
        return n to CharWidth.of(cp)
    }

    /** 編集中の行を [target] バイトまで、後ろから1文字ずつ消す。エコーするなら、表示の幅の分だけ消す */
    private fun eraseTo(target: Int) {
        if (target >= line.length) return
        if (mode.echo) {
            // 各文字の始まりの桁を、行の始まりの桁から数える
            val starts = charStarts()
            val cols = IntArray(starts.size + 1)
            var col = lineStartColumn
            for (k in starts.indices) {
                cols[k] = col
                val w = charAt(starts[k], line.length).second
                col = if (w < 0) (col / TAB_WIDTH + 1) * TAB_WIDTH else col + w
            }
            cols[starts.size] = col
            var k = starts.size - 1
            while (k >= 0 && starts[k] >= target) {
                val cells = cols[k + 1] - cols[k]
                val isTab = (line[starts[k]].toInt() and 0xFF) == TAB
                repeat(cells) { echoBytes(BS) }
                if (!isTab) {
                    repeat(cells) { echoBytes(SPACE) }
                    repeat(cells) { echoBytes(BS) }
                }
                k--
            }
        }
        line.dropLast(line.length - target)
    }

    // ---- 出力 ----

    /** エコーを積む（[TtyMode.echo] のときだけ）。LF・タブと普通の文字はそのまま、ほかの制御文字は `^X` */
    private fun echoChar(b: Int) {
        if (!mode.echo) return
        if (b == LF || b == TAB || (b >= 0x20 && b != DEL)) {
            echoBytes(b)
        } else {
            echoBytes(CARET)
            echoBytes(b xor 0x40)
        }
    }

    /** エコーの1バイトを、出力の上限を [ECHO_SLACK_BYTES] まで超えて積む。それも超えたら捨てて数える */
    private fun echoBytes(b: Int) {
        val need = if (b == LF && mode.nlToCrNl) 2 else 1
        if (output.length + need > outputCapacity + ECHO_SLACK_BYTES) {
            droppedEchoBytes += need
            return
        }
        emit(b)
    }

    /** 出力に1バイト積む（`nlToCrNl` なら LF を CR LF に）。空きは呼び出し側が確かめる */
    private fun emit(b: Int) {
        if (b == LF && mode.nlToCrNl) put(CR)
        put(b)
    }

    private fun put(b: Int) {
        output.add(b.toByte())
        outputAdded = true
        trackColumn(b)
    }

    /** 出力した1バイトで、今の桁を進める */
    private fun trackColumn(b: Int) {
        if (outRemaining > 0 && b and 0xC0 == 0x80) {
            outCodePoint = (outCodePoint shl 6) or (b and 0x3F)
            outRemaining--
            if (outRemaining == 0) column += CharWidth.of(outCodePoint)
            return
        }
        outRemaining = 0
        if (b < 0x80) {
            when {
                b == CR -> column = 0
                b == BS -> if (column > 0) column--
                b == TAB -> column = (column / TAB_WIDTH + 1) * TAB_WIDTH
                b < 0x20 || b == DEL -> Unit // LF（CR なし）と制御文字は桁を変えない
                else -> column++
            }
            return
        }
        val n = utf8Length(b)
        if (n < 2) {
            column++
            return
        }
        outCodePoint = b and (0xFF shr (n + 1))
        outRemaining = n - 1
    }

    internal companion object {
        /** 1行の上限（バイト。仮の値。確定は #251） */
        const val MAX_LINE_BYTES: Int = 4095

        /** エコーが出力の上限を超えて積める量（バイト。仮の値。確定は #251） */
        const val ECHO_SLACK_BYTES: Int = 1024

        /** 画面の大きさの既定（仮の値。画面のデバイスがつながったら #65・#66 が入れる） */
        const val DEFAULT_COLUMNS: Int = 80
        const val DEFAULT_ROWS: Int = 25

        const val TAB_WIDTH: Int = 8

        private const val CTRL_C = 0x03
        private const val CTRL_D = 0x04
        private const val BEL = 0x07
        private const val BS = 0x08
        private const val TAB = 0x09
        private const val LF = 0x0A
        private const val CR = 0x0D
        private const val CTRL_U = 0x15
        private const val CTRL_W = 0x17
        private const val CTRL_BACKSLASH = 0x1C
        private const val SPACE = 0x20
        private const val CARET = 0x5E
        private const val DEL = 0x7F

        private fun isBlank(b: Int): Boolean = (b and 0xFF) == SPACE || (b and 0xFF) == TAB

        /** UTF-8 の先頭のバイトから、その文字のバイト数（2〜4）。先頭でなければ 0 */
        private fun utf8Length(b: Int): Int = when {
            b and 0xE0 == 0xC0 -> 2
            b and 0xF0 == 0xE0 -> 3
            b and 0xF8 == 0xF0 -> 4
            else -> 0
        }
    }
}
