package pro.ikili.kibicomputers.core.wasi.p2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import pro.ikili.kibicomputers.api.device.Device
import pro.ikili.kibicomputers.api.device.DeviceMethods
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.TestDriver
import pro.ikili.kibicomputers.core.kernel.eventually
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.rt
import pro.ikili.kibicomputers.core.kernel.stateOf
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.RuntimeSelfCheck
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.component.canon.CanonValue
import pro.ikili.kibicomputers.core.runtime.component.link.ComponentProgram
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting.call
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.wasi.kibi.KibiErrors
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * Component Model の層（p2）の `spawn`・`spawn-with-handles`・`invoke` の検査の順（#616。ADR 0616）。
 *
 * canon lower は全引数を lift してからホストの本体を呼ぶので、後の引数の align・判別子・UTF-8 の誤りは、先の引数のホストの検査
 * （`name-too-long`・`too-big`）より先にトラップになる。p1 は引数を順に lift しながら確かめるので、同じ入力で error-code を返す
 * （`WasiP1KibiProcessTest` の `misaligned-order`。p1 の順として固定した）。ここでは p1 と違う側を、実際のコンポーネント
 * （WAT のコアモジュール + WIT の world）から確かめる。トラップは、別の原因のトラップで通らないようにメッセージまで照合する
 */
class KibiP2LiftOrderTest {
    private val limit = Duration.ofSeconds(180)
    private val s = "\$"

    private val nameTooLong = KibiErrors.code(Errno.NAMETOOLONG)
    private val tooBig = KibiErrors.code(Errno.TOOBIG)
    private val inval = KibiErrors.code(Errno.INVAL)
    private val badDescriptor = KibiErrors.code(Errno.BADF)

    private val realloc = """
        (memory (export "memory") 1)
        (global ${s}bump (mut i32) (i32.const 2048))
        (func (export "cabi_realloc") (param ${s}old i32) (param ${s}oldsize i32) (param ${s}align i32) (param ${s}new i32) (result i32)
          (local ${s}p i32)
          (local.set ${s}p (i32.and (i32.add (global.get ${s}bump) (i32.sub (local.get ${s}align) (i32.const 1))) (i32.sub (i32.const 0) (local.get ${s}align))))
          (global.set ${s}bump (i32.add (local.get ${s}p) (local.get ${s}new)))
          (local.get ${s}p))
    """.trimIndent()

