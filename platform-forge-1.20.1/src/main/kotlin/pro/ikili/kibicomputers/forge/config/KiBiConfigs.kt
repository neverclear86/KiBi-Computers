package pro.ikili.kibicomputers.forge.config

import com.mojang.logging.LogUtils
import net.minecraftforge.eventbus.api.IEventBus
import net.minecraftforge.fml.ModLoadingContext
import net.minecraftforge.fml.config.ModConfig
import net.minecraftforge.fml.event.config.ModConfigEvent
import net.minecraftforge.common.ForgeConfigSpec
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.server.ServerAboutToStartEvent
import pro.ikili.kibicomputers.core.config.BooleanEntry
import pro.ikili.kibicomputers.core.config.ConfigApply
import pro.ikili.kibicomputers.core.config.ConfigEntry
import pro.ikili.kibicomputers.core.config.ConfigSide
import pro.ikili.kibicomputers.core.config.ConfigSource
import pro.ikili.kibicomputers.core.config.ConfigState
import pro.ikili.kibicomputers.core.config.ConfigValues
import pro.ikili.kibicomputers.core.config.DoubleEntry
import pro.ikili.kibicomputers.core.config.IntEntry
import pro.ikili.kibicomputers.core.config.KiBiConfig
import pro.ikili.kibicomputers.core.config.LongEntry
import java.util.EnumMap
import java.util.IdentityHashMap

/**
 * core のコンフィグの定義（[KiBiConfig.ENTRIES]）を Forge の [ForgeConfigSpec] に写して登録し、読み込み・読み直しのたびに
 * 値の写し（[ConfigValues]）を作って [state] に入れる（#202、ADR 0202）。項目ごとのコードは書かない。
 *
 * 読み直しのイベントは、ファイルの書き換え（ファイルを見張るスレッド）と、専用サーバーにつないだクライアントが同期された
 * サーバーの設定を受け取ったとき（ファイルを持たない設定。案内のログは出さない）に来る。どちらもメインスレッドとは限らない。ここでは写しを作って参照を差し替えるだけで、マシンの状態には触らない。
 * 使う側は、メインスレッドの tick で参照の変化を見て当てる（1.20.1 の使う側の移植は #224）。
 * 1.21.1（`platform-neoforge-1.21.1` の同名のクラス）と同じファイル名・同じキーの木にする。
 */
object KiBiConfigs {
    private val LOGGER = LogUtils.getLogger()

    private class Registered(val spec: ForgeConfigSpec, val values: Map<ConfigEntry<*>, ForgeConfigSpec.ConfigValue<*>>)

    private val registered = EnumMap<ConfigSide, Registered>(ConfigSide::class.java)

    private val states = EnumMap<ConfigSide, ConfigState>(ConfigSide::class.java).apply {
        ConfigSide.entries.forEach { put(it, ConfigState()) }
    }

    /** [side] の今の値の入れ物 */
    fun state(side: ConfigSide): ConfigState = states.getValue(side)

    /** サーバーの設定の今の値（読み込む前は既定） */
    val server: ConfigValues get() = state(ConfigSide.SERVER).values

    private val loaded = java.util.concurrent.ConcurrentHashMap.newKeySet<ConfigSide>()

    /** [side] の spec が登録されているか（テスト用） */
    internal fun isRegistered(side: ConfigSide): Boolean = registered.containsKey(side)

    /** [side] のファイルを読み込んだことがあるか（テスト用） */
    internal fun isLoaded(side: ConfigSide): Boolean = side in loaded

    private val loadedByEvent = java.util.concurrent.ConcurrentHashMap.newKeySet<ConfigSide>()
    private val reloadedByEvent = java.util.concurrent.ConcurrentHashMap.newKeySet<ConfigSide>()

    /**
     * [side] の読み込みの `ModConfigEvent.Loading` を、MOD のバスの購読で受けたことがあるか（テスト用）。
     * [isLoaded] は、`ServerAboutToStartEvent` の予備の購読でも立つので、バスを取れなかったときは区別できない
     */
    internal fun isLoadedByEvent(side: ConfigSide): Boolean = side in loadedByEvent

    /** [side] の読み直しの `ModConfigEvent.Reloading` を、MOD のバスの購読で受けたことがあるか（テスト用） */
    internal fun isReloadedByEvent(side: ConfigSide): Boolean = side in reloadedByEvent

    /** 項目 [entry] の spec の値（テスト用。値を差し替えて、読み直しのイベントを流すのに使う） */
    @Suppress("UNCHECKED_CAST")
    internal fun specValue(entry: ConfigEntry<*>): ForgeConfigSpec.ConfigValue<Any>? =
        registered[entry.side]?.values?.get(entry) as ForgeConfigSpec.ConfigValue<Any>?

