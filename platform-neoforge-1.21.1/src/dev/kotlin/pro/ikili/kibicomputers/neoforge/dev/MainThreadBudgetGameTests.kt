package pro.ikili.kibicomputers.neoforge.dev

import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestAssertException
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.computer.ComputerStatus
import pro.ikili.kibicomputers.core.input.Key
import pro.ikili.kibicomputers.core.input.KeyboardEvent
import pro.ikili.kibicomputers.core.input.KeyboardEventCodec
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
import pro.ikili.kibicomputers.neoforge.display.ScreenBlockEntity
import pro.ikili.kibicomputers.neoforge.gametest.LoadGameTests
import pro.ikili.kibicomputers.neoforge.gametest.LoadOs
import pro.ikili.kibicomputers.neoforge.gametest.LoadRun
import pro.ikili.kibicomputers.neoforge.gametest.SmokeGameTests
import pro.ikili.kibicomputers.neoforge.input.KeyboardBlockEntity
import pro.ikili.kibicomputers.neoforge.machine.ServerMachines
import java.util.UUID

/**
 * メインスレッドの予算（#356、ADR 0356）を、ゲストから他 MOD 役のデバイスのメインスレッドのメソッドを呼んで確かめる GameTest。
 * 負荷試験（`docs/load-test.md` の「メインスレッドの予算」）の小さな版。時間の閾値は混んだ機械で揺れるので見ず（#871）、
 * 予算で打ち切った tick が増えること、呼び出しが全部、エラーなしで終わることだけを見る
 */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object MainThreadBudgetGameTests {
    private val TYPIST: UUID = UUID(0, 0x356)

    /** 同時に積む呼び出しの数（コルーチンの数。1プロセスの上限 `MAX_PENDING_CALLS` と同じ） */
    private const val COROUTINES = 16

    /** コルーチン1つが呼ぶ回数 */
    private const val CALLS_PER_COROUTINE = 4

    /** 1件の重さ（マイクロ秒）。既定の予算 5 ms に、1 tick で2件まで入る */
    private const val BURN_MICROS = 3_000L

    /**
     * 上限の tick 数。Lua の REPL を冷えた状態から起動するので、ほかの Lua の起動を待つ GameTest（`TestComputerGameTests` など）と同じにする。
     * GameTest のサーバーは待たずに tick を進めるので、lua.wasm の読み込みと起動が終わる前に普通の上限を使い切ることがある
     * （同じバッチで `loadgametests.ci` の 100 台も起動する。#871 と同じ種類の揺れ）
     */
    private const val BOOT_TIMEOUT_TICKS = 12_000

    /**
     * Lua の REPL から、樽（`kibitest:reactor`）の重いメインスレッドのメソッド `burn` を 16 のコルーチンで同時に積む。
     * 予算（既定 5 ms）を超えた分は次の tick に回り（打ち切った tick が増える）、ゲストにはエラーを返さずに全部が終わる
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, timeoutTicks = BOOT_TIMEOUT_TICKS)
    fun heavyMainThreadCallsAreSpreadOverTicksByTheBudget(helper: GameTestHelper) {
        val casePos = BlockPos(1, 1, 1)
        LoadGameTests.Layout.placeComputer(helper, casePos, LoadOs.LUA)
        helper.setBlock(casePos.north(), Blocks.BARREL)
        val case = helper.getBlockEntity<CaseBlockEntity>(casePos)
        val screen = helper.getBlockEntity<ScreenBlockEntity>(casePos.above())
        val keyboard = helper.getBlockEntity<KeyboardBlockEntity>(casePos.south())
        helper.assertTrue(case.pressPowerButton() == ComputerStatus.RUNNING, "case should be running")
        val scheduler = ServerMachines.current?.scheduler ?: throw GameTestAssertException("no running scheduler")
        // 足すのは結果を受け取ってから（n=n+r.burn() だと、n を読んでから yield するので、ほかのコルーチンの分を上書きする）
        val line = "local r=peripheral.find(\"reactor\") local n=0 local f=function() for i=1,$CALLS_PER_COROUTINE do " +
            "local v=r.$BURN_METHOD($BURN_MICROS) n=n+v end end local t={} for i=1,$COROUTINES do t[i]=f end " +
            "parallel.waitForAll(table.unpack(t)) print(\"burned \"..n)"
        var typed = false
        var cutAtStart = 0L
        var callsAtStart = 0L
        var done = false

        fun cleanUp() {
            if (case.computer.status == ComputerStatus.RUNNING) case.pressPowerButton()
            for (p in listOf(casePos, casePos.above(), casePos.south(), casePos.north())) helper.setBlock(p, Blocks.AIR)
        }

        helper.onEachTick {
            if (done) return@onEachTick
            try {
                val text = LoadRun.lines(screen.text)
                if (!typed) {
                    if (!LoadRun.hasPrompt(screen.text, LoadOs.LUA.prompt)) return@onEachTick
                    // 樽がつながっていなければ、find が nil を返してエラーになる
                    cutAtStart = scheduler.budgetCutTickCount
                    callsAtStart = case.computer.machine?.hostCallRunCount ?: 0L
                    for (packet in KeyboardEventCodec.encode(listOf(KeyboardEvent.Text(line), KeyboardEvent.KeyDown(Key.ENTER, 0, false)))) {
                        helper.assertTrue(keyboard.receive(TYPIST, packet) >= 0, "the keyboard should accept the input")
                    }
                    typed = true
                    return@onEachTick
                }
                val result = text.firstOrNull { it.startsWith("burned ") }
                if (result == null) {
                    // 行を読んで（エコーが出て）からプロンプトに戻ったなら、エラーで止まった
                    val returned = text.any { it.startsWith("> local r=") } && text.lastOrNull { it.isNotEmpty() } == ">"
                    if (returned) throw GameTestAssertException("the Lua line should finish without errors: $text")
                    return@onEachTick
                }
                val calls = COROUTINES * CALLS_PER_COROUTINE
                val burned = result.removePrefix("burned ").trim().toLongOrNull()
                helper.assertTrue(burned != null && burned >= calls * BURN_MICROS, "every call should burn $BURN_MICROS us: $text")
                val ran = (case.computer.machine?.hostCallRunCount ?: 0L) - callsAtStart
                helper.assertTrue(ran >= calls, "the scheduler should run all $calls host calls on the main thread, but $ran")
                // 16 件が同時に積まれ、1 tick に2件までしか入らないので、残りを持ち越した tick が何度もある
                val cut = scheduler.budgetCutTickCount - cutAtStart
                helper.assertTrue(cut >= 4, "heavy host calls over the budget should be cut and carried over to later ticks, but cut $cut ticks")
                done = true
                cleanUp()
                helper.succeed()
            } catch (t: Throwable) {
                done = true
                cleanUp()
                throw t
            }
        }
    }

    /** [TestReactor.BURN] */
    private const val BURN_METHOD = TestReactor.BURN
}
