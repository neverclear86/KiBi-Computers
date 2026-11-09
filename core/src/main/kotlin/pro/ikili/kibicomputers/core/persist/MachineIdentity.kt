package pro.ikili.kibicomputers.core.persist

import org.jetbrains.annotations.ApiStatus
import java.io.IOException
import java.util.UUID

/**
 * マシンの持ち主の位置（ディメンションの ID と座標。#81）。`owner.bin` と索引（[MachineIdIndex]）に入る。
 *
 * @property dimension ディメンションの ID（`minecraft:overworld` など）
 */
@ApiStatus.Internal
public data class MachineOwner(
    public val dimension: String,
    public val x: Int,
    public val y: Int,
    public val z: Int,
) {
    override fun toString(): String = "$dimension($x, $y, $z)"
}

/**
 * 確定したマシンの ID（#81）。ブロックエンティティの NBT に `MachineId`・`SaveGeneration`・`OwnerEpoch` として書く。
 *
 * @property id 変わらない ID（ディレクトリ `<uuid>/` の名前）
 * @property generation NBT が指す世代（最後に書いた世代ファイル。まだ書いていなければ 0）
 * @property epoch 持ち主の epoch（`owner.bin` と照らす）
 */
@ApiStatus.Internal
public data class MachineIdentity(
    public val id: UUID,
    public val generation: Long,
    public val epoch: Long,
)

/** 世代と epoch の範囲（#81） */
@ApiStatus.Internal
public object MachineIdentityLimits {
    /** 世代と epoch の上限（2^62）。0 以上これ以下だけを受け入れる */
    public const val MAX_COUNTER: Long = 1L shl 62

    /** [value] が世代・epoch の範囲（0 以上 [MAX_COUNTER] 以下）か */
    @JvmStatic
    public fun isValidCounter(value: Long): Boolean = value in 0..MAX_COUNTER
}

/** [MachineSaveStore.claim] の判定（#81、ADR 0081） */
@ApiStatus.Internal
public enum class ClaimResult {
    /** 自分が持ち主（`owner.bin` がなければ自分を持ち主として書いた） */
    KEPT,

    /** 持ち主の位置が違い、NBT の epoch が記録以上だったので引き取った（epoch + 1） */
    TAKEN_OVER,

    /** 索引に、同じ UUID の別の読み込み済みのブロックエンティティがいる → 新しい UUID */
    NEW_ID_DUPLICATE_LOADED,

    /** 古い写し（NBT の epoch が記録より小さい）→ 新しい UUID */
    NEW_ID_STALE,

    /** NBT の値が検査に外れた・引き取る epoch が上限 → 新しい UUID */
    NEW_ID_INVALID,

    /** `owner.bin` が壊れている・読み書きに失敗した → 新しい UUID */
    NEW_ID_UNREADABLE,
    ;

    /** 新しい UUID にした（冷えた起動） */
    public val isNewId: Boolean get() = this != KEPT && this != TAKEN_OVER
}

/**
 * [MachineSaveStore.claim] の結果。
 *
 * @property identity 確定した ID。新しい UUID の `owner.bin` も書けなかったときは `null`（ID なしとして続け、最初の取り出しで振り直す）
 * @property result 判定
 */
@ApiStatus.Internal
public class Claim(
    public val identity: MachineIdentity?,
    public val result: ClaimResult,
) {
    override fun toString(): String = "Claim($result, $identity)"
}

/**
 * [MachineSaveStore.write] の結果。
 *
 * @property identity 書いた後の ID（世代は書いた世代）
 * @property reassigned 書く前の確かめで、新しい UUID に振り直した
 * @property writes 実際に行った書き込み（fsync）の回数（世代ファイル 1 回 + 振り直した `owner.bin` の回数）
 * @property encodeNanos 符号化（[MachineSnapshotSource.build] と、バイト列にするところ）にかかった時間（ナノ秒。負荷試験用。#553）
 * @property fileWriteNanos ファイルの書き込み（世代ファイルと、振り直した `owner.bin` の tmp → fsync → rename）にかかった時間（ナノ秒。#553）
 * @property fileBytes 書いた世代のファイルの大きさ（バイト）
 * @property tmpfsBytes 世代のファイルの `TMPFS` 節の中身の大きさの合計（バイト。節の見出しは数えない）
 * @property delta 差分の世代を書いた（#83）
 */
