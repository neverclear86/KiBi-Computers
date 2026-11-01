package pro.ikili.kibicomputers.core.input

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.input.KeyboardEvent.KeyDown
import pro.ikili.kibicomputers.core.input.KeyboardEvent.KeyUp
import pro.ikili.kibicomputers.core.input.KeyboardEvent.Text
import java.util.UUID

class KeyboardSessionTest {
    private val typist = UUID(1, 2)

    private fun packet(vararg events: KeyboardEvent): ByteArray = KeyboardEventCodec.encode(events.toList()).single()

    @Test
    fun receivesAndTracksHeldKeys() {
        val session = KeyboardSession(7, typist)
        val events = listOf(KeyDown(Key.A, 0, false), KeyDown(Key.LEFT_SHIFT, 0, false), Text("A"), KeyUp(Key.A, 0))
        assertEquals(events, session.receive(packet(*events.toTypedArray()), 0))
        assertEquals(setOf(Key.LEFT_SHIFT), session.heldKeys)
        assertEquals(7, session.id)
        assertEquals(typist, session.typist)
    }

    @Test
    fun closeSynthesizesKeyUpsOnce() {
        val session = KeyboardSession(1, typist)
        session.receive(packet(KeyDown(Key.B, 0, false), KeyDown(Key.C, 0, false), KeyDown(Key.B, 0, true)), 0)
        assertEquals(listOf(KeyUp(Key.B, KeyModifiers.NONE), KeyUp(Key.C, KeyModifiers.NONE)), session.close())
        assertFalse(session.isOpen)
        assertTrue(session.heldKeys.isEmpty())
        assertTrue(session.close().isEmpty())
    }

    @Test
    fun rejectsAfterCloseMalformedAndOverBudget() {
        val small = packet(Text("y"))
        val session = KeyboardSession(1, typist, KeyboardInputBudget(capacity = small.size * 3, refillPerTick = 0))
        assertNull(session.receive(byteArrayOf(99), 0))
        assertNull(session.receive(packet(Text("x".repeat(small.size * 4))), 0))
        assertEquals(listOf(Text("y")), session.receive(small, 0))
        session.close()
        assertNull(session.receive(small, 0))
    }

    @Test
    fun sharedBudgetCarriesOverToTheNextSession() {
        val data = packet(Text("12345"))
        val budget = KeyboardInputBudget(capacity = data.size * 3 / 2, refillPerTick = 0)
        val first = KeyboardSession(1, typist, budget)
        assertEquals(listOf(Text("12345")), first.receive(data, 0))
        first.close()
        // 閉じて開き直しても、量は戻らない
        val second = KeyboardSession(2, typist, budget)
        assertNull(second.receive(data, 0))
        // 別のプレイヤー（別の予算）は受け付ける
        val other = KeyboardSession(3, UUID(3, 4), KeyboardInputBudget(capacity = data.size * 3 / 2, refillPerTick = 0))
        assertEquals(listOf(Text("12345")), other.receive(data, 0))
    }
}
