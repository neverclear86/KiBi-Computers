package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.api.device.Device
import pro.ikili.kibicomputers.api.device.DeviceCompletion
import pro.ikili.kibicomputers.api.device.DeviceMethods
import pro.ikili.kibicomputers.api.device.DeviceValue
import pro.ikili.kibicomputers.core.device.PendingDeviceCall
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.process.ComponentSuspend
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.KernelLinkEnv
import pro.ikili.kibicomputers.core.kernel.process.KillSignal
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.time.ManualClock
import pro.ikili.kibicomputers.core.kernel.time.ManualTimerService
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.core.runtime.RuntimeSelfCheck
import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.WasmModule
import pro.ikili.kibicomputers.core.runtime.WasmRuntime
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.component.link.ComponentProgram
import pro.ikili.kibicomputers.core.runtime.component.link.LinkTesting
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.wasi.kibi.KibiErrors
import pro.ikili.kibicomputers.core.wasi.p2.KernelComponentHost
import pro.ikili.kibicomputers.core.wasi.p2.KibiP2WitTool
import pro.ikili.kibicomputers.core.wasi.p2.P2Testing
import java.time.Duration
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * 待機点型（`WAIT_POINT`）の p2 のコマンドが、`kibi:device/bus` の `invoke` と `kibi:process` の `spawn`・`spawn-with-handles` を
 * 巻き戻して待つ（#800。ADR 0800）。待つ間はスレッドもブロッキング型の枠も使わず、巻き直してもメソッド・子は 1 回だけ。
 *
 * コマンドは WAT のコアモジュールから作る（`wasi:cli/run` を export する）。失敗した段は `exit-with-code` の終了コードにする
 */
class KibiP2WaitPointTest {
    private val limit = Duration.ofSeconds(180)
    private val s = "\$"

    private val realloc = """
        (memory (export "memory") 1)
        (global ${s}bump (mut i32) (i32.const 2048))
        (func (export "cabi_realloc") (param ${s}old i32) (param ${s}oldsize i32) (param ${s}align i32) (param ${s}new i32) (result i32)
          (local ${s}p i32)
          (local.set ${s}p (i32.and (i32.add (global.get ${s}bump) (i32.sub (local.get ${s}align) (i32.const 1))) (i32.sub (i32.const 0) (local.get ${s}align))))
          (global.set ${s}bump (i32.add (local.get ${s}p) (local.get ${s}new)))
          (local.get ${s}p))
        (func ${s}fail (param ${s}n i32) (call ${s}exit (local.get ${s}n)) (unreachable))
    """.trimIndent()

    /** テスト用のデバイス。直接のメソッド `echo` と、後で返すメインスレッドのメソッド `defer` */
    private class Dev : Device {
        override val kind: String = "test:dev"
        val calls: MutableMap<String, AtomicInteger> = ConcurrentHashMap()
        val deferred: MutableList<DeviceCompletion> = Collections.synchronizedList(ArrayList())

        fun calls(name: String): Int = calls[name]?.get() ?: 0

        private fun count(name: String) = calls.getOrPut(name) { AtomicInteger() }.incrementAndGet()

        override val methods: DeviceMethods = DeviceMethods.builder()
            .direct("echo") { _, args ->
                count("echo")
                Array(args.size()) { args.get(it) }
            }
            .mainThread("defer") { ctx, _ ->
                count("defer")
                deferred += ctx.defer()
                emptyArray()
            }
            .build()
    }

    /**
     * デバイスのコマンド。`open` → 直接のメソッド `echo([3])`（3 が返る）→ メインスレッドのメソッド `defer` を 2 回（1 回目は 5、2 回目は 6 が返る）。
     * [fill] なら、`echo` の前にホストの表を `open` で満杯にし（最後の失敗は `kernel(mfile)`）、`defer` は `kernel(mfile)` になることを確かめる
     */
    private fun deviceCommand(fill: Boolean): ByteArray {
        val mfile = KibiErrors.code(Errno.MFILE)
        val fillCode = if (!fill) "" else """
            (loop ${s}more
              (call ${s}open (i32.const 16) (i32.const 36) (i32.const 128))
              (br_if ${s}more (i32.eqz (i32.load8_u (i32.const 128)))))
            ;; result<device, call-error> の err: call-error の判別子 +4（kernel = 0）、error-code +8
            (if (i32.load8_u (i32.const 132)) (then (call ${s}fail (i32.const 30))))
            (if (i32.ne (i32.load8_u (i32.const 136)) (i32.const $mfile)) (then (call ${s}fail (i32.const 31))))
        """
        val deferCode = if (fill) """
            (call ${s}invoke (local.get ${s}d) (i32.const 64) (i32.const 5) (i32.const 0) (i32.const 0) (i32.const 160))
            (if (i32.ne (i32.load8_u (i32.const 160)) (i32.const 1)) (then (call ${s}fail (i32.const 32))))
            (if (i32.load8_u (i32.const 164)) (then (call ${s}fail (i32.const 33))))
            (if (i32.ne (i32.load8_u (i32.const 168)) (i32.const $mfile)) (then (call ${s}fail (i32.const 34))))
        """ else """
            (call ${s}defer (local.get ${s}d) (i64.const 5) (i32.const 20))
            (call ${s}defer (local.get ${s}d) (i64.const 6) (i32.const 40))
        """
        val wat = """
            (module
              (import "kibi:device/bus@0.1.0" "[static]device.open" (func ${s}open (param i32 i32 i32)))
              (import "kibi:device/bus@0.1.0" "[method]device.invoke" (func ${s}invoke (param i32 i32 i32 i32 i32 i32)))
              (import "kibi:device/bus@0.1.0" "[resource-drop]device" (func ${s}ddrop (param i32)))
              (import "wasi:cli/exit@0.2.12" "exit-with-code" (func ${s}exit (param i32)))
              $realloc
              (data (i32.const 16) "$ADDRESS")
              (data (i32.const 56) "echo")
              (data (i32.const 64) "defer")
              ;; defer を呼び、結果が [integer(want)] でなければ段 base + n で終わる
              (func ${s}defer (param ${s}d i32) (param ${s}want i64) (param ${s}base i32)
                (local ${s}l i32)
                (call ${s}invoke (local.get ${s}d) (i32.const 64) (i32.const 5) (i32.const 0) (i32.const 0) (i32.const 160))
                (if (i32.load8_u (i32.const 160)) (then (call ${s}fail (i32.add (local.get ${s}base) (i32.const 1)))))
                (if (i32.ne (i32.load (i32.const 168)) (i32.const 1)) (then (call ${s}fail (i32.add (local.get ${s}base) (i32.const 2)))))
                (local.set ${s}l (i32.load (i32.const 164)))
                (if (i32.ne (i32.load8_u (local.get ${s}l)) (i32.const 1)) (then (call ${s}fail (i32.add (local.get ${s}base) (i32.const 3)))))
                (if (i64.ne (i64.load offset=8 (local.get ${s}l)) (local.get ${s}want)) (then (call ${s}fail (i32.add (local.get ${s}base) (i32.const 4))))))
              (func (export "wasi:cli/run@0.2.12#run") (result i32)
                (local ${s}d i32) (local ${s}l i32)
                (call ${s}open (i32.const 16) (i32.const 36) (i32.const 128))
                (if (i32.load8_u (i32.const 128)) (then (call ${s}fail (i32.const 10))))
                (local.set ${s}d (i32.load (i32.const 132)))
                $fillCode
                ;; 直接のメソッド echo([integer 3])（list<value> の 1 つ 16 バイト。判別子 +0、中身 +8）
                (i32.store8 (i32.const 512) (i32.const 1))
                (i64.store (i32.const 520) (i64.const 3))
                (call ${s}invoke (local.get ${s}d) (i32.const 56) (i32.const 4) (i32.const 512) (i32.const 1) (i32.const 160))
                (if (i32.load8_u (i32.const 160)) (then (call ${s}fail (i32.const 11))))
                (if (i32.ne (i32.load (i32.const 168)) (i32.const 1)) (then (call ${s}fail (i32.const 12))))
                (local.set ${s}l (i32.load (i32.const 164)))
                (if (i64.ne (i64.load offset=8 (local.get ${s}l)) (i64.const 3)) (then (call ${s}fail (i32.const 13))))
                $deferCode
                (call ${s}ddrop (local.get ${s}d))
                (i32.const 0)))
        """.trimIndent()
        return KibiP2WitTool.component(
            """
            import kibi:device/bus@0.1.0;
            import wasi:cli/exit@0.2.12;
            export wasi:cli/run@0.2.12;
            """.trimIndent(),
            wat,
        )
    }

