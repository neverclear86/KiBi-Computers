package pro.ikili.kibicomputers.neoforge.machine

import com.mojang.logging.LogUtils
import net.minecraft.Util
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.gametest.framework.GameTestServer
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.server.level.ChunkHolder
import net.minecraft.server.level.FullChunkStatus
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.chunk.LevelChunk
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import pro.ikili.kibicomputers.core.persist.FileModuleStore
import pro.ikili.kibicomputers.core.persist.MachineOwner
import pro.ikili.kibicomputers.core.persist.MachineReference
import pro.ikili.kibicomputers.core.persist.MachineReferenceProbe
import pro.ikili.kibicomputers.core.persist.MachineSaveStore
import pro.ikili.kibicomputers.core.persist.SaveCleanup
import pro.ikili.kibicomputers.core.persist.SaveCleanupSettings
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.RejectedExecutionException
import java.util.function.Consumer

/**
 * 残ったディレクトリとモジュールの置き場の掃除（#564、ADR 0564）を、サーバーの tick で回す（判定は core の [SaveCleanup]）。
 *
 * - 自動: サーバーが起動してから [START_DELAY_TICKS] tick 後に1回（スポーンのチャンクの claim と復元が落ち着いてから）。
 *   GameTest のサーバーでは走らせない（テストは [start] を呼ぶ）。一定周期では回さない（指されなくなるのはまれな経路なので、再起動ごとで足りる）
 * - コマンド: `/kibicomputers saves cleanup [dry-run|force]`・`status`（[start]・[last]）
 * - 停止（[ServerStoppingEvent]）で止める（列に入れた調べ・読みの仕事は何もせずに戻る）
 *
 * メインスレッドだけで使う
 */
internal object SaveCleanups {
    private val LOGGER = LogUtils.getLogger()

    /** 自動の掃除を始めるまでの tick（仮の値。確定は #251） */
    const val START_DELAY_TICKS: Int = 600

    private var server: MinecraftServer? = null
    private var ticksUntilAuto = -1
    private var running: SaveCleanup? = null

    /** 最後に始めた掃除（走っているものを含む）。このサーバーでまだなければ `null` */
    var last: SaveCleanup? = null
        private set

    fun register() {
        NeoForge.EVENT_BUS.addListener<ServerStartedEvent> { event ->
            server = event.server
            running = null
            last = null
            autoOverride = null
            ticksUntilAuto = if (event.server is GameTestServer) -1 else START_DELAY_TICKS
        }
        NeoForge.EVENT_BUS.addListener<ServerTickEvent.Post> { tick() }
        NeoForge.EVENT_BUS.addListener<ServerStoppingEvent> { stop() }
        NeoForge.EVENT_BUS.addListener<ServerStoppedEvent> {
            server = null
            last = null
        }
    }

    /** 停止（[ServerStoppingEvent]）: 自動の掃除を取り消し、走っている掃除を止める（テストもここを通す） */
    internal fun stop() {
        ticksUntilAuto = -1
        running?.cancel()
        running = null
    }

    /**
     * 自動の掃除を [ticks] tick 後に始めるようにする（テスト用。本番は [ServerStartedEvent] が [START_DELAY_TICKS] で仕掛ける）。
     * 始めるときの置き場・モジュールの置き場は [store]・[modules]
     */
    internal fun armAuto(ticks: Int, store: MachineSaveStore, modules: FileModuleStore?) {
        autoOverride = store to modules
        ticksUntilAuto = ticks
    }

    private var autoOverride: Pair<MachineSaveStore, FileModuleStore?>? = null

    /**
     * 掃除を始める。走っている・置き場がないなら `null`。[store]・[modules] はテストが差し替える（既定は今のサーバーのもの）
     */
    fun start(
        dryRun: Boolean,
        force: Boolean,
        store: MachineSaveStore? = WorldMachineSaves.current,
        modules: FileModuleStore? = ServerMachines.moduleStore,
        settings: SaveCleanupSettings = SaveCleanupSettings.DEFAULT,
    ): SaveCleanup? {
        val s = server ?: return null
        // 止めた掃除が、入れた削除の結果をまだ受け取っている間も、次を始めない
        if (running?.let { !it.finished || it.settling } == true || store == null) return null
        val cleanup = SaveCleanup(store, modules, ChunkReferenceProbe(s), settings, dryRun, force)
        running = cleanup
        last = cleanup
        // 何も消さない試しの実行では、自動の掃除（本番）を取り消さない（自動は、走っている掃除が終わるのを待って始める）
        if (!dryRun) ticksUntilAuto = -1
        return cleanup
    }

