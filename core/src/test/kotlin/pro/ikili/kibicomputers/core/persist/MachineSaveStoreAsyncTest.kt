package pro.ikili.kibicomputers.core.persist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.OptionalLong
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** 仕事を溜め、テストが手で1つずつ進める [SaveExecutor]（入れた順に走る） */
internal class ManualSaveExecutor : SaveExecutor {
    private val queue = ArrayDeque<Runnable>()
    var closed = false

    val size: Int get() = queue.size

    override fun execute(id: UUID, task: Runnable): Boolean {
        if (closed) return false
        queue.addLast(task)
        return true
    }

    /** 1つ進める。なければ false */
    fun runNext(): Boolean {
        val task = queue.removeFirstOrNull() ?: return false
        task.run()
        return true
    }

    fun runAll() {
        while (runNext()) Unit
    }

    override fun flush(timeoutMillis: Long): Boolean {
        runAll()
        return true
    }

    override fun close(timeoutMillis: Long): Boolean {
        closed = true
        runAll()
        return true
    }
}

/** 書き込みのスレッドに移した置き場（#84、ADR 0084）: 予約と完了・世代の数え方・削除・claim の待ち・失敗・メモリの上限・掃除 */
class MachineSaveStoreAsyncTest {
    @TempDir
    lateinit var dir: Path

    private val p = MachineOwner("minecraft:overworld", 0, 64, 0)
    private val q = MachineOwner("minecraft:overworld", 16, 64, 0)
    private val index = MachineIdIndex()
    private val manual = ManualSaveExecutor()
    private val store by lazy { MachineSaveStore(dir, index, "test", manual) }
    private val limit = Duration.ofSeconds(30)

    /** 見積もり [bytes] の全体の写し */
    private fun src(bytes: Long = 0, base: DeltaBase? = null, onCommit: (UUID, Long) -> Unit = { _, _ -> }): MachineSnapshotSource =
        object : MachineSnapshotSource {
            override val deltaBase: DeltaBase? get() = base
            override val estimatedBytes: Long get() = bytes

            override fun build(id: UUID, generation: Long, modVersion: String): MachineSnapshot =
                MachineSnapshot(id, generation, modVersion, listOf(SnapshotSection(SnapshotSection.KERNEL, 1, ByteArray(16))), deltaBase = base)

            override fun committed(id: UUID, generation: Long) = onCommit(id, generation)
        }

    private fun exists(id: UUID, generation: Long) = Files.exists(store.generationFile(id, generation))

    @Test
    fun `予約しただけではファイルも最新も変わらず、走らせて受け取ると進む`() {
        val identity = store.assignNewId(p)!!
        val pending = store.submit(identity, p, src(), "tag")
        assertEquals(MachineIdentity(identity.id, 1, 0), pending.identity)
        assertEquals("tag", pending.tag)
        assertEquals(OptionalLong.of(1), store.reservedGeneration(identity.id))
        assertEquals(OptionalLong.empty(), store.latestGeneration(identity.id))
        assertFalse(exists(identity.id, 1))
        assertEquals(1, store.pendingCount)
        assertTrue(store.pollCompleted().isEmpty())

        manual.runAll()
        assertTrue(exists(identity.id, 1), "書き込みのスレッドで書く")
        assertEquals(OptionalLong.empty(), store.latestGeneration(identity.id), "受け取るまで最新は進まない")
        val completions = store.pollCompleted()
        assertEquals(1, completions.size)
        assertSame(pending, completions[0].pending)
        assertTrue(completions[0].succeeded)
        assertEquals(pending.identity, completions[0].result!!.identity)
        assertEquals(OptionalLong.of(1), store.latestGeneration(identity.id))
        assertEquals(0, store.pendingCount)
    }

    @Test
    fun `IDのない台はsubmitで新しいIDを振り、owner_binを世代の前に同じ仕事で書く`() {
        val pending = store.submit(null, p, src(), null)
        val id = pending.identity.id
        assertEquals(MachineIdentity(id, 1, 0), pending.identity)
        assertFalse(pending.reassigned)
        assertEquals(2, pending.writes)
        assertEquals(p, index.ownerOf(id), "予約の時点で索引に入る")
        assertFalse(Files.exists(store.directoryOf(id)), "owner.bin も書き込みのスレッドで書く")
        manual.runAll()
        assertEquals(OwnerRecord(0, p), OwnerRecord.read(store.directoryOf(id).resolve(OwnerRecord.FILE_NAME)))
        assertTrue(exists(id, 1))
        assertTrue(store.pollCompleted().single().succeeded)
    }

