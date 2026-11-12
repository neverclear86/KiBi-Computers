package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pro.ikili.kibicomputers.core.kernel.boot.BootVolume
import pro.ikili.kibicomputers.core.kernel.boot.BootVolumeOpener
import pro.ikili.kibicomputers.core.kernel.event.EventFilter
import pro.ikili.kibicomputers.core.kernel.event.EventQueue
import pro.ikili.kibicomputers.core.kernel.event.EventValue
import pro.ikili.kibicomputers.core.kernel.event.KernelEvent
import pro.ikili.kibicomputers.core.kernel.event.RestoreEvents
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.time.ManualClock
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.core.persist.MachineIdIndex
import pro.ikili.kibicomputers.core.persist.MachineIdentity
import pro.ikili.kibicomputers.core.persist.MachineOwner
import pro.ikili.kibicomputers.core.persist.MachineSaveStore
import pro.ikili.kibicomputers.core.persist.RestoreListener
import pro.ikili.kibicomputers.core.persist.RestoreOutcome
import pro.ikili.kibicomputers.core.persist.RestoreRequest
import pro.ikili.kibicomputers.core.persist.RestoreSource
import pro.ikili.kibicomputers.core.persist.SaveReadiness
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.nio.file.Path
import java.time.Duration
import java.util.Collections
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * 復元のイベント `restored`（#87、ADR 0087）: 成功した復元で、戻したキューに1件ずつ届き、値は実時間・ゲーム内時間の飛び。
 * 戻せなかったとき（後段の失敗）には出さない。受け手が返るまでプロセスは動かない
 */
class RestoreEventTest {
    @TempDir
    lateinit var dir: Path

    private val limit = Duration.ofSeconds(120)
    private val owner = MachineOwner("minecraft:overworld", 0, 64, 0)

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/$name")) { "$name がリソースにない" }.use { it.readBytes() }

    private fun bootConfig(): BootConfig = BootConfig.builder().addBootDisk("none").build()

    private fun env(clock: ManualClock): MachineEnvironment = MachineEnvironment.builder().clock(clock).build()

    /** 戻したマシンのキューの中身（フィルターが全部なら "all"、そうでなければ種類の並び） */
    private fun queues(m: Machine): Map<String, List<KernelEvent>> = m.lock.withLockValue {
        val out = LinkedHashMap<String, List<KernelEvent>>()
        m.kernel.objects.forEachObject { obj ->
            if (obj is EventQueue) out[if (obj.filter.acceptsAll) "all" else obj.filter.types.joinToString(",")] = obj.pendingEvents()
        }
        out
    }

    private fun restored(realtime: Long, world: Long): KernelEvent =
        KernelEvent(RestoreEvents.TYPE, "", listOf(EventValue.Integer(realtime), EventValue.Integer(world)))

