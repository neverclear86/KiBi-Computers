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
import java.util.zip.CRC32C

/**
 * マシンの保存データの置き場（#81、ADR 0081）。根は `<world>/kibicomputers/machines`。
 *
 * - `<root>/<uuid>/<世代>.bin`（世代は10進）が保存データ（[MachineSnapshot]）、`<root>/<uuid>/owner.bin` が持ち主（[OwnerRecord]）。
 *   どちらも tmp に書いて fsync し、`ATOMIC_MOVE` で置き換える
 * - 持ち主の規則（回答 A。epoch 方式）は [claim]。書く直前にも、覚えている `owner.bin` と照らす（[write]）
 * - NBT が指す世代（チャンクの印）は、チャンクの保存（`ChunkDataEvent.Save`）から [markChunkGeneration] で受け取る。
 *   残すのは「今の印」「前の印（2つ）」「覚えている最新の世代」と、印より大きい世代。それ以外は印が進んだときに消す
 *
 * `owner.bin` の中身・印・書いた最新の世代はメモリに覚え（このサーバーが書いた・読んだもの）、毎回ディスクは読まない。
 * 印はメモリの上だけに持ち、保存しない（再起動の後は [claim] で読んだ世代から始まる）。
 *
 * 差分の世代（#83、ADR 0083）: 全体の世代を書けたら、それを今の基準として覚える（メモリだけ。再起動の後の最初の保存は全体）。
 * 差分の世代は今の基準を指す（[deltaBaseFor] → 取り出し → [write]。まとめたものが [captureAndWrite]）。
 * 残す世代は、残す世代が指す基準も残す（[markChunkGeneration]）。
 *
 * スレッド: メインスレッドだけで使う（#84 で書き込みを別のスレッドに移す）。
 *
 * @property root 置き場の根
 * @property index サーバーの ID の索引
 * @param modVersion 保存データに書く MOD のバージョン
 */
