package pro.ikili.kibicomputers.core.wasi.p2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.component.ComponentImportPolicy
import pro.ikili.kibicomputers.core.runtime.component.InterfaceName
import pro.ikili.kibicomputers.core.runtime.component.link.HostResourceIds
import pro.ikili.kibicomputers.core.runtime.component.link.ResourceImpl
import pro.ikili.kibicomputers.core.wasi.kibi.WitTool

/** `wasi:*` のホスト（#267。ADR 0267）: WIT・版の受け付け方・全インターフェースのリンク・errno の表 */
class WasiP2HostTest {
    @Test
    fun `ホストのWITはアダプターが持つWITと同じ`() {
        val r = WitTool.run(listOf("component", "wit", "work/a.wasm"), mapOf("a.wasm" to P2Testing.adapter))
        assertEquals(0, r.exitCode, r.stderr)
        val text = r.stdout.substring(r.stdout.indexOf("package wasi:io@").also { assertTrue(it >= 0, r.stdout) }).trim()
        assertEquals(text, WasiP2Wit.TEXT.trim(), "アダプターを上げたら WasiP2Wit を書き出し直す")
        assertTrue(text.contains("package wasi:io@${WasiP2Wit.BASE_VERSION} {"))
    }

    @Test
    fun `ホストはWITの全インターフェースを出し、resourceの番号はwasiの範囲で重ならない`() {
        val wit = WasiP2Host.wit
        assertEquals(wit.keys - WasiP2Host.RUN, WasiP2Host.interfaces.keys)
        val ids = wit.values.flatMap { it.resources.values }.toSet()
        assertTrue(ids.all { it >= HostResourceIds.WASI_FIRST && it < HostResourceIds.WASI_END }, "$ids")
        // use で取り込んだ型も同じ番号で載る（ゲストのインスタンス型には取り込んだ型も member として出る）
        val streams = wit.getValue("wasi:io/streams").resources
        assertEquals(wit.getValue("wasi:io/poll").resources.getValue("pollable"), streams.getValue("pollable"))
        assertEquals(wit.getValue("wasi:io/error").resources.getValue("error"), streams.getValue("error"))
        assertEquals(streams.getValue("input-stream"), wit.getValue("wasi:filesystem/types").resources.getValue("input-stream"))
        // fallback は使わない
        assertTrue(WasiP2Host.interfaces.values.all { it.fallback == null })
        // @unstable のうち出すのは exit-with-code だけ（timezone などは出さない）
        assertTrue("exit-with-code" in WasiP2Host.interfaces.getValue("wasi:cli/exit").functions)
        assertNull(WasiP2Host.interfaceOf("wasi:clocks/timezone@0.2.12"))
    }

    @Test
    fun `0点2のどの版も同じインターフェースに写り、ほかの版は断る`() {
        val a = WasiP2Host.interfaceOf("wasi:io/poll@0.2.3")
        assertNotNull(a)
        assertSame(a, WasiP2Host.interfaceOf("wasi:io/poll@0.2.12"))
        assertSame(a, WasiP2Host.interfaceOf("wasi:io/poll@0.2.0"))
        assertSame(a, WasiP2Host.interfaceOf("wasi:io/poll@0.2.999"))
        for (bad in listOf("wasi:io/poll", "wasi:io/poll@0.2.0-rc-2023-11-10", "wasi:io/poll@0.2.1+build", "wasi:io/poll@0.3.0", "wasi:io/poll@1.0.0", "wasi:io/poll@0.1.0")) {
            assertNull(WasiP2Host.interfaceOf(bad), bad)
            val reason = ComponentImportPolicy.rejectWasiVersion(InterfaceName.parse(bad)!!)
            assertNotNull(reason, bad)
        }
        assertTrue(ComponentImportPolicy.rejectWasiVersion(InterfaceName.parse("wasi:io/poll@0.3.0")!!)!!.contains("#268"))
        assertNull(ComponentImportPolicy.rejectWasiVersion(InterfaceName.parse("wasi:io/poll@0.2.12")!!))
    }

    @Test
    fun `版なし・pre-release・0点3のwasiのimportは読み込みの失敗`() {
        for (v in listOf("", "@0.2.0-rc-2023-11-10", "@0.3.0")) {
            val e = assertThrows<WasmLoadException>(v) {
                P2Testing.loadWat("""(component (import "wasi:random/random$v" (instance (export "get-random-u64" (func (result u64))))))""")
            }
            assertTrue(e.message!!.contains("wasi:"), e.message)
        }
    }

