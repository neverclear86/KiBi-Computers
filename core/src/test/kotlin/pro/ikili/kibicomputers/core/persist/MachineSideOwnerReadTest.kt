package pro.ikili.kibicomputers.core.persist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 待ちにした claim の `owner.bin` を、書き込みの列の外の読み手で読む（#877、ADR 0084 の規則 7a）のテスト。
 * その ID の仕事が列に残っていなければ、ほかの ID の仕事で列が詰まっていても読み手が読んで決まり、残っていれば今までどおり列の後ろで読む。
 * 読み手が開いている間は、書き込みのスレッドがその ID の `owner.bin` を置き換えない
 */
class MachineSideOwnerReadTest {
    @TempDir
    lateinit var dir: Path

    private fun at(x: Int) = MachineOwner("minecraft:overworld", x, 64, 0)

    private fun ownerFile(id: UUID) = dir.resolve(id.toString()).resolve(OwnerRecord.FILE_NAME)

    private fun writeOwner(id: UUID, record: OwnerRecord) = OwnerRecord.write(ownerFile(id), record)

    private class Key(val name: String) {
        override fun toString() = name
    }

    private fun store(manual: ManualSaveExecutor, claimWaitMillis: Long = MachineSaveConfig.DEFAULT_WRITER_FLUSH_MILLIS) =
        MachineSaveStore(dir, MachineIdIndex(), "test", manual, claimWaitMillis = claimWaitMillis, sideOwnerReads = true)

    private fun pollUntil(store: MachineSaveStore, expected: Int, timeoutMillis: Long = 10_000): List<DeferredClaim> {
        val out = ArrayList<DeferredClaim>()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (out.size < expected && System.nanoTime() < deadline) {
            out += store.pollDeferredClaims()
            if (out.size < expected) Thread.sleep(2)
        }
        return out
    }

    private fun waitFor(timeoutMillis: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (!condition()) {
            if (System.nanoTime() > deadline) return false
            Thread.sleep(2)
        }
        return true
    }

    @Test
    fun `書き込みの列がほかのIDの仕事で詰まっていても、仕事のないIDの待ちは読み手が読んで決まる`() {
        val ids = List(20) { UUID.randomUUID() }
        for (id in ids) writeOwner(id, OwnerRecord(0, at(1)))
        val manual = ManualSaveExecutor()
        // 上限を短くしても、読み手が読むので上限には届かない
        val store = store(manual, claimWaitMillis = 5_000)
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
        val start = System.nanoTime()
        val done = pollUntil(store, ids.size)
        val millis = (System.nanoTime() - start) / 1_000_000
        assertEquals(keys.toSet(), done.map { it.key }.toSet())
        for (d in done) assertEquals(ClaimResult.KEPT, d.claim.result)
        assertTrue(millis < 5_000, "上限より前に決まる（$millis ms）")
        assertEquals(0, store.deferredClaimTimeouts)
        assertEquals(0, store.mainOwnerReads, "メインスレッドで読まない")
        assertEquals(queued, manual.size, "列の仕事は 1 つも走っていない")
        assertTrue(store.close(10_000))
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
        assertTrue(store.close(10_000))
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
            release.await(10, TimeUnit.SECONDS)
            OwnerRecord.read(path)
        }
        assertEquals(null, store.claimOrDefer(id.toString(), 0, 0, at(2), Key("waiting")))
        assertTrue(opened.await(10, TimeUnit.SECONDS))
        // 読み手が開いている間に、マシンのある台の同期の claim が引き取る（owner.bin を書く仕事が列に入る）
        assertEquals(ClaimResult.TAKEN_OVER, store.claim(id.toString(), 0, 0, at(1)).result)
        val writer = Thread { manual.runAll() }.apply { start() }
        writer.join(300)
        assertTrue(writer.isAlive, "書き込みのスレッドは、読み手が読み終えるのを待つ")
        assertEquals(OwnerRecord(0, at(0)), OwnerRecord.read(ownerFile(id)), "まだ置き換えていない")
        release.countDown()
        writer.join(10_000)
        assertFalse(writer.isAlive)
        assertEquals(OwnerRecord(1, at(1)), OwnerRecord.read(ownerFile(id)))
        // 待っていた写しは、引き取った台が索引にいるので重複になる（覚えている owner.bin で判定し直す）
        val done = pollUntil(store, 1)
        assertEquals(ClaimResult.NEW_ID_DUPLICATE_LOADED, done.single().claim.result)
        assertTrue(store.close(10_000))
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
                release.await(10, TimeUnit.SECONDS)
            }
            OwnerRecord.read(path)
        }
        assertEquals(null, store.claimOrDefer(first.toString(), 0, 0, at(0), Key("first")))
        assertTrue(opened.await(10, TimeUnit.SECONDS))
        val key = Key("second")
        assertEquals(null, store.claimOrDefer(second.toString(), 0, 0, at(3), key))
        assertEquals(2, store.sideOwnerReadRequests)
        // 読み手が second を開く前に、その ID の仕事が列に入る（判定の材料は変えない仕事）
        store.readModuleReferences(second.toString(), { false }) { }
        release.countDown()
        // first の確定はここでは見ない（受け取って捨てる）
        assertTrue(waitFor { store.pollDeferredClaims(); store.sideOwnerReadRetries == 1L })
        assertEquals(listOf(first), reads, "second は開かない")
        assertEquals(1, store.writerOwnerReadRequests, "書き込みの列に回し直す")
        manual.runAll()
        val done = store.pollDeferredClaims()
        assertEquals(key, done.single().key)
        assertEquals(ClaimResult.KEPT, done.single().claim.result)
        assertEquals(second, done.single().claim.identity!!.id)
        assertEquals(0, store.mainOwnerReads)
        assertTrue(store.close(10_000))
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
                release.await(10, TimeUnit.SECONDS)
            }
            OwnerRecord.read(path)
        }
        val first = Key("blocker")
        assertEquals(null, store.claimOrDefer(blocker.toString(), 0, 0, at(0), first))
        assertTrue(opened.await(10, TimeUnit.SECONDS))
        val key = Key("gone")
        assertEquals(null, store.claimOrDefer(gone.toString(), 0, 0, at(1), key))
        store.cancelDeferredClaim(key)
        release.countDown()
        val done = pollUntil(store, 1)
        assertEquals(listOf(first), done.map { it.key })
        assertTrue(store.close(10_000))
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
        assertTrue(store.close(10_000))
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
        assertTrue(store.close(10_000))
    }
}