    /** サーバーの tick ごとに呼ぶ（[ServerTickEvent.Post]。テストも呼べる） */
    internal fun tick() {
        if (ticksUntilAuto > 0 && --ticksUntilAuto == 0) {
            if (running?.let { !it.finished || it.settling } == true) {
                // 試しの実行が走っている間は、終わるまで待つ（自動の掃除を飛ばさない）
                ticksUntilAuto = 1
                return tickRunning()
            }
            ticksUntilAuto = -1
            val override = autoOverride
            autoOverride = null
            val started = if (override != null) {
                start(dryRun = false, force = false, store = override.first, modules = override.second)
            } else {
                start(dryRun = false, force = false)
            }
            if (started == null) LOGGER.debug("Skipping the automatic computer save cleanup")
        }
        tickRunning()
    }

    private fun tickRunning() {
        val cleanup = running ?: return
        try {
            cleanup.tick()
        } catch (e: RuntimeException) {
            LOGGER.error("The computer save cleanup failed", e)
            cleanup.cancel()
        }
        // 止めた掃除は、入れた削除の結果を受け取り終えるまで tick を続ける
        if (cleanup.finished && !cleanup.settling) running = null
    }
}

/**
 * `owner.bin` の位置のチャンクに、その ID のケースがあるかを答える（#564。[MachineReferenceProbe]）。メインスレッドで問われ、答えはどのスレッドからでも返る。
 *
 * - ディメンションが今のサーバーにない（MOD を外しただけかもしれない）: [MachineReference.UNKNOWN]
 * - チャンクの `ChunkHolder` がある（見える表・更新中の表・アンロード待ち。[ServerMachines.findHolder]）: ディスクの NBT は古いかもしれないので、ディスクは読まない（[referenceInHolder]）。
 *   - holder が FULL のチャンク（`fullStatus` が FULL 以上で、最新のチャンクが `LevelChunk`）を持っていれば、メモリのブロックエンティティで判定する:
 *     その座標にその ID のケースがあれば [MachineReference.PRESENT]、なければ [MachineReference.ABSENT]（ディレクトリを消す側）。
 *     読み込まれたケースは claim するので、ふつうは「このサーバーで見た ID」として先に外れる（ここで ABSENT になるのは、その位置にもうケースがない ID）
 *   - FULL でない（途中の段階・INACCESSIBLE に降りた `LevelChunk`・アンロード待ち）: [MachineReference.UNKNOWN]
 * - それ以外: `ChunkMap.read`（IOWorker。書き込み待ちのデータを返す）の `block_entities` に、その座標のケースのタグがあり、
 *   その ID を持つなら [MachineReference.PRESENT]、なければ（チャンクがディスクにないときも）[MachineReference.ABSENT]。読みが失敗したら [MachineReference.UNKNOWN]。
 *   ディスクの確認と読みは IO のスレッド（`Util.ioPool`）の仕事で、メインスレッドは stat も読みもしない（答えは次の tick 以降に返る）。
 *   `RegionFileStorage` は、読むだけでもリージョンファイルがなければ空のものを作る。そこで、リージョンファイル（`ChunkMap` が実際に開く保存先の `r.X.Z.mca`。[regionFile]）がなければ読まずに
 *   [MachineReference.ABSENT] とする（試しの実行でも、ワールドにファイルを増やさない）。リージョンファイルのない所のチャンクで書き込み待ちになりうるのは、
 *   このセッションで生成したチャンクだけで、そこにあるケースはこのセッションで置いた（ID を振った・claim した）もの、つまり「見た ID」なので、ここには問われない
 *   （消す直前にも見直す）。リージョンファイルがあれば、書き込み待ちは `ChunkMap.read` が返す
 *
 * チャンクの NBT は覚えない（1,000 件分を持つと大きいため。同じチャンクを何度か読むことはある。問いの数は [SaveCleanupSettings.maxProbesInFlight] まで）
 */
