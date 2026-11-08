package pro.ikili.kibicomputers.api.device

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.device.BadArgumentsException

/** デバイスの値（#65）: 6種の値と種類、取り出し、写し */
class DeviceValueTest {
    @Test
    fun `6種の値はそれぞれの種類になり、同じ種類の取り出しだけが通る`() {
        val values = listOf(
            DeviceValue.none() to DeviceValue.Type.NONE,
            DeviceValue.of(-3L) to DeviceValue.Type.INTEGER,
            DeviceValue.of(2.5) to DeviceValue.Type.FLOAT,
            DeviceValue.of(true) to DeviceValue.Type.BOOLEAN,
            DeviceValue.of("hé") to DeviceValue.Type.TEXT,
            DeviceValue.of(byteArrayOf(1, 2)) to DeviceValue.Type.BYTES,
        )
        for ((v, t) in values) assertEquals(t, v.type, "$v")
        assertEquals(DeviceValue.Type.entries.toSet(), values.map { it.second }.toSet())
        assertEquals(-3L, DeviceValue.of(-3L).asLong())
        assertEquals(2.5, DeviceValue.of(2.5).asDouble())
        assertTrue(DeviceValue.of(true).asBoolean())
        assertEquals("hé", DeviceValue.of("hé").asText())
        assertArrayEquals(byteArrayOf(1, 2), DeviceValue.of(byteArrayOf(1, 2)).asBytes())
        // 種類が違えば投げる（整数を浮動小数としては読まない）
        assertThrows<IllegalStateException> { DeviceValue.of(1L).asDouble() }
        assertThrows<IllegalStateException> { DeviceValue.of(1.0).asLong() }
        assertThrows<IllegalStateException> { DeviceValue.none().asText() }
        assertThrows<IllegalStateException> { DeviceValue.of("x").asBytes() }
        assertThrows<IllegalStateException> { DeviceValue.of(byteArrayOf()).asBoolean() }
    }

    @Test
    fun `バイト列は作るときと取り出すときに写す`() {
        val raw = byteArrayOf(1, 2, 3)
        val v = DeviceValue.of(raw)
        raw[0] = 9
        assertArrayEquals(byteArrayOf(1, 2, 3), v.asBytes())
        v.asBytes()[1] = 9
        assertArrayEquals(byteArrayOf(1, 2, 3), v.asBytes())
    }

    @Test
    fun `等しさは種類と中身で比べる`() {
        assertEquals(DeviceValue.of(1L), DeviceValue.of(1L))
        assertNotEquals(DeviceValue.of(1L), DeviceValue.of(1.0))
        assertEquals(DeviceValue.of(byteArrayOf(1)), DeviceValue.of(byteArrayOf(1)))
        assertEquals(DeviceValue.of(byteArrayOf(1)).hashCode(), DeviceValue.of(byteArrayOf(1)).hashCode())
        assertEquals(DeviceValue.of(Double.NaN), DeviceValue.of(Double.NaN))
        assertNotEquals(DeviceValue.of(0.0), DeviceValue.of(-0.0))
        assertEquals(DeviceValue.none(), DeviceValue.none())
        assertFalse(DeviceValue.of("a") == DeviceValue.of(byteArrayOf('a'.code.toByte())))
    }
}

/** 引数の並び（#65）: 範囲外、取り出しの失敗 */
class DeviceArgumentsTest {
    private val args = DeviceArguments(
        listOf(DeviceValue.of(7L), DeviceValue.of(1.5), DeviceValue.of(false), DeviceValue.of("t"), DeviceValue.of(byteArrayOf(4)), DeviceValue.none()),
    )

    @Test
    fun `取り出しは種類が合えば値を返す`() {
        assertEquals(6, args.size())
        assertEquals(7L, args.getLong(0))
        assertEquals(1.5, args.getDouble(1))
        assertFalse(args.getBoolean(2))
        assertEquals("t", args.getText(3))
        assertArrayEquals(byteArrayOf(4), args.getBytes(4))
        assertTrue(args.isNone(5))
    }

    @Test
    fun `getDoubleは整数も受ける`() {
        assertEquals(7.0, args.getDouble(0))
        assertThrows<DeviceException> { args.getDouble(3) }
    }

    @Test
    fun `範囲外はnoneで、isNoneはtrue、取り出しは引数の誤りを投げる`() {
        assertEquals(DeviceValue.none(), args.get(6))
        assertEquals(DeviceValue.none(), args.get(-1))
        assertTrue(args.isNone(100))
        val e = assertThrows<DeviceException> { args.getLong(6) }
        assertTrue(e is BadArgumentsException, "カーネルが bad-arguments に変える型")
        assertEquals("argument #7: integer expected, got nothing", e.message)
        val wrong = assertThrows<DeviceException> { args.getText(0) }
        assertEquals("argument #1: text expected, got integer", wrong.message)
        assertFalse(args.isNone(0))
    }
}

/** メソッドの表（#65）: 名前の規則、重複、登録した順 */
class DeviceMethodsTest {
    private val handler = DeviceMethodHandler { _, _ -> emptyArray() }

    @Test
    fun `名前は登録した順で、メインスレッドと直接を区別して持つ`() {
        val m = DeviceMethods.builder().mainThread("b", handler).direct("a-1_x", handler).build()
        assertEquals(listOf("b", "a-1_x"), m.names)
        assertTrue(m.find("b")!!.mainThread)
        assertFalse(m.find("a-1_x")!!.mainThread)
        assertEquals(null, m.find("c"))
        assertTrue(DeviceMethods.empty().names.isEmpty())
        assertTrue(DeviceMethods.builder().build().names.isEmpty())
    }

    @Test
    fun `名前の重複と規則に合わない名前は拒む`() {
        val b = DeviceMethods.builder().direct("x", handler)
        assertThrows<IllegalArgumentException> { b.mainThread("x", handler) }
        for (bad in listOf("", "X", "1a", "-a", "a b", "a.b", "a:b", "ä", "a".repeat(65))) {
            assertThrows<IllegalArgumentException>(bad) { DeviceMethods.builder().direct(bad, handler) }
        }
        DeviceMethods.builder().direct("a".repeat(64), handler).build()
    }
}
