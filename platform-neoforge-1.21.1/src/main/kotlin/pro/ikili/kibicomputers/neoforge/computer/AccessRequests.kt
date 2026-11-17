package pro.ikili.kibicomputers.neoforge.computer

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import pro.ikili.kibicomputers.core.access.AccessApproval
import pro.ikili.kibicomputers.core.access.AccessChange
import pro.ikili.kibicomputers.core.access.AccessRequest
import pro.ikili.kibicomputers.core.access.AccessRequestBook
import pro.ikili.kibicomputers.core.access.AccessRequestKind
import pro.ikili.kibicomputers.core.access.PlayerRef
import java.lang.ref.WeakReference
import java.util.Locale

/**
 * OS からの依頼（`kibi:computer/access`。#110、ADR 0110）の、サーバー側の受け取りと承認・拒否。サーバーのメインスレッドだけで使う。
 *
 * - 依頼はサーバー全体の表（[book]。ID → 依頼。上限 [MAX_ENTRIES] 件）に、ケースの BE の弱い参照と一緒に載せる。保存しない
 *   （サーバーを閉じると捨てる）。期限切れは、届け・承認・拒否・一覧のときに遅れて捨てる（tick ごとには見回らない）
 * - 届いたら（[deliver]）、持ち主（持ち主のいないケースの持ち主の変更では、相手本人）がオンラインなら、承認・拒否のボタンの付いた
 *   チャットを送る。OP 全員には送らない。ログインのときにも知らせない（ADR 0131 の「負荷とスレッド」）
 * - 承認・拒否（[approve]・[deny]）の権限は、そのときの [CaseBlockEntity.playerAccess] で判定する（[AccessApproval.mayDecide]）。
 *   「依頼がない」「BE が無効」「チャンクが FULL でない」「権限がない」は、同じ文言で返す（連番の ID を当てても、他人のケースに依頼が
 *   あるかが分からないように）
 * - BE の確かめ（[check]）は、`level.isLoaded(pos)` を見てから、真のときだけ `level.getBlockEntity(pos)` を呼ぶ（チャンクを読み込まない）
 */
internal object AccessRequests {
    /** 表の大きさの上限（仮値。確定は #251） */
    const val MAX_ENTRIES: Int = 4096

    /** コマンドの根と、依頼を扱うコマンド（#206 と根をそろえる） */
    const val COMMAND_ROOT: String = "kibicomputers"

    const val KEY_REQUEST_ADD: String = "message.kibicomputers.access.request.add"
    const val KEY_REQUEST_REMOVE: String = "message.kibicomputers.access.request.remove"
    const val KEY_REQUEST_TRANSFER: String = "message.kibicomputers.access.request.transfer"
    const val KEY_APPROVE_BUTTON: String = "message.kibicomputers.access.request.approve_button"
    const val KEY_DENY_BUTTON: String = "message.kibicomputers.access.request.deny_button"
    const val KEY_UNKNOWN: String = "message.kibicomputers.access.request.unknown"
    const val KEY_PLAYERS_ONLY: String = "message.kibicomputers.access.request.players_only"
    const val KEY_DENIED: String = "message.kibicomputers.access.request.denied"
    const val KEY_APPROVED_ADD: String = "message.kibicomputers.access.request.approved_add"
    const val KEY_APPROVED_REMOVE: String = "message.kibicomputers.access.request.approved_remove"
    const val KEY_APPROVED_TRANSFER: String = "message.kibicomputers.access.request.approved_transfer"
    const val KEY_UNCHANGED: String = "message.kibicomputers.access.request.unchanged"
    const val KEY_TARGET_OFFLINE: String = "message.kibicomputers.access.request.target_offline"
    const val KEY_LIST_EMPTY: String = "message.kibicomputers.access.request.list_empty"
    const val KEY_LIST_ENTRY: String = "message.kibicomputers.access.request.list_entry"

