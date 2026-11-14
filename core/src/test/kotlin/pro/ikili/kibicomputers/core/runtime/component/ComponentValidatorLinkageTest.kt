package pro.ikili.kibicomputers.core.runtime.component

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader

/**
 * 検証器のクラスが足りない（製品の環境で jar の同梱が漏れた）ときも、[ComponentLoader.load] は
 * [pro.ikili.kibicomputers.core.runtime.WasmLoadException] を投げる（#262）。
 *
 * テストのクラスパスから Endive のログ（`run.endive:log`）の jar だけを抜いたクラスローダーで、core のクラスを読み直して確かめる。
 */
class ComponentValidatorLinkageTest {
    @Test
    fun `検証器のクラスが読めなくてもWasmLoadExceptionになる`() {
        val entries = System.getProperty("java.class.path").split(File.pathSeparator).filter { it.isNotEmpty() }
        val (log, rest) = entries.partition { isEndiveLog(it) }
        assertEquals(1, log.size) { "クラスパスに Endive のログの jar が1つだけあるはず: $log" }
        URLClassLoader(rest.map { File(it).toURI().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader()).use { loader ->
            val loaderClass = loader.loadClass(ComponentLoader::class.java.name)
            assertTrue(loaderClass.classLoader === loader)
            val instance = loaderClass.getField("INSTANCE").get(null)
            val load = loaderClass.declaredMethods.single { it.name == "load\$default" }
            val empty = byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x0d, 0x00, 0x01, 0x00)
            // 1 回目はクラスの読み込みの失敗、2 回目以降は初期化に失敗したクラスの再利用。どちらも包む
            repeat(2) {
                val e = try {
                    load.invoke(null, instance, empty, null, null, null, null, 0b11110, null)
                    null
                } catch (e: InvocationTargetException) {
                    e.targetException
                }
                assertEquals("pro.ikili.kibicomputers.core.runtime.WasmLoadException", e?.javaClass?.name) { "包まれていない: $e" }
                assertTrue(e!!.message!!.contains("内部の失敗")) { e.message }
                assertTrue(e.cause is LinkageError) { "原因: ${e.cause}" }
            }
        }
    }

    private fun isEndiveLog(path: String): Boolean {
        val f = File(path)
        return f.name.matches(Regex("""log-[\d.]+\.jar""")) && f.path.replace(File.separatorChar, '/').contains("run.endive/log/")
    }
}
