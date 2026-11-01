package pro.ikili.kibicomputers.core

import org.jetbrains.annotations.ApiStatus

/**
 * MOD 全体で共有する定数。
 *
 * Java からは `KiBiComputers.MOD_ID` のように static フィールドとして参照できる。
 */
@ApiStatus.Internal
public object KiBiComputers {
    public const val MOD_ID: String = "kibicomputers"
    public const val MOD_NAME: String = "KiBi Computers"
}
