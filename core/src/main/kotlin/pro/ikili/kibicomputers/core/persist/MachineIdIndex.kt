package pro.ikili.kibicomputers.core.persist

import org.jetbrains.annotations.ApiStatus
import java.util.UUID

/**
 * マシンの ID の索引（UUID → 持ち主の位置。#81、ADR 0081）。サーバーに1つ。
 *
 * `claim` に通った・ID を振った、読み込み済みのブロックエンティティを入れ、そのブロックエンティティのアンロード・`setRemoved` で外す。
 * マシンが動いているかは問わない。同じ UUID の別のブロックエンティティが同時に読み込まれたら（`/clone`・ピックブロックの写し）、
 * 後から来た方に新しい UUID を振るのに使う。
 *
 * スレッド: メインスレッドだけで使う。
 */
@ApiStatus.Internal
public class MachineIdIndex {
    private val owners = HashMap<UUID, MachineOwner>()

    /** 入っている数 */
    public val size: Int get() = owners.size

    /** [id] の持ち主。なければ `null` */
    public fun ownerOf(id: UUID): MachineOwner? = owners[id]

    /** [id] の持ち主を [owner] にする */
    public fun put(id: UUID, owner: MachineOwner) {
        owners[id] = owner
    }

    /** [id] の持ち主が [owner] のときだけ外す（別のブロックエンティティが入れたものは外さない） */
    public fun removeIfOwner(id: UUID, owner: MachineOwner) {
        if (owners[id] == owner) owners.remove(id)
    }

    override fun toString(): String = "MachineIdIndex(${owners.size})"
}
