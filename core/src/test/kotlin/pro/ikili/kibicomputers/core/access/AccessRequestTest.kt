package pro.ikili.kibicomputers.core.access

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** OS からの依頼（#110、ADR 0110）の形・枠・承認・表 */
class AccessRequestTest {
    private val alex = PlayerRef(UUID.fromString("00000000-0000-0000-0000-00000000000a"), "Alex")
    private val steve = PlayerRef(UUID.fromString("00000000-0000-0000-0000-00000000000b"), "Steve")
    private val stranger = PlayerRef(UUID.fromString("00000000-0000-0000-0000-00000000000c"), "Stranger")
    private val op = PlayerRef(UUID.fromString("00000000-0000-0000-0000-00000000000d"), "Op")
    private val rules = AccessRules.DEFAULT
    private val now = 1_000_000_000L

    private fun req(kind: AccessRequestKind, arg: String) = AccessRequest.parse(kind, arg)!!

    // ---- 形 ----

    @Test
    fun `名前の形とUUIDの正規の形だけを受け付け、UUIDは小文字にし、崩れたUUIDは断る`() {
        assertNotNull(AccessRequest.parse(AccessRequestKind.ADD, "Alex_01"))
        assertNull(AccessRequest.parse(AccessRequestKind.ADD, "bad-name"))
        assertNull(AccessRequest.parse(AccessRequestKind.ADD, ""))
        assertNull(AccessRequest.parse(AccessRequestKind.ADD, "a".repeat(17)))
        assertNull(AccessRequest.parse(AccessRequestKind.ADD, "00000000-0000-0000-0000-00000000000A"))
        assertNull(AccessRequest.parse(AccessRequestKind.TRANSFER, "00000000-0000-0000-0000-00000000000A"))
        assertNull(AccessRequest.parse(AccessRequestKind.REMOVE, "1-2-3-4-5"))
        assertNull(AccessRequest.parse(AccessRequestKind.REMOVE, "0000000-0000-0000-0000-00000000000a0"))
        val r = AccessRequest.parse(AccessRequestKind.REMOVE, "00000000-0000-0000-0000-00000000000A")!!
        assertEquals("00000000-0000-0000-0000-00000000000a", r.argument)
        assertEquals(alex.id, r.uuidArgument)
        assertNull(req(AccessRequestKind.REMOVE, "Alex").uuidArgument)
    }

    @Test
    fun `同じ種類で小文字にした引数が同じなら等しい`() {
        assertEquals(req(AccessRequestKind.ADD, "Steve"), req(AccessRequestKind.ADD, "STEVE"))
        assertEquals(req(AccessRequestKind.ADD, "Steve").hashCode(), req(AccessRequestKind.ADD, "steve").hashCode())
        assertNotEquals(req(AccessRequestKind.ADD, "Steve"), req(AccessRequestKind.REMOVE, "Steve"))
    }

    // ---- 枠 ----

    @Test
    fun `上限でFULL、同じ依頼はMERGED、期限とreleaseで枠が戻り、記録のないreleaseは何もしない`() {
        val limiter = AccessRequestLimiter()
        val r = AccessRequestRules(1_000, 2)
        assertEquals(AccessRequestLimiter.Reserve.ACCEPTED, limiter.tryReserve(req(AccessRequestKind.ADD, "A"), now, r))
        assertEquals(AccessRequestLimiter.Reserve.MERGED, limiter.tryReserve(req(AccessRequestKind.ADD, "a"), now, r))
        assertEquals(AccessRequestLimiter.Reserve.ACCEPTED, limiter.tryReserve(req(AccessRequestKind.ADD, "B"), now + 500, r))
        assertEquals(AccessRequestLimiter.Reserve.FULL, limiter.tryReserve(req(AccessRequestKind.ADD, "C"), now + 500, r))
        limiter.release(req(AccessRequestKind.ADD, "Z"))
        assertEquals(2, limiter.size)
        limiter.release(req(AccessRequestKind.ADD, "B"))
        assertEquals(AccessRequestLimiter.Reserve.ACCEPTED, limiter.tryReserve(req(AccessRequestKind.ADD, "C"), now + 500, r))
        // A はちょうど期限で切れる
        assertEquals(AccessRequestLimiter.Reserve.ACCEPTED, limiter.tryReserve(req(AccessRequestKind.ADD, "D"), now + 1_000, r))
        assertEquals(2, limiter.size)
    }

