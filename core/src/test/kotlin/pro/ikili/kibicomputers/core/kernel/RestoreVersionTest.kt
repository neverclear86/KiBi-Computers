package pro.ikili.kibicomputers.core.kernel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pro.ikili.kibicomputers.core.kernel.boot.BootVolume
import pro.ikili.kibicomputers.core.kernel.boot.BootVolumeOpener
import pro.ikili.kibicomputers.core.kernel.fd.StdioSource
import pro.ikili.kibicomputers.core.kernel.fd.StdioSpec
import pro.ikili.kibicomputers.core.kernel.io.ByteStream
import pro.ikili.kibicomputers.core.kernel.obj.HandleRights
import pro.ikili.kibicomputers.core.kernel.process.KernelDrivers
import pro.ikili.kibicomputers.core.kernel.process.KillSignal
import pro.ikili.kibicomputers.core.kernel.process.ProcessState
import pro.ikili.kibicomputers.core.kernel.process.SpawnOptions
import pro.ikili.kibicomputers.core.kernel.vfs.OpenOptions
import pro.ikili.kibicomputers.core.kernel.vfs.TestMemoryFileSystem
import pro.ikili.kibicomputers.core.kernel.vfs.VfsPath
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.core.persist.ColdBootCause
import pro.ikili.kibicomputers.core.persist.ColdBootReason
import pro.ikili.kibicomputers.core.persist.DeltaBase
import pro.ikili.kibicomputers.core.persist.KernelStateCopy
import pro.ikili.kibicomputers.core.persist.MachineIdIndex
import pro.ikili.kibicomputers.core.persist.MachineIdentity
import pro.ikili.kibicomputers.core.persist.MachineOwner
import pro.ikili.kibicomputers.core.persist.MachineSaveStore
import pro.ikili.kibicomputers.core.persist.MachineSnapshot
import pro.ikili.kibicomputers.core.persist.MachineSnapshotSource
import pro.ikili.kibicomputers.core.persist.ModuleStore
import pro.ikili.kibicomputers.core.persist.RestoreListener
import pro.ikili.kibicomputers.core.persist.RestoreOutcome
import pro.ikili.kibicomputers.core.persist.RestoreRequest
import pro.ikili.kibicomputers.core.persist.RestoreSource
import pro.ikili.kibicomputers.core.persist.SnapshotSection
import pro.ikili.kibicomputers.core.persist.snapshot.SnapshotReader
import pro.ikili.kibicomputers.core.runtime.Instrumentation
import pro.ikili.kibicomputers.core.runtime.endive.EndiveRuntime
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Collections
import java.util.UUID
import org.junit.jupiter.api.assertTimeoutPreemptively as timeout

/**
 * 保存データの版の比較（#88、ADR 0088）: 外枠の MOD のバージョンが今のものと違えば、中身を解釈せずに冷えた起動（`MOD_VERSION`）にする。
 * 差分の世代は基準の版も比べる。計測ありのモジュールの変換器の版が違えば、読み込まずに `MODULE_MISMATCH` として覚え、
 * 使う終わっていないプロセスがあるときだけ冷えた起動にする。
 *
 * 版の違うファイルは、バイトを書き換えずに「`"old"` の置き場で書く → 同じディレクトリに `"new"` の置き場を作って戻す」で作る
 * （再起動して MOD を更新したのと同じ形）。同じ版で戻ることは、全部 `"test"` で書いて読む既存の復元のテストが確かめる
 */
class RestoreVersionTest {
    @TempDir
    lateinit var dir: Path

    private val limit = Duration.ofSeconds(120)
    private val owner = MachineOwner("minecraft:overworld", 0, 64, 0)

    private fun store(version: String): MachineSaveStore =
        MachineSaveStore(Files.createDirectories(dir.resolve("saves")), MachineIdIndex(), version)

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/kibicomputers/wasm/$name")) { "$name がリソースにない" }.use { it.readBytes() }

    private fun bootConfig(): BootConfig = BootConfig.builder().addBootDisk("none").build()

    private class Finished {
        val outcomes: MutableList<Pair<RestoreOutcome, ColdBootCause?>> = Collections.synchronizedList(ArrayList())
        val listener = RestoreListener { _, outcome, cause -> outcomes += outcome to cause }
    }