    /**
     * `[static]process.spawn` を呼ぶ export を並べたコンポーネント。どの export も、err なら error-code を、ok なら -1 を返す。
     *
     * メモリ: 4096 から 5000 バイトの 0（長すぎる path・UTF-8 としては正しい）、16384 から 4097 個の空の string（args）、
     * 64 に判別子 9 の fd-source を持つ fds の要素、80 に UTF-8 でない 1 バイト（0xff）
     */
    private val spawnComponent: ByteArray by lazy {
        // $sp: path(ptr,len) args(ptr,len) env(disc,ptr,len) cwd(disc,ptr,len) fds(ptr,len)。結果は 96（判別子 +0、error-code +4）
        fun export(name: String, vararg args: Int): String = """
              (func (export "$name") (result i32)
                (call ${s}sp ${args.joinToString(" ") { "(i32.const $it)" }}))
        """
        val longPath = intArrayOf(4096, 5000)
        val okPath = intArrayOf(32, 7)
        val noArgs = intArrayOf(0, 0)
        val manyArgs = intArrayOf(16384, 4097)
        val noEnv = intArrayOf(0, 0, 0)
        val noCwd = intArrayOf(0, 0, 0)
        val noFds = intArrayOf(0, 0)
        val exports = listOf(
            export("long-path", *longPath, *noArgs, *noEnv, *noCwd, *noFds),
            export("long-path-misaligned-args", *longPath, 17, 0, *noEnv, *noCwd, *noFds),
            export("many-args", *okPath, *manyArgs, *noEnv, *noCwd, *noFds),
            export("many-args-misaligned-fds", *okPath, *manyArgs, *noEnv, *noCwd, 18, 0),
            export("long-path-misaligned-env", *longPath, *noArgs, 1, 18, 0, *noCwd, *noFds),
            export("long-path-env-none", *longPath, *noArgs, 0, 18, 0, *noCwd, *noFds),
            export("long-path-bad-cwd-disc", *longPath, *noArgs, *noEnv, 2, 0, 0, *noFds),
            export("long-path-bad-cwd-utf8", *longPath, *noArgs, *noEnv, 1, 80, 1, *noFds),
            export("long-path-bad-fd-source", *longPath, *noArgs, *noEnv, *noCwd, 64, 1),
        )
        val wat = """
            (module
              (import "kibi:process/process@0.1.0" "[static]process.spawn" (func ${s}spawn (param i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32)))
              $realloc
              (data (i32.const 32) "/bin/ok")
              ;; fds = [{ fd: 0, source: 判別子 9 }]（record fd-assign: fd +0、variant の判別子 +4、中身 +8）
              (data (i32.const 68) "\09")
              (data (i32.const 80) "\ff")
              (func ${s}sp
                (param ${s}pp i32) (param ${s}pl i32) (param ${s}ap i32) (param ${s}al i32)
                (param ${s}ed i32) (param ${s}ep i32) (param ${s}el i32) (param ${s}cd i32) (param ${s}cp i32) (param ${s}cl i32)
                (param ${s}fp i32) (param ${s}fl i32) (result i32)
                (call ${s}spawn
                  (local.get ${s}pp) (local.get ${s}pl) (local.get ${s}ap) (local.get ${s}al)
                  (local.get ${s}ed) (local.get ${s}ep) (local.get ${s}el) (local.get ${s}cd) (local.get ${s}cp) (local.get ${s}cl)
                  (local.get ${s}fp) (local.get ${s}fl) (i32.const 96))
                (if (result i32) (i32.load8_u (i32.const 96)) (then (i32.load8_u (i32.const 100))) (else (i32.const -1))))
              ${exports.joinToString("\n")})
        """.trimIndent()
        KibiP2WitTool.component(
            """
            import kibi:process/process@0.1.0;
            ${exports.joinToString("\n") { "export ${Regex("\"(.*?)\"").find(it)!!.groupValues[1]}: func() -> s32;" }}
            """.trimIndent(),
            wat,
        )
    }

    private fun load(bytes: ByteArray): Program =
        Program.component(ComponentProgram.load(LinkTesting.runtime, bytes, KernelComponentHost))

    private fun machine(h: Harness): Machine {
        val m = h.machine(fuelPerTick = 10_000_000)
        m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
        m.vfs.createDirectory(VfsPath.parse("/bin"))
        val f = m.vfs.open(VfsPath.parse("/bin/ok"), OpenOptions(read = true, write = true, create = true, truncate = true))
        val ok = RuntimeSelfCheck.RUN_COMMAND_COMPONENT
        m.vfs.write(f, ok, 0, ok.size)
        return m
    }

