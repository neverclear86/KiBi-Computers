package pro.ikili.kibicomputers.core.wasi.p2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.TestDriver
import pro.ikili.kibicomputers.core.kernel.eventually
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.rt
import pro.ikili.kibicomputers.core.kernel.stateOf
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonValue
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.call
import pro.ikili.kibicomputers.core.wasi.kibi.WitTool
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * p2 のコマンドの規則（`wasi:cli/run` の export の数と型。終了コード）と、版の違う import をまたぐ resource（#267。ADR 0267）
 */
class WasiP2CommandTest {
    private val limit = Duration.ofSeconds(60)

    /** `run` を [exports] の名前で export する WAT のコンポーネント。[sig] は `run` の型、[ret] は core 関数の本体 */
    private fun command(vararg exports: String, sig: String = "(result (result))", params: String = "", ret: String = "i32.const 0"): String {
        val inst = exports.joinToString("\n  ") { "(export \"$it\" (instance \$inst))" }
        return """
            (component
              (core module ${'$'}m (memory (export "memory") 1) (func (export "run") $params (result i32) $ret))
              (core instance ${'$'}i (instantiate ${'$'}m))
              (func ${'$'}run $sig (canon lift (core func ${'$'}i "run") (memory ${'$'}i "memory")))
              (instance ${'$'}inst (export "run" (func ${'$'}run)))
              $inst
            )
        """.trimIndent()
    }

    private fun create(text: String): WasiP2Driver = WasiP2Driver.create(P2Testing.loadWat(text).plan)

    private fun assertNoexec(text: String) {
        val e = assertThrows<FsException> { create(text) }
        assertEquals(Errno.NOEXEC, e.errno, e.message)
    }

    @Test
    fun `runのexportがない・2つ以上・型が違うコンポーネントはNOEXEC`() {
        // export がない
        assertNoexec("(component (core module \$m) (core instance (instantiate \$m)))")
        // 版が規則に合わない（0.3 は #268 まで受けない）
        assertNoexec(command("wasi:cli/run@0.3.0"))
        // 規則に合う export が 2 つ以上（版が違っても、どれを呼ぶか決められない）
        assertNoexec(command("wasi:cli/run@0.2.3", "wasi:cli/run@0.2.12"))
        // run の型が func() -> result でない
        assertNoexec(command("wasi:cli/run@0.2.3", sig = "(result u32)"))
        assertNoexec(command("wasi:cli/run@0.2.3", sig = "(result (result u32))"))
        assertNoexec(command("wasi:cli/run@0.2.3", sig = "(result (result (error u32)))"))
        assertNoexec(command("wasi:cli/run@0.2.3", sig = "(param \"x\" u32) (result (result))", params = "(param i32)"))
        // run がインスタンスの中にない（関数を直接 export している）
        assertNoexec(
            """
            (component
              (core module ${'$'}m (func (export "run") (result i32) i32.const 0))
              (core instance ${'$'}i (instantiate ${'$'}m))
              (func ${'$'}run (result (result)) (canon lift (core func ${'$'}i "run")))
              (export "wasi:cli/run@0.2.3" (func ${'$'}run))
            )
            """.trimIndent(),
        )
        // 1 つだけなら、どの 0.2.<n> でも通る
        create(command("wasi:cli/run@0.2.3"))
        create(command("wasi:cli/run@0.2.12"))
    }

    private fun exitCodeOf(text: String): Int? {
        Harness().use { h ->
            val p = Program.component(P2Testing.loadWat(text))
            val r = P2Testing.run(h, p, WasiP2Driver.create(p.component!!.plan))
            assertEquals("", r.output)
            return r.exitCode
        }
    }

    @Test
    fun `runがokなら0、errなら（exitを通らなくても）1で終わる`() = timeout(limit) {
        assertEquals(0, exitCodeOf(command("wasi:cli/run@0.2.12", ret = "i32.const 0")))
        assertEquals(1, exitCodeOf(command("wasi:cli/run@0.2.12", ret = "i32.const 1")))
    }

