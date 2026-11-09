package pro.ikili.kibicomputers.core.persist

import org.jetbrains.annotations.ApiStatus
import pro.ikili.kibicomputers.core.util.CanonicalUuid
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.OptionalLong
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.CRC32C
import kotlin.concurrent.withLock

/**
 * マシンの保存データの置き場（#81、ADR 0081）。根は `<world>/kibicomputers/machines`。
 *
 * - `<root>/<uuid>/<世代>.bin`（世代は10進）が保存データ（[MachineSnapshot]）、`<root>/<uuid>/owner.bin` が持ち主（[OwnerRecord]）。
 *   どちらも tmp に書いて fsync し、`ATOMIC_MOVE` で置き換える
 * - 持ち主の規則（回答 A。epoch 方式）は [claim]。書く予約の時点（[submit]）にも、覚えている `owner.bin` と照らす
 * - NBT が指す世代（チャンクの印）は、チャンクの保存（`ChunkDataEvent.Save`）から [markChunkGeneration] で受け取る。
 *   残すのは「今の印」「前の印（2つ）」「覚えている最新の世代」と、印より大きい世代、それらがディスクにないときの直下の世代、
 *   書き込み待ちの世代とその基準。それ以外は印が進んだときに消す
 *
 * `owner.bin` の中身・印・書いた最新の世代はメモリに覚え（このサーバーが書いた・読んだもの）、毎回ディスクは読まない。
 * 印はメモリの上だけに持ち、保存しない（再起動の後は [claim] で読んだ世代から始まる）。
 *
 * 差分の世代（#83、ADR 0083）: 全体の世代を書けたら（完了を受け取ったら）、それを今の基準として覚える（メモリだけ。再起動の後の最初の保存は全体）。
 * 差分の世代は今の基準を指す（[deltaBaseFor] → 取り出し → [submit]。まとめたものが [captureAndSubmit]）。
 *
 * 書き込みのスレッド（#84、ADR 0084）: 世代の符号化（圧縮を含む）と書き込み、新しく振った UUID の `owner.bin`、古い世代の掃除、
 * ディレクトリの削除は、`executor` の列に入れて順に走らせる。メインスレッドは予約（[submit]。世代を決め、持ち主を確かめる）と、
 * 完了の受け取り（[pollCompleted]）だけをする。claim の `owner.bin` はメインスレッドで同期に書く（その UUID の仕事が列に残っていれば、
 * 先に待つ）。
 *
 * スレッド: 列の仕事のほかは、メインスレッドだけで使う。[hasCapacity]・[pendingBytes]・[pendingCount] はどのスレッドから読んでもよい。
 *
 * @property root 置き場の根
 * @property index サーバーの ID の索引
 * @param modVersion 保存データに書く MOD のバージョン
 * @param executor 保存の仕事を走らせる口（既定は呼んだスレッドでその場で走らせる [SaveExecutor.DIRECT]）
 * @param maxPendingBytes 書き込み待ちの写しの見積もりの合計の上限（[hasCapacity]）
 * @param claimWaitMillis [claim] がその UUID の仕事を待つ上限（過ぎたら新しい UUID にする）
 */