    /**
     * 子を作るコマンド。パイプを作り、`spawn-with-handles` で `/bin/hello` の標準出力をパイプの書く端にして、読む端から 16 バイト読む。
     * 続けて `spawn` で `/bin/ok` を作り、どちらのハンドルも drop する
     */
    private val spawnCommand: ByteArray by lazy {
        val wat = """
            (module
              (import "kibi:io/pipe-streams@0.1.0" "create" (func ${s}pipe (param i32)))
              (import "kibi:process/process-poll@0.1.0" "spawn-with-handles" (func ${s}spawnh (param i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32)))
              (import "kibi:process/process@0.1.0" "[static]process.spawn" (func ${s}spawn (param i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32)))
              (import "kibi:process/process@0.1.0" "[resource-drop]process" (func ${s}procdrop (param i32)))
              (import "wasi:io/streams@0.2.12" "[method]input-stream.blocking-read" (func ${s}read (param i32 i64 i32)))
              (import "wasi:io/streams@0.2.12" "[resource-drop]input-stream" (func ${s}idrop (param i32)))
              (import "wasi:io/streams@0.2.12" "[resource-drop]output-stream" (func ${s}odrop (param i32)))
              (import "wasi:cli/exit@0.2.12" "exit-with-code" (func ${s}exit (param i32)))
              $realloc
              (data (i32.const 16) "/bin/hello")
              (data (i32.const 32) "/bin/ok")
              (func (export "wasi:cli/run@0.2.12#run") (result i32)
                (local ${s}in i32) (local ${s}out i32) (local ${s}c i32) (local ${s}total i32)
                (call ${s}pipe (i32.const 64))
                (if (i32.load8_u (i32.const 64)) (then (call ${s}fail (i32.const 1))))
                (local.set ${s}in (i32.load (i32.const 68)))
                (local.set ${s}out (i32.load (i32.const 72)))
                ;; handles = [{ fd: 1, source: output(borrow out) }]
                (i32.store (i32.const 48) (i32.const 1))
                (i32.store8 (i32.const 52) (i32.const 1))
                (i32.store (i32.const 56) (local.get ${s}out))
                (call ${s}spawnh
                  (i32.const 16) (i32.const 10)
                  (i32.const 0) (i32.const 0)
                  (i32.const 0) (i32.const 0) (i32.const 0)
                  (i32.const 0) (i32.const 0) (i32.const 0)
                  (i32.const 0) (i32.const 0)
                  (i32.const 48) (i32.const 1)
                  (i32.const 96))
                (if (i32.load8_u (i32.const 96)) (then (call ${s}fail (i32.add (i32.const 100) (i32.load8_u (i32.const 100))))))
                (local.set ${s}c (i32.load (i32.const 100)))
                (call ${s}odrop (local.get ${s}out))
                (loop ${s}more
                  (call ${s}read (local.get ${s}in) (i64.const 256) (i32.const 128))
                  (if (i32.eqz (i32.load8_u (i32.const 128)))
                    (then
                      (local.set ${s}total (i32.add (local.get ${s}total) (i32.load (i32.const 136))))
                      (br ${s}more))))
                (if (i32.ne (local.get ${s}total) (i32.const 16)) (then (call ${s}fail (i32.const 3))))
                (call ${s}idrop (local.get ${s}in))
                (call ${s}procdrop (local.get ${s}c))
                (call ${s}spawn
                  (i32.const 32) (i32.const 7)
                  (i32.const 0) (i32.const 0)
                  (i32.const 0) (i32.const 0) (i32.const 0)
                  (i32.const 0) (i32.const 0) (i32.const 0)
                  (i32.const 0) (i32.const 0)
                  (i32.const 96))
                (if (i32.load8_u (i32.const 96)) (then (call ${s}fail (i32.add (i32.const 200) (i32.load8_u (i32.const 100))))))
                (call ${s}procdrop (i32.load (i32.const 100)))
                (i32.const 0)))
        """.trimIndent()
        KibiP2WitTool.component(
            """
            import kibi:io/pipe-streams@0.1.0;
            import kibi:process/process@0.1.0;
            import kibi:process/process-poll@0.1.0;
            import wasi:io/streams@0.2.12;
            import wasi:cli/exit@0.2.12;
            export wasi:cli/run@0.2.12;
            """.trimIndent(),
            wat,
        )
    }

