package pro.ikili.kibicomputers.neoforge.input

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import pro.ikili.kibicomputers.core.input.KeyboardEvent
import pro.ikili.kibicomputers.core.input.KeyboardEventCodec
import pro.ikili.kibicomputers.core.input.KeyboardListener
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks
import java.util.UUID

/**
 * キーボードブロックのブロックエンティティ（サーバー側で使う）。入力のセッション（[KeyboardSessions]）から届いた入力を、
 * つながった相手（[KeyboardListener]）に配る。コンピューターへの結線は #307（仮）と #129（隣接デバイスの接続）で行う。
 *
 * つながる相手は1つだけ（#360）。先に [claim] した相手が、[release] するまで持つ。2つ目からは断る
 * （他人のキーボードの隣に置いたケースに、打ったものが漏れないように）。
 *
 * 状態は持たないので、セーブデータには何も書かない。
 */
class KeyboardBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(KiBiBlocks.KEYBOARD_BLOCK_ENTITY.get(), pos, state) {

    /** つながった相手。いなければ null */
    var owner: KeyboardListener? = null
        private set

    /**
     * 入力を受け取る相手を [listener] にする。サーバーのメインスレッドで呼ぶ。
     *
     * @return つないだ（すでに [listener] がつながっていたときも true）。ほかの相手がつながっていれば false
     */
    fun claim(listener: KeyboardListener): Boolean {
        val current = owner
        if (current != null) return current === listener
        owner = listener
        return true
    }

    /** [listener] がつながっていれば外す。 */
    fun release(listener: KeyboardListener) {
        if (owner === listener) owner = null
    }

    /** [typist] が打った [events] を、つながった相手に配る。相手がいなければ捨てる。 */
    fun deliver(typist: UUID, events: List<KeyboardEvent>) {
        val listener = owner ?: return
        for (event in events) listener.onKeyboardEvent(typist, event)
    }

    /**
     * [KeyboardEventCodec] の形の [data] を読んで、[typist] が打ったものとして配る。量の上限は見ない
     * （プレイヤーからの入力は [KeyboardSessions] を通す。これはテストやサーバー内から入力するとき用）。
     *
     * @return 配った入力の数。形が崩れていれば -1（何も配らない）
     */
    fun receive(typist: UUID, data: ByteArray): Int {
        val events = try {
            KeyboardEventCodec.decode(data)
        } catch (e: IllegalArgumentException) {
            return -1
        }
        deliver(typist, events)
        return events.size
    }

    override fun setRemoved() {
        super.setRemoved()
        owner = null
    }
}
