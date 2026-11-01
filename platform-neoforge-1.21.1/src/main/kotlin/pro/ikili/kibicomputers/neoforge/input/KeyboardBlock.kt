package pro.ikili.kibicomputers.neoforge.input

import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
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
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * キーボードブロック（#142）。床に置く薄いブロックで、正面（[FACING]）は置いたプレイヤーの方を向く。
 *
 * 右クリックすると、このキーボードへの入力 GUI（`client/KeyboardInputScreen`）を開く。
 * 隣の画面を右クリックしても開く（[KeyboardInput.findKeyboard]）。受け取った入力は [KeyboardBlockEntity] が配る。
 */
class KeyboardBlock(properties: BlockBehaviour.Properties) : Block(properties), EntityBlock {
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

    // 照準の当たり（輪郭）は、ブロックの床全体にする（#348）。キーボードの見た目は奥行きが半分しかないので、
    // 見た目どおりの形だと、置いた直後の照準（手前の端）が下のブロックに当たり、右クリックしても GUI が開かなかった
    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        OUTLINE

    // ぶつかる形は見た目に合わせる
    override fun getCollisionShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        context: CollisionContext,
    ): VoxelShape = if (state.getValue(FACING).axis == Direction.Axis.X) SHAPE_X else SHAPE_Z

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = KeyboardBlockEntity(pos, state)

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hitResult: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) KeyboardInput.openOnClient(pos, null)
        return InteractionResult.sidedSuccess(level.isClientSide)
    }

    companion object {
        /** キーボードの正面（手前）の向き（水平の4方向）。 */
        @JvmField
        val FACING: DirectionProperty = HorizontalDirectionalBlock.FACING

        @JvmField
        val CODEC: MapCodec<KeyboardBlock> = simpleCodec(::KeyboardBlock)

        // 幅 14・奥行き 8・高さ 2（ピクセル）。モデル（models/block/keyboard.json）と合わせる
        private val SHAPE_Z: VoxelShape = box(1.0, 0.0, 4.0, 15.0, 2.0, 12.0)
        private val SHAPE_X: VoxelShape = box(4.0, 0.0, 1.0, 12.0, 2.0, 15.0)

        // 照準の当たり: 床全体で、高さはキーボードと同じ
        private val OUTLINE: VoxelShape = box(0.0, 0.0, 0.0, 16.0, 2.0, 16.0)
    }
}