    /**
     * `invoke` の失敗の道を確かめるコマンド。`open` の後、メソッド名が長すぎる（`name-too-long`）・引数が多すぎる（`too-big`）は待たずに `kernel(...)` で返り、
     * メインスレッドのメソッド `defer` は 1 回目が `failed(..)`（デバイスが失敗を返した）、2 回目が `detached`（呼ぶ前にデバイスが外れた）で返る。
     * 最後に、来ない時計の pollable を `block` で待つ（その間に、失敗の結果を受け取った後の表を確かめる）。
     * 食い違った段は `exit-with-code` の終了コードにする
     */
    private fun deviceErrorCommand(): ByteArray {
        val nameTooLong = KibiErrors.code(Errno.NAMETOOLONG)
        val tooBig = KibiErrors.code(Errno.TOOBIG)
        val wat = """
            (module
              (import "kibi:device/bus@0.1.0" "[static]device.open" (func ${s}open (param i32 i32 i32)))
              (import "kibi:device/bus@0.1.0" "[method]device.invoke" (func ${s}invoke (param i32 i32 i32 i32 i32 i32)))
              (import "kibi:device/bus@0.1.0" "[resource-drop]device" (func ${s}ddrop (param i32)))
              (import "wasi:clocks/monotonic-clock@0.2.12" "subscribe-duration" (func ${s}after (param i64) (result i32)))
              (import "wasi:io/poll@0.2.12" "[method]pollable.block" (func ${s}pblock (param i32)))
              (import "wasi:cli/exit@0.2.12" "exit-with-code" (func ${s}exit (param i32)))
              $realloc
              (data (i32.const 16) "$ADDRESS")
              (data (i32.const 56) "echo")
              (data (i32.const 64) "defer")
              ;; 結果が err(call-error) で、call-error の判別子が disc（kernel(0) なら error-code が code）でなければ、段 stage（+1、+2）で終わる
              (func ${s}expect (param ${s}disc i32) (param ${s}code i32) (param ${s}stage i32)
                (if (i32.ne (i32.load8_u (i32.const 160)) (i32.const 1)) (then (call ${s}fail (local.get ${s}stage))))
                (if (i32.ne (i32.load8_u (i32.const 164)) (local.get ${s}disc)) (then (call ${s}fail (i32.add (local.get ${s}stage) (i32.const 1)))))
                (if (i32.eqz (local.get ${s}disc))
                  (then (if (i32.ne (i32.load8_u (i32.const 168)) (local.get ${s}code)) (then (call ${s}fail (i32.add (local.get ${s}stage) (i32.const 2))))))))
              (func (export "wasi:cli/run@0.2.12#run") (result i32)
                (local ${s}d i32)
                (call ${s}open (i32.const 16) (i32.const 36) (i32.const 128))
                (if (i32.load8_u (i32.const 128)) (then (call ${s}fail (i32.const 10))))
                (local.set ${s}d (i32.load (i32.const 132)))
                ;; method が 64 バイトを超える（0 のバイトは正しい UTF-8）
                (call ${s}invoke (local.get ${s}d) (i32.const 1024) (i32.const 65) (i32.const 0) (i32.const 0) (i32.const 160))
                (call ${s}expect (i32.const 0) (i32.const $nameTooLong) (i32.const 20))
                ;; 引数が 32 個を超える（0 の判別子は none）
                (call ${s}invoke (local.get ${s}d) (i32.const 56) (i32.const 4) (i32.const 4096) (i32.const 33) (i32.const 160))
                (call ${s}expect (i32.const 0) (i32.const $tooBig) (i32.const 30))
                ;; defer: 1 回目は failed（判別子 4）、2 回目は detached（1）
                (call ${s}invoke (local.get ${s}d) (i32.const 64) (i32.const 5) (i32.const 0) (i32.const 0) (i32.const 160))
                (call ${s}expect (i32.const 4) (i32.const 0) (i32.const 40))
                (call ${s}invoke (local.get ${s}d) (i32.const 64) (i32.const 5) (i32.const 0) (i32.const 0) (i32.const 160))
                (call ${s}expect (i32.const 1) (i32.const 0) (i32.const 50))
                ;; 来ない時計（u64 の最大）を待つ。テストが表を確かめてから殺す
                (call ${s}pblock (call ${s}after (i64.const -1)))
                (call ${s}ddrop (local.get ${s}d))
                (i32.const 0)))
        """.trimIndent()
        return KibiP2WitTool.component(
            """
            import kibi:device/bus@0.1.0;
            import wasi:clocks/monotonic-clock@0.2.12;
            import wasi:io/poll@0.2.12;
            import wasi:cli/exit@0.2.12;
            export wasi:cli/run@0.2.12;
            """.trimIndent(),
            wat,
        )
    }

    /**
     * `spawn`・`spawn-with-handles` の失敗の道を確かめるコマンド。順に、(1) 存在しない実行ファイル（`no-entry`。読み込みの前）、
     * (2) `handles` に閉じた標準入力の stream（`bad-descriptor`。読み込みの前）、(3) WASM として読めない実行ファイル（読み込みを待って巻き直した後に
     * `not-executable`）、(4) ホストの表を `open` で満杯にして `spawn`（`too-many-open-files`。読み込みの前に断る）。食い違った段は終了コードにする
     */
    private val spawnErrorCommand: ByteArray by lazy {
        val noent = KibiErrors.code(Errno.NOENT)
        val badf = KibiErrors.code(Errno.BADF)
        val noexec = KibiErrors.code(Errno.NOEXEC)
        val mfile = KibiErrors.code(Errno.MFILE)
        val wat = """
            (module
              (import "kibi:device/bus@0.1.0" "[static]device.open" (func ${s}open (param i32 i32 i32)))
              (import "kibi:process/process-poll@0.1.0" "spawn-with-handles" (func ${s}spawnh (param i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32)))
              (import "kibi:process/process@0.1.0" "[static]process.spawn" (func ${s}spawn (param i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32)))
              (import "wasi:cli/stdin@0.2.12" "get-stdin" (func ${s}stdin (result i32)))
              (import "wasi:cli/exit@0.2.12" "exit-with-code" (func ${s}exit (param i32)))
              $realloc
              (data (i32.const 16) "/bin/none")
              (data (i32.const 32) "/bin/ok")
              (data (i32.const 40) "/bin/bad")
              (data (i32.const 256) "$ADDRESS")
              ;; 結果が err(error-code) で、error-code が code でなければ、段 stage（+1）で終わる
              (func ${s}expect (param ${s}code i32) (param ${s}stage i32)
                (if (i32.ne (i32.load8_u (i32.const 96)) (i32.const 1)) (then (call ${s}fail (local.get ${s}stage))))
                (if (i32.ne (i32.load8_u (i32.const 100)) (local.get ${s}code)) (then (call ${s}fail (i32.add (local.get ${s}stage) (i32.const 1))))))
              (func ${s}spawnPath (param ${s}ptr i32) (param ${s}len i32)
                (call ${s}spawn
                  (local.get ${s}ptr) (local.get ${s}len)
                  (i32.const 0) (i32.const 0)
                  (i32.const 0) (i32.const 0) (i32.const 0)
                  (i32.const 0) (i32.const 0) (i32.const 0)
                  (i32.const 0) (i32.const 0)
                  (i32.const 96)))
              (func (export "wasi:cli/run@0.2.12#run") (result i32)
                (local ${s}in i32)
                (call ${s}spawnPath (i32.const 16) (i32.const 9))
                (call ${s}expect (i32.const $noent) (i32.const 10))
                ;; handles = [{ fd: 0, source: input(borrow stdin) }]。標準入力は閉じている
                (local.set ${s}in (call ${s}stdin))
                (i32.store (i32.const 48) (i32.const 0))
                (i32.store8 (i32.const 52) (i32.const 0))
                (i32.store (i32.const 56) (local.get ${s}in))
                (call ${s}spawnh
                  (i32.const 32) (i32.const 7)
                  (i32.const 0) (i32.const 0)
                  (i32.const 0) (i32.const 0) (i32.const 0)
                  (i32.const 0) (i32.const 0) (i32.const 0)
                  (i32.const 0) (i32.const 0)
                  (i32.const 48) (i32.const 1)
                  (i32.const 96))
                (call ${s}expect (i32.const $badf) (i32.const 20))
                ;; 読み込みを待つ（ここで巻き戻る）。読めない実行ファイルなので、巻き直した後に失敗する
                (call ${s}spawnPath (i32.const 40) (i32.const 8))
                (call ${s}expect (i32.const $noexec) (i32.const 30))
                ;; 表を満杯にして spawn（読み込みの前の確かめで断る）
                (loop ${s}more
                  (call ${s}open (i32.const 256) (i32.const 36) (i32.const 128))
                  (br_if ${s}more (i32.eqz (i32.load8_u (i32.const 128)))))
                (call ${s}spawnPath (i32.const 32) (i32.const 7))
                (call ${s}expect (i32.const $mfile) (i32.const 40))
                (i32.const 0)))
        """.trimIndent()
        KibiP2WitTool.component(
            """
            import kibi:device/bus@0.1.0;
            import kibi:process/process@0.1.0;
            import kibi:process/process-poll@0.1.0;
            import wasi:cli/stdin@0.2.12;
            import wasi:cli/exit@0.2.12;
            export wasi:cli/run@0.2.12;
            """.trimIndent(),
            wat,
        )
    }

