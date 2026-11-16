package pro.ikili.kibicomputers.core.access

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class PlayerAccessRecordTest {
    private val alex = PlayerRef(UUID.randomUUID(), "Alex")
    private val steve = PlayerRef(UUID.randomUUID(), "Steve")
    private val rules = AccessRules.DEFAULT

    @Test
    fun roundTrips() {
        val access = PlayerAccess.ownedBy(alex).addPending("steve", 10L, rules).access
            .onAccess(steve, 11L, rules)
            .addPending("bob", 12L, rules).access
        val record = PlayerAccessRecord.of(access)!!
        assertEquals(PlayerAccessRecord.VERSION, record.version)
        assertEquals(alex.id, record.ownerId)
        assertEquals("Alex", record.ownerName)
        assertEquals(access, PlayerAccessRecord.toAccess(record))
    }

    @Test
    fun ownerlessHasNoRecord() {
        assertNull(PlayerAccessRecord.of(PlayerAccess.NONE))
        assertThrows(IllegalArgumentException::class.java) { PlayerAccessRecord.of(PlayerAccess.LOCKED) }
    }

    @Test
    fun unknownVersionOrMissingOwnerIsLocked() {
        assertSame(PlayerAccess.LOCKED, PlayerAccessRecord.toAccess(PlayerAccessRecord(2, alex.id, "Alex", emptyList(), emptyList())))
        assertSame(PlayerAccess.LOCKED, PlayerAccessRecord.toAccess(PlayerAccessRecord(0, alex.id, "Alex", emptyList(), emptyList())))
        assertSame(PlayerAccess.LOCKED, PlayerAccessRecord.toAccess(PlayerAccessRecord(1, null, "Alex", emptyList(), emptyList())))
        // 名前がなくても、UUID があれば持ち主
        assertEquals(AccessMember(alex.id, ""), PlayerAccessRecord.toAccess(PlayerAccessRecord(1, alex.id, null, emptyList(), emptyList())).owner)
    }
}
