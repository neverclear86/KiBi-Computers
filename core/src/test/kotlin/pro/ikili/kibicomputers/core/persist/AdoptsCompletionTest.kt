package pro.ikili.kibicomputers.core.persist

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/** 書き込みの完了を NBT の ID に採るかの判定（`adoptsCompletion`。#84、ADR 0084 の「完了の扱い」） */
class AdoptsCompletionTest {
    private val a = UUID.randomUUID()
    private val b = UUID.randomUUID()
    private val c = UUID.randomUUID()
    private val x = UUID.randomUUID()

    private fun id(uuid: UUID, generation: Long) = MachineIdentity(uuid, generation, 0)

    @Test
    fun `振り直しの完了は、元のIDの世代と比べずに採る`() {
        // A 世代 5 から B に振り直し、B 世代 1 の完了
        assertTrue(adoptsCompletion(id(a, 5), id(b, 1), id(b, 1)))
        // B 世代 1 が失敗し、続けて予約した B 世代 2 だけが書けた
        assertTrue(adoptsCompletion(id(a, 5), id(b, 2), id(b, 2)))
        // B 世代 1・2 を予約していて、先に世代 1 の完了が届いた
        assertTrue(adoptsCompletion(id(a, 5), id(b, 2), id(b, 1)))
    }

    @Test
    fun `同じIDなら世代が大きいときだけ採る`() {
        assertTrue(adoptsCompletion(id(b, 1), id(b, 2), id(b, 2)))
        assertFalse(adoptsCompletion(id(b, 1), id(b, 1), id(b, 1)), "同じ世代")
        assertFalse(adoptsCompletion(id(b, 2), id(b, 2), id(b, 1)), "遅れて届いた先の完了（規則 4 で後の世代を採った後など）")
    }

    @Test
    fun `新しい台の完了は採る`() {
        assertTrue(adoptsCompletion(null, id(x, 1), id(x, 1)))
    }

    @Test
    fun `今のIDと違うIDの完了は採らない`() {
        assertFalse(adoptsCompletion(id(c, 3), id(c, 3), id(b, 4)), "今の ID が C で、完了が B")
        assertFalse(adoptsCompletion(id(b, 1), null, id(b, 2)), "今の ID がない（壊した後など）")
        assertFalse(adoptsCompletion(null, null, id(x, 1)))
    }
}
