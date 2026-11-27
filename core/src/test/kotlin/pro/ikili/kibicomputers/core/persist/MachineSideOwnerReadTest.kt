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
    fun `closeのときに列に残っている頼みは、読んでから抜けて、読めなかったにはならない`() {
        val blocker = UUID.randomUUID()
        val queued = List(3) { UUID.randomUUID() }
        writeOwner(blocker, OwnerRecord(0, at(0)))
        for (id in queued) writeOwner(id, OwnerRecord(0, at(1)))
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
        val blockerKey = Key("blocker")
        assertEquals(null, store.claimOrDefer(blocker.toString(), 0, 0, at(0), blockerKey))
        val queuedKeys = queued.map { Key(it.toString()) }
        opened.await()
        // 先頭の読みを止めたまま頼みを積み、その状態で close する
        for ((i, id) in queued.withIndex()) assertEquals(null, store.claimOrDefer(id.toString(), 0, 0, at(1), queuedKeys[i]))
        assertEquals(queued.size, store.sideReadQueueLength)
        var closed: Boolean? = null
        val closer = Thread { closed = store.close(NO_DEADLINE) }
        closer.start()
        try {
            // close が止める印を立てて、読み手の join で待つところまで進むのを待つ（期限なし）
            waitFor { closer.state == Thread.State.TIMED_WAITING || closer.state == Thread.State.WAITING || !closer.isAlive }
            assertTrue(closer.isAlive, "先頭の読みが終わるまで close は抜けない")
            assertEquals(queued.size, store.sideReadQueueLength, "止めても、頼まれていた分は列に残っている")
        } finally {
            release.countDown()
        }
        closer.join()
        assertEquals(true, closed)
        assertFalse(store.sideReaderAlive)
        val done = pollUntil(store, 1 + queued.size)
        assertEquals((listOf(blockerKey) + queuedKeys).toSet(), done.map { it.key }.toSet())
        for (d in done) assertEquals(ClaimResult.KEPT, d.claim.result, "読めなかったにはならない")
        assertEquals(0, store.deferredClaimTimeouts)
        assertEquals((listOf(blocker) + queued).toSet(), reads.toSet(), "残っていた頼みも読んだ")
        assertEquals(1 + queued.size, reads.size)
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

    @Test
    fun `読み手のスレッドを始められなければ書き込みの列で読み、次の台では始め直して、どちらも元のUUIDで決まる`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        writeOwner(first, OwnerRecord(0, at(0)))
        writeOwner(second, OwnerRecord(0, at(1)))
        val manual = ManualSaveExecutor()
        val store = store(manual)
        store.sideReaderRetryNanos = 0
        val starter = store.sideReaderThreadStarter
        var attempts = 0
        store.sideReaderThreadStarter = { body ->
            attempts++
            if (attempts == 1) throw OutOfMemoryError("unable to create native thread: test")
            starter(body)
        }
        val firstKey = Key("first")
        assertEquals(null, store.claimOrDefer(first.toString(), 0, 0, at(0), firstKey), "起動の失敗はチャンクの読み込みまで上げない")
        assertEquals(1, store.sideReaderStartFailures)
        assertEquals(0, store.sideOwnerReadRequests, "読み手のいない列に頼みを残さない")
        assertEquals(1, store.writerOwnerReadRequests, "書き込みの列で読む")
        assertFalse(store.sideReaderAlive)
        assertEquals(0, store.sideReadQueueLength)
        manual.runAll()
        val done = store.pollDeferredClaims()
        assertEquals(firstKey, done.single().key)
        assertEquals(ClaimResult.KEPT, done.single().claim.result)
        assertEquals(first, done.single().claim.identity!!.id)
        // 次の台では、新しいスレッドで始め直す
        val secondKey = Key("second")
        assertEquals(null, store.claimOrDefer(second.toString(), 0, 0, at(1), secondKey))
        assertEquals(2, attempts)
        assertEquals(1, store.sideOwnerReadRequests)
        assertEquals(1, store.writerOwnerReadRequests)
        assertTrue(store.sideReaderAlive)
        val later = pollUntil(store, 1)
        assertEquals(secondKey, later.single().key)
        assertEquals(ClaimResult.KEPT, later.single().claim.result)
        assertEquals(second, later.single().claim.identity!!.id)
        assertEquals(0, store.deferredClaimTimeouts)
        assertEquals(0, manual.size, "読み手が読んだ（書き込みの列は使っていない）")
        assertTrue(store.close(NO_DEADLINE))
    }

    @Test
    fun `読み手の起動に失敗した後は、間が過ぎるまで試み直さず書き込みの列で読む`() {
        val ids = List(5) { UUID.randomUUID() }
        for (id in ids) writeOwner(id, OwnerRecord(0, at(1)))
        val manual = ManualSaveExecutor()
        val store = store(manual)
        store.sideReaderRetryNanos = Long.MAX_VALUE
        var attempts = 0
        store.sideReaderThreadStarter = {
            attempts++
            throw OutOfMemoryError("unable to create native thread: test")
        }
        val keys = ids.map { Key(it.toString()) }
        for ((i, id) in ids.withIndex()) assertEquals(null, store.claimOrDefer(id.toString(), 0, 0, at(1), keys[i]))
        assertEquals(1, attempts, "台ごとにスレッドの生成を試みない")
        assertEquals(1, store.sideReaderStartFailures)
        assertEquals(5, store.writerOwnerReadRequests)
        assertEquals(0, store.sideOwnerReadRequests)
        manual.runAll()
        val done = store.pollDeferredClaims()
        assertEquals(keys.toSet(), done.map { it.key }.toSet())
        for (d in done) {
            assertEquals(ClaimResult.KEPT, d.claim.result)
            assertEquals(UUID.fromString((d.key as Key).name), d.claim.identity!!.id)
        }
        assertTrue(store.close(NO_DEADLINE), "始めていない読み手は、すぐ止まったとする")
    }

    @Test
    fun `先頭の読みが止まっている間にclaimと取り消しを繰り返しても、読み手の列は今待っているIDの数に収まる`() {
        val blocker = UUID.randomUUID()
        val kept = List(3) { UUID.randomUUID() }
        val churn = List(50) { UUID.randomUUID() }
        writeOwner(blocker, OwnerRecord(0, at(0)))
        for (id in kept + churn) writeOwner(id, OwnerRecord(0, at(1)))
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
        val blockerKey = Key("blocker")
        assertEquals(null, store.claimOrDefer(blocker.toString(), 0, 0, at(0), blockerKey))
        val keptKeys = kept.map { Key(it.toString()) }
        try {
            opened.await()
            // 取り出し済みで読みの最中の 1 件（blocker）は、列の長さに入らない
            for ((i, id) in kept.withIndex()) assertEquals(null, store.claimOrDefer(id.toString(), 0, 0, at(1), keptKeys[i]))
            assertEquals(kept.size, store.sideReadQueueLength)
            repeat(1000) { round ->
                // 同じ台の claim と取り消しの繰り返しと、別の台の読み込みと解放の繰り返し
                val id = if (round % 2 == 0) churn[0] else churn[round % churn.size]
                val key = Key("churn-$round")
                assertEquals(null, store.claimOrDefer(id.toString(), 0, 0, at(1), key))
                assertTrue(store.sideReadQueueLength <= kept.size + 1, "今待っている ID の数に収まる")
                store.cancelDeferredClaim(key)
                assertEquals(kept.size, store.sideReadQueueLength, "取り消した読みは列から外れる")
                store.pollDeferredClaims()
            }
            assertEquals(kept.size, store.sideReadQueueLength)
        } finally {
            release.countDown()
        }
        val done = pollUntil(store, 1 + kept.size)
        assertEquals((listOf(blockerKey) + keptKeys).toSet(), done.map { it.key }.toSet())
        for (d in done) assertEquals(ClaimResult.KEPT, d.claim.result)
        assertEquals(0, store.deferredClaimTimeouts)
        assertTrue(store.close(NO_DEADLINE))
        assertEquals((listOf(blocker) + kept).toSet(), reads.toSet(), "取り消した読みは開かない")
        assertEquals(1 + kept.size, reads.size)
    }

    @Test
    fun `読みが止まっている先頭のID自身を取り消して頼み直しても、列は待っているIDの数に収まり、古い結果を捨てて読み直す`() {
        val blocker = UUID.randomUUID()
        val kept = List(3) { UUID.randomUUID() }
        writeOwner(blocker, OwnerRecord(0, at(0)))
        for (id in kept) writeOwner(id, OwnerRecord(0, at(1)))
        val manual = ManualSaveExecutor()
        val store = store(manual)
        val opened = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = java.util.Collections.synchronizedList(ArrayList<UUID>())
        store.sideOwnerReader = { path ->
            val id = UUID.fromString(path.parent.fileName.toString())
            val first = id == blocker && blocker !in reads
            reads += id
            if (first) {
                opened.countDown()
                release.await()
                // 古い番号の結果: 捨てられなければ、blocker の待ちが NEW_ID_UNREADABLE で決まる
                throw StackOverflowError("test: the stale read")
            }
            OwnerRecord.read(path)
        }
        var lastKey = Key("blocker-first")
        assertEquals(null, store.claimOrDefer(blocker.toString(), 0, 0, at(0), lastKey))
        val keptKeys = kept.map { Key(it.toString()) }
        try {
            opened.await()
            for ((i, id) in kept.withIndex()) assertEquals(null, store.claimOrDefer(id.toString(), 0, 0, at(1), keptKeys[i]))
            assertEquals(kept.size, store.sideReadQueueLength, "取り出し済みで読みの最中の blocker は列にない")
            repeat(1000) { round ->
                // 読みの最中の blocker 自身の台の解放と読み込みの繰り返し
                store.cancelDeferredClaim(lastKey)
                assertEquals(kept.size, store.sideReadQueueLength, "取り消した頼みは列から外れる")
                lastKey = Key("blocker-$round")
                assertEquals(null, store.claimOrDefer(blocker.toString(), 0, 0, at(0), lastKey))
                assertEquals(kept.size + 1, store.sideReadQueueLength, "頼み直した blocker が列に入り直し、待っている ID の数に収まる")
                assertTrue(store.pollDeferredClaims().isEmpty())
            }
        } finally {
            release.countDown()
        }
        val done = pollUntil(store, 1 + kept.size)
        assertEquals((listOf<Any>(lastKey) + keptKeys).toSet(), done.map { it.key }.toSet())
        for (d in done) assertEquals(ClaimResult.KEPT, d.claim.result, "古い番号の読めなかった結果は捨てる")
        assertEquals(blocker, done.single { it.key === lastKey }.claim.identity!!.id, "頼み直した分を読み直し、元の UUID で決まる")
        assertEquals(0, store.deferredClaimTimeouts)
        assertTrue(store.close(NO_DEADLINE))
        assertEquals(2, reads.count { it == blocker }, "blocker は古い 1 回と頼み直した 1 回だけ読む")
        assertEquals(2 + kept.size, reads.size)
    }

    /** 1 回目の `toString` で投げる Error（読み手の `catch` の中の `"$e"` から、読みの `try` の外へ出す） */
    private class PoisonError : Error("test: poison") {
        private val calls = java.util.concurrent.atomic.AtomicInteger()

        override fun toString(): String {
            if (calls.getAndIncrement() == 0) throw OutOfMemoryError("test: toString of the read failure")
            return super.toString()
        }
    }

    @Test
    fun `読み手が読みのtryの外のErrorで死んでも、取り出し済みの1件と列に残った頼みは作り直した読み手で元のUUIDで決まる`() {
        val bad = UUID.randomUUID()
        val queued = List(3) { UUID.randomUUID() }
        writeOwner(bad, OwnerRecord(0, at(0)))
        for (id in queued) writeOwner(id, OwnerRecord(0, at(1)))
        val manual = ManualSaveExecutor()
        val store = store(manual)
        store.sideReaderRetryNanos = 0
        val opened = CountDownLatch(1)
        val release = CountDownLatch(1)
        val poisoned = java.util.concurrent.atomic.AtomicBoolean()
        store.sideOwnerReader = { path ->
            if (path.parent.fileName.toString() == bad.toString() && poisoned.compareAndSet(false, true)) {
                opened.countDown()
                release.await()
                throw PoisonError()
            }
            OwnerRecord.read(path)
        }
        val badKey = Key("bad")
        assertEquals(null, store.claimOrDefer(bad.toString(), 0, 0, at(0), badKey))
        val queuedKeys = queued.map { Key(it.toString()) }
        try {
            opened.await()
            for ((i, id) in queued.withIndex()) assertEquals(null, store.claimOrDefer(id.toString(), 0, 0, at(1), queuedKeys[i]))
            assertEquals(queued.size, store.sideReadQueueLength)
        } finally {
            release.countDown()
        }
        waitFor { !store.sideReaderAlive }
        assertEquals(queued.size, store.sideReadQueueLength, "死んだ読み手の列に頼みが残っている")
        val done = pollUntil(store, 1 + queued.size)
        assertEquals((listOf(badKey) + queuedKeys).toSet(), done.map { it.key }.toSet())
        for (d in done) {
            assertEquals(ClaimResult.KEPT, d.claim.result)
            assertEquals(if (d.key === badKey) bad else UUID.fromString((d.key as Key).name), d.claim.identity!!.id)
        }
        assertEquals(1, store.sideReaderDeaths)
        assertEquals(0, store.sideReaderStartFailures)
        assertEquals(0, store.deferredClaimTimeouts)
        assertTrue(store.sideReaderAlive, "読み手を作り直した")
        assertTrue(store.close(NO_DEADLINE))
    }

    @Test
    fun `読み手が死んだ直後の作り直しも失敗したら、取り出し済みの1件と列に残った頼みは書き込みの列で読み、元のUUIDで決まる`() {
        val bad = UUID.randomUUID()
        val queued = List(3) { UUID.randomUUID() }
        writeOwner(bad, OwnerRecord(0, at(0)))
        for (id in queued) writeOwner(id, OwnerRecord(0, at(1)))
        val manual = ManualSaveExecutor()
        val store = store(manual)
        // 作り直しの試みは 1 回だけ失敗させ、その後は間（Long.MAX_VALUE）が過ぎないので書き込みの列に回す
        store.sideReaderRetryNanos = Long.MAX_VALUE
        val starter = store.sideReaderThreadStarter
        var attempts = 0
        store.sideReaderThreadStarter = { body ->
            attempts++
            if (attempts >= 2) throw OutOfMemoryError("unable to create native thread: test")
            starter(body)
        }
        val opened = CountDownLatch(1)
        val release = CountDownLatch(1)
        val poisoned = java.util.concurrent.atomic.AtomicBoolean()
        store.sideOwnerReader = { path ->
            if (path.parent.fileName.toString() == bad.toString() && poisoned.compareAndSet(false, true)) {
                opened.countDown()
                release.await()
                throw PoisonError()
            }
            OwnerRecord.read(path)
        }
        val badKey = Key("bad")
        assertEquals(null, store.claimOrDefer(bad.toString(), 0, 0, at(0), badKey))
        val queuedKeys = queued.map { Key(it.toString()) }
        try {
            opened.await()
            for ((i, id) in queued.withIndex()) assertEquals(null, store.claimOrDefer(id.toString(), 0, 0, at(1), queuedKeys[i]))
            assertEquals(queued.size, store.sideReadQueueLength)
        } finally {
            release.countDown()
        }
        waitFor { !store.sideReaderAlive }
        assertEquals(1 + queued.size.toLong(), store.sideOwnerReadRequests)
        assertEquals(0, store.writerOwnerReadRequests)
        // 死んだのを見て頼みを外し、作り直しに失敗して、4 件とも書き込みの列に回す
        assertEquals(emptyList<DeferredClaim>(), store.pollDeferredClaims())
        assertEquals(1, store.sideReaderDeaths)
        assertEquals(1, store.sideReaderStartFailures)
        assertEquals(2, attempts)
        assertFalse(store.sideReaderAlive)
        assertEquals(0, store.sideReadQueueLength, "読み手のいない列に頼みを残さない")
        assertEquals(1 + queued.size.toLong(), store.writerOwnerReadRequests)
        assertEquals(1 + queued.size.toLong(), store.sideOwnerReadRequests, "読み手への頼みは増えていない")
        assertEquals(1 + queued.size, manual.size)
        manual.runAll()
        val done = store.pollDeferredClaims()
        assertEquals((listOf(badKey) + queuedKeys).toSet(), done.map { it.key }.toSet())
        for (d in done) {
            assertEquals(ClaimResult.KEPT, d.claim.result)
            assertEquals(if (d.key === badKey) bad else UUID.fromString((d.key as Key).name), d.claim.identity!!.id)
        }
        assertEquals(2, attempts, "間が過ぎるまで試み直さない")
        assertEquals(0, store.deferredClaimTimeouts)
        assertTrue(store.close(NO_DEADLINE))
    }

    @Test
    fun `読み手が止める前の割り込みで抜けても、次の頼みで作り直して元のUUIDで決まる`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        writeOwner(first, OwnerRecord(0, at(0)))
        writeOwner(second, OwnerRecord(0, at(1)))
        val manual = ManualSaveExecutor()
        val store = store(manual)
        val starter = store.sideReaderThreadStarter
        val threads = ArrayList<Thread>()
        store.sideReaderThreadStarter = { body -> starter(body).also { threads += it } }
        val firstKey = Key("first")
        assertEquals(null, store.claimOrDefer(first.toString(), 0, 0, at(0), firstKey))
        assertEquals(ClaimResult.KEPT, pollUntil(store, 1).single().claim.result)
        threads.single().interrupt()
        threads.single().join()
        val secondKey = Key("second")
        assertEquals(null, store.claimOrDefer(second.toString(), 0, 0, at(1), secondKey))
        assertEquals(2, threads.size, "死んだのを見て作り直す")
        assertEquals(1, store.sideReaderDeaths)
        val later = pollUntil(store, 1).single()
        assertEquals(secondKey, later.key)
        assertEquals(ClaimResult.KEPT, later.claim.result)
        assertEquals(second, later.claim.identity!!.id)
        assertEquals(0, store.deferredClaimTimeouts)
        assertEquals(0, manual.size)
        assertTrue(store.close(NO_DEADLINE))
    }
}
