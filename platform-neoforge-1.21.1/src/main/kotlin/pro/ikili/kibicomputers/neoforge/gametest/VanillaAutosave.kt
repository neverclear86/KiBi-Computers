package pro.ikili.kibicomputers.neoforge.gametest

import net.minecraft.server.MinecraftServer
import java.lang.reflect.Field

/**
 * バニラの自動保存までの tick 数（`MinecraftServer.ticksUntilAutosave`。private）を読み書きする（負荷試験の本番の形の段階用。#834）。
 *
 * 試験のためだけに Access Transformer を足すと製品の jar に入るので、試験のコードの中だけで反射を使う。
 * 1.21.1 の NeoForge は実行時も Mojang の名前。`tickServer` は tick の終わりにこの値を1つ減らし、0 以下になったら
 * `saveEverything` を呼んで次の間隔（tick rate × 300。20 TPS で 6000）に戻す。メインスレッドだけで使う
 */
internal class VanillaAutosave(private val server: MinecraftServer) {
    private val handle: Field = MinecraftServer::class.java.getDeclaredField(FIELD).apply { isAccessible = true }

    var ticksUntilAutosave: Int
        get() = handle.getInt(server)
        set(value) = handle.setInt(server, value)

    companion object {
        private const val FIELD = "ticksUntilAutosave"
    }
}
