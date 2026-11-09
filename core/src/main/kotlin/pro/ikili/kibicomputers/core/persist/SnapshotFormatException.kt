package pro.ikili.kibicomputers.core.persist

import org.jetbrains.annotations.ApiStatus
import java.io.IOException

/**
 * 保存データの節の中身が壊れている・読めない（#82、ADR 0082）。途中で切れている、数が上限を超える、参照が表にない、
 * 知らない種類・タグなど。読む側（#85）は、これを受けたら状態を戻さずに冷えた起動にする
 */
@ApiStatus.Internal
public class SnapshotFormatException @JvmOverloads constructor(message: String, cause: Throwable? = null) : IOException(message, cause)
