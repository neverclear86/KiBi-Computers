package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.kernel.boot.BootVolume
import pro.ikili.kibicomputers.core.kernel.boot.BootVolumeOpener
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.vfs.ImageFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.core.persist.DeltaBase
import pro.ikili.kibicomputers.core.persist.FullSaveRule
import pro.ikili.kibicomputers.core.persist.MachineSnapshot
import pro.ikili.kibicomputers.core.persist.StoredSnapshot
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Duration
import java.util.UUID
import java.util.zip.CRC32C
import pro.ikili.kibicomputers.core.testing.bodyTimeout as timeout

/**
 * 標準 OS（本物の `/sbin/init` と `/bin/sh`）で、全体か差分かの規則（[FullSaveRule]。#668、ADR 0083 の規則 3）を確かめる。
 *
 * 全体の保存の後に、シェルで 1 行・数行のコマンド、子プロセスを起こすコマンド、Lua の REPL の 1 行を動かし（基準の後に起こしたプロセスと、
 * 基準を持たない台＝復元の直後も試す）、変わった区画と書き換えた区画の数
 * （`Machine.changedChunks`）と、差分・全体の世代の大きさを測って標準出力に出す（測った値は ADR 0083 の付録に残す）。
 * 確かめるのは判断の結果と余裕のある上限だけにする（区画の数は OS の変更でずれる。#667）
 */
class StandardOsDeltaRuleTest {
    private val limit = Duration.ofSeconds(240)
    private val policy = CapturePolicy.SKIP_IF_TRANSIENT

    private class Host : MachineHost {
        private val out = ByteArrayOutputStream()

        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit

        override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
            synchronized(this) { out.write(bytes, 0, bytes.size) }
        }

        fun text(): String = synchronized(this) { out.toString(Charsets.UTF_8) }