    /** 変わらなかった理由（[AccessChange]）の文言のキーの始まり */
    const val KEY_REASON_PREFIX: String = "message.kibicomputers.access.request.reason."

    /** BE の確かめ（[check]）の結果 */
    enum class Validity {
        /** 依頼を出した BE が、今その位置にある */
        VALID,

        /** チャンクが FULL でない（#545 の降格中）。表にも枠にも触らない。FULL に戻れば扱える */
        UNAVAILABLE,

        /** BE がもうない・別の BE に置き換わった。表から捨てる */
        INVALID,
    }

    /** サーバー全体の承認待ちの表（メインスレッドだけ） */
    private var book = AccessRequestBook<WeakReference<CaseBlockEntity>>(MAX_ENTRIES)

    fun register() {
        NeoForge.EVENT_BUS.addListener<ServerStoppedEvent> { book = AccessRequestBook(MAX_ENTRIES) }
    }

    /** 表の1件を取り出さずに見る（テスト用） */
    internal fun peek(id: Long): AccessRequestBook.Entry<WeakReference<CaseBlockEntity>>? {
        val rules = CaseAccess.requestRules()
        return book.peek(id, System.currentTimeMillis(), rules.ttlMillis)
    }

    /**
     * [be] の依頼 [request] が届いた（[CaseAccessPort.deliver]。メインスレッド）。BE をそのまま使い、`level.getBlockEntity` は呼ばない。
     * 今の [CaseBlockEntity.playerAccess] で振り分ける:
     * - 持ち主の変更: 相手の名前をオンラインのプレイヤーから引いて UUID を固定する（オフラインなら捨てる）。持ち主ありなら持ち主に、
     *   持ち主なしなら相手本人に知らせる。読めなかった記録なら知らせない（OP が一覧から承認する）
     * - 追加・削除: 持ち主なし・読めなかった記録なら捨てる。名前での削除は、その名前のオンラインのプレイヤーを相手に固定する（いなければ保留だけを外す）
     *
     * 捨てたときは、このケースの枠を返す。名前から UUID を引くのはオンラインのプレイヤーだけ（`GameProfileCache` は、キャッシュにないと
     * Mojang に同期で問い合わせ、メインスレッドを止めるので使わない）
     *
     * @return 表に載せた依頼の ID（捨てたら `null`）
     */
    fun deliver(be: CaseBlockEntity, request: AccessRequest): Long? {
        val limiter = be.accessPort.limiter
        val level = be.level as? ServerLevel
        if (be.isRemoved || level == null) {
            limiter.release(request)
            return null
        }
        val players = level.server.playerList
        val access = be.playerAccess
        val owner = access.owner
        var target: PlayerRef? = null
        val notify: ServerPlayer?
        if (request.kind == AccessRequestKind.TRANSFER) {
            val player = players.getPlayerByName(request.argument)
            if (player == null) {
                limiter.release(request)
                return null
            }
            target = CaseAccess.ref(player)
            notify = when {
                access.locked -> null
                owner == null -> player
                else -> players.getPlayer(owner.id)
            }
        } else {
            if (access.locked || owner == null) {
                limiter.release(request)
                return null
            }
            if (request.kind == AccessRequestKind.REMOVE && request.uuidArgument == null) {
                target = players.getPlayerByName(request.argument)?.let(CaseAccess::ref)
            }
            notify = players.getPlayer(owner.id)
        }
        val rules = be.accessPort.requestRules()
        val entry = book.add(WeakReference(be), request, target, System.currentTimeMillis(), rules.ttlMillis)
        if (entry == null) {
            limiter.release(request)
            return null
        }
        notify?.sendSystemMessage(notification(entry.id, request, be, level))
        return entry.id
    }

