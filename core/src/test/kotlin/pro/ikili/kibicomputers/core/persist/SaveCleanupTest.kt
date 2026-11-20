package pro.ikili.kibicomputers.core.persist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.UUID
import java.util.function.Consumer

/** 残ったディレクトリとモジュールの置き場の掃除（`SaveCleanup`。#564、ADR 0564）のテスト */
class SaveCleanupTest {
    @TempDir
    lateinit var dir: Path

    private val machines by lazy { dir.resolve("machines") }
    private val modulesRoot by lazy { dir.resolve("modules") }

    private val p = MachineOwner("minecraft:overworld", 0, 64, 0)
    private val q = MachineOwner("minecraft:overworld", 16, 64, 0)

    private val now = 100L * 24 * 60 * 60 * 1000
    private val old = now - 30L * 24 * 60 * 60 * 1000

    /** 位置ごとの答え（ない位置は ABSENT）と、問われた位置 */
    private val answers = HashMap<MachineOwner, MachineReference>()
    private val asked = ArrayList<UUID>()
    private val probe = MachineReferenceProbe { id, owner, answer ->
        asked += id
        answer.accept(answers[owner] ?: MachineReference.ABSENT)
    }

    private fun abiSection(hash: ByteArray, layer: Int = SnapshotSection.ABI_LAYER_P1, layerVersion: Int = SnapshotSection.P1_LAYER_VERSION): SnapshotSection {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(2)
            out.writeShort(layer)
            out.writeShort(layerVersion)
            out.write(hash)
            out.write(ByteArray(17))
        }
        return SnapshotSection(SnapshotSection.PROCESS_ABI, SnapshotSection.PROCESS_ABI_VERSION, bytes.toByteArray())
    }

    private fun sourceOf(vararg sections: SnapshotSection) = MachineSnapshotSource { id, generation, version ->
        MachineSnapshot(
            id, generation, version,
            listOf(SnapshotSection(SnapshotSection.KERNEL, SnapshotSection.KERNEL_VERSION, ByteArray(40))) + sections,
        )
    }

    /** 前のセッションで [owner] の台を1回書き、そのディレクトリを古くして ID を返す */
    private fun previousMachine(owner: MachineOwner, source: MachineSnapshotSource = sourceOf()): UUID {
        val store = MachineSaveStore(machines, MachineIdIndex(), "test")
        val identity = store.assignNewId(owner)!!
        store.write(identity, owner, source)
        age(identity.id.toString())
        return identity.id
    }

    private fun age(name: String) {
        val d = machines.resolve(name)
        Files.list(d).use { it.forEach { f -> Files.setLastModifiedTime(f, FileTime.fromMillis(old)) } }
        Files.setLastModifiedTime(d, FileTime.fromMillis(old))
    }

    private fun run(cleanup: SaveCleanup): SaveCleanup {
        var ticks = 0
        while (!cleanup.finished) {
            cleanup.tick()
            check(++ticks < 10_000) { "the cleanup did not finish" }
        }
        return cleanup
    }

    private fun cleanup(
        store: MachineSaveStore,
        modules: FileModuleStore? = null,
        settings: SaveCleanupSettings = SaveCleanupSettings.DEFAULT,
        dryRun: Boolean = false,
        force: Boolean = false,
    ) = SaveCleanup(store, modules, probe, settings, dryRun, force) { now }

    @Test
    fun `指されていないディレクトリだけを消し、ある・判定できないものは残す`() {
        val gone = previousMachine(p)
        val here = previousMachine(q)
        val unknownAt = MachineOwner("minecraft:overworld", 32, 64, 0)
        val unknown = previousMachine(unknownAt)
        answers[q] = MachineReference.PRESENT
        answers[unknownAt] = MachineReference.UNKNOWN
        val store = MachineSaveStore(machines, MachineIdIndex(), "test")
        val report = run(cleanup(store)).report
        assertFalse(Files.exists(machines.resolve(gone.toString())))
        assertTrue(Files.exists(machines.resolve(here.toString())))
        assertTrue(Files.exists(machines.resolve(unknown.toString())))
        assertEquals(3, report.candidates)
        assertEquals(1, report.deleted)
        assertEquals(1, report.present)
        assertEquals(1, report.undetermined)
        assertTrue(report.deletedBytes > 0)
    }

    @Test
    fun `このサーバーで見たIDは問わずに残す`() {
        val id = previousMachine(p)
        val store = MachineSaveStore(machines, MachineIdIndex(), "test")
        assertEquals(ClaimResult.KEPT, store.claim(id.toString(), 1, 0, p).result)
        store.index.removeIfOwner(id, p)
        val report = run(cleanup(store)).report
        assertTrue(Files.exists(machines.resolve(id.toString())))
        assertEquals(1, report.seen)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `猶予の中のディレクトリは残す`() {
        val id = previousMachine(p)
        Files.setLastModifiedTime(store0Generation(id), FileTime.fromMillis(now - 1000))
        val store = MachineSaveStore(machines, MachineIdIndex(), "test")
        val report = run(cleanup(store)).report
        assertTrue(Files.exists(machines.resolve(id.toString())))
        assertEquals(1, report.recent)
        assertTrue(asked.isEmpty())
    }

    private fun store0Generation(id: UUID): Path = machines.resolve(id.toString()).resolve("1.bin")

    @Test
    fun `大文字の名前は候補にしない`() {
        val id = previousMachine(p)
        val upper = id.toString().uppercase()
        val tmp = machines.resolve("tmp-rename")
        Files.move(machines.resolve(id.toString()), tmp)
        Files.move(tmp, machines.resolve(upper))
        val store = MachineSaveStore(machines, MachineIdIndex(), "test")
        val report = run(cleanup(store)).report
        assertEquals(0, report.candidates)
        assertTrue(Files.isDirectory(machines.resolve(upper)))
    }

    @Test
    fun `owner_binがなく世代もないものは問わずに消し、世代があれば残す`() {
        val empty = UUID.randomUUID()
        Files.createDirectories(machines.resolve(empty.toString()))
        age(empty.toString())
        val orphan = previousMachine(p)
        Files.delete(machines.resolve(orphan.toString()).resolve(OwnerRecord.FILE_NAME))
        age(orphan.toString())
        val store = MachineSaveStore(machines, MachineIdIndex(), "test")
        val report = run(cleanup(store)).report
        assertFalse(Files.exists(machines.resolve(empty.toString())))
        assertTrue(Files.exists(machines.resolve(orphan.toString())))
        assertEquals(1, report.deleted)
        assertEquals(1, report.undetermined)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `安全の上限に当たれば1つも消さず、forceなら消す`() {
        val ids = (0 until 3).map { previousMachine(MachineOwner("minecraft:overworld", it * 16, 64, 0)) }
        val settings = SaveCleanupSettings(safetyMinimum = 2, safetyRatio = 0.5)
        val report = run(cleanup(MachineSaveStore(machines, MachineIdIndex(), "test"), settings = settings)).report
        assertTrue(report.safetyStopped)
        assertEquals(0, report.deleted)
        for (id in ids) assertTrue(Files.exists(machines.resolve(id.toString())))

        val forced = run(cleanup(MachineSaveStore(machines, MachineIdIndex(), "test"), settings = settings, force = true)).report
        assertFalse(forced.safetyStopped)
        assertEquals(3, forced.deleted)
    }

    @Test
    fun `試しの実行では消さずに数える`() {
        val id = previousMachine(p)
        val report = run(cleanup(MachineSaveStore(machines, MachineIdIndex(), "test"), dryRun = true)).report
        assertEquals(1, report.unreferenced)
        assertEquals(0, report.deleted)
        assertTrue(Files.exists(machines.resolve(id.toString())))
    }

    @Test
    fun `消した後にclaimが来ればowner_binを書き直してKEPTになる`() {
        val id = previousMachine(p)
        val store = MachineSaveStore(machines, MachineIdIndex(), "test")
        run(cleanup(store))
        assertFalse(Files.exists(machines.resolve(id.toString())))
        val claim = store.claim(id.toString(), 1, 0, p)
        assertEquals(ClaimResult.KEPT, claim.result)
        store.write(claim.identity!!, p, sourceOf())
        assertEquals(OwnerRecord(0, p), OwnerRecord.read(machines.resolve(id.toString()).resolve(OwnerRecord.FILE_NAME)))
    }

    @Test
    fun `判定の後に更新されたディレクトリは消さない`() {
        val id = previousMachine(p)
        val store = MachineSaveStore(machines, MachineIdIndex(), "test")
        val judged = Files.getLastModifiedTime(machines.resolve(id.toString())).toMillis()
        Files.setLastModifiedTime(store0Generation(id), FileTime.fromMillis(judged + 10_000))
        var result = 0L
        assertTrue(store.deleteUnreferenced(id, judged) { result = it })
        assertEquals(-1, result)
        assertTrue(Files.exists(machines.resolve(id.toString())))
        store.cleanupDeletionSkipped(id)
        assertEquals(ClaimResult.KEPT, store.claim(id.toString(), 1, 0, p).result)
    }

    // ---- モジュール ----

    private fun putModule(store: FileModuleStore, content: String): String {
        val bytes = content.toByteArray()
        val hash = ModuleStore.sha256(bytes)
        assertTrue(store.put(hash, bytes))
        return ModuleStore.hex(hash)
    }

    private fun oldModules(vararg contents: String): List<String> {
        val previous = FileModuleStore(modulesRoot)
        val hexes = contents.map { putModule(previous, it) }
        Files.list(modulesRoot).use { it.forEach { f -> Files.setLastModifiedTime(f, FileTime.fromMillis(old)) } }
        return hexes
    }

    private fun hashOf(hex: String): ByteArray = ByteArray(32) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun `参照されている・このセッションで使ったモジュールは残し、ほかは消す`() {
        val (referenced, used, unused) = oldModules("referenced", "used", "unused")
        answers[p] = MachineReference.PRESENT
        previousMachine(p, sourceOf(abiSection(hashOf(referenced))))
        val modules = FileModuleStore(modulesRoot)
        assertNotNull(modules.get(hashOf(used)))
        val store = MachineSaveStore(machines, MachineIdIndex(), "test")
        val report = run(cleanup(store, modules)).report
        assertTrue(Files.exists(modulesRoot.resolve("$referenced.wasm")))
        assertTrue(Files.exists(modulesRoot.resolve("$used.wasm")))
        assertFalse(Files.exists(modulesRoot.resolve("$unused.wasm")))
        assertFalse(modules.contains(hashOf(unused)))
        assertTrue(modules.contains(hashOf(used)))
        assertEquals(3, report.moduleCandidates)
        assertEquals(1, report.modulesReferenced)
        assertEquals(1, report.modulesTouched)
        assertEquals(1, report.modulesDeleted)
    }

    @Test
    fun `消したディレクトリの参照ではモジュールを残さない`() {
        val (module) = oldModules("only-in-gone")
        previousMachine(p, sourceOf(abiSection(hashOf(module))))
        val modules = FileModuleStore(modulesRoot)
        run(cleanup(MachineSaveStore(machines, MachineIdIndex(), "test"), modules))
        assertFalse(Files.exists(modulesRoot.resolve("$module.wasm")))
    }

    @Test
    fun `知らないABI層の世代があればモジュールを1つも消さない`() {
        val (unused) = oldModules("unused")
        answers[p] = MachineReference.PRESENT
        previousMachine(p, sourceOf(abiSection(ByteArray(32), layer = 9)))
        val modules = FileModuleStore(modulesRoot)
        val report = run(cleanup(MachineSaveStore(machines, MachineIdIndex(), "test"), modules)).report
        assertTrue(report.modulesAborted)
        assertTrue(Files.exists(modulesRoot.resolve("$unused.wasm")))
    }

    @Test
    fun `使ったモジュールは消さず、消した後のputは書き直す`() {
        val (hex) = oldModules("module")
        val modules = FileModuleStore(modulesRoot)
        val bytes = "module".toByteArray()
        assertTrue(modules.put(hashOf(hex), bytes))
        assertEquals(-1, modules.deleteIfUnused(hex))

        val (other) = oldModules("other")
        val fresh = FileModuleStore(modulesRoot)
        assertTrue(fresh.deleteIfUnused(other) > 0)
        assertFalse(fresh.contains(hashOf(other)))
        assertTrue(fresh.put(hashOf(other), "other".toByteArray()))
        assertTrue(Files.exists(modulesRoot.resolve("$other.wasm")))
        assertTrue(fresh.contains(hashOf(other)))
    }

    // ---- 参照の読み手 ----

    private fun refsOf(snapshot: MachineSnapshot): List<String> {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { snapshot.writeTo(it) }
        val out = ArrayList<String>()
        DataInputStream(bytes.toByteArray().inputStream()).use { input -> MachineSnapshot.readModuleReferences(input) { out += ModuleStore.hex(it) } }
        return out
    }

    @Test
    fun `参照の読み手はPROCESS_ABI節の元のハッシュだけを読み、差分の世代も読む`() {
        val id = UUID.randomUUID()
        val a = ByteArray(32) { 1 }
        val b = ByteArray(32) { 2 }
        val snapshot = MachineSnapshot(
            id, 3, "test",
            listOf(
                SnapshotSection(SnapshotSection.KERNEL, SnapshotSection.KERNEL_VERSION, ByteArray(10)),
                abiSection(a),
                abiSection(ByteArray(32), layer = SnapshotSection.ABI_LAYER_TEST),
                abiSection(b, layerVersion = 1),
                SnapshotSection(SnapshotSection.TMPFS, SnapshotSection.TMPFS_VERSION, ByteArray(100)),
            ),
            deltaBase = DeltaBase(id, 2, 100, 7),
        )
        assertEquals(listOf(ModuleStore.hex(a), ModuleStore.hex(b)), refsOf(snapshot))
        val cold = MachineSnapshot(id, 4, "test", coldBootCode = ColdBootReason.entries.first { it != ColdBootReason.NONE }.code)
        assertEquals(emptyList<String>(), refsOf(cold))
    }

    @Test
    fun `参照の読み手は知らない版と層を断る`() {
        val id = UUID.randomUUID()
        assertThrows(SnapshotFormatException::class.java) {
            refsOf(MachineSnapshot(id, 1, "test", listOf(abiSection(ByteArray(32), layerVersion = 99))))
        }
        assertThrows(SnapshotFormatException::class.java) {
            refsOf(MachineSnapshot(id, 1, "test", listOf(SnapshotSection(SnapshotSection.PROCESS_ABI, 99, ByteArray(40)))))
        }
        assertThrows(SnapshotFormatException::class.java) {
            refsOf(MachineSnapshot(id, 1, "test", listOf(abiSection(ByteArray(32), layer = 7))))
        }
    }

    @Test
    fun `接続層の答えが後から来ても進む`() {
        val id = previousMachine(p)
        val later = ArrayList<Consumer<MachineReference>>()
        val store = MachineSaveStore(machines, MachineIdIndex(), "test")
        val cleanup = SaveCleanup(store, null, { _, _, answer -> later += answer }) { now }
        repeat(5) { cleanup.tick() }
        assertFalse(cleanup.finished)
        later.single().accept(MachineReference.ABSENT)
        run(cleanup)
        assertFalse(Files.exists(machines.resolve(id.toString())))
    }
}
