package pro.ikili.kibicomputers.core.runtime.component.link

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.runtime.WasmModule
import pro.ikili.kibicomputers.core.runtime.WasmModuleShape
import pro.ikili.kibicomputers.core.runtime.WasmRuntime
import pro.ikili.kibicomputers.core.runtime.validation.ModuleLimits

/**
 * 読み込みの時間の上限の残りを、ランタイムに渡す（#446。Endive のコンパイルのやり直しを、その残りに収める）。
 * コンポーネントは、残りをまだ読み込んでいない core モジュールで等分して渡す
 */
class LoadTimeLeftTest {
    /** 渡された残りを覚えるランタイム */
    private class RecordingRuntime(private val inner: WasmRuntime = LinkTesting.runtime) : WasmRuntime {
        val timeLefts = ArrayList<Long>()
        override val name: String get() = "recording-" + inner.name

        override fun load(bytes: ByteArray): WasmModule = throw AssertionError("残りを渡さずに読み込んだ")

        override fun load(bytes: ByteArray, timeLeftNanos: Long): WasmModule {
            timeLefts += timeLeftNanos
            return inner.load(bytes, timeLeftNanos)
        }

        override fun describe(bytes: ByteArray): WasmModuleShape = inner.describe(bytes)
    }

    @Test
    fun `コンポーネントは、時間の上限の残りを core モジュールで等分して渡す`() {
        val runtime = RecordingRuntime()
        LinkTesting.load(
            """
            (component
              (core module ${'$'}a (func (export "f") (result i32) (i32.const 1)))
              (core module ${'$'}b
                (import "a" "f" (func ${'$'}f (result i32)))
                (func (export "g") (result i32) (call ${'$'}f)))
              (core instance ${'$'}ia (instantiate ${'$'}a))
              (core instance ${'$'}ib (instantiate ${'$'}b (with "a" (instance ${'$'}ia))))
              (func (export "g") (result u32) (canon lift (core func ${'$'}ib "g")))
            )
            """.trimIndent(),
            runtime = runtime,
        )
        val limit = ModuleLimits.DEFAULT_TIME_LIMIT_NANOS
        assertEquals(2, runtime.timeLefts.size, "${runtime.timeLefts}")
        val (first, second) = runtime.timeLefts
        // 1つ目は残りの半分（2 つで等分）、2つ目は残りのすべて
        assertTrue(first in 1..limit / 2, "$first")
        assertTrue(second in (first + 1)..limit, "$first, $second")
    }

    @Test
    fun `p1 のモジュールは、時間の上限の残りを渡す`() {
        val bytes = LinkTesting.wat("(module (func (export \"f\") (result i32) (i32.const 1)))")
        val limited = RecordingRuntime()
        Program.loadMetered(limited, bytes, ModuleLimits.DEFAULT)
        Program.load(limited, bytes, ModuleLimits.DEFAULT)
        assertEquals(2, limited.timeLefts.size)
        assertTrue(limited.timeLefts.all { it in 1..ModuleLimits.DEFAULT_TIME_LIMIT_NANOS }, "${limited.timeLefts}")
        // 上限がなければ、残りは無限
        val unlimited = RecordingRuntime()
        Program.load(unlimited, bytes)
        assertEquals(listOf(Long.MAX_VALUE), unlimited.timeLefts)
    }
}
