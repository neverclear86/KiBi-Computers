package pro.ikili.kibicomputers.neoforge.gametest

import com.google.gson.JsonParser
import com.mojang.brigadier.exceptions.CommandSyntaxException
import net.minecraft.commands.CommandSource
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.contents.TranslatableContents
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.kernel.InterpreterRanking
import pro.ikili.kibicomputers.neoforge.computer.CaseAccess
import java.util.Collections

/**
 * `/kibicomputers runtime interpreter`（#931、ADR 0088 の「追記（#931）」）: OP とコンソールだけが打て、インタプリタに回した数え口の要約を返す。
 * 数え口は JVM・サーバー全体の累計で、同じ batch のほかのテストで動くので、値は見ない（集計と選び方は core の `InterpreterCountsTest`）
 */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object InterpreterCommandGameTests {
    private const val COMMAND = "kibicomputers runtime interpreter"

    private val KEYS = listOf(
        "message.kibicomputers.runtime.interpreter.compiles",
        "message.kibicomputers.runtime.interpreter.processes",
        "message.kibicomputers.runtime.interpreter.machine",
        "message.kibicomputers.runtime.interpreter.others",
        "message.kibicomputers.runtime.interpreter.none",
    )

    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun interpreterCommandIsForOperatorsAndSummarizes(helper: GameTestHelper) {
        val server = helper.level.server
        val dispatcher = server.commands.dispatcher
        val opLevel = CaseAccess.rules().opPermissionLevel
        val messages: MutableList<Component> = Collections.synchronizedList(ArrayList())
        val capture = object : CommandSource {
            override fun sendSystemMessage(component: Component) {
                messages += component
            }

            override fun acceptsSuccess(): Boolean = true

            override fun acceptsFailure(): Boolean = true

            override fun shouldInformAdmins(): Boolean = false
        }
        val notOp = server.createCommandSourceStack().withSource(capture).withPermission(opLevel - 1)
        val rejected = try {
            dispatcher.execute(COMMAND, notOp)
            false
        } catch (e: CommandSyntaxException) {
            true
        }
        helper.assertTrue(rejected, "'$COMMAND' should be rejected without the op permission")
        helper.assertTrue(messages.isEmpty(), "a rejected command should not report: $messages")
        val op = server.createCommandSourceStack().withSource(capture).withPermission(opLevel)
        val result = dispatcher.execute(COMMAND, op)
        val keys = messages.map { (it.contents as? TranslatableContents)?.key }
        helper.assertTrue(result >= 0, "the command should succeed: $result")
        helper.assertTrue(keys.size >= 3, "the command should report the counts and the machines: $keys")
        helper.assertTrue(keys[0] == KEYS[0] && keys[1] == KEYS[1], "the counts should come first: $keys")
        helper.assertTrue(keys.drop(2).all { it in KEYS }, "every line should be a known message: $keys")
        // 台の行は上位の数（InterpreterRanking.DEFAULT_LIMIT）までと、残りの1行
        helper.assertTrue(keys.size <= 2 + InterpreterRanking.DEFAULT_LIMIT + 1, "the command should not list every machine: ${keys.size}")
        for (lang in listOf("en_us", "ja_jp")) {
            val json = InterpreterCommandGameTests::class.java.getResourceAsStream("/assets/${KiBiComputers.MOD_ID}/lang/$lang.json")
                ?.reader(Charsets.UTF_8)?.use { JsonParser.parseReader(it).asJsonObject }
            helper.assertTrue(json != null, "lang file $lang.json not found")
            val missing = KEYS.filterNot { json!!.has(it) }
            helper.assertTrue(missing.isEmpty(), "$lang is missing the interpreter command messages: $missing")
        }
        helper.succeed()
    }
}