    /**
     * 待機点型の `spawn` の lift の順（#616。ADR 0616）を確かめるコマンド。`/bin/ok` を 1 回 spawn して（読み込みを巻き戻して待つ）、
     * 次に長すぎる path と揃っていない args の ptr で spawn する。後のほうは lift でトラップになる（p1 は `name-too-long`）
     */
    private val spawnLiftTrapCommand: ByteArray by lazy {
        val wat = """
            (module
              (import "kibi:process/process@0.1.0" "[static]process.spawn" (func ${s}spawn (param i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32)))
              (import "kibi:process/process@0.1.0" "[resource-drop]process" (func ${s}procdrop (param i32)))
              (import "wasi:cli/exit@0.2.12" "exit-with-code" (func ${s}exit (param i32)))
              $realloc
              (data (i32.const 32) "/bin/ok")
              (func ${s}sp (param ${s}pp i32) (param ${s}pl i32) (param ${s}ap i32)
                (call ${s}spawn
                  (local.get ${s}pp) (local.get ${s}pl)
                  (local.get ${s}ap) (i32.const 0)
                  (i32.const 0) (i32.const 0) (i32.const 0)
                  (i32.const 0) (i32.const 0) (i32.const 0)
                  (i32.const 0) (i32.const 0)
                  (i32.const 96)))
              (func (export "wasi:cli/run@0.2.12#run") (result i32)
                (call ${s}sp (i32.const 32) (i32.const 7) (i32.const 0))
                (if (i32.load8_u (i32.const 96)) (then (call ${s}fail (i32.const 10))))
                (call ${s}procdrop (i32.load (i32.const 100)))
                ;; path は 4096 からの 5000 バイト（上限 4096 を超える）、args の ptr は 17（align 4 に揃っていない）
                (call ${s}sp (i32.const 4096) (i32.const 5000) (i32.const 17))
                (call ${s}fail (i32.const 20))
                (i32.const 0)))
        """.trimIndent()
        KibiP2WitTool.component(
            """
            import kibi:process/process@0.1.0;
            import wasi:cli/exit@0.2.12;
            export wasi:cli/run@0.2.12;
            """.trimIndent(),
            wat,
        )
    }

    /**
     * `invoke` の「起きないはず」の守りを確かめるコマンド。`defer` を呼んで `failed("interrupted")` を受け取り（テストが待ちを忘れさせる）、
     * 時計で 100 ms 待ち（手で進める時計。テストが古い待ちを戻してから進める）、もう一度 `defer` を呼んで ok を受け取る。食い違った段は終了コードにする
     */
    private val deviceGuardCommand: ByteArray by lazy {
        val wat = """
            (module
              (import "kibi:device/bus@0.1.0" "[static]device.open" (func ${s}open (param i32 i32 i32)))
              (import "kibi:device/bus@0.1.0" "[method]device.invoke" (func ${s}invoke (param i32 i32 i32 i32 i32 i32)))
              (import "kibi:device/bus@0.1.0" "[resource-drop]device" (func ${s}ddrop (param i32)))
              (import "wasi:clocks/monotonic-clock@0.2.12" "subscribe-duration" (func ${s}after (param i64) (result i32)))
              (import "wasi:io/poll@0.2.12" "[method]pollable.block" (func ${s}pblock (param i32)))
              (import "wasi:io/poll@0.2.12" "[resource-drop]pollable" (func ${s}pdrop (param i32)))
              (import "wasi:cli/exit@0.2.12" "exit-with-code" (func ${s}exit (param i32)))
              $realloc
              (data (i32.const 16) "$ADDRESS")
              (data (i32.const 64) "defer")
              (data (i32.const 80) "interrupted")
              (func (export "wasi:cli/run@0.2.12#run") (result i32)
                (local ${s}d i32) (local ${s}p i32) (local ${s}i i32)
                (call ${s}open (i32.const 16) (i32.const 36) (i32.const 128))
                (if (i32.load8_u (i32.const 128)) (then (call ${s}fail (i32.const 10))))
                (local.set ${s}d (i32.load (i32.const 132)))
                ;; 1 回目: err(failed("interrupted"))（call-error の判別子 +4 が 4、文字列 +8・+12）
                (call ${s}invoke (local.get ${s}d) (i32.const 64) (i32.const 5) (i32.const 0) (i32.const 0) (i32.const 160))
                (if (i32.ne (i32.load8_u (i32.const 160)) (i32.const 1)) (then (call ${s}fail (i32.const 20))))
                (if (i32.ne (i32.load8_u (i32.const 164)) (i32.const 4)) (then (call ${s}fail (i32.const 21))))
                (if (i32.ne (i32.load (i32.const 172)) (i32.const 11)) (then (call ${s}fail (i32.const 22))))
                (loop ${s}cmp
                  (if (i32.ne (i32.load8_u (i32.add (i32.load (i32.const 168)) (local.get ${s}i))) (i32.load8_u (i32.add (i32.const 80) (local.get ${s}i))))
                    (then (call ${s}fail (i32.const 23))))
                  (local.set ${s}i (i32.add (local.get ${s}i) (i32.const 1)))
                  (br_if ${s}cmp (i32.lt_u (local.get ${s}i) (i32.const 11))))
                ;; 時計で 100 ms 待つ
                (local.set ${s}p (call ${s}after (i64.const 100000000)))
                (call ${s}pblock (local.get ${s}p))
                (call ${s}pdrop (local.get ${s}p))
                ;; 2 回目: ok
                (call ${s}invoke (local.get ${s}d) (i32.const 64) (i32.const 5) (i32.const 0) (i32.const 0) (i32.const 160))
                (if (i32.load8_u (i32.const 160)) (then (call ${s}fail (i32.const 30))))
                (call ${s}ddrop (local.get ${s}d))
                (i32.const 0)))
        """.trimIndent()
        KibiP2WitTool.component(
            """
            import kibi:device/bus@0.1.0;
            import wasi:clocks/monotonic-clock@0.2.12;
            import wasi:io/poll@0.2.12;
            import wasi:cli/exit@0.2.12;
            export wasi:cli/run@0.2.12;
            """.trimIndent(),
            wat,
        )
    }

