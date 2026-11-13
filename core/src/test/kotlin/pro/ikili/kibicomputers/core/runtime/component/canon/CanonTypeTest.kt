package pro.ikili.kibicomputers.core.runtime.component.canon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType.Bool
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType.Enum
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType.F32
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType.F64
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType.Flags
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType.ListOf
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType.Option
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType.Record
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType.Result
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType.S64
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType.Str
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType.Tuple
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType.U32
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType.U64
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType.U8
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType.Variant
import pro.ikili.kibicomputers.core.runtime.component.canon.CoreFlat.I32
import pro.ikili.kibicomputers.core.runtime.component.canon.CoreFlat.I64
import pro.ikili.kibicomputers.core.wasi.p1.P1Canonical

/** Canonical ABI の型の配置と平らにした形（#264） */
class CanonTypeTest {
    private fun record(vararg types: CanonType) = Record(types.indices.map { "f$it" }, types.toList())
    private fun variant(vararg types: CanonType?) = Variant(types.indices.map { "c$it" }, types.toList())

    private val errorCode = Enum((0 until 40).map { "e$it" })

    @Test
    fun `kibi の型の配置が p1 の定数と C のヘッダーに合う`() {
        // ADR 0073 の表・P1Canonical の定数・kibi_*.h の _Static_assert と同じ答えになるか
        val eventValue = variant(S64, F64, Bool, Str, ListOf(U8))
        assertEquals(P1Canonical.VALUE_SIZE to P1Canonical.VALUE_ALIGN, eventValue.size to eventValue.align)
        val event = record(Str, Str, ListOf(eventValue))
        assertEquals(P1Canonical.EVENT_SIZE to P1Canonical.EVENT_ALIGN, event.size to event.align)
        val batch = Result(record(ListOf(event), U64), errorCode)
        assertEquals(P1Canonical.BATCH_RESULT_SIZE to P1Canonical.BATCH_RESULT_ALIGN, batch.size to batch.align)

        val exitStatus = variant(U32, Option(U8), null)
        val processState = variant(null, null, null, exitStatus)
        val processInfo = record(U32, U32, processState, ListOf(Str))
        assertEquals(P1Canonical.PROCESS_INFO_SIZE to P1Canonical.PROCESS_INFO_ALIGN, processInfo.size to processInfo.align)
        assertEquals(listOf(0, 4, 8, 20), processInfo.offsets.toList())
        val status = Result(Option(exitStatus), errorCode)
        assertEquals(P1Canonical.STATUS_RESULT_SIZE to P1Canonical.STATUS_RESULT_ALIGN, status.size to status.align)

        val fsUsage = record(U64, U64, U64, Option(U32), U32)
        assertEquals(40 to 8, fsUsage.size to fsUsage.align)
        val mountInfo = record(Str, Str, Enum(listOf("disk", "image", "tmp", "dev")), Bool, U32, Option(fsUsage))
        assertEquals(P1Canonical.MOUNT_INFO_SIZE to P1Canonical.MOUNT_INFO_ALIGN, mountInfo.size to mountInfo.align)
        assertEquals(listOf(0, 8, 16, 17, 20, 24), mountInfo.offsets.toList())
        val diskInfo = record(Str, U64, U32, Option(Str))
        assertEquals(P1Canonical.DISK_INFO_SIZE to P1Canonical.DISK_INFO_ALIGN, diskInfo.size to diskInfo.align)

        assertEquals(P1Canonical.U64_RESULT_SIZE to P1Canonical.U64_RESULT_ALIGN, Result(U64, errorCode).let { it.size to it.align })
        assertEquals(P1Canonical.UNIT_RESULT_SIZE to P1Canonical.UNIT_RESULT_ALIGN, Result(null, errorCode).let { it.size to it.align })
        assertEquals(P1Canonical.PIPE_RESULT_SIZE to P1Canonical.PIPE_RESULT_ALIGN, Result(record(U32, U32), errorCode).let { it.size to it.align })
        assertEquals(P1Canonical.HANDLE_RESULT_SIZE to P1Canonical.HANDLE_RESULT_ALIGN, Result(CanonType.Own(0), errorCode).let { it.size to it.align })
        assertEquals(P1Canonical.ENV_ENTRY_SIZE, Tuple(listOf(Str, Str)).size)
    }

