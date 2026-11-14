package pro.ikili.kibicomputers.core.access

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.config.ConfigValues
import pro.ikili.kibicomputers.core.config.KiBiConfig
import java.util.UUID

class PlayerAccessTest {
    private val alex = PlayerRef(UUID.randomUUID(), "Alex")
    private val steve = PlayerRef(UUID.randomUUID(), "Steve")
    private val notch = PlayerRef(UUID.randomUUID(), "Notch")
    private val rules = AccessRules(2, 1000L, 2, 2)

    private fun withMember(): PlayerAccess {
        val added = PlayerAccess.ownedBy(alex).addPending("steve", 0L, rules)
        assertEquals(AccessChange.ADDED, added.change)
        return added.access.onAccess(steve, 1L, rules)
    }

    @Test
    fun ownerlessAllowsEveryone() {
        val none = PlayerAccess.NONE
        assertFalse(none.hasOwner)
        assertTrue(none.mayUse(notch, false))
        assertTrue(none.isManager(notch, false))
        assertSame(none, none.onAccess(notch, 0L, rules))
        assertEquals(AccessChange.NO_OWNER, none.addPending("steve", 0L, rules).change)
    }

    @Test
    fun ownerMembersAndOperators() {
        val access = withMember()
        assertTrue(access.hasOwner)
        assertEquals(listOf(AccessMember.of(steve)), access.members)
        assertTrue(access.mayUse(alex, false))
        assertTrue(access.mayUse(steve, false))
        assertFalse(access.mayUse(notch, false))
        assertTrue(access.mayUse(notch, true))
        assertTrue(access.isManager(alex, false))
        assertFalse(access.isManager(steve, false), "メンバーは管理できない")
        assertFalse(access.isManager(notch, false))
        assertTrue(access.isManager(notch, true))
        // 照合は UUID。名前が同じでも別人は通らない
        assertFalse(access.mayUse(PlayerRef(UUID.randomUUID(), "Alex"), false))
    }

    @Test
    fun lockedAllowsOnlyOperators() {
        val locked = PlayerAccess.LOCKED
        assertTrue(locked.hasOwner)
        assertFalse(locked.mayUse(alex, false))
        assertTrue(locked.mayUse(alex, true))
        assertFalse(locked.isManager(alex, false))
        assertTrue(locked.isManager(alex, true))
        assertSame(locked, locked.onAccess(alex, 0L, rules))
        assertEquals(AccessChange.LOCKED, locked.addPending("steve", 0L, rules).change)
        assertEquals(AccessChange.LOCKED, locked.removeMember("steve").change)
        val fixed = locked.transferOwner(alex)
        assertFalse(fixed.locked)
        assertEquals(AccessMember.of(alex), fixed.owner)
    }

    @Test
    fun promotionHonoursTheExpiryBoundary() {
        val invited = PlayerAccess.ownedBy(alex).addPending("Steve", 100L, rules).access
        assertEquals(listOf(PendingInvite("steve", 100L)), invited.pending, "名前は小文字で持つ")
        // 期限内
        assertEquals(1, invited.onAccess(steve, 100L + 999L, rules).members.size)
        // ちょうど期限は期限切れ。期限切れの保留は捨てる
        val atTtl = invited.onAccess(steve, 100L + 1000L, rules)
        assertTrue(atTtl.members.isEmpty())
        assertTrue(atTtl.pending.isEmpty())
        assertTrue(invited.onAccess(steve, 100L + 5000L, rules).members.isEmpty())
        // 時計が戻ったら年齢 0（期限切れにしない）
        assertEquals(1, invited.onAccess(steve, 0L, rules).members.size)
    }

    @Test
    fun promotionIgnoresCaseAndKeepsTheInviteAtTheMemberLimit() {
        val invited = PlayerAccess.ownedBy(alex).addPending("NOTCH", 0L, rules).access
        val promoted = invited.onAccess(notch, 1L, rules)
        assertEquals(listOf(AccessMember.of(notch)), promoted.members)
        assertTrue(promoted.pending.isEmpty())

        val full = AccessRules(2, 1000L, 0, 2)
        val kept = invited.onAccess(notch, 1L, full)
        assertSame(invited, kept, "メンバーの上限なら保留を残す")
    }

    @Test
    fun ownerAndMemberAccessDoNotConsumeInvites() {
        val access = PlayerAccess.ownedBy(alex).addPending("someone", 0L, rules).access
        assertSame(access, access.onAccess(alex, 1L, rules))
    }

