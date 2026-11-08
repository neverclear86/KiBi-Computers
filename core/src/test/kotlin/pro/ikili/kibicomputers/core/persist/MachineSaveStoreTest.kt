package pro.ikili.kibicomputers.core.persist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.OptionalLong
import java.util.UUID

/** マシンの保存データの置き場（`MachineSaveStore`）のテスト（#81、ADR 0081） */
class MachineSaveStoreTest {
    @TempDir
    lateinit var dir: Path

    private val p = MachineOwner("minecraft:overworld", 0, 64, 0)
    private val q = MachineOwner("minecraft:overworld", 16, 64, 0)
    private val pNether = MachineOwner("minecraft:the_nether", 0, 64, 0)

    private val index = MachineIdIndex()
    private val store by lazy { MachineSaveStore(dir, index, "test") }

    private val source = MachineSnapshotSource { id, generation, version -> MachineSnapshot(id, generation, version, killedPids = listOf(2)) }

    /** [identity] から [count] 回書いて、最後の ID を返す */
    private fun writeTimes(identity: MachineIdentity, count: Int, self: MachineOwner = p): MachineIdentity {
        var current = identity
        repeat(count) { current = store.write(current, self, source).identity }
        return current
    }

    private fun claimFresh(generation: Long, self: MachineOwner = p): MachineIdentity {
        val claim = store.claim(UUID.randomUUID().toString(), generation, 0, self)
        assertEquals(ClaimResult.KEPT, claim.result)
        return claim.identity!!
    }

    @Test
    fun `IDを振るとowner_binが書かれ、索引に入り、世代のファイルを書ける`() {
        val identity = store.assignNewId(p)!!
        assertEquals(0, identity.generation)
        assertEquals(0, identity.epoch)
        assertEquals(p, index.ownerOf(identity.id))
        val record = OwnerRecord.read(store.directoryOf(identity.id).resolve(OwnerRecord.FILE_NAME))!!
        assertEquals(OwnerRecord(0, p), record)
        assertEquals(OptionalLong.empty(), store.chunkGeneration(identity.id), "ID を振った台は印なしから始める")

        val result = store.write(identity, p, source)
        assertFalse(result.reassigned)
        assertEquals(1, result.writes)
        assertEquals(1, result.identity.generation)
        val file = store.generationFile(identity.id, 1)
        val snapshot = DataInputStream(Files.newInputStream(file)).use { MachineSnapshot.readFrom(it) }
        assertEquals(identity.id, snapshot.machineId)
        assertEquals(1, snapshot.generation)
        assertEquals("test", snapshot.modVersion)
        assertEquals(listOf(2), snapshot.killedPids)
    }

    @Test
    fun `tmpが残っても世代として読まない`() {
        val identity = writeTimes(store.assignNewId(p)!!, 1)
        Files.write(store.directoryOf(identity.id).resolve("5.bin.tmp"), byteArrayOf(1))
        Files.write(store.directoryOf(identity.id).resolve("007.bin"), byteArrayOf(1))
        assertEquals(listOf(1L), store.generationsOnDisk(identity.id))
    }

    @Test
    fun `owner_binを往復する`() {
        val record = OwnerRecord(12, MachineOwner("kibi:test", -5, 300, Int.MIN_VALUE))
        val file = dir.resolve("x").resolve(OwnerRecord.FILE_NAME)
        OwnerRecord.write(file, record)
        assertEquals(record, OwnerRecord.read(file))
        assertFalse(Files.exists(file.resolveSibling("owner.bin.tmp")))
    }

    @Test
    fun `壊れたUUIDと範囲外の世代・epochはIDなしとして新しいUUIDにする`() {
        for (raw in listOf("../x", "0123ABCD-4567-89ef-0123-456789abcdef", "", "not-a-uuid")) {
            val claim = store.claim(raw, 1, 0, p)
            assertEquals(ClaimResult.NEW_ID_INVALID, claim.result, raw)
            assertNotNull(claim.identity)
        }
        val ok = UUID.randomUUID().toString()
        for ((generation, epoch) in listOf(-1L to 0L, 0L to -1L, MachineIdentityLimits.MAX_COUNTER + 1 to 0L, 0L to Long.MAX_VALUE)) {
            val claim = store.claim(ok, generation, epoch, p)
            assertEquals(ClaimResult.NEW_ID_INVALID, claim.result, "$generation/$epoch")
            assertNotEquals(ok, claim.identity!!.id.toString())
        }
        assertFalse(Files.exists(dir.resolve(ok)), "検査に外れた ID のディレクトリは作らない")
    }

