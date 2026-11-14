package pro.ikili.kibicomputers.core.runtime.component.link

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonType
import pro.ikili.kibicomputers.core.wasi.kibi.WitTool

/**
 * 実際のツールチェーンの形のコンポーネント（#263）: wasi-libc でビルドした p1 のプログラム（`hello-wasi`）を、wasmtime の
 * アダプター（`gradle/wasi-adapter.properties`）で p2 のコマンドのコンポーネントにし（wasi-sdk の wasm32-wasip2 と同じ作り方）、
 * 読み込み・リンク・インスタンス化して `wasi:cli/run` を呼ぶ。
 *
 * resource のハンドル表（#265）がまだないので、標準入出力を取る（own<input-stream> を返す）ところから先へは進めない。
 * ここでは、アダプターの 4 つの core モジュール（メモリ・テーブルを渡し合う）がつながり、最初のホストの関数まで canon lower で届くことを確かめる
 */
class ToolchainComponentTest {
    private fun resource(path: String): ByteArray = requireNotNull(javaClass.getResourceAsStream(path)) { "$path がない" }.readBytes()

    private val component: ByteArray by lazy {
        val r = WitTool.run(
            listOf("component", "new", "work/m.wasm", "--adapt", "wasi_snapshot_preview1=work/a.wasm", "-o", "work/c.wasm"),
            mapOf(
                "m.wasm" to resource("/kibicomputers/wasm/hello-wasi.wasm"),
                "a.wasm" to resource("/kibicomputers/wasi-adapter/wasi_snapshot_preview1.command.wasm"),
            ),
            listOf("c.wasm"),
        )
        assertEquals(0, r.exitCode, r.stderr)
        r.outputs.getValue("c.wasm")
    }

    /** ゲストの宣言どおりの関数を出し、呼ばれたら記録してトラップするホスト。`wasi:cli/exit` の `exit` だけは型を書いて出す */
    private class StubHost : ComponentHost {
        val calls = ArrayList<String>()
        val names = ArrayList<String>()

        override fun interfaceOf(name: String): HostInterface {
            names += name
            val stop = HostFunctionBody { call, _ ->
                calls += "${call.interfaceName}#${call.functionName}"
                throw WasmTrapException(WasmTrapKind.OTHER, "テストのホスト: ${call.functionName}")
            }
            val declared = if (name.startsWith("wasi:cli/exit@")) {
                mapOf("exit" to HostFunction(LinkTesting.fn(listOf(CanonType.Result(null, null)), null), stop))
            } else {
                emptyMap()
            }
            return HostInterface(name, declared, fallback = { _, _ -> stop })
        }
    }

    @Test
    fun `アダプターで作ったp2のコマンドをリンクして動かす`() {
        val host = StubHost()
        val p = ComponentProgram.load(LinkTesting.runtime, component, host, metered = true)
        assertTrue(host.names.isNotEmpty() && host.names.all { it.startsWith("wasi:") && it.contains("@0.2.") }, "${host.names}")
        assertTrue(host.names.any { it.startsWith("wasi:cli/exit@") })
        assertTrue(p.moduleCount >= 2, "core モジュールの数: ${p.moduleCount}")
        val run = p.plan.exports.keys.single { it.startsWith("wasi:cli/run@") }
        LinkTesting.instantiate(p).use { c ->
            assertEquals(p.plan.steps.size, c.coreInstances.size)
            val f = requireNotNull(c.function(run, "run"))
            val e = assertThrows<WasmTrapException> { c.call(f, emptyList()) }
            assertTrue(e.message!!.contains("テストのホスト"), e.message)
            assertEquals(1, host.calls.size, "${host.calls}")
            assertTrue(host.calls[0].startsWith("wasi:cli/std"), "${host.calls}")
        }
    }

    @Test
    fun `ホストの関数の型が違えば読み込みの失敗`() {
        val wrong = ComponentHost { name ->
            val f = HostFunction(LinkTesting.fn(listOf(CanonType.U32), null)) { _, _ -> null }
            HostInterface(name, if (name.startsWith("wasi:cli/exit@")) mapOf("exit" to f) else emptyMap(), fallback = { _, _ -> HostFunctionBody { _, _ -> null } })
        }
        val e = assertThrows<WasmLoadException> { ComponentProgram.load(LinkTesting.runtime, component, wrong, metered = true) }
        assertTrue(e.message!!.contains("exit"), e.message)
    }
}