    /** 項目のある分類の spec を作って登録する。MOD の初期化で1回だけ呼ぶ */
    fun register(context: ModLoadingContext) {
        for (side in ConfigSide.entries) {
            val entries = KiBiConfig.entries(side)
            if (entries.isEmpty()) continue
            val built = build(entries)
            registered[side] = built
            context.registerConfig(typeOf(side), built.spec, side.fileName)
        }
        val modBus = modBus(context)
        if (modBus != null) {
            modBus.addListener(::onLoading)
            modBus.addListener(::onReloading)
        } else {
            LOGGER.warn("Could not find the mod event bus; config reloads will apply only when the server starts")
        }
        // SERVER のファイルは ServerAboutToStartEvent の前に読まれる。イベントを受け損ねても、ここで写しを作る
        MinecraftForge.EVENT_BUS.addListener { _: ServerAboutToStartEvent ->
            registered[ConfigSide.SERVER]?.let { refresh(ConfigSide.SERVER, it, reload = false) }
        }
    }

    /**
     * KFF 4 の MOD のイベントバス。KFF の `MOD_BUS` は jarJar の中の kfflib にあってコンパイルのクラスパスに見えないので、
     * 同じもの（`KotlinModLoadingContext.getKEventBus`）をリフレクションで取る
     */
    private fun modBus(context: ModLoadingContext): IEventBus? = runCatching {
        val extension = context.extension<Any>()
        extension.javaClass.getMethod("getKEventBus").invoke(extension) as IEventBus
    }.getOrNull()

    private fun typeOf(side: ConfigSide): ModConfig.Type = when (side) {
        ConfigSide.SERVER -> ModConfig.Type.SERVER
        ConfigSide.CLIENT -> ModConfig.Type.CLIENT
        ConfigSide.COMMON -> ModConfig.Type.COMMON
    }

    private fun build(entries: List<ConfigEntry<*>>): Registered {
        val builder = ForgeConfigSpec.Builder()
        val values = IdentityHashMap<ConfigEntry<*>, ForgeConfigSpec.ConfigValue<*>>()
        for ((section, inSection) in entries.groupBy { it.section }) {
            KiBiConfig.SECTION_COMMENTS[section]?.let { builder.comment(*it.toTypedArray()) }
            builder.translation(KiBiConfig.sectionTranslationKey(section)).push(section)
            for (entry in inSection) {
                builder.comment(entry.commentEn, entry.commentJa).translation(entry.translationKey)
                if (entry.apply == ConfigApply.WORLD_RESTART) builder.worldRestart()
                values[entry] = when (entry) {
                    is IntEntry -> builder.defineInRange(entry.name, entry.default, entry.min, entry.max)
                    is LongEntry -> builder.defineInRange(entry.name, entry.default, entry.min, entry.max)
                    is DoubleEntry -> builder.defineInRange(entry.name, entry.default, entry.min, entry.max)
                    is BooleanEntry -> builder.define(entry.name, entry.default)
                }
            }
            builder.pop()
        }
        return Registered(builder.build(), values)
    }

    private fun onLoading(event: ModConfigEvent.Loading) = refresh(event.config, reload = false)

    private fun onReloading(event: ModConfigEvent.Reloading) = refresh(event.config, reload = true)

    private fun refresh(config: ModConfig, reload: Boolean) {
        val (side, reg) = registered.entries.firstOrNull { it.value.spec === config.getSpec() }?.toPair() ?: return
        (if (reload) reloadedByEvent else loadedByEvent) += side
        refresh(side, reg, reload, synced = reload && !isFileBacked(config))
    }

    /**
     * [config] がファイルから読んだものか。専用サーバーから同期された設定は、ファイルを持たない `CommentedConfig` になり、
     * `getFullPath` が例外になる（Forge 1.20.1 の `ModConfig.acceptSyncedConfig`）
     */
    private fun isFileBacked(config: ModConfig): Boolean = runCatching { config.fullPath }.isSuccess

    private fun refresh(side: ConfigSide, reg: Registered, reload: Boolean, synced: Boolean = false) {
        // 1.20.1 の get() は読み直しでキャッシュを捨てる（worldRestart は印だけ）。読み込む前なら既定にする
        val source = ConfigSource { entry -> runCatching { reg.values[entry]?.get() }.getOrNull() }
        val next = ConfigValues.resolve(source, KiBiConfig.entries(side))
        for (entry in next.rejected) LOGGER.warn("Ignoring an invalid value of {} in {}; using the default", entry.path, side.fileName)
        val changed = state(side).update(next)
        loaded += side
        if (!reload) return
        // 同期された設定を受け取ったクライアントでは何も変えておらず、次の起動で効くものもないので、案内を出さない
        if (synced) {
            LOGGER.debug("Received {} from the server ({} values differ from the previous copy)", side.fileName, changed.size)
            return
        }
        changed.filter { it.apply == ConfigApply.WORLD_RESTART }.forEach {
            LOGGER.info("{} in {} changed; it takes effect the next time the server (world) starts", it.path, side.fileName)
        }
        if (changed.isNotEmpty()) LOGGER.info("Reloaded {} ({} values changed)", side.fileName, changed.size)
    }
}