    @Test
    fun `完了の前に続けて予約すると別々の世代になり、予約で次の世代が決まる`() {
        val identity = store.assignNewId(p)!!
        val first = store.submit(identity, p, src(), null)
        // ケースは書き込み中の予約の ID（first.identity）を今の ID にして、もう一度入れる（規則 7c）
        val second = store.submit(first.identity, p, src(), null)
        assertEquals(1, first.identity.generation)
        assertEquals(2, second.identity.generation)
        // NBT が古い世代のままでも、予約から次を決める（二重に予約しない）
        val third = store.submit(identity, p, src(), null)
        assertEquals(3, third.identity.generation)
        manual.runAll()
        assertEquals(listOf(1L, 2L, 3L), store.pollCompleted().map { it.result!!.identity.generation })
        assertEquals(OptionalLong.of(3), store.latestGeneration(identity.id))
    }

    @Test
    fun `持ち主が合わないsubmitは新しいIDの世代1、続く予約は新しいIDの世代2`() {
        var identity = store.assignNewId(p)!!
        repeat(5) { identity = store.write(identity, p, src()).identity }
        assertEquals(5, identity.generation)
        val moved = store.submit(identity.copy(epoch = 7), p, src(), null)
        assertTrue(moved.reassigned)
        assertNotEquals(identity.id, moved.identity.id)
        assertEquals(MachineIdentity(moved.identity.id, 1, 0), moved.identity)
        assertNull(index.ownerOf(identity.id), "元の ID の索引の項は外れる")
        assertEquals(p, index.ownerOf(moved.identity.id))
        val next = store.submit(moved.identity, p, src(), null)
        assertFalse(next.reassigned)
        assertEquals(1, next.writes)
        assertEquals(MachineIdentity(moved.identity.id, 2, 0), next.identity)
        manual.runAll()
        val results = store.pollCompleted().map { it.result!! }
        assertEquals(listOf(moved.identity, next.identity), results.map { it.identity })
        assertTrue(results[0].reassigned)
    }

    @Test
    fun `世代のファイルが書けなければIDは変えず、次は同じIDの欠番の次に書く`() {
        val identity = store.assignNewId(p)!!
        // 世代 1 の名前のディレクトリを置いて塞ぐ
        Files.createDirectories(store.generationFile(identity.id, 1).resolve("x"))
        val pending = store.submit(identity, p, src(), null)
        manual.runAll()
        val failed = store.pollCompleted().single()
        assertFalse(failed.succeeded)
        assertNotNull(failed.error)
        assertEquals(p, store.ownerOf(identity.id))
        assertEquals(OptionalLong.empty(), store.latestGeneration(identity.id))
        assertEquals(OptionalLong.of(1), store.reservedGeneration(identity.id), "予約は戻さない（欠番）")
        val again = store.submit(identity, p, src(), null)
        assertEquals(MachineIdentity(identity.id, 2, 0), again.identity)
        assertFalse(again.reassigned)
        manual.runAll()
        assertTrue(store.pollCompleted().single().succeeded)
        assertEquals(1, pending.identity.generation)
    }

    @Test
    fun `新しく振ったIDのowner_binが書けなければ、後続も失敗で返り、IDは外れて次は振り直す`() {
        // 根をファイルにして、どのディレクトリも作れなくする
        val root = dir.resolve("root")
        Files.write(root, byteArrayOf(1))
        val s = MachineSaveStore(root, index, "test", manual)
        val first = s.submit(null, p, src(), null)
        val second = s.submit(first.identity, p, src(), null)
        assertEquals(first.identity.id, second.identity.id, "書き込み中の ID の次の世代を予約する")
        manual.runAll()
        val completions = s.pollCompleted()
        assertEquals(listOf(first, second), completions.map { it.pending })
        assertTrue(completions.none { it.succeeded }, "どれも失敗で返る")
        assertNull(index.ownerOf(first.identity.id), "使えない ID は索引から外れる")
        assertNull(s.ownerOf(first.identity.id))
        assertEquals(OptionalLong.empty(), s.reservedGeneration(first.identity.id))
        val third = s.submit(first.identity, p, src(), null)
        assertNotEquals(first.identity.id, third.identity.id, "次は振り直す")
        manual.runAll()
        assertFalse(s.pollCompleted().single().succeeded)
    }

