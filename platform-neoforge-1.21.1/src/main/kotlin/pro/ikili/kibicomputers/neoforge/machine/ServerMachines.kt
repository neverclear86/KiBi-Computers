package pro.ikili.kibicomputers.neoforge.machine

import com.mojang.logging.LogUtils
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import pro.ikili.kibicomputers.core.kernel.SchedulerConfig
import pro.ikili.kibicomputers.core.machine.MachineErrorListener
import pro.ikili.kibicomputers.core.machine.MachineManager

/** サーバーの中のマシンのキー: ディメンションとブロックの座標。 */
data class MachineKey(val dimension: ResourceKey<Level>, val pos: BlockPos) {
    companion object {
        /** [level] の [pos] のキー。[pos] は変わらない座標に写す。 */
        fun of(level: Level, pos: BlockPos): MachineKey = MachineKey(level.dimension(), pos.immutable())
    }
}

/**
 * サーバー側のマシン管理（#120）の接続層。サーバーの起動・停止・tick を、`core` の [MachineManager] につなぐ。
 *
 * - サーバーの起動（[ServerAboutToStartEvent]。ワールドとチャンクを読み込む前）に [MachineManager] を作る
 * - 毎 tick の始め（[ServerTickEvent.Pre]）に [MachineManager.tick] を呼ぶ
 * - サーバーが止まった後（[ServerStoppedEvent]）に全マシンを止め、スレッドが終わるのを待つ（上限 [SHUTDOWN_TIMEOUT_MILLIS]）
 *
 * マシンの中や接続層の通知で起きた例外は、サーバーを落とさずログに書く。
 * ブロックエンティティは `core` の [pro.ikili.kibicomputers.core.machine.MachineSlot] を持ち、[current] に登録する。
 */
object ServerMachines {
    private val LOGGER = LogUtils.getLogger()

    /** 停止のときに、マシンのスレッドが終わるのを待つ上限 */
    const val SHUTDOWN_TIMEOUT_MILLIS: Long = 5_000

    private val ERRORS = MachineErrorListener { message, error -> LOGGER.error(message, error) }

    @Volatile
    private var manager: MachineManager<MachineKey>? = null

    /** 動いているサーバーのマシン管理。サーバーが動いていなければ `null` */
    val current: MachineManager<MachineKey>?
        get() = manager

    /** NeoForge のイベントバスに登録する。MOD の初期化で1回だけ呼ぶ。 */
    fun register() {
        NeoForge.EVENT_BUS.addListener(::onServerAboutToStart)
        NeoForge.EVENT_BUS.addListener(::onServerTick)
        NeoForge.EVENT_BUS.addListener(::onServerStopped)
    }

    private fun onServerAboutToStart(event: ServerAboutToStartEvent) {
        // 前のサーバーのものが残っていれば止める（統合サーバーを開き直したときなど）
        manager?.let { stop(it) }
        // 設定はまだ仮の値（コンフィグにするのは #251）
        manager = MachineManager(SchedulerConfig.DEFAULT, ERRORS)
    }

    private fun onServerTick(event: ServerTickEvent.Pre) {
        manager?.tick()
    }

    private fun onServerStopped(event: ServerStoppedEvent) {
        val m = manager ?: return
        manager = null
        stop(m)
    }

    private fun stop(m: MachineManager<MachineKey>) {
        val count = m.size
        try {
            if (!m.shutdown(SHUTDOWN_TIMEOUT_MILLIS)) {
                LOGGER.warn("Computer threads did not finish within {} ms", SHUTDOWN_TIMEOUT_MILLIS)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            LOGGER.error("Failed to stop computers", e)
        }
        LOGGER.info("Stopped {} computers", count)
    }
}
