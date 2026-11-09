package pro.ikili.kibicomputers.core.persist

import org.jetbrains.annotations.ApiStatus
import pro.ikili.kibicomputers.core.util.CanonicalUuid
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.OptionalLong
import java.util.UUID

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
     * @throws IOException 書けなかった（振り直しの `owner.bin` が書けなかったときも）
     */
    @Throws(IOException::class)
    public fun write(current: MachineIdentity, self: MachineOwner, snapshot: MachineSnapshotSource): WriteResult {
        var identity = current
        var writes = 0
        var reassigned = false
        var fileWriteNanos = 0L
        val record = owners[current.id]
        val mismatch = record == null || record.owner != self || record.epoch != current.epoch
        if (mismatch || nextGeneration(current) > MachineIdentityLimits.MAX_COUNTER) {
            val ownerStart = System.nanoTime()
            val fresh = assignNewId(self) ?: throw IOException("owner.bin for a new id at $self cannot be written")
            fileWriteNanos += System.nanoTime() - ownerStart
            LOG.log(
                System.Logger.Level.WARNING,
                "Machine ${current.id} at $self does not match its owner.bin (${record?.owner}, epoch ${record?.epoch} vs ${current.epoch}) " +
                    "or ran out of generations; writing under a new id ${fresh.id}",
            )
            index.removeIfOwner(current.id, self)
            identity = fresh
            writes++
            reassigned = true
        }
        val generation = nextGeneration(identity)
        val encodeStart = System.nanoTime()
        val built = snapshot.build(identity.id, generation, modVersion)
        val bytes = ByteArrayOutputStream(256)
        DataOutputStream(bytes).use { out -> built.writeTo(out) }
        val data = bytes.toByteArray()
        val encodeNanos = System.nanoTime() - encodeStart
        val tmpfsBytes = built.sections.sumOf { if (it.kind == SnapshotSection.TMPFS) it.size else 0 }
        val writeStart = System.nanoTime()
        writeAtomically(generationFile(identity.id, generation), data)
        fileWriteNanos += System.nanoTime() - writeStart
        latest[identity.id] = generation
        writes++
        return WriteResult(identity.copy(generation = generation), reassigned, writes, encodeNanos, fileWriteNanos, data.size, tmpfsBytes)
    }

    private fun nextGeneration(identity: MachineIdentity): Long = maxOf(identity.generation, latest[identity.id] ?: -1L) + 1

    /** [id] のディレクトリを消し、覚えていることも忘れる（プレイヤーの破壊・爆発のときだけ呼ぶ） */
    @Throws(IOException::class)
    public fun delete(id: UUID) {
        owners.remove(id)
        marks.remove(id)
        latest.remove(id)
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

    private fun pruneGenerations(id: UUID, m: Marks) {
        val current = m.current ?: return
        val keep = setOfNotNull(current, m.previous, m.previous2, latest[id])
        for (generation in generationsOnDisk(id)) {
            if (generation >= current || generation in keep) continue
            try {
                Files.deleteIfExists(generationFile(id, generation))
            } catch (e: IOException) {
                LOG.log(System.Logger.Level.WARNING, "Failed to delete generation $generation of $id", e)
            }
        }
    }

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
    }
}
