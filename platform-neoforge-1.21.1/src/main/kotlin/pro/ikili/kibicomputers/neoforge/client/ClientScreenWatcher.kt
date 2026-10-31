package pro.ikili.kibicomputers.neoforge.client

import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import pro.ikili.kibicomputers.core.display.ScreenDetail
import pro.ikili.kibicomputers.neoforge.display.ScreenBlockEntity
import pro.ikili.kibicomputers.neoforge.display.ScreenSync
import pro.ikili.kibicomputers.neoforge.display.ScreenWatchPayload
import java.util.Collections
import java.util.IdentityHashMap

/**
 * クライアントで、どの画面を「見ている」かを決めて、サーバーに知らせる（#140）。
 *
 * 描画（[ScreenRenderer]）が中身を描く距離（[ScreenDetail.COLORS_DISTANCE]）に入った画面を見はじめ、
 * それより [HYSTERESIS] ブロック遠ざかったらやめる。見ていない画面には、サーバーは何も送らない。
 */
object ClientScreenWatcher {
    /** 見はじめる距離と、やめる距離の差（ブロックの数）。境目を行き来したときに知らせが続かないようにする。 */
    const val HYSTERESIS: Double = 8.0

    private val watching: MutableSet<ScreenBlockEntity> = Collections.newSetFromMap(IdentityHashMap())

    fun onClientTick(@Suppress("UNUSED_PARAMETER") event: ClientTickEvent.Post) {
        val mc = Minecraft.getInstance()
        val player = mc.player
        val connection = mc.connection
        if (player == null || connection == null || mc.level == null) {
            // ワールドを出たら、知らせる相手もいない
            watching.forEach { it.clientWatching = false }
            watching.clear()
            return
        }
        if (!connection.hasChannel(ScreenWatchPayload.TYPE)) return
        // 消えた画面（チャンクの読み込みが外れたなど）は、サーバーも見ている人から外すが、念のため知らせる
        val it = watching.iterator()
        while (it.hasNext()) {
            val screen = it.next()
            if (screen.isRemoved || screen.level !== mc.level) {
                it.remove()
                screen.clientWatching = false
                screen.resetSync()
                PacketDistributor.sendToServer(ScreenWatchPayload(screen.blockPos, false))
            }
        }
        val eye = player.eyePosition
        for (screen in ScreenSync.clientScreens) {
            if (screen.level !== mc.level) continue
            val d2 = Vec3.atCenterOf(screen.blockPos).distanceToSqr(eye)
            val start = ScreenDetail.COLORS_DISTANCE
            val stop = start + HYSTERESIS
            if (!screen.clientWatching && d2 <= start * start) {
                screen.clientWatching = true
                watching.add(screen)
                PacketDistributor.sendToServer(ScreenWatchPayload(screen.blockPos, true))
            } else if (screen.clientWatching && d2 > stop * stop) {
                screen.clientWatching = false
                watching.remove(screen)
                screen.resetSync()
                PacketDistributor.sendToServer(ScreenWatchPayload(screen.blockPos, false))
            }
        }
    }
}
