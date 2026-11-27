package pro.ikili.kibicomputers.core.persist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import pro.ikili.kibicomputers.core.kernel.joinOrFail
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 持ち主の規則（`MachineSaveStore.claim`。回答 A の epoch 方式）のテスト（#81、ADR 0081）。
 * 実スレッドの終わりは壁時計の期限で合否を決めずに待つ（#878）。クラスの [Timeout] は、止まったときの保険（CI のジョブを止めない）で、合否の基準ではない
 */
@Timeout(10, unit = TimeUnit.MINUTES)
class MachineClaimTest {
    @TempDir
    lateinit var dir: Path

    private val index = MachineIdIndex()
    private val store by lazy { MachineSaveStore(dir, index) }

    private fun at(x: Int) = MachineOwner("minecraft:overworld", x, 64, 0)

    private fun snapshot(): MachineSnapshotSource = object : MachineSnapshotSource {
        override val deltaBase: DeltaBase? get() = null
        override val estimatedBytes: Long get() = 0

        override fun build(id: UUID, generation: Long, modVersion: String): MachineSnapshot =
            MachineSnapshot(id, generation, modVersion, listOf(SnapshotSection(SnapshotSection.KERNEL, 1, ByteArray(16))), deltaBase = null)

        override fun committed(id: UUID, generation: Long) = Unit
    }

    /** ブロックエンティティ1つ（NBT の値と位置）。[load] で claim し、[unload] で索引から外す */
    private inner class Block(var id: String, var generation: Long, var epoch: Long, val pos: MachineOwner) {
        fun load(): ClaimResult {
            val claim = store.claim(id, generation, epoch, pos)
            claim.identity?.let {
                id = it.id.toString()
                generation = it.generation
                epoch = it.epoch
            }
            return claim.result
        }

        fun unload() = index.removeIfOwner(UUID.fromString(id), pos)

        /** このブロックの NBT を写した、別の位置のブロック */
        fun copyTo(x: Int) = Block(id, generation, epoch, at(x))
    }

    private fun original(): Block {
        val identity = store.assignNewId(at(0))!!
        index.removeIfOwner(identity.id, at(0))
        return Block(identity.id.toString(), 0, 0, at(0))
    }

    @Test
    fun `同時に読み込まれた重複は、後から来た方が新しいUUID`() {
        val a = original()
        assertEquals(ClaimResult.KEPT, a.load())
        val b = a.copyTo(5)
        assertEquals(ClaimResult.NEW_ID_DUPLICATE_LOADED, b.load())
        assertNotEquals(a.id, b.id)
        assertEquals(at(5), index.ownerOf(UUID.fromString(b.id)))
    }

    @Test
    fun `アンロードした後に同じUUIDを別の位置で読み込むと引き取る`() {
        val a = original()
        assertEquals(ClaimResult.KEPT, a.load())
        a.unload()
        val moved = a.copyTo(9)
        assertEquals(ClaimResult.TAKEN_OVER, moved.load())
        assertEquals(a.id, moved.id)
        assertEquals(1, moved.epoch)
        assertEquals(OwnerRecord(1, at(9)), OwnerRecord.read(store.directoryOf(UUID.fromString(a.id)).resolve(OwnerRecord.FILE_NAME)))
    }

    @Test
    fun `原本とコピーを交互に読み込むと、引き取った後は原本が冷えた起動になり、以後は安定する`() {
        val a = original()
        val copy = a.copyTo(3)
        assertEquals(ClaimResult.TAKEN_OVER, copy.load())
        copy.unload()
        assertEquals(ClaimResult.NEW_ID_STALE, a.load())
        a.unload()
        assertEquals(ClaimResult.KEPT, copy.load())
        copy.unload()
        assertEquals(ClaimResult.KEPT, a.load(), "新しい UUID の原本は自分が持ち主")
        assertNotEquals(a.id, copy.id)
    }

    @Test
    fun `コピー10個を順に読み込むと、最初の1つだけ引き取り、残りは冷えた起動`() {
        val a = original()
        val copies = (1..10).map { a.copyTo(it) }
        val results = copies.map { c -> c.load().also { c.unload() } }
        assertEquals(listOf(ClaimResult.TAKEN_OVER) + List(9) { ClaimResult.NEW_ID_STALE }, results)
        assertEquals(10, copies.map { it.id }.toSet().size)
    }

