package pro.ikili.kibicomputers.core.runtime.component

import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind
import run.endive.log.Logger
import run.endive.log.SystemLogger
import run.endive.runtime.ByteArrayMemory
import run.endive.runtime.ImportValues
import run.endive.runtime.Instance
import run.endive.runtime.WasmInterruptedException
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
 * - 検証の時間の上限は持たない（`LoadDeadline` は検証の間は確かめられない。呼ぶ側が見積もりで重いものを断る。
 *   時間で止める形は #666）。スレッドの割り込み（`Thread.interrupt`。スケジューラの停止が使う）では途中で止まり、
 *   内容の失敗ではないので [WasmLoadException] ではなく [WasmTrapException]（[WasmTrapKind.INTERRUPTED]）にする
 *   （割り込みの印は立てたまま返す）
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

    /**
     * Endive の WASI のログを黙らせるもの。初めて使うときに作る（Endive のクラスが見えないときに、このオブジェクトの
     * 初期化ではなく、[validate] の中の [LinkageError] として失敗させ、[WasmLoadException] に包むため）
     */
    private val quiet: Logger by lazy {
        object : SystemLogger() {
            override fun isLoggable(level: Logger.Level): Boolean = false
        }
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

    /**
     * 検証用の写し [image] を検証する。失敗のメッセージの位置（`at offset 0x..`）は、元のバイト列の中の位置に直す。
     * 失敗は [validate] と同じ
     */
    @Throws(WasmLoadException::class, WasmTrapException::class)
    fun validate(image: ValidationImage.Image) {
        validate(image.bytes, MAX_MEMORY_PAGES, image::mapMessage)
    }

    /**
     * [bytes] を検証する。通らなければ [WasmLoadException]。スレッドが割り込まれて止まったら [WasmTrapException]
     * （[WasmTrapKind.INTERRUPTED]。割り込みの印は立てたまま）。
     * [maxMemoryPages] はゲストの線形メモリの上限（テストで小さくする。普段は [MAX_MEMORY_PAGES]）。
     * [mapMessage] は、失敗の理由（wasm-tools の標準エラー出力）の書き直し（位置を元のバイト列のものに直す）
     */
    @Throws(WasmLoadException::class, WasmTrapException::class)
    fun validate(bytes: ByteArray, maxMemoryPages: Int = MAX_MEMORY_PAGES, mapMessage: (String) -> String = { it }) {
        val m = guarded("初期化") { module }
        val stderr = BoundedOutput(MAX_OUTPUT_BYTES)
        val exit = try {
            run(m, bytes, stderr, maxMemoryPages)
        } catch (e: WasmLoadException) {
            throw e
        } catch (e: WasmTrapException) {
            throw e
        } catch (e: StackOverflowError) {
            throw WasmLoadException("コンポーネントの検証が異常終了した（スタックが足りない）", e)
        } catch (e: LinkageError) {
            throw internalFailure(e)
        } catch (e: RuntimeException) {
            // 割り込みで止まったものは、内容の失敗ではない（壊れたコンポーネントとして覚えさせない）
            if (interrupted(e)) throw interruptedFailure(e)
            // ゲストのトラップ（メモリの上限で確保に失敗して abort したときなど）。内容に原因があることが多いので、内容の失敗として扱う
            throw WasmLoadException("コンポーネントの検証が異常終了した（メモリの上限を超えた、など）: $e", e)
        }
        if (exit != 0) {
            // 割り込まれて WASI の入出力が失敗し、終了コードで終わったときも、内容の失敗にしない
            if (Thread.currentThread().isInterrupted) throw interruptedFailure(null)
            val message = mapMessage(reason(stderr.text())).let {
                if (it.length > MAX_MESSAGE_CHARS) it.take(MAX_MESSAGE_CHARS) + "…" else it
            }
            throw WasmLoadException("コンポーネントの検証に通らない: $message")
        }
    }

    /** [e]（原因を含む）が割り込みで止まったものか。割り込みの印が立っているときも、割り込みとみなす */
    private fun interrupted(e: Throwable): Boolean {
        if (Thread.currentThread().isInterrupted) return true
        var cur: Throwable? = e
        var depth = 0
        while (cur != null && depth++ < 16) {
            if (cur is WasmInterruptedException || cur is InterruptedException) return true
            cur = cur.cause
        }
        return false
    }

    private fun interruptedFailure(cause: Throwable?): WasmTrapException {
        // Endive が印を下ろしていても、呼ぶ側（コンパイル用のスレッドの停止）が見られるように立て直す
        Thread.currentThread().interrupt()
        return WasmTrapException(WasmTrapKind.INTERRUPTED, "コンポーネントの検証中にスレッドが割り込まれた", cause)
    }

    /** ログの行（`[2026-11-13T00:36:39Z WARN ] ...`。時刻が入る）。失敗の理由ではない */
    private val LOG_LINE = Regex("""^\[\S+\s+(?:ERROR|WARN|INFO|DEBUG|TRACE)\s*].*""")

    /**
     * 標準エラー出力から、失敗の理由を取り出す。wasm-tools は検証に失敗すると、DWARF の読み直しなどのログ
     * （時刻つき）を先に出すことがあるので、ログの行は除き、先頭の `error: ` も取る。
     * 同じバイナリなら呼ぶたびに同じメッセージになるようにするため
     */
    internal fun reason(stderr: String): String =
        stderr.lineSequence()
            .filterNot { LOG_LINE.matches(it.trim()) }
            .joinToString("\n")
            .trim()
            .removePrefix("error: ")

    /** wasm-tools を動かし、終了コードを返す */
    private fun run(m: WasmModule, bytes: ByteArray, stderr: OutputStream, maxMemoryPages: Int): Int {
        val options = WasiOptions.builder()
            .withStdin(ByteArrayInputStream(bytes), false)
            .withStdout(BoundedOutput(MAX_OUTPUT_BYTES), false)
            .withStderr(stderr, false)
            // 乱数はハッシュの種などにだけ使われる。結果を決定的にするため固定する
            .withRandom(Random(0x262))
            // ログ（時刻つき）を出させない。理由の取り出し（[reason]）でも除く
            .withEnvironment("RUST_LOG", "off")
            .withArguments(listOf("wasm-tools", "validate", "--features=$FEATURES", "-"))
            .build()
        WasiPreview1.builder().withLogger(quiet).withOptions(options).build().use { wasi ->
            try {
                Instance.builder(m)
                    .withMachineFactory(WasmToolsModule::create)
                    .withMemoryFactory { limits -> ByteArrayMemory(capped(limits, maxMemoryPages)) }
                    .withImportValues(ImportValues.builder().addFunction(*wasi.toHostFunctions()).build())
                    .build()
            } catch (e: WasiExitException) {
                return e.exitCode()
            }
        }
        return 0
    }

    private fun capped(limits: MemoryLimits, maxPages: Int): MemoryLimits {
        if (limits.initialPages() > maxPages) {
            throw WasmLoadException("検証器のメモリの最初の大きさ（${limits.initialPages()} ページ）が上限を超える（内部の失敗）")
        }
        return MemoryLimits(limits.initialPages(), minOf(limits.maximumPages(), maxPages))
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