    /**
     * `prompt` を動かし、全部を受けるキューと `restored` だけを受けるキュー（と、[extra] の種類だけを受けるキュー）を持たせて、
     * 入力を待ったところで書く
     */
    private fun save(h: Harness, store: MachineSaveStore, clock: ManualClock, extra: String = "timer"): MachineIdentity {
        h.scheduler.hooks.bootVolumes = BootVolumeOpener { id -> if (id == "root") BootVolume(TestMemoryFileSystem(), readOnly = false) else null }
        val m = h.machine(fuelPerTick = 10_000_000, environment = env(clock))
        val bytes = resource("prompt-wasi.wasm")
        m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "disk:root")
        m.vfs.createDirectory(VfsPath.parse("/bin"))
        val f = m.vfs.open(VfsPath.parse("/bin/prompt"), OpenOptions(read = true, write = true, create = true, truncate = true))
        m.vfs.write(f, bytes, 0, bytes.size)
        val (input, output) = m.lock.withLockValue {
            ByteStream(4096).also { m.kernel.objects.add(it) }.id to ByteStream(64 * 1024).also { m.kernel.objects.add(it) }.id
        }
        val stdio = StdioSpec(StdioSource.Object(input, HandleRights.READ), StdioSource.Object(output, HandleRights.WRITE), StdioSource.Closed)
        val pid = m.exec("/bin/prompt", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("prompt"), stdio = stdio))
        m.lock.withLockValue {
            m.kernel.initPid = pid
            m.openEventQueueLocked(pid, EventFilter.ALL, 64)
            m.openEventQueueLocked(pid, EventFilter(setOf(RestoreEvents.TYPE)), 64)
            m.openEventQueueLocked(pid, EventFilter(setOf(extra)), 64)
        }
        eventually(60_000, "入力を待たない") {
            h.scheduler.tick()
            m.stateOf(pid) == ProcessState.WAITING_EVENT && m.isAtWaitPoint
        }
        val identity = store.assignNewId(owner)!!
        val written = store.write(identity, owner, m.captureSnapshot(CapturePolicy.SKIP_IF_TRANSIENT)!!).identity
        m.close()
        return written
    }

    private class Result(val outcome: RestoreOutcome, val inListener: Map<String, List<KernelEvent>>, val quiesced: Boolean)

    /** [identity] を時計 [clock] のマシンに戻し、受け手の中で見たキューの中身を返す。戻したマシンは [keep] に渡す */
    private fun restore(h: Harness, store: MachineSaveStore, identity: MachineIdentity, clock: ManualClock, keep: (Machine) -> Unit = { it.close() }): Result {
        val m = h.machine(fuelPerTick = 10_000_000, environment = env(clock))
        val results = Collections.synchronizedList(ArrayList<Result>())
        val errors = Collections.synchronizedList(ArrayList<Throwable>())
        val listener = RestoreListener { machine, outcome, _ ->
            try {
                results += Result(outcome, queues(machine), machine.isQuiesced)
            } catch (t: Throwable) {
                errors += t
            }
        }
        assertTrue(m.restore(RestoreRequest(RestoreSource(store, identity.id, identity.generation), bootConfig(), listener)))
        eventually(60_000, "復元が終わらない") {
            h.scheduler.tick()
            results.isNotEmpty() || m.state == MachineState.CRASHED
        }
        errors.firstOrNull()?.let { throw AssertionError("受け手の中で落ちた", it) }
        keep(m)
        return results.singleOrNull() ?: Result(RestoreOutcome.CANCELLED, emptyMap(), false)
    }

    private fun jumpCase(before: ManualClock, after: ManualClock, realtime: Long, world: Long) = timeout(limit) {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
            val saved = save(h, store, before)
            val r = restore(h, store, saved, after)
            assertEquals(RestoreOutcome.RESTORED, r.outcome)
            assertTrue(r.quiesced, "受け手の中は止めたまま")
            val expected = listOf(restored(realtime, world))
            assertEquals(expected, r.inListener["all"], "全部を受けるキュー")
            assertEquals(expected, r.inListener[RestoreEvents.TYPE], "restored だけを受けるキュー")
            assertEquals(emptyList<KernelEvent>(), r.inListener["timer"], "ほかの種類だけを受けるキューには届かない")
        }
    }

    @Test
    fun `成功した復元で、実時間とゲーム内時間の飛びを1件ずつ届ける`() = jumpCase(
        ManualClock(realtime = 1_000_000_000L, world = 100),
        ManualClock(monotonic = 5L, realtime = 6_000_000_000L, world = 1_300),
        realtime = 5_000_000_000L,
        world = 1_200,
    )

    @Test
    fun `飛びが0でも届ける`() = jumpCase(
        ManualClock(realtime = 42L, world = 7),
        ManualClock(realtime = 42L, world = 7),
        realtime = 0,
        world = 0,
    )

    @Test
    fun `時計が戻ったときは負の値をそのまま届ける`() = jumpCase(
        ManualClock(realtime = 9_000L, world = 500),
        ManualClock(realtime = 1_000L, world = 20),
        realtime = -8_000,
        world = -480,
    )

    @Test
    fun `あふれる飛びは飽和した値で届ける`() = jumpCase(
        ManualClock(realtime = Long.MAX_VALUE, world = Long.MIN_VALUE),
        ManualClock(realtime = Long.MIN_VALUE, world = Long.MAX_VALUE),
        realtime = Long.MIN_VALUE,
        world = Long.MAX_VALUE,
    )

    @Test
    fun `取り出さずにもう一度保存して戻すと2件になる`() = timeout(limit) {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
            val saved = save(h, store, ManualClock(realtime = 100L, world = 1))
            var second: MachineIdentity? = null
            restore(h, store, saved, ManualClock(realtime = 300L, world = 4)) { m ->
                eventually(60_000, "知らせの後も写せない") {
                    h.scheduler.tick()
                    m.saveReadiness() == SaveReadiness.READY && m.isAtWaitPoint
                }
                // 写しを取る時点の時計（実時間 300、ゲーム内 4）が、次の復元の起点になる
                second = store.write(store.assignNewId(owner)!!, owner, m.captureSnapshot(CapturePolicy.SKIP_IF_TRANSIENT)!!).identity
                m.close()
            }
            val r = restore(h, store, second!!, ManualClock(realtime = 1_000L, world = 10))
            assertEquals(RestoreOutcome.RESTORED, r.outcome)
            assertEquals(listOf(restored(200, 3), restored(700, 6)), r.inListener[RestoreEvents.TYPE])
        }
    }

    @Test
    fun `表に入れる途中で失敗したときは出さない`() = timeout(limit) {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
            val saved = save(h, store, ManualClock())
            var seen: Map<String, List<KernelEvent>>? = null
            // 時計を戻した後（キューは表に入っている）で落とす
            h.scheduler.hooks.duringRestoreInstall = { m ->
                seen = queues(m)
                throw IllegalStateException("テストの失敗")
            }
            val r = restore(h, store, saved, ManualClock(realtime = 9_999_999_999L)) { m ->
                assertEquals(MachineState.CRASHED, m.state)
                repeat(3) { h.scheduler.tick() }
                assertTrue(queues(m).values.all { q -> q.none { it.type == RestoreEvents.TYPE } }, "CRASHED のマシンに restored が入った")
                m.close()
            }
            assertEquals(RestoreOutcome.CANCELLED, r.outcome, "知らせは来ない")
            assertTrue(seen!!.isNotEmpty(), "キューを表に入れる前に落ちた")
            assertTrue(seen!!.values.all { it.isEmpty() }, "時計を戻した時点で出していた")
        }
    }
}
