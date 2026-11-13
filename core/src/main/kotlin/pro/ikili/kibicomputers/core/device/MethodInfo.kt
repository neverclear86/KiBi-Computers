package pro.ikili.kibicomputers.core.device

import org.jetbrains.annotations.ApiStatus

/** デバイスのメソッド1つの情報（WIT の `method-info`。#66、ADR 0066）。 */
@ApiStatus.Internal
public data class MethodInfo(
    /** メソッドの名前 */
    public val name: String,
    /** メインスレッドで呼ぶメソッド（`false` なら直接。呼んだスレッドでその場で実行する） */
    public val mainThread: Boolean,
)