    @Test
    fun `判別子の大きさは場合の数で決まる`() {
        assertEquals(1, Enum(List(256) { "e$it" }).discSize)
        assertEquals(2, Enum(List(257) { "e$it" }).discSize)
        assertEquals(2, Enum(List(65536) { "e$it" }).discSize)
        assertEquals(4, Enum(List(65537) { "e$it" }).discSize)
        assertEquals(1, Enum(listOf("a")).size)
    }

    @Test
    fun `flags の大きさは数で決まり 32 個まで`() {
        assertEquals(1, Flags(List(8) { "f$it" }).size)
        assertEquals(2, Flags(List(9) { "f$it" }).size)
        assertEquals(2, Flags(List(16) { "f$it" }).size)
        assertEquals(4, Flags(List(17) { "f$it" }).size)
        assertEquals(-1, Flags(List(32) { "f$it" }).mask)
        assertEquals(0x1ff, Flags(List(9) { "f$it" }).mask)
        assertThrows<IllegalArgumentException> { Flags(List(33) { "f$it" }) }
        assertEquals(listOf(I32), Flags(List(32) { "f$it" }).flat)
    }

    @Test
    fun `variant の平らにした形は場合どうしを join する`() {
        assertEquals(listOf(I32, I32), variant(F32, U32).flat)
        assertEquals(listOf(I32, I64), variant(F32, U64).flat)
        assertEquals(listOf(I32, I64), variant(F64, F32).flat)
        assertEquals(listOf(I32, I64), variant(F64, U32).flat)
        assertEquals(listOf(I32, CoreFlat.F32), variant(F32, null).flat)
        assertEquals(listOf(I32, I32, I64), variant(Tuple(listOf(U8, F64)), Tuple(listOf(F32, U32))).flat)
        assertEquals(listOf(I32), Enum(listOf("a", "b")).flat)
        assertEquals(listOf(I32, I32, I32), Option(Str).flat)
    }

    @Test
    fun `平らにした形は 16 個を超えたら打ち切る`() {
        val sixteen = Tuple(List(16) { U8 })
        assertEquals(16, sixteen.flat!!.size)
        assertNull(Tuple(List(17) { U8 }).flat)
        // variant は判別子の分も数える
        assertNull(variant(sixteen).flat)
        assertEquals(16, variant(Tuple(List(15) { U8 })).flat!!.size)
        // 大きなフィールドを持つ record も、数え上げは途中で止める（形は持たない）
        assertNull(record(*Array(100_000) { U32 }).flat)
        assertNull(ListOf(Tuple(List(17) { U8 })).element.flat)
        assertEquals(listOf(I32, I32), ListOf(Tuple(List(17) { U8 })).flat)
    }

    @Test
    fun `深い型も再帰せずに作れ、深さを持つ`() {
        var t: CanonType = U8
        repeat(100_000) { t = Option(t) }
        assertEquals(100_001, t.depth)
        assertThrows<IllegalArgumentException> { CanonFunction(listOf(t), null) }
    }

    @Test
    fun `構造で比べる`() {
        val a = record(U32, Str)
        val b = record(U32, Str)
        assertTrue(CanonType.sameShape(a, b))
        assertFalse(CanonType.sameShape(a, Record(listOf("x", "f1"), listOf(U32, Str))))
        assertFalse(CanonType.sameShape(a, Tuple(listOf(U32, Str))))
        assertTrue(CanonType.sameShape(Result(null, a), Result(null, b)))
        assertFalse(CanonType.sameShape(CanonType.Own(1), CanonType.Own(2)))
        assertFalse(CanonType.sameShape(CanonType.Own(1), CanonType.Borrow(1)))
    }
}