    /**
     * コンポーネント [bytes] の export [entry] を、ブロッキング型のプロセスの中で呼んで戻り値を返す（トラップならその例外を投げる）。
     * どちらでも、呼んだ後に子がいないことと、ホストの表が [table] 個（ゲストが閉じずに残したものの数）のことを確かめる
     */
    private fun runGuest(h: Harness, m: Machine, bytes: ByteArray, entry: String, table: Int = 0): Int {
        val result = AtomicReference<Any>()
        val tableAfter = AtomicReference<Int>()
        val children = AtomicReference<List<Int>>()
        val driver = TestDriver(ProcessKind.BLOCKING) { ctx ->
            try {
                result.set((ctx.component!!.call(entry) as CanonValue.I32).value)
            } catch (t: Throwable) {
                result.set(t)
            }
            children.set(ctx.children())
            tableAfter.set(ctx.withKernelLock { P2.tableLocked(ctx).size })
            StepResult.Exit(0)
        }
        val pid = m.spawn(load(bytes), driver, SpawnOptions(stdio = StdioSpec.INHERIT, fds = SpawnOptions.ROOT_PREOPEN), 0)
        eventually(120_000, "終わらない") {
            h.scheduler.tick()
            m.stateOf(pid).isTerminal
        }
        assertEquals(ProcessState.EXITED, m.stateOf(pid), "${m.rt(pid).lastError}")
        assertEquals(table, tableAfter.get(), "$entry: ホストの resource の数が違う")
        assertEquals(emptyList<Int>(), children.get(), "$entry: 子を作った")
        (result.get() as? Throwable)?.let { throw it }
        return result.get() as Int
    }

    private fun assertTrap(h: Harness, m: Machine, bytes: ByteArray, entry: String, message: String, table: Int = 0) {
        val e = assertThrows<WasmTrapException>(entry) { runGuest(h, m, bytes, entry, table) }
        assertTrue(e.message.orEmpty().contains(message), "$entry: 別の原因のトラップ: ${e.message}")
    }