    /**
     * `spawn` の「起きないはず」の守りを確かめるコマンド。`/bin/ok` を `spawn`（読み込みで巻き戻る。テストが待ちを忘れさせる）し、
     * 時計で 100 ms 待ち（手で進める時計。テストが古い待ちを戻してから進める）、子のハンドルを drop して `/bin/none` を `spawn`（`no-entry`）し、来ない時計で待つ。
     * 食い違った段は終了コードにする
     */
    private val spawnGuardCommand: ByteArray by lazy {
        val noent = KibiErrors.code(Errno.NOENT)
        val wat = """
            (module
              (import "kibi:process/process@0.1.0" "[static]process.spawn" (func ${s}spawn (param i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32 i32)))
              (import "kibi:process/process@0.1.0" "[resource-drop]process" (func ${s}procdrop (param i32)))
              (import "wasi:clocks/monotonic-clock@0.2.12" "subscribe-duration" (func ${s}after (param i64) (result i32)))
              (import "wasi:io/poll@0.2.12" "[method]pollable.block" (func ${s}pblock (param i32)))
              (import "wasi:io/poll@0.2.12" "[resource-drop]pollable" (func ${s}pdrop (param i32)))
              (import "wasi:cli/exit@0.2.12" "exit-with-code" (func ${s}exit (param i32)))
              $realloc
              (data (i32.const 16) "/bin/none")
              (data (i32.const 32) "/bin/ok")
              (func ${s}spawnPath (param ${s}ptr i32) (param ${s}len i32)
                (call ${s}spawn
                  (local.get ${s}ptr) (local.get ${s}len)
                  (i32.const 0) (i32.const 0)
                  (i32.const 0) (i32.const 0) (i32.const 0)
                  (i32.const 0) (i32.const 0) (i32.const 0)
                  (i32.const 0) (i32.const 0)
                  (i32.const 96)))
              (func (export "wasi:cli/run@0.2.12#run") (result i32)
                (local ${s}c i32) (local ${s}p i32)
                (call ${s}spawnPath (i32.const 32) (i32.const 7))
                (if (i32.load8_u (i32.const 96)) (then (call ${s}fail (i32.add (i32.const 100) (i32.load8_u (i32.const 100))))))
                (local.set ${s}c (i32.load (i32.const 100)))
                (local.set ${s}p (call ${s}after (i64.const 100000000)))
                (call ${s}pblock (local.get ${s}p))
                (call ${s}pdrop (local.get ${s}p))
                (call ${s}procdrop (local.get ${s}c))
                (call ${s}spawnPath (i32.const 16) (i32.const 9))
                (if (i32.ne (i32.load8_u (i32.const 96)) (i32.const 1)) (then (call ${s}fail (i32.const 20))))
                (if (i32.ne (i32.load8_u (i32.const 100)) (i32.const $noent)) (then (call ${s}fail (i32.const 21))))
                ;; 来ない時計（u64 の最大）を待つ。テストが確かめてから殺す
                (call ${s}pblock (call ${s}after (i64.const -1)))
                (i32.const 0)))
        """.trimIndent()
        KibiP2WitTool.component(
            """
            import kibi:process/process@0.1.0;
            import wasi:clocks/monotonic-clock@0.2.12;
            import wasi:io/poll@0.2.12;
            import wasi:cli/exit@0.2.12;
            export wasi:cli/run@0.2.12;
            """.trimIndent(),
            wat,
        )
    }

    /** 読み込みを [gate] が開くまで止めるランタイム（子の読み込みを確実に待たせる） */
    private class GatedRuntime(private val inner: WasmRuntime) : WasmRuntime {
        val gate = CountDownLatch(1)
        val loads = AtomicInteger()

        /** 立てると、門が開いた後の読み込みが `WasmLoadException`（WASM として読めない）で失敗する */
        @Volatile
        var failLoad = false
        override val name: String get() = "gated-" + inner.name

        override fun load(bytes: ByteArray): WasmModule {
            loads.incrementAndGet()
            gate.await()
            if (failLoad) throw WasmLoadException("test: WASM として読めない")
            return inner.load(bytes)
        }
    }

    /**
     * [bytes] のコマンドを待機点型のプロセスとして動かす。
     * [timers] は、台が手で進める時計（[manualTimers]）を使うときの、その時計のタイマー。
     * テストのスレッドがカーネルロックの外で `advance` して、時計の待ちを起こす
     */
    private class Run(val h: Harness, val m: Machine, bytes: ByteArray, val timers: ManualTimerService? = null) {
        val program: Program = Program.component(ComponentProgram.load(LinkTesting.runtime, bytes, KernelComponentHost))
        val pid: Int = m.spawn(program, KernelDrivers.DEFAULT.create(program), SpawnOptions(stdio = StdioSpec(StdioSource.Closed, StdioSource.Closed, StdioSource.Closed)))

        val rt get() = m.rt(pid)
        private var cachedSuspend: ComponentSuspend? = null

        /** プロセスの [ComponentSuspend]（作り終えた後に初めて引いたものを覚える。終わった後も読めるように） */
        val suspend: ComponentSuspend
            get() = cachedSuspend ?: (rt.component!!.env as KernelLinkEnv).suspend.also { cachedSuspend = it }

