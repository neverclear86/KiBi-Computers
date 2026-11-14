package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pro.ikili.kibicomputers.api.device.Device
import pro.ikili.kibicomputers.api.device.DeviceMethods
import pro.ikili.kibicomputers.core.kernel.boot.BootVolume
import pro.ikili.kibicomputers.core.kernel.boot.BootVolumeOpener
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.core.persist.MachineIdIndex
import pro.ikili.kibicomputers.core.persist.MachineOwner
import pro.ikili.kibicomputers.core.persist.MachineSaveStore
import pro.ikili.kibicomputers.core.persist.RestoreListener
import pro.ikili.kibicomputers.core.persist.RestoreOutcome
import pro.ikili.kibicomputers.core.persist.RestoreRequest
import pro.ikili.kibicomputers.core.persist.RestoreSource
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.nio.file.Path
import java.time.Duration
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * Lua 向けの KiBi API（#94。ADR 0094）で待っている間に保存して戻す。
 * `os.pullEvent` で待つ Lua は、戻した後に `restored` のイベントを受け取って続く。
 * `os.startTimer`・`os.sleep` で待つ Lua は、期限が単調時計の絶対時刻なので、戻した後も同じ期限で続く（タイマーの表と C の静的変数がそろっている）。
 * `peripheral.call` でメインスレッドのメソッドの結果を待つ Lua は、実行中のものが `interrupted` の Lua のエラーになり、メソッドは1回だけ走る
 */
class LuaKibiApiRestoreTest {
    @TempDir
    lateinit var dir: Path

    private val limit = Duration.ofSeconds(120)
    private val owner = MachineOwner("minecraft:overworld", 0, 64, 0)
    private val address = "00000000-0000-4000-8000-0000000000c3"

    /** メインスレッドのメソッド defer（後で返すと決めて、返さない）を持つデバイス。呼ばれた回数は [calls] */
    private class Dev(private val calls: AtomicInteger) : Device {
        override val kind = "test:dev"
        override val methods: DeviceMethods = DeviceMethods.builder()
            .mainThread("defer") { ctx, _ ->
                calls.incrementAndGet()
                ctx.defer()
                emptyArray()
            }
            .direct("echo") { _, args -> Array(args.size()) { args.get(it) } }
            .build()
    }

    private fun output(m: Machine, id: Int): String = m.lock.withLockValue {
        (m.kernel.objects[id] as? ByteStream)?.availableBytes()?.toString(Charsets.UTF_8) ?: ""
    }

    /** [script] を動かし、[ready] が出て待機点で待つようになったら保存して、戻したマシンの出力を返す */
    private fun saveAndRestore(script: String, ready: String, until: Regex, calls: AtomicInteger, callsBeforeSave: Int = 0): String {
        val store = MachineSaveStore(dir, MachineIdIndex(), "test")
        return Harness(execRuntime = EndiveRuntime(), moduleStore = MemoryModuleStore()).use { h ->
            h.scheduler.hooks.bootVolumes = BootVolumeOpener { id -> if (id == "root") BootVolume(TestMemoryFileSystem(), readOnly = false) else null }
            val m = h.machine(fuelPerTick = 10_000_000)
            m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "disk:root")
            m.vfs.createDirectory(VfsPath.parse("/bin"))
            fun write(path: String, bytes: ByteArray) {
                val f = m.vfs.open(VfsPath.parse(path), OpenOptions(read = true, write = true, create = true, truncate = true))
                m.vfs.write(f, bytes, 0, bytes.size)
            }
            write("/bin/lua", requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/lua.wasm")).use { it.readBytes() })
            write("/bin/t", ("#!/bin/lua\n" + script).toByteArray())
            assertTrue(m.devices.attach(address, Dev(calls)))
            val outId = m.lock.withLockValue { ByteStream(64 * 1024).also { m.kernel.objects.add(it) }.id }
            val stdio = StdioSpec(StdioSource.Closed, StdioSource.Object(outId, HandleRights.WRITE), StdioSource.Object(outId, HandleRights.WRITE))
            val pid = m.exec("/bin/t", KernelDrivers.DEFAULT, SpawnOptions(stdio = stdio, fds = SpawnOptions.ROOT_PREOPEN))
            m.lock.withLockValue { m.kernel.initPid = pid }
            eventually(60_000, "待たない: ${output(m, outId)}") {
                h.scheduler.tick()
                Thread.sleep(1)
                ready in output(m, outId) && calls.get() >= callsBeforeSave && m.stateOf(pid) == ProcessState.WAITING_EVENT && m.isAtWaitPoint
            }
            val identity = store.assignNewId(owner)!!
            val snapshot = m.captureSnapshot(CapturePolicy.SKIP_IF_TRANSIENT)
            assertNotNull(snapshot, "待っている間は保存できる")
            val written = store.write(identity, owner, snapshot!!).identity
            m.close()

            val r = h.machine(fuelPerTick = 10_000_000)
            val outcomes = Collections.synchronizedList(ArrayList<RestoreOutcome>())
            val listener = RestoreListener { machine, outcome, _ ->
                if (outcome == RestoreOutcome.RESTORED) {
                    machine.devices.attach(address, Dev(calls))
                    machine.redispatchRestoredDeviceCalls()
                }
                outcomes += outcome
            }
            val boot = BootConfig.builder().addBootDisk("none").build()
            assertTrue(r.restore(RestoreRequest(RestoreSource(store, written.id, written.generation), boot, listener)))
            eventually(60_000, "戻したプロセスが続かない: ${output(r, outId)}") {
                h.scheduler.tick()
                Thread.sleep(1)
                outcomes.isNotEmpty() && until.containsMatchIn(output(r, outId))
            }
            assertEquals(listOf(RestoreOutcome.RESTORED), outcomes.toList())
            val out = output(r, outId)
            r.close()
            out
        }
    }

