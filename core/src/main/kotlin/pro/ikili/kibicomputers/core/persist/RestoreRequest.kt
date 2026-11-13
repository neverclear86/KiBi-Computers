package pro.ikili.kibicomputers.core.persist

import org.jetbrains.annotations.ApiStatus
import pro.ikili.kibicomputers.core.kernel.BootConfig
import pro.ikili.kibicomputers.core.kernel.Machine
import java.util.UUID

/**
 * 戻す保存データの在りか（#85）: 置き場 [store] の [id] の、NBT が指す世代 [generation]
 * （`claim` の後の世代。書き込み中なら予約の世代。ADR 0084 の規則 4）。
 * 実際に読む世代は [MachineSaveStore.restorableGeneration] が決める（規則 9・10）
 */
@ApiStatus.Internal
public class RestoreSource(
    public val store: MachineSaveStore,
    public val id: UUID,
    public val generation: Long,
) {
    override fun toString(): String = "RestoreSource($id, $generation)"
}

/**
 * [Machine.restore] の入力（#85、ADR 0085）。
 *
 * @property source 戻す保存データ
 * @property bootConfig 戻せなかったときの冷えた起動の設定。戻したマシンの [Machine.lastBootConfig] にもなる（ADR 0049。`reboot()` が効く）
 * @property listener 終わりの知らせ（メインスレッドで、マシンの通知の列から届く）。`null` なら、[RestoreOutcome.COLD_BOOT] では
 *   マシンが自分で [Machine.coldBootAfterRestore] を呼ぶ
 */
@ApiStatus.Internal
public class RestoreRequest @JvmOverloads constructor(
    public val source: RestoreSource,
    public val bootConfig: BootConfig,
    public val listener: RestoreListener? = null,
)

/**
 * 復元の終わりの知らせ（#85）。メインスレッドで、マシンの通知の列から1回だけ届く。
 * 後段の失敗（カーネルの不整合。[pro.ikili.kibicomputers.core.kernel.MachineState.CRASHED]）では呼ばない（ADR 0085）。
 * 知らせは [Machine.close] の後も配られるので、受け手は自分の今のマシンか確かめる（ADR 0085）。
 * これが返った後に、マシンが復元の `quiesce` を閉じる（そこで初めてプロセスに step が割り当てられる）
 */
@ApiStatus.Internal
public fun interface RestoreListener {
    /** [machine] の復元が [outcome] で終わった。[cause] は [RestoreOutcome.COLD_BOOT] のときの理由（ほかは `null`） */
    public fun onRestoreFinished(machine: Machine, outcome: RestoreOutcome, cause: ColdBootCause?)
}

/** 復元の結果（#85） */
@ApiStatus.Internal
public enum class RestoreOutcome {
    /** 保存した状態に戻した（マシンは [pro.ikili.kibicomputers.core.kernel.MachineState.RUNNING]） */
    RESTORED,

    /**
     * 戻せなかった（カーネルの表は空のまま）。マシンは「冷えた起動待ち」で、[Machine.coldBootAfterRestore] で
     * [RestoreRequest.bootConfig] の起動を始める
     */
    COLD_BOOT,

    /**
     * 取り消した（`stop()`・`close()`）。マシンは STOPPED のまま（`StateChanged` は出さない）。
     * 冷えた起動待ちになった後、知らせを配る前に取り消したときもこれ（[COLD_BOOT] は届かない。#576）
     */
    CANCELLED,
}

/** 冷えた起動にした理由（#85。ログ・数え口・テスト用） */
@ApiStatus.Internal
public enum class ColdBootCause {
    /** 電源は入っていたが ID がない（一度も取り出さずに落ちた・`owner.bin` が書けない） */
    NO_IDENTITY,

    /** `claim` が新しい ID を振った（`NEW_ID_*`） */
    NEW_ID,

    /** 期限を過ぎても、その ID の書き込みが終わらない */
    WRITES_PENDING,

    /** 戻せる世代がない（`restorableGeneration` が空） */
    NO_GENERATION,

    /** 保存データを書いた MOD のバージョンが今のものと違う（差分の基準のものを含む。#88、ADR 0088） */
    MOD_VERSION,

    /** `KILLED` 節の理由が 0 以外（起動中・再起動の待ち・init を殺した写し） */
    KILLED_SECTION,

    /** 読めない・壊れている・`KILLED` 節が読めない・差分の基準がない・合わない */
    UNREADABLE,

    /** モジュールが置き場にない */
    MODULE_MISSING,

    /** 読み込み直したモジュールのハッシュが保存データと合わない・読み込めない */
    MODULE_MISMATCH,

    /** 戻せないプロセス（ABI 層 0・p1 以外、入口と状態の組み合わせが表にない、BLOCKING のドライバー） */
    UNSUPPORTED_PROCESS,

    /** init が終わっている・表にない */
    NO_LIVE_INIT,

    /** ディスク・イメージが開けない、`/tmp` の中身がない、知らないマウント */
    MOUNT,

    /** RAM が足りない（区画・テーブル・`/tmp`。プロセス・マシン・サーバー全体の RAM の勘定が確保を断った） */
    NO_MEMORY,

    /** そのほかの失敗（インスタンスを作り直せない（状態が合わない・start 関数を持つ・リンクできない）、内部の例外） */
    RESTORE_FAILED,
}
