package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import pro.ikili.kibicomputers.core.kernel.event.EventFilter
import pro.ikili.kibicomputers.core.kernel.event.EventQueue
import pro.ikili.kibicomputers.core.kernel.event.EventValue
import pro.ikili.kibicomputers.core.kernel.event.KernelEvent
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.obj.HandleEntry
import pro.ikili.kibicomputers.core.kernel.process.ModuleHash
import pro.ikili.kibicomputers.core.kernel.process.Process
import pro.ikili.kibicomputers.core.kernel.process.ProcessKind
import pro.ikili.kibicomputers.core.kernel.tty.Tty
import pro.ikili.kibicomputers.core.kernel.tty.TtySize
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.Vfs
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.kernel.wait.WaitCondition
import pro.ikili.kibicomputers.core.kernel.wait.WaitKey
import pro.ikili.kibicomputers.core.kernel.wait.WakeSink
import java.lang.reflect.Modifier
import java.util.IdentityHashMap

/**
 * [KernelState] の下が、番号・列挙・バイト列（と、それをまとめるカーネルのクラス・コレクション）だけでできていることを、
 * リフレクションでたどって確かめる（実行時だけのものを参照したらここで気づく）。
 */
class KernelStateIsolationTest {
    private val allowedLeaf = setOf(
        String::class.java, java.lang.Integer::class.java, java.lang.Long::class.java, java.lang.Boolean::class.java,
        java.lang.Byte::class.java, java.lang.Double::class.java, java.lang.Short::class.java, java.lang.Character::class.java,
    )

    private fun walk(value: Any?, path: String, seen: IdentityHashMap<Any, Unit>) {
        if (value == null) return
        if (seen.containsKey(value)) return
        seen[value] = Unit
        val c = value.javaClass
        when {
            c.isPrimitive || c in allowedLeaf || c.isEnum || (c.superclass?.isEnum == true) -> return
            value is ByteArray || value is IntArray || value is LongArray -> return
            value is Array<*> -> value.forEachIndexed { i, v -> walk(v, "$path[$i]", seen) }
            value is Map<*, *> -> value.forEach { (k, v) ->
                walk(k, "$path{key}", seen)
                walk(v, "$path[$k]", seen)
            }
            value is Collection<*> -> value.forEachIndexed { i, v -> walk(v, "$path[$i]", seen) }
            c.name.startsWith("pro.ikili.kibicomputers.core.kernel.") -> {
                if (value is WakeSink) fail("$path が WakeSink を参照している")
                var k: Class<*>? = c
                while (k != null && k != Any::class.java) {
                    for (f in k.declaredFields) {
                        if (Modifier.isStatic(f.modifiers)) continue
                        f.isAccessible = true
                        walk(f.get(value), "$path.${f.name}", seen)
                    }
                    k = k.superclass
                }
            }
            else -> fail("$path が書き出せない型 ${c.name} を参照している")
        }
    }

    @Test
    fun `KernelStateの下は実行時だけのものを参照しない`() {
        val state = KernelState(8)
        val stream = ByteStream(64)
        val id = state.objects.add(stream)
        stream.write("data".toByteArray(), 0, 4, WakeSink.NONE)
        val process = Process(
            1, 0, ProcessKind.WAIT_POINT, ModuleHash.of(byteArrayOf(1, 2, 3)), false,
            args = arrayListOf("prog", "-x"), env = linkedMapOf("HOME" to "/home"), cwd = VfsPath.parse("/disk"),
        )
        process.handles.insert(HandleEntry(id))
        process.wait = listOf(WaitKey(id, WaitCondition.READABLE))
        state.processes.add(process)
        state.fuel = -5
        // VFS: マウントの表は KernelState に、ファイルシステムは Vfs（実行時）に置く
        val vfs = Vfs(state.mounts)
        vfs.mount(VfsPath.parse("/disk"), TestMemoryFileSystem(), readOnly = false, source = "disk:1")
        val file = vfs.open(VfsPath.parse("/disk/a"), OpenOptions(write = true, create = true))
        vfs.write(file, "xy".toByteArray(), 0, 2)
        process.handles.insert(HandleEntry(state.objects.add(file)))
        // TTY: 行の途中・たまった入力と出力・変えた大きさ
        val tty = Tty(64, 64)
        state.consoleTtyId = state.objects.add(tty)
        tty.input("done\npart".toByteArray(), 0, 9, WakeSink.NONE)
        tty.write("out\n".toByteArray(), 0, 4, WakeSink.NONE)
        tty.resize(TtySize(40, 12))
        tty.foregroundPid = 1
        process.handles.insert(HandleEntry(tty.id))
        // イベント: 中身の入ったキュー、あふれた分、索引（#63）
        val queue = EventQueue(EventFilter(setOf("key", "timer")), 4)
        state.objects.add(queue)
        state.events.subscribe(queue, state.objects)
        val all = EventQueue(EventFilter.ALL)
        state.objects.add(all)
        state.events.subscribe(all, state.objects)
        val values = listOf(EventValue.I64(1), EventValue.F64(0.5), EventValue.Bool(true), EventValue.Text("a"), EventValue.Bytes(byteArrayOf(1)))
        repeat(5) { state.events.post(KernelEvent("key", "keyboard:1", values), state.objects, WakeSink.NONE) }
        process.handles.insert(HandleEntry(queue.id))
        walk(state, "state", IdentityHashMap())
    }

    @Test
    fun `動いたマシンのKernelStateも実行時だけのものを参照しない`() {
        Harness().use { h ->
            val machine = h.machine()
            val program = h.program(emptyModule())
            val pid = machine.spawnTest(program, TestDriver(ProcessKind.WAIT_POINT) { pro.ikili.kibicomputers.core.kernel.process.StepResult.Exit(0) })
            h.scheduler.tick()
            eventually { machine.stateOf(pid).isTerminal }
            machine.lock.withLockValue { walk(machine.kernel, "kernel", IdentityHashMap()) }
            assertTrue(true)
        }
    }
}
