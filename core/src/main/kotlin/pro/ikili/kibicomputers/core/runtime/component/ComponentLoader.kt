package pro.ikili.kibicomputers.core.runtime.component

import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.WasmTrapException
import pro.ikili.kibicomputers.core.runtime.WasmTrapKind
import pro.ikili.kibicomputers.core.runtime.validation.LoadDeadline
import pro.ikili.kibicomputers.core.runtime.validation.ModulePrescan
import pro.ikili.kibicomputers.core.runtime.validation.ValidationWatchdog
import java.security.MessageDigest

/**
 * 読み込んで検証したコンポーネント（#262）。
 *
 * 元のバイト列 [bytes] をそのまま持ち、木（[root]）と中の core モジュールの範囲は、そのバイト列の中を指す
 * （core モジュールを写して持たない）。core モジュールを Endive に渡すとき（#263）に [coreModuleBytes] で写す。
 */
internal class LoadedComponent(
    /** 元のバイト列（呼ぶ側は書き換えない） */
    val bytes: ByteArray,
    /** 元のバイト列の SHA-256（#29 の内容のハッシュで共有するときの鍵） */
    val sha256: ByteArray,
    /** 一番外のコンポーネント（宣言の順の木） */
    val root: ParsedComponent,
    /** 入れ子を含めた、すべての core モジュールの範囲（バイナリに出てくる順） */
    val coreModules: List<ByteRange>,
    /** 入れ子を展開した後の、コアモジュールのインスタンスの数（[ComponentExpansion]） */
    val expandedCoreInstances: Int,
    /**
     * 入れ子を展開した後の、コンポーネントのインスタンス（`instance (instantiate ...)`）の数（[ComponentExpansion]）。
     * 一番外のコンポーネントそのものは数えない
     */
    val expandedInstances: Int,
) {
    /** [range] の core モジュールのバイト列（写したもの） */
    fun coreModuleBytes(range: ByteRange): ByteArray = range.copyOf(bytes)

    /** トップレベルの import */
    val imports: List<ComponentImport> get() = root.imports

    /** トップレベルの export */
    val exports: List<ComponentExport> get() = root.exports
}

/**
 * コンポーネントの読み込みと検証（#262。#40 の検証をコンポーネントに広げたもの）。次の順で確かめ、だめなら
 * [WasmLoadException] を投げる。各段の後で時間の上限（[LoadDeadline]）を確かめる。
 *
 * 1. 大きさの上限
 * 2. ヘッダー（layer 1・version 0x0d）
 * 3. 自前のパーサーで、構造の上限を確かめながら読む（[ComponentParser]）
 * 4. 中の各 core モジュールに、p1 と同じ構造の確認（`ModulePrescan`）を当てる
 * 5. wasm-tools で検証する（[ComponentValidator]）。検証の間は、見張り（[ValidationWatchdog]）が同じ時間の上限で見て、
 *    超えたらスレッドに割り込んで止める（#666）。時間切れで止めたものは、ほかの段と同じく [WasmLoadException]（時間の上限を超えた）にする。
 *    わかっている、時間が膨らむ形（大きな型の確認を何度もさせる。[ComponentValidationCost]）は、すぐ決定的に断れるよう、
 *    始める前に見積もりで断る。core モジュールの関数の本体は除いた写し（[ValidationImage]）を渡す。失敗のメッセージの位置は、
 *    元のバイト列の位置に直す。本体と、core モジュールのカスタム セクションの壊れなどは、p1 と同じく Endive が読み込むとき（#263）に確かめる
 * 6. import の形（[ComponentImportPolicy]）と、core モジュールが予約したモジュール名を import していないこと
 * 7. 入れ子を展開した後のインスタンスの数（コアモジュールとコンポーネントの両方。[ComponentExpansion]）
 *
 * 検証器（[ComponentValidator]）のクラスが読めないなど、検証器そのものの失敗（[LinkageError]）も、内部の失敗と分かる
 * [WasmLoadException] にする（ログにも出す）。検証の途中でスレッドが（見張りの時間切れ以外の理由で。スケジューラの停止など）
 * 割り込まれたら、内容の失敗ではないので [WasmTrapException]（[WasmTrapKind.INTERRUPTED]）を投げ、割り込みの印は立てたままにする
 * （`ProgramCache` は [WasmLoadException] だけを失敗として覚える）。
 *
 * 重い（検証に数十 ms、初回は1秒ほど）ので、コンパイル用のスレッドから呼ぶ。状態を持たないので、同時に呼んでよい。
 * 中の core モジュールの読み込みとリンクは `ComponentProgram`（#263）。
 */
internal object ComponentLoader {
    private val LOG: System.Logger = System.getLogger("kibicomputers.component")

