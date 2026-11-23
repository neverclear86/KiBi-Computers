package pro.ikili.kibicomputers.core.wasi.p2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pro.ikili.kibicomputers.api.device.Device
import pro.ikili.kibicomputers.api.device.DeviceMethods
import pro.ikili.kibicomputers.core.kernel.Harness
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.eventually
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.stateOf
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.kernel.withLockValue
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.time.Duration
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * `/dev/device/<address>` を開いた fd の種類・`isatty`・`fstat`（#865・#885。ADR 0054「fd とパスで種類が違う」、ADR 0267「descriptor の種類」）。
 *
 * 同じ `dev_device_wasi.c` を、p1 のまま・アダプターで包んだ p2・`wasm32-wasip2` でビルドした p2 の3通りで動かす。
 * p1 と包んだ p2 は `fd_fdstat_get` の filetype が `UNKNOWN` であることも確かめる（包んだ p2 の filetype は `descriptor.get-type` を写したもの）。
 *
 * この PR の変更（p2 の fd の種類を `UNKNOWN` にする）を見張っているのは、p1 のままの2つの検査と、包んだ p2 の filetype の検査だけ。
 * - 包んだ p2 の `isatty` は、直す前（`character-device`）でも偽になるので、後退の見張りだけ。
 *   wasmtime のアダプターの `fd_fdstat_get` は、ディレクトリ以外の descriptor の `fs_rights_base` を全ビット（SEEK・TELL を含む）から始めて READ・WRITE しか落とさず、
 *   wasi-libc の p1 の `isatty` は SEEK か TELL があれば偽を返すため
 * - wasi-libc の p2 の `isatty` は種類を見ない（ファイルの fd はいつも偽）ので、ネイティブの p2 も後退の見張りだけ
 *
 * device と `/dev/tty` の fd の `fstat` も確かめる（#885。ADR 0267「ファイル以外の descriptor の metadata-hash」）。
 * 包んだ p2 とネイティブの p2 の `fstat` は `descriptor.metadata-hash` も呼ぶので（アダプターは `stat` の後。wasi-libc の順序は確かめていない）、直す前（`bad-descriptor`）は EBADF で落ちる。
 * p1 のままは前から通るので、後退の見張り。`/dev/tty` を開けるように、console の `Tty` を用意しておく。
 */
class DevDeviceFdProgramTest {
    private val limit = Duration.ofSeconds(120)

    private val address = "00000000-0000-4000-8000-000000000865"

    private object TestDevice : Device {
        override val kind = "test:device"
        override val methods: DeviceMethods = DeviceMethods.builder().build()
    }

    private fun resource(path: String): ByteArray = P2Testing.resource(path)

    private fun machine(h: Harness): Machine {
        val m = h.machine(fuelPerTick = 10_000_000)
        m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "test:root")
        m.vfs.createDirectory(VfsPath.parse("/bin"))
        m.vfs.createDirectory(VfsPath.parse("/dev"))
        assertNull(m.mountDev())
        assertTrue(m.devices.attach(address, TestDevice))
        // stdin が Closed なので console の Tty は作られない。/dev/tty を開けるように作っておく
        m.lock.withLockValue { m.ensureConsoleLocked() }
        return m
    }

    /** [bytes] を `/bin/prog` に置いて exec し、標準出力と終了コードを返す */
    private fun runProgram(bytes: ByteArray): Pair<String, Int?> = Harness(execRuntime = EndiveRuntime()).use { h ->
        val m = machine(h)
        val f = m.vfs.open(VfsPath.parse("/bin/prog"), OpenOptions(read = true, write = true, create = true, truncate = true))
        m.vfs.write(f, bytes, 0, bytes.size)
        val out = P2Testing.outputStream(m)
        val stdio = StdioSpec(StdioSource.Closed, StdioSource.Object(out, HandleRights.WRITE), StdioSource.Object(out, HandleRights.WRITE))
        val pid = m.exec("/bin/prog", KernelDrivers.DEFAULT, SpawnOptions(args = listOf("dev-device-wasi", address), stdio = stdio, fds = SpawnOptions.ROOT_PREOPEN))
        val text = StringBuilder()
        eventually(60_000, "終わらない") {
            h.scheduler.tick()
            text.append(P2Testing.drain(m, out))
            m.stateOf(pid).isTerminal
        }
        text.append(P2Testing.drain(m, out))
        text.toString() to m.lock.withLockValue { m.kernel.processes[pid]?.exitCode }
    }

    @Test
    fun `p1のままではdeviceのfdはUNKNOWNでisattyが偽、deviceとttyのfstatが成功する`() = timeout(limit) {
        assertEquals("OK\n" to 0, runProgram(resource("/kibicomputers/wasm/dev-device-wasi.wasm")))
    }

    @Test
    fun `アダプターで包んだp2でもdeviceのfdはUNKNOWNでisattyが偽、deviceとttyのfstatが成功する`() = timeout(limit) {
        assertEquals("OK\n" to 0, runProgram(P2Testing.wrap("dev-device-wasi")))
    }

    @Test
    fun `wasm32-wasip2でビルドしたp2でもdeviceのfdのisattyが偽、deviceとttyのfstatが成功する`() = timeout(limit) {
        assertEquals("OK\n" to 0, runProgram(resource("/kibicomputers/wasm-native/dev-device-wasi-p2.wasm")))
    }
}
