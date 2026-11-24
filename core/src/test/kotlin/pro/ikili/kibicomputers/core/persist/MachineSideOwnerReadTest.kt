package pro.ikili.kibicomputers.core.persist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 待ちにした claim の `owner.bin` を、書き込みの列の外の読み手で読む（#877、ADR 0084 の規則 7a）のテスト。
 * その ID の仕事が列に残っていなければ、ほかの ID の仕事で列が詰まっていても読み手が読んで決まり、残っていれば今までどおり列の後ろで読む。
 * 読み手が開いている間は、書き込みのスレッドがその ID の `owner.bin` を置き換えない。
 * 読み手（実スレッド）の結果と止まるのは、壁時計の期限で合否を決めずに待つ（#878。待ちの上限 `claimWaitMillis` も 1 日にして、上限で決まらないようにし、
 * 上限に届かなかったことは `KEPT` と `deferredClaimTimeouts == 0` で見る。`close` にも上限を付けない）。
 * クラスの [Timeout] は、止まったときの保険で、合否の基準ではない
 */
@Timeout(10, unit = TimeUnit.MINUTES)
class MachineSideOwnerReadTest {
    private companion object {
        val CLAIM_WAIT_MILLIS: Long = TimeUnit.DAYS.toMillis(1)

        /**
         * `close` に渡す上限。列の外の読み手（実スレッド）が止まるのを、壁時計の期限で合否を決めずに待つ（#878）。
         * 止まらないときは、クラスの [Timeout] が落とす
         */
        const val NO_DEADLINE: Long = Long.MAX_VALUE
    }

    @TempDir
    lateinit var dir: Path

    private fun at(x: Int) = MachineOwner("minecraft:overworld", x, 64, 0)

    private fun ownerFile(id: UUID) = dir.resolve(id.toString()).resolve(OwnerRecord.FILE_NAME)

    private fun writeOwner(id: UUID, record: OwnerRecord) = OwnerRecord.write(ownerFile(id), record)

    private class Key(val name: String) {
        override fun toString() = name
    }

    /**
     * 待ちの上限（`claimWaitMillis`）は、壁時計で合否が決まらないように 1 日にしてある（#878）。
     * 上限を過ぎた claim は読み手の結果を待たずに `NEW_ID_UNREADABLE` で決まるので、既定の 30 秒だと、読み手（実スレッド）が遅れた負荷の高い CI で落ちる。
     * 上限に届かず読み手が決めたことは、結果が `KEPT` で `deferredClaimTimeouts` が 0 であることで確かめる
     */
    private fun store(manual: ManualSaveExecutor) =
        MachineSaveStore(dir, MachineIdIndex(), "test", manual, claimWaitMillis = CLAIM_WAIT_MILLIS, sideOwnerReads = true)

    /** [store] の待ちを、[expected] 件そろうまで [MachineSaveStore.pollDeferredClaims] で受け取る（期限なし。#878） */
    private fun pollUntil(store: MachineSaveStore, expected: Int): List<DeferredClaim> {
        val out = ArrayList<DeferredClaim>()
        while (true) {
            out += store.pollDeferredClaims()
            if (out.size >= expected) return out
            Thread.sleep(2)
        }
    }

    /** [condition] が真になるまで待つ（期限なし。#878） */
    private fun waitFor(condition: () -> Boolean) {
        while (!condition()) Thread.sleep(2)
    }

    @Test
    fun `書き込みの列がほかのIDの仕事で詰まっていても、仕事のないIDの待ちは読み手が読んで決まる`() {
        val ids = List(20) { UUID.randomUUID() }
        for (id in ids) writeOwner(id, OwnerRecord(0, at(1)))
        val manual = ManualSaveExecutor()
        val store = store(manual)
        // ほかの台の仕事で列を詰める（走らせない）
        repeat(50) { store.delete(UUID.randomUUID()) }
        val queued = manual.size
        val keys = ids.map { Key(it.toString()) }
        for ((i, id) in ids.withIndex()) {
            assertEquals(null, store.claimOrDefer(id.toString(), 0, 0, at(1), keys[i]), "読みの要る ID は待ち")
        }
        assertEquals(20, store.sideOwnerReadRequests)
        assertEquals(0, store.writerOwnerReadRequests, "書き込みの列には入れない")
        assertEquals(queued, manual.size)
        val done = pollUntil(store, ids.size)
        assertEquals(keys.toSet(), done.map { it.key }.toSet())
        for (d in done) assertEquals(ClaimResult.KEPT, d.claim.result)
        assertEquals(0, store.deferredClaimTimeouts, "上限で決まったのではなく、読み手が読んで決まった")
        assertEquals(0, store.mainOwnerReads, "メインスレッドで読まない")
        assertEquals(queued, manual.size, "列の仕事は 1 つも走っていない")
        assertTrue(store.close(NO_DEADLINE))
    }