    @Test
    fun `owner_binの失敗を受け取る前に入れた同じIDの後続は、書かずに失敗で返る`() {
        // 根をファイルにして、1つ目の owner.bin を書けなくする
        val root = dir.resolve("root")
        Files.write(root, byteArrayOf(1))
        val s = MachineSaveStore(root, index, "test", manual)
        val first = s.submit(null, p, src(), null)
        val id = first.identity.id
        manual.runAll()
        assertEquals(0, s.pendingCount, "1つ目は走り終わっている")
        assertTrue(s.isUnusable(id), "受け取る前は、仕事が尽きても使えない印を残す")
        // 根を書けるようにしてから、受け取る前に同じ ID の次を入れる（自動保存の後、同じ tick のアンロードなど）
        Files.delete(root)
        Files.createDirectories(root)
        val second = s.submit(first.identity, p, src(), null)
        assertEquals(id, second.identity.id, "受け取る前は同じ ID の次の世代を予約する")
        manual.runAll()
        assertFalse(exists(second, s), "owner.bin のないディレクトリに世代のファイルを書かない")
        assertFalse(Files.exists(s.directoryOf(id).resolve(OwnerRecord.FILE_NAME)))
        val completions = s.pollCompleted()
        assertEquals(listOf(first, second), completions.map { it.pending })
        assertTrue(completions.none { it.succeeded }, "どちらも失敗で返る")
        assertNull(index.ownerOf(id))
        assertNull(s.ownerOf(id))
        assertFalse(s.isUnusable(id), "受け取って仕事がなければ印を外す")
        val third = s.submit(first.identity, p, src(), null)
        assertNotEquals(id, third.identity.id, "次は振り直す")
        manual.runAll()
        assertTrue(s.pollCompleted().single().succeeded)
        assertTrue(exists(third, s))
    }

    @Test
    fun `owner_binの失敗を受け取った時点で後続が残っていれば、印はその後続が終わるまで残す`() {
        val root = dir.resolve("root")
        Files.write(root, byteArrayOf(1))
        val s = MachineSaveStore(root, index, "test", manual)
        val first = s.submit(null, p, src(), null)
        val id = first.identity.id
        val second = s.submit(first.identity, p, src(), null)
        manual.runNext()
        Files.delete(root)
        Files.createDirectories(root)
        // 1つ目の失敗を受け取る。2つ目はまだ列にある
        assertEquals(listOf(first), s.pollCompleted().map { it.pending })
        assertTrue(s.isUnusable(id), "後続が残っているうちは印を外さない")
        manual.runAll()
        assertFalse(exists(second, s), "受け取った後に走る後続も書かない")
        assertFalse(s.pollCompleted().single().succeeded)
        assertFalse(s.isUnusable(id), "最後の仕事が終われば印を外す")
    }

    @Test
    fun `owner_binの失敗を受け取る前に、deleteの後のclaimが同じIDを受け入れれば、次の世代の前にowner_binを書けて印が外れる`() {
        val root = dir.resolve("root")
        Files.write(root, byteArrayOf(1))
        val s = MachineSaveStore(root, index, "test", manual)
        val first = s.submit(null, p, src(), null)
        val id = first.identity.id
        manual.runAll()
        assertTrue(s.isUnusable(id), "失敗をまだ受け取っていないので印が残る")
        Files.delete(root)
        Files.createDirectories(root)
        // 受け取る前に壊して消し、コピーした NBT を置き直す（消した ID なので読まずに KEPT で同じ ID。owner.bin は次の世代の前に書く。#585）
        s.delete(id)
        index.removeIfOwner(id, p)
        manual.runAll()
        val claim = s.claim(id.toString(), 1, 0, q)
        assertEquals(ClaimResult.KEPT, claim.result)
        assertEquals(id, claim.identity!!.id)
        assertEquals(0, s.mainOwnerReads, "消した ID の owner.bin は読まない")
        assertNull(OwnerRecord.read(s.directoryOf(id).resolve(OwnerRecord.FILE_NAME)), "claim では書かない")
        assertTrue(s.isUnusable(id), "まだ書いていないので印は残る")
        // 1つ目の失敗を受け取る前に、次を書かせる（受け取ると、仕事がなければそこでも印が外れるため）
        val second = s.submit(claim.identity!!, q, src(), null)
        assertEquals(id, second.identity.id)
        assertEquals(2, second.writes, "owner.bin と世代")
        manual.runAll()
        assertEquals(OwnerRecord(0, q), OwnerRecord.read(s.directoryOf(id).resolve(OwnerRecord.FILE_NAME)))
        assertFalse(s.isUnusable(id), "owner.bin が書けたので印を外す")
        assertTrue(exists(second, s), "印が外れたので世代のファイルを書く")
        val completions = s.pollCompleted()
        assertEquals(listOf(first, second), completions.map { it.pending })
        assertFalse(completions[0].succeeded)
        assertTrue(completions[1].succeeded, "後続は成功で返る")
        assertEquals(OptionalLong.of(second.identity.generation), s.latestGeneration(id))
        assertEquals(q, index.ownerOf(id), "古い失敗を受け取っても、claim し直した登録は外さない")
    }

