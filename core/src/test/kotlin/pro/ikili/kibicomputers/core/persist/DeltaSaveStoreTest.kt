package pro.ikili.kibicomputers.core.persist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** 差分の世代の置き場（#83、ADR 0083）: 基準の覚え方・書く前の確かめ・取り直し・残す世代 */
class DeltaSaveStoreTest {
    @TempDir
    lateinit var dir: Path

    private val p = MachineOwner("minecraft:overworld", 0, 64, 0)
    private val q = MachineOwner("minecraft:overworld", 16, 64, 0)

    private val index = MachineIdIndex()
    private val store by lazy { MachineSaveStore(dir, index, "test") }

    /** 書けた世代（[MachineSnapshotSource.committed] に来たもの） */
    private val committed = ArrayList<Pair<UUID, Long>>()

    /** 全体の写し（[base] が `null`）か、[base] を指す差分の写し */
    private fun src(base: DeltaBase?): MachineSnapshotSource = object : MachineSnapshotSource {
        override val deltaBase: DeltaBase? get() = base

        override fun build(id: UUID, generation: Long, modVersion: String): MachineSnapshot =
            MachineSnapshot(id, generation, modVersion, listOf(SnapshotSection(SnapshotSection.KERNEL, 1, ByteArray(16))), deltaBase = base)

        override fun committed(id: UUID, generation: Long) {
            committed += id to generation
        }
    }

    private fun full(store: MachineSaveStore, identity: MachineIdentity): MachineIdentity = store.write(identity, p, src(null)).identity

    private fun delta(store: MachineSaveStore, identity: MachineIdentity): MachineIdentity {
        val base = store.deltaBaseFor(identity, p) ?: error("基準がない")
        val result = store.write(identity, p, src(base))
        assertTrue(result.delta)
        return result.identity
    }

    private fun exists(store: MachineSaveStore, id: UUID, generation: Long) = Files.exists(store.generationFile(id, generation))

    @Test
    fun `全体を書くと基準を覚え、差分はDELTA_BASEで基準を指す`() {
        val identity = store.assignNewId(p)!!
        assertNull(store.deltaBaseFor(identity, p), "初めての保存は全体")
        val g1 = full(store, identity)
        val base = store.deltaBaseFor(g1, p)!!
        assertEquals(store.readGeneration(identity.id, 1).asDeltaBase(), base)
        assertEquals(Files.size(store.generationFile(identity.id, 1)), base.fileLength)
        val g2 = delta(store, g1)
        val stored = store.readGeneration(identity.id, 2)
        assertEquals(base, stored.snapshot.deltaBase)
        // 差分を書いても基準は変わらない
        assertEquals(base, store.deltaBaseFor(g2, p))
        assertEquals(listOf(identity.id to 1L, identity.id to 2L), committed)
    }

    @Test
    fun `deltaBaseForがnullになる場合はwriteも差分を書かない（二つの確かめが一致する）`() {
        val identity = full(store, store.assignNewId(p)!!)
        val base = store.deltaBaseFor(identity, p)!!
        val cases = listOf(
            "持ち主が違う" to (identity to q),
            "epoch が違う" to (identity.copy(epoch = 5) to p),
            "世代の上限" to (identity.copy(generation = MachineIdentityLimits.MAX_COUNTER) to p),
        )
        for ((name, case) in cases) {
            val (current, self) = case
            assertNull(store.deltaBaseFor(current, self), name)
            val before = store.generationsOnDisk(identity.id)
            assertThrows<DeltaBaseUnavailableException>(name) { store.write(current, self, src(base)) }
            assertEquals(before, store.generationsOnDisk(identity.id), "$name: 何も書かない")
        }
        // 基準を知らない（ほかの UUID）
        val other = store.assignNewId(p)!!
        assertNull(store.deltaBaseFor(other, p))
        assertThrows<DeltaBaseUnavailableException> { store.write(other, p, src(base)) }
        // 振り直した後は、新しい UUID の基準を知らないので全体
        val reassigned = store.write(identity.copy(epoch = 5), p, src(null))
        assertTrue(reassigned.reassigned)
        assertNotEquals(identity.id, reassigned.identity.id)
        assertEquals(reassigned.identity.id, store.deltaBaseFor(reassigned.identity, p)?.id, "振り直した先で書いた全体が新しい基準")
        // 基準が古い（もっと新しい全体を書いた後）
        val g2 = full(store, reassigned.identity)
        assertThrows<DeltaBaseUnavailableException> { store.write(g2, p, src(store.readGeneration(g2.id, 1).asDeltaBase())) }
    }