    @Test
    fun `引き取ったブロックをさらにコピーすると、最後に読み込んだ側が勝つ`() {
        val a = original()
        val b = a.copyTo(1)
        assertEquals(ClaimResult.TAKEN_OVER, b.load())
        b.unload()
        val c = b.copyTo(2)
        assertEquals(ClaimResult.TAKEN_OVER, c.load())
        assertEquals(2, c.epoch)
        c.unload()
        assertEquals(ClaimResult.NEW_ID_STALE, b.load())
    }

    @Test
    fun `AからBへ運び、Aへ運び戻しても状態が残る`() {
        val a = original()
        assertEquals(ClaimResult.KEPT, a.load())
        a.unload()
        val atB = a.copyTo(7)
        assertEquals(ClaimResult.TAKEN_OVER, atB.load())
        atB.unload()
        val back = atB.copyTo(0)
        assertEquals(ClaimResult.TAKEN_OVER, back.load())
        assertEquals(a.id, back.id)
        assertEquals(2, back.epoch)
    }

    @Test
    fun `引き取った後、チャンクの保存の前に落ちたとき（NBTのepochが古く持ち主が自分）はKEPTで直す`() {
        val a = original()
        val b = a.copyTo(4)
        assertEquals(ClaimResult.TAKEN_OVER, b.load())
        b.unload()
        // チャンクの NBT には引き取る前の epoch 0 が残っている
        val crashed = Block(b.id, 0, 0, at(4))
        assertEquals(ClaimResult.KEPT, crashed.load())
        assertEquals(1, crashed.epoch)
    }

    private fun corrupt(bytes: ByteArray): ClaimResult {
        val id = UUID.randomUUID()
        Files.createDirectories(dir.resolve(id.toString()))
        Files.write(dir.resolve(id.toString()).resolve(OwnerRecord.FILE_NAME), bytes)
        val fresh = MachineSaveStore(dir, MachineIdIndex())
        val claim = fresh.claim(id.toString(), 0, 0, at(1))
        assertNotEquals(id, claim.identity!!.id)
        return claim.result
    }

