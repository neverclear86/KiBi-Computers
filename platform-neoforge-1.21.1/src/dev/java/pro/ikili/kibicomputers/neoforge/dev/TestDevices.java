package pro.ikili.kibicomputers.neoforge.dev;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import org.jetbrains.annotations.Nullable;
import pro.ikili.kibicomputers.api.device.Device;
import pro.ikili.kibicomputers.neoforge.api.KibiCapabilities;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 他 MOD 役の試験用デバイスの登録（#153。開発環境の GameTest・負荷試験でだけ読み込む。jar には入れない）。
 * 公開 API（{@link KibiCapabilities#DEVICE}）を Java から使う例も兼ねる。
 *
 * <ul>
 * <li>樽（ブロックエンティティあり）: ブロックエンティティごとにキャッシュした {@link TestReactor} を出す</li>
 * <li>ロードストーン（ブロックエンティティなし）: 位置ごとの振る舞い（{@link #behave}）。なければ位置ごとにキャッシュした
 *     {@link TestReactor}。普通のブロックなので、置いた・壊した・振る舞いを変えたら、作者の役として
 *     {@code level.invalidateCapabilities(pos)} を呼ぶ</li>
 * </ul>
 */
public final class TestDevices {
    private TestDevices() {}

    /** 公開 API の型（Java から見た形の確かめ） */
    public static final BlockCapability<Device, @Nullable Direction> CAPABILITY = KibiCapabilities.DEVICE;

    private static final Map<BlockEntity, TestReactor> BARRELS = new WeakHashMap<>();
    private static final Map<BlockPos, TestReactor> PLAIN = new ConcurrentHashMap<>();
    private static final Map<BlockPos, Function<Direction, Device>> BEHAVIORS = new ConcurrentHashMap<>();

    /** MOD のイベントバスに登録する（{@code kibicomputers.dev.testDevices} のときだけ、本体から呼ばれる） */
    public static void register(IEventBus modBus) {
        modBus.addListener(RegisterCapabilitiesEvent.class, TestDevices::onRegisterCapabilities);
    }

    private static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(CAPABILITY, BlockEntityType.BARREL, (be, side) -> barrel(be));
        event.registerBlock(CAPABILITY, (level, pos, state, be, side) -> plain(pos, side), Blocks.LODESTONE);
    }

    /** 樽 {@code be} のデバイス（同じブロックエンティティには同じ実体） */
    public static synchronized TestReactor barrel(BlockEntity be) {
        return BARRELS.computeIfAbsent(be, b -> new TestReactor(TestReactor.KIND));
    }

    private static @Nullable Device plain(BlockPos pos, @Nullable Direction side) {
        Function<Direction, Device> behavior = BEHAVIORS.get(pos);
        if (behavior != null) return behavior.apply(side);
        return PLAIN.computeIfAbsent(pos.immutable(), p -> new TestReactor(TestReactor.KIND));
    }

    /** ロードストーンの位置 {@code pos}（絶対座標）の振る舞いを決める（{@code null} を返せば、その面には出さない） */
    public static void behave(BlockPos pos, Function<Direction, Device> behavior) {
        BEHAVIORS.put(pos.immutable(), behavior);
    }

    /** ロードストーンの位置 {@code pos} の、振る舞いを決めていないときのデバイス */
    public static TestReactor plainAt(BlockPos pos) {
        return PLAIN.computeIfAbsent(pos.immutable(), p -> new TestReactor(TestReactor.KIND));
    }

    /** 位置 {@code pos} の振る舞いとキャッシュを忘れる（テストの後始末） */
    public static void forget(BlockPos pos) {
        BEHAVIORS.remove(pos);
        PLAIN.remove(pos);
    }
}