@ApiStatus.Internal
public class WriteResult(
    public val identity: MachineIdentity,
    public val reassigned: Boolean,
    public val writes: Int,
    public val encodeNanos: Long = 0,
    public val fileWriteNanos: Long = 0,
    public val fileBytes: Int = 0,
    public val tmpfsBytes: Int = 0,
    public val delta: Boolean = false,
) {
    override fun toString(): String =
        "WriteResult($identity, reassigned=$reassigned, writes=$writes, encode=${encodeNanos}ns, fileWrite=${fileWriteNanos}ns, " +
            "$fileBytes bytes, tmpfs $tmpfsBytes bytes, delta=$delta)"
}

/** [MachineSaveStore.markChunkGeneration] の結果（#81） */
@ApiStatus.Internal
public enum class ChunkMarkResult {
    /** 印が進み、残さない世代ファイルを消した */
    ADVANCED,

    /** 印を戻した（ファイルは消さない） */
    MOVED_BACK,

    /** 同じ値（ファイルは消さない） */
    UNCHANGED,

    /** 位置が持ち主と違う。印もファイルも変えない */
    NOT_OWNER,

    /** その UUID の `owner.bin` を覚えていない。印もファイルも変えない */
    UNKNOWN_ID,
}

/**
 * 世代と UUID が決まってから、書き出す中身を作る（書く前の確かめで振り直したときに、UUID を入れ直すため。#81）。
 */
@ApiStatus.Internal
public fun interface MachineSnapshotSource {
    /** [id]・[generation] の保存データを作る。[modVersion] は書き出す MOD のバージョン */
    public fun build(id: UUID, generation: Long, modVersion: String): MachineSnapshot

    /**
     * 差分の写しなら、指す基準（#83。[build] が作る保存データの [MachineSnapshot.deltaBase] と同じ）。全体の写しなら `null`。
     * [MachineSaveStore.write] は、これが置き場の覚えている基準と合わなければ何も書かずに [DeltaBaseUnavailableException] を投げる
     */
    public val deltaBase: DeltaBase? get() = null

    /**
     * 置き場が [id] の世代 [generation] のファイルを書けた後に呼ぶ（#83。全体保存なら、マシンのプロセスの基準を進める）。
     * [id] は書いたファイルの UUID（振り直した後なら新しい UUID）
     */
    public fun committed(id: UUID, generation: Long) {}
}

/**
 * 差分の世代が指す基準（#83、ADR 0083）。`DELTA_BASE` 節の中身（世代・ファイルの長さ・CRC32C）と、外枠の UUID。
 *
 * @property id 基準の UUID（差分の世代の UUID と同じ）
 * @property generation 基準の世代
 * @property fileLength 基準のファイルの長さ（バイト）
 * @property crc32c 基準のファイルの CRC32C
 */
@ApiStatus.Internal
public data class DeltaBase(
    public val id: UUID,
    public val generation: Long,
    public val fileLength: Long,
    public val crc32c: Int,
)

/**
 * 差分の写しの基準が、置き場の覚えている基準と合わない（#83。[MachineSaveStore.write] が何も書かずに投げる）。
 * [MachineSaveStore.captureAndWrite] は、全体の写しを取り直して書く
 */
@ApiStatus.Internal
public class DeltaBaseUnavailableException(message: String) : IOException(message)

/**
 * 写しを取る（#83。[MachineSaveStore.captureAndWrite] が呼ぶ）
 */
@ApiStatus.Internal
public fun interface SnapshotCapturer {
    /**
     * [deltaBase] を基準に取り出す（`null` なら全体）。[retry] なら、止めている間に取り出し済みでも取り出す（取り直し）。
     * 取り出せなければ `null`
     */
    public fun capture(deltaBase: DeltaBase?, retry: Boolean): MachineSnapshotSource?
}

