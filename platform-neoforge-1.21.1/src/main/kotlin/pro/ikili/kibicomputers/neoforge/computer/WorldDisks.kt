package pro.ikili.kibicomputers.neoforge.computer

import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.minecraft.world.level.storage.LevelResource
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.computer.DiskStore

/**
 * サーバーのワールドの内蔵ディスクの置き場（#124）。根は `<world>/kibicomputers/disks`（ディメンションによらず1つ）。
 *
 * [pro.ikili.kibicomputers.neoforge.machine.ServerMachines] と同じく、サーバーの起動で作り、止まったら捨てる。
 * ここではディレクトリを作らない（マシンが初めて開くときにカーネルが作る）。
 */
object WorldDisks {
    @Volatile
    private var store: DiskStore? = null

    /** 動いているサーバーのディスクの置き場。サーバーが動いていなければ `null` */
    val current: DiskStore?
        get() = store

    /** NeoForge のイベントバスに登録する。MOD の初期化で1回だけ呼ぶ。 */
    fun register() {
        NeoForge.EVENT_BUS.addListener(::onServerAboutToStart)
        NeoForge.EVENT_BUS.addListener(::onServerStopped)
    }

    private fun onServerAboutToStart(event: ServerAboutToStartEvent) {
        val root = event.server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()
        store = DiskStore(root.resolve(KiBiComputers.MOD_ID).resolve("disks"))
    }

    private fun onServerStopped(event: ServerStoppedEvent) {
        store = null
    }
}