    /** 戻すマシンが開く根のディスクを、新しい空のメモリのファイルシステムにする */
    private fun openFreshRoot(h: Harness) {
        h.scheduler.hooks.bootVolumes = BootVolumeOpener { id -> if (id == "root") BootVolume(TestMemoryFileSystem(), readOnly = false) else null }
    }

    private fun restore(h: Harness, store: MachineSaveStore, identity: MachineIdentity, finished: Finished = Finished()): Machine {
        val m = h.machine(fuelPerTick = 10_000_000)
        assertTrue(m.restore(RestoreRequest(RestoreSource(store, identity.id, identity.generation), bootConfig(), finished.listener)))
        eventually(60_000, "知らせが来ない") {
            h.scheduler.tick()
            finished.outcomes.isNotEmpty()
        }
        return m
    }

    private fun assertColdBoot(m: Machine, finished: Finished, cause: ColdBootCause) {
        assertEquals(listOf(RestoreOutcome.COLD_BOOT to cause), finished.outcomes.toList())
        assertEquals(0, m.lock.withLockValue { m.kernel.processes.size }, "表は空のまま")
        assertTrue(m.lock.withLockValue { m.kernel.mounts.entries.isEmpty() }, "マウントは空のまま")
    }

    /** 中身を解釈されない（比べる前に止まる）写しを作る口。[forcedVersion] を渡せば、置き場の版の代わりにそれを書く */
    private fun fake(
        base: DeltaBase? = null,
        coldBoot: ColdBootReason = ColdBootReason.NONE,
        forcedVersion: String? = null,
    ): MachineSnapshotSource = object : MachineSnapshotSource {
        override val deltaBase: DeltaBase? get() = base

        override fun build(id: UUID, generation: Long, modVersion: String): MachineSnapshot = MachineSnapshot(
            id, generation, forcedVersion ?: modVersion,
            if (coldBoot == ColdBootReason.NONE) listOf(SnapshotSection(SnapshotSection.KERNEL, 1, ByteArray(16))) else emptyList(),
            coldBootCode = coldBoot.code,
            deltaBase = base,
        )
    }

    /** `/bin/<name>` に [bytes] を置いて動かす（標準入出力は新しい ByteStream） */
    private fun exec(m: Machine, name: String, bytes: ByteArray): Int {
        val f = m.vfs.open(VfsPath.parse("/bin/$name"), OpenOptions(read = true, write = true, create = true, truncate = true))
        m.vfs.write(f, bytes, 0, bytes.size)
        val (input, output) = m.lock.withLockValue {
            ByteStream(4096).also { m.kernel.objects.add(it) }.id to ByteStream(64 * 1024).also { m.kernel.objects.add(it) }.id
        }
        val stdio = StdioSpec(StdioSource.Object(input, HandleRights.READ), StdioSource.Object(output, HandleRights.WRITE), StdioSource.Closed)
        return m.exec("/bin/$name", KernelDrivers.DEFAULT, SpawnOptions(args = listOf(name), stdio = stdio))
    }

    private fun mountRoot(m: Machine) {
        m.vfs.mount(VfsPath.ROOT, TestMemoryFileSystem(), readOnly = false, source = "disk:root")
        m.vfs.createDirectory(VfsPath.parse("/bin"))
    }

    private fun awaitWaiting(h: Harness, m: Machine, pids: List<Int>) = eventually(60_000, "入力を待たない") {
        h.scheduler.tick()
        pids.all { m.stateOf(it) == ProcessState.WAITING_EVENT } && m.isAtWaitPoint
    }

    private fun captureCopy(m: Machine): KernelStateCopy {
        var copy: KernelStateCopy? = null
        eventually(10_000, "写しが取れない") {
            copy = m.captureCopy(CapturePolicy.SKIP_IF_TRANSIENT)
            copy != null
        }
        return copy!!
    }

    /** `prompt` を init として動かし、入力を待ったところを [store] に書いて閉じる */
    private fun savePrompt(h: Harness, store: MachineSaveStore): MachineIdentity {
        val m = h.machine(fuelPerTick = 10_000_000)
        mountRoot(m)
        val pid = exec(m, "prompt", resource("prompt-wasi.wasm"))
        m.lock.withLockValue { m.kernel.initPid = pid }
        awaitWaiting(h, m, listOf(pid))
        val identity = store.write(store.assignNewId(owner)!!, owner, captureCopy(m).toSource()).identity
        m.close()
        return identity
    }

