package pro.ikili.kibicomputers.neoforge.input

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import pro.ikili.kibicomputers.core.input.KeyboardEvent
import pro.ikili.kibicomputers.core.input.KeyboardEventCodec
import pro.ikili.kibicomputers.core.input.KeyboardInputBudget
import pro.ikili.kibicomputers.core.input.KeyboardListener
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks
import java.util.concurrent.CopyOnWriteArrayList

/**
 * キーボードブロックのブロックエンティティ（サーバー側で使う）。クライアントから届いた入力を、登録した相手
 * （[KeyboardListener]）に配る。コンピューターへの結線は #307（仮）と #129（隣接デバイスの接続）で行う。
 *
 * 状態は持たないので、セーブデータには何も書かない。
 */
class KeyboardBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(KiBiBlocks.KEYBOARD_BLOCK_ENTITY.get(), pos, state) {

    private val listeners = CopyOnWriteArrayList<KeyboardListener>()
    private val budget = KeyboardInputBudget()

    /** 入力を受け取る相手を足す。サーバーのメインスレッドで呼ばれる。 */
    fun addListener(listener: KeyboardListener) {
        listeners.addIfAbsent(listener)
    }

    /** 入力を受け取る相手を外す。 */
    fun removeListener(listener: KeyboardListener) {
        listeners.remove(listener)
    }

    /**
     * クライアントから届いたパケット [data] を読み、相手に配る。
     *
     * @return 配った入力の数。形が崩れている、または受け取れる量（[KeyboardInputBudget]）を超えたなら -1（何も配らない）
     */
    fun receive(data: ByteArray, gameTime: Long): Int {
        if (!budget.tryConsume(data.size, gameTime)) return -1
        val events: List<KeyboardEvent> = try {
            KeyboardEventCodec.decode(data)
        } catch (e: IllegalArgumentException) {
            return -1
        }
        for (event in events) {
            for (listener in listeners) listener.onKeyboardEvent(event)
        }
        return events.size
    }

    override fun setRemoved() {
        super.setRemoved()
        listeners.clear()
    }
}
