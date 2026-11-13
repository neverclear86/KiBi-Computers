package pro.ikili.kibicomputers.core.runtime.component

import pro.ikili.kibicomputers.core.runtime.WasmLoadException
import pro.ikili.kibicomputers.core.runtime.validation.LoadDeadline
import pro.ikili.kibicomputers.core.runtime.validation.ModulePrescan
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
 * 5. wasm-tools で検証する（[ComponentValidator]）。core モジュールの関数の本体は除いた写し（[ValidationImage]）を渡す。
 *    本体と、core モジュールのカスタム セクションの壊れなどは、p1 と同じく Endive が読み込むとき（#263）に確かめる
 * 6. import の形（[ComponentImportPolicy]）と、core モジュールが予約したモジュール名を import していないこと
 * 7. 入れ子を展開した後のインスタンスの数（[ComponentExpansion]）
 *
 * 重い（検証に数十 ms、初回は1秒ほど）ので、コンパイル用のスレッドから呼ぶ。状態を持たないので、同時に呼んでよい。
 * 実行（exec）にはまだつないでいない（#263）。
 */
internal object ComponentLoader {
    /** [bytes] を読み込んで検証する */
    @Throws(WasmLoadException::class)
    fun load(
        bytes: ByteArray,
        limits: ComponentLimits = ComponentLimits.DEFAULT,
        policy: ComponentImportPolicy = ComponentImportPolicy.DEFAULT,
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
        ComponentValidator.warmUp()
        val deadline = LoadDeadline(limits.moduleLimits.timeLimitNanos)
        val root = ComponentParser.parse(bytes, limits, deadline)
        val modules = root.allCoreModules()
        for (range in modules) {
            // ModulePrescan はバイト列全体を見るので、その間だけ写す（持ち続けない）
            ModulePrescan.check(range.copyOf(bytes), limits.moduleLimits, deadline)
        }
        deadline.check("core モジュールの構造の確認")
        // 関数の本体は Endive が読み込むときに検証するので、wasm-tools には本体を除いた写しを渡す（ValidationImage）
        ComponentValidator.validate(ValidationImage.of(bytes, root))
        deadline.check("コンポーネントの検証")
        for (import in root.imports) {
            policy.reject(import)?.let { throw WasmLoadException("許可しない import ${import.name}: $it") }
        }
        for (range in modules) {
            if (CoreModuleImports.RESERVED_MODULE in CoreModuleImports.moduleNames(bytes, range)) {
                throw WasmLoadException("core モジュールが予約したモジュール名 ${CoreModuleImports.RESERVED_MODULE} を import している")
            }
        }
        val instances = ComponentExpansion.countCoreInstances(root, limits, deadline)
        deadline.check("コンポーネントの展開の確認")
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
        return LoadedComponent(bytes, sha256, root, modules, instances)
    }
}