    /**
     * 依頼を出した BE が今も有効か。順に:
     * 1. 参照が消えた・`isRemoved`・level が [ServerLevel] でない → [Validity.INVALID]（ここまではワールドに触らない）
     * 2. `!level.isLoaded(pos)`（チャンクが FULL でない）→ [Validity.UNAVAILABLE]（`getBlockEntity` は呼ばない）
     * 3. `level.getBlockEntity(pos) !== be` → [Validity.INVALID]（壊して置き直した別のケースには当てない）
     *
     * `Level.getBlockEntity` は FULL でないチャンクを同期で読み込む・格上げするので、必ず `isLoaded` の後に呼ぶ（前例は `KeyboardSessions.isValid`）
     */
    fun check(entry: AccessRequestBook.Entry<WeakReference<CaseBlockEntity>>): Validity {
        val be = entry.holder.get() ?: return Validity.INVALID
        if (be.isRemoved) return Validity.INVALID
        val level = be.level as? ServerLevel ?: return Validity.INVALID
        if (!level.isLoaded(be.blockPos)) return Validity.UNAVAILABLE
        return if (level.getBlockEntity(be.blockPos) === be) Validity.VALID else Validity.INVALID
    }

    /** 承認・拒否の共通の前半: peek → BE の確かめ → 権限。扱えるなら (表の1件, BE)、扱えなければ `null`（返す文言は [KEY_UNKNOWN]） */
    private fun decidable(actor: ServerPlayer, id: Long, now: Long, ttl: Long): Pair<AccessRequestBook.Entry<WeakReference<CaseBlockEntity>>, CaseBlockEntity>? {
        val entry = book.peek(id, now, ttl) ?: return null
        when (check(entry)) {
            Validity.INVALID -> {
                // 元の BE と一緒に枠も捨てられるので、返さない（新しい BE の枠には触らない）
                book.take(id, now, ttl)
                return null
            }
            Validity.UNAVAILABLE -> return null
            Validity.VALID -> Unit
        }
        val be = entry.holder.get() ?: return null
        val rules = CaseAccess.rules()
        if (!AccessApproval.mayDecide(be.playerAccess, entry.request, entry.target, CaseAccess.ref(actor), CaseAccess.isOp(actor, rules))) return null
        return entry to be
    }

    /** `approve <id>`。[actor] は本物のプレイヤー（呼び手が確かめる）。戻り値は実行者への文言と、成功したか */
    fun approve(actor: ServerPlayer, id: Long): Pair<Component, Boolean> {
        val now = System.currentTimeMillis()
        val ttl = CaseAccess.requestRules().ttlMillis
        val (entry, be) = decidable(actor, id, now, ttl) ?: return unknown()
        val rules = CaseAccess.rules()
        val decision = AccessApproval.apply(be.playerAccess, entry.request, entry.target, CaseAccess.ref(actor), CaseAccess.isOp(actor, rules), now, rules)
        return when (decision.outcome) {
            AccessApproval.Outcome.APPLIED -> {
                book.take(id, now, ttl)
                be.replacePlayerAccess(decision.access)
                be.accessPort.limiter.release(entry.request)
                val key = when (entry.request.kind) {
                    AccessRequestKind.ADD -> KEY_APPROVED_ADD
                    AccessRequestKind.REMOVE -> KEY_APPROVED_REMOVE
                    AccessRequestKind.TRANSFER -> KEY_APPROVED_TRANSFER
                }
                Component.translatable(key, subject(entry), place(be)) to true
            }
            AccessApproval.Outcome.NO_CHANGE -> {
                book.take(id, now, ttl)
                be.accessPort.limiter.release(entry.request)
                val reason = decision.change?.let { Component.translatable(KEY_REASON_PREFIX + it.name.lowercase(Locale.ROOT)) } ?: Component.empty()
                Component.translatable(KEY_UNCHANGED, reason) to true
            }
            AccessApproval.Outcome.TARGET_OFFLINE -> Component.translatable(KEY_TARGET_OFFLINE, entry.request.argument) to false
            AccessApproval.Outcome.NOT_PERMITTED -> unknown()
        }
    }

