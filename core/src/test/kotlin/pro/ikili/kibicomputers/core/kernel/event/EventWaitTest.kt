package pro.ikili.kibicomputers.core.kernel.event

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.ResourceLimitException
import pro.ikili.kibicomputers.core.kernel.TestDriver
import pro.ikili.kibicomputers.core.kernel.emptyModule
import pro.ikili.kibicomputers.core.kernel.eventually
import pro.ikili.kibicomputers.core.kernel.process
import pro.ikili.kibicomputers.core.kernel.fd.FdIo
import pro.ikili.kibicomputers.core.kernel.fd.FdTable
import pro.ikili.kibicomputers.core.kernel.fd.IoResult
import pro.ikili.kibicomputers.core.kernel.host
import pro.ikili.kibicomputers.core.kernel.hostCallModule
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.kernelThreadNames
import pro.ikili.kibicomputers.core.kernel.process.ProcessContext
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.StepResult
import pro.ikili.kibicomputers.core.kernel.spawnTest
import pro.ikili.kibicomputers.core.kernel.stateOf
import pro.ikili.kibicomputers.core.kernel.tickUntilTrue
import pro.ikili.kibicomputers.core.kernel.vfs.Errno
import pro.ikili.kibicomputers.core.kernel.vfs.FsException
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.kernel.withLockValue
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/** スケジューラの上でイベントキューを待つ（#63）。 */
class EventWaitTest {
    private val limit = Duration.ofSeconds(30)

    private fun code(e: KernelEvent): Int = (e.values[0] as EventValue.I64).value.toInt()

    private fun ev(type: String = "test", value: Long = 0) = KernelEvent(type, "", listOf(EventValue.I64(value)))

    /** 待機点型: "test" のキューを開いて待ち、届いたイベントの値で終わる。[handle] にハンドル番号を入れる */
    private fun waitPointDriver(handle: AtomicInteger, resumes: AtomicInteger = AtomicInteger(), extra: (ProcessContext) -> List<WaitKey> = { emptyList() }) =
        TestDriver(
            ProcessKind.WAIT_POINT,
            onResume = { ctx, _ ->
                resumes.incrementAndGet()
                val batch = ctx.takeEvents(handle.get())
                if (batch.events.isEmpty()) StepResult.Wait(listOf(ctx.eventWaitKey(handle.get())) + extra(ctx)) else StepResult.Exit(code(batch.events[0]))
            },
        ) { ctx ->
            handle.set(ctx.openEventQueue(EventFilter(setOf("test"))))
            StepResult.Wait(listOf(ctx.eventWaitKey(handle.get())) + extra(ctx))
        }

    private fun Machine.hub(): EventHub = kernel.events

    private fun Machine.exited(pid: Int): Int? = process(pid)?.takeIf { it.state == ProcessState.EXITED }?.exitCode

