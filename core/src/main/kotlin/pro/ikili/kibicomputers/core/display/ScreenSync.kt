package pro.ikili.kibicomputers.core.display

/**
 * サーバー側の、1つの画面の同期（#140、ADR 0031 の B+K）。
 *
 * 見ているプレイヤーがいる間だけ、画面バッファに描画命令を記録させる。tick の終わりに [flush] を1回呼ぶと、
 * その tick に送るバイト列（フラグメントの並び）を返す。見ている全員に同じバイト列を送る。
 *
 * - 命令が画面全体（キーフレーム）の大きさの半分を超えた tick は、命令を捨ててキーフレームを送る。
 * - 新しく見に来たプレイヤーには、その tick の終わりの画面をキーフレームで送る。
 * - 1画面あたりの送信量（バイト/秒）が [bytesPerSecond] を超えたら送るのを止め、余裕ができたらキーフレームを送る。
 * - 1つのフラグメントは [maxFragmentSize] バイト以下にする（パケット1つの大きさの上限に収めるため）。
 *
 * バイト列の形式は、同じ版の MOD のサーバーとクライアントの間だけで使う（保存しない）。
 * スレッドセーフではない。画面バッファと同じスレッド（サーバーのメインスレッド）から使う。
 *
 * @param text 同期する文字画面のバッファ
 * @param bytesPerSecond 1画面あたりの送信量の上限（バイト/秒）。仮の値（確定は #20・#251）
 * @param maxFragmentSize 1つのフラグメントの最大の大きさ（バイト）
 */
public class ScreenSyncSender
@JvmOverloads
@Throws(IllegalArgumentException::class)
public constructor(
    private val text: TextBuffer,
    private val bytesPerSecond: Int = DEFAULT_BYTES_PER_SECOND,
    private val maxFragmentSize: Int = DEFAULT_MAX_FRAGMENT_SIZE,
) {
    init {
        require(bytesPerSecond > 0) { "bytesPerSecond must be positive: $bytesPerSecond" }
        require(maxFragmentSize >= MIN_FRAGMENT_SIZE) { "maxFragmentSize is too small: $maxFragmentSize" }
    }

    private var log: DrawLog? = null
    private var keyframePending = false

    // 送ってよいバイト数の残り。送った tick に負になってもよく、負の間は送らない
    private var budget: Long = bytesPerSecond.toLong()

    /**
     * 同期するピクセル画面のバッファ。ピクセル画面を使わない（まだ作っていない）ときは null。
     * 途中で設定してもよい（それまでの記録はそのまま続く）。
     */
    public var pixels: PixelBuffer? = null
        set(value) {
            if (field === value) return
            field?.log = null
            field = value
            value?.log = log
        }

    /** いま描画命令を記録しているか（見ているプレイヤーがいるか）。 */
    public val isRecording: Boolean
        get() = log != null

    /**
     * tick の終わりに1回呼ぶ。
     *
     * @param hasWatchers 前の tick から見ているプレイヤーがいるか（その人たちは前に送った画面を持っている）
     * @param hasNewWatchers この tick に新しく見に来たプレイヤーがいるか（その人たちにはキーフレームを送る）
     * @return この tick に送るもの。見ているプレイヤーがいなければ何も送らず、記録も止める
     */
    public fun flush(hasWatchers: Boolean, hasNewWatchers: Boolean): ScreenSyncFlush {
        budget = minOf(bytesPerSecond.toLong(), budget + bytesPerSecond / TICKS_PER_SECOND)
        if (!hasWatchers && !hasNewWatchers) {
            stopRecording()
            return ScreenSyncFlush.EMPTY
        }
        var keyframe: List<ByteArray>? = null
        fun keyframe(): List<ByteArray> = keyframe ?: fragments(encodeKeyframe()).also { keyframe = it }

        var forWatchers: List<ByteArray> = emptyList()
        if (hasWatchers) {
            val log = log
            val needsKeyframe = keyframePending || log == null || log.overflow
            val changed = needsKeyframe || (log != null && log.size > 0)
            if (changed) {
                if (budget <= 0) {
                    // 送りすぎなので、この tick は送らない。余裕ができたら画面全体を送る
                    keyframePending = true
                } else if (log == null || needsKeyframe || log.size > keyframeSize() / 2) {
                    forWatchers = keyframe()
                    keyframePending = false
                } else {
                    val out = SyncWriter(log.size + 1)
                    out.byte(SyncOps.KIND_COMMANDS)
                    out.put(log.out.bytes, 0, log.size)
                    forWatchers = fragments(out)
                    charge(forWatchers)
                }
            }
        }
        // 新しく見に来たプレイヤーには、送りすぎでも最初の画面を送る
        val forNewWatchers = if (hasNewWatchers) keyframe() else emptyList()
        keyframe?.let { charge(it) }
        startRecording()
        return ScreenSyncFlush(forWatchers, forNewWatchers)
    }

    private fun startRecording() {
        val log = log ?: DrawLog(0).also {
            log = it
            text.log = it
            pixels?.log = it
        }
        log.limit = keyframeSize() / 2
        log.reset()
    }

    private fun stopRecording() {
        log = null
        text.log = null
        pixels?.log = null
        keyframePending = false
    }

    private fun keyframeSize(): Int = text.snapshotSize + (pixels?.snapshotSize ?: 0)

    private fun encodeKeyframe(): SyncWriter {
        val out = SyncWriter(keyframeSize() + 2)
        out.byte(SyncOps.KIND_KEYFRAME)
        text.writeSnapshot(out)
        val p = pixels
        if (p == null) {
            out.byte(0)
        } else {
            out.byte(1)
            p.writeSnapshot(out)
        }
        return out
    }

    private fun charge(fragments: List<ByteArray>) {
        for (f in fragments) budget -= f.size
    }

    // メッセージを、先頭 1 バイトが「最後のフラグメントか」のフラグメントに分ける
    private fun fragments(message: SyncWriter): List<ByteArray> {
        val chunk = maxFragmentSize - 1
        val n = maxOf(1, (message.size + chunk - 1) / chunk)
        return List(n) { i ->
            val from = i * chunk
            val len = minOf(chunk, message.size - from)
            ByteArray(len + 1).also {
                it[0] = if (i == n - 1) 1 else 0
                System.arraycopy(message.bytes, from, it, 1, len)
            }
        }
    }

    public companion object {
        /** 1画面あたりの送信量の上限の既定値（1 MiB/秒）。仮の値（確定は #20・#251）。 */
        public const val DEFAULT_BYTES_PER_SECOND: Int = 1 shl 20

        /**
         * 1つのフラグメントの最大の大きさの既定値（512 KiB）。Minecraft のカスタムペイロードの上限（1 MiB）より十分小さくする。
         */
        public const val DEFAULT_MAX_FRAGMENT_SIZE: Int = 1 shl 19

        /** 1つのフラグメントの大きさの下限。 */
        public const val MIN_FRAGMENT_SIZE: Int = 16

        /** 1秒あたりの tick の数。 */
        public const val TICKS_PER_SECOND: Int = 20
    }
}

