package pro.ikili.kibicomputers.neoforge.display

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.DirectionProperty
import net.minecraft.world.phys.BlockHitResult
import pro.ikili.kibicomputers.core.display.ScreenTier
import pro.ikili.kibicomputers.neoforge.input.KeyboardInput

/**
 * 画面ブロック（#136）。ティアごとに別のブロックとして登録する。
 *
 * 正面（[FACING]）は置いたプレイヤーの方を向く。画面の中身は [ScreenBlockEntity] が持つ。
 * キーボードがつながっていれば（[KeyboardInput.findKeyboard]）、右クリックでそのキーボードへの入力 GUI を開く（#142）。
 */
class ScreenBlock(val tier: ScreenTier, properties: BlockBehaviour.Properties) : Block(properties), EntityBlock {
    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH))
    }

    override fun codec(): MapCodec<out Block> = CODEC

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(FACING, context.horizontalDirection.opposite)

    override fun rotate(state: BlockState, rotation: Rotation): BlockState =
        state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    override fun mirror(state: BlockState, mirror: Mirror): BlockState =
        state.rotate(mirror.getRotation(state.getValue(FACING)))

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = ScreenBlockEntity(pos, state)

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hitResult: BlockHitResult,
    ): InteractionResult {
        val keyboard = KeyboardInput.findKeyboard(level, pos) ?: return InteractionResult.PASS
        if (level.isClientSide) KeyboardInput.openOnClient(keyboard, pos)
        return InteractionResult.sidedSuccess(level.isClientSide)
    }

    companion object {
        /** 画面の正面の向き（水平の4方向）。 */
        @JvmField
        val FACING: DirectionProperty = HorizontalDirectionalBlock.FACING

        private val TIER_CODEC: Codec<ScreenTier> = Codec.INT.xmap(ScreenTier::fromLevel) { it.level }

        @JvmField
        val CODEC: MapCodec<ScreenBlock> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                TIER_CODEC.fieldOf("tier").forGetter(ScreenBlock::tier),
                propertiesCodec<ScreenBlock>(),
            ).apply(instance, ::ScreenBlock)
        }
    }
}
