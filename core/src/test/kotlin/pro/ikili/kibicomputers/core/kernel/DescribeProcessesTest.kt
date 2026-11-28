package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.Program
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.runtime.WasmBinary
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.time.Duration
import pro.ikili.kibicomputers.core.testing.bodyTimeout as timeout

/**
 * [Machine.describeProcesses]（#636 の切り分けのログ）が、プレイヤーが決められる argv[0] をそのままログに出さないこと。
 * 制御文字は `?` にし（改行でログに偽の行を作らせない）、長い名前は切る。
 */
class DescribeProcessesTest {
    private val limit = Duration.ofSeconds(60)

    private fun program(): Program = Program.load(EndiveRuntime(), WasmBinary().build())

    @Test
    fun `argv0の制御文字を置き換え、長い名前を切る`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            // 走らせると終わって一覧から消えるので、割り当てを止めておく
            val hold = m.quiesce()
            val evil = "sh\n[main/INFO] fake\u001b[2J"
            m.spawn(program(), TestDriver(ProcessKind.BLOCKING) { StepResult.Exit(0) }, SpawnOptions(args = listOf(evil), allowUnmetered = true), 0)
            m.spawn(program(), TestDriver(ProcessKind.BLOCKING) { StepResult.Exit(0) }, SpawnOptions(args = listOf("x".repeat(1000)), allowUnmetered = true), 0)
            val line = m.describeProcesses()
            assertFalse(line.any { Character.isISOControl(it) }, "制御文字を出さない: $line")
            assertTrue(line.contains("sh?[main/INFO] fake?[2J"), "制御文字は ? にする: $line")
            assertTrue(line.contains("x".repeat(64) + "..."), "長い名前は切る: $line")
            assertFalse(line.contains("x".repeat(65)), "切った後ろは出さない: $line")
            assertEquals(2, line.split("; ").size, "プロセスごとに1つ: $line")
            hold.close()
        }
    }
}