    @Test
    fun `基準より新しい版でも、ある関数だけなら通り、ない関数を宣言すれば読み込みの失敗`() {
        P2Testing.loadWat("""(component (import "wasi:random/random@0.2.99" (instance (export "get-random-u64" (func (result u64))))))""")
        val e = assertThrows<WasmLoadException> {
            P2Testing.loadWat("""(component (import "wasi:random/random@0.2.99" (instance (export "get-random-u128" (func (result u64))))))""")
        }
        assertTrue(e.message!!.contains("get-random-u128"), e.message)
        // 型が違えば失敗
        assertThrows<WasmLoadException> {
            P2Testing.loadWat("""(component (import "wasi:random/random@0.2.3" (instance (export "get-random-u64" (func (result u32))))))""")
        }
    }

    @Test
    fun `全インターフェースをimportするダミーのコンポーネントがfallbackなしでリンクできる`() {
        val world = "package test:all;\n\nworld all {\n  include wasi:cli/command@${WasiP2Wit.BASE_VERSION};\n}\n\n" + WasiP2Wit.TEXT
        val r = WitTool.run(
            listOf("component", "embed", "--dummy", "--world", "all", "work/w.wit", "-o", "work/m.wasm"),
            mapOf("w.wit" to world.toByteArray()),
            listOf("m.wasm"),
        )
        assertEquals(0, r.exitCode, r.stderr)
        val c = WitTool.run(listOf("component", "new", "work/m.wasm", "-o", "work/c.wasm"), mapOf("m.wasm" to r.outputs.getValue("m.wasm")), listOf("c.wasm"))
        assertEquals(0, c.exitCode, c.stderr)
        val p = P2Testing.load(c.outputs.getValue("c.wasm"))
        // export の run がドライバーの規則に合う
        WasiP2Driver.create(p)
        // ホストの resource はどれも、ホストの番号ごとに 1 つの正規の番号にまとまる
        val hostResources = p.plan.resources.filterNotNull().filter { it.impl is ResourceImpl.Host }
        assertTrue(hostResources.isNotEmpty())
        val byHost = hostResources.groupBy { (it.impl as ResourceImpl.Host).resource.id }
        assertTrue(byHost.values.all { infos -> infos.map { it.canonical }.toSet().size == 1 }, "$byHost")
    }

    @Test
    fun `版の違うimportのpollableは同じ正規の型になる`() {
        val p = P2Testing.loadWat(
            """
            (component
              (import "wasi:io/poll@0.2.3" (instance (export "pollable" (type (sub resource)))))
              (import "wasi:io/poll@0.2.12" (instance (export "pollable" (type (sub resource)))))
            )
            """.trimIndent(),
        )
        val hosts = p.plan.resources.filterNotNull().filter { it.impl is ResourceImpl.Host }
        assertEquals(2, hosts.size, "$hosts")
        assertEquals(1, hosts.map { it.canonical }.toSet().size, "$hosts")
        assertEquals(WasiP2Host.wit.getValue("wasi:io/poll").resources.getValue("pollable"), (hosts[0].impl as ResourceImpl.Host).resource.id)
    }

    @Test
    fun `errnoの表`() {
        assertEquals("not-permitted", WasiP2Errors.name(Errno.NOTCAPABLE))
        assertEquals("not-permitted", WasiP2Errors.name(Errno.PERM))
        assertEquals("unsupported", WasiP2Errors.name(Errno.NOSYS))
        assertEquals("unsupported", WasiP2Errors.name(Errno.NOTSUP))
        assertEquals("insufficient-memory", WasiP2Errors.name(Errno.MFILE))
        assertEquals("no-entry", WasiP2Errors.name(Errno.NOENT))
        assertEquals("io", WasiP2Errors.name(Errno.CONNRESET))
        // 表の名前はどれも error-code にある
        val codes = WasiP2Host.wit.getValue("wasi:filesystem/types").types.getValue("error-code")
            as pro.ikili.kibicomputers.core.runtime.component.canon.CanonType.Enum
        for (n in WasiP2Errors.TABLE.values) codes.caseIndex(n)
    }
}