    @Test
    fun `並行のtryReserveとreleaseで、上限を超えて取らない`() {
        val limiter = AccessRequestLimiter()
        val r = AccessRequestRules(60_000, 4)
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val accepted = AtomicInteger()
        try {
            repeat(64) { i ->
                pool.execute {
                    start.await()
                    if (limiter.tryReserve(req(AccessRequestKind.ADD, "p$i"), now, r) == AccessRequestLimiter.Reserve.ACCEPTED) accepted.incrementAndGet()
                }
            }
            start.countDown()
        } finally {
            pool.shutdown()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
        assertEquals(4, accepted.get())
        assertEquals(4, limiter.size)
    }

    // ---- 承認 ----

    private fun owned(): PlayerAccess = PlayerAccess.ownedBy(alex).addPending("Steve", now, rules).access.onAccess(steve, now, rules)

    @Test
    fun `mayDecideの表`() {
        val states = mapOf("unowned" to PlayerAccess.NONE, "owned" to owned(), "locked" to PlayerAccess.LOCKED)
        data class Actor(val name: String, val ref: PlayerRef, val isOp: Boolean)
        val actors = listOf(
            Actor("owner", alex, false),
            Actor("member", steve, false),
            Actor("stranger", stranger, false),
            Actor("op", op, true),
            Actor("target", stranger, false),
        )
        for ((stateName, state) in states) {
            for (kind in AccessRequestKind.entries) {
                val request = req(kind, "Stranger")
                for (a in actors) {
                    val target = if (a.name == "target" || kind == AccessRequestKind.TRANSFER) stranger else null
                    val expected = when (stateName) {
                        "unowned" -> if (kind == AccessRequestKind.TRANSFER) a.ref.id == stranger.id else true
                        "owned" -> a.name == "owner" || a.name == "op"
                        else -> a.name == "op"
                    }
                    val actual = AccessApproval.mayDecide(state, request, target, a.ref, a.isOp)
                    assertEquals(expected, actual, "$stateName $kind ${a.name}")
                    // apply の NOT_PERMITTED は mayDecide と一致する
                    val d = AccessApproval.apply(state, request, target, a.ref, a.isOp, now, rules)
                    assertEquals(!expected, d.outcome == AccessApproval.Outcome.NOT_PERMITTED, "$stateName $kind ${a.name}: ${d.outcome}")
                }
            }
        }
    }

    @Test
    fun `持ち主が変わった後の元の持ち主は承認できない`() {
        val moved = owned().transferOwner(stranger)
        val d = AccessApproval.apply(moved, req(AccessRequestKind.ADD, "Bob"), null, alex, false, now, rules)
        assertEquals(AccessApproval.Outcome.NOT_PERMITTED, d.outcome)
        assertSame(moved, d.access)
    }

    @Test
    fun `追加は保留になり、もうあればNO_CHANGE`() {
        val d = AccessApproval.apply(owned(), req(AccessRequestKind.ADD, "Bob"), null, alex, false, now, rules)
        assertEquals(AccessApproval.Outcome.APPLIED, d.outcome)
        assertEquals(listOf("bob"), d.access.pending.map { it.name })
        assertFalse(d.access.members.any { it.name == "Bob" })
        val again = AccessApproval.apply(d.access, req(AccessRequestKind.ADD, "BOB"), null, alex, false, now, rules)
        assertEquals(AccessApproval.Outcome.NO_CHANGE, again.outcome)
        assertEquals(AccessChange.ALREADY_PRESENT, again.change)
        // 読めなかった記録・持ち主なしは変えない
        assertEquals(AccessChange.LOCKED, AccessApproval.apply(PlayerAccess.LOCKED, req(AccessRequestKind.ADD, "Bob"), null, op, true, now, rules).change)
        assertEquals(AccessChange.NO_OWNER, AccessApproval.apply(PlayerAccess.NONE, req(AccessRequestKind.ADD, "Bob"), null, op, true, now, rules).change)
    }

    @Test
    fun `名前での削除は保留とオンラインのメンバーを外し、UUIDならそのメンバーを外す`() {
        val base = owned().addPending("Steve", now, rules).access
        assertEquals(1, base.pending.size)
        // 名前（相手が分からない）: 保留だけ
        val byName = AccessApproval.apply(base, req(AccessRequestKind.REMOVE, "steve"), null, alex, false, now, rules)
        assertEquals(AccessApproval.Outcome.APPLIED, byName.outcome)
        assertTrue(byName.access.pending.isEmpty())
        assertEquals(listOf(steve.id), byName.access.members.map { it.id })
        // 名前（オンラインの相手を固定）: メンバーも
        val withTarget = AccessApproval.apply(base, req(AccessRequestKind.REMOVE, "Steve"), steve, alex, false, now, rules)
        assertTrue(withTarget.access.members.isEmpty() && withTarget.access.pending.isEmpty())
        // UUID
        val byId = AccessApproval.apply(owned(), req(AccessRequestKind.REMOVE, steve.id.toString()), null, op, true, now, rules)
        assertEquals(AccessApproval.Outcome.APPLIED, byId.outcome)
        assertTrue(byId.access.members.isEmpty())
        // ない
        val none = AccessApproval.apply(owned(), req(AccessRequestKind.REMOVE, "Nobody"), null, alex, false, now, rules)
        assertEquals(AccessApproval.Outcome.NO_CHANGE, none.outcome)
        assertEquals(AccessChange.NOT_FOUND, none.change)
    }

    @Test
    fun `持ち主の変更は、持ち主なしなら本人だけ、読めなかった記録からもOPが変えられる`() {
        val request = req(AccessRequestKind.TRANSFER, "Stranger")
        assertEquals(AccessApproval.Outcome.NOT_PERMITTED, AccessApproval.apply(PlayerAccess.NONE, request, stranger, op, true, now, rules).outcome)
        val self = AccessApproval.apply(PlayerAccess.NONE, request, stranger, stranger, false, now, rules)
        assertEquals(AccessApproval.Outcome.APPLIED, self.outcome)
        assertEquals(stranger.id, self.access.owner?.id)
        val fromLocked = AccessApproval.apply(PlayerAccess.LOCKED, request, stranger, op, true, now, rules)
        assertEquals(AccessApproval.Outcome.APPLIED, fromLocked.outcome)
        assertFalse(fromLocked.access.locked)
        val owned = AccessApproval.apply(owned(), request, stranger, alex, false, now, rules)
        assertEquals(stranger.id, owned.access.owner?.id)
        assertFalse(owned.access.members.any { it.id == alex.id })
        assertEquals(AccessApproval.Outcome.TARGET_OFFLINE, AccessApproval.apply(owned(), request, null, alex, false, now, rules).outcome)
        val same = AccessApproval.apply(owned(), req(AccessRequestKind.TRANSFER, "Alex"), alex, alex, false, now, rules)
        assertEquals(AccessApproval.Outcome.NO_CHANGE, same.outcome)
    }

    // ---- 表 ----

    @Test
    fun `表は連番のIDで、peekでは取り出さず、期限切れはpeekでもnull、上限を超えては足さない`() {
        val book = AccessRequestBook<String>(2)
        val a = book.add("a", req(AccessRequestKind.ADD, "A"), null, now, 1_000)!!
        val b = book.add("b", req(AccessRequestKind.ADD, "B"), steve, now + 100, 1_000)!!
        assertEquals(1, a.id)
        assertEquals(2, b.id)
        assertNull(book.add("c", req(AccessRequestKind.ADD, "C"), null, now + 100, 1_000))
        assertSame(a, book.peek(1, now + 200, 1_000))
        assertSame(a, book.peek(1, now + 200, 1_000))
        assertEquals(listOf(1L, 2L), book.list(now + 200, 1_000).map { it.id })
        assertSame(a, book.take(1, now + 200, 1_000))
        assertNull(book.peek(1, now + 200, 1_000))
        assertNull(book.take(1, now + 200, 1_000))
        // b はちょうど期限で切れる
        assertNull(book.peek(2, now + 1_100, 1_000))
        assertEquals(0, book.size)
        val c = book.add("c", req(AccessRequestKind.ADD, "C"), null, now + 1_100, 1_000)!!
        assertEquals(3, c.id)
        assertEquals(steve, b.target)
    }
}
