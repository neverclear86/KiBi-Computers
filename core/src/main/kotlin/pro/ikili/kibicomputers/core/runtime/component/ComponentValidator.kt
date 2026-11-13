package pro.ikili.kibicomputers.core.runtime.component

import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import run.endive.log.Logger
import run.endive.log.SystemLogger
import run.endive.runtime.ByteArrayMemory
import run.endive.runtime.ImportValues
import run.endive.runtime.Instance
import run.endive.tools.wasm.WasmToolsModule
import run.endive.wasi.WasiExitException
import run.endive.wasi.WasiOptions
import run.endive.wasi.WasiPreview1
import run.endive.wasm.WasmModule
import run.endive.wasm.types.MemoryLimits
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.util.Random

/**
 * コンポーネントの検証（#262。ADR 0260・0262）。Endive が WASM にした wasm-tools（`run.endive:wasm-tools`）の
 * `validate` を、自前の薄い包みで動かす。
 *
 * Endive の `Validate` は使わない（呼ぶたびにログを出す、ゲストのメモリの上限と機能を外から決められない、
 * 失敗が標準エラー出力のまま）。ここでは:
 *
 * - 入力は標準入力だけで渡す（ディレクトリは渡さない。ゲストからホストのファイルは見えない）
 * - 機能は [FEATURES] で明示する（wasm-tools の既定や `all` にしない）
 * - ゲストの線形メモリに上限（[MAX_MEMORY_PAGES]）を付ける
 * - 失敗は [WasmLoadException] にする。メッセージには標準エラー出力の先頭を入れる
 *
 * 状態を持たないので、どのスレッドから同時に呼んでもよい（呼び出しごとに WASI とインスタンスを作って閉じる）。
 * 重い（2回目以降で数十 ms）ので、コンパイル用のスレッド（#29・#37）から呼ぶ。メインスレッドと tick からは呼ばない。
 *
 * 初回だけ、wasm-tools のモジュールの読み込みとクラスの初期化に1秒ほどかかる。これを読み込みの時間の上限
 * （`LoadDeadline`）に数えないよう、呼ぶ側は時間を測り始める前に [warmUp] を呼ぶ。サーバーの起動時には暖めない
 * （コンポーネントを読まないサーバーで、起動を遅くしないため）。
 */
internal object ComponentValidator {
    /**
     * 検証で有効にする機能（wasm-tools の `--features`）。まず全部を外し（`-all`）、使うものだけを足す。
     *
     * - core: WebAssembly 2.0（`wasm2`）、末尾呼び出し、例外処理（exnref）、extended-const、call_indirect の長い符号、
     *   bulk-memory の追加命令。p1 のモジュールで Endive が動かせる範囲に合わせる。GC・スレッド・memory64・
     *   複数のメモリは入れない
     * - Component Model: `component-model` と p3 の非同期（`cm-async`）。値（`cm-values`）、スレッド（`cm-threading`）、
     *   GC、error-context、長さ固定の list などは入れない。stackful の非同期（`cm-async-stackful`）を許すかは #266 で決める
     *   （今は断る側）
     */
    const val FEATURES: String =
        "-all,wasm2,tail-call,exceptions,extended-const,call-indirect-overlong,bulk-memory-opt,component-model,cm-async"

    /**
     * ゲスト（wasm-tools）の線形メモリの上限（ページ。仮の値、確定は #251）。16 MiB のコンポーネントを読む分に、
     * 検証の作業の分を足した大きさ。同時に動く検証はコンパイル用のスレッドの数までなので、ヒープの最大はその本数倍
     */
    const val MAX_MEMORY_PAGES: Int = 2048

    /** 失敗のメッセージに入れる、標準エラー出力の最大の長さ（文字） */
    private const val MAX_MESSAGE_CHARS = 500

    /** 標準エラー出力・標準出力から取っておくバイト数 */
    private const val MAX_OUTPUT_BYTES = 4096

    private val LOG: System.Logger = System.getLogger("kibicomputers.component")

    private val quiet = object : SystemLogger() {
        override fun isLoggable(level: Logger.Level): Boolean = false
    }

    /** wasm-tools のモジュール。初めて使うときに読む（1回だけ。スレッドセーフ） */
    private val module: WasmModule by lazy { WasmToolsModule.load() }