    private fun ownerBytes(magic: String = "KIBO", version: Int = 1, epoch: Long = 0): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.write(magic.toByteArray())
            d.writeShort(version)
            d.writeLong(epoch)
            d.writeUTF("minecraft:overworld")
            d.writeInt(0)
            d.writeInt(64)
            d.writeInt(0)
        }
        return out.toByteArray()
    }

    @Test
    fun `owner_binが壊れている・magic違い・版違い・epochが範囲外なら例外を出さずNEW_ID_UNREADABLE`() {
        assertEquals(ClaimResult.NEW_ID_UNREADABLE, corrupt(byteArrayOf(1, 2, 3)))
        assertEquals(ClaimResult.NEW_ID_UNREADABLE, corrupt(ownerBytes(magic = "XXXX")))
        assertEquals(ClaimResult.NEW_ID_UNREADABLE, corrupt(ownerBytes(version = 2)))
        assertEquals(ClaimResult.NEW_ID_UNREADABLE, corrupt(ownerBytes(epoch = -1)))
        assertEquals(ClaimResult.NEW_ID_UNREADABLE, corrupt(ownerBytes(epoch = MachineIdentityLimits.MAX_COUNTER + 1)))
    }

    @Test
    fun `owner_binのepochが上限のとき引き取ると、負にならずNEW_ID_INVALID`() {
        val id = UUID.randomUUID()
        OwnerRecord.write(dir.resolve(id.toString()).resolve(OwnerRecord.FILE_NAME), OwnerRecord(MachineIdentityLimits.MAX_COUNTER, at(0)))
        val claim = store.claim(id.toString(), 0, MachineIdentityLimits.MAX_COUNTER, at(1))
        assertEquals(ClaimResult.NEW_ID_INVALID, claim.result)
        assertEquals(0, claim.identity!!.epoch)
    }

    @Test
    fun `NBTのOwnerEpochがLong_MAX_VALUEならNEW_ID_INVALID`() {
        val a = original()
        a.epoch = Long.MAX_VALUE
        assertEquals(ClaimResult.NEW_ID_INVALID, a.load())
    }

    // ---- メインスレッドの I/O（#585） ----

    @Test
    fun `開いたときに根になかったIDは、owner_binを読まずにKEPTにし、書くのは最初の世代の前`() {
        val id = UUID.randomUUID()
        val claim = store.claim(id.toString(), 3, 2, at(1))
        assertEquals(ClaimResult.KEPT, claim.result)
        assertEquals(MachineIdentity(id, 3, 2), claim.identity)
        assertEquals(0, store.mainOwnerReads)
        assertEquals(false, Files.exists(store.directoryOf(id)), "claim では書かない")
        assertEquals(at(1), store.ownerOf(id))
        store.write(claim.identity!!, at(1)) { i, g, v -> MachineSnapshot(i, g, v) }
        assertEquals(OwnerRecord(2, at(1)), OwnerRecord.read(store.directoryOf(id).resolve(OwnerRecord.FILE_NAME)))
    }

    @Test
    fun `写しは何台貼っても、claimではディスクに触れない`() {
        val a = original()
        assertEquals(ClaimResult.KEPT, a.load())
        val copies = (1..20).map { a.copyTo(it) }
        assertEquals(List(20) { ClaimResult.NEW_ID_DUPLICATE_LOADED }, copies.map { it.load() })
        assertEquals(0, store.mainOwnerReads)
        Files.newDirectoryStream(dir).use { assertEquals(0, it.count(), "ディレクトリも作らない") }
    }

    @Test
    fun `根の名前を読みきれなかったときは、知らないIDでもowner_binを読む`() {
        val id = UUID.randomUUID()
        val fresh = MachineSaveStore(dir, MachineIdIndex())
        // 開いた後に置かれた owner.bin（列挙が欠けたときに見落としうるものの代わり）
        OwnerRecord.write(dir.resolve(id.toString()).resolve(OwnerRecord.FILE_NAME), OwnerRecord(4, at(0)))
        fresh.scanComplete = false
        val claim = fresh.claim(id.toString(), 0, 0, at(1))
        assertEquals(ClaimResult.NEW_ID_STALE, claim.result, "読んだので、古い写しが状態を奪わない")
        assertEquals(1, fresh.mainOwnerReads)
    }

    @Test
    fun `先読みが終わっていれば、開いたときにあったIDもowner_binを読まない`() {
        val mine = UUID.randomUUID()
        val other = UUID.randomUUID()
        val bare = UUID.randomUUID()
        OwnerRecord.write(dir.resolve(mine.toString()).resolve(OwnerRecord.FILE_NAME), OwnerRecord(1, at(1)))
        OwnerRecord.write(dir.resolve(other.toString()).resolve(OwnerRecord.FILE_NAME), OwnerRecord(5, at(0)))
        Files.createDirectories(dir.resolve(bare.toString()))
        val fresh = MachineSaveStore(dir, MachineIdIndex())
        fresh.startOwnerPrefetch { it.run() }
        assertEquals(true, fresh.ownerPrefetchDone)
        assertEquals(ClaimResult.KEPT, fresh.claim(mine.toString(), 0, 0, at(1)).result)
        assertEquals(ClaimResult.NEW_ID_STALE, fresh.claim(other.toString(), 0, 0, at(1)).result)
        assertEquals(ClaimResult.KEPT, fresh.claim(bare.toString(), 0, 0, at(2)).result, "owner.bin のないディレクトリ")
        assertEquals(3, fresh.prefetchHits)
        assertEquals(0, fresh.mainOwnerReads)
    }

    @Test
    fun `先読みが終わる前のclaimは読み、触ったIDには先読みの値を使わない`() {
        val id = UUID.randomUUID()
        OwnerRecord.write(dir.resolve(id.toString()).resolve(OwnerRecord.FILE_NAME), OwnerRecord(0, at(0)))
        val fresh = MachineSaveStore(dir, MachineIdIndex())
        var prefetch: Runnable? = null
        fresh.startOwnerPrefetch { prefetch = it }
        assertEquals(ClaimResult.TAKEN_OVER, fresh.claim(id.toString(), 0, 0, at(1)).result)
        assertEquals(1, fresh.mainOwnerReads)
        // 先読みはディスクの (0, at(0)) を見るが、引き取った後は覚えている (1, at(1)) が勝つ
        prefetch!!.run()
        fresh.index.removeIfOwner(id, at(1))
        assertEquals(ClaimResult.NEW_ID_STALE, fresh.claim(id.toString(), 0, 0, at(0)).result)
        // 消した後も、先読みの値（持ち主 at(0)）は使わない
        fresh.delete(id)
        assertEquals(ClaimResult.KEPT, fresh.claim(id.toString(), 0, 0, at(3)).result)
        assertEquals(0, fresh.prefetchHits)
        assertEquals(1, fresh.mainOwnerReads)
    }

    @Test
    fun `先読みが読んでいるowner_binは、読み終えるまで書き込みのスレッドが置き換えず、触ったIDは先読みが開かない`() {
        val reading = UUID.randomUUID()
        val other = UUID.randomUUID()
        OwnerRecord.write(dir.resolve(reading.toString()).resolve(OwnerRecord.FILE_NAME), OwnerRecord(0, at(0)))
        OwnerRecord.write(dir.resolve(other.toString()).resolve(OwnerRecord.FILE_NAME), OwnerRecord(0, at(0)))
        val manual = ManualSaveExecutor()
        val fresh = MachineSaveStore(dir, MachineIdIndex(), "test", manual)
        val opened = Collections.synchronizedList(ArrayList<UUID>())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        // 最初に開いた ID の読みの途中で止める
        fresh.prefetchOwnerReader = { path ->
            val id = UUID.fromString(path.parent.fileName.toString())
            opened += id
            if (opened.size == 1) {
                entered.countDown()
                release.await()
            }
            OwnerRecord.read(path)
        }
        var prefetch: Thread? = null
        fresh.startOwnerPrefetch { prefetch = Thread(it).apply { start() } }
        val first: UUID
        val second: UUID
        var writer: Thread? = null
        try {
            entered.await()
            first = opened.single()
            second = if (first == reading) other else reading
            // 読んでいる ID と、まだ開いていない ID を引き取る（どちらも owner.bin を書く仕事が列に入る。後者は先読みの前にメインスレッドで読む）
            assertEquals(ClaimResult.TAKEN_OVER, fresh.claim(first.toString(), 0, 0, at(1)).result)
            assertEquals(ClaimResult.TAKEN_OVER, fresh.claim(second.toString(), 0, 0, at(1)).result)
            val started = Thread { manual.runAll() }.apply { start() }
            writer = started
            // 待たされていることを確かめる（負荷が高いほど通りやすい向きなので、短い時間のままでよい）
            started.join(300)
            assertTrue(started.isAlive, "先読みが読み終えるまで、その owner.bin を置き換えない")
        } finally {
            // 途中で落ちても、止めた先読みのスレッドを放し、書き込み・先読みのスレッドが終わるのを待つ（@TempDir の掃除と重ならないように）
            release.countDown()
            writer?.joinOrFail()
            prefetch?.joinOrFail()
        }
        assertEquals(listOf(first), opened.toList(), "触った ID は先読みが開かない")
        assertFalse(fresh.isUnusable(first))
        assertFalse(fresh.isUnusable(second))
        assertEquals(OwnerRecord(1, at(1)), OwnerRecord.read(dir.resolve(first.toString()).resolve(OwnerRecord.FILE_NAME)))
        assertEquals(OwnerRecord(1, at(1)), OwnerRecord.read(dir.resolve(second.toString()).resolve(OwnerRecord.FILE_NAME)))
    }

    @Test
    fun `先読みで読めなかったowner_binはNEW_ID_UNREADABLE`() {
        val id = UUID.randomUUID()
        Files.createDirectories(dir.resolve(id.toString()))
        Files.write(dir.resolve(id.toString()).resolve(OwnerRecord.FILE_NAME), byteArrayOf(1, 2, 3))
        val fresh = MachineSaveStore(dir, MachineIdIndex())
        fresh.startOwnerPrefetch { it.run() }
        val claim = fresh.claim(id.toString(), 0, 0, at(1))
        assertEquals(ClaimResult.NEW_ID_UNREADABLE, claim.result)
        assertNotEquals(id, claim.identity!!.id)
    }

    @Test
    fun `引き取りのowner_binを書く前に落ちても、次の起動で引き取り直し、原本は古い写しになる`() {
        // 原本 A（at(0)、epoch 0）の owner.bin がディスクにある
        val id = UUID.randomUUID()
        OwnerRecord.write(dir.resolve(id.toString()).resolve(OwnerRecord.FILE_NAME), OwnerRecord(0, at(0)))
        // 運んだ先 B（at(1)）が引き取り、NBT は epoch 1 になったが、owner.bin を書く前に落ちた（列の仕事を走らせない）
        val crashed = MachineSaveStore(dir, MachineIdIndex(), "test", ManualSaveExecutor())
        val taken = crashed.claim(id.toString(), 0, 0, at(1))
        assertEquals(ClaimResult.TAKEN_OVER, taken.result)
        assertEquals(1, taken.identity!!.epoch)
        assertEquals(OwnerRecord(0, at(0)), OwnerRecord.read(dir.resolve(id.toString()).resolve(OwnerRecord.FILE_NAME)))
        // 次の起動: B が先に読み込まれれば、もう一度引き取る（epoch 1）。A は古い写し
        val next = MachineSaveStore(dir, MachineIdIndex())
        val again = next.claim(id.toString(), 0, 1, at(1))
        assertEquals(ClaimResult.TAKEN_OVER, again.result)
        assertEquals(1, again.identity!!.epoch)
        assertEquals(OwnerRecord(1, at(1)), OwnerRecord.read(dir.resolve(id.toString()).resolve(OwnerRecord.FILE_NAME)))
        next.index.removeIfOwner(id, at(1))
        assertEquals(ClaimResult.NEW_ID_STALE, next.claim(id.toString(), 0, 0, at(0)).result)
    }

    @Test
    fun `KEPTや新しいIDのowner_binを書く前に落ちても、次の起動でまたKEPTになる`() {
        val crashed = MachineSaveStore(dir, MachineIdIndex(), "test", ManualSaveExecutor())
        val kept = UUID.randomUUID()
        assertEquals(ClaimResult.KEPT, crashed.claim(kept.toString(), 0, 0, at(1)).result)
        val assigned = crashed.assignNewId(at(2))!!
        // 世代を書く前に落ちた（owner.bin もディレクトリもない）。NBT は ID を持つ
        val next = MachineSaveStore(dir, MachineIdIndex())
        assertEquals(ClaimResult.KEPT, next.claim(kept.toString(), 0, 0, at(1)).result)
        val claim = next.claim(assigned.id.toString(), 0, 0, at(2))
        assertEquals(ClaimResult.KEPT, claim.result)
        assertEquals(assigned, claim.identity)
        assertEquals(0, next.mainOwnerReads)
    }

    @Test
    fun `scanDirectoriesは、根がなければ読みきれた、根がファイルなら読みきれなかったとする`() {
        val missing = MachineSaveStore(dir.resolve("missing"), MachineIdIndex())
        assertEquals(true, missing.scanComplete, "根がないと確かに分かる")
        val file = dir.resolve("file")
        Files.write(file, byteArrayOf(1))
        assertEquals(false, MachineSaveStore(file, MachineIdIndex()).scanComplete, "根がファイルなら、claim は読む側に倒す")
        val empty = dir.resolve("empty")
        Files.createDirectories(empty)
        assertEquals(true, MachineSaveStore(empty, MachineIdIndex()).scanComplete)
    }

    @Test
    fun `scanDirectoriesは、列挙を開けなかった・途中で失敗したときは読みきれなかったとし、読めた分は覚える`() {
        val listed = UUID.randomUUID()
        Files.createDirectories(dir.resolve(listed.toString()))
        val original = openRootEntries
        try {
            // 開けない
            openRootEntries = { throw java.io.IOException("cannot open") }
            assertEquals(false, MachineSaveStore(dir, MachineIdIndex()).scanComplete)
            // 途中で失敗する（1つ返した後、反復中の IOException）
            openRootEntries = { path ->
                object : java.nio.file.DirectoryStream<Path> {
                    override fun iterator(): MutableIterator<Path> {
                        var n = 0
                        return object : MutableIterator<Path> {
                            override fun hasNext(): Boolean {
                                if (n >= 1) throw java.nio.file.DirectoryIteratorException(java.io.IOException("broken"))
                                return true
                            }

                            override fun next(): Path = path.resolve(listed.toString()).also { n++ }

                            override fun remove() = throw UnsupportedOperationException()
                        }
                    }

                    override fun close() = Unit
                }
            }
            val partial = MachineSaveStore(dir, MachineIdIndex())
            assertEquals(false, partial.scanComplete, "途中の失敗の後に真にならない")
            // 欠けたかもしれないので、知らない ID でも owner.bin を読む
            val other = UUID.randomUUID()
            OwnerRecord.write(dir.resolve(other.toString()).resolve(OwnerRecord.FILE_NAME), OwnerRecord(4, at(0)))
            assertEquals(ClaimResult.NEW_ID_STALE, partial.claim(other.toString(), 0, 0, at(1)).result)
            assertEquals(1, partial.mainOwnerReads)
        } finally {
            openRootEntries = original
        }
        assertEquals(true, MachineSaveStore(dir, MachineIdIndex()).scanComplete)
    }

    @Test
    fun `実スレッドの先読み（prefetchOwners）が終われば、開いたときにあったIDもowner_binを読まない`() {
        val mine = UUID.randomUUID()
        val other = UUID.randomUUID()
        OwnerRecord.write(dir.resolve(mine.toString()).resolve(OwnerRecord.FILE_NAME), OwnerRecord(1, at(1)))
        OwnerRecord.write(dir.resolve(other.toString()).resolve(OwnerRecord.FILE_NAME), OwnerRecord(5, at(0)))
        val fresh = MachineSaveStore(dir, MachineIdIndex(), prefetchOwners = true)
        // 壁時計の期限ではなく、先読みのスレッドの終わりそのものを待つ（#878）
        fresh.awaitOwnerPrefetch()
        assertEquals(true, fresh.ownerPrefetchDone, "先読みのスレッドが結果を渡す")
        assertEquals(ClaimResult.KEPT, fresh.claim(mine.toString(), 0, 0, at(1)).result)
        assertEquals(ClaimResult.NEW_ID_STALE, fresh.claim(other.toString(), 0, 0, at(1)).result)
        assertEquals(2, fresh.prefetchHits)
        assertEquals(0, fresh.mainOwnerReads)
    }

    @Test
    fun `claimが受け入れてまだ書いていないIDと振ったIDは、次のsubmitがowner_binも書く`() {
        val crashed = MachineSaveStore(dir, MachineIdIndex(), "test", ManualSaveExecutor())
        val kept = UUID.randomUUID()
        assertEquals(false, crashed.hasUnwrittenOwner(kept))
        assertEquals(ClaimResult.KEPT, crashed.claim(kept.toString(), 0, 0, at(1)).result)
        assertEquals(true, crashed.hasUnwrittenOwner(kept), "owner.bin のなかった ID（KEPT）")
        val assigned = crashed.assignNewId(at(2))!!
        assertEquals(true, crashed.hasUnwrittenOwner(assigned.id), "claim が振った ID")
        val taken = UUID.randomUUID()
        OwnerRecord.write(dir.resolve(taken.toString()).resolve(OwnerRecord.FILE_NAME), OwnerRecord(0, at(0)))
        val reading = MachineSaveStore(dir, MachineIdIndex(), "test", ManualSaveExecutor())
        assertEquals(ClaimResult.TAKEN_OVER, reading.claim(taken.toString(), 0, 0, at(3)).result)
        assertEquals(false, reading.hasUnwrittenOwner(taken), "引き取りの owner.bin は claim が列に入れた")
        // 書いたら外れる
        val direct = MachineSaveStore(dir, MachineIdIndex())
        val identity = direct.assignNewId(at(4))!!
        assertEquals(true, direct.hasUnwrittenOwner(identity.id))
        direct.submit(identity, at(4), snapshot(), null)
        assertEquals(false, direct.hasUnwrittenOwner(identity.id), "submit が owner.bin を書く仕事に回した")
    }
}
