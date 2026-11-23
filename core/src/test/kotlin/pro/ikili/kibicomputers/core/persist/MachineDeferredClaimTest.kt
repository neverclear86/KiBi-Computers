package pro.ikili.kibicomputers.core.persist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 読みの要る claim の待ち（`MachineSaveStore.claimOrDefer`・`pollDeferredClaims`。#869、ADR 0084 の規則 7a）のテスト。
 * メインスレッドで `owner.bin` を読まず、先読みの途中でも読み終えた ID はその場で決まり、未読の ID は待ちにして後で確定する。
 * 実スレッドの終わりは壁時計の期限で合否を決めずに待つ（#878）。クラスの [Timeout] は、止まったときの保険で、合否の基準ではない
 */
@Timeout(10, unit = TimeUnit.MINUTES)
class MachineDeferredClaimTest {
    @TempDir
    lateinit var dir: Path

    private fun at(x: Int) = MachineOwner("minecraft:overworld", x, 64, 0)

    private fun writeOwner(id: UUID, record: OwnerRecord) =
        OwnerRecord.write(dir.resolve(id.toString()).resolve(OwnerRecord.FILE_NAME), record)

    private fun readOwner(id: UUID) = OwnerRecord.read(dir.resolve(id.toString()).resolve(OwnerRecord.FILE_NAME))

    /** 待ちの鍵（ブロックエンティティの代わり） */
    private class Key(val name: String) {
        override fun toString() = name
    }

    @Test
    fun `先読みの途中でも、読み終えたIDはその場で決まり、未読のIDは待ちにして先に読んでもらう`() {
        val ids = List(6) { UUID.randomUUID() }
        // 持ち主: 0〜3 は at(0)、4・5 は at(1)
        for ((i, id) in ids.withIndex()) writeOwner(id, OwnerRecord(if (i == 3) 5 else 0, at(if (i >= 4) 1 else 0)))
        val store = MachineSaveStore(dir, MachineIdIndex())
        val opened = Collections.synchronizedList(ArrayList<UUID>())
        val stuck = CountDownLatch(1)
        val release = CountDownLatch(1)
        // 2 件目を開いたところで止める（1 件目の結果はもう入っている）
        store.prefetchOwnerReader = { path ->
            opened += UUID.fromString(path.parent.fileName.toString())
            if (opened.size == 2) {
                stuck.countDown()
                release.await()
            }
            OwnerRecord.read(path)
        }
        var thread: Thread? = null
        store.startOwnerPrefetch { thread = Thread(it).apply { start() } }
        val reading: UUID
        val urgent: UUID
        val keys = HashMap<UUID, Key>()
        try {
            stuck.await()
            val read = opened[0]
            reading = opened[1]
            // 読み終えた ID は待たずに決まる
            val readOwner = if (ids.indexOf(read) >= 4) at(1) else at(0)
            val now = store.claimOrDefer(read.toString(), 0, 0, readOwner, Key("read"))
            assertEquals(ClaimResult.KEPT, now?.result)
            assertEquals(1, store.prefetchHits)
            // まだ開いていない ID のうち、先読みの順で最後のもの（順番より先に読まれるはず）
            val notOpened = ids.filter { it != read && it != reading }
            urgent = HashSet(ids).toList().last { it in notOpened }
            for (id in listOf(reading, urgent)) {
                val key = Key(id.toString())
                keys[id] = key
                assertNull(store.claimOrDefer(id.toString(), 0, 0, at(1), key), "未読の ID は待ち")
                assertTrue(store.isClaimDeferred(key))
            }
            assertEquals(2, store.waitingClaims)
            assertEquals(0, store.mainOwnerReads, "メインスレッドで読まない")
            assertEquals(0, store.writerOwnerReadRequests, "先読みが読むので、書き込みのスレッドには頼まない")
            assertTrue(store.pollDeferredClaims().isEmpty(), "読み終わるまでは決まらない")
            assertNull(store.index.ownerOf(urgent), "待つ間は索引に入れない")
        } finally {
            // 途中で落ちても、止めた先読みのスレッドを放し、終わるのを待つ（@TempDir の掃除と重ならないように）
            release.countDown()
            thread?.join()
        }
        assertEquals(urgent, opened[2], "待っている ID を順番より先に読む")
        // 先読みのスレッドが終わったので、待ちの ID の結果はすべて入っている（1 回の poll で決まる）
        val done = store.pollDeferredClaims()
        assertEquals(setOf(keys[reading], keys[urgent]), done.map { it.key }.toSet())
        for (d in done) {
            val id = UUID.fromString((d.key as Key).name)
            val i = ids.indexOf(id)
            val expected = when {
                i == 3 -> ClaimResult.NEW_ID_STALE
                i >= 4 -> ClaimResult.KEPT
                else -> ClaimResult.TAKEN_OVER
            }
            assertEquals(expected, d.claim.result, "$id (#$i)")
            assertEquals(at(1), store.index.ownerOf(d.claim.identity!!.id))
        }
        assertEquals(0, store.waitingClaims)
        assertEquals(0, store.mainOwnerReads)
    }

