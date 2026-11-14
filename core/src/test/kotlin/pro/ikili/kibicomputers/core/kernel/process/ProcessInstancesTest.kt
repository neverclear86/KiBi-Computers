package pro.ikili.kibicomputers.core.kernel.process

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.runtime.WasmInstance
import java.lang.reflect.Proxy

/** プロセスのインスタンスの一覧（#691）: 同一性で O(1) に照合する */
class ProcessInstancesTest {
    /** equals がいつも true・hashCode が 0 のインスタンス（同一性で比べていることを確かめる） */
    private fun instance(): WasmInstance = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(WasmInstance::class.java)) { proxy, method, args ->
        when (method.name) {
            "equals" -> true
            "hashCode" -> 0
            "toString" -> "instance@${System.identityHashCode(proxy)}"
            else -> throw UnsupportedOperationException(method.name)
        }
    } as WasmInstance

    @Test
    fun `1つでも複数でも同一性で照合する`() {
        val list = ProcessInstances()
        val a = instance()
        val b = instance()
        val c = instance()
        assertFalse(list.containsSame(a))
        list += a
        assertTrue(list.containsSame(a))
        assertFalse(list.containsSame(b))
        list += b
        assertTrue(list.containsSame(a))
        assertTrue(list.containsSame(b))
        assertFalse(list.containsSame(c))
        assertEquals(2, list.size)
        assertSame(a, list.first())
        assertEquals(listOf(a, b).map { System.identityHashCode(it) }, list.map { System.identityHashCode(it) })
    }

    @Test
    fun `置き換えと空にした後も照合が合う`() {
        val list = ProcessInstances()
        val a = instance()
        val b = instance()
        val c = instance()
        list += a
        list += b
        assertSame(b, list.set(1, c))
        assertFalse(list.containsSame(b))
        assertTrue(list.containsSame(c))
        list.clear()
        assertTrue(list.isEmpty())
        assertFalse(list.containsSame(a))
        list += b
        assertTrue(list.containsSame(b))
        assertFalse(list.containsSame(a))
        val single = ProcessInstances()
        single += a
        single.set(0, c)
        assertFalse(single.containsSame(a))
        assertTrue(single.containsSame(c))
    }
}
