package pro.ikili.kibicomputers.core.util

import org.jetbrains.annotations.ApiStatus
import java.util.UUID

/**
 * UUID の正規の小文字の文字列（ハイフン付き 36 文字）の検査（ADR 0124・#81）。
 *
 * ディスクの識別子（[pro.ikili.kibicomputers.core.computer.DiskStore]）とマシンの ID（[pro.ikili.kibicomputers.core.persist.MachineSaveStore]）は、
 * どちらもこの形のものだけをディレクトリの名前に使う（`..`・区切り文字・大文字・空を通さず、根の外を指させない）。
 */
@ApiStatus.Internal
public object CanonicalUuid {
    private val PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    /** [text] が UUID の正規の小文字の形か */
    @JvmStatic
    public fun isValid(text: String): Boolean = PATTERN.matches(text)

    /** [text] が正規の形なら UUID、そうでなければ（`null` を含む）`null` */
    @JvmStatic
    public fun parse(text: String?): UUID? = if (text != null && isValid(text)) UUID.fromString(text) else null
}
