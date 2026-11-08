package pro.ikili.kibicomputers.core.device

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** デバイスの address と種類の形（#65、ADR 0065） */
class DeviceAddressTest {
    @Test
    fun `addressは小文字の36文字のUUIDだけを受ける`() {
        val ok = "0123abcd-ef01-4234-8567-89abcdef0123"
        assertTrue(DeviceAddress.isValid(ok))
        assertEquals(ok, DeviceAddress.parse(ok))
        val random = DeviceAddress.random()
        assertTrue(DeviceAddress.isValid(random), random)
        for (bad in listOf(ok.uppercase(), ok.dropLast(1), ok + "0", ok.replace("-", ""), "", "g123abcd-ef01-4234-8567-89abcdef0123", " $ok")) {
            assertFalse(DeviceAddress.isValid(bad), bad)
            assertThrows<IllegalArgumentException>(bad) { DeviceAddress.parse(bad) }
        }
    }

    @Test
    fun `種類はResourceLocationと同じ文字で64バイトまで`() {
        for (ok in listOf("kibicomputers:screen", "kibicomputers:disk-drive", "mymod:fusion-reactor/core", "my-mod:reactor", "a.b_c:d.e", "a:" + "b".repeat(62))) {
            assertTrue(DeviceKind.isValid(ok), ok)
        }
        assertEquals(64, ("a:" + "b".repeat(62)).length)
        val bads = listOf(
            "Mymod:reactor", "mymod:", ":reactor", "mymod/x:reactor", "a:b:c", "mymod:a,b", "mymod:a b", "mymod:a+b", "mymod:Reactor",
            "", "reactor", "a:" + "b".repeat(63),
        )
        for (bad in bads) assertFalse(DeviceKind.isValid(bad), bad)
        // 組み込みの種類と、後で足す種類も規則に合う
        for (k in listOf(DeviceKind.SCREEN, DeviceKind.KEYBOARD, DeviceKind.SPEAKER, "kibicomputers:gpu", "kibicomputers:redstone-io", "kibicomputers:robot-body")) {
            assertTrue(DeviceKind.isValid(k), k)
        }
    }
}