    @Test
    fun `owner_binの失敗を受け取る前に、引き取りのclaimが同じIDのowner_binを列で書けば、印が外れて後続は書ける`() {
        val root = dir.resolve("root")
        Files.write(root, byteArrayOf(1))
        val s = MachineSaveStore(root, index, "test", manual)
        val first = s.submit(null, p, src(), null)
        val id = first.identity.id
        manual.runAll()
        assertTrue(s.isUnusable(id))
        Files.delete(root)
        Files.createDirectories(root)
        // 受け取る前にアンロードされ、NBT が別の場所に置かれる（覚えている owner.bin の epoch 以上なので引き取り、epoch 1 を列で書く。#585）
        index.removeIfOwner(id, p)
        val claim = s.claim(id.toString(), 1, 0, q)
        assertEquals(ClaimResult.TAKEN_OVER, claim.result)
        assertEquals(id, claim.identity!!.id)
        assertNull(OwnerRecord.read(s.directoryOf(id).resolve(OwnerRecord.FILE_NAME)), "claim では書かない")
        val second = s.submit(claim.identity!!, q, src(), null)
        assertEquals(id, second.identity.id)
        assertFalse(second.reassigned)
        assertEquals(1, second.writes, "引き取りの owner.bin は claim が列に入れた")
        manual.runAll()
        assertEquals(OwnerRecord(1, q), OwnerRecord.read(s.directoryOf(id).resolve(OwnerRecord.FILE_NAME)))
        assertFalse(s.isUnusable(id), "owner.bin が書けたので印を外す")
        assertTrue(exists(second, s), "印が外れたので世代のファイルを書く")
        val completions = s.pollCompleted()
        assertEquals(listOf(first, second), completions.map { it.pending }, "owner.bin だけの仕事の完了は返さない")
        assertFalse(completions[0].succeeded)
        assertTrue(completions[1].succeeded, "後続は成功で返る")
        assertEquals(q, index.ownerOf(id), "古い失敗を受け取っても、引き取った登録は外さない")
        assertEquals(OptionalLong.of(second.identity.generation), s.latestGeneration(id))
    }

    @Test
    fun `owner_binを書く仕事がまだ走らないうちに引き取ると、前の仕事の失敗を後から受け取っても引き取った登録は外れない`() {
        // 開いたときに根がない（claim はディスクを読まない）。その後、根をファイルにして最初の owner.bin を書けなくする
        val root = dir.resolve("root")
        val s = MachineSaveStore(root, index, "test", manual)
        Files.write(root, byteArrayOf(1))
        val id = UUID.randomUUID()
        val a = s.claim(id.toString(), 0, 0, p)
        assertEquals(ClaimResult.KEPT, a.result)
        assertTrue(s.hasUnwrittenOwner(id))
        // A が最初の世代（owner.bin と世代。T1）を入れ、同じ tick にアンロードされて、B が別の位置で引き取る（owner.bin だけ。T2）
        val first = s.submit(a.identity!!, p, src(), null)
        assertEquals(2, first.writes)
        assertFalse(s.hasUnwrittenOwner(id), "submit が owner.bin を書く仕事に回した")
        index.removeIfOwner(id, p)
        val b = s.claim(id.toString(), 0, 0, q)
        assertEquals(ClaimResult.TAKEN_OVER, b.result)
        // T1 は書けず（印が付く）、その後 T2 は書ける（印を外す）
        assertTrue(manual.runNext())
        assertTrue(s.isUnusable(id))
        Files.delete(root)
        Files.createDirectories(root)
        manual.runAll()
        assertFalse(s.isUnusable(id), "T2 が書けたので印を外す")
        assertEquals(OwnerRecord(1, q), OwnerRecord.read(s.directoryOf(id).resolve(OwnerRecord.FILE_NAME)))
        // T1 の失敗を受け取っても、B の登録・持ち主の記録・索引は外さない
        val completions = s.pollCompleted()
        assertEquals(listOf(first), completions.map { it.pending })
        assertFalse(completions.single().succeeded)
        assertEquals(q, index.ownerOf(id))
        assertEquals(q, s.ownerOf(id))
        val second = s.submit(b.identity!!, q, src(), null)
        assertEquals(id, second.identity.id, "B は振り直されない")
        assertFalse(second.reassigned)
        assertEquals(1, second.writes)
        manual.runAll()
        assertTrue(s.pollCompleted().single().succeeded)
        assertTrue(exists(second, s))
    }

