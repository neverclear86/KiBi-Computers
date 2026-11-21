package pro.ikili.kibicomputers.neoforge.gametest

import net.minecraft.server.MinecraftServer
import java.lang.reflect.Field

/**
 * サーバーの次の tick の予定（`MinecraftServer.nextTickTimeNanos`。protected）をずらす（負荷試験の [LoadPhase.RESTORE] 用。#582）。
 *
 * 試験の呼び出しの中で同期に読み込むチャンク（`setChunkForced`）は、実際のサーバーなら読み込みのスレッドで進むので、tick の予定を
 * 遅らせない。試験ではメインスレッドが止まり、その分だけ予定から遅れて、追いつくまで tick が詰めて回る。読み込みにかかった時間だけ
 * 予定を後ろへずらし、読み込みがなかったときと同じ進み方にする。
 *
 * [VanillaAutosave] と同じく、試験のためだけに Access Transformer を足すと製品の jar に入るので、試験のコードの中だけで反射を使う。
 * `runServer` は tick の前に予定を1 tick 進めるので、tick の中でずらすと、この tick の残り（`haveTime`）と次の tick の待ちの両方に効く。
 * メインスレッドだけで使う
 */
internal class TickSchedule(private val server: MinecraftServer) {
    private val handle: Field = MinecraftServer::class.java.getDeclaredField(FIELD).apply { isAccessible = true }

    /** 予定を [nanos] だけ後ろへずらす */
    fun delay(nanos: Long) {
        handle.setLong(server, handle.getLong(server) + nanos)
    }

    companion object {
        private const val FIELD = "nextTickTimeNanos"
    }
}
