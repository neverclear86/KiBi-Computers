package pro.ikili.kibicomputers.neoforge.machine

import net.minecraft.world.level.storage.LevelResource
import net.neoforged.fml.ModList
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import pro.ikili.kibicomputers.core.KiBiComputers
import com.mojang.logging.LogUtils
import pro.ikili.kibicomputers.core.persist.MachineIdIndex
import pro.ikili.kibicomputers.core.persist.MachineSaveStore
import pro.ikili.kibicomputers.core.persist.SaveWriter

/**
 * サーバーのワールドのマシンの保存データの置き場（#81、ADR 0081）。根は `<world>/kibicomputers/machines`（ディメンションによらず1つ）。
 *
 * [pro.ikili.kibicomputers.neoforge.computer.WorldDisks] と同じく、サーバーの起動で [MachineSaveStore] と ID の索引（[MachineIdIndex]）を作り、
 * 止まったら捨てる。ディレクトリはここでは作らない（マシンを初めて取り出すとき・持ち主を確かめるときに作る）。
 *
 * 書き込みのスレッド（[SaveWriter]。サーバーに1本。#84、ADR 0084）も起動で作り、[ServerStoppedEvent] で閉じて join する
 * （チャンクの保存が積んだ掃除を流しきる。上限は [pro.ikili.kibicomputers.core.persist.MachineSaveConfig.writerFlushMillis]）。
 */
object WorldMachineSaves {
    private val LOGGER = LogUtils.getLogger()

    @Volatile
    private var store: MachineSaveStore? = null

    /** 動いているサーバーの書き込みのスレッド（GameTest が仕事を差し込んで止める） */
    @Volatile
    internal var writer: SaveWriter? = null
        private set

    /** 動いているサーバーの置き場。サーバーが動いていなければ `null` */
    val current: MachineSaveStore?
        get() = store

    /** NeoForge のイベントバスに登録する。MOD の初期化で1回だけ呼ぶ。 */
    fun register() {
        NeoForge.EVENT_BUS.addListener(::onServerAboutToStart)
        NeoForge.EVENT_BUS.addListener(::onServerStopped)
    }

    private fun onServerAboutToStart(event: ServerAboutToStartEvent) {
        // 前のサーバーのものが残っていれば閉じる（統合サーバーを開き直したときなど）
        close()
        val root = event.server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()
        val version = ModList.get().getModContainerById(KiBiComputers.MOD_ID).map { it.modInfo.version.toString() }.orElse("unknown")
        val config = ServerMachines.saveConfig
        val w = SaveWriter()
        writer = w
        store = MachineSaveStore(
            root.resolve(KiBiComputers.MOD_ID).resolve("machines"), MachineIdIndex(), version, w, config.writerMaxPendingBytes, config.writerFlushMillis,
        )
    }

    private fun onServerStopped(event: ServerStoppedEvent) {
        close()
    }

    private fun close() {
        val s = store ?: return
        store = null
        writer = null
        val timeout = ServerMachines.saveConfig.writerFlushMillis
        if (!s.close(timeout)) LOGGER.warn("The computer save writer did not finish within {} ms", timeout)
    }
}