    /**
     * [bytes] を読み込んで検証する。
     *
     * [validate] は wasm-tools の検証の差し替え（テストで、検証をまたいだ時間の上限を確かめるため）。`null` なら
     * [ComponentValidator.validate]。[deadline] は時間の上限（`null` なら、検証器の初期化の後にここで作る）。読み込みの後の段
     * （core モジュールの読み込みとリンク。#263）まで同じ上限で測るときに渡す。[watchdog] は検証の見張り
     * （テストで差し替える。普段は全体で共有するもの）。[validate] を差し替えても見張る
     */
    @Throws(WasmLoadException::class, WasmTrapException::class)
    fun load(
        bytes: ByteArray,
        limits: ComponentLimits = ComponentLimits.DEFAULT,
        policy: ComponentImportPolicy = ComponentImportPolicy.DEFAULT,
        deadline: LoadDeadline? = null,
        watchdog: ValidationWatchdog = ValidationWatchdog.shared,
        validate: ((ByteArray) -> Unit)? = null,
    ): LoadedComponent {
        if (bytes.size > limits.maxBytes) {
            throw WasmLoadException("コンポーネントが大きすぎる（${bytes.size} バイト。上限 ${limits.maxBytes}）")
        }
        when (WasmBinaryHeader.kindOf(bytes)) {
            WasmBinaryKind.COMPONENT -> Unit
            WasmBinaryKind.CORE_MODULE -> throw WasmLoadException("コンポーネントではなく core モジュール（layer 0）")
            null -> throw WasmLoadException("WASM のコンポーネントのヘッダーがない")
        }
        // 検証器の初回の初期化（1秒ほど）は、内容と関係なく遅いので、時間の上限を測り始める前に済ませる
        warmUp()
        @Suppress("NAME_SHADOWING")
        val deadline = deadline ?: LoadDeadline(limits.moduleLimits.timeLimitNanos)
        val root = ComponentParser.parse(bytes, limits, deadline)
        val modules = root.allCoreModules()
        for (range in modules) {
            // ModulePrescan はバイト列全体を見るので、その間だけ写す（持ち続けない）
            ModulePrescan.check(range.copyOf(bytes), limits.moduleLimits, deadline)
        }
        deadline.check("core モジュールの構造の確認")
        // わかっている、時間が膨らむ形は、検証を始める前に決定的に断る（見張りで止めるより速く、何度出されても安い）
        ComponentValidationCost.check(root, limits)
        // 関数の本体は Endive が読み込むときに検証するので、wasm-tools には本体を除いた写しを渡す（ValidationImage）
        val image = ValidationImage.of(bytes, root)
        watched(watchdog, deadline, VALIDATION_PHASE) {
            if (validate != null) validate(image.bytes) else validator { ComponentValidator.validate(image) }
        }
        deadline.check(VALIDATION_PHASE)
        for (import in root.imports) {
            policy.reject(import)?.let { throw WasmLoadException("許可しない import ${import.name}: $it") }
        }
        for (range in modules) {
            if (CoreModuleImports.RESERVED_MODULE in CoreModuleImports.moduleNames(bytes, range)) {
                throw WasmLoadException("core モジュールが予約したモジュール名 ${CoreModuleImports.RESERVED_MODULE} を import している")
            }
        }
        val expansion = ComponentExpansion.count(root, limits, deadline)
        deadline.check("コンポーネントの展開の確認")
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
        return LoadedComponent(bytes, sha256, root, modules, expansion.coreInstances, expansion.instances)
    }

    /**
     * 検証器の初回の初期化を済ませる（内容と関係なく遅いので、時間の上限を測り始める前に呼ぶ）。
     * [load] に [LoadDeadline] を渡す側（リンクまで同じ上限で測る。#263）は、上限を作る前にこれを呼ぶ
     */
    @Throws(WasmLoadException::class)
    fun warmUp() {
        validator { ComponentValidator.warmUp() }
    }

    private const val VALIDATION_PHASE = "コンポーネントの検証"

    /**
     * [block] を、[watchdog] で [deadline] の上限まで見張って動かす（#666）。見張りが時間切れで割り込んだら、[block] が
     * どう終わっても（割り込みの [WasmTrapException]、WASI の入出力が割り込みで失敗した [WasmLoadException]、終わり際に届いて
     * 正常に戻った、のどれでも）、割り込みの印を下ろして、時間の上限を超えた [WasmLoadException] を投げる。
     * 時間切れでなければ、[block] の結果をそのまま返す（ほかの理由の割り込みは [WasmTrapException] のまま、印も立てたまま）
     */
    private inline fun watched(watchdog: ValidationWatchdog, deadline: LoadDeadline, phase: String, block: () -> Unit) {
        val watch = watchdog.watch(deadline)
        val failure = try {
            block()
            null
        } catch (e: Throwable) {
            e
        }
        if (watch.finish()) throw deadline.exceeded(phase, cause = failure)
        if (failure != null) throw failure
    }

    /**
     * 検証器を呼ぶ。[ComponentValidator] のクラスの初期化そのもの（Endive のクラスが見えないときなど）で失敗した
     * [LinkageError] は、[ComponentValidator] の中では包めないので、ここで内部の失敗として包む
     */
    private inline fun <T> validator(block: () -> T): T = try {
        block()
    } catch (e: LinkageError) {
        LOG.log(System.Logger.Level.ERROR, "コンポーネントの検証器（wasm-tools）を動かせない（クラスの読み込み）", e)
        throw WasmLoadException("コンポーネントの検証器を動かせない（内部の失敗。クラスの読み込み）: $e", e)
    }
}