@ApiStatus.Internal
public class MachineSaveStore @JvmOverloads constructor(
    public val root: Path,
    public val index: MachineIdIndex,
    private val modVersion: String = "unknown",
) {
    /** 覚えている `owner.bin`（このサーバーが書いた・読んだもの） */
    private val owners = HashMap<UUID, OwnerRecord>()

    /** チャンクの印（今の印と、前の印2つ） */
    private val marks = HashMap<UUID, Marks>()

    /** その UUID で書いた最新の世代 */
    private val latest = HashMap<UUID, Long>()

    /** その UUID の今の基準（次の差分が指す全体の世代。#83）。状態なので、`delete` と振り直しでだけ変わる */
    private val bases = HashMap<UUID, DeltaBase>()

    /**
     * UUID → 世代 → その世代が指す基準の世代（全体の世代なら `null`。#83）。ファイルの頭の写し（キャッシュ）で、
     * 忘れても次に要るときに [deltaBaseReader] で読み直すだけ。消せた世代・`delete`・振り直しで忘れる
     */
    private val baseOf = HashMap<UUID, HashMap<Long, Long?>>()

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

    /** [id] のディレクトリ */
    public fun directoryOf(id: UUID): Path = root.resolve(id.toString())

    /** [id] の世代 [generation] のファイル */
    public fun generationFile(id: UUID, generation: Long): Path = directoryOf(id).resolve("$generation$GENERATION_SUFFIX")

    /**
     * 読み込まれたブロックエンティティ（位置 [self]）の NBT の値で、持ち主を確かめる（#81 の 8.）。例外を投げない。
     *
     * 判定の順: 値の検査 → 索引の重複 → `owner.bin` がない（自分を持ち主に）→ 持ち主が自分 → 引き取り（NBT の epoch が記録以上）→ 古い写し。
     * 新しい UUID にしたときは `owner.bin`（epoch 0）を書いて索引に入れる。それも書けなければ [Claim.identity] は `null`。
     * 通った ID は索引に入れる（外すのは呼び出し側。アンロード・`setRemoved`）
     */
    public fun claim(rawId: String?, rawGeneration: Long, rawEpoch: Long, self: MachineOwner): Claim {
        val id = CanonicalUuid.parse(rawId)
        if (id == null || !MachineIdentityLimits.isValidCounter(rawGeneration) || !MachineIdentityLimits.isValidCounter(rawEpoch)) {
            return fresh(self, ClaimResult.NEW_ID_INVALID, "invalid values (id=$rawId, generation=$rawGeneration, epoch=$rawEpoch)")
        }
        val indexed = index.ownerOf(id)
        if (indexed != null && indexed != self) return fresh(self, ClaimResult.NEW_ID_DUPLICATE_LOADED, "$id is already loaded at $indexed")
        val record = try {
            owners[id] ?: OwnerRecord.read(directoryOf(id).resolve(OwnerRecord.FILE_NAME))
        } catch (e: IOException) {
            return fresh(self, ClaimResult.NEW_ID_UNREADABLE, "owner.bin of $id is unreadable: ${e.message}")
        } catch (e: RuntimeException) {
            return fresh(self, ClaimResult.NEW_ID_UNREADABLE, "owner.bin of $id is unreadable: $e")
        }
        val epoch: Long
        val result: ClaimResult
        when {
            record == null -> {
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
        // 印は、読み込んだ NBT（チャンクのファイルから来たもの）の世代から始める。前の印は空
        marks[id] = Marks(rawGeneration)
        return Claim(MachineIdentity(id, rawGeneration, epoch), result)
    }

    private fun fresh(self: MachineOwner, result: ClaimResult, reason: String): Claim {
        val identity = assignNewId(self)
        LOG.log(System.Logger.Level.WARNING, "Machine at $self gets a new id (${identity?.id ?: "none"}, $result): $reason")
        return Claim(identity, result)
    }

    /**
     * 新しい ID を振る（#81 の 7.）: 索引にもディレクトリにもない UUID を作り、`owner.bin`（epoch 0、持ち主は [self]）を書いて索引に入れる。
     * `owner.bin` が書けなければ `null`（ID を確定しない）。印なしから始める
     */
    public fun assignNewId(self: MachineOwner): MachineIdentity? {
        var id: UUID
        do {
            id = UUID.randomUUID()
        } while (index.ownerOf(id) != null || owners.containsKey(id) || Files.exists(directoryOf(id)))
        if (!writeOwner(id, OwnerRecord(0, self))) return null
        index.put(id, self)
        marks.remove(id)
        baseOf.remove(id)
        bases.remove(id)
        return MachineIdentity(id, 0, 0)
    }

    private fun writeOwner(id: UUID, record: OwnerRecord): Boolean = try {
        OwnerRecord.write(directoryOf(id).resolve(OwnerRecord.FILE_NAME), record)
        owners[id] = record
        true
    } catch (e: IOException) {
        LOG.log(System.Logger.Level.WARNING, "Failed to write owner.bin of $id", e)
        false
    }

    /**
     * [current] の次の世代を書く（tmp → fsync → rename）。印は変えず、古い世代もここでは消さない。
     *
     * 書く直前に、覚えている `owner.bin` の持ち主と epoch が [self]・[current] と合うかを確かめる。合わない（または覚えていない、
     * 次の世代が上限を超える）なら、そのディレクトリには書かず、新しい UUID に振り直して（`owner.bin` を書き、索引を付け替えて）そこに書く。
     * 次の世代は「[current] の世代 + 1」と「覚えている、その UUID で書いた最新の世代 + 1」の大きい方。
     *
     * 差分の写し（[MachineSnapshotSource.deltaBase] が `null` でない。#83）は、[deltaBaseFor] と同じ確かめで得る基準と一致するときだけ書く
     * （振り直しは起きない）。合わなければ何も書かずに [DeltaBaseUnavailableException]。
     * 全体の世代（冷えた起動の写しでないもの）を書けたら、それを今の基準として覚える。書けたら [MachineSnapshotSource.committed] を呼ぶ。
     *
     * @throws IOException 書けなかった（振り直しの `owner.bin` が書けなかったときも）
     * @throws DeltaBaseUnavailableException 差分の写しの基準が今の基準と合わない
     */
    @Throws(IOException::class)
    public fun write(current: MachineIdentity, self: MachineOwner, snapshot: MachineSnapshotSource): WriteResult {
        var identity = current
        var writes = 0
        var reassigned = false
        var fileWriteNanos = 0L
        val wantedBase = snapshot.deltaBase
        if (wantedBase != null) {
            val candidate = deltaBaseCandidate(current, self)
            if (candidate != wantedBase) {
                throw DeltaBaseUnavailableException("the delta base $wantedBase of ${current.id} is not the current base $candidate")
            }
        }
        val record = owners[current.id]
        if (!matchesOwner(current, self)) {
            val ownerStart = System.nanoTime()
            val fresh = assignNewId(self) ?: throw IOException("owner.bin for a new id at $self cannot be written")
            fileWriteNanos += System.nanoTime() - ownerStart
            LOG.log(
                System.Logger.Level.WARNING,
                "Machine ${current.id} at $self does not match its owner.bin (${record?.owner}, epoch ${record?.epoch} vs ${current.epoch}) " +
                    "or ran out of generations; writing under a new id ${fresh.id}",
            )
            index.removeIfOwner(current.id, self)
            // 元の UUID の頭の写しは忘れる（ファイルは残るが、要れば読み直せる）
            baseOf.remove(current.id)
            identity = fresh
            writes++
            reassigned = true
        }
        val generation = nextGeneration(identity)
        val encodeStart = System.nanoTime()
        val built = snapshot.build(identity.id, generation, modVersion)
        check(built.deltaBase == wantedBase) { "写しの基準と保存データの基準が違う" }
        val bytes = ByteArrayOutputStream(256)
        DataOutputStream(bytes).use { out -> built.writeTo(out) }
        val data = bytes.toByteArray()
        val encodeNanos = System.nanoTime() - encodeStart
        val tmpfsBytes = built.sections.sumOf { if (it.kind == SnapshotSection.TMPFS) it.size else 0 }
        val writeStart = System.nanoTime()
        writeAtomically(generationFile(identity.id, generation), data)
        fileWriteNanos += System.nanoTime() - writeStart
        latest[identity.id] = generation
        baseOf.getOrPut(identity.id) { HashMap() }[generation] = built.deltaBase?.generation
        // 全体の世代（冷えた起動の写しでないもの）を、次の差分の基準として覚える
        if (built.deltaBase == null && !built.requiresColdBoot) {
            bases[identity.id] = DeltaBase(identity.id, generation, data.size.toLong(), crc32c(data))
        }
        writes++
        snapshot.committed(identity.id, generation)
        return WriteResult(
            identity.copy(generation = generation), reassigned, writes, encodeNanos, fileWriteNanos, data.size, tmpfsBytes,
            built.deltaBase != null,
        )
    }

    private fun nextGeneration(identity: MachineIdentity): Long = maxOf(identity.generation, latest[identity.id] ?: -1L) + 1

    /** 覚えている `owner.bin` の持ち主・epoch が合い、次の世代が上限以下（[write] が振り直さずに書ける） */
    private fun matchesOwner(current: MachineIdentity, self: MachineOwner): Boolean {
        val record = owners[current.id]
        return record != null && record.owner == self && record.epoch == current.epoch &&
            nextGeneration(current) <= MachineIdentityLimits.MAX_COUNTER
    }

    /** [write] が差分の世代を書ける基準（#83）。振り直さずに書けて、今の基準を覚えているときだけ */
    private fun deltaBaseCandidate(current: MachineIdentity, self: MachineOwner): DeltaBase? {
        if (!matchesOwner(current, self)) return null
        return bases[current.id]
    }

    /**
     * 次の差分の世代が指せる基準（#83）。`null` なら全体を書く（置き場が基準を知らない（初めての保存・再起動の後・振り直しの後）、
     * 持ち主・epoch が合わない、世代の上限）。同じ呼び出しの中で、置き場を変えずに [write] まで進めれば、[write] の確かめと一致する
     */
    public fun deltaBaseFor(current: MachineIdentity, self: MachineOwner): DeltaBase? = deltaBaseCandidate(current, self)

    /**
     * [deltaBaseFor] → [capturer] で取り出し → [write] をまとめて行う（#83）。[write] が [DeltaBaseUnavailableException] を投げたら
     * （何も書いていない）、その場で全体を取り直して（`capturer.capture(null, retry = true)`）書く。取り出せなければ `null`
     *
     * @throws IOException 書けなかった
     */
    @Throws(IOException::class)
    public fun captureAndWrite(current: MachineIdentity, self: MachineOwner, capturer: SnapshotCapturer): CaptureWriteResult? {
        val base = deltaBaseFor(current, self)
        var start = System.nanoTime()
        val source = capturer.capture(base, false)
        var captureNanos = System.nanoTime() - start
        if (source == null) return null
        return try {
            val result = write(current, self, source)
            CaptureWriteResult(result, captureNanos, false, result.delta)
        } catch (e: DeltaBaseUnavailableException) {
            LOG.log(System.Logger.Level.WARNING, "Delta base of ${current.id} is unavailable; capturing a full snapshot again: ${e.message}")
            start = System.nanoTime()
            val full = capturer.capture(null, true)
            captureNanos += System.nanoTime() - start
            if (full == null) return null
            val result = write(current, self, full)
            CaptureWriteResult(result, captureNanos, true, result.delta)
        }
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

    /** [id] のディレクトリを消し、覚えていることも忘れる（プレイヤーの破壊・爆発のときだけ呼ぶ） */
    @Throws(IOException::class)
    public fun delete(id: UUID) {
        owners.remove(id)
        marks.remove(id)
        latest.remove(id)
        bases.remove(id)
        baseOf.remove(id)
        val dir = directoryOf(id)
        if (!Files.exists(dir)) return
        Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    /**
     * チャンクの保存（`ChunkDataEvent.Save`）で、NBT に入った [id] の世代 [generation] を記録する（#81 の 9.）。
     *
     * [at]（タグの座標とディメンション）が覚えている `owner.bin` の持ち主と合うときだけ印を動かす。印が変わると、
     * 前の印 = 変わる前の印、今の印 = [generation] にする。印が進んだ（[ChunkMarkResult.ADVANCED]）ときだけ、
     * 「今の印・前の印2つ・覚えている最新・印より大きいもの」以外の世代ファイルを消す。戻す向き・同じ値では消さない
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
                pruneGenerations(id, m)
                ChunkMarkResult.ADVANCED
            }
        }
    }

    /**
     * 残さない世代を消す（ADR 0081。#83 で差分の基準を残すように広げた。ADR 0083）:
     *
     * 1. 候補: K（今の印・前の印2つ・覚えている最新）に入る世代と、今の印より大きい世代
     * 2. 候補の基準: [baseOf] に覚えていればそれ、なければファイルの頭（[deltaBaseReader]）から読んで覚える
     * 3. keep = 候補 ∪ 候補の差分が指す基準 ∪ 今の基準
     * 4. keep に入らず、今の印より小さい世代を消す
     *
     * 2. で頭を読めなければ（[IOException]）、その回はこの UUID の世代を1つも消さない（基準を失わない側に倒す）。
     * 読めない世代は印が進めば K から外れ（最大 3 回）、候補でなくなるので掃除が再開する
     */
    private fun pruneGenerations(id: UUID, m: Marks) {
        val current = m.current ?: return
        val onDisk = generationsOnDisk(id)
        val k = setOfNotNull(current, m.previous, m.previous2, latest[id])
        val known = baseOf.getOrPut(id) { HashMap() }
        val keep = HashSet<Long>()
        for (generation in onDisk) {
            if (generation !in k && generation <= current) continue
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
        bases[id]?.let { keep += it.generation }
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
        known.keys.removeAll { it in gone || it !in onDisk }
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

    /** その UUID で書いた最新の世代（なければ空） */
    public fun latestGeneration(id: UUID): OptionalLong = latest[id].toOptional()

    /** 覚えている `owner.bin` の持ち主（ディスクは読まない） */
    public fun ownerOf(id: UUID): MachineOwner? = owners[id]?.owner

    /** 覚えている `owner.bin` の epoch（ディスクは読まない） */
    public fun epochOf(id: UUID): OptionalLong = owners[id]?.epoch.toOptional()

    override fun toString(): String = "MachineSaveStore($root)"

    private companion object {
        val LOG: System.Logger = System.getLogger("kibicomputers.persist")

        const val GENERATION_SUFFIX = ".bin"

        val GENERATION_NAME = Regex("(0|[1-9][0-9]{0,18})\\.bin")

        fun parseGeneration(name: String): Long? {
            if (!GENERATION_NAME.matches(name)) return null
            return name.removeSuffix(GENERATION_SUFFIX).toLongOrNull()?.takeIf { MachineIdentityLimits.isValidCounter(it) }
        }

        fun Long?.toOptional(): OptionalLong = if (this == null) OptionalLong.empty() else OptionalLong.of(this)

        fun crc32c(data: ByteArray): Int = CRC32C().also { it.update(data, 0, data.size) }.value.toInt()
    }
}