    /**
     * `@0.2.3` と `@0.2.12` の `wasi:io/poll` を両方 import したコンポーネントで、片方の版（0.2.3 の clock）が作った pollable を、
     * もう片方の版（0.2.12）の `pollable.ready` に渡し、`resource.drop` も 0.2.12 のもので行う（正規の型が 1 つなので通る）
     */
    @Test
    fun `版の違うwasi-io-pollのpollableを、もう片方の版の関数に渡して動かせる`() = timeout(limit) {
        val poll = "interface poll { resource pollable { ready: func() -> bool; block: func(); } poll: func(in: list<borrow<pollable>>) -> list<u32>; }"
        val world = """
            package test:cross;

            world w {
              import wasi:clocks/monotonic-clock@0.2.3;
              import wasi:io/poll@0.2.12;
              export run: func() -> u32;
            }

            package wasi:io@0.2.3 { $poll }
            package wasi:io@0.2.12 { $poll }
            package wasi:clocks@0.2.3 {
              interface monotonic-clock {
                use wasi:io/poll@0.2.3.{pollable};
                subscribe-duration: func(when: u64) -> pollable;
              }
            }
        """.trimIndent()
        val module = """
            (module
              (import "wasi:clocks/monotonic-clock@0.2.3" "subscribe-duration" (func ${'$'}sub (param i64) (result i32)))
              (import "wasi:io/poll@0.2.12" "[method]pollable.ready" (func ${'$'}ready (param i32) (result i32)))
              (import "wasi:io/poll@0.2.12" "[resource-drop]pollable" (func ${'$'}drop (param i32)))
              (func (export "run") (result i32)
                (local ${'$'}p i32) (local ${'$'}r i32)
                (local.set ${'$'}p (call ${'$'}sub (i64.const 0)))
                (local.set ${'$'}r (call ${'$'}ready (local.get ${'$'}p)))
                (call ${'$'}drop (local.get ${'$'}p))
                (local.get ${'$'}r)))
        """.trimIndent()
        val e = WitTool.run(
            listOf("component", "embed", "--world", "w", "work/w.wit", "work/m.wat", "-o", "work/m.wasm"),
            mapOf("w.wit" to world.toByteArray(), "m.wat" to module.toByteArray()),
            listOf("m.wasm"),
        )
        assertEquals(0, e.exitCode, e.stderr)
        val c = WitTool.run(listOf("component", "new", "work/m.wasm", "-o", "work/c.wasm"), mapOf("m.wasm" to e.outputs.getValue("m.wasm")), listOf("c.wasm"))
        assertEquals(0, c.exitCode, c.stderr)
        val program = P2Testing.load(c.outputs.getValue("c.wasm"))
        // 版の違う 2 つの pollable の import は、1 つの正規の型にまとまる
        val hostPollables = program.plan.resources.filterNotNull().filter { it.impl is pro.ikili.kibicomputers.core.runtime.component.link.ResourceImpl.Host }
        assertTrue(hostPollables.map { it.canonical }.toSet().size == 1, "$hostPollables")

        Harness().use { h ->
            val m = h.machine(fuelPerTick = 10_000_000)
            val result = AtomicReference<Any>()
            val driver = TestDriver(ProcessKind.BLOCKING) { ctx ->
                try {
                    result.set((ctx.component!!.call("run") as CanonValue.I32).value)
                    assertEquals(0, ctx.withKernelLock { P2.tableLocked(ctx).size }, "pollable が残った")
                } catch (t: Throwable) {
                    result.set(t)
                }
                StepResult.Exit(0)
            }
            val pid = m.spawn(Program.component(program), driver, SpawnOptions(), 0)
            eventually(30_000, "終わらない") {
                h.scheduler.tick()
                m.stateOf(pid).isTerminal
            }
            (result.get() as? Throwable)?.let { throw it }
            assertEquals(1, result.get(), "版をまたいだ ready が true を返さなかった")
            assertEquals(ProcessState.EXITED, m.stateOf(pid), "${m.rt(pid).lastError}")
            m.stop()
        }
    }
}
