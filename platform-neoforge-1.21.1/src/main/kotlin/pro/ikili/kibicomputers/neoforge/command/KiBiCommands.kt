package pro.ikili.kibicomputers.neoforge.command

import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.common.util.FakePlayer
import net.neoforged.neoforge.event.RegisterCommandsEvent
import pro.ikili.kibicomputers.neoforge.computer.AccessRequests
import pro.ikili.kibicomputers.neoforge.computer.CaseAccess
import pro.ikili.kibicomputers.neoforge.machine.SaveCleanups

/**
 * この MOD のコマンド（根は `/kibicomputers`。#206 と同じ根）。
 *
 * - `/kibicomputers access approve <id>`・`deny <id>`・`list`（#110、ADR 0110）: OS からの持ち主・メンバーの変更の依頼を承認・拒否する。
 *   コマンドの権限レベルは 0 で、中で確かめる（[AccessRequests]。持ち主か OP。OP の判定はコンフィグの `opPermissionLevel`）。
 *   実行できるのは本物のプレイヤーだけ（FakePlayer とコンソールは不可）
 * - `/kibicomputers saves cleanup [dry-run|force]`・`saves status`（#564、ADR 0564）: 残ったディレクトリとモジュールの置き場の掃除を始める・
 *   最後の掃除の結果を見る。OP（コンフィグの `opPermissionLevel`）とコンソールだけ
 */
internal object KiBiCommands {
    fun register() {
        NeoForge.EVENT_BUS.addListener<RegisterCommandsEvent> { event ->
            event.dispatcher.register(
                Commands.literal(AccessRequests.COMMAND_ROOT).then(
                    Commands.literal("access")
                        .then(Commands.literal("approve").then(idArgument { ctx, player -> AccessRequests.approve(player, LongArgumentType.getLong(ctx, "id")) }))
                        .then(Commands.literal("deny").then(idArgument { ctx, player -> AccessRequests.deny(player, LongArgumentType.getLong(ctx, "id")) }))
                        .then(Commands.literal("list").executes { ctx -> list(ctx) }),
                ).then(
                    Commands.literal("saves")
                        .requires { it.hasPermission(CaseAccess.rules().opPermissionLevel) }
                        .then(
                            Commands.literal("cleanup")
                                .executes { ctx -> cleanup(ctx, dryRun = false, force = false) }
                                .then(Commands.literal("dry-run").executes { ctx -> cleanup(ctx, dryRun = true, force = false) })
                                .then(Commands.literal("force").executes { ctx -> cleanup(ctx, dryRun = false, force = true) }),
                        )
                        .then(Commands.literal("status").executes { ctx -> status(ctx) }),
                ),
            )
        }
    }

    /** 残ったディレクトリとモジュールの置き場の掃除を始める（#564。結果はログと `status`） */
    private fun cleanup(ctx: CommandContext<CommandSourceStack>, dryRun: Boolean, force: Boolean): Int {
        val started = SaveCleanups.start(dryRun, force)
            ?: return reply(ctx.source, Component.translatable(KEY_CLEANUP_BUSY), false)
        val mode = when {
            started.dryRun -> "dry-run"
            started.force -> "force"
            else -> "normal"
        }
        return reply(ctx.source, Component.translatable(KEY_CLEANUP_STARTED, mode), true)
    }

    /** 最後の掃除の段階と数 */
    private fun status(ctx: CommandContext<CommandSourceStack>): Int {
        val last = SaveCleanups.last ?: return reply(ctx.source, Component.translatable(KEY_CLEANUP_NONE), true).let { 0 }
        return reply(ctx.source, Component.translatable(KEY_CLEANUP_STATUS, last.phase.name, last.report.toString()), true)
    }

    private const val KEY_CLEANUP_STARTED = "message.kibicomputers.saves.cleanup.started"
    private const val KEY_CLEANUP_BUSY = "message.kibicomputers.saves.cleanup.busy"
    private const val KEY_CLEANUP_NONE = "message.kibicomputers.saves.cleanup.none"
    private const val KEY_CLEANUP_STATUS = "message.kibicomputers.saves.cleanup.status"

    private fun idArgument(action: (CommandContext<CommandSourceStack>, ServerPlayer) -> Pair<Component, Boolean>) =
        Commands.argument("id", LongArgumentType.longArg(1)).executes { ctx ->
            val player = player(ctx) ?: return@executes 0
            val (message, ok) = action(ctx, player)
            reply(ctx.source, message, ok)
        }

    private fun list(ctx: CommandContext<CommandSourceStack>): Int {
        val player = player(ctx) ?: return 0
        val lines = AccessRequests.list(player)
        if (lines.isEmpty()) return reply(ctx.source, Component.translatable(AccessRequests.KEY_LIST_EMPTY), true).let { 0 }
        for (line in lines) ctx.source.sendSuccess({ line }, false)
        return lines.size
    }

    /** 実行者が本物のプレイヤーならそれを、そうでなければ断る文言を返して `null` */
    private fun player(ctx: CommandContext<CommandSourceStack>): ServerPlayer? {
        val player = ctx.source.player
        if (player == null || player is FakePlayer) {
            ctx.source.sendFailure(Component.translatable(AccessRequests.KEY_PLAYERS_ONLY))
            return null
        }
        return player
    }

    private fun reply(source: CommandSourceStack, message: Component, ok: Boolean): Int {
        if (ok) source.sendSuccess({ message }, false) else source.sendFailure(message)
        return if (ok) 1 else 0
    }
}
