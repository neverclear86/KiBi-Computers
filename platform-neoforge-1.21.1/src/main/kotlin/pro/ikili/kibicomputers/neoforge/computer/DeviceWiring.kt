package pro.ikili.kibicomputers.neoforge.computer

import com.mojang.logging.LogUtils
import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.world.level.Level
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import pro.ikili.kibicomputers.core.device.wiring.DeviceRegistry
import pro.ikili.kibicomputers.core.device.wiring.ExternalDeviceSlots
import pro.ikili.kibicomputers.core.device.wiring.rethrowIfFatal
import java.util.Collections
import java.util.IdentityHashMap

/**
 * サーバーの、位置から引くデバイスの登録表（#129、ADR 0129）と、つなぎ直しの予約。サーバーのメインスレッドからだけ使う。
 *
 * - 画面・キーボードのブロックエンティティは、サーバーで読み込まれたら [registry] に載り（`onLoad`）、壊れた・アンロードしたら外れる
 *   （`setRemoved`・`onChunkUnloaded`）。キーは（ディメンション、座標）の [GlobalPos]、世代は登録ごとに振る
 * - 結線のつなぎ直し（[CaseWiring.rewire]）は、頼まれたら（[schedule]）tick の終わり（[ServerTickEvent.Post]）にまとめて行う。
 *   チャンクの読み込みの途中や `onLoad` の中ではつながない。置いたブロックのデータ（address）は `setBlock` の後で読み込まれるので、
 *   tick の終わりにはそろっている（#535）
 * - 頼まれない台は何もしない（tick ごとの巡回はしない）
 * - 他 MOD のデバイス（#153）は、ケースが見つけたら [externalSlots] がアダプターを登録表に載せる。つなぎ直しはケースごとに
 *   受け、投げても残りのケースを続ける（[guardedFailures]）
 */
internal object DeviceWiring {
    /** 登録表 */
    val registry: DeviceRegistry<GlobalPos> = DeviceRegistry()

    /** 他 MOD のデバイスのアダプターの出し入れ（#153） */
    val externalSlots: ExternalDeviceSlots<GlobalPos> = ExternalDeviceSlots(registry)

    private val LOGGER = LogUtils.getLogger()

    // つなぎ直しを頼まれた結線（同じものは1回にまとめる）
    private val pending: MutableSet<CaseWiring> = Collections.newSetFromMap(IdentityHashMap())

    /** 1 tick の終わりに、つなぎ直しが続けて頼まれたときに回す回数の上限（残りは次の tick） */
    private const val MAX_ROUNDS = 4

    /** つなぎ直しを頼まれた回数（テスト・負荷試験用） */
    var scheduledCount: Long = 0
        private set

    /** つなぎ直しを行った回数（テスト・負荷試験用） */
    var rewireCount: Long = 0
        private set

    /** ケースのつなぎ直しが投げて、ここで受けた回数（ふつうは 0。テスト用） */
    var guardedFailures: Long = 0
        private set

    fun register() {
        NeoForge.EVENT_BUS.addListener<ServerTickEvent.Post> { flush() }
        NeoForge.EVENT_BUS.addListener<ServerStoppedEvent> {
            pending.clear()
            externalSlots.clear()
            registry.clear()
        }
    }

    /** [level] の [pos] のキー */
    fun keyOf(level: Level, pos: BlockPos): GlobalPos = GlobalPos.of(level.dimension(), pos.immutable())

    /** [wiring] のつなぎ直しを、この tick の終わりに行う */
    fun schedule(wiring: CaseWiring) {
        if (pending.add(wiring)) scheduledCount++
    }

    /** 頼まれたつなぎ直しを行う（tick の終わり。GameTest からも呼ぶ） */
    fun flush() {
        var rounds = 0
        while (pending.isNotEmpty() && rounds++ < MAX_ROUNDS) {
            val batch = pending.toTypedArray()
            pending.clear()
            for (w in batch) {
                rewireCount++
                try {
                    w.rewire()
                } catch (t: Throwable) {
                    // 1つのケースの誤りで、残りのケースのつなぎ直しとサーバーを止めない
                    rethrowIfFatal(t)
                    guardedFailures++
                    LOGGER.error("Rewiring a computer case failed", t)
                }
            }
        }
    }
}
