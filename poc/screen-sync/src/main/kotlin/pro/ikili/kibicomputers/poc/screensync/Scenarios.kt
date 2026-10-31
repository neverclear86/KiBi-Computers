package pro.ikili.kibicomputers.poc.screensync

import kotlin.math.sin
import kotlin.random.Random

/**
 * 測定する画面の使い方。[tick] は 1 tick（50ms）の間にプログラムが描く内容。
 * 乱数は種を固定するので、同じシナリオは方式によらず同じ描画になる。
 */
sealed class Scenario(val name: String, val text: Boolean) {
    abstract fun tick(s: Screen, t: Int, rnd: Random)

    companion object {
        val all: List<Scenario> = listOf(
            Idle, Typing, Scroll, Dashboard, FullRedraw,
            Sprite, Shapes, Scope, VideoBlit, VideoSetPixel,
        )
    }
}

private val palette = intArrayOf(
    0x000000, 0xAA0000, 0x00AA00, 0xAA5500, 0x0000AA, 0xAA00AA, 0x00AAAA, 0xAAAAAA,
    0x555555, 0xFF5555, 0x55FF55, 0xFFFF55, 0x5555FF, 0xFF55FF, 0x55FFFF, 0xFFFFFF,
)

private fun Random.word(len: Int): String = buildString {
    repeat(len) { append(('a'.code + nextInt(26)).toChar()) }
}

/** 何も描かない（待機中の端末）。 */
object Idle : Scenario("待機", true) {
    override fun tick(s: Screen, t: Int, rnd: Random) {}
}

/** 1 tick に1文字ずつ打つ（エディタ・シェルでの入力）。 */
object Typing : Scenario("1文字ずつ入力", true) {
    override fun tick(s: Screen, t: Int, rnd: Random) {
        s as TextScreen
        val x = t % s.width
        val y = (t / s.width) % s.height
        s.set(x, y, ('a'.code + rnd.nextInt(26)).toChar().toString(), 0xFFFFFF, 0x000000)
    }
}

/** 1 tick に3行出力してスクロールする（ログの流し読み、`cat` の大きなファイル）。 */
object Scroll : Scenario("ログ出力でスクロール", true) {
    override fun tick(s: Screen, t: Int, rnd: Random) {
        s as TextScreen
        repeat(3) {
            s.copy(0, 1, s.width, s.height - 1, 0, -1)
            s.fill(0, s.height - 1, s.width, 1, ' '.code, 0xFFFFFF, 0x000000)
            s.set(0, s.height - 1, rnd.word(rnd.nextInt(s.width / 3, s.width)), 0xFFFFFF, 0x000000)
        }
    }
}

/** 画面の約5%のセルを色付きで書き換える（htop のような監視画面）。 */
object Dashboard : Scenario("監視画面（5%更新）", true) {
    override fun tick(s: Screen, t: Int, rnd: Random) {
        s as TextScreen
        var budget = s.width * s.height / 20
        while (budget > 0) {
            val len = rnd.nextInt(4, 9)
            s.set(rnd.nextInt(s.width), rnd.nextInt(s.height), rnd.word(len), palette[rnd.nextInt(16)], palette[rnd.nextInt(8)])
            budget -= len
        }
    }
}

/** 毎 tick 全画面を書き直す（全画面のゲーム、最悪の場合）。 */
object FullRedraw : Scenario("毎tick全画面を書き直す", true) {
    override fun tick(s: Screen, t: Int, rnd: Random) {
        s as TextScreen
        for (y in 0 until s.height) {
            s.set(0, y, rnd.word(s.width), palette[rnd.nextInt(16)], palette[rnd.nextInt(8)])
        }
    }
}

/** 16x16 のスプライトが動く（小さなゲーム）。 */
object Sprite : Scenario("スプライト移動", false) {
    private val img = IntArray(16 * 16) { if ((it % 16 + it / 16) % 3 == 0) 0xFFCC00 else 0x3366FF }

    override fun tick(s: Screen, t: Int, rnd: Random) {
        s as PixelScreen
        val span = s.width - 16
        fun pos(tt: Int) = (tt * 3) % span
        if (t > 0) s.fillRect(pos(t - 1), s.height / 2, 16, 16, 0)
        s.blit(pos(t), s.height / 2, 16, 16, img)
    }
}

/** 1 tick に線20本と矩形5個を描き足す（お絵かき・グラフ）。 */
object Shapes : Scenario("線と矩形を描き足す", false) {
    override fun tick(s: Screen, t: Int, rnd: Random) {
        s as PixelScreen
        repeat(20) {
            s.line(rnd.nextInt(s.width), rnd.nextInt(s.height), rnd.nextInt(s.width), rnd.nextInt(s.height), palette[rnd.nextInt(16)])
        }
        repeat(5) {
            s.fillRect(rnd.nextInt(s.width), rnd.nextInt(s.height), rnd.nextInt(4, 40), rnd.nextInt(4, 40), palette[rnd.nextInt(16)])
        }
    }
}

/** 左へ2px流して右端に波形を描く（オシロスコープ・推移グラフ）。 */
object Scope : Scenario("波形を横に流す", false) {
    override fun tick(s: Screen, t: Int, rnd: Random) {
        s as PixelScreen
        s.copy(2, 0, s.width - 2, s.height, -2, 0)
        s.fillRect(s.width - 2, 0, 2, s.height, 0)
        fun y(tt: Int) = (s.height / 2 + sin(tt * 0.2) * s.height / 3).toInt()
        s.line(s.width - 3, y(t - 1), s.width - 1, y(t), 0x00FF00)
    }
}

private fun plasma(w: Int, h: Int, t: Int, x: Int, y: Int): Int {
    val v = sin(x * 0.05 + t * 0.3) + sin(y * 0.07 - t * 0.2) + sin((x + y) * 0.03 + t * 0.1)
    val k = ((v + 3) / 6 * 255).toInt().coerceIn(0, 255)
    return (k shl 16) or ((255 - k) shl 8) or (k / 2)
}

/** 毎 tick 全画面の画像を転送する（動画、最悪の場合）。 */
object VideoBlit : Scenario("動画（全画面を画像転送）", false) {
    override fun tick(s: Screen, t: Int, rnd: Random) {
        s as PixelScreen
        s.blit(0, 0, s.width, s.height, IntArray(s.width * s.height) { plasma(s.width, s.height, t, it % s.width, it / s.width) })
    }
}

/** 毎 tick 全ピクセルを1点ずつ打つ（B にとっての最悪の場合）。 */
object VideoSetPixel : Scenario("動画（1点ずつ打つ）", false) {
    override fun tick(s: Screen, t: Int, rnd: Random) {
        s as PixelScreen
        for (y in 0 until s.height) for (x in 0 until s.width) s.setPixel(x, y, plasma(s.width, s.height, t, x, y))
    }
}
