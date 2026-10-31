package pro.ikili.kibicomputers.core.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import run.endive.runtime.Instance
import run.endive.wasm.Parser
import run.endive.wasm.WasmModule

/** Gradle の zig cc でビルドしたテスト用 WASM（`core/src/test/wasm/`）を読み込めるか（#9）。 */
class ZigBuiltWasmTest {
    private fun load(name: String): WasmModule {
        val bytes = javaClass.getResourceAsStream("/kibicomputers/wasm/$name.wasm")
            ?.use { it.readBytes() }
        assertNotNull(bytes, "$name.wasm がテストのリソースにない")
        return Parser.parse(bytes!!)
    }

    @Test
    fun `zig ccでビルドしたfibを実行できる`() {
        val instance = Instance.builder(load("fib")).build()
        val fib = instance.export("fib")
        assertEquals(55L, fib.apply(10L)[0])
        assertEquals(6765L, fib.apply(20L)[0])
    }

    @Test
    fun `WASIのプログラムはwasi-libcをリンクしてビルドされる`() {
        val module = load("hello-wasi")
        val exports = (0 until module.exportSection().exportCount()).map { module.exportSection().getExport(it).name() }
        assertTrue("_start" in exports, "_start がない: $exports")
        val imports = module.importSection().stream().map { "${it.module()}.${it.name()}" }.toList()
        assertTrue("wasi_snapshot_preview1.fd_write" in imports, "fd_write の import がない: $imports")
    }
}