    @Test
    fun `同じIDの写しは来た順に確定し、先の台がKEPT、後の台が重複になる`() {
        val id = UUID.randomUUID()
        writeOwner(id, OwnerRecord(0, at(0)))
        val manual = ManualSaveExecutor()
        val store = MachineSaveStore(dir, MachineIdIndex(), "test", manual)
        val first = Key("first")
        val second = Key("second")
        assertNull(store.claimOrDefer(id.toString(), 0, 0, at(0), first))
        assertNull(store.claimOrDefer(id.toString(), 0, 0, at(1), second))
        assertEquals(1, store.writerOwnerReadRequests, "読みは ID ごとに 1 回")
        assertTrue(store.pollDeferredClaims().isEmpty())
        manual.runAll()
        val done = store.pollDeferredClaims()
        assertEquals(listOf(first, second), done.map { it.key })
        assertEquals(ClaimResult.KEPT, done[0].claim.result)
        assertEquals(id, done[0].claim.identity!!.id)
        assertEquals(ClaimResult.NEW_ID_DUPLICATE_LOADED, done[1].claim.result)
        assertNotEquals(id, done[1].claim.identity!!.id)
        assertEquals(0, store.mainOwnerReads)
    }

    @Test
    fun `取り消した待ちは確定せず、索引にも残らない`() {
        val id = UUID.randomUUID()
        writeOwner(id, OwnerRecord(0, at(0)))
        val manual = ManualSaveExecutor()
        val store = MachineSaveStore(dir, MachineIdIndex(), "test", manual)
        val gone = Key("gone")
        val next = Key("next")
        assertNull(store.claimOrDefer(id.toString(), 0, 0, at(0), gone))
        assertNull(store.claimOrDefer(id.toString(), 0, 0, at(1), next))
        store.cancelDeferredClaim(gone)
        assertFalse(store.isClaimDeferred(gone))
        manual.runAll()
        val done = store.pollDeferredClaims()
        assertEquals(listOf(next), done.map { it.key }, "取り消した台は返らず、後ろの台が先に進む")
        assertEquals(ClaimResult.TAKEN_OVER, done.single().claim.result)
        assertEquals(at(1), store.index.ownerOf(id))
        // 全部取り消すと、読みの結果も捨てる
        val other = UUID.randomUUID()
        writeOwner(other, OwnerRecord(0, at(0)))
        val reopened = MachineSaveStore(dir, MachineIdIndex(), "test", manual)
        val key = Key("only")
        assertNull(reopened.claimOrDefer(other.toString(), 0, 0, at(0), key))
        reopened.cancelDeferredClaim(key)
        manual.runAll()
        assertTrue(reopened.pollDeferredClaims().isEmpty())
        assertNull(reopened.index.ownerOf(other))
        assertEquals(0, reopened.waitingClaims)
    }

