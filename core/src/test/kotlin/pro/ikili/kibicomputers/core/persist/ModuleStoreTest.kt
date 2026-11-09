package pro.ikili.kibicomputers.core.persist

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** モジュールの置き場（#82）: 書く・あるか・確かめて返す・作るときの種 */
class ModuleStoreTest {
    @TempDir
    lateinit var dir: Path

    private val bytes = "module-bytes".toByteArray()
    private val hash = ModuleStore.sha256(bytes)

    @Test
    fun `書いたものはあり、ファイルに元のバイト列が入る`() {
        val root = dir.resolve("modules")
        val store = FileModuleStore(root)
        assertFalse(store.contains(hash))
        assertTrue(store.put(hash, bytes))
        assertTrue(store.contains(hash))
        assertArrayEquals(bytes, Files.readAllBytes(root.resolve(ModuleStore.hex(hash) + ".wasm")))
        assertArrayEquals(bytes, store.get(hash))
        // 2回目は書かない（集合を引くだけ）
        assertTrue(store.put(hash, bytes))
        assertEquals(listOf(ModuleStore.hex(hash) + ".wasm"), Files.list(root).use { s -> s.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun `ハッシュの違うバイト列は書かない`() {
        val store = FileModuleStore(dir)
        assertThrows<IllegalArgumentException> { store.put(hash, "other".toByteArray()) }
        assertFalse(store.contains(hash))
    }

    @Test
    fun `作るときに置き場のファイル名を種にし、getで中身を確かめる`() {
        FileModuleStore(dir).put(hash, bytes)
        Files.writeString(dir.resolve("not-a-hash.wasm"), "x")
        val other = ModuleStore.sha256("broken".toByteArray())
        Files.write(dir.resolve(ModuleStore.hex(other) + ".wasm"), "tampered".toByteArray())

        val store = FileModuleStore(dir)
        assertTrue(store.contains(hash), "ファイル名が種になる")
        assertTrue(store.contains(other), "中身はまだ確かめていない")
        assertArrayEquals(bytes, store.get(hash))
        assertNull(store.get(other), "中身が合わなければ null")
        assertFalse(store.contains(other), "合わなかったものは集合から外す")
    }

    @Test
    fun `既にあるファイルの中身が合わなければ書き直す`() {
        Files.write(dir.resolve(ModuleStore.hex(hash) + ".wasm"), "tampered".toByteArray())
        val store = FileModuleStore(dir.resolve("none"))
        assertFalse(store.contains(hash))
        val same = FileModuleStore(dir)
        assertTrue(same.contains(hash), "壊れたファイルの名前も種になる")
        assertTrue(same.put(hash, bytes), "種だけのハッシュは put で中身を確かめる")
        assertArrayEquals(bytes, Files.readAllBytes(dir.resolve(ModuleStore.hex(hash) + ".wasm")))
        assertArrayEquals(bytes, same.get(hash))
    }

    @Test
    fun `ファイルが消えていればgetはnullで、集合から外す`() {
        val store = FileModuleStore(dir)
        store.put(hash, bytes)
        Files.delete(dir.resolve(ModuleStore.hex(hash) + ".wasm"))
        assertNull(store.get(hash))
        assertFalse(store.contains(hash))
    }

    @Test
    fun `NONEは何も書かず、すべてない`() {
        assertFalse(ModuleStore.NONE.put(hash, bytes))
        assertFalse(ModuleStore.NONE.contains(hash))
        assertNull(ModuleStore.NONE.get(hash))
    }
}