    @Test
    fun `pullEventで待っている間に保存して戻すと、restoredのイベントを受ける`() = timeout(limit) {
        val out = saveAndRestore(
            "os.queueEvent('go'); os.pullEvent('go'); print('waiting'); io.stdout:flush()\n" +
                "local e, ns, ticks = os.pullEvent(); print('got', e, math.type(ns), math.type(ticks))\n" +
                "print(peripheral.call('$address', 'echo', 3))\n",
            "waiting\n",
            Regex("got.*\n3\n"),
            AtomicInteger(),
        )
        assertTrue("waiting\ngot\trestored\tinteger\tinteger\n3\n" in out, out)
    }

    @Test
    fun `peripheral_callで実行中に保存して戻すと、interruptedのエラーになり、メソッドは1回だけ走る`() = timeout(limit) {
        val calls = AtomicInteger()
        val out = saveAndRestore(
            "print('calling'); io.stdout:flush()\nprint(pcall(peripheral.call, '$address', 'defer'))\nprint('done')\n",
            "calling\n",
            Regex("done\n"),
            calls,
            callsBeforeSave = 1,
        )
        assertTrue(Regex("calling\nfalse\t[^\n]*interrupted\ndone\n").containsMatchIn(out), out)
        assertEquals(1, calls.get())
    }

    @Test
    fun `startTimerで待っている間に保存して戻すと、同じ期限のtimerが鳴り、タイマーの表とFIFOが続く`() = timeout(limit) {
        val out = saveAndRestore(
            """
            local t1 = os.startTimer(2)
            local t2 = os.startTimer(100000)
            local t4 = os.startTimer(100000)
            os.queueEvent('a', 1)
            print('waiting'); io.stdout:flush()
            local e, id = os.pullEvent('timer')
            print(e, id == t1)
            os.cancelTimer(t2)
            os.queueEvent('x', 5)
            print(os.pullEvent('x'))
            local t3 = os.startTimer(0.1)
            local e3, id3 = os.pullEvent('timer')
            print(id3 == t3, t1, t2, t3, t4)
            os.cancelTimer(t4)
            local t5 = os.startTimer(0.1)
            print(select(2, os.pullEvent('timer')) == t5, t5)
            """.trimIndent() + "\n",
            "waiting\n",
            Regex("true\t5\n"),
            AtomicInteger(),
        )
        assertEquals("waiting\ntimer\ttrue\nx\t5\ntrue\t1\t2\t4\t3\ntrue\t5\n", out)
    }

    @Test
    fun `sleepで待っている間に保存して戻すと、期限まで待って続ける`() = timeout(limit) {
        val out = saveAndRestore(
            "print('waiting'); io.stdout:flush()\nos.sleep(1.5)\nprint('slept')\nprint(peripheral.call('$address', 'echo', 9))\n",
            "waiting\n",
            Regex("slept\n9\n"),
            AtomicInteger(),
        )
        assertEquals("waiting\nslept\n9\n", out)
    }
}
