package pro.ikili.kibicomputers.neoforge.machine

import com.mojang.logging.LogUtils
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
import java.util.UUID
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
            ticksUntilAuto = if (event.server is GameTestServer) -1 else START_DELAY_TICKS
        }
        NeoForge.EVENT_BUS.addListener<ServerTickEvent.Post> { tick() }
        NeoForge.EVENT_BUS.addListener<ServerStoppingEvent> {
            ticksUntilAuto = -1
            running?.cancel()
            running = null
        }
        NeoForge.EVENT_BUS.addListener<ServerStoppedEvent> {
            server = null
            last = null
        }
    }

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
        if (running?.finished == false || store == null) return null
        val cleanup = SaveCleanup(store, modules, ChunkReferenceProbe(s), settings, dryRun, force)
        running = cleanup
        last = cleanup
        ticksUntilAuto = -1
        return cleanup
    }

    private fun tick() {
        if (ticksUntilAuto > 0 && --ticksUntilAuto == 0) {
            ticksUntilAuto = -1
            if (start(dryRun = false, force = false) == null) LOGGER.debug("Skipping the automatic computer save cleanup")
        }
        val cleanup = running ?: return
        try {
            cleanup.tick()
        } catch (e: RuntimeException) {
            LOGGER.error("The computer save cleanup failed", e)
            cleanup.cancel()
        }
        if (cleanup.finished) running = null
    }
}

/**
 * `owner.bin` の位置のチャンクに、その ID のケースがあるかを答える（#564。[MachineReferenceProbe]）。メインスレッドで問われる。
 *
 * - ディメンションが今のサーバーにない（MOD を外しただけかもしれない）: [MachineReference.UNKNOWN]
 * - チャンクの `ChunkHolder` がある（読み込まれている・アンロード待ち。ディスクより、メモリが正しい）: [MachineReference.UNKNOWN]。
 *   読み込まれたケースは claim するので、ふつうは「このサーバーで見た ID」として先に外れる
 * - それ以外: `ChunkMap.read`（IOWorker。書き込み待ちのデータを返す。リージョンファイルを直接開かない）の `block_entities` に、
 *   その座標のケースのタグがあり、その ID を持つなら [MachineReference.PRESENT]、なければ（チャンクがディスクにないときも）
 *   [MachineReference.ABSENT]。読みが失敗したら [MachineReference.UNKNOWN]
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
        if (ServerMachines.findHolder(level, chunk.toLong()) != null) {
            answer.accept(MachineReference.UNKNOWN)
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