    @Test
    fun `待機点型はスレッドを持たずに待ち、別スレッドからのpostEventでresumeされる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val handle = AtomicInteger(-1)
            val pid = m.spawnTest(h.program(emptyModule()), waitPointDriver(handle))
            h.tickUntilTrue { m.stateOf(pid) == ProcessState.WAITING_EVENT }
            assertTrue(m.isAtWaitPoint)
            eventually { kernelThreadNames("KiBi-m").isEmpty() }
            val delivered = AtomicInteger(-1)
            Thread { delivered.set(m.postEvent(ev(value = 42))) }.apply { start() }.join()
            assertEquals(1, delivered.get())
            h.tickUntilTrue { m.stateOf(pid).isTerminal }
            assertEquals(42, m.exited(pid))
        }
    }

    @Test
    fun `ブロッキング型のawaitEventsはpostEventで起きる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val got = AtomicReference<EventBatch>()
            val driver = TestDriver(
                ProcessKind.BLOCKING,
                onConfigure = { ctx, b ->
                    b.host(ctx) { c, _, _ ->
                        val q = c.openEventQueue(EventFilter(setOf("test")))
                        got.set(c.awaitEvents(q))
                        LongArray(0)
                    }
                },
            ) { ctx ->
                ctx.instance.call("run")
                StepResult.Exit(code(got.get().events[0]))
            }
            val pid = m.spawnTest(h.program(hostCallModule()), driver)
            h.tickUntilTrue { m.stateOf(pid) == ProcessState.WAITING_EVENT }
            assertEquals(0, m.postEvent(ev("other")))
            assertEquals(1, m.postEvent(ev(value = 7)))
            h.tickUntilTrue { m.stateOf(pid).isTerminal }
            assertEquals(7, m.exited(pid))
            assertEquals(0L, got.get().dropped)
        }
    }

    @Test
    fun `待つ前に届いたイベントで、待たずにresumeされる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val driver = TestDriver(
                ProcessKind.WAIT_POINT,
                onResume = { ctx, ready -> StepResult.Exit(code(ctx.takeEvents(ready.single().let { k -> handleOf(ctx, k) }).events.single())) },
            ) { ctx ->
                val q = ctx.openEventQueue()
                m.postEvent(ev(value = 5))
                StepResult.Wait(listOf(ctx.eventWaitKey(q)))
            }
            val pid = m.spawnTest(h.program(emptyModule()), driver)
            h.tickUntilTrue { m.stateOf(pid).isTerminal }
            assertEquals(5, m.exited(pid))
        }
    }

    /** [key] のオブジェクトを指す fd */
    private fun handleOf(ctx: ProcessContext, key: WaitKey): Int = ctx.withKernelLock {
        ctx.kernel.processes[ctx.pid]!!.handles.entries().entries.first { it.value.objectId == key.objectId }.key
    }

    @Test
    fun `種類の違うイベントでは起きない`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val handle = AtomicInteger(-1)
            val resumes = AtomicInteger()
            val pid = m.spawnTest(h.program(emptyModule()), waitPointDriver(handle, resumes))
            h.tickUntilTrue { m.stateOf(pid) == ProcessState.WAITING_EVENT }
            repeat(3) { assertEquals(0, m.postEvent(ev("other"))) }
            repeat(5) { h.scheduler.tick() }
            Thread.sleep(20)
            assertEquals(0, resumes.get())
            assertEquals(ProcessState.WAITING_EVENT, m.stateOf(pid))
            m.postEvent(ev(value = 3))
            h.tickUntilTrue { m.stateOf(pid).isTerminal }
            assertEquals(3, m.exited(pid))
        }
    }

    @Test
    fun `キューとByteStreamをまとめて待ち、どちらでも起きる`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val stream = m.lock.withLockValue { ByteStream(16).also { m.kernel.objects.add(it) } }
            val streamKey = WaitKey(stream.id, WaitCondition.READABLE)
            val handle = AtomicInteger(-1)
            val readyLog = java.util.Collections.synchronizedList(ArrayList<List<WaitKey>>())
            val driver = TestDriver(
                ProcessKind.WAIT_POINT,
                onResume = { ctx, ready ->
                    readyLog += ready
                    val batch = ctx.takeEvents(handle.get())
                    if (batch.events.isNotEmpty()) return@TestDriver StepResult.Exit(code(batch.events[0]))
                    ctx.withKernelLock { stream.read(ByteArray(16), 0, 16, ctx.wakeSink) }
                    StepResult.Wait(listOf(ctx.eventWaitKey(handle.get()), streamKey))
                },
            ) { ctx ->
                handle.set(ctx.openEventQueue(EventFilter(setOf("test"))))
                StepResult.Wait(listOf(ctx.eventWaitKey(handle.get()), streamKey))
            }
            val pid = m.spawnTest(h.program(emptyModule()), driver)
            h.tickUntilTrue { m.stateOf(pid) == ProcessState.WAITING_EVENT }
            m.lock.withLockValue { stream.write(byteArrayOf(1), 0, 1, m.wakeSink) }
            h.tickUntilTrue { readyLog.size == 1 && m.stateOf(pid) == ProcessState.WAITING_EVENT }
            assertEquals(listOf(streamKey), readyLog[0])
            m.postEvent(ev(value = 9))
            h.tickUntilTrue { m.stateOf(pid).isTerminal }
            assertEquals(9, m.exited(pid))
            assertEquals(WaitCondition.READABLE, readyLog[1].single().condition)
            assertTrue(readyLog[1].single() != streamKey)
        }
    }

    @Test
    fun `待っている間のstopでKILLED、pauseAllは成功し、一時停止中のイベントは再開後に届く`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val handle = AtomicInteger(-1)
            val pid = m.spawnTest(h.program(emptyModule()), waitPointDriver(handle))
            h.tickUntilTrue { m.stateOf(pid) == ProcessState.WAITING_EVENT }
            val pause = h.scheduler.pauseAll(5_000)
            assertEquals(1, m.postEvent(ev(value = 11)))
            repeat(3) { h.scheduler.tick() }
            Thread.sleep(20)
            // 起こされて CPU の待ち行列に入る（RUNNABLE）が、一時停止の間は resume されない
            assertTrue(m.stateOf(pid) == ProcessState.WAITING_EVENT || m.stateOf(pid) == ProcessState.RUNNABLE, "${m.stateOf(pid)}")
            assertTrue(m.isAtWaitPoint, "一時停止の間は待機点のまま")
            pause.close()
            h.tickUntilTrue { m.stateOf(pid).isTerminal }
            assertEquals(11, m.exited(pid))

            val m2 = h.machine()
            val pid2 = m2.spawnTest(h.program(emptyModule()), waitPointDriver(AtomicInteger(-1)))
            h.tickUntilTrue { m2.stateOf(pid2) == ProcessState.WAITING_EVENT }
            m2.stop()
            h.tickUntilTrue { m2.stateOf(pid2).isTerminal }
            assertEquals(ProcessState.KILLED, m2.stateOf(pid2))
        }
    }

    @Test
    fun `プロセスが終わるとキューが閉じ、その後のpostEventは0で索引が掃除される`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val queueId = AtomicInteger()
            val pid = m.spawnTest(
                h.program(emptyModule()),
                TestDriver(ProcessKind.WAIT_POINT) { ctx ->
                    val q = ctx.openEventQueue()
                    queueId.set(ctx.eventWaitKey(q).objectId)
                    StepResult.Exit(0)
                },
            )
            h.tickUntilTrue { m.stateOf(pid).isTerminal }
            m.lock.withLockValue {
                assertNull(m.kernel.objects[queueId.get()])
                assertEquals(1, m.hub().indexedCount)
            }
            assertEquals(0, m.postEvent(ev()))
            m.lock.withLockValue { assertEquals(0, m.hub().indexedCount) }
        }
    }

    @Test
    fun `一度もpostされない種類のキューを開いたまま終わるプログラムを上限より多く動かしても、次のプログラムがキューを開ける`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val program = h.program(emptyModule())
            val opener = TestDriver(ProcessKind.WAIT_POINT) { ctx ->
                ctx.openEventQueue(EventFilter(setOf("never")))
                StepResult.Exit(0)
            }
            repeat(EventLimits.MAX_QUEUES + 1) {
                val pid = m.spawnTest(program, opener)
                h.tickUntilTrue { m.stateOf(pid).isTerminal }
                assertEquals(0, m.exited(pid))
            }
            // 起動の始め（再起動を含む）でも掃除される
            m.lock.withLockValue { assertTrue(m.hub().indexedCount > 0) }
            assertTrue(m.beginBoot())
            m.lock.withLockValue { assertEquals(0, m.hub().indexedCount) }
            val pid = m.spawnTest(program, opener)
            h.tickUntilTrue { m.stateOf(pid).isTerminal }
            assertEquals(0, m.exited(pid))
        }
    }

    @Test
    fun `キューの数の上限ではResourceLimitException`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val thrown = AtomicReference<Throwable>()
            val pid = m.spawnTest(
                h.program(emptyModule()),
                TestDriver(ProcessKind.WAIT_POINT) { ctx ->
                    repeat(EventLimits.MAX_QUEUES) { ctx.openEventQueue() }
                    thrown.set(runCatching { ctx.openEventQueue() }.exceptionOrNull())
                    StepResult.Exit(0)
                },
            )
            h.tickUntilTrue { m.stateOf(pid).isTerminal }
            assertTrue(thrown.get() is ResourceLimitException, "${thrown.get()}")
        }
    }

    @Test
    fun `capacityが範囲外ならINVAL、fd表が満杯ならMFILEで、索引にもオブジェクト表にも残らない`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val errors = java.util.Collections.synchronizedList(ArrayList<Errno>())
            val counts = AtomicReference<Pair<Int, Int>>()
            val pid = m.spawnTest(
                h.program(emptyModule()),
                TestDriver(ProcessKind.WAIT_POINT) { ctx ->
                    for (c in listOf(0, -1, EventLimits.MAX_CAPACITY + 1)) {
                        errors += assertThrows<FsException> { ctx.openEventQueue(capacity = c) }.errno
                    }
                    ctx.withKernelLock {
                        val table = FdTable(ctx.kernel.processes[ctx.pid]!!, ctx.kernel.objects, ctx.wakeSink)
                        val fd = table.open(ByteStream(1))
                        while (!table.isFull) table.dup(fd)
                    }
                    val before = ctx.withKernelLock { ctx.kernel.objects.size to ctx.kernel.events.indexedCount }
                    errors += assertThrows<FsException> { ctx.openEventQueue() }.errno
                    counts.set(before)
                    ctx.withKernelLock { assertEquals(before, ctx.kernel.objects.size to ctx.kernel.events.indexedCount) }
                    StepResult.Exit(0)
                },
            )
            h.tickUntilTrue { m.stateOf(pid).isTerminal }
            assertEquals(0, m.exited(pid))
            assertEquals(listOf(Errno.INVAL, Errno.INVAL, Errno.INVAL, Errno.MFILE), errors.toList())
        }
    }

    @Test
    fun `イベントキューでないハンドルはBADF、maxが1未満はINVAL、fdへのreadはINVALでwriteはBADF`() = timeout(limit) {
        Harness().use { h ->
            val m = h.machine()
            val results = java.util.Collections.synchronizedList(ArrayList<Any>())
            val pid = m.spawnTest(
                h.program(emptyModule()),
                TestDriver(ProcessKind.WAIT_POINT) { ctx ->
                    val q = ctx.openEventQueue()
                    val stream = ctx.withKernelLock { FdTable(ctx.kernel.processes[ctx.pid]!!, ctx.kernel.objects, ctx.wakeSink).open(ByteStream(1)) }
                    results += assertThrows<FsException> { ctx.takeEvents(stream) }.errno
                    results += assertThrows<FsException> { ctx.eventWaitKey(stream) }.errno
                    results += assertThrows<FsException> { ctx.takeEvents(200) }.errno
                    results += assertThrows<FsException> { ctx.takeEvents(q, 0) }.errno
                    results += FdIo.read(ctx, q, ByteArray(4), 0, 4)
                    results += FdIo.write(ctx, q, ByteArray(4), 0, 4)
                    StepResult.Exit(0)
                },
            )
            h.tickUntilTrue { m.stateOf(pid).isTerminal }
            assertEquals(0, m.exited(pid))
            assertEquals(
                listOf(Errno.BADF, Errno.BADF, Errno.BADF, Errno.INVAL, IoResult.Error(Errno.INVAL), IoResult.Error(Errno.BADF)),
                results.toList(),
            )
        }
    }
}
