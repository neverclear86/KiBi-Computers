package pro.ikili.kibicomputers.core.wasi.p1

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.core.access.AccessPort
import pro.ikili.kibicomputers.core.access.AccessRequest
import pro.ikili.kibicomputers.core.access.AccessRequestKind
import pro.ikili.kibicomputers.core.access.AccessRequestLimiter
import pro.ikili.kibicomputers.core.access.AccessRequestRules
import pro.ikili.kibicomputers.core.access.AccessRules
import pro.ikili.kibicomputers.core.access.AccessView
import pro.ikili.kibicomputers.core.access.PlayerAccess
import pro.ikili.kibicomputers.core.access.PlayerRef
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.MachineEnvironment
import pro.ikili.kibicomputers.core.kernel.MachineHost
import pro.ikili.kibicomputers.core.kernel.MachineState
import pro.ikili.kibicomputers.core.kernel.eventuallyDescribe
import pro.ikili.kibicomputers.core.kernel.stateOf
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import pro.ikili.kibicomputers.core.wasi.kibi.KibiErrors
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * 独自拡張 `kibi:computer/access`（#110。ADR 0110）の p1 の import を、kibi_access.c（実際の Endive）とカーネルで確かめる。
 * 接続層の口は偽のもの（[FakePort]）
 */
class WasiP1AccessTest {
    private val limit = Duration.ofSeconds(60)

    private val bytes: ByteArray by lazy {
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/kibi-access-wasi.wasm")) { "kibi-access-wasi.wasm がリソースにない" }
            .use { it.readBytes() }
    }

    /** 偽の口。届いた依頼と、届けたスレッドを覚える */
    private class FakePort(
        @Volatile var access: PlayerAccess,
        val rules: AccessRequestRules = AccessRequestRules(60_000, 2),
        val accessRules: AccessRules = AccessRules.DEFAULT,
    ) : AccessPort {
        override val limiter: AccessRequestLimiter = AccessRequestLimiter()
        val delivered = CopyOnWriteArrayList<AccessRequest>()
        val threads = CopyOnWriteArrayList<Thread>()
        var views = 0

        override fun view(): AccessView {
            views++
            return AccessView(access, accessRules)
        }

        override fun requestRules(): AccessRequestRules = rules

        override fun deliver(request: AccessRequest) {
            threads += Thread.currentThread()
            delivered += request
        }
    }

    private class ConsoleHost : MachineHost {
        private val out = ByteArrayOutputStream()

        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) = Unit

