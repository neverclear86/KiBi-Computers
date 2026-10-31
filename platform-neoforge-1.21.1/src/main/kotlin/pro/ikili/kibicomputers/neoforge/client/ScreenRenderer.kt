package pro.ikili.kibicomputers.neoforge.client

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.network.chat.Style
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.FormattedCharSequence
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.display.PixelBuffer
import pro.ikili.kibicomputers.core.display.ScreenCells
import pro.ikili.kibicomputers.core.display.ScreenDetail
import pro.ikili.kibicomputers.core.display.ScreenLayout
import pro.ikili.kibicomputers.core.display.TextBuffer
import pro.ikili.kibicomputers.neoforge.display.ScreenBlock
import pro.ikili.kibicomputers.neoforge.display.ScreenBlockEntity
import java.util.concurrent.atomic.AtomicInteger

/**
 * 画面ブロックの正面に、画面バッファの中身を描く（#139）。
 *
 * 描く順（奥から）: 画面の地（黒）→ ピクセル画面（3段目で、使われているときだけ）→ 文字の背景 → 文字。
 * 既定の背景色（黒）のセルは地と同じなので描かず、ピクセル画面が透けて見える。
 *
 * 負荷を下げるために、次のことをする。
 * - カメラが画面の裏側にいるときは描かない。
 * - 距離に応じて文字を省く（[ScreenDetail]）。
 * - 背景は同じ色が続くセルを1つの矩形にまとめる（[ScreenCells]）。
 * - ピクセル画面はテクスチャにして、中身が変わったとき（[PixelBuffer.revision]）だけ送り直す。
 */
class ScreenRenderer(context: BlockEntityRendererProvider.Context) : BlockEntityRenderer<ScreenBlockEntity> {
    private val font: Font = context.font

    override fun render(
        be: ScreenBlockEntity,
        partialTick: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        val camera = Minecraft.getInstance().gameRenderer.mainCamera.position
        val detail = ScreenDetail.forDistance(Vec3.atCenterOf(be.blockPos).distanceToSqr(camera))
        if (detail == ScreenDetail.NONE) return
        val text = be.text
        val layout = ScreenLayout.fit(text.width, text.height)

        poseStack.pushPose()
        moveToContent(poseStack, be, layout)
        val pose = poseStack.last().pose()
        val w = layout.contentWidth.toFloat()
        val h = layout.contentHeight.toFloat()

        val quads = bufferSource.getBuffer(RenderType.textBackground())
        quad(quads, pose, 0f, 0f, w, h, Z_BASE, TextBuffer.DEFAULT_BACKGROUND)
        if (be.hasPixels) be.pixels?.let { drawPixels(be, it, pose, bufferSource, w, h) }
        // 別の描画の種類のバッファを取ると前のバッファは使えなくなるので、取り直す
        val backgrounds = bufferSource.getBuffer(RenderType.textBackground())
        ScreenCells.forEachBackgroundRun(text, TextBuffer.DEFAULT_BACKGROUND) { x, y, length, rgb ->
            val x0 = (x * ScreenLayout.CELL_WIDTH).toFloat()
            val y0 = (y * ScreenLayout.CELL_HEIGHT).toFloat()
            quad(backgrounds, pose, x0, y0, x0 + length * ScreenLayout.CELL_WIDTH, y0 + ScreenLayout.CELL_HEIGHT, Z_BACKGROUND, rgb)
        }
        if (detail == ScreenDetail.FULL) drawGlyphs(text, poseStack, bufferSource)
        poseStack.popPose()
    }

