package pro.ikili.kibicomputers.core.display

import org.jetbrains.annotations.ApiStatus
import pro.ikili.kibicomputers.core.display.terminal.AnsiTerminal
import pro.ikili.kibicomputers.core.display.terminal.TerminalListener
import pro.ikili.kibicomputers.core.kernel.ConsoleSink
import pro.ikili.kibicomputers.core.kernel.Machine
import java.util.concurrent.locks.ReentrantLock

/**
 * 表示デバイス（#355、ADR 0355）。1つの画面の中身と端末の状態を、まとめて1か所に持つ。
 *
 * - 持つもの: 文字画面（[text]）、ピクセル画面（[pixels]。使うまで作らない）、端末（ANSI の解釈。#61）の状態
 *   （カーソル、属性、代替画面、スクロール領域など）
 * - 持ち主はマシンの側（今はテスト用の結線。正式にはデバイスモデル #65）。画面ブロックは、これを「映す」だけ
 *   （tick の終わりに同期の差分を取り出して送る）。1台に複数の画面・大型モニター（#138）でも、持ち主は変わらない
 * - 書き込みはプロセスのスレッドで行う（コンソールの TTY に書いたプロセスが、自分のスレッドで端末を処理する。燃料で払う）。
 *   メインスレッドからも書く（カーネルのメッセージ、キー入力のエコー、同期の差分の取り出し）
 * - 状態はすべてこのオブジェクトの中にあり、[lock] で守る。保存（#81）は、[lock] を持って中身を写せば、一貫した状態になる
 *   （保存の形式は #81 で決める）
 *
 * スレッド: 中身（[text]・[pixels]・端末）を読み書きするときは、どのスレッドでも [lock] を持つ。
 * [lock] を持ったままカーネルロックを取ってよいが、逆（カーネルロックを持ったまま [lock] を取る）はしない。
 *
 * ```java
 * DisplayDevice display = DisplayDevice.forTier(ScreenTier.COLOR);
 * display.getLock().lock();
 * try {
 *     display.getText().write(0, 0, "hello", 0xFFFFFF, 0);
 * } finally {
 *     display.getLock().unlock();
 * }
 * ```
 *
 * @property text 文字画面のバッファ
 * @property maxColors 色数（2・16・256 など）。端末はこれに合わせて色を丸める
 * @param pixelWidth ピクセル画面の幅。ピクセル描画できない画面では 0
 * @param pixelHeight ピクセル画面の高さ。ピクセル描画できない画面では 0
 */
@ApiStatus.Internal
public class DisplayDevice
@JvmOverloads
@Throws(IllegalArgumentException::class)
public constructor(
    public val text: TextBuffer,
    public val maxColors: Int,
    private val pixelWidth: Int = 0,
    private val pixelHeight: Int = 0,
) {
    init {
        require(pixelWidth >= 0 && pixelHeight >= 0) { "pixel size must not be negative: ${pixelWidth}x$pixelHeight" }
    }

    /** 中身（[text]・[pixels]・端末の状態）を守るロック */
    public val lock: ReentrantLock = ReentrantLock()

    /** 端末からの応答（カーソル位置の問い合わせへの答えなど）を流す先のマシン。`null` なら捨てる */
    @Volatile
    internal var replyTo: Machine? = null

    /** 端末（ANSI の解釈。#61）。[lock] を持って使う */
    internal val terminal: AnsiTerminal = AnsiTerminal(
        text,
        maxColors,
        object : TerminalListener {
            override fun reply(bytes: ByteArray) {
                replyTo?.offerConsoleInput(bytes)
            }
        },
    )

    /** マシンのコンソールの出力を書く口（[Machine.consoleSink]）。プロセスのスレッドから、[lock] を持って呼ばれる */
    internal val consoleSink: ConsoleSink = object : ConsoleSink {
        override val lock: ReentrantLock
            get() = this@DisplayDevice.lock

        override fun write(bytes: ByteArray): Long {
            terminal.write(bytes, 0, bytes.size)
            return terminal.cost
        }
    }

    private var pixelBuffer: PixelBuffer? = null

    /** ピクセル描画ができるか */
    public val supportsPixels: Boolean
        get() = pixelWidth > 0 && pixelHeight > 0

    /**
     * ピクセル画面のバッファ。まだなければ作る（大きいので、使うまで作らない）。ピクセル描画できない画面では `null`。
     * [lock] を持って呼ぶ
     */
    public fun pixels(): PixelBuffer? {
        pixelBuffer?.let { return it }
        if (!supportsPixels) return null
        // 作った時点から、文字画面と同じ記録（同期の差分）に描画を残す
        return PixelBuffer(pixelWidth, pixelHeight).also {
            it.log = text.log
            pixelBuffer = it
        }
    }

    /** ピクセル画面のバッファをもう作っていれば返す。作っていなければ `null`（作らない） */
    public fun pixelsIfCreated(): PixelBuffer? = pixelBuffer

    /** ピクセル画面のバッファをもう作ったか */
    public val hasPixels: Boolean
        get() = pixelBuffer != null

    /** 端末を最初の状態に戻し、画面を消す（電源を入れ直したときなど）。[lock] を持たずに呼んでよい */
    public fun resetTerminal() {
        lock.lock()
        try {
            terminal.reset()
        } finally {
            lock.unlock()
        }
    }

    override fun toString(): String = "DisplayDevice(${text.width}x${text.height}, $maxColors colors)"

    public companion object {
        /** [tier] の最大の大きさと色数の表示デバイスを作る */
        @JvmStatic
        public fun forTier(tier: ScreenTier): DisplayDevice =
            DisplayDevice(tier.createTextBuffer(), tier.maxColors, tier.maxPixelWidth, tier.maxPixelHeight)
    }
}