    @Test
    fun `owner_binを書く仕事がまだ走らないうちに同じ位置で読み込み直すと、前の仕事の失敗を後から受け取っても登録は外れず、owner_binは書き直す`() {
        val root = dir.resolve("root")
        val s = MachineSaveStore(root, index, "test", manual)
        Files.write(root, byteArrayOf(1))
        val id = UUID.randomUUID()
        val a = s.claim(id.toString(), 0, 0, p)
        val first = s.submit(a.identity!!, p, src(), null)
        index.removeIfOwner(id, p)
        // 同じ位置で読み込み直す（持ち主は自分。記録は覚えているので KEPT で、前の owner.bin の失敗に備えて書き直す仕事を入れる）
        val b = s.claim(id.toString(), 0, 0, p)
        assertEquals(ClaimResult.KEPT, b.result)
        assertTrue(manual.runNext())
        assertTrue(s.isUnusable(id))
        Files.delete(root)
        Files.createDirectories(root)
        manual.runAll()
        assertFalse(s.isUnusable(id))
        assertEquals(OwnerRecord(0, p), OwnerRecord.read(s.directoryOf(id).resolve(OwnerRecord.FILE_NAME)))
        assertEquals(listOf(first), s.pollCompleted().map { it.pending })
        assertEquals(p, index.ownerOf(id))
        assertEquals(p, s.ownerOf(id))
        val second = s.submit(b.identity!!, p, src(), null)
        assertEquals(id, second.identity.id)
        assertFalse(second.reassigned)
    }

    private fun exists(pending: PendingSave, s: MachineSaveStore) =
        Files.exists(s.generationFile(pending.identity.id, pending.identity.generation))

    @Test
    fun `同期のwriteはowner_binの失敗をIOExceptionで返す`() {
        val root = dir.resolve("root")
        Files.write(root, byteArrayOf(1))
        val s = MachineSaveStore(root, index, "test")
        val identity = MachineIdentity(UUID.randomUUID(), 0, 0)
        org.junit.jupiter.api.assertThrows<java.io.IOException> { s.write(identity, p, src()) }
    }

    @Test
    fun `閉じた後の予約は失敗の完了で返る`() {
        val identity = store.assignNewId(p)!!
        assertTrue(store.close(1000))
        val pending = store.submit(identity, p, src(10), null)
        val completion = store.pollCompleted().single()
        assertSame(pending, completion.pending)
        assertFalse(completion.succeeded)
        assertEquals(0, store.pendingCount)
        assertEquals(0, store.pendingBytes)
    }

    @Test
    fun `書き込み中にdeleteすると、書いた後に消え、表は進めない`() {
        val identity = store.assignNewId(p)!!
        store.submit(identity, p, src(), null)
        store.delete(identity.id)
        manual.runAll()
        val completion = store.pollCompleted().single()
        assertTrue(completion.succeeded, "走った後に消えた（失敗ではない）")
        assertFalse(Files.exists(store.directoryOf(identity.id)))
        assertEquals(OptionalLong.empty(), store.latestGeneration(identity.id))
        assertNull(store.ownerOf(identity.id))
    }

    @Test
    fun `deleteの後に同じIDでclaimし直すと、その書き込みは削除の後に走り成功する`() {
        val identity = store.assignNewId(p)!!
        store.submit(identity, p, src(), null)
        store.delete(identity.id)
        index.removeIfOwner(identity.id, p)
        manual.runAll()
        // コピーした NBT を、元を壊した後に置く（owner.bin がないので KEPT で同じ ID）
        val claim = store.claim(identity.id.toString(), 1, 0, q)
        assertEquals(ClaimResult.KEPT, claim.result)
        assertEquals(identity.id, claim.identity!!.id)
        store.pollCompleted()
        val pending = store.submit(claim.identity!!, q, src(), null)
        assertEquals(identity.id, pending.identity.id)
        manual.runAll()
        val completion = store.pollCompleted().single()
        assertTrue(completion.succeeded)
        assertTrue(exists(identity.id, 2))
        assertEquals(OptionalLong.of(2), store.latestGeneration(identity.id))
    }

