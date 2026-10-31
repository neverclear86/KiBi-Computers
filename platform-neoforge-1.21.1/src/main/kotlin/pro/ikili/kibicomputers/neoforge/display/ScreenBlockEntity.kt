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

    /** ピクセル画面のバッファ。ピクセル描画できないティアでは null。大きいので最初に使うときに作る。 */
    val pixels: PixelBuffer? by lazy { if (tier.supportsPixels) tier.createPixelBuffer() else null }
}