/**
 * 読んだ世代のファイル（#83。[MachineSaveStore.readGeneration]）
 *
 * @property snapshot 中身
 * @property fileLength ファイルの長さ
 * @property crc32c ファイルの CRC32C
 */
@ApiStatus.Internal
public class StoredSnapshot(
    public val snapshot: MachineSnapshot,
    public val fileLength: Long,
    public val crc32c: Int,
) {
    /** これを基準として指す [DeltaBase] */
    public fun asDeltaBase(): DeltaBase = DeltaBase(snapshot.machineId, snapshot.generation, fileLength, crc32c)
}

/**
 * [MachineSaveStore.captureAndWrite] の結果（#83）
 *
 * @property write 書いた結果
 * @property captureNanos 取り出しにかかった時間（取り直したら、その分も足す）
 * @property retried 基準が使えず、全体を取り直して書いた
 * @property delta 差分の世代を書いた
 */
@ApiStatus.Internal
public class CaptureWriteResult(
    public val write: WriteResult,
    public val captureNanos: Long,
    public val retried: Boolean,
    public val delta: Boolean,
) {
    override fun toString(): String = "CaptureWriteResult($write, capture=${captureNanos}ns, retried=$retried, delta=$delta)"
}

/**
 * 待機点保存の仮の値（#81。確定は #251）。
 *
 * @property autosavePeriodTicks 自動保存の周期（tick）
 * @property autosaveQuiesceTicks 自動保存で、止めてから [SaveReadiness.READY] になるまで待つ上限（tick）
 * @property autosaveWritesPerTick 自動保存の tick ごとの書き込み（fsync）の回数の予算。`owner.bin` を書く台は 2 と数える。
 *   書き込みはメインスレッドで同期して行う（#84 で別スレッドに移す）ので、fsync 1 回の時間 × 予算が tick に足される。
 *   仮の値 1 は、fsync が約 20 ms かかるディスク（開発機で測った値。ADR 0081）でも 20 TPS を保てるように選んだ。
 *   1,000 台の一巡は、ID を振る初回で約 2,000 tick、2回目から約 1,000 tick（周期 6000 tick より短い）
 * @property unloadWaitBudgetMillis チャンクのアンロードで、tick ごとに待機点を待つ時間の合計の上限
 * @property stopQuiesceMillis サーバーの停止で、全台が待機点に来るのを待つ上限
 * @property fullSaveChangedRatio 差分の世代を書く代わりに全体保存にする、「基準から変わった区画の数 ÷ 書き換えた区画の数」の境目（#83）。
 *   これを超えたら全体保存。仮の値 0.5（確定は #251）
 */
@ApiStatus.Internal
public data class MachineSaveConfig @JvmOverloads constructor(
    public val autosavePeriodTicks: Long = 6000,
    public val autosaveQuiesceTicks: Int = 4,
    public val autosaveWritesPerTick: Int = 1,
    public val unloadWaitBudgetMillis: Long = 50,
    public val stopQuiesceMillis: Long = 500,
    public val fullSaveChangedRatio: Double = DEFAULT_FULL_SAVE_CHANGED_RATIO,
) {
    init {
        require(autosavePeriodTicks > 0) { "自動保存の周期は 1 以上" }
        require(autosaveQuiesceTicks > 0) { "待つ tick は 1 以上" }
        require(autosaveWritesPerTick > 0) { "書き込みの予算は 1 以上" }
        require(unloadWaitBudgetMillis >= 0) { "アンロードの待ちの予算は 0 以上" }
        require(stopQuiesceMillis >= 0) { "停止の待ちは 0 以上" }
        require(fullSaveChangedRatio in 0.0..1.0) { "全体保存に切り替える比は 0 以上 1 以下" }
    }

    public companion object {
        /** [fullSaveChangedRatio] の仮の既定値（#83。確定は #251） */
        public const val DEFAULT_FULL_SAVE_CHANGED_RATIO: Double = 0.5

        /** 仮の既定値 */
        @JvmField
        public val DEFAULT: MachineSaveConfig = MachineSaveConfig()
    }
}