    /**
     * 初回の重い初期化（wasm-tools のモジュールの読み込みとクラスの初期化）を済ませる。2回目以降はすぐ戻る。
     * 失敗したら [WasmLoadException]（内部の失敗。ログにも出す）
     */
    @Throws(WasmLoadException::class)
    fun warmUp() {
        if (warm) return
        guarded("初期化") { module }
        // 初めての実行では、wasm-tools の巨大なクラスの読み込みと初期化が走る。空のコンポーネントを1回検証して済ませる
        validate(EMPTY_COMPONENT)
        warm = true
    }

    @Volatile
    private var warm = false

    /** 空のコンポーネント（ヘッダーだけ） */
    private val EMPTY_COMPONENT = byteArrayOf(0x00, 0x61, 0x73, 0x6d, WasmBinaryHeader.COMPONENT_VERSION.toByte(), 0x00, 0x01, 0x00)

    /** [bytes] を検証する。通らなければ [WasmLoadException] */
    @Throws(WasmLoadException::class)
    fun validate(bytes: ByteArray) {
        val m = guarded("初期化") { module }
        val stderr = BoundedOutput(MAX_OUTPUT_BYTES)
        val exit = try {
            run(m, bytes, stderr)
        } catch (e: WasmLoadException) {
            throw e
        } catch (e: StackOverflowError) {
            throw WasmLoadException("コンポーネントの検証が異常終了した（スタックが足りない）", e)
        } catch (e: LinkageError) {
            throw internalFailure(e)
        } catch (e: RuntimeException) {
            // ゲストのトラップ（メモリの上限で確保に失敗して abort したときなど）。内容に原因があることが多いので、内容の失敗として扱う
            throw WasmLoadException("コンポーネントの検証が異常終了した（メモリの上限を超えた、など）: $e", e)
        }
        if (exit != 0) {
            val message = stderr.text().trim().removePrefix("error: ").let {
                if (it.length > MAX_MESSAGE_CHARS) it.take(MAX_MESSAGE_CHARS) + "…" else it
            }
            throw WasmLoadException("コンポーネントの検証に通らない: $message")
        }
    }

    /** wasm-tools を動かし、終了コードを返す */
    private fun run(m: WasmModule, bytes: ByteArray, stderr: OutputStream): Int {
        val options = WasiOptions.builder()
            .withStdin(ByteArrayInputStream(bytes), false)
            .withStdout(BoundedOutput(MAX_OUTPUT_BYTES), false)
            .withStderr(stderr, false)
            // 乱数はハッシュの種などにだけ使われる。結果を決定的にするため固定する
            .withRandom(Random(0x262))
            .withArguments(listOf("wasm-tools", "validate", "--features=$FEATURES", "-"))
            .build()
        WasiPreview1.builder().withLogger(quiet).withOptions(options).build().use { wasi ->
            try {
                Instance.builder(m)
                    .withMachineFactory(WasmToolsModule::create)
                    .withMemoryFactory { limits -> ByteArrayMemory(capped(limits)) }
                    .withImportValues(ImportValues.builder().addFunction(*wasi.toHostFunctions()).build())
                    .build()
            } catch (e: WasiExitException) {
                return e.exitCode()
            }
        }
        return 0
    }

    private fun capped(limits: MemoryLimits): MemoryLimits {
        if (limits.initialPages() > MAX_MEMORY_PAGES) {
            throw WasmLoadException("検証器のメモリの最初の大きさが上限を超える（内部の失敗）")
        }
        return MemoryLimits(limits.initialPages(), minOf(limits.maximumPages(), MAX_MEMORY_PAGES))
    }

    /** 検証器そのものの準備（内容ではない失敗は、内部の失敗と分かるメッセージにしてログに出す） */
    private inline fun <T> guarded(phase: String, block: () -> T): T = try {
        block()
    } catch (e: LinkageError) {
        throw internalFailure(e, phase)
    } catch (e: RuntimeException) {
        throw internalFailure(e, phase)
    }

    private fun internalFailure(e: Throwable, phase: String = "実行"): WasmLoadException {
        LOG.log(System.Logger.Level.ERROR, "コンポーネントの検証器（wasm-tools）を動かせない（$phase）", e)
        return WasmLoadException("コンポーネントの検証器を動かせない（内部の失敗。$phase）: $e", e)
    }

    /** 先頭の [max] バイトだけ取っておく出力 */
    private class BoundedOutput(private val max: Int) : OutputStream() {
        private val buf = java.io.ByteArrayOutputStream()

        override fun write(b: Int) {
            if (buf.size() < max) buf.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            val n = minOf(len, max - buf.size())
            if (n > 0) buf.write(b, off, n)
        }

        fun text(): String = buf.toString(Charsets.UTF_8)
    }
}