    @Test
    fun `仕事が列に残っているIDは、今までどおり書き込みの列でその仕事の後ろに読む`() {
        val id = UUID.randomUUID()
        writeOwner(id, OwnerRecord(0, at(0)))
        val manual = ManualSaveExecutor()
        val store = store(manual)
        // at(1) が引き取る（owner.bin (1, at(1)) を書く仕事が列に残る）。覚えている owner.bin を忘れ、読みの要る写しが来る
        assertEquals(ClaimResult.TAKEN_OVER, store.claim(id.toString(), 0, 0, at(1)).result)
        store.index.removeIfOwner(id, at(1))
        store.forgetOwnerForTest(id)
        val key = Key("copy")
        assertEquals(null, store.claimOrDefer(id.toString(), 0, 1, at(2), key))
        assertEquals(1, store.writerOwnerReadRequests)
        assertEquals(0, store.sideOwnerReadRequests, "前の仕事の前のディスクを読まない")
        manual.runAll()
        val done = store.pollDeferredClaims()
        // 引き取りの後の owner.bin (1, at(1)) を読んで、さらに引き取る
        assertEquals(ClaimResult.TAKEN_OVER, done.single().claim.result)
        assertEquals(MachineIdentity(id, 0, 2), done.single().claim.identity)
        assertTrue(store.close(NO_DEADLINE))
    }

    @Test
    fun `読み手が開いている間は、書き込みのスレッドがそのIDのowner_binを置き換えるのを待つ`() {
        val id = UUID.randomUUID()
        writeOwner(id, OwnerRecord(0, at(0)))
        val manual = ManualSaveExecutor()
        val store = store(manual)
        val opened = CountDownLatch(1)
        val release = CountDownLatch(1)
        store.sideOwnerReader = { path ->
            opened.countDown()
            release.await()
            OwnerRecord.read(path)
        }
        assertEquals(null, store.claimOrDefer(id.toString(), 0, 0, at(2), Key("waiting")))
        var writer: Thread? = null
        try {
            opened.await()
            // 読み手が開いている間に、マシンのある台の同期の claim が引き取る（owner.bin を書く仕事が列に入る）
            assertEquals(ClaimResult.TAKEN_OVER, store.claim(id.toString(), 0, 0, at(1)).result)
            val started = Thread { manual.runAll() }.apply { start() }
            writer = started
            // 待たされていることを確かめる（負荷が高いほど通りやすい向きなので、短い時間のままでよい）
            started.join(300)
            assertTrue(started.isAlive, "書き込みのスレッドは、読み手が読み終えるのを待つ")
            assertEquals(OwnerRecord(0, at(0)), OwnerRecord.read(ownerFile(id)), "まだ置き換えていない")
        } finally {
            // 途中で落ちても、止めた読み手を放し、書き込みのスレッドが終わるのを待つ（@TempDir の掃除と重ならないように）
            release.countDown()
            writer?.join()
        }
        assertEquals(OwnerRecord(1, at(1)), OwnerRecord.read(ownerFile(id)))
        // 待っていた写しは、引き取った台が索引にいるので重複になる（覚えている owner.bin で判定し直す）
        val done = pollUntil(store, 1)
        assertEquals(ClaimResult.NEW_ID_DUPLICATE_LOADED, done.single().claim.result)
        assertTrue(store.close(NO_DEADLINE))
    }