    @Test
    fun `deleteの後、登録し直さずにsubmitすると振り直す`() {
        val identity = store.assignNewId(p)!!
        store.delete(identity.id)
        val pending = store.submit(identity, p, src(), null)
        assertTrue(pending.reassigned)
        assertNotEquals(identity.id, pending.identity.id)
    }

    @Test
    fun `claimは列が詰まっていても待たずに返り、引き取りのowner_binは前の世代の後に列で書く`() = timeout(limit) {
        val gate = CountDownLatch(1)
        val writer = SaveWriter("gated")
        val s = MachineSaveStore(dir, index, "test", writer)
        try {
            val identity = s.assignNewId(p)!!
            writer.execute(identity.id) { gate.await() }
            s.submit(identity, p, src(), null)
            index.removeIfOwner(identity.id, p)
            val claim = s.claim(identity.id.toString(), 1, 0, q)
            assertEquals(ClaimResult.TAKEN_OVER, claim.result)
            assertEquals(1, claim.identity!!.epoch)
            assertEquals(0, s.claimWaitNanos, "待たない")
            assertEquals(0, s.mainOwnerReads, "覚えている owner.bin は読まない")
            assertNull(OwnerRecord.read(s.directoryOf(identity.id).resolve(OwnerRecord.FILE_NAME)), "列が止まっている間は何も書かない")
            assertFalse(s.awaitRestorable(identity.id, 0), "世代の書き込みが残っている間は、復元を待たせる")
            gate.countDown()
            assertTrue(s.flush(10_000))
            assertTrue(Files.exists(s.generationFile(identity.id, 1)), "前の世代の書き込みも済む")
            assertEquals(OwnerRecord(1, q), OwnerRecord.read(s.directoryOf(identity.id).resolve(OwnerRecord.FILE_NAME)), "引き取りの owner.bin が後に書かれる")
            assertEquals(1, s.pollCompleted().size, "owner.bin だけの仕事の完了は返さない")
        } finally {
            gate.countDown()
            writer.close(10_000)
        }
    }

    @Test
    fun `覚えていないIDに仕事が残っていれば、読む前に待ち、上限を過ぎたら新しいID、仕事がなければ待たない`() {
        // owner.bin が書けずに外した ID（規則 7b）は覚えていないが、後続の仕事が列に残りうる
        val root = dir.resolve("root")
        Files.write(root, byteArrayOf(1))
        val s = MachineSaveStore(root, index, "test", manual, claimWaitMillis = 50)
        val first = s.submit(null, p, src(), null)
        val id = first.identity.id
        s.submit(first.identity, p, src(), null)
        manual.runNext()
        assertFalse(s.pollCompleted().single().succeeded)
        assertNull(s.ownerOf(id), "外した")
        val claim = s.claim(id.toString(), 1, 0, q)
        assertEquals(ClaimResult.NEW_ID_UNREADABLE, claim.result)
        assertNotEquals(id, claim.identity!!.id)
        assertTrue(s.claimWaitNanos >= TimeUnit.MILLISECONDS.toNanos(40), "待った時間を数える")
        manual.runAll()
        s.pollCompleted()
        val before = s.claimWaitNanos
        assertEquals(ClaimResult.KEPT, s.claim(id.toString(), 1, 0, q).result)
        assertEquals(before, s.claimWaitNanos, "仕事がなければ待たない")
        assertEquals(1, s.mainOwnerReads, "このときだけ読む")
    }

    @Test
    fun `復元の待ちは、引き取りのowner_binだけが残っていれば待たない`() {
        val identity = store.assignNewId(p)!!
        store.submit(identity, p, src(), null)
        manual.runAll()
        store.pollCompleted()
        index.removeIfOwner(identity.id, p)
        assertEquals(ClaimResult.TAKEN_OVER, store.claim(identity.id.toString(), 1, 0, q).result)
        assertEquals(1, manual.size, "owner.bin の仕事")
        assertFalse(store.awaitWrites(identity.id, 0))
        assertTrue(store.awaitRestorable(identity.id, 0), "復元は世代のファイルだけを読む")
        manual.runAll()
        assertTrue(store.awaitWrites(identity.id, 0))
    }