    @Test
    fun `取り直し、writeが基準を使えなければ、全体を取り直して書く`() {
        val g1 = full(store, store.assignNewId(p)!!)
        val calls = ArrayList<Pair<DeltaBase?, Boolean>>()
        val capturer = SnapshotCapturer { base, retry ->
            calls += base to retry
            if (!retry) {
                // 取り出しの後に置き場の基準が変わる
                store.write(g1, p, src(null))
            }
            src(base)
        }
        val result = store.captureAndSubmit(g1, p, capturer, null)!!
        val written = store.pollCompleted().single { it.pending === result.pending }.result!!
        assertTrue(result.retried)
        assertFalse(written.delta)
        assertEquals(3, written.identity.generation)
        assertEquals(2, calls.size)
        assertNotNull(calls[0].first)
        assertEquals(null to true, calls[1])
        // ふだんは取り直さずに差分
        val again = store.captureAndSubmit(written.identity, p, { base, _ -> src(base) }, null)!!
        val againWritten = store.pollCompleted().single().result!!
        assertFalse(again.retried)
        assertTrue(againWritten.delta)
        // 取り出せなければ null
        assertNull(store.captureAndSubmit(againWritten.identity, p, { _, _ -> null }, null))
    }

    @Test
    fun `印が進んで基準が古くなっても、残す世代が指す基準は残る`() {
        var id = full(store, store.assignNewId(p)!!)
        repeat(4) { id = delta(store, id) }
        for (g in 2L..5L) assertEquals(ChunkMarkResult.ADVANCED, store.markChunkGeneration(id.id, g, p))
        assertTrue(exists(store, id.id, 1), "差分が指す全体の世代")
        assertFalse(exists(store, id.id, 2))
    }

    @Test
    fun `(a) 再起動の後も、前の印の差分の頭から基準を引いて残す`() {
        val s1 = MachineSaveStore(dir, MachineIdIndex(), "test")
        var id = full(s1, s1.assignNewId(p)!!)
        id = delta(s1, id) // g2 → g1
        id = delta(s1, id) // g3 → g1
        // 再起動: NBT は g3 を指す
        val s2 = MachineSaveStore(dir, MachineIdIndex(), "test")
        val claim = s2.claim(id.id.toString(), 3, 0, p)
        assertEquals(ClaimResult.KEPT, claim.result)
        assertNull(s2.deltaBaseFor(claim.identity!!, p), "再起動の後の最初の保存は全体")
        val g4 = full(s2, claim.identity!!)
        assertEquals(4, g4.generation)
        assertEquals(ChunkMarkResult.ADVANCED, s2.markChunkGeneration(id.id, 4, p))
        assertTrue(exists(s2, id.id, 1), "前の印 g3 が指す基準")
        assertTrue(exists(s2, id.id, 3))
        assertFalse(exists(s2, id.id, 2))
        assertEquals(setOf(3L, 4L), s2.rememberedBaseGenerations(id.id))
    }

    /** g1 全体・g2 全体・g3 差分（g2 を指す）を書き、再起動して NBT が g3 を指す置き場を返す */
    private fun restartedAtDelta(): Pair<MachineSaveStore, MachineIdentity> {
        val s1 = MachineSaveStore(dir, MachineIdIndex(), "test")
        var id = full(s1, s1.assignNewId(p)!!)
        id = full(s1, id)
        id = delta(s1, id)
        val s2 = MachineSaveStore(dir, MachineIdIndex(), "test")
        return s2 to s2.claim(id.id.toString(), 3, 0, p).identity!!
    }

    /** 今の印 g3 の頭が読めないとき、印を進めた [n] 回目まで消さず、3 回目で掃除が再開する */
    private fun assertResumesAfterThree(s: MachineSaveStore, start: MachineIdentity) {
        var id = start
        for (round in 1..3) {
            id = full(s, id)
            assertEquals(ChunkMarkResult.ADVANCED, s.markChunkGeneration(id.id, id.generation, p))
            if (round < 3) {
                assertTrue(exists(s, id.id, 1), "$round 回目: 頭を読めないので g1 も消さない")
                assertTrue(exists(s, id.id, 3))
            }
        }
        assertFalse(exists(s, id.id, 1), "3 回目で読めない世代が K から外れ、掃除が再開する")
        assertFalse(exists(s, id.id, 2))
        assertFalse(exists(s, id.id, 3), "読めない差分そのものも消える")
        assertTrue(exists(s, id.id, 4) && exists(s, id.id, 5) && exists(s, id.id, 6))
    }

