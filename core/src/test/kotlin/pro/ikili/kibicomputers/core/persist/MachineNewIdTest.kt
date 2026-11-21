package pro.ikili.kibicomputers.core.persist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** 新しい ID を振るときの重なりの確かめ（#579。開いたときに名前を読んだ集合だけを見て、振るときにディスクを見ない） */
class MachineNewIdTest {
    @TempDir
    lateinit var dir: Path

    private val p = MachineOwner("minecraft:overworld", 0, 64, 0)
    private val index = MachineIdIndex()
    private val manual = ManualSaveExecutor()

    private val x = UUID.fromString("00000000-0000-4000-8000-000000000001")
    private val y = UUID.fromString("00000000-0000-4000-8000-000000000002")
    private val z = UUID.fromString("00000000-0000-4000-8000-000000000003")
    private val w = UUID.fromString("00000000-0000-4000-8000-000000000004")

    private fun src(): MachineSnapshotSource = object : MachineSnapshotSource {
        override val deltaBase: DeltaBase? get() = null
        override val estimatedBytes: Long get() = 0

        override fun build(id: UUID, generation: Long, modVersion: String): MachineSnapshot =
            MachineSnapshot(id, generation, modVersion, listOf(SnapshotSection(SnapshotSection.KERNEL, 1, ByteArray(16))))

        override fun committed(id: UUID, generation: Long) = Unit
    }

    /** [ids] を順に返し、尽きたら乱数（同じ値を返し続けて `newUuid` が抜けなくならないように） */
    private fun MachineSaveStore.useIds(vararg ids: UUID) {
        val queue = ArrayDeque(ids.toList())
        uuidSource = { queue.removeFirstOrNull() ?: UUID.randomUUID() }
    }

    @Test
    fun `開く前からディレクトリがあるUUIDは、submitでもassignNewIdでも振らない`() {
        Files.createDirectories(dir.resolve(x.toString()))
        val store = MachineSaveStore(dir, index, "test", manual)
        store.useIds(x, z)
        assertEquals(z, store.submit(null, p, src(), null).identity.id)
        store.useIds(x, y)
        assertEquals(y, store.assignNewId(p)?.id)
    }

    @Test
    fun `大文字の名前のディレクトリと、ディレクトリでないファイルの名前も避け、UUIDでない名前は読み飛ばす`() {
        Files.createDirectories(dir.resolve(x.toString().uppercase()))
        Files.write(dir.resolve(y.toString()), byteArrayOf(1))
        Files.createDirectories(dir.resolve("not-a-uuid"))
        Files.createDirectories(dir.resolve("zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz"))
        val store = MachineSaveStore(dir, index, "test", manual)
        store.useIds(x, y, z)
        assertEquals(z, store.assignNewId(p)?.id)
    }

    @Test
    fun `振るときはディスクを見ない（開いた後に消したディレクトリも避け、名前を読むのは開いたときの1回だけ）`() {
        val existing = dir.resolve(x.toString())
        Files.createDirectories(existing)
        val store = MachineSaveStore(dir, index, "test", manual)
        assertEquals(1, store.directoryScans)
        Files.delete(existing)
        store.useIds(x, z)
        // ディスクを見ていれば、消えた x を振るはず
        assertEquals(z, store.submit(null, p, src(), null).identity.id)
        assertEquals(1, manual.size, "書き込みの仕事は走らせていない")
        store.useIds(x, y)
        assertEquals(y, store.assignNewId(p)?.id)
        assertEquals(1, store.directoryScans, "振った後も読み直さない")
    }

    @Test
    fun `動いている間に外から置かれたディレクトリは見ず、submitでもassignNewIdでもそのUUIDを振る`() {
        val store = MachineSaveStore(dir, index, "test", manual)
        // 開いた後に、サーバーの外からディレクトリを置く（ADR 0084「動いている間に外から置かれたディレクトリは見ない」）。
        // submit が振った ID は索引・owners・集合に入るので、submit と assignNewId で別の UUID を使う
        Files.createDirectories(dir.resolve(x.toString()))
        Files.createDirectories(dir.resolve(y.toString()))
        store.useIds(x)
        // ディスクを見ていれば、x を避けて乱数を振るはず
        assertEquals(x, store.submit(null, p, src(), null).identity.id)
        store.useIds(y)
        assertEquals(y, store.assignNewId(p)?.id)
        assertEquals(1, store.directoryScans, "振るときに読み直さない")
    }

    @Test
    fun `開いた後に振ったUUIDは、書き終わって索引から外れても振らない`() {
        val store = MachineSaveStore(dir, index, "test", manual)
        store.useIds(x)
        val first = store.submit(null, p, src(), null)
        assertEquals(x, first.identity.id)
        manual.runAll()
        store.pollCompleted()
        // 索引から外れても（アンロードの後など）、覚えている owner.bin（と集合）があるので振らない
        index.removeIfOwner(x, p)
        store.useIds(x, z)
        assertEquals(z, store.assignNewId(p)?.id)
    }

    @Test
    fun `deleteした後のUUIDも振らない`() {
        val store = MachineSaveStore(dir, index, "test", manual)
        store.useIds(x)
        assertEquals(x, store.assignNewId(p)?.id)
        index.removeIfOwner(x, p)
        store.delete(x)
        manual.runAll()
        assertFalse(Files.exists(store.directoryOf(x)))
        store.useIds(x, z)
        assertEquals(z, store.assignNewId(p)?.id)
    }

    @Test
    fun `新しく振ったowner_binが書けず外したUUIDも振らない`() {
        // 根をファイルにして、どのディレクトリも作れなくする
        val root = dir.resolve("root")
        Files.write(root, byteArrayOf(1))
        val store = MachineSaveStore(root, index, "test", manual)
        store.useIds(x)
        assertEquals(x, store.submit(null, p, src(), null).identity.id)
        manual.runAll()
        assertFalse(store.pollCompleted().single().succeeded)
        Files.delete(root)
        Files.createDirectories(root)
        store.useIds(x, z)
        assertEquals(z, store.submit(null, p, src(), null).identity.id)
    }

    @Test
    fun `claimで受け入れたNBTのUUIDは、owner_binが書けずに外れても振らない`() {
        val root = dir.resolve("root")
        Files.write(root, byteArrayOf(1))
        val store = MachineSaveStore(root, index, "test", manual)
        // owner.bin がないので KEPT。owner.bin は最初の世代の前に書く（#585）が、根がファイルなので書けない
        val claim = store.claim(y.toString(), 0, 0, p)
        assertEquals(ClaimResult.KEPT, claim.result)
        store.submit(claim.identity!!, p, src(), null)
        manual.runAll()
        assertFalse(store.pollCompleted().single().succeeded)
        assertEquals(null, store.ownerOf(y), "書けなかった ID は外す")
        Files.delete(root)
        Files.createDirectories(root)
        store.useIds(y, w)
        assertEquals(w, store.assignNewId(p)?.id)
    }

    @Test
    fun `根がない・根がファイルでも開け、空の集合で始める`() {
        val missing = MachineSaveStore(dir.resolve("missing"), index, "test", manual)
        missing.useIds(x)
        assertNotNull(missing.submit(null, p, src(), null))
        val file = dir.resolve("file")
        Files.write(file, byteArrayOf(1))
        val onFile = MachineSaveStore(file, index, "test", manual)
        assertEquals(1, onFile.directoryScans)
        onFile.useIds(y)
        assertTrue(onFile.submit(null, p, src(), null).identity.id == y)
    }
}
