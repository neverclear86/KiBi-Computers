package pro.ikili.kibicomputers.neoforge.machine

import net.minecraft.world.level.storage.LevelResource
import net.neoforged.fml.ModList
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.persist.MachineIdIndex
import pro.ikili.kibicomputers.core.persist.MachineSaveStore

/**
 * サーバーのワールドのマシンの保存データの置き場（#81、ADR 0081）。根は `<world>/kibicomputers/machines`（ディメンションによらず1つ）。
 *
 * [pro.ikili.kibicomputers.neoforge.computer.WorldDisks] と同じく、サーバーの起動で [MachineSaveStore] と ID の索引（[MachineIdIndex]）を作り、
 * 止まったら捨てる。ディレクトリはここでは作らない（マシンを初めて取り出すとき・持ち主を確かめるときに作る）。
 */
object WorldMachineSaves {
    @Volatile
    private var store: MachineSaveStore? = null

    /** 動いているサーバーの置き場。サーバーが動いていなければ `null` */
    val current: MachineSaveStore?
        get() = store

    /** NeoForge のイベントバスに登録する。MOD の初期化で1回だけ呼ぶ。 */
    fun register() {
        NeoForge.EVENT_BUS.addListener(::onServerAboutToStart)
        NeoForge.EVENT_BUS.addListener(::onServerStopped)
    }

    private fun onServerAboutToStart(event: ServerAboutToStartEvent) {
        val root = event.server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()
        val version = ModList.get().getModContainerById(KiBiComputers.MOD_ID).map { it.modInfo.version.toString() }.orElse("unknown")
        store = MachineSaveStore(root.resolve(KiBiComputers.MOD_ID).resolve("machines"), MachineIdIndex(), version)
    }

    private fun onServerStopped(event: ServerStoppedEvent) {
        store = null
    }
}