    @Test
    fun `(b) 今の印の頭が壊れていると、3回進めるまで消さない`() {
        val (s, id) = restartedAtDelta()
        val file = s.generationFile(id.id, 3)
        val bytes = Files.readAllBytes(file)
        // 外枠の後の節の頭の途中で切る（SnapshotFormatException）
        Files.write(file, bytes.copyOf(36 + 3))
        assertThrows<SnapshotFormatException> { java.io.DataInputStream(Files.newInputStream(file)).use { MachineSnapshot.readDeltaBase(it) } }
        assertResumesAfterThree(s, id)
    }

    @Test
    fun `(b) 今の印の頭を読む手がIOExceptionを投げると、3回進めるまで消さない`() {
        val (s, id) = restartedAtDelta()
        val default = s.deltaBaseReader
        s.deltaBaseReader = { path -> if (path.fileName.toString() == "3.bin") throw IOException("共有違反") else default(path) }
        assertResumesAfterThree(s, id)
    }

    @Test
    fun `(b) 前の印の頭が読めないときは、2回進めると掃除が再開する`() {
        var id = full(store, store.assignNewId(p)!!) // g1
        id = full(store, id) // g2
        id = delta(store, id) // g3 → g2
        id = full(store, id) // g4
        assertEquals(ChunkMarkResult.ADVANCED, store.markChunkGeneration(id.id, 3, p))
        assertEquals(ChunkMarkResult.ADVANCED, store.markChunkGeneration(id.id, 4, p)) // g3 は前の印
        store.forgetBaseCache(id.id)
        // 頭を読めれば消える世代（印より小さく、どの世代も指さない）
        Files.copy(store.generationFile(id.id, 4), store.generationFile(id.id, 0))
        val default = store.deltaBaseReader
        store.deltaBaseReader = { path -> if (path.fileName.toString() == "3.bin") throw IOException("共有違反") else default(path) }
        id = full(store, id) // g5
        assertEquals(ChunkMarkResult.ADVANCED, store.markChunkGeneration(id.id, 5, p))
        assertTrue(exists(store, id.id, 0), "1 回目: 消さない")
        id = full(store, id) // g6
        assertEquals(ChunkMarkResult.ADVANCED, store.markChunkGeneration(id.id, 6, p))
        assertFalse(exists(store, id.id, 0), "2 回目で g3 が K から外れ、掃除が再開する")
        assertFalse(exists(store, id.id, 3))
    }

    @Test
    fun `(c) 印より大きい差分の世代の基準も残る`() {
        var id = full(store, store.assignNewId(p)!!) // g1
        id = delta(store, id) // g2 → g1
        id = delta(store, id) // g3 → g1
        id = full(store, id) // g4（今の基準）
        // 印は g2 を指すが、g2 のファイルはない（g3 が印より大きい差分）
        Files.delete(store.generationFile(id.id, 2))
        assertEquals(ChunkMarkResult.ADVANCED, store.markChunkGeneration(id.id, 2, p))
        assertTrue(exists(store, id.id, 1), "印より大きい g3 が指す基準")
    }

    @Test
    fun `(d) 消した世代の頭の写しを忘れ、deleteと振り直しで空になる`() {
        var id = full(store, store.assignNewId(p)!!) // g1
        id = delta(store, id) // g2 → g1
        id = delta(store, id) // g3 → g1
        id = full(store, id) // g4
        id = delta(store, id) // g5 → g4
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L), store.rememberedBaseGenerations(id.id))
        store.markChunkGeneration(id.id, 2, p)
        store.markChunkGeneration(id.id, 4, p) // g3 が消える（g1 は前の印 g2 が指す）
        assertFalse(exists(store, id.id, 3))
        assertEquals(setOf(1L, 2L, 4L, 5L), store.rememberedBaseGenerations(id.id))
        id = delta(store, id) // g6 → g4
        store.markChunkGeneration(id.id, 5, p)
        store.markChunkGeneration(id.id, 6, p) // g1・g2 が消える
        assertFalse(exists(store, id.id, 1))
        assertEquals(setOf(4L, 5L, 6L), store.rememberedBaseGenerations(id.id))

        // write で振り直した元の UUID
        val moved = store.write(id.copy(epoch = 9), p, src(null))
        assertTrue(moved.reassigned)
        assertEquals(emptySet<Long>(), store.rememberedBaseGenerations(id.id))
        assertEquals(setOf(1L), store.rememberedBaseGenerations(moved.identity.id))
        // delete
        store.delete(moved.identity.id)
        assertEquals(emptySet<Long>(), store.rememberedBaseGenerations(moved.identity.id))
        assertNull(store.currentBase(moved.identity.id))
        // assignNewId は新しい UUID を空から始める
        val fresh = store.assignNewId(p)!!
        assertEquals(emptySet<Long>(), store.rememberedBaseGenerations(fresh.id))
    }
}