    // ブロックの原点から、正面の中身の左上（x は右、y は下、単位はフォントの単位）へ移す
    private fun moveToContent(poseStack: PoseStack, be: ScreenBlockEntity, layout: ScreenLayout) {
        val facing = be.blockState.getValue(ScreenBlock.FACING)
        poseStack.translate(0.5f, 0.5f, 0.5f)
        // 南向きのとき、正面は +z の面で、右が +x
        poseStack.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()))
        poseStack.translate(-0.5f + layout.offsetX, 0.5f - layout.offsetY, 0.5f + FACE_OFFSET)
        poseStack.scale(layout.scale, -layout.scale, layout.scale)
    }

    private fun drawGlyphs(text: TextBuffer, poseStack: PoseStack, bufferSource: MultiBufferSource) {
        poseStack.pushPose()
        poseStack.translate(0f, 0f, Z_GLYPH)
        val pose = poseStack.last().pose()
        ScreenCells.forEachGlyph(text) { x, y, codePoint, foreground, cells ->
            val glyph = FormattedCharSequence.codepoint(codePoint, STYLE)
            // フォントの文字は幅がまちまちなので、セルの真ん中に置く
            val advance = font.width(glyph)
            val cellWidth = cells * ScreenLayout.CELL_WIDTH
            val gx = x * ScreenLayout.CELL_WIDTH + (cellWidth - (advance - 1)) / 2f
            val gy = (y * ScreenLayout.CELL_HEIGHT).toFloat()
            font.drawInBatch(
                glyph, gx, gy, OPAQUE or foreground, false, pose, bufferSource,
                Font.DisplayMode.POLYGON_OFFSET, 0, LightTexture.FULL_BRIGHT,
            )
        }
        poseStack.popPose()
    }

    private fun drawPixels(
        be: ScreenBlockEntity,
        pixels: PixelBuffer,
        pose: Matrix4f,
        bufferSource: MultiBufferSource,
        w: Float,
        h: Float,
    ) {
        val texture = (be.renderCache as? PixelTexture)?.takeIf { it.fits(pixels) }
            ?: PixelTexture(pixels.width, pixels.height).also {
                be.renderCache?.close()
                be.renderCache = it
            }
        texture.update(pixels)
        val consumer = bufferSource.getBuffer(RenderType.text(texture.location))
        consumer.addVertex(pose, 0f, 0f, Z_PIXELS).setColor(-1).setUv(0f, 0f).setLight(LightTexture.FULL_BRIGHT)
        consumer.addVertex(pose, 0f, h, Z_PIXELS).setColor(-1).setUv(0f, 1f).setLight(LightTexture.FULL_BRIGHT)
        consumer.addVertex(pose, w, h, Z_PIXELS).setColor(-1).setUv(1f, 1f).setLight(LightTexture.FULL_BRIGHT)
        consumer.addVertex(pose, w, 0f, Z_PIXELS).setColor(-1).setUv(1f, 0f).setLight(LightTexture.FULL_BRIGHT)
    }

    // 文字の四角形と同じ頂点の順（左上・左下・右下・右上）で描く
    private fun quad(consumer: VertexConsumer, pose: Matrix4f, x0: Float, y0: Float, x1: Float, y1: Float, z: Float, rgb: Int) {
        val color = OPAQUE or rgb
        consumer.addVertex(pose, x0, y0, z).setColor(color).setLight(LightTexture.FULL_BRIGHT)
        consumer.addVertex(pose, x0, y1, z).setColor(color).setLight(LightTexture.FULL_BRIGHT)
        consumer.addVertex(pose, x1, y1, z).setColor(color).setLight(LightTexture.FULL_BRIGHT)
        consumer.addVertex(pose, x1, y0, z).setColor(color).setLight(LightTexture.FULL_BRIGHT)
    }

    override fun getViewDistance(): Int = ScreenDetail.COLORS_DISTANCE.toInt()

    // 画面の裏側からは見えないので描かない
    override fun shouldRender(be: ScreenBlockEntity, cameraPos: Vec3): Boolean {
        if (!super.shouldRender(be, cameraPos)) return false
        val facing = be.blockState.getValue(ScreenBlock.FACING)
        val face = Vec3.atCenterOf(be.blockPos).add(facing.stepX * 0.5, 0.0, facing.stepZ * 0.5)
        val toCamera = cameraPos.subtract(face)
        return toCamera.x * facing.stepX + toCamera.z * facing.stepZ > 0
    }

    /** ピクセル画面のテクスチャ。中身が変わったときだけ送り直す。 */
    private class PixelTexture(val width: Int, val height: Int) : AutoCloseable {
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
    }

    private companion object {
        /** 画面に使うフォント（`assets/kibicomputers/font/terminal.json`）。 */
        val FONT: ResourceLocation = ResourceLocation.fromNamespaceAndPath(KiBiComputers.MOD_ID, "terminal")
        val STYLE: Style = Style.EMPTY.withFont(FONT)
        val NEXT_ID = AtomicInteger()

        const val OPAQUE = 0xFF000000.toInt()

        // ブロックの面から少し浮かせて、面と重ならないようにする（ブロックの単位）
        const val FACE_OFFSET = 0.002f

        // 層ごとの手前へのずれ（フォントの単位。scale を掛けるとブロックの単位になる）
        const val Z_BASE = 0f
        const val Z_PIXELS = 0.25f
        const val Z_BACKGROUND = 0.5f
        const val Z_GLYPH = 0.75f
    }
}
