package pro.ikili.kibicomputers.neoforge.machine

import com.mojang.logging.LogUtils
import cpw.mods.modlauncher.Launcher
import cpw.mods.modlauncher.TransformingClassLoader
import cpw.mods.modlauncher.api.IEnvironment
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
import net.minecraft.server.level.ChunkMap
import net.minecraft.server.level.FullChunkStatus
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.storage.ChunkStorage
import net.minecraft.world.level.chunk.storage.IOWorker
import net.minecraft.world.level.chunk.storage.RegionFile
import net.minecraft.world.level.chunk.storage.RegionFileStorage
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.spongepowered.asm.mixin.extensibility.IMixinInfo
import pro.ikili.kibicomputers.core.persist.FileModuleStore
import pro.ikili.kibicomputers.core.persist.MachineOwner
import pro.ikili.kibicomputers.core.persist.MachineReference
import pro.ikili.kibicomputers.core.persist.MachineReferenceProbe
import pro.ikili.kibicomputers.core.persist.MachineSaveStore
import pro.ikili.kibicomputers.core.persist.SaveCleanup
import pro.ikili.kibicomputers.core.persist.SaveCleanupSettings
import pro.ikili.kibicomputers.neoforge.computer.CaseBlockEntity
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks
import java.io.IOException
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
            commandOverride = null
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
     * コマンド（`/kibicomputers saves cleanup`）が始める掃除の置き場・モジュールの置き場の差し替え（テスト用。`null` なら今のサーバーのもの）。
     * コマンドの経路をサーバーの本物の置き場を消さずに試す
     */
    internal var commandOverride: Pair<MachineSaveStore, FileModuleStore?>? = null

    /** コマンドから掃除を始める（[start]。置き場は [commandOverride] があればそれ） */
    fun startFromCommand(dryRun: Boolean, force: Boolean): SaveCleanup? {
        val override = commandOverride
        return if (override != null) {
            start(dryRun, force, store = override.first, modules = override.second)
        } else {
            start(dryRun, force)
        }
    }

    /**
     * 掃除を始める。走っている・置き場がないなら `null`。[store]・[modules]・[untrustedReasons] はテストが差し替える（既定は今のサーバーのもの）。
     * [untrustedReasons] は、チャンクを読み書きする側が既定の作りでない理由の一覧を返す（空なら既定の作り。[ChunkReferenceProbe.untrustedRegionStorage]）。
     * [regionOf] は、チャンクのリージョンファイルの場所を返す（テストが、別の名前の保存先に移した環境を作るために差し替える）
     */
    fun start(
        dryRun: Boolean,
        force: Boolean,
        store: MachineSaveStore? = WorldMachineSaves.current,
        modules: FileModuleStore? = ServerMachines.moduleStore,
        settings: SaveCleanupSettings = SaveCleanupSettings.DEFAULT,
        untrustedReasons: (MinecraftServer) -> List<String> = { ChunkReferenceProbe.untrustedRegionStorage(it) },
        regionOf: (ServerLevel, ChunkPos) -> Path = { level, chunk -> ChunkReferenceProbe.regionFile(level, chunk) },
    ): SaveCleanup? {
        val s = server ?: return null
        // 止めた掃除が、入れた削除の結果をまだ受け取っている間も、次を始めない
        if (running?.let { !it.finished || it.settling } == true || store == null) return null
        // 理由の一覧は掃除1回につき1回だけ調べ、問いの判定とログの両方に同じ結果を使う
        val untrusted = untrustedReasons(s)
        val probe = ChunkReferenceProbe(s, regionNamesTrusted = untrusted.isEmpty(), regionOf = regionOf)
        if (untrusted.isNotEmpty()) {
            // 掃除1回につき1行（問いごとには出さない）
            LOGGER.info(
                "The chunk storage is modified, substituted or transformed ({}); the computer save cleanup keeps directories at positions without a region file",
                untrusted.joinToString(),
            )
        }
        val cleanup = SaveCleanup(store, modules, probe, settings, dryRun, force)
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
 * ModLauncher の変換の記録（`ITransformerActivity`）の1件を写したもの（[ChunkReferenceProbe.transformedRegionStorage]）。
 * [type] は記録の種類の名前（[PLUGIN]・[TRANSFORMER]・[REASON]）、[context] はその中身（PLUGIN なら先頭が launch plugin の名前、
 * TRANSFORMER なら先頭が変換のサービスの名前（FML の coremod は `fml`）で、続きが変換のラベル）
 */
internal data class TransformActivity(val type: String, val context: List<String>) {
    /** ログ・理由に載せる形（「種類 中身:…」） */
    fun describe(): String = "$type ${context.joinToString(":")}"

    companion object {
        const val PLUGIN: String = "PLUGIN"
        const val TRANSFORMER: String = "TRANSFORMER"
        const val REASON: String = "REASON"
    }
}

/** クラス名の変換の記録を返す口（[ChunkReferenceProbe.modLauncherAudit]。テストは差し替える）。記録が引けなければ `null` */
internal fun interface TransformAudit {
    fun activities(className: String): List<TransformActivity>?
}

/**
 * あるクラスを狙う Mixin の1つを写したもの（[ChunkReferenceProbe.mixinRegistry]。#969）。[name] は Mixin のクラス名、[accessor] はアクセサー・インボーカーだけの
 * Mixin（インターフェース。[ChunkReferenceProbe.isAccessorMixin]）か、[hasPlugin] は Mixin の config に plugin（`IMixinConfigPlugin`）があるか
 */
internal data class MixinRecord(val name: String, val accessor: Boolean, val hasPlugin: Boolean) {
    /** ログ・理由に載せる形 */
    fun describe(): String = name + if (accessor && hasPlugin) " (accessor in a config with a plugin)" else ""
}

/**
 * クラス名を狙う Mixin の一覧を返す口（[ChunkReferenceProbe.mixinRegistry]。テストは差し替える）。そのクラスの Mixin の記録がなければ `null`、
 * 読むのに失敗したら例外
 */
internal fun interface MixinRegistry {
    fun mixins(className: String): List<MixinRecord>?
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
 *   `RegionFileStorage` は、読むだけでもリージョンファイルがなければ空のものを作る。そこで、リージョンファイル（`ChunkMap` が実際に開く保存先の `r.X.Z.mca`。[regionFile]）がないと確かめられ、
 *   チャンクを読み書きする側が既定の作りで（[regionNamesTrusted]。リージョンファイルの名前で、チャンクがないと言える）、同じ保存先にバニラの名前のリージョンファイルがあれば、
 *   読まずに [MachineReference.ABSENT] とする（試しの実行でも、ワールドにファイルを増やさない）。
 *   確かめられない・既定の作りと確かめられない・保存先にバニラの名前のファイルがない（名前・形式を変える環境かもしれない）なら [MachineReference.UNKNOWN]（[regionAbsence]）。
 *   別の座標のリージョンファイルがあることは、対象のチャンクがないことの証拠にならない（形式を変える MOD への移行の後の混ざった状態では、対象のチャンクが別の形で生きている。#899）。
 *   リージョンファイルのない所のチャンクで書き込み待ちになりうるのは、
 *   このセッションで生成したチャンクだけで、そこにあるケースはこのセッションで置いた（ID を振った・claim した）もの、つまり「見た ID」なので、ここには問われない
 *   （消す直前にも見直す）。リージョンファイルがあれば、書き込み待ちは `ChunkMap.read` が返す
 *
 * チャンクの NBT は覚えない（1,000 件分を持つと大きいため。同じチャンクを何度か読むことはある。問いの数は [SaveCleanupSettings.maxProbesInFlight] まで）
 *
 * [regionNamesTrusted] は、チャンクを読み書きする側（`RegionFileStorage`・`RegionFile`・`IOWorker`・`ChunkStorage` と、その入れ子のクラス）に処理を書き換える Mixin が当たっておらず、
 * 実際に使われているインスタンスがバニラのクラスそのもので、coremod などの変換も当たっていないか（`ChunkMap` も含む。[untrustedRegionStorage]）。
 * 作るときに1回だけ決める（メインスレッドで作る。問いごと・IO のスレッドでは調べない）。テストは差し替える
 */
internal class ChunkReferenceProbe(
    private val server: MinecraftServer,
    val regionNamesTrusted: Boolean = untrustedRegionStorage(server).isEmpty(),
    /** チャンクのリージョンファイルの場所を返す（既定は `ChunkMap` が実際に開く保存先。テストが差し替える） */
    private val regionOf: (ServerLevel, ChunkPos) -> Path = { level, chunk -> regionFile(level, chunk) },
) : MachineReferenceProbe {
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
            regionOf(level, chunk)
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
        // リージョンファイルがなければ読まない（読みが空のリージョンファイルを作るため）。ないと確かめられたときだけ「ない」にする
        regionAbsence(region, vanillaFolders, regionNamesTrusted)?.let {
            answer.accept(it)
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

    /** バニラの名前（`r.X.Z.mca`）のリージョンファイルが1つでもあると分かった保存先（[regionAbsence]。どのスレッドからも使う） */
    private val vanillaFolders: MutableSet<Path> = ConcurrentHashMap.newKeySet()

    companion object {
        /** バニラのリージョンファイルの名前 */
        private val REGION_NAME = Regex("""r\.-?\d+\.-?\d+\.mca""")

        /**
         * リージョンファイル [region] がないときの答え（IO のスレッドで呼ぶ）。読んでよい（ファイルがある）なら `null`。
         *
         * - ファイルがあると確かめられた: `null`（`ChunkMap.read` で読む）
         * - ないと確かめられ（`Files.notExists`）、チャンクを読み書きする側が既定の作り（[trusted]）で、同じ保存先にバニラの名前のリージョンファイルが1つでもある: [MachineReference.ABSENT]
         * - それ以外（stat の I/O エラー・アクセス拒否で確かめられない、既定の作りと確かめられない、保存先にバニラの名前のファイルが1つもない）: [MachineReference.UNKNOWN]。
         *   既定の作りでないと、対象のチャンクがリージョンファイルの外（別の名前・形式）にあるかもしれない。別の座標のバニラの名前のファイルがあっても、
         *   対象のチャンクがないことの証拠にはならない（#899）。保存先にバニラの名前のファイルがないのは、このディメンションでまだチャンクを保存していないか、
         *   リージョンの名前・形式を変える環境で、どちらもファイル名からは判定できない。どれも消す側に倒さない（ADR 0564 の「判定できないものは残す」）
         *
         * [known] は、バニラの名前のファイルがあると分かった保存先の覚え（同じ保存先を何度も一覧しない）
         */
        fun regionAbsence(region: Path, known: MutableSet<Path>, trusted: Boolean): MachineReference? {
            if (Files.exists(region)) return null
            if (!Files.notExists(region)) return MachineReference.UNKNOWN
            if (!trusted) return MachineReference.UNKNOWN
            val folder = region.parent ?: return MachineReference.UNKNOWN
            if (folder !in known) {
                val vanilla = try {
                    Files.newDirectoryStream(folder, "r.*.mca").use { dir -> dir.any { REGION_NAME.matches(it.fileName.toString()) } }
                } catch (e: IOException) {
                    false
                } catch (e: DirectoryIteratorException) {
                    false
                }
                if (!vanilla) return MachineReference.UNKNOWN
                known.add(folder)
            }
            return MachineReference.ABSENT
        }

        /** チャンクを読み書きする経路のうち、リージョンファイルの名前と中身を決めるクラス（[modifiedRegionStorage]） */
        private val REGION_STORAGE_CLASSES: List<Class<*>> =
            listOf(RegionFileStorage::class.java, RegionFile::class.java, IOWorker::class.java, ChunkStorage::class.java)

        /**
         * [REGION_STORAGE_CLASSES] の入れ子のクラスの名前（#969。1.21.1 の実物にあるもの。匿名のクラスはない。ラムダの本体は外側のクラスの synthetic なメソッドなので、外側のクラスで見えている）。
         * `RegionFile$ChunkBuffer` は `close` で書き込み先を決める。`IOWorker$PendingStore`・`IOWorker$Priority`・`RegionFile$CommitOp` はデータ・enum・関数のインターフェースに近いが、
         * 数件で掃除1回につき定数のコストなので同じに見る。一覧は版で変わりうるので、GameTest が実物の `declaredClasses` と比べる
         */
        val NESTED_REGION_STORAGE_CLASSES: List<String> = listOf(
            "net.minecraft.world.level.chunk.storage.IOWorker\$PendingStore",
            "net.minecraft.world.level.chunk.storage.IOWorker\$Priority",
            "net.minecraft.world.level.chunk.storage.RegionFile\$ChunkBuffer",
            "net.minecraft.world.level.chunk.storage.RegionFile\$CommitOp",
        )

        /**
         * 入れ子のクラス（[names]）を読み込む（初期化はしない。まだ読み込まれていなければ、ここで読み込まれて変換が走り、記録が残る）。
         * 読み込めたクラスと、読み込めなかったものの理由（「クラス名: not found」。[untrustedRegionStorage] は数える）を返す
         */
        fun nestedRegionStorage(
            names: List<String> = NESTED_REGION_STORAGE_CLASSES,
            load: (String) -> Class<*> = { Class.forName(it, false, RegionFile::class.java.classLoader) },
        ): Pair<List<Class<*>>, List<String>> {
            val loaded = ArrayList<Class<*>>()
            val missing = ArrayList<String>()
            for (name in names) {
                try {
                    loaded += load(name)
                } catch (e: ReflectiveOperationException) {
                    missing += "$name: not found"
                } catch (e: RuntimeException) {
                    missing += "$name: not found"
                } catch (e: LinkageError) {
                    missing += "$name: not found"
                }
            }
            return loaded to missing
        }

        /** リージョンファイルの名前と中身を決めるクラス（[REGION_STORAGE_CLASSES] とその入れ子のうち、読み込めたもの） */
        fun regionStorageClasses(): List<Class<*>> = REGION_STORAGE_CLASSES + nestedRegionStorage().first

        /** Mixin が合わせたメソッドに付ける印（実行時に見える注釈） */
        private const val MIXIN_MERGED = "org.spongepowered.asm.mixin.transformer.meta.MixinMerged"

        /**
         * サーバー [server] のチャンクを読み書きする側が、既定の作りと確かめられない理由（空なら既定の作り）。
         * 既定の作りなら「リージョンファイル `r.X.Z.mca` がない ⇒ そのチャンクはない」が成り立つ（外部のチャンクファイル `.mcc` はリージョンのヘッダーからしか指されない）。
         *
         * 3つを見る。対象のクラスは、`RegionFileStorage`・`RegionFile`・`IOWorker`・`ChunkStorage` と、その入れ子のクラス（[NESTED_REGION_STORAGE_CLASSES]。#969。読み込めなければ数える）。
         * - クラスの書き換え（[modifiedRegionStorage]）: 対象のクラスに、処理を書き換える Mixin が当たっていないか。
         *   `ChunkMap` は、`ChunkStorage` のメソッドを上書きする Mixin が当たっていないか
         * - 差し替え（[substitutedRegionStorage]）: 全ディメンションで、実際に使われている `ChunkMap`・`IOWorker`・`RegionFileStorage` のインスタンスがバニラのクラスそのものか。
         *   `IOWorker` は `final` でなく、コンストラクターは `protected`、`loadAsync`・`store` は上書きできるので、バイトコードを書き換えなくてもサブクラスが作れる。
         *   それを `ChunkStorage.worker`（`final`）に入れるのは、アクセサーの Mixin・リフレクション・AT で足りる（アクセサーの Mixin はクラスの書き換えとしては数えないので、こちらで見る）
         * - 変換の記録（[transformedRegionStorage]。#959）: 対象のクラスと `ChunkMap` に、coremod などの変換（ModLauncher の記録の TRANSFORMER、許可した名前でない PLUGIN）が当たっていないか。
         *   coremod はメソッドの本体を書き換えても、Mixin の印にも実行時のクラスにも現れないので、ModLauncher の記録で見る。[audit] はテストが差し替える。
         *   対象のクラス（`ChunkMap` を除く）に PLUGIN `mixin` があれば、Mixin の記録（[mixins]。#969）で、当たった Mixin がアクセサーだけ（plugin のない config のもの）と説明できるかも見る
         *   （合わせたメンバーのない Mixin は印が付かないため）
         *
         * 調べるのに失敗したら、確かめられないとして理由に数える（残す側）。掃除1回につき1回、メインスレッドで呼ぶ（問いごと・IO のスレッドでは調べない）
         */
        fun untrustedRegionStorage(
            server: MinecraftServer,
            audit: TransformAudit? = modLauncherAudit(),
            mixins: MixinRegistry? = mixinRegistry(),
            nestedStorage: () -> Pair<List<Class<*>>, List<String>> = { nestedRegionStorage() },
        ): List<String> {
            val (nested, missing) = nestedStorage()
            val storage = REGION_STORAGE_CLASSES + nested
            return missing + modifiedRegionStorage(storage) + substitutedRegionStorage(server) +
                transformedRegionStorage(audit, storage + ChunkMap::class.java, mixins = mixins, mixinChecked = storage.toSet())
        }

        /**
         * 変換の記録の PLUGIN のうち、数えないもの（launch plugin の名前。前方一致でなく、名前がそのまま一致するものだけ）。
         * - `accesstransformer`: アクセス修飾子だけを変える（製品の環境では、このプロジェクト自身の AT もこれで当たる）
         * - `mixin`: Mixin は合わせたメンバーの印（[modifiedRegionStorage]）と、Mixin の記録（[unexplainedMixins]。`ChunkMap` 以外）で細かく見る。
         *   `ChunkMap` の性能のための Mixin を数えない方針は、`ChunkMap` では Mixin の記録を見ないことで保つ
         * - `runtimedistcleaner`: 反対側（クライアント）だけのメンバーを消すだけで、処理を変えない
         */
        private val HARMLESS_PLUGINS: Set<String> = setOf("accesstransformer", "mixin", "runtimedistcleaner")

        /** Mixin の launch plugin の名前（変換の記録の PLUGIN の先頭） */
        private const val MIXIN_PLUGIN = "mixin"

        /** Mixin がクラスの情報を覚える表（`ClassInfo`。公開の API でないので、反射で読む） */
        private const val CLASS_INFO = "org.spongepowered.asm.mixin.transformer.ClassInfo"

        /**
         * `ClassInfo.fromCache(String)`（public static）と、private のフィールド `mixins`（そのクラスを狙う MixinInfo の `HashSet`。それを返す package-private の `getMixins()` は、
         * 配られている jar では削られていて引けない）。引けなければ例外（毎回引き直す）
         */
        private val classInfoAccess: Pair<Method, Field> by lazy {
            val type = Class.forName(CLASS_INFO, false, RegionFile::class.java.classLoader)
            val fromCache = type.getMethod("fromCache", String::class.java)
            val mixins = type.getDeclaredField("mixins").apply { isAccessible = true }
            fromCache to mixins
        }

        /**
         * Mixin の記録を読む口（#969）。クラス名の `ClassInfo` を Mixin のキャッシュから引き（`fromCache`。キャッシュにない名前のクラスを読み込む `forName` は使わない）、
         * そのクラスを狙う Mixin（フィールド `mixins`。Mixin の config を準備するときに、対象のクラスごとに入る）を写して返す。キャッシュにない・`null` なら `null`。
         * Mixin の版が変わって反射が通らない、読むのに失敗した（集合はほかのスレッドのクラスの読み込みと同時に書き換わりうるので、すぐに写し、写すのに失敗したら）は例外
         * （[unexplainedMixins] は数える）。Mixin のロックは取らない（ほかのスレッドのクラスの読み込みを待たせない）。
         * Mixin がアクセサーかは、クラスファイルのアクセスフラグで見る（[isAccessorMixin]。`getClassNode` などの重い読み直しはしない）。
         * 返す口を作るだけでは反射しない（呼ばれたときに引く。PLUGIN `mixin` のないバニラの環境では呼ばれない）
         */
        fun mixinRegistry(): MixinRegistry = MixinRegistry { name ->
            val (fromCache, mixinsField) = classInfoAccess
            val info = fromCache.invoke(null, name) ?: return@MixinRegistry null
            val targeting = mixinsField.get(info) as? Collection<*> ?: return@MixinRegistry null
            val loader = RegionFile::class.java.classLoader
            ArrayList(targeting).map { mixin ->
                mixin as IMixinInfo
                val className = mixin.className
                MixinRecord(className, isAccessorMixin(loader, className), mixin.config?.plugin != null)
            }
        }

        /**
         * PLUGIN `mixin` の記録があるクラス [name] の Mixin を、Mixin の記録 [mixins] で説明できなければ、その理由（説明できれば `null`）。
         *
         * 説明できるのは、そのクラスを狙う Mixin が1つ以上あり、すべてアクセサー・インボーカーだけの Mixin で、その config に plugin がないときだけ。
         * plugin の `preApply`・`postApply` は、その config の Mixin が当たるたびに対象の ClassNode を受け取って書き換えられるので、アクセサーでも plugin のある config のものは数える。
         * 処理を書き換える Mixin は、合わせたメンバーの有無を問わず数える（空の Mixin・`<clinit>` だけの Mixin が見える）。性能のための Mixin とは分けられない
         * （Mixin の名前・config・plugin・MOD からは、形式を変えるかが分からない）。記録が引けない・ない・空（PLUGIN `mixin` があるのに説明できない）・読めないも数える
         */
        fun unexplainedMixins(name: String, mixins: MixinRegistry?): String? {
            if (mixins == null) return "the mixin records are unavailable"
            val records = try {
                mixins.mixins(name)
            } catch (e: ReflectiveOperationException) {
                return "the mixin records are unreadable"
            } catch (e: RuntimeException) {
                return "the mixin records are unreadable"
            } catch (e: LinkageError) {
                return "the mixin records are unreadable"
            }
            if (records.isNullOrEmpty()) return "no mixin record explains the mixin transformation"
            val counted = records.filter { !it.accessor || it.hasPlugin }
            if (counted.isEmpty()) return null
            return "mixin ${counted.joinToString { it.describe() }}".take(MAX_ACTIVITY_LENGTH)
        }

        /** 理由に載せる記録の文字列の長さの上限（ログの1行を長くしすぎない） */
        private const val MAX_ACTIVITY_LENGTH = 200

        /**
         * ModLauncher の変換の記録（`ITransformerAuditTrail`）を読む口。記録が引けない（ModLauncher で起動していない、`Launcher.INSTANCE` がない、
         * ModLauncher のクラスが見えない）なら `null`（[transformedRegionStorage] は、確かめられないとして数える）。
         * 返す関数は、クラス名の記録を写して返す（ModLauncher の記録の一覧は同期されない `ArrayList` の生のビューなので、すぐに写す。写すのに失敗したら例外）
         */
        fun modLauncherAudit(): TransformAudit? = try {
            val trail = Launcher.INSTANCE?.environment()?.getProperty(IEnvironment.Keys.AUDITTRAIL.get())?.orElse(null)
            trail?.let { t ->
                TransformAudit { name ->
                    ArrayList(t.getActivityFor(name)).map { activity ->
                        TransformActivity(activity.type.name, activity.context.toList())
                    }
                }
            }
        } catch (e: RuntimeException) {
            null
        } catch (e: LinkageError) {
            null
        }

        /** 変換の記録を見る既定のクラス（[transformedRegionStorage]）。[regionStorageClasses] に `ChunkMap` を足したもの */
        fun auditedClasses(): List<Class<*>> = regionStorageClasses() + ChunkMap::class.java

        /**
         * チャンクを読み書きする経路のクラス（[classes]。既定は [auditedClasses]）のうち、coremod などの変換が当たったもの（「クラス名: 記録」。確かめられなければ「クラス名: 理由」）。
         *
         * ModLauncher の変換の記録で見る。FML の coremod（JS・Java の `ICoreMod`）とほかの `ITransformationService` の変換は TRANSFORMER として、
         * 書き換えた launch plugin は PLUGIN として残る。TRANSFORMER は1つでもあれば数え、PLUGIN は [HARMLESS_PLUGINS] にない名前なら数える（REASON は数えない）。
         * [mixinChecked] のクラス（既定は `ChunkMap` 以外の [regionStorageClasses]）に PLUGIN `mixin` があれば、Mixin の記録 [mixins] で説明できるかを見て、
         * できなければ数える（[unexplainedMixins]。#969）。PLUGIN `mixin` のないクラスでは Mixin の記録を引かない（Mixin の内部の読みが版上げで壊れても、
         * Mixin の当たっていない環境の回収は止まらない）。Mixin の当たったクラスでは、PLUGIN `mixin` は相（`A`・`B`）の印として必ず残る。
         * Mixin 自身が足す `mixin:APP:…` などの記録は、Mixin の監査の口がスレッドの間で同期されないので抜けうる。判定には使わない。
         * 記録が空であることは、変換されていないことの証拠にならない（変換も plugin もないクラスは、記録を残さずに読み込まれる）。そこで、
         * クラスが ModLauncher の `TransformingClassLoader` で読み込まれていること（[transforming]。そうでなければ、記録の外で読み込まれた）も確かめる。
         * 記録が引けない（[audit] が `null`）、読むのに失敗した、クラスローダーが違う、はどれも数える（残す側）。
         *
         * 見えないもの: JVM エージェント（`java.lang.instrument`）の書き換えと、記録の外での書き換え（[modifiedRegionStorage]）。
         * 掃除1回につき1回、メインスレッドで呼ぶ（クラスの数だけ記録を写す。台の数によらない）
         */
        fun transformedRegionStorage(
            audit: TransformAudit?,
            classes: List<Class<*>> = auditedClasses(),
            transforming: (Class<*>) -> Boolean = { it.classLoader is TransformingClassLoader },
            mixins: MixinRegistry? = mixinRegistry(),
            mixinChecked: Set<Class<*>> = regionStorageClasses().toSet(),
        ): List<String> = classes.mapNotNull { type ->
            val name = type.name
            if (audit == null) return@mapNotNull "$name: the transformer audit trail is unavailable"
            try {
                if (!transforming(type)) return@mapNotNull "$name: not loaded by the transforming class loader"
                val activities = audit.activities(name) ?: return@mapNotNull "$name: the transformer audit trail is unavailable"
                activities.firstOrNull { transformsCode(it) }?.let { return@mapNotNull "$name: ${it.describe().take(MAX_ACTIVITY_LENGTH)}" }
                val mixed = activities.any { it.type == TransformActivity.PLUGIN && it.context.firstOrNull() == MIXIN_PLUGIN }
                if (mixed && type in mixinChecked) unexplainedMixins(name, mixins)?.let { "$name: $it" } else null
            } catch (e: RuntimeException) {
                "$name: the transformer audit trail is unreadable"
            } catch (e: LinkageError) {
                "$name: the transformer audit trail is unreadable"
            }
        }

        /** 変換の記録 [activity] が、処理を書き換えうるものか（[transformedRegionStorage]）。知らない種類も数える */
        private fun transformsCode(activity: TransformActivity): Boolean = when (activity.type) {
            TransformActivity.REASON -> false
            TransformActivity.PLUGIN -> activity.context.firstOrNull() !in HARMLESS_PLUGINS
            else -> true
        }

        /**
         * チャンクを読み書きする経路のクラス（[classes]。既定は [regionStorageClasses]）のうち、処理を書き換える Mixin が当たったもの（の名前）。
         *
         * Mixin は、合わせたメンバーの印（`@MixinMerged`。[isMixinTarget]）で見る。調べるのに失敗したら、当たったものとして数える（残す側）。
         * アクセサー・インボーカーだけの Mixin（NeoForge 自身が `BlockEntityType` などに当てているもの。[isAccessorMixin]）は、処理を書き換えないので数えない
         * （それで入れ替えたインスタンスは [substitutedRegionStorage] が見る）。
         * 処理を書き換える Mixin は、形式を変えないもの（性能のためのもの）も区別できず数える。その環境では、リージョンファイルのない位置の残骸は回収されない（毎回「判定できない」）。
         *
         * 印の付かない Mixin（合わせたメソッド・フィールドを1つも持たない Mixin。`IMixinConfigPlugin` の `preApply`・`postApply` で ClassNode を直接書き換えるための空の Mixin、
         * `<clinit>` だけの Mixin）は、ここでは見えず、変換の記録の PLUGIN `mixin` と Mixin の記録（[transformedRegionStorage]・[unexplainedMixins]。#969）で見る。
         * 見えないもの: JVM エージェントの書き換え（coremod は [transformedRegionStorage] が変換の記録で見る）。当てたクラスの記録（`Mixins.getMixinsForClass`）は、
         * 適用の記録が Mixin 自身のクラスの側にしか付かず、対象のクラスでは作りの上で常に空なので使わない（狙う Mixin の記録は `ClassInfo` の別の集合にある。[mixinRegistry]）。
         * `ChunkMap` は多くの MOD パックで性能のための Mixin が当たるので、Mixin が当たったこと自体は数えない（数えると、ほとんどの環境で
         * リージョンのない位置の残骸を回収できなくなる。保存先は `ChunkMap` が持つ `ChunkStorage` の `IOWorker` の `RegionFileStorage` から引いている）。
         * ただし、`ChunkStorage` のメソッド（`read`・`write` など）を `ChunkMap` で上書きする Mixin（Mixin のクラスが `ChunkStorage` を継承して override を足す形）は、
         * チャンクの読み書きをすべて横取りできるので数える（[overridesWithMixin]）
         */
        fun modifiedRegionStorage(classes: List<Class<*>> = regionStorageClasses()): List<String> =
            classes.filter { isMixinTarget(it) }.map { it.name } +
                listOfNotNull(ChunkMap::class.java.name.takeIf { overridesWithMixin(ChunkMap::class.java, ChunkStorage::class.java) })

        /**
         * クラス [type] に、親のクラス [base] のメソッド（`static`・`private` でないもの。名前と引数の型が同じ）を上書きする、Mixin が合わせたメソッドがあるか
         * （調べるのに失敗したら `true`）。印（[marker]。既定は `@MixinMerged`）の付いたメソッドだけを見るので、バニラ自身の上書き（`ChunkMap.close` など）は数えない。
         * アクセサー・インボーカーだけの Mixin の印は数えない（[isAccessorMixin]。[isMixinTarget] と同じ）
         */
        fun overridesWithMixin(type: Class<*>, base: Class<*>, marker: String = MIXIN_MERGED): Boolean = try {
            val loader = type.classLoader
            val overridable = base.declaredMethods
                .filter { !Modifier.isStatic(it.modifiers) && !Modifier.isPrivate(it.modifiers) }
                .map { it.name to it.parameterTypes.toList() }
                .toSet()
            type.declaredMethods.any { method ->
                !Modifier.isStatic(method.modifiers) &&
                    (method.name to method.parameterTypes.toList()) in overridable &&
                    method.declaredAnnotations.any { it.annotationClass.java.name == marker && !isAccessorMixin(loader, mergedMixin(it)) }
            }
        } catch (e: RuntimeException) {
            true
        } catch (e: LinkageError) {
            true
        }

        /**
         * 全ディメンションのうち、チャンクを読み書きするインスタンス（`ChunkMap`・その `IOWorker`・その `RegionFileStorage`）が、バニラのクラスそのものでないもの
         * （「ディメンション: クラス名」。引けなければ「ディメンション: unreadable」）。サブクラスで読み書きを差し替える環境を見る（[untrustedRegionStorage]）
         */
        fun substitutedRegionStorage(server: MinecraftServer): List<String> = server.allLevels.flatMap { level ->
            val name = level.dimension().location().toString()
            try {
                val chunkMap = level.chunkSource.chunkMap
                val worker = chunkMap.worker
                substitutedInstances(
                    listOf(ChunkMap::class.java to chunkMap, IOWorker::class.java to worker, RegionFileStorage::class.java to worker.storage),
                ).map { "$name: $it" }
            } catch (e: RuntimeException) {
                listOf("$name: unreadable")
            }
        }

        /** 組（期待するクラス, インスタンス）のうち、インスタンスがそのクラスそのものでないもの（の実際のクラス名。インスタンスがなければ `null`） */
        fun substitutedInstances(instances: List<Pair<Class<*>, Any?>>): List<String> =
            instances.filter { (expected, instance) -> instance == null || instance.javaClass != expected }
                .map { (_, instance) -> instance?.javaClass?.name ?: "null" }

        /**
         * クラス [type] に、処理を書き換える Mixin が当たっているか（調べるのに失敗したら `true`）。合わせたメンバーの印（[marker]。既定は `@MixinMerged`。[hasMergedMembers]）で見る。
         * 印の `mixin` がアクセサー・インボーカーだけの Mixin（インターフェースの Mixin。フィールド・メソッドを開くだけで、処理を書き換えない）なら数えない（[isAccessorMixin]）。
         * `mixin` が読めない印、クラスファイルが読めない Mixin は数える。印の付かない Mixin は見えない（[modifiedRegionStorage]）
         */
        fun isMixinTarget(type: Class<*>, marker: String = MIXIN_MERGED): Boolean = try {
            val loader = type.classLoader
            hasMergedMembers(type, marker, ignoredMixin = { isAccessorMixin(loader, it) })
        } catch (e: RuntimeException) {
            true
        } catch (e: LinkageError) {
            true
        }

        /**
         * Mixin のクラス [mixin] が、アクセサー・インボーカーだけのもの（インターフェース）か。Mixin のインターフェースには、ふつうアクセサーとインボーカーしか書けない。
         * ただし、クラスを狙うインターフェースの Mixin でも、synthetic なメソッドや `<clinit>` は印なしで対象に足されうる（sponge-mixin 0.16.4）。この判定はアクセスフラグだけを見るので、それは見えない（ADR 0564 の限界）。
         * 処理を書き換えるクラスの Mixin は Mixin が読み込みを弾く（`isLoadable()` が偽）が、アクセサーの Mixin は対象のクラスが実装するインターフェースとして読み込まれる。
         * どちらも読み込まずに同じ方法で扱えるよう、クラスファイルのアクセスフラグを読む。名前がない・読めないなら `false`（数える）
         */
        fun isAccessorMixin(loader: ClassLoader?, mixin: String?): Boolean {
            if (mixin == null) return false
            return try {
                val bytes = (loader ?: ClassLoader.getSystemClassLoader()).getResourceAsStream(mixin.replace('.', '/') + ".class")?.use { it.readAllBytes() } ?: return false
                ClassReader(bytes).access and Opcodes.ACC_INTERFACE != 0
            } catch (e: IOException) {
                false
            } catch (e: RuntimeException) {
                false
            }
        }

        /**
         * クラス [type] に、Mixin が合わせたメソッド・コンストラクター・フィールド（印 [marker]。既定は `@MixinMerged`）があるか。
         * 印の `mixin`（合わせた Mixin のクラス名）が [ignoredMixin] に当たるものは数えない。`mixin` が読めない印は数える（残す側）
         */
        fun hasMergedMembers(type: Class<*>, marker: String = MIXIN_MERGED, ignoredMixin: (String?) -> Boolean = { false }): Boolean {
            val members = type.declaredMethods.asSequence() + type.declaredConstructors.asSequence() + type.declaredFields.asSequence()
            return members.any { member ->
                member.declaredAnnotations.any { it.annotationClass.java.name == marker && !ignoredMixin(mergedMixin(it)) }
            }
        }

        /** 印 [mark]（`@MixinMerged`）の `mixin`。読めなければ `null`（[hasMergedMembers] は数える） */
        private fun mergedMixin(mark: Annotation): String? = try {
            mark.annotationClass.java.getMethod("mixin").invoke(mark) as? String
        } catch (e: ReflectiveOperationException) {
            null
        }

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
