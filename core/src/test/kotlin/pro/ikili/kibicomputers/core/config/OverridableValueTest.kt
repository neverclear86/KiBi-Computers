package pro.ikili.kibicomputers.core.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OverridableValueTest {
    @Test
    fun overrideWinsOverTheConfiguredValueAndKeepsWinningAcrossReloads() {
        val v = OverridableValue(1)
        v.set(5)
        assertEquals(5, v.value)
        v.configure(2)
        assertEquals(5, v.value)
        assertEquals(2, v.configured)
        assertTrue(v.isOverridden)
    }

    @Test
    fun settingTheConfiguredValueDropsTheOverride() {
        val v = OverridableValue(1)
        v.set(5)
        v.configure(2)
        v.set(2)
        assertFalse(v.isOverridden)
        v.configure(3)
        assertEquals(3, v.value)
    }

    @Test
    fun restoringThePreOverrideValueAfterAReloadTakesTheNewConfiguredValue() {
        // テストの定型: val c = value; set(c の変えたもの); …（この間に読み直し）…; set(c)
        val v = OverridableValue(1)
        val c = v.value
        v.set(5)
        v.configure(2)
        v.set(c)
        assertFalse(v.isOverridden)
        assertEquals(2, v.value)
    }

    @Test
    fun nestedOverrideRestoresToTheOuterOverride() {
        val v = OverridableValue(1)
        v.set(5)
        val outer = v.value
        v.set(6)
        v.set(outer)
        assertEquals(5, v.value)
        v.set(1)
        assertFalse(v.isOverridden)
    }

    @Test
    fun aReloadToTheOverriddenValueDropsTheOverride() {
        val v = OverridableValue(1)
        v.set(5)
        v.configure(5)
        assertFalse(v.isOverridden)
        v.configure(7)
        assertEquals(7, v.value)
    }

    @Test
    fun clearOverrideReturnsToTheConfiguredValue() {
        val v = OverridableValue(1)
        v.set(5)
        v.clearOverride()
        assertEquals(1, v.value)
        // base も捨てるので、古い値に戻しても上書きにならない
        v.configure(2)
        v.set(1)
        assertEquals(1, v.value)
        assertTrue(v.isOverridden)
    }
}
