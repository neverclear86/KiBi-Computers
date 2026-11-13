package pro.ikili.kibicomputers.core.runtime.component

/**
 * コンポーネントのトップレベルの import を許すかの判定（#262。#40 の「許可しない import の拒否」をコンポーネントに広げたもの）。
 *
 * ここで見るのは形だけ（種類と名前の文法、予約した名前）。インスタンスの型がホストの出す型に合うか（部分型か）の照合は、
 * リンク（#263）の仕事。ホストが出すインターフェースの一覧と、`wasi:*` の版の受け付け方（semver の互換を認めるか）は
 * システムコールの ABI に関わるので、#267・#268 で決めて、この判定を差し替える。
 */
internal fun interface ComponentImportPolicy {
    /** [import] を断る理由。許すなら `null` */
    fun reject(import: ComponentImport): String?

    companion object {
        /** `kibi:*` の版（ADR 0073 の Q5-A。ホストは版の完全一致だけを受け付ける） */
        const val KIBI_VERSION: String = "0.1.0"

        /**
         * 既定の判定:
         *
         * - 種類はインスタンスだけ（core モジュール・関数・値・型・コンポーネントの import は断る。ホストはインターフェースの
         *   インスタンスだけを出す）
         * - 名前はインターフェースの名前（`<名前空間>:<パッケージ>/<インターフェース>[@<版>]`）
         * - 名前空間は `wasi` と `kibi` だけ（ほかはホストが出さない）
         * - `kibi:*` は版 `@0.1.0` の完全一致だけ。`wasi:*` の版はここでは見ない（#267・#268）
         */
        val DEFAULT: ComponentImportPolicy = ComponentImportPolicy { import ->
            if (import.desc !is ExternDesc.Instance) {
                return@ComponentImportPolicy "インスタンスでない import（${import.desc.sort.label}）"
            }
            val name = InterfaceName.parse(import.name)
                ?: return@ComponentImportPolicy "インターフェースの名前（名前空間:パッケージ/インターフェース@版）でない"
            when (name.namespace) {
                "wasi" -> null
                "kibi" -> if (name.version == KIBI_VERSION) null else "kibi:* の版は @$KIBI_VERSION だけ（${name.version ?: "版なし"}）"
                else -> "ホストが出さない名前空間 ${name.namespace}"
            }
        }
    }
}

/** インターフェースの名前（`ns:pkg/iface@version`）。入れ子の名前（`cm-nested-names`）には対応していない */
internal data class InterfaceName(val namespace: String, val pkg: String, val iface: String, val version: String?) {
    companion object {
        private val WORD = Regex("[a-z][a-z0-9]*|[A-Z][A-Z0-9]*")
        private val SEMVER = Regex(
            "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)" +
                "(-[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*)?(\\+[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*)?",
        )

        /** [text] を読む。インターフェースの名前でなければ `null` */
        fun parse(text: String): InterfaceName? {
            val at = text.indexOf('@')
            val body = if (at < 0) text else text.substring(0, at)
            val version = if (at < 0) null else text.substring(at + 1)
            if (version != null && !SEMVER.matches(version)) return null
            val colon = body.indexOf(':')
            val slash = body.indexOf('/')
            if (colon <= 0 || slash <= colon + 1 || slash == body.length - 1) return null
            val ns = body.substring(0, colon)
            val pkg = body.substring(colon + 1, slash)
            val iface = body.substring(slash + 1)
            if (!isLabel(ns) || !isLabel(pkg) || !isLabel(iface)) return null
            return InterfaceName(ns, pkg, iface, version)
        }

        /** ケバブケースのラベル（`word(-word)*`） */
        private fun isLabel(s: String): Boolean = s.isNotEmpty() && s.split('-').all { WORD.matches(it) }
    }
}