        fun state(): ProcessState = m.stateOf(pid)

        fun tickUntil(message: String, condition: () -> Boolean) = eventually(60_000, message) {
            h.scheduler.tick()
            condition()
        }

        fun hostResources(): Int = m.lock.withLockValue { m.kernel.processes[pid]!!.hostResources.size }

        /** 待機点で、スレッドもブロッキング型の枠も使わずに待っている。[clock] なら時計の待ち（やり直しの値に期限を持つ） */
        fun assertParked(clock: Boolean = false) {
            assertEquals(ProcessKind.WAIT_POINT, m.lock.withLockValue { m.kernel.processes[pid]!!.kind })
            assertTrue(program.component!!.waitPoints, "${program.component!!.waitPointRejection}")
            eventually(5_000, "スレッドが外れない") { rt.thread == null }
            assertTrue(h.scheduler.dedicatedThreads.isEmpty(), "専用スレッドがある")
            assertEquals(0, h.scheduler.blockingProcesses, "ブロッキング型の枠を使っている")
            assertTrue(m.lock.withLockValue { m.kernel.processes[pid]!!.asyncStack!!.size } > 0, "退避した値がない")
            // kibi の待ちは実行時だけの状態で、カーネルの AsyncWait には入れない
            if (!clock) assertNull(m.lock.withLockValue { m.kernel.processes[pid]!!.asyncWait })
            assertTrue(m.isAtWaitPoint, "待機点でない")
        }

        fun finish(expected: ProcessState = ProcessState.EXITED, code: Int = 0) {
            tickUntil("終わらない: ${state()}") { state().isTerminal }
            assertEquals(expected, state(), "${rt.lastError}")
            if (expected == ProcessState.EXITED) assertEquals(code, m.lock.withLockValue { m.kernel.processes[pid]?.exitCode }, "段 ${m.lock.withLockValue { m.kernel.processes[pid]?.exitCode }} で失敗した")
        }
    }

    private fun pendingCallObjects(m: Machine): Int = m.lock.withLockValue {
        var n = 0
        m.kernel.objects.forEachObject { if (it is PendingDeviceCall) n++ }
        n
    }

    /** 手で進める時計のタイマー。時計の待ちを、テストが [ManualTimerService.advance] するまで起こさない */
    private fun manualTimers() = ManualTimerService(ManualClock())

    /** [timers] があれば、台の時計とタイマーをそれにする */
    private fun Harness.machineWith(timers: ManualTimerService?): Machine =
        if (timers == null) {
            machine(fuelPerTick = 10_000_000)
        } else {
            machine(fuelPerTick = 10_000_000, environment = MachineEnvironment.builder().clock(timers.clock).build())
        }

    private fun device(timers: ManualTimerService? = null, block: (Harness, Machine, Dev) -> Unit) = Harness(execRuntime = EndiveRuntime(), timers = timers).use { h ->
        val m = h.machineWith(timers)
        val dev = Dev()
        assertTrue(m.devices.attach(ADDRESS, dev))
        block(h, m, dev)
    }

    /** `defer` の [n] 回目の呼び出しで、巻き戻して待つまで進める */
    private fun Run.awaitDefer(dev: Dev, n: Int) = tickUntil("defer で待たない: ${state()} ${rt.lastError}") {
        dev.deferred.size == n && state() == ProcessState.WAITING_EVENT && suspend.pendingInvoke != null
    }

    @Test
    fun `メインスレッドのメソッドのinvokeは巻き戻して待ち、メソッドは1回だけ走り、終わればpending-callを外す`() = timeout(limit) {
        device { h, m, dev ->
            val r = Run(h, m, deviceCommand(fill = false))
            r.awaitDefer(dev, 1)
            r.assertParked()
            // device と、隠れた pending-call
            assertEquals(2, r.hostResources())
            assertEquals(1, pendingCallObjects(m))
            // 起こしても結果がまだなら、同じ key で巻き戻し直す（メソッドを呼び直さない）
            m.lock.withLockValue { m.wakeSink.wake(r.suspend.pendingInvoke!!.key) }
            repeat(20) { h.scheduler.tick() }
            assertEquals(1, dev.calls("defer"))
            dev.deferred[0].complete(DeviceValue.of(5L))
            // 2 回目の defer で待つ。1 回目の pending-call は外れていて、表の大きさは元に戻っている
            r.awaitDefer(dev, 2)
            r.assertParked()
            assertEquals(2, r.hostResources(), "1 回目の pending-call が残った")
            assertEquals(1, pendingCallObjects(m))
            dev.deferred[1].complete(DeviceValue.of(6L))
            r.finish()
            assertEquals(1, dev.calls("echo"), "直接のメソッド")
            assertEquals(2, dev.calls("defer"), "巻き直しでメソッドが二重に走った")
            assertNull(r.suspend.pendingInvoke)
            assertEquals(0, pendingCallObjects(m))
        }
    }

    @Test
    fun `invokeで待っているプロセスをkillすると、呼び出しは取り消される`() = timeout(limit) {
        device { h, m, dev ->
            val r = Run(h, m, deviceCommand(fill = false))
            r.awaitDefer(dev, 1)
            m.lock.withLockValue { m.killLocked(r.pid, KillSignal.KILL) }
            r.finish(ProcessState.KILLED)
            assertTrue(dev.deferred.single().isCancelled, "結果が要らなくなったら取り消す")
            assertEquals(0, pendingCallObjects(m))
        }
    }

    @Test
    fun `ホストの表が満杯なら、待機点型のメインスレッドのメソッドのinvokeはmfileで、直接のメソッドは呼べる`() = timeout(limit) {
        device { h, m, dev ->
            val r = Run(h, m, deviceCommand(fill = true))
            r.finish()
            assertEquals(1, dev.calls("echo"))
            assertEquals(0, dev.calls("defer"), "満杯で断ったのにメソッドを走らせた")
        }
    }

    @Test
    fun `invokeで待っている台の写しでは、プロセスを殺しpending-callを残さず、元の呼び出しは取り消さない`() = timeout(limit) {
        device { h, m, dev ->
            val r = Run(h, m, deviceCommand(fill = false))
            r.awaitDefer(dev, 1)
            r.assertParked()
            val copy = m.captureCopy(CapturePolicy.KILL_TRANSIENT)!!
            assertTrue(r.pid in copy.killedPids, "写しの中で殺していない")
            copy.processes[r.pid]?.let { p ->
                assertTrue(p.asyncStack?.isEmpty != false, "写しに退避した値が残った")
                assertNull(p.asyncWait)
                assertEquals(0, p.hostResources.size, "写しにホストの resource が残った")
            }
            var copied = 0
            copy.objects.forEachObject { if (it is PendingDeviceCall) copied++ }
            assertEquals(0, copied, "写しに pending-call が残った")
            // 元はそのまま待ち、結果を受け取って続ける
            assertFalse(dev.deferred.single().isCancelled, "写しの後始末が元の呼び出しを取り消した")
            assertNotNull(r.suspend.pendingInvoke)
            dev.deferred[0].complete(DeviceValue.of(5L))
            r.awaitDefer(dev, 2)
            dev.deferred[1].complete(DeviceValue.of(6L))
            r.finish()
            assertEquals(2, dev.calls("defer"))
        }
    }