    @Test
    fun `ホストの検査だけに当たる入力は、p1と同じerror-codeで返る（対照）`() = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = machine(h)
            assertEquals(nameTooLong, runGuest(h, m, spawnComponent, "long-path"))
            // 4097 個は lift の予算の中なので、lift した後のホストの検査で too-big
            assertEquals(tooBig, runGuest(h, m, spawnComponent, "many-args"))
            // env が none なら list を lift しないので、揃っていない env_ptr はトラップにならない（p1 と同じ）
            assertEquals(nameTooLong, runGuest(h, m, spawnComponent, "long-path-env-none"))
            m.stop()
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("liftTraps")
    fun `後の引数のliftの誤りは、先の引数のホストの検査より先にトラップになる`(entry: String, message: String) = timeout(limit) {
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = machine(h)
            assertTrap(h, m, spawnComponent, entry, message)
            m.stop()
        }
    }

    @Test
    fun `spawn-with-handlesのhandlesの個数は、fdsまでの引数とKibiProcess-optionsの後に確かめる`() = timeout(limit) {
        // handles = 257 個の { fd: i, source: input(borrow stdin) }（record handle-assign: fd +0、判別子 +4、ハンドル +8）。個数だけが誤り
        // 各 export は、257 個の handles に、別の誤りを 1 つ足して呼ぶ。引数: path(ptr,len) args(ptr,len) cwd(disc,ptr,len) fds(ptr,len)
        // メモリ: 4096 から 5000 バイトの 0（長すぎる path・cwd）、20480 から 4097 個の空の string（args）、
        // 64 に fds の要素 { fd: 5, source: parent(3) }（p2 は 0..2 だけ）、80 に { fd: 2^31, source: closed }（fd が範囲外）
        fun export(name: String, vararg args: Int): String = """
              (func (export "$name") (result i32)
                (call ${s}sh ${args.joinToString(" ") { "(i32.const $it)" }}))
        """
        val okPath = intArrayOf(32, 7)
        val longPath = intArrayOf(4096, 5000)
        val noArgs = intArrayOf(0, 0)
        val noCwd = intArrayOf(0, 0, 0)
        val noFds = intArrayOf(0, 0)
        val exports = listOf(
            export("long-path", *longPath, *noArgs, *noCwd, *noFds),
            export("ok-path", *okPath, *noArgs, *noCwd, *noFds),
            export("many-args", *okPath, 20480, 4097, *noCwd, *noFds),
            export("long-cwd", *okPath, *noArgs, 1, 4096, 5000, *noFds),
            export("bad-parent", *okPath, *noArgs, *noCwd, 64, 1),
            export("fd-out-of-range", *okPath, *noArgs, *noCwd, 80, 1),
        )
        val wat = """
            (module
              (import "kibi:process/process-poll@0.1.0" "spawn-with-handles" (func ${s}spawnh (param i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32)))
              (import "wasi:cli/stdin@0.2.12" "get-stdin" (func ${s}stdin (result i32)))
              (import "wasi:io/streams@0.2.12" "[resource-drop]input-stream" (func ${s}idrop (param i32)))
              $realloc
              (data (i32.const 32) "/bin/ok")
              (data (i32.const 64) "\05\00\00\00\00\00\00\00\03\00\00\00")
              (data (i32.const 80) "\00\00\00\80\01\00\00\00\00\00\00\00")
              (func ${s}sh
                (param ${s}pp i32) (param ${s}pl i32) (param ${s}ap i32) (param ${s}al i32)
                (param ${s}cd i32) (param ${s}cp i32) (param ${s}cl i32) (param ${s}fp i32) (param ${s}fl i32) (result i32)
                (local ${s}in i32) (local ${s}i i32) (local ${s}r i32)
                (local.set ${s}in (call ${s}stdin))
                (loop ${s}fill
                  (i32.store (i32.add (i32.const 16384) (i32.mul (local.get ${s}i) (i32.const 12))) (local.get ${s}i))
                  (i32.store8 (i32.add (i32.const 16388) (i32.mul (local.get ${s}i) (i32.const 12))) (i32.const 0))
                  (i32.store (i32.add (i32.const 16392) (i32.mul (local.get ${s}i) (i32.const 12))) (local.get ${s}in))
                  (local.set ${s}i (i32.add (local.get ${s}i) (i32.const 1)))
                  (br_if ${s}fill (i32.lt_u (local.get ${s}i) (i32.const 257))))
                (call ${s}spawnh
                  (local.get ${s}pp) (local.get ${s}pl)
                  (local.get ${s}ap) (local.get ${s}al)
                  (i32.const 0) (i32.const 0) (i32.const 0)
                  (local.get ${s}cd) (local.get ${s}cp) (local.get ${s}cl)
                  (local.get ${s}fp) (local.get ${s}fl)
                  (i32.const 16384) (i32.const 257)
                  (i32.const 96))
                (local.set ${s}r (if (result i32) (i32.load8_u (i32.const 96)) (then (i32.load8_u (i32.const 100))) (else (i32.const -1))))
                (call ${s}idrop (local.get ${s}in))
                (local.get ${s}r))
              ${exports.joinToString("\n")})
        """.trimIndent()
        val bytes = KibiP2WitTool.component(
            """
            import kibi:process/process-poll@0.1.0;
            import wasi:cli/stdin@0.2.12;
            import wasi:io/streams@0.2.12;
            ${exports.joinToString("\n") { "export ${Regex("\"(.*?)\"").find(it)!!.groupValues[1]}: func() -> s32;" }}
            """.trimIndent(),
            wat,
        )
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val m = machine(h)
            // 引数の順（path → args → cwd → fds → KibiProcess.options → handles）なので、handles の個数（invalid）より前の誤りが先
            assertEquals(nameTooLong, runGuest(h, m, bytes, "long-path"))
            assertEquals(tooBig, runGuest(h, m, bytes, "many-args"))
            assertEquals(nameTooLong, runGuest(h, m, bytes, "long-cwd"))
            // fds の parent(3) は p2 の規則（request）、fd の範囲は KibiProcess.options。どちらも handles の個数より先
            assertEquals(badDescriptor, runGuest(h, m, bytes, "bad-parent"))
            assertEquals(badDescriptor, runGuest(h, m, bytes, "fd-out-of-range"))
            // ほかに誤りがなければ handles の個数
            assertEquals(inval, runGuest(h, m, bytes, "ok-path"))
            m.stop()
        }
    }

    private class TestDevice(override val methods: DeviceMethods) : Device {
        override val kind: String = "test:device"
    }

    @Test
    fun `invokeの長すぎるmethodより、揃っていないargsのliftのトラップが先`() = timeout(limit) {
        val address = "00000000-0000-4000-8000-0000000000e2"
        val wat = """
            (module
              (import "kibi:device/bus@0.1.0" "[static]device.open" (func ${s}open (param i32 i32 i32)))
              (import "kibi:device/bus@0.1.0" "[method]device.invoke" (func ${s}invoke (param i32 i32 i32 i32 i32 i32)))
              (import "kibi:device/bus@0.1.0" "[resource-drop]device" (func ${s}ddrop (param i32)))
              $realloc
              (data (i32.const 16) "$address")
              ;; method は 4096 からの 65 バイト（上限 64 を超える）。結果 result<list<value>, call-error> は 160
              ;; （call-error の判別子 +4（kernel は 0）、error-code +8）
              (func ${s}inv (param ${s}ap i32) (result i32)
                (local ${s}d i32) (local ${s}r i32)
                (call ${s}open (i32.const 16) (i32.const 36) (i32.const 128))
                (if (i32.load8_u (i32.const 128)) (then (return (i32.const -2))))
                (local.set ${s}d (i32.load (i32.const 132)))
                (call ${s}invoke (local.get ${s}d) (i32.const 4096) (i32.const 65) (local.get ${s}ap) (i32.const 0) (i32.const 160))
                (local.set ${s}r
                  (if (result i32) (i32.and (i32.eq (i32.load8_u (i32.const 160)) (i32.const 1)) (i32.eqz (i32.load8_u (i32.const 164))))
                    (then (i32.load8_u (i32.const 168)))
                    (else (i32.const -1))))
                (call ${s}ddrop (local.get ${s}d))
                (local.get ${s}r))
              (func (export "long-method") (result i32) (call ${s}inv (i32.const 0)))
              (func (export "long-method-misaligned-args") (result i32) (call ${s}inv (i32.const 4))))
        """.trimIndent()
        val bytes = KibiP2WitTool.component(
            """
            import kibi:device/bus@0.1.0;
            export long-method: func() -> s32;
            export long-method-misaligned-args: func() -> s32;
            """.trimIndent(),
            wat,
        )
        Harness().use { h ->
            val m = machine(h)
            m.devices.attach(address, TestDevice(DeviceMethods.builder().direct("x") { _, _ -> arrayOf() }.build()))
            assertEquals(nameTooLong, runGuest(h, m, bytes, "long-method"))
            // value は align 8（p1 は kernel(name-too-long) を先に返す。ADR 0077）
            // トラップで drop まで進まないので、開いた device が 1 つ表に残る（ほかには増えない）
            assertTrap(h, m, bytes, "long-method-misaligned-args", "list の場所が align 8 に揃っていない", table = 1)
            m.stop()
        }
    }

    private companion object {
        private const val ALIGN4 = "list の場所が align 4 に揃っていない"
        private const val DISC = "判別子が範囲の外"
        private const val UTF8 = "文字列が正しい UTF-8 でない"

        /** export の名前と、トラップのメッセージ。括弧の中は、同じ入力で p1 が返す error-code */
        @JvmStatic
        fun liftTraps(): List<Arguments> = listOf(
            // name-too-long
            Arguments.of("long-path-misaligned-args", ALIGN4),
            // too-big
            Arguments.of("many-args-misaligned-fds", ALIGN4),
            // name-too-long
            Arguments.of("long-path-misaligned-env", ALIGN4),
            // name-too-long（判別子だけなら invalid）
            Arguments.of("long-path-bad-cwd-disc", DISC),
            // name-too-long（UTF-8 だけなら invalid）
            Arguments.of("long-path-bad-cwd-utf8", UTF8),
            // name-too-long（判別子だけなら invalid）
            Arguments.of("long-path-bad-fd-source", DISC),
        )
    }
}
