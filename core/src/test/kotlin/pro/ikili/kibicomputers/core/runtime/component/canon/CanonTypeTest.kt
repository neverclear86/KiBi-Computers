package pro.ikili.kibicomputers.core.runtime.component.canon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.function.ThrowingSupplier
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
import java.time.Duration

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
        assertTrue(CanonType.sameShape(a, b, CanonResourceMatch.SAME_SPACE))
        assertFalse(CanonType.sameShape(a, Record(listOf("x", "f1"), listOf(U32, Str)), CanonResourceMatch.SAME_SPACE))
        assertFalse(CanonType.sameShape(a, Tuple(listOf(U32, Str)), CanonResourceMatch.SAME_SPACE))
        assertTrue(CanonType.sameShape(Result(null, a), Result(null, b), CanonResourceMatch.SAME_SPACE))
        assertFalse(CanonType.sameShape(CanonType.Own(1), CanonType.Own(2), CanonResourceMatch.SAME_SPACE))
        assertFalse(CanonType.sameShape(CanonType.Own(1), CanonType.Borrow(1), CanonResourceMatch.SAME_SPACE))
    }

    @Test
    fun `別の添字空間の resource は、渡した対応で比べる`() {
        // ゲストの空間では 3 番、ホストの空間では 0 番が同じ resource。ゲストの 0 番は別の resource
        val map = mapOf(3 to 0, 5 to 1)
        val guestToHost = CanonResourceMatch { g, h -> map[g] == h }
        val guest = Result(CanonType.Own(3), CanonType.Borrow(5))
        val host = Result(CanonType.Own(0), CanonType.Borrow(1))
        assertTrue(CanonType.sameShape(guest, host, guestToHost))
        // 番号が同じでも、対応がなければ違う
        assertFalse(CanonType.sameShape(Result(CanonType.Own(0), null), Result(CanonType.Own(0), null), guestToHost))
        // 番号で比べると取り違える（同じ resource が違うと出て、別の resource が同じと出る）
        assertFalse(CanonType.sameShape(guest, host, CanonResourceMatch.SAME_SPACE))
        assertTrue(CanonType.sameShape(CanonType.Own(0), CanonType.Own(0), CanonResourceMatch.SAME_SPACE))
        // list・record の中でも対応で比べる
        val g2 = Record(listOf("h"), listOf(ListOf(CanonType.Own(5))))
        val h2 = Record(listOf("h"), listOf(ListOf(CanonType.Own(1))))
        assertTrue(CanonType.sameShape(g2, h2, guestToHost))
        assertFalse(CanonType.sameShape(g2, Record(listOf("h"), listOf(ListOf(CanonType.Own(0)))), guestToHost))
    }

    @Test
    fun `ホストは名前で値を作り、読む`() {
        val kind = Enum(listOf("file", "dir"))
        val info = Record(listOf("name", "size", "kind"), listOf(Str, U64, kind))
        val v = info.value("size" to CanonValue.I64(10), "kind" to kind.case("dir"), "name" to CanonValue.str("a"))
        assertEquals(CanonValue.Fields(listOf(CanonValue.str("a"), CanonValue.I64(10), CanonValue.Case(1, null))), v)
        assertEquals(CanonValue.I64(10), info.field(v, "size"))
        assertEquals("dir", kind.caseName(info.field(v, "kind")))
        assertEquals(2, info.fieldIndex("kind"))
        assertEquals(0, kind.caseIndex("file"))
        // 足りない・重なる・ない名前・型に合わない値はホストの誤り
        assertThrows<IllegalArgumentException> { info.value("name" to CanonValue.str("a"), "size" to CanonValue.I64(1)) }
        assertThrows<IllegalArgumentException> {
            info.value("name" to CanonValue.str("a"), "name" to CanonValue.str("b"), "kind" to kind.case("file"))
        }
        assertThrows<IllegalArgumentException> { info.field(v, "nope") }
        assertThrows<IllegalArgumentException> { info.field(CanonValue.I32(0), "size") }
        assertThrows<IllegalArgumentException> { kind.case("nope") }

        val ready = Variant(listOf("closed", "ready"), listOf(null, U32))
        assertEquals(CanonValue.Case(1, CanonValue.I32(4)), ready.case("ready", CanonValue.I32(4)))
        assertEquals(CanonValue.Case(0, null), ready.case("closed"))
        assertEquals("ready", ready.caseName(CanonValue.Case(1, CanonValue.I32(4))))
        assertEquals(1, ready.caseIndex("ready"))
        assertThrows<IllegalArgumentException> { ready.case("ready") }
        assertThrows<IllegalArgumentException> { ready.case("closed", CanonValue.I32(1)) }
        assertThrows<IllegalArgumentException> { ready.caseName(CanonValue.Case(2, null)) }

        val perms = Flags(listOf("read", "write", "exec"))
        val p = perms.value("read", "exec")
        assertEquals(CanonValue.Flags(0b101), p)
        assertTrue(perms.has(p, "exec"))
        assertFalse(perms.has(p, "write"))
        assertEquals(listOf("read", "exec"), perms.namesOf(p))
        assertEquals(1 shl 31, Flags((0 until 32).map { "b$it" }).bit("b31"))
        assertThrows<IllegalArgumentException> { perms.value("nope") }
    }

    private fun <T> within10s(block: () -> T): T = assertTimeoutPreemptively(Duration.ofSeconds(10), ThrowingSupplier { block() })

    /** 部分型を 2 回ずつ指して重ねた型（大きさはほとんど増えないが、木に展開すると 2^depth の大きさ） */
    private fun diamond(depth: Int, leaf: CanonType): CanonType {
        var t = leaf
        repeat(depth - 1) { t = Result(t, t) }
        return t
    }

    @Test
    fun `別々に作った共有の多い型どうしも、比べる手間は型の定義の数で済む`() {
        // 深さ 100 まで作れる（CanonTypeSpace も wasmparser も通す）。木に展開すると 2^99 回の比較になる
        val a = diamond(CanonLimits.MAX_TYPE_DEPTH, U32)
        val b = diamond(CanonLimits.MAX_TYPE_DEPTH, U32)
        assertEquals(CanonLimits.MAX_TYPE_DEPTH, a.depth)
        assertTrue(within10s { CanonType.sameShape(a, b, CanonResourceMatch.SAME_SPACE) })
        // 一番底が違えば、違うと分かる（これも定義の数で済む）
        val c = diamond(CanonLimits.MAX_TYPE_DEPTH, U64)
        assertFalse(within10s { CanonType.sameShape(a, c, CanonResourceMatch.SAME_SPACE) })
        // 形が同じで片方の一番底の 1 か所だけが違うものも、違うと分かる
        var left: CanonType = Result(U32, U32)
        var right: CanonType = Result(U32, U64)
        repeat(CanonLimits.MAX_TYPE_DEPTH - 2) {
            left = Result(left, left)
            right = Result(right, right)
        }
        assertFalse(within10s { CanonType.sameShape(left, right, CanonResourceMatch.SAME_SPACE) })
    }

    @Test
    fun `型の書き方は、展開すると大きい型でも短く打ち切る`() {
        assertEquals("record{f0: u32, f1: string}", record(U32, Str).toString())
        assertEquals("result<_, list<u8>>", Result(null, ListOf(U8)).toString())
        assertEquals("variant{c0(u8), c1}", variant(U8, null).toString())
        assertEquals("option<own<3>>", Option(CanonType.Own(3)).toString())
        val deep = diamond(CanonLimits.MAX_TYPE_DEPTH, U32)
        val s = within10s { deep.toString() }
        assertTrue(s.length < 1000) { "${s.length} 文字" }
        assertTrue(s.contains("…"))
        // 深さ 10 万の鎖も、再帰を使い果たさずに書ける
        var t: CanonType = U8
        repeat(100_000) { t = Option(t) }
        assertTrue(t.toString().length < 1000)
    }
}