    @Test
    fun onAccessKeepsTheReferenceUnlessANameChanged() {
        val access = withMember()
        assertSame(access, access.onAccess(alex, 2L, rules))
        assertSame(access, access.onAccess(steve, 2L, rules))
        val renamedOwner = access.onAccess(PlayerRef(alex.id, "Alex2"), 2L, rules)
        assertNotSame(access, renamedOwner)
        assertEquals("Alex2", renamedOwner.owner!!.name)
        val renamedMember = access.onAccess(PlayerRef(steve.id, "Steve2"), 2L, rules)
        assertEquals("Steve2", renamedMember.members.single().name)
        assertEquals(steve.id, renamedMember.members.single().id)
    }

    @Test
    fun addPendingChecksNamesDuplicatesAndLimits() {
        val owned = PlayerAccess.ownedBy(alex)
        assertEquals(AccessChange.INVALID_NAME, owned.addPending("a".repeat(17), 0L, rules).change)
        assertEquals(AccessChange.INVALID_NAME, owned.addPending("test-player", 0L, rules).change)
        assertEquals(AccessChange.INVALID_NAME, owned.addPending("", 0L, rules).change)
        assertEquals(AccessChange.ADDED, owned.addPending("a".repeat(16), 0L, rules).change)
        assertEquals(AccessChange.ALREADY_PRESENT, owned.addPending("ALEX", 0L, rules).change, "持ち主と同じ名前")

        val one = owned.addPending("bob", 0L, rules).access
        assertEquals(AccessChange.ALREADY_PRESENT, one.addPending("Bob", 0L, rules).change)
        val two = one.addPending("carol", 0L, rules).access
        assertEquals(AccessChange.LIMIT_REACHED, two.addPending("dave", 0L, rules).change)
        // 期限切れの保留は先に捨てるので、上限が空く
        val later = two.addPending("dave", 5000L, rules)
        assertEquals(AccessChange.ADDED, later.change)
        assertEquals(listOf("dave"), later.access.pending.map { it.name })

        val member = withMember()
        assertEquals(AccessChange.ALREADY_PRESENT, member.addPending("STEVE", 2L, rules).change, "メンバーと同じ名前")
    }

    @Test
    fun removeMemberByNameOrId() {
        val access = withMember().addPending("bob", 2L, rules).access
        assertEquals(AccessChange.NOT_FOUND, access.removeMember("alex").change, "持ち主は外せない")
        assertEquals(AccessChange.NOT_FOUND, access.removeMember("nobody").change)
        val byName = access.removeMember("STEVE")
        assertEquals(AccessChange.REMOVED, byName.change)
        assertTrue(byName.access.members.isEmpty())
        assertFalse(byName.access.mayUse(steve, false))
        assertEquals(AccessChange.REMOVED, access.removeMember(steve.id.toString()).change)
        val pending = access.removeMember("Bob")
        assertEquals(AccessChange.REMOVED, pending.change)
        assertTrue(pending.access.pending.isEmpty())
    }

    @Test
    fun transferOwnerDropsTheOldOwner() {
        val access = withMember().addPending("notch", 2L, rules).access
        val toSteve = access.transferOwner(steve)
        assertEquals(AccessMember.of(steve), toSteve.owner)
        assertTrue(toSteve.members.isEmpty(), "新しい持ち主はメンバーから外れ、元の持ち主は残らない")
        assertFalse(toSteve.mayUse(alex, false))
        val toNotch = access.transferOwner(notch)
        assertTrue(toNotch.pending.isEmpty(), "新しい持ち主の保留は外す")
        assertEquals(listOf(AccessMember.of(steve)), toNotch.members)
    }

    @Test
    fun pruneExpiredKeepsTheReferenceWhenNothingExpired() {
        val access = PlayerAccess.ownedBy(alex).addPending("bob", 0L, rules).access
        assertSame(access, access.pruneExpired(999L, rules))
        assertTrue(access.pruneExpired(1000L, rules).pending.isEmpty())
    }

    @Test
    fun ofDropsDuplicates() {
        val owner = AccessMember.of(alex)
        val access = PlayerAccess.of(
            owner,
            listOf(AccessMember.of(steve), AccessMember(steve.id, "again"), owner),
            listOf(PendingInvite("Bob", 1L), PendingInvite("bob", 2L)),
        )
        assertEquals(listOf(AccessMember.of(steve)), access.members)
        assertEquals(listOf(PendingInvite("bob", 1L)), access.pending)
    }

    @Test
    fun rulesComeFromTheConfig() {
        assertEquals(AccessRules.DEFAULT, KiBiConfig.accessRules(ConfigValues.DEFAULTS))
        assertEquals(24L * 60 * 60 * 1000, AccessRules.DEFAULT.pendingTtlMillis)
        assertThrows(IllegalArgumentException::class.java) { AccessRules(2, 0L, 1, 1) }
    }
}
