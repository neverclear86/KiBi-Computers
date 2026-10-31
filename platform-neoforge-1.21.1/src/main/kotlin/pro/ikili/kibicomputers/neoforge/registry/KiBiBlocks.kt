package pro.ikili.kibicomputers.neoforge.registry

import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.material.MapColor
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.registries.DeferredBlock
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredItem
import net.neoforged.neoforge.registries.DeferredRegister
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.display.ScreenTier
import pro.ikili.kibicomputers.neoforge.display.ScreenBlock
import pro.ikili.kibicomputers.neoforge.display.ScreenBlockEntity

/** ブロック・アイテム・ブロックエンティティ・クリエイティブタブの登録。 */
object KiBiBlocks {
    private val BLOCKS = DeferredRegister.createBlocks(KiBiComputers.MOD_ID)
    private val ITEMS = DeferredRegister.createItems(KiBiComputers.MOD_ID)
    private val BLOCK_ENTITY_TYPES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, KiBiComputers.MOD_ID)
    private val CREATIVE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, KiBiComputers.MOD_ID)

    /** ティアごとの画面ブロック（`screen_1`〜`screen_3`）。ティアの順に並ぶ。 */
    val SCREENS: Map<ScreenTier, DeferredBlock<ScreenBlock>> = ScreenTier.entries.associateWith { tier ->
        BLOCKS.registerBlock("screen_${tier.level}", { ScreenBlock(tier, it) }, screenProperties())
    }

    /** 画面ブロックのアイテム。 */
    val SCREEN_ITEMS: Map<ScreenTier, DeferredItem<BlockItem>> =
        SCREENS.mapValues { (_, block) -> ITEMS.registerSimpleBlockItem(block) }

    /** すべてのティアの画面ブロックで共通のブロックエンティティ。 */
    val SCREEN_BLOCK_ENTITY: DeferredHolder<BlockEntityType<*>, BlockEntityType<ScreenBlockEntity>> =
        BLOCK_ENTITY_TYPES.register("screen") { ->
            val blocks = SCREENS.values.map { it.get() }.toTypedArray()
            // データフィクサーの型は使わない（MOD のブロックエンティティでは null を渡すのが通例）
            @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
            BlockEntityType.Builder.of(::ScreenBlockEntity, *blocks).build(null)
        }

    /** MOD のクリエイティブタブ。 */
    val TAB: DeferredHolder<CreativeModeTab, CreativeModeTab> = CREATIVE_TABS.register("main") { ->
        CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.${KiBiComputers.MOD_ID}"))
            .icon { ItemStack(SCREEN_ITEMS.getValue(ScreenTier.COLOR).get()) }
            .displayItems { _, output -> SCREEN_ITEMS.values.forEach { output.accept(it.get()) } }
            .build()
    }

    /** MOD のイベントバスに登録する。MOD の初期化で1回だけ呼ぶ。 */
    fun register(modBus: IEventBus) {
        BLOCKS.register(modBus)
        ITEMS.register(modBus)
        BLOCK_ENTITY_TYPES.register(modBus)
        CREATIVE_TABS.register(modBus)
    }

    // 硬さは仮の値（#251 で確定）。鉄ブロックより柔らかく、ツルハシで壊す
    private fun screenProperties(): BlockBehaviour.Properties =
        BlockBehaviour.Properties.of()
            .mapColor(MapColor.METAL)
            .strength(2.0f, 6.0f)
            .sound(SoundType.METAL)
            .requiresCorrectToolForDrops()
}
