package pro.ikili.kibicomputers.neoforge.gametest

import com.google.gson.JsonParser
import com.mojang.brigadier.exceptions.CommandSyntaxException
import net.minecraft.commands.CommandSource
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.contents.TranslatableContents
import net.minecraft.server.MinecraftServer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.computer.CaseTier
import pro.ikili.kibicomputers.core.computer.ComputerStatus
import pro.ikili.kibicomputers.core.computer.PartKind
import pro.ikili.kibicomputers.core.computer.PartTier
import pro.ikili.kibicomputers.core.kernel.InterpreterCounts
import pro.ikili.kibicomputers.core.kernel.InterpreterRanking
import pro.ikili.kibicomputers.core.runtime.InterpreterReason
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
import pro.ikili.kibicomputers.neoforge.computer.CaseAccess
import pro.ikili.kibicomputers.neoforge.computer.WorldDisks
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks
import pro.ikili.kibicomputers.neoforge.registry.KiBiDataComponents
import java.nio.file.Files
import java.util.Collections

/**
 * `/kibicomputers runtime interpreter`（#931、ADR 0088 の「追記（#931）」）: OP とコンソールだけが打て、インタプリタに回した数え口の要約と、
 * インタプリタで動くコードを持つプログラムを動かした台（`Machine.id`・位置・ID・回数）を返す。
 * 数え口は JVM・サーバー全体の累計で、同じ batch のほかのテストで動くので、全体の値は見ない（集計と選び方は core の `InterpreterCountsTest`）。
 * 台の行は、このテストが置いた台（SIMD の関数を持つ WASM を init にしたディスクで起動する）が、位置・ID・回数付きで出ることを見る
 */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object InterpreterCommandGameTests {
    private const val COMMAND = "kibicomputers runtime interpreter"
    private const val KEY_MACHINE = "message.kibicomputers.runtime.interpreter.machine"
    private const val KEY_BAD_REASON = "message.kibicomputers.runtime.interpreter.bad_reason"

    /** 起動を待つ tick 数の上限（`TestComputerGameTests` と同じ） */
    private const val BOOT_TIMEOUT_TICKS = 12_000

    private val CASE = BlockPos(1, 1, 1)

    /** コマンドが返しうる行（理由の指定がまちがいのときの [KEY_BAD_REASON] は、別に見る） */
    private val LINE_KEYS = listOf(
        "message.kibicomputers.runtime.interpreter.compiles",
        "message.kibicomputers.runtime.interpreter.processes",
        KEY_MACHINE,
        "message.kibicomputers.runtime.interpreter.others",
        "message.kibicomputers.runtime.interpreter.none",
        "message.kibicomputers.runtime.interpreter.none.reason",
    )

    /** lang ファイルにあるべき鍵（行の鍵と、文言を差し込む鍵と、まちがいの指定の鍵） */
    private val ALL_KEYS = LINE_KEYS + listOf(KEY_BAD_REASON, "message.kibicomputers.runtime.interpreter.value.none")

    /** コマンドを実行した結果。[result] は戻り値、[messages] は返ってきた行 */
    private class Run(val result: Int, val messages: List<Component>) {
        /** 行の鍵。失敗の返事（`sendFailure`）は赤く包まれるので、中身の鍵を見る */
        val keys: List<String?> get() = messages.map { keyOf(it) }

        /** 台の行（[KEY_MACHINE]）の引数 */
        val machineLines: List<Array<Any>> get() = messages.mapNotNull { m ->
            (m.contents as? TranslatableContents)?.takeIf { it.key == KEY_MACHINE }?.args
        }
    }

    private fun keyOf(component: Component): String? =
        (component.contents as? TranslatableContents)?.key ?: component.siblings.firstNotNullOfOrNull { keyOf(it) }

    /** [command] を [permission] の権限で打つ。権限が足りなければ [CommandSyntaxException] を投げる */
    private fun execute(server: MinecraftServer, command: String, permission: Int): Run {
        val messages: MutableList<Component> = Collections.synchronizedList(ArrayList())
        val capture = object : CommandSource {
            override fun sendSystemMessage(component: Component) {
                messages += component
            }

            override fun acceptsSuccess(): Boolean = true

            override fun acceptsFailure(): Boolean = true

            override fun shouldInformAdmins(): Boolean = false
        }
        val source = server.createCommandSourceStack().withSource(capture).withPermission(permission)
        val result = server.commands.dispatcher.execute(command, source)
        return Run(result, messages.toList())
    }

    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun interpreterCommandIsForOperatorsAndSummarizes(helper: GameTestHelper) {
        val server = helper.level.server
        val opLevel = CaseAccess.rules().opPermissionLevel
        val rejected = try {
            execute(server, COMMAND, opLevel - 1)
            false
        } catch (e: CommandSyntaxException) {
            true
        }
        helper.assertTrue(rejected, "'$COMMAND' should be rejected without the op permission")
        val run = execute(server, COMMAND, opLevel)
        val keys = run.keys
        helper.assertTrue(run.result >= 0, "the command should succeed: ${run.result}")
        helper.assertTrue(keys.size >= 3, "the command should report the counts and the machines: $keys")
        helper.assertTrue(keys[0] == LINE_KEYS[0] && keys[1] == LINE_KEYS[1], "the counts should come first: $keys")
        helper.assertTrue(keys.drop(2).all { it in LINE_KEYS }, "every line should be a known message: $keys")
        // 台の行は上位の数（InterpreterRanking.DEFAULT_LIMIT）までと、残りの1行
        helper.assertTrue(keys.size <= 2 + InterpreterRanking.DEFAULT_LIMIT + 1, "the command should not list every machine: ${keys.size}")
        // 理由が不明なら断る（台の行は出さない）
        val bad = execute(server, "$COMMAND nonsense", opLevel)
        helper.assertTrue(bad.result == 0 && bad.keys == listOf(KEY_BAD_REASON), "an unknown reason should be rejected: ${bad.keys}")
        // 理由を all と、台数を渡す形は、行の鍵が同じ
        val all = execute(server, "$COMMAND all 1000", opLevel)
        helper.assertTrue(all.keys.drop(2).all { it in LINE_KEYS }, "every line should be a known message: ${all.keys}")
        val limit = execute(server, "$COMMAND all 1", opLevel)
        helper.assertTrue(limit.machineLines.size <= 1, "the limit should cap the machine lines: ${limit.keys}")
        for (lang in listOf("en_us", "ja_jp")) {
            val json = InterpreterCommandGameTests::class.java.getResourceAsStream("/assets/${KiBiComputers.MOD_ID}/lang/$lang.json")
                ?.reader(Charsets.UTF_8)?.use { JsonParser.parseReader(it).asJsonObject }
            helper.assertTrue(json != null, "lang file $lang.json not found")
            val missing = ALL_KEYS.filterNot { json!!.has(it) }
            helper.assertTrue(missing.isEmpty(), "$lang is missing the interpreter command messages: $missing")
            // 空の要約は日本語の固定文字列でなく鍵で出すので、en_us の文言に日本語が混ざらない
            if (lang == "en_us") {
                val japanese = ALL_KEYS.filter { key -> json!!.get(key).asString.any { it in '぀'..'ヿ' || it in '一'..'鿿' } }
                helper.assertTrue(japanese.isEmpty(), "en_us should not contain Japanese: $japanese")
            }
        }
        helper.succeed()
    }

    /**
     * SIMD を使う関数（v128 のローカル変数。SIMD_FUNCTIONS）だけを持つ WASI の `_start`（空ループ）。ディスクの `/sbin/init` に置くと、
     * 起動でインタプリタで動く関数を持つプログラムの exec になり、動き続ける（init が終わると台が止まるので、ループする）。
     * `(module (memory (export "memory") 1) (func (export "_start") (local v128) (loop (br 0))))`
     */
    private val SIMD_INIT: ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00,
        // type: () -> ()
        0x01, 0x04, 0x01, 0x60, 0x00, 0x00,
        // function: 1 つ（型 0）
        0x03, 0x02, 0x01, 0x00,
        // memory: 1 ページ
        0x05, 0x03, 0x01, 0x00, 0x01,
        // export: memory（memory 0）と _start（func 0）
        0x07, 0x13, 0x02,
        0x06, 0x6d, 0x65, 0x6d, 0x6f, 0x72, 0x79, 0x02, 0x00,
        0x06, 0x5f, 0x73, 0x74, 0x61, 0x72, 0x74, 0x00, 0x00,
        // code: v128 のローカル 1 つ、loop { br 0 }
        0x0a, 0x0b, 0x01, 0x09, 0x01, 0x01, 0x7b, 0x03, 0x40, 0x0c, 0x00, 0x0b, 0x0b,
    )

    /**
     * インタプリタで動くコードを持つプログラム（上の [SIMD_INIT]。ディスクの init）を exec した台が、位置・ID・回数付きの行で出る
     * （運用者が `Machine.id` を位置に結ぶ口）。理由で絞っても、台数を渡しても出る
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun interpreterCommandListsTheMachineThatStartedInterpretedCode(helper: GameTestHelper) {
        val store = WorldDisks.current
        helper.assertTrue(store != null, "the server should have a disk store")
        val id = store!!.newId()
        val init = store.directoryOf(id)!!.resolve("sbin/init")
        Files.createDirectories(init.parent)
        Files.write(init, SIMD_INIT)
        val stack = ItemStack(KiBiBlocks.part(PartTier.TIER_2, PartKind.STORAGE))
        stack.set(KiBiDataComponents.DISK_ID.get(), id)
        helper.setBlock(CASE, KiBiBlocks.CASES.getValue(CaseTier.BASIC).get())
        val case = helper.getBlockEntity<CaseBlockEntity>(CASE)
        for (kind in PartKind.entries.filter { it.required && it != PartKind.BOOT && it != PartKind.STORAGE }) {
            case.parts.setStackInSlot(case.tier.firstSlot(kind), ItemStack(Items.IRON_INGOT))
        }
        case.parts.setStackInSlot(case.tier.firstSlot(PartKind.STORAGE), stack)
        helper.assertTrue(case.pressPowerButton() == ComputerStatus.RUNNING, "case should run but was ${case.computer}")
        val machine = case.computer.machine!!
        val server = helper.level.server
        val opLevel = CaseAccess.rules().opPermissionLevel
        helper.succeedWhen {
            helper.assertTrue(case.wiring.bootFailure == null, "boot should not fail but was ${case.wiring.bootFailure}")
            helper.assertTrue(machine.interpretedProcessCount > 0, "the machine should have started a program with interpreted code: ${machine.state}")
            helper.assertTrue(
                machine.interpretedReasons == setOf(InterpreterReason.SIMD_FUNCTIONS),
                "the init should be interpreted for its SIMD function only: ${machine.interpretedReasons}",
            )
            val position = case.owner.toString()
            for (command in listOf("$COMMAND all 1000", "$COMMAND SIMD_FUNCTIONS 1000", "$COMMAND simd_functions 1000")) {
                val run = execute(server, command, opLevel)
                val mine = run.machineLines.filter { it[1] == machine.id.toString() }
                helper.assertTrue(mine.size == 1, "'$command' should list the machine once (id ${machine.id}): ${run.messages}")
                val line = mine.single()
                helper.assertTrue(line[2] == position, "the line should carry the position $position: ${line.toList()}")
                val identity = case.currentIdentity?.id?.toString() ?: "-"
                helper.assertTrue(line[3] == identity, "the line should carry the machine ID $identity: ${line.toList()}")
                helper.assertTrue((line[4] as Number).toLong() >= 1L, "the line should carry the process count: ${line.toList()}")
                helper.assertTrue(run.result >= 1, "the command should return the number of machines: ${run.result}")
            }
            // 別の理由で絞ると、この台は出ない（理由別の回数で並べる）
            val other = execute(server, "$COMMAND TOO_LARGE_FUNCTIONS 1000", opLevel)
            helper.assertTrue(other.machineLines.none { it[1] == machine.id.toString() }, "the machine should not be listed for another reason: ${other.messages}")
            case.pressPowerButton()
            helper.setBlock(CASE, Blocks.AIR)
        }
    }

    /** 負荷試験の出力の節（#931）: どの段階も 0 なら1行、0 でない段階があれば段階の名前付きで並べる */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun loadReportSectionListsOnlyPhasesThatInterpreted(helper: GameTestHelper) {
        val none = interpreterSection(listOf("a" to InterpreterCounts.ZERO, "b" to InterpreterCounts.ZERO))
        helper.assertTrue(none.contains("どの段階も 0") && !none.contains("- a:"), "all zero should be one line: $none")
        // 差が 0 でない数え口: JVM 全体のコンパイルの回数は、起動の自己診断などで 0 より大きい
        val total = InterpreterCounts.current(null) - InterpreterCounts.ZERO
        if (!total.isZero) {
            val some = interpreterSection(listOf("zero" to InterpreterCounts.ZERO, "nonzero" to total))
            helper.assertTrue(some.contains("- nonzero: $total") && !some.contains("- zero:"), "only a non-zero phase should be listed: $some")
        }
        helper.succeed()
    }
}
