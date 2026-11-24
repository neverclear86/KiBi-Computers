package pro.ikili.kibicomputers.neoforge.machine

import net.minecraft.server.level.ServerLevel
import pro.ikili.kibicomputers.core.kernel.PublishedWorldClock
import java.util.IdentityHashMap

/**
 * ワールド（次元。[ServerLevel]）ごとのマシンの時計（#439）。マシンの環境に渡し、ゲーム内時間（`Level.getGameTime()`）を見せる。
 *
 * 時計は [ServerLevel] ごとに1つ。[ServerMachines] がサーバーの tick の始めに [publishAll] で各ワールドの gameTime を写す
 * （tick ごとの処理はワールドの数だけで、台数によらない）。マシンの環境は時計のインスタンスを直接持つので、
 * 表を空にしても（サーバーの停止）、動いているマシンの時計は最後に写した値のまま残る（停止時の保存に 0 を書かない）。
 *
 * 表を触るのはメインスレッドだけ（[of] はケースのブロックエンティティから、[publishAll]・[clear] はサーバーのイベントから）。
 * マシンのスレッドは時計の `volatile` の値を読むだけ。
 */
internal object WorldClocks {
    private val clocks = IdentityHashMap<ServerLevel, PublishedWorldClock>()

    /** [level] の時計。なければ作り、今の gameTime を初めの値にする（最初の tick を待たずに正しい値を返す）。メインスレッドから呼ぶ */
    fun of(level: ServerLevel): PublishedWorldClock = clocks.getOrPut(level) { PublishedWorldClock(level.gameTime) }

    /** 各ワールドの今の gameTime を時計に写す。サーバーの tick の始めに呼ぶ */
    fun publishAll() {
        for ((level, clock) in clocks) clock.publish(level.gameTime)
    }

    /** 表を空にする（サーバーの開始・停止。前のワールドの時計を使い回さない） */
    fun clear() {
        clocks.clear()
    }
}
