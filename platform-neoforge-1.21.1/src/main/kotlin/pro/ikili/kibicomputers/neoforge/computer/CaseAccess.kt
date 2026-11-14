package pro.ikili.kibicomputers.neoforge.computer

import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.common.util.FakePlayer
import net.neoforged.neoforge.event.entity.living.LivingDestroyBlockEvent
import net.neoforged.neoforge.event.level.BlockEvent
import pro.ikili.kibicomputers.core.access.AccessRules
import pro.ikili.kibicomputers.core.access.PlayerAccess
import pro.ikili.kibicomputers.core.access.PlayerRef
import pro.ikili.kibicomputers.core.config.KiBiConfig
import pro.ikili.kibicomputers.neoforge.config.KiBiConfigs

/**
 * ケースの持ち主と権限（#131、ADR 0131）の MC との橋渡し。判定の規則は core の [PlayerAccess]。サーバーのメインスレッドで使う。
 *
 * - プレイヤー（FakePlayer を含む）の破壊は `BlockEvent.BreakEvent` で止める。`playerWillDestroy`（保存のディレクトリを消す）より
 *   前に届くので、取り消せば保存は残る。クリエイティブの即時の破壊もこの経路を通る
 * - mob の破壊（ウィザーなど。`LivingDestroyBlockEvent`）は、持ち主のある（読めなかった記録を含む）ケースなら止める
 * - 爆発は [CaseBlock] の `onExplosionHit`・`onBlockExploded` で止める
 */
internal object CaseAccess {
    /** 拒んだときの actionbar の文言（%s は持ち主の名前） */
    const val DENIED_KEY: String = "message.kibicomputers.access.denied"

    /** 読めなかった記録のケースを拒んだときの文言 */
    const val DENIED_LOCKED_KEY: String = "message.kibicomputers.access.denied_locked"

    fun register() {
        NeoForge.EVENT_BUS.addListener<BlockEvent.BreakEvent>(::onBreak)
        NeoForge.EVENT_BUS.addListener<LivingDestroyBlockEvent>(::onLivingDestroy)
    }

    /** 今のコンフィグの仮値 */
    fun rules(): AccessRules = KiBiConfig.accessRules(KiBiConfigs.server)

    /** [player] の識別 */
    fun ref(player: Player): PlayerRef = PlayerRef(player.uuid, player.gameProfile.name)

    /** [player] が OP（コンフィグの権限レベル以上）か */
    fun isOp(player: Player, rules: AccessRules = rules()): Boolean = player.hasPermissions(rules.opPermissionLevel)

    /** 置いた人 [placer] が持ち主になるか（本物のプレイヤーだけ。FakePlayer・ディスペンサーなどは持ち主なし） */
    fun placedAccess(placer: Any?): PlayerAccess =
        if (placer is ServerPlayer && placer !is FakePlayer) PlayerAccess.ownedBy(ref(placer)) else PlayerAccess.NONE

    /** [player] に、[access] のコンピューターを使えないことを actionbar で知らせる */
    fun deny(player: Player, access: PlayerAccess) {
        val owner = access.owner
        val message = if (owner == null) Component.translatable(DENIED_LOCKED_KEY) else Component.translatable(DENIED_KEY, owner.name)
        player.displayClientMessage(message, true)
    }

    private fun onBreak(event: BlockEvent.BreakEvent) {
        val level = event.level as? ServerLevel ?: return
        val be = level.getBlockEntity(event.pos) as? CaseBlockEntity ?: return
        val player = event.player
        if (be.checkAccess(player)) return
        event.isCanceled = true
        deny(player, be.playerAccess)
    }

    private fun onLivingDestroy(event: LivingDestroyBlockEvent) {
        val level = event.entity.level() as? ServerLevel ?: return
        val be = level.getBlockEntity(event.pos) as? CaseBlockEntity ?: return
        if (be.playerAccess.hasOwner) event.isCanceled = true
    }
}