    /** `deny <id>`。表から外すが、枠は返さない（期限まで占める。拒否した依頼を繰り返させない） */
    fun deny(actor: ServerPlayer, id: Long): Pair<Component, Boolean> {
        val now = System.currentTimeMillis()
        val ttl = CaseAccess.requestRules().ttlMillis
        val (entry, be) = decidable(actor, id, now, ttl) ?: return unknown()
        book.take(id, now, ttl)
        return Component.translatable(KEY_DENIED, describe(entry.request), place(be)) to true
    }

    /**
     * `list`: [actor] が承認・拒否できる依頼の一覧の行。1件ごとに、参照 → `isRemoved`・[ServerLevel] → 権限（ここまでワールドに触らない）→
     * `isLoaded` → `getBlockEntity` の順に確かめる。権限のない依頼は `isLoaded` の前に落ちる。降格中・無効な依頼は見せないだけで、表からは外さない
     */
    fun list(actor: ServerPlayer): List<Component> {
        val now = System.currentTimeMillis()
        val ttl = CaseAccess.requestRules().ttlMillis
        val rules = CaseAccess.rules()
        val ref = CaseAccess.ref(actor)
        val isOp = CaseAccess.isOp(actor, rules)
        val out = ArrayList<Component>()
        for (entry in book.list(now, ttl)) {
            val be = entry.holder.get() ?: continue
            if (be.isRemoved) continue
            val level = be.level as? ServerLevel ?: continue
            if (!AccessApproval.mayDecide(be.playerAccess, entry.request, entry.target, ref, isOp)) continue
            if (!level.isLoaded(be.blockPos) || level.getBlockEntity(be.blockPos) !== be) continue
            out += Component.translatable(KEY_LIST_ENTRY, entry.id, describe(entry.request), place(be)).append(" ").append(buttons(entry.id))
        }
        return out
    }

    private fun unknown(): Pair<Component, Boolean> = Component.translatable(KEY_UNKNOWN) to false

    /** 持ち主（か相手本人）に送る知らせ: 依頼の中身・場所と、承認・拒否のボタン */
    private fun notification(id: Long, request: AccessRequest, be: CaseBlockEntity, level: ServerLevel): Component =
        Component.literal("[KiBi] ").withStyle(ChatFormatting.GOLD)
            .append(describe(request).copy().withStyle(ChatFormatting.WHITE))
            .append(Component.literal(" @ ").withStyle(ChatFormatting.GRAY))
            .append(place(be, level).withStyle(ChatFormatting.GRAY))
            .append(" ")
            .append(buttons(id))

    /** 依頼の中身（「NAME をメンバーに足す」など） */
    fun describe(request: AccessRequest): MutableComponent = Component.translatable(
        when (request.kind) {
            AccessRequestKind.ADD -> KEY_REQUEST_ADD
            AccessRequestKind.REMOVE -> KEY_REQUEST_REMOVE
            AccessRequestKind.TRANSFER -> KEY_REQUEST_TRANSFER
        },
        request.argument,
    )

    private fun subject(entry: AccessRequestBook.Entry<*>): String = entry.target?.name ?: entry.request.argument

    private fun place(be: CaseBlockEntity, level: ServerLevel? = be.level as? ServerLevel): MutableComponent {
        val p = be.blockPos
        val dim = level?.dimension()?.location()?.toString() ?: "?"
        return Component.literal("$dim ${p.x} ${p.y} ${p.z}")
    }

    private fun buttons(id: Long): Component =
        button(KEY_APPROVE_BUTTON, "approve", id, ChatFormatting.GREEN).append(" ").append(button(KEY_DENY_BUTTON, "deny", id, ChatFormatting.RED))

    private fun button(key: String, verb: String, id: Long, color: ChatFormatting): MutableComponent {
        val command = "/$COMMAND_ROOT access $verb $id"
        return Component.literal("[").append(Component.translatable(key)).append("]").withStyle { s ->
            s.withColor(color)
                .withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(command)))
        }
    }
}
