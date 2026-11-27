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
import pro.ikili.kibicomputers.core.kernel.InterpreterCounts
import pro.ikili.kibicomputers.core.kernel.InterpreterRanking
import pro.ikili.kibicomputers.neoforge.computer.CaseAccess
import pro.ikili.kibicomputers.neoforge.machine.SaveCleanups
import pro.ikili.kibicomputers.neoforge.machine.ServerMachines

/**
 * この MOD のコマンド（根は `/kibicomputers`。#206 と同じ根）。
 *
 * - `/kibicomputers access approve <id>`・`deny <id>`・`list`（#110、ADR 0110）: OS からの持ち主・メンバーの変更の依頼を承認・拒否する。
 *   コマンドの権限レベルは 0 で、中で確かめる（[AccessRequests]。持ち主か OP。OP の判定はコンフィグの `opPermissionLevel`）。
 *   実行できるのは本物のプレイヤーだけ（FakePlayer とコンソールは不可）
 * - `/kibicomputers saves cleanup [dry-run|force]`・`saves status`（#564、ADR 0564）: 残ったディレクトリとモジュールの置き場の掃除を始める・
 *   最後の掃除の結果を見る。OP（コンフィグの `opPermissionLevel`）とコンソールだけ
 * - `/kibicomputers runtime interpreter`（#931、ADR 0088 の「追記（#931）」）: WASM をインタプリタに回した数え口（理由ごとのコンパイルの回数と
 *   プロセスの数）と、インタプリタで動くコードを持つプログラムを多く動かした上位の台（`Machine.id`・位置・ID）を見る。
 *   OP（`opPermissionLevel`）とコンソールだけ。全台をなめるのは打ったときだけ
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
                ).then(
                    Commands.literal("runtime")
                        .requires { it.hasPermission(CaseAccess.rules().opPermissionLevel) }
                        .then(Commands.literal("interpreter").executes { ctx -> interpreter(ctx) }),
                ),
            )
        }
    }

    /** 残ったディレクトリとモジュールの置き場の掃除を始める（#564。結果はログと `status`） */
    private fun cleanup(ctx: CommandContext<CommandSourceStack>, dryRun: Boolean, force: Boolean): Int {
        val started = SaveCleanups.startFromCommand(dryRun, force)
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

    /**
     * インタプリタに回した数え口と、上位の台（#931）。集計と選び方は core（[InterpreterCounts]・[InterpreterRanking]）、ここは台と位置の対応だけ。
     * 返す値は、1回以上数えた台の数
     */
    private fun interpreter(ctx: CommandContext<CommandSourceStack>): Int {
        val manager = ServerMachines.current
        val counts = InterpreterCounts.current(manager?.scheduler)
        val source = ctx.source
        source.sendSuccess({ Component.translatable(KEY_INTERPRETER_COMPILES, counts.compileSummary()) }, false)
        source.sendSuccess({ Component.translatable(KEY_INTERPRETER_PROCESSES, counts.processSummary()) }, false)
        val ranked = InterpreterRanking.top(ServerMachines.trackedCases()) { it.computer.machine?.interpretedProcessCount ?: 0L }
        if (ranked.machines == 0) {
            source.sendSuccess({ Component.translatable(KEY_INTERPRETER_NO_MACHINES) }, false)
            return 0
        }
        for ((i, r) in ranked.top.withIndex()) {
            val be = r.item
            val machine = be.computer.machine
            val line = Component.translatable(
                KEY_INTERPRETER_MACHINE,
                i + 1,
                machine?.id?.toString() ?: "-",
                be.owner?.toString() ?: "-",
                be.currentIdentity?.id?.toString() ?: "-",
                r.count,
                InterpreterCounts.formatReasons(machine?.interpretedReasons.orEmpty()),
                machine?.lastInterpretedProgram ?: "-",
            )
            source.sendSuccess({ line }, false)
        }
        if (ranked.others > 0) source.sendSuccess({ Component.translatable(KEY_INTERPRETER_OTHERS, ranked.others) }, false)
        return ranked.machines
    }

    private const val KEY_INTERPRETER_COMPILES = "message.kibicomputers.runtime.interpreter.compiles"
    private const val KEY_INTERPRETER_PROCESSES = "message.kibicomputers.runtime.interpreter.processes"
    private const val KEY_INTERPRETER_MACHINE = "message.kibicomputers.runtime.interpreter.machine"
    private const val KEY_INTERPRETER_OTHERS = "message.kibicomputers.runtime.interpreter.others"
    private const val KEY_INTERPRETER_NO_MACHINES = "message.kibicomputers.runtime.interpreter.none"

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
