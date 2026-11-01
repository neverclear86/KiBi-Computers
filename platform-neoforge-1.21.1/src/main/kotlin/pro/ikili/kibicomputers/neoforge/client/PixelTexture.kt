package pro.ikili.kibicomputers.neoforge.client

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.ResourceLocation
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.display.PixelBuffer
import pro.ikili.kibicomputers.neoforge.display.ScreenBlockEntity
import java.util.concurrent.atomic.AtomicInteger

/**
 * ピクセル画面のテクスチャ（#139）。中身が変わったとき（[PixelBuffer.revision]）だけ送り直す。
 * 画面ブロックの描画（[ScreenRenderer]）と入力 GUI（[KeyboardInputScreen]）が、画面ごとに1つを共有する。
 */
internal class PixelTexture(val width: Int, val height: Int) : AutoCloseable {
    val location: ResourceLocation = ResourceLocation.fromNamespaceAndPath(
        KiBiComputers.MOD_ID, "dynamic/screen_${NEXT_ID.incrementAndGet()}",
    )
    private val texture = DynamicTexture(NativeImage(width, height, false))
    private val row = IntArray(width)
    private var revision = -1L

    init {
        Minecraft.getInstance().textureManager.register(location, texture)
    }

    fun fits(pixels: PixelBuffer): Boolean = pixels.width == width && pixels.height == height

    fun update(pixels: PixelBuffer) {
        if (pixels.revision == revision) return
        revision = pixels.revision
        val image = texture.pixels ?: return
        for (y in 0 until height) {
            pixels.getPixels(0, y, width, 1, row, 0, width)
            for (x in 0 until width) {
                // 0xRRGGBB を NativeImage の ABGR にする
                val rgb = row[x]
                val abgr = OPAQUE or ((rgb and 0xFF) shl 16) or (rgb and 0xFF00) or ((rgb shr 16) and 0xFF)
                image.setPixelRGBA(x, y, abgr)
            }
        }
        texture.upload()
    }

    override fun close() {
        Minecraft.getInstance().textureManager.release(location)
    }

    companion object {
        private val NEXT_ID = AtomicInteger()
        private const val OPAQUE = 0xFF000000.toInt()

        /** 画面 [be] のピクセル画面 [pixels] のテクスチャを、最新の中身にして返す。大きさが変わっていれば作り直す。 */
        fun forScreen(be: ScreenBlockEntity, pixels: PixelBuffer): PixelTexture {
            val texture = (be.renderCache as? PixelTexture)?.takeIf { it.fits(pixels) }
                ?: PixelTexture(pixels.width, pixels.height).also {
                    be.renderCache?.close()
                    be.renderCache = it
                }
            texture.update(pixels)
            return texture
        }
    }
}