    private fun writeFile(m: Machine, path: String, bytes: ByteArray) {
        val f = m.vfs.open(VfsPath.parse(path), OpenOptions(read = true, write = true, create = true, truncate = true))
        m.vfs.write(f, bytes, 0, bytes.size)
    }

    /**
     * 子の読み込みを止めたまま、親が `spawn-with-handles` で待つところまで進める。
     * [manualClock] なら、台の時計を手で進める時計にする（[Run.timers]。時計の待ちはテストが進めるまで起きない）
     */
    private fun gated(command: ByteArray = spawnCommand, manualClock: Boolean = false, block: Run.(GatedRuntime) -> Unit) = timeout(limit) {
        val runtime = GatedRuntime(EndiveRuntime())
        val timers = if (manualClock) manualTimers() else null
        Harness(execRuntime = runtime, timers = timers).use { h ->
            try {
                val m = h.machineWith(timers)
                m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
                m.vfs.createDirectory(VfsPath.parse("/bin"))
                writeFile(m, "/bin/hello", P2Testing.wrap("hello-wasi"))
                writeFile(m, "/bin/ok", RuntimeSelfCheck.RUN_COMMAND_COMPONENT)
                // 読み込みだけが失敗する実行ファイル（読み込みを失敗させるのは GatedRuntime.failLoad）
                writeFile(m, "/bin/bad", RuntimeSelfCheck.RUN_COMMAND_COMPONENT)
                assertTrue(m.devices.attach(ADDRESS, Dev()))
                val r = Run(h, m, command, timers)
                r.tickUntil("読み込みに入らない") { runtime.loads.get() > 0 || r.state().isTerminal }
                assertTrue(runtime.loads.get() > 0, "読み込みに入る前に終わった: ${r.state()} 終了コード ${m.lock.withLockValue { m.kernel.processes[r.pid]?.exitCode }} ${r.rt.lastError}")
                r.tickUntil("spawn で待たない: ${r.state()}") { r.state() == ProcessState.WAITING_EVENT && r.suspend.pendingSpawn != null }
                r.block(runtime)
            } finally {
                runtime.gate.countDown()
            }
        }
    }

    private fun Run.others(): Set<Int> = m.lock.withLockValue { m.kernel.processes.all().map { it.pid }.toSet() - pid }

    @Test
    fun `spawn-with-handlesとspawnは読み込みを巻き戻して待ち、開くと子を1つずつ作る`() = gated { runtime ->
        assertParked()
        // 子はまだない（ハンドルも置いていない。パイプの 2 つの端だけ）
        assertEquals(emptySet<Int>(), others())
        assertEquals(2, hostResources())
        runtime.gate.countDown()
        // 子の標準出力をパイプにでき、読み終えるまで子は 1 つだけ（16 バイトちょうどを読む）
        finish()
        assertNull(suspend.pendingSpawn)
        // 作った子は 2 つ（pid は親の後の 2 つ）だけで、どちらも終わる（drop したハンドルの子は刈り取られるか、親のない終わったプロセスとして残る）
        tickUntil("子が終わらない: ${others()}") { m.lock.withLockValue { others().all { m.kernel.processes[it]?.state?.isTerminal != false } } }
        assertTrue(others().all { it == pid + 1 || it == pid + 2 }, "余計な子がある: ${others()}")
    }

    @Test
    fun `spawnで待っている親をkillすると、読み込みが終わっても子を作らない`() = gated { runtime ->
        m.lock.withLockValue { m.killLocked(pid, KillSignal.KILL) }
        finish(ProcessState.KILLED)
        runtime.gate.countDown()
        repeat(50) {
            h.scheduler.tick()
            Thread.sleep(5)
        }
        assertEquals(emptySet<Int>(), others())
    }

    @Test
    fun `spawnで待っている台の写しでは、プロセスを殺し、元の読み込みはそのまま続く`() = gated { runtime ->
        assertParked()
        val copy = m.captureCopy(CapturePolicy.KILL_TRANSIENT)!!
        assertTrue(pid in copy.killedPids, "写しの中で殺していない")
        copy.processes[pid]?.let { p ->
            assertNull(p.asyncWait)
            assertEquals(0, p.hostResources.size, "写しにホストの resource が残った")
        }
        assertNotNull(suspend.pendingSpawn)
        runtime.gate.countDown()
        finish()
    }

    @Test
    fun `invokeの失敗は巻き戻す道でもerr(call-error)で返る`() = timeout(limit) {
        device { h, m, dev ->
            val detach = AtomicBoolean()
            // 2 回目の defer は、デバイスを引いた後・メソッドを呼ぶ前に外す
            h.scheduler.hooks.afterDeviceInvokeResolve = { machine, _ -> if (detach.get()) machine.devices.detach(ADDRESS) }
            val r = Run(h, m, deviceErrorCommand())
            // 引数の誤り（name-too-long・too-big）は待たずに返り、defer で初めて待つ
            r.awaitDefer(dev, 1)
            r.assertParked()
            detach.set(true)
            // デバイスの失敗: 巻き直して着いた後に failed(..) で返る
            dev.deferred[0].fail("boom")
            // 段を最後まで通って、来ない時計で待つ（表は device と pollable の 2 つ。pending-call が残っていれば 3 以上）
            r.tickUntil("時計で待たない: ${r.state()}") {
                r.state().isTerminal || (r.state() == ProcessState.WAITING_EVENT && r.suspend.pendingInvoke == null)
            }
            assertFalse(r.state().isTerminal, "時計で待つ前に終わった: 終了コード ${m.lock.withLockValue { m.kernel.processes[r.pid]?.exitCode }} ${r.rt.lastError}")
            r.assertParked(clock = true)
            assertEquals(1, dev.calls("defer"), "外れたデバイスのメソッドを呼んだ")
            // プロセスが生きている間に確かめる（終わると表は閉じられて、外し忘れていても 0 になる）
            assertEquals(2, r.hostResources(), "failed・detached の pending-call が表に残った")
            assertEquals(0, pendingCallObjects(m), "failed・detached の pending-call が残った")
            m.lock.withLockValue { m.killLocked(r.pid, KillSignal.KILL) }
            r.finish(ProcessState.KILLED)
        }
    }