    @Test
    fun `引き取りのowner_binが書けなければ、受け取ったときにIDを外し、次のsubmitが振り直す`() {
        val root = dir.resolve("root")
        Files.write(root, byteArrayOf(1))
        val s = MachineSaveStore(root, index, "test", manual)
        val identity = s.assignNewId(p)!!
        index.removeIfOwner(identity.id, p)
        val claim = s.claim(identity.id.toString(), 0, 0, q)
        assertEquals(ClaimResult.TAKEN_OVER, claim.result)
        assertEquals(q, index.ownerOf(identity.id))
        manual.runAll()
        assertTrue(s.isUnusable(identity.id))
        assertTrue(s.pollCompleted().isEmpty(), "owner.bin だけの仕事の失敗は外に返さない")
        assertNull(s.ownerOf(identity.id), "受け取ったので外す")
        assertNull(index.ownerOf(identity.id))
        assertFalse(s.isUnusable(identity.id), "仕事が残っていないので印も外す")
        val pending = s.submit(claim.identity!!, q, src(), null)
        assertTrue(pending.reassigned, "外した ID には書かない")
        assertNotEquals(identity.id, pending.identity.id)
    }

    @Test
    fun `引き取りのowner_binが書けなくても、受け取る前に入れた後続の世代は書かない`() {
        val root = dir.resolve("root")
        Files.write(root, byteArrayOf(1))
        val s = MachineSaveStore(root, index, "test", manual)
        val identity = s.assignNewId(p)!!
        index.removeIfOwner(identity.id, p)
        val claim = s.claim(identity.id.toString(), 0, 0, q)
        manual.runNext()
        Files.delete(root)
        Files.createDirectories(root)
        // 失敗を受け取る前に、引き取った台を保存する
        val pending = s.submit(claim.identity!!, q, src(), null)
        assertFalse(pending.reassigned)
        manual.runAll()
        assertFalse(exists(pending, s), "前の owner.bin（持ち主 p）のままの ID に、q の世代を書かない")
        assertFalse(s.pollCompleted().single().succeeded)
        assertNull(s.ownerOf(identity.id))
    }

    @Test
    fun `メモリの上限は書き込みに回す前の合計で判定し、列が空なら大きな1件も通る`() {
        val s = MachineSaveStore(dir, index, "test", manual, maxPendingBytes = 100)
        val identity = s.assignNewId(p)!!
        assertTrue(s.hasCapacity)
        // 台ごとに取り出しの直前に判定して入れる: 待ち中の合計は「上限 + 1 台」を超えない
        var current: MachineIdentity = identity
        var submitted = 0
        repeat(5) {
            if (!s.hasCapacity) return@repeat
            current = s.submit(current, p, src(40), null).identity
            submitted++
        }
        assertEquals(3, submitted)
        assertEquals(120, s.pendingBytes)
        assertTrue(s.pendingBytes < 100 + 40)
        assertFalse(s.hasCapacity)
        manual.runNext()
        assertEquals(80, s.pendingBytes, "走った時点で減る（pollCompleted は要らない）")
        assertTrue(s.hasCapacity)
        manual.runAll()
        assertEquals(0, s.pendingBytes)
        // 1件で上限を超える台も、列が空なら通る
        assertTrue(s.hasCapacity)
        s.submit(current, p, src(500), null)
        assertFalse(s.hasCapacity)
        manual.runAll()
        assertTrue(s.hasCapacity)
    }

    @Test
    fun `awaitCapacityは書き込みのスレッドが進むと戻り、上限でfalse`() = timeout(limit) {
        val gate = CountDownLatch(1)
        val writer = SaveWriter("gated")
        val s = MachineSaveStore(dir, index, "test", writer, maxPendingBytes = 100)
        try {
            val identity = s.assignNewId(p)!!
            writer.execute(identity.id) { gate.await() }
            s.submit(identity, p, src(150), null)
            assertFalse(s.hasCapacity)
            assertFalse(s.awaitCapacity(50))
            Thread {
                Thread.sleep(100)
                gate.countDown()
            }.start()
            assertTrue(s.awaitCapacity(10_000))
            assertTrue(s.awaitWrites(identity.id, 10_000))
            assertEquals(0, s.pendingBytes)
            assertTrue(s.pollCompleted().single().succeeded)
        } finally {
            gate.countDown()
            writer.close(10_000)
        }
    }