    @Test
    fun `書く前の確かめ - owner_binの持ち主・epochが違えば元のディレクトリに書かず振り直す`() {
        val identity = store.assignNewId(p)!!
        val fromQ = store.write(identity, q, source)
        assertTrue(fromQ.reassigned)
        assertEquals(2, fromQ.writes, "owner.bin と世代ファイル")
        assertNotEquals(identity.id, fromQ.identity.id)
        assertEquals(emptyList<Long>(), store.generationsOnDisk(identity.id))
        assertEquals(listOf(1L), store.generationsOnDisk(fromQ.identity.id))
        assertEquals(q, index.ownerOf(fromQ.identity.id))
        assertEquals(p, index.ownerOf(identity.id), "持ち主の違う索引は外さない")

        val wrongEpoch = store.write(identity.copy(epoch = 3), p, source)
        assertTrue(wrongEpoch.reassigned)
        assertEquals(emptyList<Long>(), store.generationsOnDisk(identity.id))
    }

    @Test
    fun `次の世代が上限を超えるなら振り直す`() {
        val identity = claimFresh(MachineIdentityLimits.MAX_COUNTER)
        val result = store.write(identity, p, source)
        assertTrue(result.reassigned)
        assertEquals(1, result.identity.generation)
    }

    @Test
    fun `印のまま書いても消えず、印が進むと今の印・前の印・最新だけが残る`() {
        val first = writeTimes(store.assignNewId(p)!!, 1)
        val g = first.generation
        assertEquals(ChunkMarkResult.ADVANCED, store.markChunkGeneration(first.id, g, p))
        val last = writeTimes(first, 3)
        assertEquals(listOf(g, g + 1, g + 2, g + 3), store.generationsOnDisk(first.id), "印 = g のまま進めても g は消えない")
        assertEquals(OptionalLong.of(g), store.chunkGeneration(first.id), "write だけでは印は進まない")
        assertEquals(ChunkMarkResult.ADVANCED, store.markChunkGeneration(first.id, last.generation, p))
        assertEquals(listOf(g, g + 3), store.generationsOnDisk(first.id))
        assertEquals(OptionalLong.of(g), store.previousChunkGeneration(first.id))
        assertEquals(ChunkMarkResult.UNCHANGED, store.markChunkGeneration(first.id, last.generation, p))
    }

    @Test
    fun `印g7でg8・g9を書いて印がg9に進むと、g7が残りg8が消える`() {
        val claimed = claimFresh(6)
        val g7 = writeTimes(claimed, 1)
        assertEquals(7, g7.generation)
        store.markChunkGeneration(g7.id, 7, p)
        val g9 = writeTimes(g7, 2)
        assertEquals(ChunkMarkResult.ADVANCED, store.markChunkGeneration(g9.id, 9, p))
        assertEquals(listOf(7L, 9L), store.generationsOnDisk(g9.id))
        assertEquals(OptionalLong.of(7), store.previousChunkGeneration(g9.id))
    }

    @Test
    fun `前の印を2つ覚え、3回目に進めたときに最初の印が消える`() {
        val claimed = claimFresh(6)
        val g7 = writeTimes(claimed, 1)
        // claim の印は 6（ファイルはない）。g7 → g8 → g9 → g10
        store.markChunkGeneration(g7.id, 7, p)
        val g8 = writeTimes(g7, 1)
        store.markChunkGeneration(g8.id, 8, p)
        val g9 = writeTimes(g8, 1)
        store.markChunkGeneration(g9.id, 9, p)
        assertEquals(listOf(7L, 8L, 9L), store.generationsOnDisk(g9.id))
        assertEquals(listOf(8L, 7L), store.previousChunkGenerations(g9.id))
        val g10 = writeTimes(g9, 1)
        store.markChunkGeneration(g10.id, 10, p)
        assertEquals(listOf(8L, 9L, 10L), store.generationsOnDisk(g10.id))
    }