/**
 * [ScreenSyncSender.flush] が返す、1 tick に送るもの。どちらもフラグメントの並びで、順に送る。
 *
 * @property forWatchers 前から見ているプレイヤー全員に送るもの。空なら送らない
 * @property forNewWatchers この tick に新しく見に来たプレイヤー全員に送るもの（キーフレーム）。空なら送らない
 */
public class ScreenSyncFlush(
    @JvmField public val forWatchers: List<ByteArray>,
    @JvmField public val forNewWatchers: List<ByteArray>,
) {
    /** 何も送らないか。 */
    public val isEmpty: Boolean
        get() = forWatchers.isEmpty() && forNewWatchers.isEmpty()

    public companion object {
        /** 何も送らない。 */
        @JvmField
        public val EMPTY: ScreenSyncFlush = ScreenSyncFlush(emptyList(), emptyList())
    }
}

/** [ScreenSyncReceiver] が受け取った内容を書き込む先（クライアントの画面）。 */
public interface ScreenSyncTarget {
    /** 文字画面のバッファ。 */
    public val text: TextBuffer

    /** ピクセル画面のバッファ。まだなければ作る。ピクセル描画できない画面では null。 */
    public fun pixels(): PixelBuffer?

    /** ピクセル画面のバッファをもう作っていれば返す。作っていなければ null（作らない）。 */
    public fun pixelsIfCreated(): PixelBuffer?
}

/**
 * クライアント側の、1つの画面の同期（#140）。[ScreenSyncSender] が作ったフラグメントを順に受け取り、
 * 画面バッファに再生する。再生には、サーバーと同じ `core` の描画関数を使う。
 *
 * スレッドセーフではない。
 */
public class ScreenSyncReceiver {
    private var pending: SyncWriter? = null

