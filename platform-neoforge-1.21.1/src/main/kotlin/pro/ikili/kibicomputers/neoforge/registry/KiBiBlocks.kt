package pro.ikili.kibicomputers.neoforge.registry

import net.minecraft.core.HolderLookup
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Rarity
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
import pro.ikili.kibicomputers.core.computer.CaseTier
import pro.ikili.kibicomputers.core.computer.PartKind
import pro.ikili.kibicomputers.core.computer.PartTier
import pro.ikili.kibicomputers.core.display.ScreenTier
import pro.ikili.kibicomputers.neoforge.computer.CaseBlock
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
import pro.ikili.kibicomputers.neoforge.computer.CaseParts
import pro.ikili.kibicomputers.neoforge.computer.ComputerPartItem
import pro.ikili.kibicomputers.neoforge.display.ScreenBlock
import pro.ikili.kibicomputers.neoforge.display.ScreenBlockEntity
import pro.ikili.kibicomputers.neoforge.input.KeyboardBlock
import pro.ikili.kibicomputers.neoforge.input.KeyboardBlockEntity

/** ブロック・アイテム・ブロックエンティティ・クリエイティブタブの登録。 */
object KiBiBlocks {
    private val BLOCKS = DeferredRegister.createBlocks(KiBiComputers.MOD_ID)
    private val ITEMS = DeferredRegister.createItems(KiBiComputers.MOD_ID)
    private val BLOCK_ENTITY_TYPES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, KiBiComputers.MOD_ID)
    private val CREATIVE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, KiBiComputers.MOD_ID)

    /** ティアごとの画面ブロック（`screen_1`〜`screen_3`）。ティアの順に並ぶ。 */
    val SCREENS: Map<ScreenTier, DeferredBlock<ScreenBlock>> = ScreenTier.entries.associateWith { tier ->
        BLOCKS.registerBlock("screen_${tier.level}", { ScreenBlock(tier, it) }, machineProperties())
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

    /** ティアごとのケースブロック（`case_1`〜`case_3`、#121）。ティアの順に並ぶ。 */
    val CASES: Map<CaseTier, DeferredBlock<CaseBlock>> = CaseTier.entries.associateWith { tier ->
        BLOCKS.registerBlock("case_${tier.level}", { CaseBlock(tier, it) }, machineProperties())
    }

    /** ケースブロックのアイテム。 */
    val CASE_ITEMS: Map<CaseTier, DeferredItem<BlockItem>> =
        CASES.mapValues { (_, block) -> ITEMS.registerSimpleBlockItem(block) }

    /** すべてのティアのケースブロックで共通のブロックエンティティ。 */
    val CASE_BLOCK_ENTITY: DeferredHolder<BlockEntityType<*>, BlockEntityType<CaseBlockEntity>> =
        BLOCK_ENTITY_TYPES.register("case") { ->
            val blocks = CASES.values.map { it.get() }.toTypedArray()
            @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
            BlockEntityType.Builder.of(::CaseBlockEntity, *blocks).build(null)
        }

    /** 部品の種類（拡張カードを除く）。部品のアイテムがある種類 */
    private val PART_KINDS: List<PartKind> = PartKind.entries.filter { it != PartKind.EXPANSION }

    /**
     * 部品のアイテム（#347）。ティアごと・種類ごとに `<ティア>_<種類>`（例: `creative_cpu`）で登録する。
     * 今は検証用のクリエイティブのティアだけ（正式なティアは #122〜#125）。
     */
    val PARTS: Map<PartTier, Map<PartKind, DeferredItem<ComputerPartItem>>> = PartTier.entries.associateWith { tier ->
        PART_KINDS.associateWith { kind ->
            ITEMS.registerItem(ComputerPartItem.idOf(kind, tier), { ComputerPartItem(kind, tier, it) }, partProperties(tier))
        }
    }

    /** ティア [tier]・種類 [kind] の部品のアイテム */
    fun part(tier: PartTier, kind: PartKind): ComputerPartItem = PARTS.getValue(tier).getValue(kind).get()

    /** キーボードブロック（#142）。 */
    val KEYBOARD: DeferredBlock<KeyboardBlock> =
        BLOCKS.registerBlock("keyboard", ::KeyboardBlock, machineProperties().noOcclusion())

    /** キーボードブロックのアイテム。 */
    val KEYBOARD_ITEM: DeferredItem<BlockItem> = ITEMS.registerSimpleBlockItem(KEYBOARD)

    /** キーボードブロックのブロックエンティティ。 */
    val KEYBOARD_BLOCK_ENTITY: DeferredHolder<BlockEntityType<*>, BlockEntityType<KeyboardBlockEntity>> =
        BLOCK_ENTITY_TYPES.register("keyboard") { ->
            @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
            BlockEntityType.Builder.of(::KeyboardBlockEntity, KEYBOARD.get()).build(null)
        }

    /** MOD のクリエイティブタブ。 */
    val TAB: DeferredHolder<CreativeModeTab, CreativeModeTab> = CREATIVE_TABS.register("main") { ->
        CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.${KiBiComputers.MOD_ID}"))
            .icon { ItemStack(SCREEN_ITEMS.getValue(ScreenTier.COLOR).get()) }
            .displayItems { params, output ->
                CASE_ITEMS.values.forEach { output.accept(it.get()) }
                output.accept(creativeCase(params.holders()))
                PARTS.values.forEach { parts -> parts.values.forEach { output.accept(it.get()) } }
                SCREEN_ITEMS.values.forEach { output.accept(it.get()) }
                output.accept(KEYBOARD_ITEM.get())
            }
            .build()
    }

    /** MOD のイベントバスに登録する。MOD の初期化で1回だけ呼ぶ。 */
    fun register(modBus: IEventBus) {
        BLOCKS.register(modBus)
        ITEMS.register(modBus)
        BLOCK_ENTITY_TYPES.register(modBus)
        CREATIVE_TABS.register(modBus)
    }

    /**
     * 検証用のクリエイティブのケース（#347）: いちばん上のティアのケースに、起動に要る種類のスロットをすべて
     * クリエイティブの部品で埋めたもの。置くとすぐ電源を入れられる。ケースのブロックは同じで、
     * 部品はブロックエンティティのデータ（[DataComponents.BLOCK_ENTITY_DATA]）として持つ。
     */
    fun creativeCase(registries: HolderLookup.Provider): ItemStack {
        val tier = CaseTier.entries.last()
        val parts = CaseParts(tier)
        for (slot in 0 until tier.slotCount) {
            val kind = tier.kindOf(slot)
            if (kind in PART_KINDS) parts.setStackInSlot(slot, ItemStack(part(PartTier.CREATIVE, kind)))
        }
        val tag = CompoundTag()
        tag.put(CaseBlockEntity.PARTS_TAG, parts.serializeNBT(registries))
        val stack = ItemStack(CASE_ITEMS.getValue(tier).get())
        BlockItem.setBlockEntityData(stack, CASE_BLOCK_ENTITY.get(), tag)
        stack.set(DataComponents.ITEM_NAME, Component.translatable("item.${KiBiComputers.MOD_ID}.creative_case"))
        stack.set(DataComponents.RARITY, Rarity.EPIC)
        return stack
    }

    private fun partProperties(tier: PartTier): Item.Properties =
        Item.Properties().stacksTo(1).rarity(if (tier == PartTier.CREATIVE) Rarity.EPIC else Rarity.COMMON)

    // 画面・ケース・キーボードで共通。硬さは仮の値（#251 で確定）。鉄ブロックより柔らかく、ツルハシで壊す
    private fun machineProperties(): BlockBehaviour.Properties =
        BlockBehaviour.Properties.of()
            .mapColor(MapColor.METAL)
            .strength(2.0f, 6.0f)
            .sound(SoundType.METAL)
            .requiresCorrectToolForDrops()
}