    /** `PROCESS_ABI` 節（p1 の層）の変換器の版を [version] に書き換えた節 */
    private fun withInstrumentationVersion(section: SnapshotSection, version: Int): SnapshotSection {
        val payload = section.payload
        val r = SnapshotReader(payload)
        r.i32()
        assertEquals(SnapshotSection.ABI_LAYER_P1, r.u16())
        r.u16()
        r.raw(32)
        r.raw(32)
        assertTrue(r.bool(), "計測ありのモジュールのはず")
        repeat(r.count(Int.MAX_VALUE, 4)) { r.string() }
        val at = payload.size - r.remaining
        assertEquals(Instrumentation.VERSION, ByteBuffer.wrap(payload).getInt(at))
        ByteBuffer.wrap(payload).putInt(at, version)
        return SnapshotSection(section.kind, section.version, payload)
    }

    private fun pidOf(section: SnapshotSection): Int = SnapshotReader(section.payload).i32()

    /** [sections] をそのまま書く写し */
    private fun sectionsSource(sections: List<SnapshotSection>): MachineSnapshotSource =
        MachineSnapshotSource { id, generation, modVersion -> MachineSnapshot(id, generation, modVersion, sections) }

    @Test
    fun `MODのバージョンが違えばMOD_VERSIONで冷えた起動にし、モジュールの読み込みを頼まない`() = timeout(limit) {
        val modules = MemoryModuleStore()
        Harness(execRuntime = EndiveRuntime(), moduleStore = modules).use { h ->
            openFreshRoot(h)
            val identity = savePrompt(h, store("old"))
            val newStore = store("new")
            assertEquals("old", newStore.readGeneration(identity.id, identity.generation).snapshot.modVersion)
            // 置き場からモジュールを消しても、MODULE_MISSING でなく MOD_VERSION（読み込みの前に比べる）
            modules.remove(ModuleStore.sha256(resource("prompt-wasi.wasm")))
            val gets = modules.gets.get()
            val finished = Finished()
            val m = restore(h, newStore, identity, finished)
            assertColdBoot(m, finished, ColdBootCause.MOD_VERSION)
            assertEquals(gets, modules.gets.get(), "モジュールを置き場から読んだ")
            m.close()

            // 同じ版の置き場なら（MOD を戻したのと同じ）、同じ世代は MODULE_MISSING まで進む（中身を読んでいる）
            val finished2 = Finished()
            val m2 = restore(h, store("old"), identity, finished2)
            assertColdBoot(m2, finished2, ColdBootCause.MODULE_MISSING)
            m2.close()
        }
    }

    @Test
    fun `KILLED節の写しでもMODのバージョンが違えば理由はMOD_VERSION`() = timeout(limit) {
        val old = store("old")
        val identity = old.write(old.assignNewId(owner)!!, owner, fake(coldBoot = ColdBootReason.INIT_KILLED)).identity
        assertTrue(old.readGeneration(identity.id, identity.generation).snapshot.requiresColdBoot)
        Harness().use { h ->
            val finished = Finished()
            val m = restore(h, store("new"), identity, finished)
            assertColdBoot(m, finished, ColdBootCause.MOD_VERSION)
            m.close()
            // 同じ版なら KILLED_SECTION
            val finished2 = Finished()
            val m2 = restore(h, store("old"), identity, finished2)
            assertColdBoot(m2, finished2, ColdBootCause.KILLED_SECTION)
            m2.close()
        }
    }

    @Test
    fun `差分の世代で基準だけMODのバージョンが違えばMOD_VERSION`() = timeout(limit) {
        val s = store("new")
        // 基準（全体）だけ "old" で書き、差分は今の版 "new" で書く
        val full = s.write(s.assignNewId(owner)!!, owner, fake(forcedVersion = "old"))
        assertFalse(full.delta)
        val base = s.deltaBaseFor(full.identity, owner)!!
        val delta = s.write(full.identity, owner, fake(base = base))
        assertTrue(delta.delta)
        val written = s.readGeneration(delta.identity.id, delta.identity.generation).snapshot
        assertEquals("new", written.modVersion)
        assertEquals(base, written.deltaBase)
        Harness().use { h ->
            val finished = Finished()
            val m = restore(h, s, delta.identity, finished)
            assertColdBoot(m, finished, ColdBootCause.MOD_VERSION)
            m.close()
        }
    }