@ApiStatus.Internal
public class MachineSaveStore @JvmOverloads constructor(
    public val root: Path,
    public val index: MachineIdIndex,
    private val modVersion: String = "unknown",
    private val executor: SaveExecutor = SaveExecutor.DIRECT,
    maxPendingBytes: Long = Long.MAX_VALUE,
    claimWaitMillis: Long = MachineSaveConfig.DEFAULT_WRITER_FLUSH_MILLIS,
) {
    /** 書き込み待ちの写しの見積もりの合計の上限（バイト。GameTest が差し替える） */
    @Volatile
    public var maxPendingBytes: Long = maxPendingBytes

    /** [claim] がその UUID の仕事を待つ上限（ミリ秒） */
    public var claimWaitMillis: Long = claimWaitMillis

    // ---- メインスレッドだけが触る ----

    /** 覚えている `owner.bin`（このサーバーが書いた・読んだもの） */
    private val owners = HashMap<UUID, OwnerRecord>()

    /** チャンクの印（今の印と、前の印2つ） */
    private val marks = HashMap<UUID, Marks>()

    /** その UUID で書けた（完了を受け取った）最新の世代 */
    private val latest = HashMap<UUID, Long>()

    /** その UUID で予約した最大の世代（失敗しても戻さない。#84） */
    private val reserved = HashMap<UUID, Long>()

    /** その UUID の、予約して完了を受け取っていない世代 → 差分ならその基準の世代（全体なら `null`。#84） */
    private val inFlight = HashMap<UUID, HashMap<Long, Long?>>()

    /** その UUID の今の基準（次の差分が指す全体の世代。#83）。全体の世代の完了を受け取ったときに進む */
    private val bases = HashMap<UUID, DeltaBase>()

    /** その UUID の登録（claim・ID を振ったときに作り、`delete` で消す。古い登録の完了は表を進めない。#84） */
    private val registrations = HashMap<UUID, Registration>()

    /** 受け取ったが、まだ [pollCompleted] で返していない完了（[write] が自分の分だけ抜く） */
    private val ready = ArrayList<SaveCompletion>()

    // ---- 書き込みのスレッドの持ち物 ----

    /**
     * UUID → 世代 → その世代が指す基準の世代（全体の世代なら `null`。#83）。ファイルの頭の写し（キャッシュ）で、
     * 忘れても次に要るときに [deltaBaseReader] で読み直すだけ。消せた世代・`delete` で忘れる
     */
    private val baseOf = ConcurrentHashMap<UUID, HashMap<Long, Long?>>()

    /** このサーバーで書いたディレクトリ（初めて書く前に、残った `*.tmp` を片付ける。#84 の規則 8） */
    private val initialized: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /**
     * 新しく振った `owner.bin` が書けなかった UUID（後続の仕事は書かずに失敗で返す。#84 の規則 7b）。外すのは、メインスレッドが失敗を
     * 受け取り（[dropped]）、かつその UUID の仕事が残っていないとき（#566。仕事が尽きた時点で外すと、受け取る前に入れた後続が書かれる）
     */
    private val unusable: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /**
     * [unusable] のうち、メインスレッドが失敗の完了を受け取ったもの（#566）。[accept] は「ここに入れる → 仕事が残っているか見る」、
     * [finishTask] は「仕事の数を減らす → ここにあるか見る」の順なので、どちらかが必ず最後の仕事の後に [unusable] から外す
     */
    private val dropped: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    // ---- 両方が触る ----

    /** UUID → 列に入れて終わっていない仕事の数 */
    private val outstanding = ConcurrentHashMap<UUID, Int>()
    private val pendingBytesCounter = AtomicLong()
    private val pendingTasks = AtomicInteger()
    private val completed = ConcurrentLinkedQueue<Done>()
    private val waitLock = ReentrantLock()
    private val progress = waitLock.newCondition()

    /** 世代のファイルの頭から基準を読む（#83。テストが差し替える。既定は [MachineSnapshot.readDeltaBase]） */
    internal var deltaBaseReader: (Path) -> DeltaBase? = { path ->
        DataInputStream(BufferedInputStream(Files.newInputStream(path), 64)).use { MachineSnapshot.readDeltaBase(it) }
    }

    private class Marks(var current: Long? = null) {
        /** 印が最後に変わる直前の値（[previous2] はその1つ前）。`claim` 直後・ID の振り直し直後は空 */
        var previous: Long? = null
        var previous2: Long? = null

        fun moveTo(value: Long) {
            previous2 = previous
            previous = current
            current = value
        }
    }

    /** UUID の登録（中身は持たない。同じものかだけを見る） */
    private class Registration

    /** 書き込みのスレッドで終わった世代の書き込み（[pollCompleted] でメインスレッドが受け取る） */
    private class Done(
        val pending: PendingSave,
        val registration: Registration?,
        val self: MachineOwner,
        val snapshot: MachineSnapshotSource,
        val result: WriteResult?,
        val error: IOException?,
        /** 書けた全体の世代（冷えた起動の写しでないもの）なら、それを指す基準 */
        val newBase: DeltaBase? = null,
        /** この UUID は新しく振った `owner.bin` が書けず、使えない（規則 7b） */
        val unusable: Boolean = false,
    )

    /** [id] のディレクトリ */
    public fun directoryOf(id: UUID): Path = root.resolve(id.toString())

    /** [id] の世代 [generation] のファイル */
    public fun generationFile(id: UUID, generation: Long): Path = directoryOf(id).resolve("$generation$GENERATION_SUFFIX")

    // ---- 持ち主の確かめ ----

    /**
     * 読み込まれたブロックエンティティ（位置 [self]）の NBT の値で、持ち主を確かめる（#81 の 8.）。例外を投げない。
     *
     * 判定の順: 値の検査 → 索引の重複 → `owner.bin` がない（自分を持ち主に）→ 持ち主が自分 → 引き取り（NBT の epoch が記録以上）→ 古い写し。
     * 新しい UUID にしたときは `owner.bin`（epoch 0）を書いて索引に入れる。それも書けなければ [Claim.identity] は `null`。
     * 通った ID は索引に入れる（外すのは呼び出し側。アンロード・`setRemoved`）。
     *
     * その UUID の仕事が書き込みの列に残っていれば、覚えていない `owner.bin` をディスクから読む前と、`owner.bin` を書く前に、
     * それが終わるのを待つ（上限 [claimWaitMillis]。過ぎたら新しい UUID。#84 の規則 7a）
     */
    public fun claim(rawId: String?, rawGeneration: Long, rawEpoch: Long, self: MachineOwner): Claim {
        val id = CanonicalUuid.parse(rawId)
        if (id == null || !MachineIdentityLimits.isValidCounter(rawGeneration) || !MachineIdentityLimits.isValidCounter(rawEpoch)) {
            return fresh(self, ClaimResult.NEW_ID_INVALID, "invalid values (id=$rawId, generation=$rawGeneration, epoch=$rawEpoch)")
        }
        val indexed = index.ownerOf(id)
        if (indexed != null && indexed != self) return fresh(self, ClaimResult.NEW_ID_DUPLICATE_LOADED, "$id is already loaded at $indexed")
        val known = owners[id]
        if (known == null && !awaitIdle(id)) return fresh(self, ClaimResult.NEW_ID_UNREADABLE, busy(id))
        val record = try {
            known ?: OwnerRecord.read(directoryOf(id).resolve(OwnerRecord.FILE_NAME))
        } catch (e: IOException) {
            return fresh(self, ClaimResult.NEW_ID_UNREADABLE, "owner.bin of $id is unreadable: ${e.message}")
        } catch (e: RuntimeException) {
            return fresh(self, ClaimResult.NEW_ID_UNREADABLE, "owner.bin of $id is unreadable: $e")
        }
        val epoch: Long
        val result: ClaimResult
        when {
            record == null -> {
                if (!awaitIdle(id)) return fresh(self, ClaimResult.NEW_ID_UNREADABLE, busy(id))
                if (!writeOwner(id, OwnerRecord(rawEpoch, self))) {
                    return fresh(self, ClaimResult.NEW_ID_UNREADABLE, "owner.bin of $id cannot be written")
                }
                epoch = rawEpoch
                result = ClaimResult.KEPT
            }
            record.owner == self -> {
                // NBT の epoch が古いのは、引き取って owner.bin を書いた後、チャンクの保存の前に落ちたとき。記録の値に直す
                owners[id] = record
                epoch = record.epoch
                result = ClaimResult.KEPT
            }
            rawEpoch >= record.epoch -> {
                if (record.epoch >= MachineIdentityLimits.MAX_COUNTER) {
                    return fresh(self, ClaimResult.NEW_ID_INVALID, "epoch of $id cannot be advanced")
                }
                if (!awaitIdle(id)) return fresh(self, ClaimResult.NEW_ID_UNREADABLE, busy(id))
                val next = record.epoch + 1
                if (!writeOwner(id, OwnerRecord(next, self))) {
                    return fresh(self, ClaimResult.NEW_ID_UNREADABLE, "owner.bin of $id cannot be written")
                }
                epoch = next
                result = ClaimResult.TAKEN_OVER
            }
            else -> {
                owners[id] = record
                return fresh(self, ClaimResult.NEW_ID_STALE, "$id is a stale copy (epoch $rawEpoch < ${record.epoch}, owner ${record.owner})")
            }
        }
        index.put(id, self)
        registrations.getOrPut(id) { Registration() }
        // 印は、読み込んだ NBT（チャンクのファイルから来たもの）の世代から始める。前の印は空
        marks[id] = Marks(rawGeneration)
        return Claim(MachineIdentity(id, rawGeneration, epoch), result)
    }

    private fun busy(id: UUID) = "writes of $id did not finish within $claimWaitMillis ms"

    /** claim でその UUID の仕事を待った時間の合計（ナノ秒。数え口。#84） */
    public var claimWaitNanos: Long = 0
        private set

    /** [id] の仕事が列に残っていれば、終わるまで待つ（上限 [claimWaitMillis]）。待ちきれなければ `false`（規則 7a） */
    private fun awaitIdle(id: UUID): Boolean {
        if (!outstanding.containsKey(id)) return true
        val start = System.nanoTime()
        return try {
            awaitWrites(id, claimWaitMillis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } finally {
            claimWaitNanos += System.nanoTime() - start
        }
    }

    private fun fresh(self: MachineOwner, result: ClaimResult, reason: String): Claim {
        val identity = assignNewId(self)
        LOG.log(System.Logger.Level.WARNING, "Machine at $self gets a new id (${identity?.id ?: "none"}, $result): $reason")
        return Claim(identity, result)
    }

    /**
     * 新しい ID を振る（#81 の 7.。claim の振り直し）: 索引にもディレクトリにもない UUID を作り、`owner.bin`（epoch 0、持ち主は [self]）を
     * メインスレッドで同期に書いて索引に入れる。`owner.bin` が書けなければ `null`（ID を確定しない）。印なしから始める。
     * 保存の予約（[submit]）で振るときは、`owner.bin` を書き込みのスレッドで書く
     */
    public fun assignNewId(self: MachineOwner): MachineIdentity? {
        val id = newUuid()
        if (!writeOwner(id, OwnerRecord(0, self))) return null
        register(id, self)
        return MachineIdentity(id, 0, 0)
    }

    /** 索引・覚えている `owner.bin`・列のどれにも、ディスクにもない UUID */
    private fun newUuid(): UUID {
        var id: UUID
        do {
            id = UUID.randomUUID()
        } while (index.ownerOf(id) != null || owners.containsKey(id) || outstanding.containsKey(id) || Files.exists(directoryOf(id)))
        return id
    }

    /** 振った [id] を索引に入れ、新しい登録で始める */
    private fun register(id: UUID, self: MachineOwner) {
        index.put(id, self)
        registrations[id] = Registration()
        marks.remove(id)
        bases.remove(id)
        latest.remove(id)
        reserved.remove(id)
        inFlight.remove(id)
    }

    private fun writeOwner(id: UUID, record: OwnerRecord): Boolean = try {
        OwnerRecord.write(directoryOf(id).resolve(OwnerRecord.FILE_NAME), record)
        owners[id] = record
        // owner.bin が書けたので、使えない印が残っていても（削除の後に claim し直したときなど）外す
        forgetUnusable(id)
        true
    } catch (e: IOException) {
        LOG.log(System.Logger.Level.WARNING, "Failed to write owner.bin of $id", e)
        false
    }

    // ---- 書き込み ----

    /**
     * [current] の次の世代の書き込みを予約し、書き込みの列に入れる（メインスレッド。#84）。印は変えない。
     *
     * 予約の時点で、覚えている `owner.bin` の持ち主と epoch が [self]・[current] と合うかを確かめる。合わない（または覚えていない、
     * 次の世代が上限を超える）か、[current] が `null` なら、新しい UUID を振り（索引を付け替え、`owner.bin` は書き込みのスレッドで
     * 世代の前に書く）、そこに書く。次の世代は「[current] の世代 + 1」と「予約した最大の世代 + 1」の大きい方。
     *
     * 差分の写し（[MachineSnapshotSource.deltaBase] が `null` でない。#83）は、[deltaBaseFor] と同じ確かめで得る基準と一致するときだけ入れる
     * （振り直しは起きない）。合わなければ何も入れずに [DeltaBaseUnavailableException]。
     *
     * 書けたかどうかは、後で [pollCompleted] が返す（予約1つにつき完了が必ず1つ）。[tag] はその完了の [SaveCompletion.pending] に入る
     *
     * @throws DeltaBaseUnavailableException 差分の写しの基準が今の基準と合わない
     */
    @Throws(DeltaBaseUnavailableException::class)
    public fun submit(current: MachineIdentity?, self: MachineOwner, snapshot: MachineSnapshotSource, tag: Any?): PendingSave {
        val wantedBase = snapshot.deltaBase
        if (wantedBase != null) {
            val candidate = current?.let { deltaBaseCandidate(it, self) }
            if (candidate != wantedBase) {
                throw DeltaBaseUnavailableException("the delta base $wantedBase of ${current?.id} is not the current base $candidate")
            }
        }
        val identity: MachineIdentity
        var ownerRecord: OwnerRecord? = null
        var reassigned = false
        if (current != null && matchesOwner(current, self)) {
            identity = current
        } else {
            val id = newUuid()
            val record = OwnerRecord(0, self)
            if (current != null) {
                val old = owners[current.id]
                LOG.log(
                    System.Logger.Level.WARNING,
                    "Machine ${current.id} at $self does not match its owner.bin (${old?.owner}, epoch ${old?.epoch} vs ${current.epoch}) " +
                        "or ran out of generations; writing under a new id $id",
                )
                index.removeIfOwner(current.id, self)
                reassigned = true
            }
            owners[id] = record
            register(id, self)
            ownerRecord = record
            identity = MachineIdentity(id, 0, 0)
        }
        val id = identity.id
        val generation = nextGeneration(identity)
        reserved[id] = generation
        inFlight.getOrPut(id) { HashMap() }[generation] = wantedBase?.generation
        val estimated = snapshot.estimatedBytes.coerceAtLeast(0)
        val pending = PendingSave(
            identity.copy(generation = generation), reassigned, if (ownerRecord != null) 2 else 1, estimated, wantedBase, tag,
        )
        val registration = registrations.getOrPut(id) { Registration() }
        enqueue(
            id,
            estimated,
            { writeGeneration(pending, registration, self, snapshot, ownerRecord, if (reassigned) current?.id else null) },
            { Done(pending, registration, self, snapshot, null, IOException("the save writer is closed; generation $generation of $id is not written")) },
        )
        return pending
    }

    /**
     * [deltaBaseFor] → [capturer] で取り出し → [submit] をまとめて行う（#83・#84）。[submit] が [DeltaBaseUnavailableException] を投げたら
     * （何も入れていない）、その場で全体を取り直して（`capturer.capture(null, retry = true)`）入れる。取り出せなければ `null`
     */
    public fun captureAndSubmit(current: MachineIdentity?, self: MachineOwner, capturer: SnapshotCapturer, tag: Any?): CaptureSubmitResult? {
        val base = current?.let { deltaBaseFor(it, self) }
        var start = System.nanoTime()
        val source = capturer.capture(base, false)
        var captureNanos = System.nanoTime() - start
        if (source == null) return null
        return try {
            CaptureSubmitResult(submit(current, self, source, tag), captureNanos, false)
        } catch (e: DeltaBaseUnavailableException) {
            LOG.log(System.Logger.Level.WARNING, "Delta base of ${current?.id} is unavailable; capturing a full snapshot again: ${e.message}")
            start = System.nanoTime()
            val full = capturer.capture(null, true)
            captureNanos += System.nanoTime() - start
            if (full == null) return null
            CaptureSubmitResult(submit(current, self, full, tag), captureNanos, true)
        }
    }

    /**
     * 同期の書き込み（[submit] して、列を流しきるまで待ち、自分の完了だけを受け取って返す。ほかの完了は次の [pollCompleted] に残す）。
     * テストと、スレッドを使わない置き場用
     *
     * @throws IOException 書けなかった（振り直しの `owner.bin` が書けなかったときも）
     * @throws DeltaBaseUnavailableException 差分の写しの基準が今の基準と合わない
     */
    @Throws(IOException::class)
    public fun write(current: MachineIdentity, self: MachineOwner, snapshot: MachineSnapshotSource): WriteResult {
        val pending = submit(current, self, snapshot, null)
        val finished = try {
            executor.flush(WRITE_WAIT_MILLIS) && awaitWrites(pending.identity.id, WRITE_WAIT_MILLIS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("interrupted while writing generation ${pending.identity.generation} of ${pending.identity.id}", e)
        }
        if (!finished) throw IOException("generation ${pending.identity.generation} of ${pending.identity.id} was not written in time")
        drainCompleted()
        val mine = ready.firstOrNull { it.pending === pending } ?: throw IOException("the completion of ${pending.identity} was not returned")
        ready.remove(mine)
        return mine.result ?: throw mine.error!!
    }

    /** 書き込みのスレッドの仕事: 世代のファイル（新しく振った UUID なら先に `owner.bin`）を書く。例外は捕まえて失敗の完了にする */
    private fun writeGeneration(
        pending: PendingSave,
        registration: Registration,
        self: MachineOwner,
        snapshot: MachineSnapshotSource,
        ownerRecord: OwnerRecord?,
        forget: UUID?,
    ): Done {
        // 振り直した元の UUID の頭の写しは忘れる（ファイルは残るが、要れば読み直せる）
        if (forget != null) baseOf.remove(forget)
        val id = pending.identity.id
        val generation = pending.identity.generation
        fun failed(error: IOException) = Done(pending, registration, self, snapshot, null, error, unusable = id in unusable)
        try {
            if (id in unusable) return failed(IOException("owner.bin of the new id $id could not be written; generation $generation is not written"))
            prepareDirectory(id)
            var fileWriteNanos = 0L
            if (ownerRecord != null) {
                val start = System.nanoTime()
                try {
                    OwnerRecord.write(directoryOf(id).resolve(OwnerRecord.FILE_NAME), ownerRecord)
                } catch (e: IOException) {
                    unusable += id
                    return failed(IOException("owner.bin for the new id $id at $self cannot be written", e))
                }
                fileWriteNanos += System.nanoTime() - start
            }
            val encodeStart = System.nanoTime()
            val built = snapshot.build(id, generation, modVersion)
            check(built.deltaBase == pending.deltaBase) { "写しの基準と保存データの基準が違う" }
            val bytes = ByteArrayOutputStream(256)
            DataOutputStream(bytes).use { out -> built.writeTo(out) }
            val data = bytes.toByteArray()
            val encodeNanos = System.nanoTime() - encodeStart
            val tmpfsBytes = built.sections.sumOf { if (it.kind == SnapshotSection.TMPFS) it.size else 0 }
            val writeStart = System.nanoTime()
            writeAtomically(generationFile(id, generation), data)
            fileWriteNanos += System.nanoTime() - writeStart
            baseOf.getOrPut(id) { HashMap() }[generation] = built.deltaBase?.generation
            // 全体の世代（冷えた起動の写しでないもの）を、次の差分の基準にする（覚えるのは完了を受け取ったとき。規則 12）
            val newBase = if (built.deltaBase == null && !built.requiresColdBoot) {
                DeltaBase(id, generation, data.size.toLong(), crc32c(data))
            } else {
                null
            }
            val result = WriteResult(
                pending.identity, pending.reassigned, pending.writes, encodeNanos, fileWriteNanos, data.size, tmpfsBytes, built.deltaBase != null,
            )
            return Done(pending, registration, self, snapshot, result, null, newBase)
        } catch (e: IOException) {
            return failed(e)
        } catch (e: Throwable) {
            return failed(IOException("failed to write generation $generation of $id: $e", e))
        }
    }

    /** 書き込みのスレッドで、このサーバーで初めて書くディレクトリの残った `*.tmp` を片付ける（規則 8） */
    private fun prepareDirectory(id: UUID) {
        if (!initialized.add(id)) return
        try {
            deleteStaleTemps(directoryOf(id))
        } catch (e: IOException) {
            LOG.log(System.Logger.Level.WARNING, "Failed to delete leftover temporary files of $id", e)
        }
    }

    /** [task] を列に入れる。閉じていれば [rejected] の完了で返す。終わったら（どちらでも）見積もりと数を戻し、待ち手を起こす */
    private fun enqueue(id: UUID, bytes: Long, task: () -> Done?, rejected: () -> Done?) {
        outstanding.merge(id, 1, Int::plus)
        pendingTasks.incrementAndGet()
        pendingBytesCounter.addAndGet(bytes)
        val accepted = try {
            executor.execute(id) {
                var done: Done? = null
                try {
                    done = task()
                } finally {
                    finishTask(id, bytes, done)
                }
            }
        } catch (e: RuntimeException) {
            LOG.log(System.Logger.Level.WARNING, "Failed to queue a save task of $id", e)
            false
        }
        if (!accepted) finishTask(id, bytes, rejected())
    }

    private fun finishTask(id: UUID, bytes: Long, done: Done?) {
        // 完了を先に積む（その UUID を待っていた呼び出しが、起きたときに完了を受け取れるように）
        if (done != null) completed.add(done)
        pendingBytesCounter.addAndGet(-bytes)
        pendingTasks.decrementAndGet()
        val left = outstanding.compute(id) { _, n -> if (n == null || n <= 1) null else n - 1 }
        if (left == null && id in dropped) forgetUnusable(id)
        waitLock.withLock { progress.signalAll() }
    }

    /** [id] の「使えない」の印を外す（#566） */
    private fun forgetUnusable(id: UUID) {
        unusable.remove(id)
        dropped.remove(id)
    }

    /** [id] が「使えない」の印を持つか（テスト用） */
    internal fun isUnusable(id: UUID): Boolean = id in unusable

    private fun nextGeneration(identity: MachineIdentity): Long =
        maxOf(identity.generation, reserved[identity.id] ?: -1L, latest[identity.id] ?: -1L) + 1

    /** 覚えている `owner.bin` の持ち主・epoch が合い、次の世代が上限以下（[submit] が振り直さずに書ける） */
    private fun matchesOwner(current: MachineIdentity, self: MachineOwner): Boolean {
        val record = owners[current.id]
        return record != null && record.owner == self && record.epoch == current.epoch &&
            nextGeneration(current) <= MachineIdentityLimits.MAX_COUNTER
    }

    /** [submit] が差分の世代を書ける基準（#83）。振り直さずに書けて、今の基準を覚えているときだけ */
    private fun deltaBaseCandidate(current: MachineIdentity, self: MachineOwner): DeltaBase? {
        if (!matchesOwner(current, self)) return null
        return bases[current.id]
    }

    /**
     * 次の差分の世代が指せる基準（#83）。`null` なら全体を書く（置き場が基準を知らない（初めての保存・再起動の後・振り直しの後・
     * 全体の世代の書き込みの完了をまだ受け取っていない）、持ち主・epoch が合わない、世代の上限）。同じ呼び出しの中で、置き場を変えずに
     * [submit] まで進めれば、[submit] の確かめと一致する
     */
    public fun deltaBaseFor(current: MachineIdentity, self: MachineOwner): DeltaBase? = deltaBaseCandidate(current, self)

    // ---- 完了の受け取り ----

    /**
     * 終わった書き込みを受け取る（メインスレッドで、毎 tick。#84）。失敗したもの・走らせなかったものも入る。
     * その予約を作った登録が今も同じときだけ、書けた最新の世代・今の基準を進め、[MachineSnapshotSource.committed] を呼ぶ。
     * 新しく振った `owner.bin` が書けなかった UUID は、覚えていること・索引から外す（規則 7b）
     */
    public fun pollCompleted(): List<SaveCompletion> {
        drainCompleted()
        if (ready.isEmpty()) return emptyList()
        val out = ArrayList(ready)
        ready.clear()
        return out
    }

    private fun drainCompleted() {
        while (true) {
            val done = completed.poll() ?: return
            ready += accept(done)
        }
    }

    private fun accept(done: Done): SaveCompletion {
        val id = done.pending.identity.id
        val generation = done.pending.identity.generation
        inFlight[id]?.let {
            it.remove(generation)
            if (it.isEmpty()) inFlight.remove(id)
        }
        if (done.unusable) {
            // 印を外すのは、受け取った後に、その UUID の仕事が残っていないときだけ（受け取る前に入れた後続も書かずに失敗で返す。#566）
            dropped += id
            if (!outstanding.containsKey(id)) forgetUnusable(id)
        }
        if (done.registration != null && registrations[id] === done.registration) {
            val result = done.result
            if (done.unusable) {
                LOG.log(System.Logger.Level.WARNING, "Dropping the new id $id because its owner.bin could not be written")
                owners.remove(id)
                index.removeIfOwner(id, done.self)
                registrations.remove(id)
                reserved.remove(id)
                inFlight.remove(id)
                marks.remove(id)
                latest.remove(id)
                bases.remove(id)
            } else if (result != null) {
                latest[id] = maxOf(latest[id] ?: -1L, generation)
                done.newBase?.let { bases[id] = it }
                try {
                    done.snapshot.committed(id, generation)
                } catch (e: RuntimeException) {
                    LOG.log(System.Logger.Level.WARNING, "Failed to commit generation $generation of $id", e)
                }
            }
        }
        return SaveCompletion(done.pending, done.result, done.error)
    }

    /** 書き込みに回してよいか（書き込み待ちの見積もりの合計が上限未満。列が空なら必ず真。#84） */
    public val hasCapacity: Boolean get() = pendingBytesCounter.get() < maxPendingBytes

    /** 書き込み待ちの写しの見積もりの合計（バイト。書き込みのスレッドで、仕事が終わった時点で減る。数え口） */
    public val pendingBytes: Long get() = pendingBytesCounter.get()

    /** 列に入れて終わっていない仕事の数（数え口） */
    public val pendingCount: Int get() = pendingTasks.get()

    /** [hasCapacity] が真になるまで、最大 [timeoutMillis] ミリ秒待つ（アンロード・停止の取り出しの前。#84）。待ちきれなければ `false` */
    @Throws(InterruptedException::class)
    public fun awaitCapacity(timeoutMillis: Long): Boolean = awaitUntil(timeoutMillis) { hasCapacity }

    /** [id] の仕事がすべて終わるまで、最大 [timeoutMillis] ミリ秒待つ（claim の規則 7a と、#85 が世代のファイルを読む前）。終われば `true` */
    @Throws(InterruptedException::class)
    public fun awaitWrites(id: UUID, timeoutMillis: Long): Boolean = awaitUntil(timeoutMillis) { !outstanding.containsKey(id) }

    private inline fun awaitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        if (condition()) return true
        var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis.coerceAtLeast(0))
        waitLock.withLock {
            while (!condition()) {
                if (remaining <= 0) return false
                remaining = progress.awaitNanos(remaining)
            }
        }
        return true
    }

    /** 入れた仕事がすべて終わるまで待つ（停止の手順）。終われば `true`。完了は [pollCompleted] で受け取る */
    @Throws(InterruptedException::class)
    public fun flush(timeoutMillis: Long): Boolean = executor.flush(timeoutMillis)

    /** 書き込みの口を閉じる（入っている仕事は終える）。止まれば `true`。後から入れた予約は失敗の完了で返る */
    public fun close(timeoutMillis: Long): Boolean = executor.close(timeoutMillis)

    /** 予約した最大の世代（なければ空。#84） */
    public fun reservedGeneration(id: UUID): OptionalLong = reserved[id].toOptional()

    /**
     * 戻す世代（#84 の規則 9、回答 2）: [generation] のファイルがあればそれ。なければ、ディスクにある、それより小さい最大の世代。
     * それもなければ空（冷えた起動）。#85 が NBT の世代で呼ぶ（読む前に [awaitWrites] で待つ）
     */
    public fun restorableGeneration(id: UUID, generation: Long): OptionalLong {
        val onDisk = generationsOnDisk(id)
        if (generation in onDisk) return OptionalLong.of(generation)
        return onDisk.lastOrNull { it < generation }.toOptional()
    }

    /**
     * [id] の世代 [generation] のファイルを読む（#83。差分の世代を戻すときは、これで基準を読んで確かめる）。
     * ファイルの長さと CRC32C も返す
     *
     * @throws IOException 読めない・壊れている
     */
    @Throws(IOException::class)
    public fun readGeneration(id: UUID, generation: Long): StoredSnapshot {
        val data = Files.readAllBytes(generationFile(id, generation))
        val snapshot = DataInputStream(data.inputStream()).use { MachineSnapshot.readFrom(it) }
        if (snapshot.machineId != id || snapshot.generation != generation) {
            throw SnapshotFormatException("世代のファイル $id/$generation の外枠が ${snapshot.machineId}/${snapshot.generation}")
        }
        return StoredSnapshot(snapshot, data.size.toLong(), crc32c(data))
    }

    /**
     * [id] のディレクトリを消し、覚えていることも忘れる（プレイヤーの破壊・爆発のときだけ呼ぶ）。消すのは書き込みの列の仕事で、
     * それより前に入れた書き込みの後に走る（#84 の規則 7）。消せなければ warn
     */
    public fun delete(id: UUID) {
        owners.remove(id)
        marks.remove(id)
        latest.remove(id)
        reserved.remove(id)
        inFlight.remove(id)
        bases.remove(id)
        registrations.remove(id)
        enqueue(id, 0, {
            deleteDirectory(id)
            null
        }, {
            LOG.log(System.Logger.Level.WARNING, "The save writer is closed; the directory of $id is not deleted")
            null
        })
    }

    private fun deleteDirectory(id: UUID) {
        baseOf.remove(id)
        initialized.remove(id)
        val dir = directoryOf(id)
        try {
            if (!Files.exists(dir)) return
            Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        } catch (e: Exception) {
            LOG.log(System.Logger.Level.WARNING, "Failed to delete the directory of $id", e)
        }
    }

    // ---- 印と掃除 ----

    /**
     * チャンクの保存（`ChunkDataEvent.Save`）で、NBT に入った [id] の世代 [generation] を記録する（#81 の 9.）。
     *
     * [at]（タグの座標とディメンション）が覚えている `owner.bin` の持ち主と合うときだけ印を動かす。印が変わると、
     * 前の印 = 変わる前の印、今の印 = [generation] にする。印が進んだ（[ChunkMarkResult.ADVANCED]）ときだけ、
     * 残さない世代ファイルを消す仕事を書き込みの列に入れる（[pruneGenerations]）。戻す向き・同じ値では消さない
     */
    public fun markChunkGeneration(id: UUID, generation: Long, at: MachineOwner): ChunkMarkResult {
        val record = owners[id] ?: return ChunkMarkResult.UNKNOWN_ID
        if (record.owner != at) return ChunkMarkResult.NOT_OWNER
        val m = marks.getOrPut(id) { Marks() }
        val current = m.current
        return when {
            current == generation -> ChunkMarkResult.UNCHANGED
            current != null && generation < current -> {
                m.moveTo(generation)
                ChunkMarkResult.MOVED_BACK
            }
            else -> {
                m.moveTo(generation)
                schedulePrune(id, m)
                ChunkMarkResult.ADVANCED
            }
        }
    }

    /** keep の材料（印・完了した最新・書き込み待ちの世代と基準・今の基準）をメインスレッドで集め、掃除を列に入れる（#84） */
    private fun schedulePrune(id: UUID, m: Marks) {
        val current = m.current ?: return
        val k = setOfNotNull(current, m.previous, m.previous2, latest[id])
        val extra = HashSet<Long>()
        inFlight[id]?.forEach { (generation, base) ->
            extra += generation
            if (base != null) extra += base
        }
        bases[id]?.let { extra += it.generation }
        enqueue(id, 0, {
            pruneGenerations(id, current, k, extra)
            null
        }, { null })
    }

    /**
     * 残さない世代を消す（書き込みのスレッド。ADR 0081。#83 で差分の基準を、#84 で直下の世代と書き込み待ちを残すように広げた。ADR 0083・0084）:
     *
     * 1. 候補: K（今の印・前の印2つ・覚えている最新）に入る世代と、今の印より大きい世代。K の世代がディスクにないときは、
     *    それより小さいうちディスクにある最大の世代も候補にする（規則 10。読み込みで戻す世代）
     * 2. 候補の基準: [baseOf] に覚えていればそれ、なければファイルの頭（[deltaBaseReader]）から読んで覚える
     * 3. keep = 候補 ∪ 候補の差分が指す基準 ∪ [extra]（書き込み待ちの世代とその基準・今の基準。規則 11）
     * 4. keep に入らず、今の印より小さい世代を消す
     *
     * 2. で頭を読めなければ（[IOException]）、その回はこの UUID の世代を1つも消さない（基準を失わない側に倒す）。
     * 読めない世代は印が進めば K から外れ（最大 3 回）、候補でなくなるので掃除が再開する
     */
    private fun pruneGenerations(id: UUID, current: Long, k: Set<Long>, extra: Set<Long>) {
        val onDisk = generationsOnDisk(id)
        val present = onDisk.toHashSet()
        val candidates = LinkedHashSet<Long>()
        for (generation in onDisk) if (generation in k || generation > current) candidates += generation
        for (generation in k) {
            if (generation in present) continue
            onDisk.lastOrNull { it < generation }?.let { candidates += it }
        }
        val known = baseOf.getOrPut(id) { HashMap() }
        val keep = HashSet<Long>(extra)
        for (generation in candidates) {
            keep += generation
            val base = if (known.containsKey(generation)) {
                known[generation]
            } else {
                val read = try {
                    deltaBaseReader(generationFile(id, generation))
                } catch (e: IOException) {
                    LOG.log(
                        System.Logger.Level.WARNING,
                        "Cannot read the delta base of generation $generation of $id; keeping all generations this time: $e",
                    )
                    return
                }
                known[generation] = read?.generation
                read?.generation
            }
            if (base != null) keep += base
        }
        val gone = HashSet<Long>()
        for (generation in onDisk) {
            if (generation >= current || generation in keep) continue
            try {
                Files.deleteIfExists(generationFile(id, generation))
                gone += generation
            } catch (e: IOException) {
                LOG.log(System.Logger.Level.WARNING, "Failed to delete generation $generation of $id", e)
            }
        }
        // 消せた世代と、もうディスクにない世代の頭の写しを忘れる
        known.keys.removeAll { it in gone || it !in present }
    }

    /** [id] の世代の頭の写しを忘れる（テストが「覚えていない世代」を作る用。#83） */
    internal fun forgetBaseCache(id: UUID) {
        baseOf.remove(id)
    }

    /** 覚えている、[id] の世代の頭の写し（[baseOf] のキー。検査用。#83） */
    internal fun rememberedBaseGenerations(id: UUID): Set<Long> = baseOf[id]?.keys?.toSet().orEmpty()

    /** 今の基準（次の差分が指す全体の世代。なければ `null`。#83） */
    public fun currentBase(id: UUID): DeltaBase? = bases[id]

    /** ディスクにある [id] の世代ファイルの世代（小さい順。tmp は含めない） */
    public fun generationsOnDisk(id: UUID): List<Long> {
        val dir = directoryOf(id)
        if (!Files.isDirectory(dir)) return emptyList()
        return try {
            Files.list(dir).use { paths ->
                val result = ArrayList<Long>()
                paths.forEach { path -> parseGeneration(path.fileName.toString())?.let(result::add) }
                result.sorted()
            }
        } catch (e: IOException) {
            LOG.log(System.Logger.Level.WARNING, "Failed to list generations of $id", e)
            emptyList()
        }
    }

    /** 記録した今の印（なければ空） */
    public fun chunkGeneration(id: UUID): OptionalLong = marks[id]?.current.toOptional()

    /** 前の印（印が最後に変わる直前の値）。`claim`・引き取り・振り直しの直後は空 */
    public fun previousChunkGeneration(id: UUID): OptionalLong = marks[id]?.previous.toOptional()

    /** 残す前の印（[previousChunkGeneration] と、その1つ前。新しい順。なければ空の並び） */
    public fun previousChunkGenerations(id: UUID): List<Long> = marks[id]?.let { listOfNotNull(it.previous, it.previous2) }.orEmpty()

    /** その UUID で書けた（完了を受け取った）最新の世代（なければ空） */
    public fun latestGeneration(id: UUID): OptionalLong = latest[id].toOptional()

    /** 覚えている `owner.bin` の持ち主（ディスクは読まない） */
    public fun ownerOf(id: UUID): MachineOwner? = owners[id]?.owner

    /** 覚えている `owner.bin` の epoch（ディスクは読まない） */
    public fun epochOf(id: UUID): OptionalLong = owners[id]?.epoch.toOptional()

    override fun toString(): String = "MachineSaveStore($root)"

    private companion object {
        val LOG: System.Logger = System.getLogger("kibicomputers.persist")

        const val GENERATION_SUFFIX = ".bin"

        /** [write] が書き込みを待つ上限（実際には待ちきる） */
        const val WRITE_WAIT_MILLIS = 24L * 60 * 60 * 1000

        val GENERATION_NAME = Regex("(0|[1-9][0-9]{0,18})\\.bin")

        fun parseGeneration(name: String): Long? {
            if (!GENERATION_NAME.matches(name)) return null
            return name.removeSuffix(GENERATION_SUFFIX).toLongOrNull()?.takeIf { MachineIdentityLimits.isValidCounter(it) }
        }

        fun Long?.toOptional(): OptionalLong = if (this == null) OptionalLong.empty() else OptionalLong.of(this)

        fun crc32c(data: ByteArray): Int = CRC32C().also { it.update(data, 0, data.size) }.value.toInt()
    }
}