    @Test
    fun `持ち主でない位置の写しのタグでは、印も世代ファイルも変わらない`() {
        val claimed = claimFresh(5)
        val g6 = writeTimes(claimed, 1)
        store.markChunkGeneration(g6.id, 6, p)
        val g7 = writeTimes(g6, 1)
        store.markChunkGeneration(g7.id, 7, p)
        val files = store.generationsOnDisk(g7.id)
        assertEquals(listOf(6L, 7L), files)
        assertEquals(ChunkMarkResult.NOT_OWNER, store.markChunkGeneration(g7.id, 5, q))
        assertEquals(ChunkMarkResult.NOT_OWNER, store.markChunkGeneration(g7.id, 9, pNether), "同じ座標で別のディメンション")
        assertEquals(OptionalLong.of(7), store.chunkGeneration(g7.id))
        assertEquals(files, store.generationsOnDisk(g7.id))
        val unknown = UUID.randomUUID()
        assertEquals(ChunkMarkResult.UNKNOWN_ID, store.markChunkGeneration(unknown, 3, p))
        assertEquals(OptionalLong.empty(), store.chunkGeneration(unknown))
    }

    @Test
    fun `戻す向きでは消さず、次に進めたときに戻した印を残す`() {
        val claimed = claimFresh(4)
        var id = writeTimes(claimed, 1)
        store.markChunkGeneration(id.id, 5, p)
        id = writeTimes(id, 1)
        store.markChunkGeneration(id.id, 6, p)
        id = writeTimes(id, 1)
        store.markChunkGeneration(id.id, 7, p)
        assertEquals(listOf(5L, 6L, 7L), store.generationsOnDisk(id.id))
        assertEquals(ChunkMarkResult.MOVED_BACK, store.markChunkGeneration(id.id, 5, p))
        assertEquals(listOf(5L, 6L, 7L), store.generationsOnDisk(id.id), "戻す向きでは消さない")
        assertEquals(OptionalLong.of(7), store.previousChunkGeneration(id.id))
        val g8 = writeTimes(id, 1)
        assertEquals(8, g8.generation)
        assertEquals(ChunkMarkResult.ADVANCED, store.markChunkGeneration(id.id, 8, p))
        // 前の印 5 と、2つ目の前の印 7 が残る（g6 は消える）
        assertEquals(listOf(5L, 7L, 8L), store.generationsOnDisk(id.id))
    }

    @Test
    fun `引き取りで印が前の持ち主の印より小さくても消さず、次に進めたら引き取った世代と新しい世代が残る`() {
        val original = claimFresh(0)
        val g7 = writeTimes(original, 7)
        val before = store.generationsOnDisk(g7.id)
        assertTrue(before.containsAll(listOf(3L, 6L, 7L)), "$before")
        // P のブロックがアンロードされ、世代 3 を指す古い NBT のブロックが Q で読み込まれる
        index.removeIfOwner(g7.id, p)
        val taken = store.claim(g7.id.toString(), 3, 0, q)
        assertEquals(ClaimResult.TAKEN_OVER, taken.result)
        assertEquals(OptionalLong.of(3), store.chunkGeneration(g7.id))
        assertEquals(OptionalLong.empty(), store.previousChunkGeneration(g7.id))
        assertEquals(before, store.generationsOnDisk(g7.id), "引き取りでは消さない")
        val g8 = store.write(taken.identity!!, q, source).identity
        assertEquals(8, g8.generation, "次の世代は覚えている最新より大きい")
        assertEquals(ChunkMarkResult.NOT_OWNER, store.markChunkGeneration(g8.id, 7, p), "前の持ち主の位置のタグでは動かない")
        assertEquals(ChunkMarkResult.ADVANCED, store.markChunkGeneration(g8.id, 8, q))
        assertEquals(listOf(3L, 8L), store.generationsOnDisk(g8.id))
    }

    @Test
    fun `deleteはディレクトリを消し、覚えていることも忘れる`() {
        val id = writeTimes(store.assignNewId(p)!!, 2)
        store.delete(id.id)
        assertFalse(Files.exists(store.directoryOf(id.id)))
        assertEquals(null, store.ownerOf(id.id))
        assertEquals(ChunkMarkResult.UNKNOWN_ID, store.markChunkGeneration(id.id, 2, p))
    }
}