    /**
     * フラグメントを1つ受け取る。メッセージの最後のフラグメントなら、[target] に書き込む。
     *
     * @return メッセージを書き込んだか（途中のフラグメントなら false）
     * @throws IllegalArgumentException データが壊れているとき。それまでに受け取ったフラグメントは捨てる。
     *   画面が途中まで書き換わっていることがあるので、キーフレームを送り直してもらう
     */
    @Throws(IllegalArgumentException::class)
    public fun receive(fragment: ByteArray, target: ScreenSyncTarget): Boolean {
        try {
            require(fragment.isNotEmpty()) { "empty fragment" }
            val last = fragment[0].toInt() == 1
            val buffered = pending
            if (!last) {
                val p = buffered ?: SyncWriter(fragment.size * 2).also { pending = it }
                p.put(fragment, 1, fragment.size - 1)
                return false
            }
            val reader = if (buffered == null) {
                SyncReader(fragment, 1)
            } else {
                pending = null
                buffered.put(fragment, 1, fragment.size - 1)
                SyncReader(buffered.bytes, 0, buffered.size)
            }
            apply(reader, target)
            return true
        } catch (e: IllegalArgumentException) {
            pending = null
            throw e
        } catch (e: RuntimeException) {
            // 範囲外の添字など、壊れたデータで起きうるものはまとめて壊れたデータとして扱う
            pending = null
            throw IllegalArgumentException("malformed screen sync data", e)
        }
    }

    /** 受け取り途中のフラグメントを捨てる（画面を見るのをやめたときなど）。 */
    public fun reset() {
        pending = null
    }

    private fun apply(input: SyncReader, target: ScreenSyncTarget) {
        when (val kind = input.byte()) {
            SyncOps.KIND_KEYFRAME -> {
                target.text.readSnapshot(input)
                if (input.byte() == 1) {
                    val p = target.pixels() ?: throw IllegalArgumentException("this screen has no pixels")
                    p.readSnapshot(input)
                } else {
                    // サーバーがまだピクセル画面を作っていなければ、中身は既定の色のまま
                    target.pixelsIfCreated()?.clear()
                }
                require(!input.hasMore) { "trailing data after keyframe" }
            }
            SyncOps.KIND_COMMANDS -> while (input.hasMore) command(input, target)
            else -> throw IllegalArgumentException("unknown message kind: $kind")
        }
    }

    private fun command(input: SyncReader, target: ScreenSyncTarget) {
        val text = target.text
        when (val op = input.byte()) {
            SyncOps.TEXT_SET -> text.set(input.uint(), input.uint(), input.uint(), input.rgb(), input.rgb())
            SyncOps.TEXT_WRITE -> {
                val x = input.uint()
                val y = input.uint()
                val fg = input.rgb()
                val bg = input.rgb()
                val n = input.count(input.remaining)
                val sb = StringBuilder(n)
                repeat(n) {
                    val cp = input.uint()
                    require(Character.isValidCodePoint(cp)) { "invalid code point: $cp" }
                    sb.appendCodePoint(cp)
                }
                text.write(x, y, sb.toString(), fg, bg)
            }
            SyncOps.TEXT_FILL -> text.fill(
                input.uint(), input.uint(), input.uint(), input.uint(), input.uint(), input.rgb(), input.rgb(),
            )
            SyncOps.TEXT_COPY -> text.copy(input.sint(), input.sint(), input.sint(), input.sint(), input.sint(), input.sint())
            SyncOps.TEXT_RESIZE -> text.resize(input.uint(), input.uint())
            SyncOps.TEXT_CURSOR -> text.setCursor(input.uint(), input.uint(), input.byte() == 1)
            else -> pixelCommand(op, input, target)
        }
    }

    private fun pixelCommand(op: Int, input: SyncReader, target: ScreenSyncTarget) {
        require(op in SyncOps.PIXEL_SET..SyncOps.PIXEL_RESIZE) { "unknown draw command: $op" }
        val p = target.pixels() ?: throw IllegalArgumentException("this screen has no pixels")
        when (op) {
            SyncOps.PIXEL_SET -> p.set(input.uint(), input.uint(), input.rgb())
            SyncOps.PIXEL_FILL -> p.fill(input.uint(), input.uint(), input.uint(), input.uint(), input.rgb())
            SyncOps.PIXEL_COPY -> p.copy(input.sint(), input.sint(), input.sint(), input.sint(), input.sint(), input.sint())
            SyncOps.PIXEL_IMAGE -> {
                val x = input.uint()
                val y = input.uint()
                val w = input.uint()
                val h = input.uint()
                val n = w.toLong() * h
                require(n * 3 <= input.remaining) { "image is truncated" }
                val src = IntArray(n.toInt()) { input.rgb() }
                p.setPixels(x, y, w, h, src, 0, w)
            }
            SyncOps.PIXEL_CLEAR -> p.clear(input.rgb())
            SyncOps.PIXEL_RESIZE -> p.resize(input.uint(), input.uint())
        }
    }
}