internal class ChunkReferenceProbe(private val server: MinecraftServer) : MachineReferenceProbe {
    override fun probe(id: UUID, owner: MachineOwner, answer: Consumer<MachineReference>) {
        val location = ResourceLocation.tryParse(owner.dimension)
        val level = location?.let { server.getLevel(ResourceKey.create(Registries.DIMENSION, it)) }
        if (level == null) {
            answer.accept(MachineReference.UNKNOWN)
            return
        }
        val pos = BlockPos(owner.x, owner.y, owner.z)
        val chunk = ChunkPos(pos)
        val holder = ServerMachines.findHolder(level, chunk.toLong())
        if (holder != null) {
            answer.accept(referenceInHolder(holder, id, pos))
            return
        }
        // ここから先のディスクの確認（stat）と読みは、メインスレッドでしない（遅いディスクで tick を止めない）。IO のスレッドの仕事にする
        val region = try {
            regionFile(level, chunk)
        } catch (e: RuntimeException) {
            // 保存先が分からない（ChunkMap の中身が想定と違う）なら、削除側には倒さない
            answer.accept(MachineReference.UNKNOWN)
            return
        }
        try {
            Util.ioPool().execute {
                try {
                    readFromStorage(level, chunk, region, id, pos, answer)
                } catch (e: RuntimeException) {
                    answer.accept(MachineReference.UNKNOWN)
                }
            }
        } catch (e: RejectedExecutionException) {
            answer.accept(MachineReference.UNKNOWN)
        }
    }

    /** IO のスレッドで、チャンクの保存先の確認と読みをする（[probe]） */
    private fun readFromStorage(level: ServerLevel, chunk: ChunkPos, region: Path, id: UUID, pos: BlockPos, answer: Consumer<MachineReference>) {
        // リージョンファイルがなければ読まない（読みが空のリージョンファイルを作るため）
        if (!Files.exists(region)) {
            answer.accept(MachineReference.ABSENT)
            return
        }
        level.chunkSource.chunkMap.read(chunk).whenComplete { data, error ->
            answer.accept(
                when {
                    error != null -> MachineReference.UNKNOWN
                    data.isEmpty -> MachineReference.ABSENT
                    else -> referenceIn(data.get(), id, pos)
                },
            )
        }
    }

    companion object {
        /**
         * ディメンション [level] のチャンク [chunk] のリージョンファイル。`ChunkMap` が実際に開く保存先（`RegionFileStorage` の `folder`。
         * バニラでは `<dimension>/region`）の `r.X.Z.mca`。パスを自分で組み立てず、チャンクを読み書きする側の保存先を直接引くので、
         * チャンクの保存先を変える環境でも食い違わない（引けなければ例外。呼び出し側は「判定できない」にする）。ファイルの有無は調べない（I/O をしない）
         */
        fun regionFile(level: ServerLevel, chunk: ChunkPos): Path =
            level.chunkSource.chunkMap.worker.storage.folder.resolve("r.${chunk.regionX}.${chunk.regionZ}.mca")

        /**
         * holder [holder] のあるチャンクの [pos] に、[id] のケースがあるか（メインスレッドで呼ぶ）。FULL のチャンクを持っていればメモリで判定し（[referenceInMemory]）、
         * FULL でない段階・アンロード待ちなら [MachineReference.UNKNOWN]（メモリにだけ新しい状態があるかもしれず、ディスクの NBT では判定しない）
         */
        fun referenceInHolder(holder: ChunkHolder, id: UUID, pos: BlockPos): MachineReference {
            val loaded = (holder.latestChunk as? LevelChunk)?.takeIf { holder.fullStatus.isOrAfter(FullChunkStatus.FULL) }
            return if (loaded == null) MachineReference.UNKNOWN else referenceInMemory(loaded, id, pos)
        }

        /**
         * 読み込まれた（FULL の）チャンク [chunk] の [pos] に、[id] のケースがあるか（メインスレッドで呼ぶ）。
         * まだブロックエンティティにならずに保留されている NBT は、バニラと同じく引いた時点で取り込まれる（取り込まれたケースは claim して「見た ID」になる）
         */
        fun referenceInMemory(chunk: LevelChunk, id: UUID, pos: BlockPos): MachineReference {
            val be = chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK)
            return if (be is CaseBlockEntity && be.referencesMachine(id)) MachineReference.PRESENT else MachineReference.ABSENT
        }

        /** チャンクの NBT [data] の `block_entities` に、[pos] のケースのタグがあり ID が [id] か（どのスレッドから呼んでもよい） */
        fun referenceIn(data: CompoundTag, id: UUID, pos: BlockPos): MachineReference {
            val caseId = BlockEntityType.getKey(KiBiBlocks.CASE_BLOCK_ENTITY.get())?.toString() ?: return MachineReference.UNKNOWN
            val list = data.getList("block_entities", Tag.TAG_COMPOUND.toInt())
            for (i in 0 until list.size) {
                val tag = list.getCompound(i)
                if (tag.getInt("x") != pos.x || tag.getInt("y") != pos.y || tag.getInt("z") != pos.z) continue
                if (tag.getString("id") != caseId) continue
                if (tag.getString(CaseBlockEntity.MACHINE_ID_TAG) == id.toString()) return MachineReference.PRESENT
            }
            return MachineReference.ABSENT
        }
    }
}
