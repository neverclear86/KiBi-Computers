package pro.ikili.kibicomputers.core.input

import org.jetbrains.annotations.ApiStatus
import java.util.UUID

/**
 * キーボードへの入力のセッション（#360）。サーバーがプレイヤーに入力 GUI を開かせたときに作り、
 * GUI を閉じる・離れる・ログアウトするなどで閉じる。入力のパケットは、開いているセッションからだけ受け付ける。
 *
 * - 受け取る量は [budget] で抑える。プレイヤーとキーボードの組ごとに1つ渡す（ほかの人の入力を捨てさせないため）
 * - 押したまま（離しが届いていない）のキーを覚えておき、閉じるときに離しを合成する（[close]）
 *
 * スレッド: 1つのスレッド（サーバーのメインスレッド）から使う。
 *
 * @property id セッションの番号。クライアントはパケットにこれを付けて送る。閉じたあとに届いた古いパケットを見分ける
 * @property typist 打つプレイヤーの UUID
 * @property budget 受け取る量の上限
 */
@ApiStatus.Internal
public class KeyboardSession @JvmOverloads constructor(
    public val id: Int,
    public val typist: UUID,
    public val budget: KeyboardInputBudget = KeyboardInputBudget(),
) {
    private val held = LinkedHashSet<Key>()

    /** 開いているか。[close] で false になる。 */
    public var isOpen: Boolean = true
        private set

    /** 押したまま（離しが届いていない）のキー。押した順。 */
    public val heldKeys: Set<Key> get() = held.toSet()

    /**
     * クライアントから届いたパケット [data] を読む。[tick] はサーバーの tick の番号（[budget] の計算に使う）。
     *
     * @return 受け取った入力。閉じている、受け取れる量を超えた、または形が崩れているなら null（何も受け取らない）
     */
    public fun receive(data: ByteArray, tick: Long): List<KeyboardEvent>? {
        if (!isOpen || !budget.tryConsume(data.size, tick)) return null
        val events = try {
            KeyboardEventCodec.decode(data)
        } catch (e: IllegalArgumentException) {
            return null
        }
        for (event in events) {
            when (event) {
                is KeyboardEvent.KeyDown -> held.add(event.key)
                is KeyboardEvent.KeyUp -> held.remove(event.key)
                else -> Unit
            }
        }
        return events
    }

    /**
     * セッションを閉じる。押したままのキーの離し（修飾キーなし）を、押した順に返す。2回目からは空を返す。
     */
    public fun close(): List<KeyboardEvent> {
        if (!isOpen) return emptyList()
        isOpen = false
        val ups = held.map { KeyboardEvent.KeyUp(it, KeyModifiers.NONE) }
        held.clear()
        return ups
    }
}