    @Test
    fun `読み手が開く前にそのIDの仕事が列に入れば、読まずに書き込みの列に回し直し、読めなかったことにはしない`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        writeOwner(first, OwnerRecord(0, at(0)))
        writeOwner(second, OwnerRecord(0, at(3)))
        val manual = ManualSaveExecutor()
        val store = store(manual)
        val opened = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = java.util.Collections.synchronizedList(ArrayList<UUID>())
        store.sideOwnerReader = { path ->
            val id = UUID.fromString(path.parent.fileName.toString())
            reads += id
            if (id == first) {
                opened.countDown()
                release.await()
            }
            OwnerRecord.read(path)
        }
        assertEquals(null, store.claimOrDefer(first.toString(), 0, 0, at(0), Key("first")))
        val key = Key("second")
        try {
            opened.await()
            assertEquals(null, store.claimOrDefer(second.toString(), 0, 0, at(3), key))
            assertEquals(2, store.sideOwnerReadRequests)
            // 読み手が second を開く前に、その ID の仕事が列に入る（判定の材料は変えない仕事）
            store.readModuleReferences(second.toString(), { false }) { }
        } finally {
            release.countDown()
        }
        // first の確定はここでは見ない（受け取って捨てる）
        waitFor { store.pollDeferredClaims(); store.sideOwnerReadRetries == 1L }
        assertEquals(listOf(first), reads, "second は開かない")
        assertEquals(1, store.writerOwnerReadRequests, "書き込みの列に回し直す")
        manual.runAll()
        val done = store.pollDeferredClaims()
        assertEquals(key, done.single().key)
        assertEquals(ClaimResult.KEPT, done.single().claim.result)
        assertEquals(second, done.single().claim.identity!!.id)
        assertEquals(0, store.mainOwnerReads)
        assertTrue(store.close(NO_DEADLINE))
    }

    @Test
    fun `取り消した待ちの読みは開かず、確定もしない`() {
        val blocker = UUID.randomUUID()
        val gone = UUID.randomUUID()
        writeOwner(blocker, OwnerRecord(0, at(0)))
        writeOwner(gone, OwnerRecord(0, at(1)))
        val manual = ManualSaveExecutor()
        val store = store(manual)
        val opened = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = java.util.Collections.synchronizedList(ArrayList<UUID>())
        store.sideOwnerReader = { path ->
            val id = UUID.fromString(path.parent.fileName.toString())
            reads += id
            if (id == blocker) {
                opened.countDown()
                release.await()
            }
            OwnerRecord.read(path)
        }
        val first = Key("blocker")
        assertEquals(null, store.claimOrDefer(blocker.toString(), 0, 0, at(0), first))
        try {
            opened.await()
            val key = Key("gone")
            assertEquals(null, store.claimOrDefer(gone.toString(), 0, 0, at(1), key))
            store.cancelDeferredClaim(key)
        } finally {
            release.countDown()
        }
        val done = pollUntil(store, 1)
        assertEquals(listOf(first), done.map { it.key })
        assertTrue(store.close(NO_DEADLINE))
        assertEquals(listOf(blocker), reads, "取り消した読みは開かない")
        assertTrue(store.pollDeferredClaims().isEmpty())
        assertEquals(null, store.index.ownerOf(gone))
    }

    @Test
    fun `closeで読み手が止まり、その後の読みは書き込みの列に回る`() {
        val id = UUID.randomUUID()
        val later = UUID.randomUUID()
        writeOwner(id, OwnerRecord(0, at(0)))
        writeOwner(later, OwnerRecord(0, at(1)))
        val manual = ManualSaveExecutor()
        val store = store(manual)
        assertEquals(null, store.claimOrDefer(id.toString(), 0, 0, at(0), Key("a")))
        assertEquals(ClaimResult.KEPT, pollUntil(store, 1).single().claim.result)
        assertTrue(store.sideReaderAlive)
        assertTrue(store.close(NO_DEADLINE))
        assertFalse(store.sideReaderAlive, "close で止まる")
        // 閉じた後は書き込みの列に回り、閉じた口に断られて読めなかったとする（書き込みの口が閉じたときと同じ）
        assertEquals(null, store.claimOrDefer(later.toString(), 0, 0, at(1), Key("b")))
        assertEquals(1, store.writerOwnerReadRequests)
        val done = store.pollDeferredClaims()
        assertEquals(ClaimResult.NEW_ID_UNREADABLE, done.single().claim.result)
        assertNotEquals(later, done.single().claim.identity!!.id)
    }

    @Test
    fun `読み手の読みがErrorで落ちても、待ちは読めなかったとして決まり、読み手は動き続ける`() {
        val bad = UUID.randomUUID()
        val good = UUID.randomUUID()
        writeOwner(bad, OwnerRecord(0, at(0)))
        writeOwner(good, OwnerRecord(0, at(1)))
        val manual = ManualSaveExecutor()
        val store = store(manual)
        store.sideOwnerReader = { path ->
            if (path.parent.fileName.toString() == bad.toString()) throw StackOverflowError("test")
            OwnerRecord.read(path)
        }
        assertEquals(null, store.claimOrDefer(bad.toString(), 0, 0, at(0), Key("bad")))
        assertEquals(null, store.claimOrDefer(good.toString(), 0, 0, at(1), Key("good")))
        val done = pollUntil(store, 2).associate { (it.key as Key).name to it.claim.result }
        assertEquals(ClaimResult.NEW_ID_UNREADABLE, done["bad"])
        assertEquals(ClaimResult.KEPT, done["good"])
        assertEquals(0, store.deferredClaimTimeouts)
        assertTrue(store.close(NO_DEADLINE))
    }
}