    @Test
    fun `変換器の版が違うモジュールは読み込まず、使う終わっていないプロセスがあればMODULE_MISMATCH`() = timeout(limit) {
        val modules = MemoryModuleStore()
        val s = store("test")
        Harness(execRuntime = EndiveRuntime(), moduleStore = modules).use { h ->
            openFreshRoot(h)
            val identity = savePrompt(h, s)
            val snapshot = s.readGeneration(identity.id, identity.generation).snapshot
            val patched = snapshot.sections.map {
                if (it.kind == SnapshotSection.PROCESS_ABI) withInstrumentationVersion(it, Instrumentation.VERSION + 1) else it
            }
            val written = s.write(identity, owner, sectionsSource(patched)).identity
            val gets = modules.gets.get()
            val finished = Finished()
            val m = restore(h, s, written, finished)
            assertColdBoot(m, finished, ColdBootCause.MODULE_MISMATCH)
            assertEquals(gets, modules.gets.get(), "変換器の版が違うモジュールを置き場から読んだ")
            m.close()
        }
    }

    @Test
    fun `変換器の版が違うモジュールを終わったプロセスだけが参照するなら、読み込まずに戻す`() = timeout(limit) {
        val modules = MemoryModuleStore()
        val s = store("test")
        Harness(execRuntime = EndiveRuntime(), moduleStore = modules).use { h ->
            openFreshRoot(h)
            val m = h.machine(fuelPerTick = 10_000_000)
            mountRoot(m)
            val prompt = resource("prompt-wasi.wasm")
            val prompt2 = SnapshotDecoderModuleSourcesTest.withCustomSection(prompt)
            val init = exec(m, "prompt", prompt)
            m.lock.withLockValue { m.kernel.initPid = init }
            val other = exec(m, "prompt2", prompt2)
            awaitWaiting(h, m, listOf(init, other))
            // 生きている間の other の PROCESS_ABI 節
            val alive = captureCopy(m).toSource().build(UUID.randomUUID(), 1, "test")
            val otherSection = alive.sections.single { it.kind == SnapshotSection.PROCESS_ABI && pidOf(it) == other }
            // other を殺す（親がないので刈り取られず、表に残る）
            m.lock.withLockValue { m.killLocked(other, KillSignal.KILL) }
            eventually(30_000, "終わらない") {
                h.scheduler.tick()
                m.stateOf(other).isTerminal && m.isAtWaitPoint
            }
            val dead = captureCopy(m).toSource().build(UUID.randomUUID(), 1, "test")
            assertTrue(dead.sections.none { it.kind == SnapshotSection.PROCESS_ABI && pidOf(it) == other }, "終わったプロセスの節は書かない")
            // 終わったプロセスの節（変換器の版だけ違う）を足して書く
            val sections = dead.sections + withInstrumentationVersion(otherSection, Instrumentation.VERSION + 1)
            val identity = s.write(s.assignNewId(owner)!!, owner, sectionsSource(sections)).identity
            m.close()

            // other のモジュールを置き場から消しても読みに行かない（読みに行けば MODULE_MISSING を覚えるが、使わないので結果は同じ。
            // 読みに行かないことは置き場を引いた数で確かめる）
            val gets = modules.gets.get()
            val finished = Finished()
            val m2 = restore(h, s, identity, finished)
            assertEquals(listOf(RestoreOutcome.RESTORED to null), finished.outcomes.toList())
            assertEquals(MachineState.RUNNING, m2.state)
            assertEquals(ProcessState.WAITING_EVENT, m2.stateOf(init))
            assertNotEquals(gets, modules.gets.get(), "init のモジュールは読む")
            assertEquals(gets + 1, modules.gets.get(), "変換器の版が違うモジュールを置き場から読んだ")
            m2.close()
        }
    }
}
