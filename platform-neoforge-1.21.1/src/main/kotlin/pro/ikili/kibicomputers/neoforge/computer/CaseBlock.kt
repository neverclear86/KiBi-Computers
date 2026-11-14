package pro.ikili.kibicomputers.neoforge.computer

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Containers
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Explosion
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
import java.util.function.BiConsumer

/**
 * ケースブロック（#121）。ティアごとに別のブロックとして登録する。
 *
 * 正面（[FACING]）は置いたプレイヤーの方を向く。右クリックで部品スロットと電源の GUI（[CaseMenu]）を開く。
 * 部品と電源の状態は [CaseBlockEntity] が持つ。壊すと、挿していた部品も落とす。
 *
 * 持ち主と権限（#131、ADR 0131）: 置いた本物のプレイヤーが持ち主になる。権限のない人は GUI を開けず、壊せない（破壊は
 * [CaseAccess] の `BreakEvent`）。持ち主のあるケースは爆発で壊れない。持ち主のないケースは誰でも使え、これまでどおり壊れる。
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

    // 持ち主は常に置き換える（クリエイティブのピックブロックで写したアイテムの NBT から、他人の持ち主を引き継がない）。
    // BlockItem.place は BLOCK_ENTITY_DATA を当てた後にこれを呼ぶ
    override fun setPlacedBy(level: Level, pos: BlockPos, state: BlockState, placer: LivingEntity?, stack: ItemStack) {
        super.setPlacedBy(level, pos, state, placer, stack)
        if (level.isClientSide) return
        (level.getBlockEntity(pos) as? CaseBlockEntity)?.replacePlayerAccess(CaseAccess.placedAccess(placer))
    }

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hitResult: BlockHitResult,
    ): InteractionResult {
        if (!level.isClientSide && player is ServerPlayer) {
            val be = level.getBlockEntity(pos) as? CaseBlockEntity ?: return InteractionResult.PASS
            if (!be.touchAccess(player)) {
                CaseAccess.deny(player, be.playerAccess)
                return InteractionResult.FAIL
            }
            player.openMenu(be) { buf -> CaseMenu.writeOpenData(buf, be.tier) }
        }
        return InteractionResult.sidedSuccess(level.isClientSide)
    }

    // プレイヤーの破壊と爆発のときだけ、マシンの保存データのディレクトリを消す（#81。ブロックを運ぶ MOD の setRemoved では消さない）。
    // 権限のない人の破壊は、これより前の BreakEvent で止まる（#131。CaseAccess）
    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        if (!level.isClientSide) (level.getBlockEntity(pos) as? CaseBlockEntity)?.deleteSaves()
        return super.playerWillDestroy(level, pos, state, player)
    }

    /**
     * 持ち主のある（読めなかった記録を含む）ケースは、どの爆発でも壊れない（#131、ADR 0131）。ドロップ（ルートテーブル）を作る前の
     * ここで止める（[onBlockExploded] だけで止めると、ブロックが残るのにケースのアイテムが落ちる）。
     *
     * クライアントでは常に何もしない。クライアントは持ち主を知らないので、爆発のパケットで予測して消さない。持ち主のないケースは、
     * サーバーの `setBlock(AIR)` のブロック更新で消える
     */
    override fun onExplosionHit(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        explosion: Explosion,
        dropConsumer: BiConsumer<ItemStack, BlockPos>,
    ) {
        if (level.isClientSide) return
        if (isOwned(level, pos)) return
        super.onExplosionHit(state, level, pos, explosion, dropConsumer)
    }

    // 他 MOD が直接呼ぶ経路への備えとして、ここでも持ち主のあるケースを守る。クライアントでは何もしない（onExplosionHit と同じ）
    override fun onBlockExploded(state: BlockState, level: Level, pos: BlockPos, explosion: Explosion) {
        if (level.isClientSide) return
        if (isOwned(level, pos)) return
        (level.getBlockEntity(pos) as? CaseBlockEntity)?.deleteSaves()
        super.onBlockExploded(state, level, pos, explosion)
    }

    private fun isOwned(level: Level, pos: BlockPos): Boolean =
        (level.getBlockEntity(pos) as? CaseBlockEntity)?.playerAccess?.hasOwner == true

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