    @Test
    fun `spawnの失敗は巻き戻す道でもerror-codeで返り、子を作らない`() = gated(spawnErrorCommand) { runtime ->
        // (1)(2) は読み込みの前に断るので、ここで待っているのは (3) の読み込み
        assertParked()
        val loadsWhileWaiting = runtime.loads.get()
        assertEquals(emptySet<Int>(), others())
        runtime.failLoad = true
        runtime.gate.countDown()
        // 巻き直した後の失敗（pendingSpawn を消して err）と、置き場の満杯（読み込みの前）の後で、段を最後まで通る
        finish()
        assertNull(suspend.pendingSpawn)
        assertEquals(loadsWhileWaiting, runtime.loads.get(), "満杯で断ったのに読み込みに入った")
        assertEquals(emptySet<Int>(), others(), "失敗したのに子ができた")
    }

    @Test
    fun `待機点型でも、後の引数のliftの誤りは先の引数のホストの検査より先にトラップになり、pendingSpawnも子も残さない`() = gated(spawnLiftTrapCommand) { runtime ->
        // 1 つ目の spawn の読み込みを止めたまま、巻き戻して待っていること（pendingSpawn がある）を確かめる。待機点型の巻き戻しの後の spawn の場面
        assertParked()
        assertNotNull(suspend.pendingSpawn)
        runtime.gate.countDown()
        finish(ProcessState.CRASHED)
        val e = rt.lastError
        assertTrue(e is WasmTrapException && e.message.orEmpty().contains("list の場所が align 4 に揃っていない"), "別の終わり方: $e")
        assertNull(suspend.pendingSpawn)
        // 子は 1 つ目の spawn の 1 つだけ（drop したので、刈り取られるか親なしで残る）
        assertTrue(others().all { it == pid + 1 }, "余計な子がある: ${others()}")
    }

    /** 終わるか、[parked] の形で時計を待つところまで進め、待機点で待っていることを確かめる */
    private fun Run.awaitParked(message: String, parked: () -> Boolean) {
        // 子が走っている間は台が待機点でない（isAtWaitPoint は全プロセスを見る）ので、子が終わるのも待つ
        tickUntil("$message: ${state()}") {
            state().isTerminal || (state() == ProcessState.WAITING_EVENT && parked() &&
                m.lock.withLockValue { others().all { m.kernel.processes[it]?.state?.isTerminal != false } })
        }
        assertFalse(state().isTerminal, "$message: 待つ前に終わった（終了コード ${m.lock.withLockValue { m.kernel.processes[pid]?.exitCode }}）")
        assertParked(clock = true)
    }

    @Test
    fun `巻き直して着いたのにinvokeの待ちがなければinterruptedで返してメソッドを呼び直さず、新しい呼び出しは残った待ちを閉じてから始める`() = timeout(limit) {
        val timers = manualTimers()
        device(timers) { h, m, dev ->
            val r = Run(h, m, deviceGuardCommand, timers)
            r.awaitDefer(dev, 1)
            r.assertParked()
            assertEquals(2, r.hostResources())
            val stale = r.suspend.pendingInvoke!!
            // (1) 巻き戻している間に待ちを忘れる（起きないはず）: 巻き直して着いても、メソッドを呼び直さずに failed("interrupted")
            r.suspend.pendingInvoke = null
            dev.deferred[0].complete(DeviceValue.of(5L))
            // 時計で待つ。隠れた pending-call は外されずに残る（device・残った pending-call・pollable）
            r.awaitParked("時計で待たない") { r.hostResources() == 3 }
            assertEquals(1, dev.calls("defer"), "待ちがないのにメソッドを呼び直した")
            assertEquals(1, pendingCallObjects(m))
            // (2) 新しい invoke の時点で前の待ちが残っている（起きないはず）: 閉じてから最初から行う
            r.suspend.pendingInvoke = stale
            // 待ちを戻してから時計を 100 ms 進める（手で進める時計なので、戻す前に親が起きて 2 回目の invoke に進むことはない）
            timers.advance(100_000_000L)
            r.awaitDefer(dev, 2)
            r.assertParked()
            assertNotSame(stale, r.suspend.pendingInvoke, "残っていた待ちをそのまま使った")
            // device と新しい pending-call だけ（残っていた pending-call は閉じた）
            assertEquals(2, r.hostResources(), "残っていた pending-call を閉じていない")
            assertEquals(1, pendingCallObjects(m), "残っていた pending-call を閉じていない")
            dev.deferred[1].complete(DeviceValue.of(6L))
            r.finish()
            assertEquals(2, dev.calls("defer"))
        }
    }

    @Test
    fun `巻き直して着いたのにspawnの読み込みがなければbeginExecからやり直して子は1つだけ、新しい呼び出しは残った読み込みを閉じてから始める`() = gated(spawnGuardCommand, manualClock = true) { runtime ->
        assertParked()
        val spawned = Collections.synchronizedList(ArrayList<Int>())
        h.scheduler.hooks.duringSpawn = { _, child -> spawned += child }
        val stale = suspend.pendingSpawn!!
        val staleId = stale.key.objectId
        // (1) 巻き戻している間に読み込みを忘れる（起きないはず）: 巻き直して着いたら beginExec からやり直す（子はまだ作っていない）
        suspend.pendingSpawn = null
        runtime.gate.countDown()
        // 子を 1 つ作り、時計で待つ（子のハンドルと pollable）
        awaitParked("時計で待たない") { hostResources() == 2 }
        assertEquals(1, spawned.size, "子の数: $spawned")
        assertNull(suspend.pendingSpawn)
        assertNotNull(m.lock.withLockValue { m.kernel.objects[staleId] }, "忘れた読み込みの待ちがもう閉じている（前提が崩れた）")
        // (2) 新しい spawn の時点で前の読み込みが残っている（起きないはず）: 閉じてから最初から行う（残っていた読み込みで子を作らない）
        suspend.pendingSpawn = stale
        // 読み込みを戻してから時計を 100 ms 進める（手で進める時計なので、子が終わるのを待つ間や戻す前に、親が起きて 2 回目の spawn に進むことはない）
        timers!!.advance(100_000_000L)
        // /bin/none の spawn が no-entry で返り、来ない時計で待つ（表は pollable だけ）
        awaitParked("2 回目の spawn の後で待たない") { hostResources() == 1 }
        assertEquals(1, spawned.size, "残っていた読み込みで子を作った: $spawned")
        assertNull(suspend.pendingSpawn)
        assertNull(m.lock.withLockValue { m.kernel.objects[staleId] }, "残っていた読み込みの待ちを閉じていない")
        m.lock.withLockValue { m.killLocked(pid, KillSignal.KILL) }
        finish(ProcessState.KILLED)
    }

    private companion object {
        const val ADDRESS = "00000000-0000-4000-8000-0000000000e2"
    }
}
