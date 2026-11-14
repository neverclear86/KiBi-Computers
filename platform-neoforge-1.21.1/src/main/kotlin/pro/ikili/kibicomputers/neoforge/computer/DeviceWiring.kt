package pro.ikili.kibicomputers.neoforge.computer

import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.world.level.Level
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import pro.ikili.kibicomputers.core.device.wiring.DeviceRegistry
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
 */
internal object DeviceWiring {
    /** 登録表 */
    val registry: DeviceRegistry<GlobalPos> = DeviceRegistry()

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

    fun register() {
        NeoForge.EVENT_BUS.addListener<ServerTickEvent.Post> { flush() }
        NeoForge.EVENT_BUS.addListener<ServerStoppedEvent> {
            pending.clear()
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
                w.rewire()
            }
        }
    }
}