        override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
            synchronized(out) { out.write(bytes, 0, bytes.size) }
        }

        fun text(): String = synchronized(out) { out.toString(Charsets.UTF_8).replace("\r\n", "\n") }
    }

    /** `kac` を [args] ごとに順に動かし、それぞれの出力を返す（終わるたびに数 tick 回し、依頼を届ける） */
    private fun run(port: AccessPort?, vararg args: List<String>): List<String> = timeout(limit) {
        tickThread = Thread.currentThread()
        Harness(execRuntime = EndiveRuntime()).use { h ->
            val host = ConsoleHost()
            val env = MachineEnvironment.builder().access(port).build()
            val m = h.machine(fuelPerTick = 10_000_000, host = host, environment = env)
            m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
            val f = m.vfs.open(VfsPath.parse("/kac"), OpenOptions(read = true, write = true, create = true, truncate = true))
            m.vfs.write(f, bytes, 0, bytes.size)
            val out = ArrayList<String>()
            for (a in args) {
                val before = host.text().length
                val pid = m.exec("/kac", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("kac") + a, cwd = "/", fds = SpawnOptions.ROOT_PREOPEN))
                eventuallyDescribe(30_000, { "終わらない: ${host.text()}" }) {
                    h.scheduler.tick()
                    Thread.sleep(1)
                    m.stateOf(pid).isTerminal
                }
                repeat(5) { h.scheduler.tick() }
                out += host.text().substring(before)
            }
            out
        }
    }

    @Volatile
    private var tickThread: Thread? = null

    private fun err(errno: Errno): String = "err ${KibiErrors.code(errno)}\n"

    private val alex = PlayerRef(UUID.fromString("00000000-0000-0000-0000-00000000000a"), "Alex")
    private val steve = PlayerRef(UUID.fromString("00000000-0000-0000-0000-00000000000b"), "Steve")

    @Test
    fun `口がなければget-accessもrequestもnot-supported`() {
        val out = run(null, listOf("get"), listOf("add", "Alex"), listOf("remove", "Alex"), listOf("transfer", "Alex"))
        assertEquals(err(Errno.NOTSUP) + "pad\n", out[0])
        for (o in out.drop(1)) assertEquals(err(Errno.NOTSUP), o)
    }

    @Test
    fun `get-accessは持ち主・メンバー・期限内の保留を、項目の順にcabi_reallocで取って書き、詰め物は0`() {
        val now = System.currentTimeMillis()
        val rules = AccessRules.DEFAULT
        var access = PlayerAccess.ownedBy(alex)
        access = access.addPending("Steve", now, rules).access
        access = access.onAccess(steve, now, rules)
        access = access.addPending("Fresh", now, rules).access
        access = access.addPending("Old", now - rules.pendingTtlMillis - 1, rules).access
        val port = FakePort(access)
        val out = run(port, listOf("get"))[0]
        val expected = """
            state 1
            owner Alex ${alex.id}
            member Steve ${steve.id}
            invite fresh
            pad
            realloc 1:4 1:36 4:16 1:5 1:36 4:8 1:5
        """.trimIndent() + "\n"
        assertEquals(expected, out)
        // BE の値（ポートの値）は変えない
        assertEquals(2, port.access.pending.size)
    }

    @Test
    fun `持ち主なし・読めなかった記録は、ownerがnoneで、空の配列も取る`() {
        assertEquals("state 0\nowner none\npad\nrealloc 4:0 4:0\n", run(FakePort(PlayerAccess.NONE), listOf("get"))[0])
        assertEquals("state 2\nowner none\npad\nrealloc 4:0 4:0\n", run(FakePort(PlayerAccess.LOCKED), listOf("get"))[0])
    }

    @Test
    fun `形の誤り・長すぎ・不正なUTF-8はinvalid、範囲外はfault`() {
        val port = FakePort(PlayerAccess.ownedBy(alex))
        val out = run(
            port,
            listOf("add", "bad-name"),
            listOf("add", "a".repeat(17)),
            listOf("add", "a".repeat(65)),
            listOf("transfer", "00000000-0000-0000-0000-00000000000b"),
            listOf("remove", "1-2-3-4-5"),
            listOf("add-hex", "ff"),
            listOf("add-oob"),
            listOf("add", ""),
        )
        val inval = err(Errno.INVAL)
        assertEquals(listOf(inval, inval, inval, inval, inval, inval, err(Errno.FAULT), inval), out)
        assertTrue(port.delivered.isEmpty(), "${port.delivered}")
    }

    @Test
    fun `受け付けた依頼はメインスレッドに順に届き、同じ依頼はまとめ、上限でagain`() {
        val port = FakePort(PlayerAccess.ownedBy(alex))
        val out = run(
            port,
            listOf("add", "Steve"),
            listOf("remove", "00000000-0000-0000-0000-00000000000B"),
            listOf("add", "STEVE"),
            listOf("transfer", "Steve"),
        )
        assertEquals(listOf("ok\n", "ok\n", "ok\n", err(Errno.AGAIN)), out)
        assertEquals(
            listOf(AccessRequestKind.ADD to "Steve", AccessRequestKind.REMOVE to "00000000-0000-0000-0000-00000000000b"),
            port.delivered.map { it.kind to it.argument },
        )
        // 届けはワーカーでなく、tick を回したスレッド
        assertTrue(port.threads.all { it === port.threads[0] } && port.threads[0] === tickThread, "${port.threads} / $tickThread")
        // request は view を読まない
        assertEquals(0, port.views)
        // 取り下げ（届けで捨てたとき）で枠が戻る
        port.limiter.release(port.delivered[0])
        assertEquals(listOf("ok\n"), run(port, listOf("transfer", "Steve")))
    }

    @Test
    fun `cabi_reallocの中からrequestを呼んでもトラップにならない`() {
        val port = FakePort(PlayerAccess.ownedBy(alex))
        val out = run(port, listOf("realloc-add", "Inner"))[0]
        assertTrue(out.startsWith("state 1\nowner Alex"), out)
        assertTrue(out.endsWith("inner ok\n"), out)
        assertEquals(listOf("Inner"), port.delivered.map { it.argument })
    }
}