    @Test
    fun `全体の書き込みの完了を受け取るまでは前の基準、失敗なら基準もcommittedも変わらない`() {
        val identity = store.assignNewId(p)!!
        val commits = ArrayList<Long>()
        store.submit(identity, p, src(onCommit = { _, g -> commits += g }), null)
        assertNull(store.deltaBaseFor(identity, p), "まだ基準がない")
        manual.runAll()
        assertNull(store.deltaBaseFor(identity, p), "受け取るまで基準にしない")
        assertTrue(commits.isEmpty())
        val g1 = store.pollCompleted().single().result!!.identity
        val base1 = store.deltaBaseFor(g1, p)!!
        assertEquals(1, base1.generation)
        assertEquals(listOf(1L), commits)
        // 世代 2 の全体の書き込みが失敗すると、基準は 1 のまま
        Files.createDirectories(store.generationFile(identity.id, 2).resolve("x"))
        store.submit(g1, p, src(onCommit = { _, g -> commits += g }), null)
        manual.runAll()
        assertFalse(store.pollCompleted().single().succeeded)
        assertEquals(base1, store.deltaBaseFor(g1, p))
        assertEquals(listOf(1L), commits)
        // 差分は今の基準（1）を指して書ける
        val delta = store.submit(g1, p, src(base = base1), null)
        assertEquals(3, delta.identity.generation)
        manual.runAll()
        assertTrue(store.pollCompleted().single().result!!.delta)
    }

    @Test
    fun `書き込み中に印が進んでも、書き込み待ちの世代は掃除で消さない`() {
        var identity = store.assignNewId(p)!!
        identity = store.write(identity, p, src()).identity // g1
        store.markChunkGeneration(identity.id, 1, p)
        val g2 = store.submit(identity, p, src(), null)
        val g3 = store.submit(g2.identity, p, src(), null)
        // アンロード（規則 4）で g3 を NBT に載せて保存した
        store.markChunkGeneration(identity.id, 3, p)
        manual.runAll()
        assertTrue(exists(identity.id, 2), "書き込み待ちの g2 は残す（規則 11）")
        assertTrue(exists(identity.id, 3))
        assertTrue(exists(identity.id, 1), "前の印")
        assertEquals(2, store.pollCompleted().size)
    }

    @Test
    fun `NBTの世代がないときは直下の世代に戻し、再起動の後の掃除でも残す`() {
        val s1 = MachineSaveStore(dir, index, "test")
        var identity = s1.assignNewId(p)!!
        repeat(4) { identity = s1.write(identity, p, src()).identity }
        assertEquals(listOf(1L, 2L, 3L, 4L), s1.generationsOnDisk(identity.id))
        assertEquals(OptionalLong.of(4), s1.restorableGeneration(identity.id, 4))
        // NBT は書けなかった予約の世代 6 を指している（規則 4。5 も欠番）
        assertEquals(OptionalLong.of(4), s1.restorableGeneration(identity.id, 6))
        assertEquals(OptionalLong.empty(), s1.restorableGeneration(UUID.randomUUID(), 3), "何もなければ冷えた起動")

        // 再起動の後: 新しい置き場で NBT（世代 6）から claim し、印が進む
        val index2 = MachineIdIndex()
        val s2 = MachineSaveStore(dir, index2, "test")
        val claim = s2.claim(identity.id.toString(), 6, 0, p)
        assertEquals(ClaimResult.KEPT, claim.result)
        assertEquals(ChunkMarkResult.ADVANCED, s2.markChunkGeneration(identity.id, 7, p))
        assertEquals(listOf(4L), s2.generationsOnDisk(identity.id), "戻す世代 4 は残し、ほかは消す")
        assertEquals(OptionalLong.of(4), s2.restorableGeneration(identity.id, 6))
    }

    @Test
    fun `初めて書くディレクトリの残ったtmpを片付け、世代のファイルは消さない`() {
        val s1 = MachineSaveStore(dir, index, "test")
        var identity = s1.assignNewId(p)!!
        identity = s1.write(identity, p, src()).identity
        val d = s1.directoryOf(identity.id)
        Files.write(d.resolve("2.bin.tmp"), byteArrayOf(1))
        Files.write(d.resolve("owner.bin.tmp"), byteArrayOf(1))
        // 再起動の後の置き場で、最初の書き込みの前に片付く
        val s2 = MachineSaveStore(dir, MachineIdIndex(), "test")
        val claim = s2.claim(identity.id.toString(), 1, 0, p)
        s2.write(claim.identity!!, p, src())
        assertFalse(Files.exists(d.resolve("2.bin.tmp")))
        assertFalse(Files.exists(d.resolve("owner.bin.tmp")))
        assertTrue(Files.exists(s2.generationFile(identity.id, 1)))
        assertTrue(Files.exists(s2.generationFile(identity.id, 2)))
        assertTrue(Files.exists(d.resolve(OwnerRecord.FILE_NAME)))
    }
}