    @Test
    fun `同じ鍵で出し直すと、前の待ちを取り消す`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        writeOwner(a, OwnerRecord(0, at(0)))
        writeOwner(b, OwnerRecord(0, at(0)))
        val manual = ManualSaveExecutor()
        val store = MachineSaveStore(dir, MachineIdIndex(), "test", manual)
        val key = Key("be")
        assertNull(store.claimOrDefer(a.toString(), 0, 0, at(0), key))
        assertNull(store.claimOrDefer(b.toString(), 0, 0, at(0), key))
        assertEquals(1, store.waitingClaims)
        manual.runAll()
        val done = store.pollDeferredClaims()
        assertEquals(b, done.single().claim.identity!!.id)
        assertNull(store.index.ownerOf(a))
    }

    @Test
    fun `先読みがないとき・触ったIDは、書き込みのスレッドがその仕事の後ろで読む`() {
        val id = UUID.randomUUID()
        Files.createDirectories(dir.resolve(id.toString()))
        Files.write(dir.resolve(id.toString()).resolve(OwnerRecord.FILE_NAME), byteArrayOf(1, 2, 3))
        val manual = ManualSaveExecutor()
        val store = MachineSaveStore(dir, MachineIdIndex(), "test", manual)
        store.startOwnerPrefetch { it.run() }
        // 先読みで読めなかった ID は、その場で新しい ID（触った ID になる）
        val first = store.claimOrDefer(id.toString(), 0, 0, at(0), Key("first"))
        assertEquals(ClaimResult.NEW_ID_UNREADABLE, first?.result)
        // 直した後の写し: 触った ID なので先読みの値は使えず、書き込みのスレッドで読む
        writeOwner(id, OwnerRecord(2, at(1)))
        val key = Key("second")
        assertNull(store.claimOrDefer(id.toString(), 0, 2, at(1), key))
        assertEquals(1, store.writerOwnerReadRequests)
        assertEquals(0, store.prefetchRequests)
        manual.runAll()
        val done = store.pollDeferredClaims()
        assertEquals(ClaimResult.KEPT, done.single().claim.result)
        assertEquals(MachineIdentity(id, 0, 2), done.single().claim.identity)
        assertEquals(0, store.mainOwnerReads)
    }

    @Test
    fun `引き取りのowner_binの書き込みが列に残っていても、覚えているIDは待たず、メインスレッドは仕事も待たない`() {
        val id = UUID.randomUUID()
        writeOwner(id, OwnerRecord(0, at(0)))
        val manual = ManualSaveExecutor()
        val store = MachineSaveStore(dir, MachineIdIndex(), "test", manual)
        store.startOwnerPrefetch { it.run() }
        // at(1) が引き取る（owner.bin (1, at(1)) を書く仕事が列に入る）。アンロードして、写しの at(2) が来る
        assertEquals(ClaimResult.TAKEN_OVER, store.claimOrDefer(id.toString(), 0, 0, at(1), Key("taker"))?.result)
        store.index.removeIfOwner(id, at(1))
        // 覚えているので、その場で引き取る（読まない）
        assertEquals(ClaimResult.TAKEN_OVER, store.claimOrDefer(id.toString(), 0, 1, at(2), Key("copy"))?.result)
        assertEquals(0, store.writerOwnerReadRequests)
        manual.runAll()
        assertEquals(OwnerRecord(2, at(2)), readOwner(id))
        assertEquals(0, store.mainOwnerReads)
        assertEquals(0, store.claimWaitNanos, "メインスレッドで仕事を待たない")
    }

    @Test
    fun `書き込みの口が閉じていれば、読めなかったとして新しいID`() {
        val id = UUID.randomUUID()
        writeOwner(id, OwnerRecord(0, at(0)))
        val manual = ManualSaveExecutor()
        val store = MachineSaveStore(dir, MachineIdIndex(), "test", manual)
        manual.closed = true
        val key = Key("closed")
        assertNull(store.claimOrDefer(id.toString(), 0, 0, at(0), key))
        val done = store.pollDeferredClaims()
        assertEquals(ClaimResult.NEW_ID_UNREADABLE, done.single().claim.result)
        assertNotNull(done.single().claim.identity)
        assertNotEquals(id, done.single().claim.identity!!.id)
    }

    @Test
    fun `開いたときに始めた先読み（prefetchOwners）の結果を、claimがそのまま使う`() {
        val ids = List(50) { UUID.randomUUID() }
        for (id in ids) writeOwner(id, OwnerRecord(0, at(1)))
        // 置き場のコンストラクターが先読みのスレッドを始める（ほかのプロパティを作った後に始めないと、表が作り直されて結果を失う）
        val store = MachineSaveStore(dir, MachineIdIndex(), prefetchOwners = true)
        // 壁時計の期限ではなく、先読みのスレッドの終わりそのものを待つ（#878）
        store.awaitOwnerPrefetch()
        assertTrue(store.ownerPrefetchDone)
        for ((i, id) in ids.withIndex()) {
            assertEquals(ClaimResult.KEPT, store.claimOrDefer(id.toString(), 0, 0, at(1), Key("$i"))?.result, "$id")
        }
        assertEquals(50, store.prefetchHits)
        assertEquals(0, store.deferredClaims)
        assertEquals(0, store.mainOwnerReads)
    }

    @Test
    fun `待ちがなければ、pollは何もしない`() {
        val store = MachineSaveStore(dir, MachineIdIndex())
        assertTrue(store.pollDeferredClaims().isEmpty())
        // メモリで決まる claim（根になかった ID）は待たない
        val id = UUID.randomUUID()
        assertEquals(ClaimResult.KEPT, store.claimOrDefer(id.toString(), 0, 0, at(0), Key("k"))?.result)
        assertEquals(0, store.deferredClaims)
    }

    @Test
    fun `先読みのスレッドに頼んだ後、結果が入らないまま先読みが終われば、次のpollが書き込みのスレッドに回し直す`() {
        val id = UUID.randomUUID()
        writeOwner(id, OwnerRecord(0, at(0)))
        val manual = ManualSaveExecutor()
        val store = MachineSaveStore(dir, MachineIdIndex(), "test", manual)
        // 先読みのスレッドが、結果を入れずに落ちる（Error は読みの失敗として拾わず、結果が入らない）
        store.prefetchOwnerReader = { throw AssertionError("the prefetch thread died") }
        var prefetch: Runnable? = null
        store.startOwnerPrefetch { prefetch = it }
        val key = Key("late")
        assertNull(store.claimOrDefer(id.toString(), 0, 0, at(0), key))
        assertEquals(1, store.prefetchRequests, "先読みが動いているので、先に読んでもらう")
        assertEquals(0, store.writerOwnerReadRequests)
        assertThrows(AssertionError::class.java) { prefetch!!.run() }
        assertTrue(store.pollDeferredClaims().isEmpty(), "結果がないので、まだ決まらない")
        assertEquals(1, store.writerOwnerReadRequests, "先読みが終わったので、書き込みのスレッドに回し直す")
        manual.runAll()
        val done = store.pollDeferredClaims()
        assertEquals(listOf(key), done.map { it.key })
        assertEquals(ClaimResult.KEPT, done.single().claim.result)
        assertEquals(0, store.mainOwnerReads)
    }

    @Test
    fun `根の列挙が欠けて先読みのIDにないIDは、先読みに頼まず書き込みのスレッドが読む`() {
        val manual = ManualSaveExecutor()
        val store = MachineSaveStore(dir, MachineIdIndex(), "test", manual)
        store.scanComplete = false
        var prefetch: Runnable? = null
        store.startOwnerPrefetch { prefetch = it }
        // 先読みの対象を決めた後に現れた ID（列挙が欠けていて分からなかった）
        val id = UUID.randomUUID()
        writeOwner(id, OwnerRecord(0, at(0)))
        val key = Key("unlisted")
        assertNull(store.claimOrDefer(id.toString(), 0, 0, at(0), key))
        assertEquals(0, store.prefetchRequests, "先読みの ID にないので頼まない")
        assertEquals(1, store.writerOwnerReadRequests)
        manual.runAll()
        val done = store.pollDeferredClaims()
        assertEquals(ClaimResult.KEPT, done.single().claim.result)
        assertEquals(id, done.single().claim.identity!!.id)
        assertEquals(0, store.mainOwnerReads)
        prefetch!!.run()
    }

    @Test
    fun `owner_binの書き込みの失敗を受け取る前に読みの仕事を後ろに入れても、確定が登録を新しくして、失敗を受け取っても外れない`() {
        val id = UUID.randomUUID()
        writeOwner(id, OwnerRecord(0, at(0)))
        // 引き取りの owner.bin を書けなくする（一時ファイルの場所が空でないディレクトリ）
        val blocker = dir.resolve(id.toString()).resolve(OwnerRecord.FILE_NAME + ".tmp")
        Files.createDirectories(blocker)
        Files.write(blocker.resolve("x"), byteArrayOf(1))
        val manual = ManualSaveExecutor()
        val store = MachineSaveStore(dir, MachineIdIndex(), "test", manual)
        assertEquals(ClaimResult.TAKEN_OVER, store.claim(id.toString(), 0, 0, at(1)).result)
        manual.runAll()
        assertTrue(store.isUnusable(id), "書けなかった失敗はまだ受け取っていない（規則 7b の印）")
        // 引き取った台がアンロードされ、覚えている owner.bin を忘れた（読みが要る）ところに、別の写しが来る
        store.index.removeIfOwner(id, at(1))
        store.forgetOwnerForTest(id)
        val key = Key("copy")
        assertNull(store.claimOrDefer(id.toString(), 0, 0, at(2), key))
        assertEquals(1, store.writerOwnerReadRequests, "印が付いたままでも、読みの仕事を列の後ろに入れる")
        // 読みの仕事が先に走り、書けなかった前の owner.bin（持ち主 at(0)）を読む
        manual.runAll()
        Files.delete(blocker.resolve("x"))
        Files.delete(blocker)
        // 失敗を受け取る前に確定する（確定が登録を新しくするので、後から失敗を受け取っても外れない）
        val done = store.pollDeferredClaims()
        assertEquals(ClaimResult.TAKEN_OVER, done.single().claim.result)
        assertTrue(store.pollCompleted().isEmpty())
        assertEquals(at(2), store.ownerOf(id), "古い失敗を受け取っても、引き取り直した ID を外さない")
        assertEquals(at(2), store.index.ownerOf(id))
        // 引き取り直しの owner.bin は、書ける状態になってから走る
        manual.runAll()
        assertEquals(OwnerRecord(1, at(2)), readOwner(id))
        assertFalse(store.isUnusable(id), "書けたので印が外れる")
        assertTrue(store.pollCompleted().isEmpty())
        assertEquals(at(2), store.ownerOf(id))
    }

    @Test
    fun `書き込みの列が空かないまま上限を過ぎた待ちは、読みを待たずに新しいIDになり、後から来た読みの結果は捨てる`() {
        val id = UUID.randomUUID()
        writeOwner(id, OwnerRecord(0, at(0)))
        val manual = ManualSaveExecutor()
        // 列はほかの台の仕事で詰まっている（読みの仕事を走らせない）
        val store = MachineSaveStore(dir, MachineIdIndex(), "test", manual, claimWaitMillis = 50)
        val first = Key("first")
        val second = Key("second")
        assertNull(store.claimOrDefer(id.toString(), 0, 0, at(0), first))
        assertNull(store.claimOrDefer(id.toString(), 0, 0, at(1), second))
        assertEquals(1, store.writerOwnerReadRequests)
        assertTrue(store.pollDeferredClaims().isEmpty(), "上限の前は待つ")
        Thread.sleep(120)
        val done = store.pollDeferredClaims()
        assertEquals(listOf(first, second), done.map { it.key }, "来た順に、上限を過ぎた分を新しい ID にする")
        for (d in done) {
            assertEquals(ClaimResult.NEW_ID_UNREADABLE, d.claim.result)
            assertNotEquals(id, d.claim.identity!!.id)
        }
        assertEquals(2, store.deferredClaimTimeouts)
        assertEquals(0, store.waitingClaims)
        assertNull(store.index.ownerOf(id), "元の ID は索引に入れない")
        // 遅れて走った読みの結果は捨てる（待ちはもうない）
        manual.runAll()
        assertTrue(store.pollDeferredClaims().isEmpty())
        assertNull(store.index.ownerOf(id))
        assertEquals(0, store.mainOwnerReads)
        assertEquals(OwnerRecord(0, at(0)), readOwner(id), "元の owner.bin は変えない")
    }

    @Test
    fun `書き込みのスレッドの読みがErrorで落ちても、待ちは読めなかったとして次のpollで決まる`() {
        val id = UUID.randomUUID()
        writeOwner(id, OwnerRecord(0, at(0)))
        val manual = ManualSaveExecutor()
        val store = MachineSaveStore(dir, MachineIdIndex(), "test", manual)
        store.writerOwnerReader = { throw StackOverflowError("test") }
        val key = Key("error")
        assertNull(store.claimOrDefer(id.toString(), 0, 0, at(0), key))
        // SaveWriter と同じく、仕事の外に出た Error は列の側で捕まえる
        assertThrows(StackOverflowError::class.java) { manual.runNext() }
        val done = store.pollDeferredClaims()
        assertEquals(key, done.single().key)
        assertEquals(ClaimResult.NEW_ID_UNREADABLE, done.single().claim.result)
        assertEquals(0, store.deferredClaimTimeouts, "上限を待たずに決まる")
        assertEquals(0, store.waitingClaims)
    }
}
