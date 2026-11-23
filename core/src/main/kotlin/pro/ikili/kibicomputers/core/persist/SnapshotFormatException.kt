package pro.ikili.kibicomputers.core.persist

import org.jetbrains.annotations.ApiStatus
import java.io.IOException

/**
 * 保存データの節の中身が壊れている・読めない（#82、ADR 0082）。途中で切れている、数が上限を超える、参照が表にない、
 * 知らない種類・タグなど。読む側（#85）は、これを受けたら状態を戻さずに冷えた起動にする
 */
@ApiStatus.Internal
public open class SnapshotFormatException @JvmOverloads constructor(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * 保存データは読めるが、今のマシンの上限（プロセスの数・RAM）に収まらない（#719、ADR 0719）。コンフィグや部品で上限を下げた後に、
 * 下げる前に保存した台を戻すときに起きる。読む側は [SnapshotFormatException] と同じく冷えた起動にし、理由を
 * [ColdBootCause.OVER_LIMIT] にする（壊れているのではないことを、管理者に分かるようにする）
 */
internal class SnapshotLimitException(message: String) : SnapshotFormatException(message)
