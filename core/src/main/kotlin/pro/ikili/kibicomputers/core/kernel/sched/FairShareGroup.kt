package pro.ikili.kibicomputers.core.kernel.sched

import org.jetbrains.annotations.ApiStatus
import java.util.UUID

/**
 * 公平の組（#738、ADR 0738）。サーバー全体の早い者勝ちの上限（同時に WASM を実行する数の許可・ブロッキング型の枠・実行ファイルの
 * 読み込みの待ち行列・画面の送信の予算）を、まず組の間で順番に回し、組ごとの上限をかけるための鍵。
 *
 * 組はケースのプレイヤーの持ち主（ADR 0131 の `PlayerAccess` の持ち主）ごとに1つ。持ち主のないケース（`/setblock`・構造物・
 * FakePlayer が置いたもの、#131 より前のワールドのもの）と、読めなかった記録（locked）は、まとめて1つの組 [NONE] に入れる
 * （台ごとの組にすると、自動設置で置くだけで、公平と組ごとの上限から逃れられるため）。
 *
 * core はプレイヤーを知らないので、接続層がマシンを作るときに持ち主の UUID を渡す（`MachineEnvironment.Builder.fairnessKey`）。
 * ADR 0081 の「owner」（`owner.bin`。マシンの ID の持ち主）や `ProgramCache` のマシンごとの勘定とは別物。
 * 実行時だけのもので、保存しない。値で比べる（同じ UUID なら同じ組）。
 */
@ApiStatus.Internal
public class FairShareGroup private constructor(
    /** 持ち主のプレイヤーの UUID（[NONE] なら `null`） */
    public val playerId: UUID?,
) {
    override fun equals(other: Any?): Boolean = other is FairShareGroup && other.playerId == playerId

    override fun hashCode(): Int = playerId?.hashCode() ?: 0

    override fun toString(): String = if (playerId == null) "FairShareGroup.NONE" else "FairShareGroup($playerId)"

    public companion object {
        /** 持ち主なし（と、読めなかった記録）の組 */
        @JvmField
        public val NONE: FairShareGroup = FairShareGroup(null)

        /** 持ち主 [playerId] の組。`null` なら [NONE] */
        @JvmStatic
        public fun of(playerId: UUID?): FairShareGroup = if (playerId == null) NONE else FairShareGroup(playerId)
    }
}