        fun bytes(): Int = synchronized(this) { out.size() }
    }

    private fun bytesOf(snapshot: MachineSnapshot): ByteArray =
        ByteArrayOutputStream().also { out -> DataOutputStream(out).use { snapshot.writeTo(it) } }.toByteArray()

    private fun stored(bytes: ByteArray): StoredSnapshot = StoredSnapshot(
        MachineSnapshot.readFrom(DataInputStream(bytes.inputStream())),
        bytes.size.toLong(),
        CRC32C().also { it.update(bytes) }.value.toInt(),
    )

    /**
     * 出力が [prompt] で終わり、入力を読み切って、生きている全プロセスが入力を待って（`WAITING_EVENT`）止まり、出力が止まるまで回す。
     * [Machine.isAtWaitPoint] だけだと、入力で起こされてまだワーカーが拾っていないプロセス（`RUNNABLE`）でも真になり、
     * 前のプロンプトが出力の末尾に残っているので、コマンドが動く前に返ることがある
     */
    private fun settle(h: Harness, m: Machine, host: Host, what: String, prompt: String = "$ ") {
        var last = -1
        var still = 0
        eventuallyDescribe(90_000, { "$what: 止まらない ${m.describeProcesses()}\n${host.text().takeLast(2000)}" }) {
            h.scheduler.tick()
            Thread.sleep(1)
            val idle = m.lock.withLockValue {
                m.consoleTtyLocked().inputAvailable == 0 &&
                    m.kernel.processes.all().filter { !it.state.isTerminal }.all { it.state == ProcessState.WAITING_EVENT }
            }
            val now = host.bytes()
            still = if (idle && m.isAtWaitPoint && now == last && host.text().endsWith(prompt)) still + 1 else 0
            last = now
            still >= 5
        }
    }

    /** 既定の比で、下限を既定と [changed] の大きいほうにした規則（測った場面が必ず下限以下になる） */
    private fun atLeast(changed: Long): FullSaveRule =
        FullSaveRule(FullSaveRule.DEFAULT.changedRatio, maxOf(FullSaveRule.DEFAULT.minChangedChunks, changed))

    private class Measured(val case: String, val changed: Long, val total: Long, val deltaBytes: Int, val fullBytes: Int) {
        override fun toString(): String =
            "$case: changed=$changed total=$total ratio=${"%.2f".format(if (total == 0L) 0.0 else changed.toDouble() / total)} " +
                "delta=$deltaBytes B full=$fullBytes B"
    }

    @Test
    fun `標準OSのシェルとLuaの1行は、既定の規則で差分になる`() = timeout(limit) {
        val image = requireNotNull(ImageFileSystem.bundled("os")) { "os.zip がリソースにない" }
        Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
            h.scheduler.hooks.bootVolumes = BootVolumeOpener { id ->
                when (id) {
                    OS -> BootVolume(image, readOnly = true)
                    HOME -> BootVolume(TestMemoryFileSystem(), readOnly = false)
                    else -> null
                }
            }
            val host = Host()
            val m = h.machine(fuelPerTick = 10_000_000, host = host, memoryBytes = 32L * 1024 * 1024)
            assertTrue(m.boot(BootConfig.builder().bootDisks(listOf(OS)).homeDisk(HOME).build()))
            settle(h, m, host, "起動")
            assertTrue(host.text().endsWith("$ "), host.text())

            val id = UUID.randomUUID()
            var generation = 0L
            val results = ArrayList<Measured>()

            /** 全体を書いて基準にする。全体の世代の大きさと基準を返す */
            fun full(): Pair<Int, DeltaBase> {
                val source = m.captureSnapshot(policy)!!
                generation++
                val bytes = bytesOf(source.build(id, generation, "test"))
                source.committed(id, generation)
                return bytes.size to stored(bytes).asDeltaBase()
            }

            /** 全体の保存の後に [input] を入れて止まるまで回し、変わった量と、差分の世代の大きさを測る（基準は進めない） */
            fun measure(case: String, input: String, prompt: String = "$ "): Pair<Measured, DeltaBase> {
                val (fullBytes, base) = full()
                m.offerConsoleInput(input.toByteArray())
                settle(h, m, host, case, prompt)
                val counts = m.changedChunks(base)
                val delta = m.captureSnapshot(policy, base, FullSaveRule(1.0))!!
                assertEquals(base, delta.deltaBase, case)
                generation++
                val deltaBytes = bytesOf(delta.build(id, generation, "test")).size
                return Measured(case, counts.changed, counts.total, deltaBytes, fullBytes).also { results += it } to base
            }

            measure("idle", "")
            // 基準を持たない台（チャンクの読み直しの後。置き場は基準を覚えているが、プロセスの基準は None）は、init と sh だけの小さな台でも全体にする
            // （下限だけだと、2 プロセスの全区画を毎回の差分に書き続ける）
            val unknown = DeltaBase(UUID.randomUUID(), 1, 0, 0)
            val restoredSmall = m.changedChunks(unknown)
            println("[#668] 復元の直後の init と sh（全プロセスが基準なし）: $restoredSmall")
            assertTrue(restoredSmall.total > 0 && restoredSmall.unbased == restoredSmall.total, "全プロセスが基準なしになっていない: $restoredSmall")
            assertEquals(null, m.captureSnapshot(policy, unknown)!!.deltaBase, "既定の規則で全体にならない: $restoredSmall")
            // 変わった区画の数は OS の変更でずれる（#667）ので、下限を測った値以上にした規則でも全体になる（下限が効かない）ことを確かめる
            assertEquals(null, m.captureSnapshot(policy, unknown, atLeast(restoredSmall.changed))!!.deltaBase, "下限が効いた: $restoredSmall")
            val (echo, echoBase) = measure("sh: echo 1 line", "echo delta-one\n")
            // 既定の規則では、init と sh の 1 行は差分（#668。比は境目 0.5 の近くにあるが、変わった区画が下限以下）
            assertTrue(echo.changed <= FullSaveRule.DEFAULT.minChangedChunks / 2, "下限に倍の余裕がない: $echo")
            val byDefault = m.captureSnapshot(policy, echoBase)
            assertNotNull(byDefault)
            assertEquals(echoBase, byDefault!!.deltaBase, "既定の規則で差分にならない: $echo")
            measure("sh: 5 lines", (1..5).joinToString("") { "echo line-$it\n" })
            measure("sh: ls (child process)", "ls /\n")
            // 基準の後に Lua の REPL を起こす（基準に入っていないプロセスが加わる）。変わった区画が下限以下でも、既定の規則は全体にする
            // （全体の写しでしかプロセスに基準を付けられない。下限だけだと、lua の全区画を毎回の差分に書き続ける）
            val (_, beforeLua) = full()
            m.offerConsoleInput("lua\n".toByteArray())
            settle(h, m, host, "lua の起動", "> ")
            val late = m.changedChunks(beforeLua)
            println("[#668] lua を基準の後に起こす: $late")
            assertTrue(late.unbased > 0, "基準の後に起こした lua が基準なしになっていない: $late")
            assertEquals(null, m.captureSnapshot(policy, beforeLua)!!.deltaBase, "既定の規則で全体にならない: $late")
            assertEquals(null, m.captureSnapshot(policy, beforeLua, atLeast(late.changed))!!.deltaBase, "下限が効いた: $late")
            // 全体の写しで基準が付いた後は、REPL で 1 行動かしても差分
            val (lua, luaBase) = measure("lua: 1 line", "print(1 + 1)\n", "> ")
            assertTrue(host.text().contains("\n2\r\n"), host.text().takeLast(2000))
            assertEquals(luaBase, m.captureSnapshot(policy, luaBase)!!.deltaBase, "既定の規則で差分にならない: $lua")

            // lua がいる台でも同じ
            val restored = m.changedChunks(unknown)
            println("[#668] 復元の直後の init・sh・lua（全プロセスが基準なし）: $restored")
            assertTrue(restored.total > 0 && restored.unbased == restored.total, "全プロセスが基準なしになっていない: $restored")
            assertEquals(null, m.captureSnapshot(policy, unknown)!!.deltaBase, "既定の規則で全体にならない: $restored")

            for (r in results) println("[#668] $r")
            for (r in results) assertTrue(r.deltaBytes <= r.fullBytes, "差分が全体より大きい: $r")
            m.close()
        }
    }

    private companion object {
        const val OS = "image:os"
        const val HOME = "home"
    }
}
