package pro.ikili.kibicomputers.neoforge.display

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import pro.ikili.kibicomputers.core.display.PixelBuffer
import pro.ikili.kibicomputers.core.display.ScreenTier
import pro.ikili.kibicomputers.core.display.TextBuffer
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks

/**
 * 画面ブロックのブロックエンティティ。ティアの最大の大きさの画面バッファ（`core`）を持つ。
 *
 * 画面の中身はコンピューターが描き直すものとして、今はセーブデータに書かない
 * （保存するかどうかと形式は、コンピューターとつなぐときに決める）。
 */
class ScreenBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(KiBiBlocks.SCREEN_BLOCK_ENTITY.get(), pos, state) {

    /** この画面のティア。ブロックから決まる。 */
    val tier: ScreenTier = (state.block as ScreenBlock).tier

    /** 文字画面のバッファ。 */
    val text: TextBuffer = tier.createTextBuffer()

    private val lazyPixels = lazy { if (tier.supportsPixels) tier.createPixelBuffer() else null }

    /** ピクセル画面のバッファ。ピクセル描画できないティアでは null。大きいので最初に使うときに作る。 */
    val pixels: PixelBuffer? by lazyPixels

    /** ピクセル画面のバッファをもう作ったか。まだなら描画（#139）はピクセルの層を描かない。 */
    val hasPixels: Boolean
        get() = lazyPixels.isInitialized() && lazyPixels.value != null

    /**
     * クライアントの描画が持つ資源（ピクセル画面のテクスチャなど）。サーバーでは常に null。
     * ブロックエンティティが消えるときに閉じる。
     */
    var renderCache: AutoCloseable? = null

    override fun setRemoved() {
        super.setRemoved()
        renderCache?.close()
        renderCache = null
    }
}
