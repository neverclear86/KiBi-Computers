package pro.ikili.kibicomputers.neoforge.computer

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Containers
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
import pro.ikili.kibicomputers.core.computer.CaseTier

/**
 * ケースブロック（#121）。ティアごとに別のブロックとして登録する。
 *
 * 正面（[FACING]）は置いたプレイヤーの方を向く。右クリックで部品スロットと電源の GUI（[CaseMenu]）を開く。
 * 部品と電源の状態は [CaseBlockEntity] が持つ。壊すと、挿していた部品も落とす。
 */
class CaseBlock(val tier: CaseTier, properties: BlockBehaviour.Properties) : Block(properties), EntityBlock {
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

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = CaseBlockEntity(pos, state)

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hitResult: BlockHitResult,
    ): InteractionResult {
        if (!level.isClientSide && player is ServerPlayer) {
            val be = level.getBlockEntity(pos) as? CaseBlockEntity ?: return InteractionResult.PASS
            player.openMenu(be) { buf -> CaseMenu.writeOpenData(buf, be.tier) }
        }
        return InteractionResult.sidedSuccess(level.isClientSide)
    }

    @Deprecated("Deprecated in Java")
    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, movedByPiston: Boolean) {
        if (!state.`is`(newState.block)) {
            (level.getBlockEntity(pos) as? CaseBlockEntity)?.let { be ->
                for (i in 0 until be.parts.slots) {
                    Containers.dropItemStack(level, pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble(), be.parts.getStackInSlot(i))
                }
            }
        }
        @Suppress("DEPRECATION")
        super.onRemove(state, level, pos, newState, movedByPiston)
    }

    companion object {
        /** ケースの正面の向き（水平の4方向）。 */
        @JvmField
        val FACING: DirectionProperty = HorizontalDirectionalBlock.FACING

        private val TIER_CODEC: Codec<CaseTier> = Codec.INT.xmap(CaseTier::fromLevel) { it.level }

        @JvmField
        val CODEC: MapCodec<CaseBlock> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                TIER_CODEC.fieldOf("tier").forGetter(CaseBlock::tier),
                propertiesCodec<CaseBlock>(),
            ).apply(instance, ::CaseBlock)
        }
    }
}
