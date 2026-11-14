package pro.ikili.kibicomputers.neoforge.config

import com.mojang.logging.LogUtils
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.config.ModConfig
import net.neoforged.fml.event.config.ModConfigEvent
import net.neoforged.neoforge.common.ModConfigSpec
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
 * core のコンフィグの定義（[KiBiConfig.ENTRIES]）を NeoForge の [ModConfigSpec] に写して登録し、読み込み・読み直しのたびに
 * 値の写し（[ConfigValues]）を作って [state] に入れる（#202、ADR 0202）。項目ごとのコードは書かない。
 *
 * 読み直し（[ModConfigEvent.Reloading]）は3つの経路で来る。どれもメインスレッドとは限らない:
 * - ファイルの書き換え（ファイルを見張るスレッド）
 * - 設定画面（NeoForge の `ConfigurationScreen`）の保存（クライアントのスレッド）
 * - 専用サーバーにつないだクライアントが、同期されたサーバーの設定を受け取ったとき（FML の `ConfigTracker.acceptSyncedConfig`。
 *   ファイルを持たない設定になる）。このときはクライアントの写しを差し替えるだけで、「次の起動で効く」などの案内は出さない
 *   （クライアントでは何も変えておらず、このクライアントがサーバーを開くときは、ファイルを読み直して `Loading` が来る）
 *
 * ここでは写しを作って参照を差し替えるだけで、マシンの状態には触らない。
 * 使う側（[pro.ikili.kibicomputers.neoforge.machine.ServerMachines] など）は、メインスレッドの tick で参照の変化を見て当てる。
 */
object KiBiConfigs {
    private val LOGGER = LogUtils.getLogger()

    private class Registered(val spec: ModConfigSpec, val values: Map<ConfigEntry<*>, ModConfigSpec.ConfigValue<*>>)

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

    /** 項目 [entry] の spec の値（テスト用。値を差し替えて、読み直しのイベントを流すのに使う） */
    @Suppress("UNCHECKED_CAST")
    internal fun specValue(entry: ConfigEntry<*>): ModConfigSpec.ConfigValue<Any>? =
        registered[entry.side]?.values?.get(entry) as ModConfigSpec.ConfigValue<Any>?

    /** 読み込み・読み直しの結果（テスト用） */
    class Report(
        val side: ConfigSide,
        val reload: Boolean,
        val changed: List<ConfigEntry<*>>,
        val rejected: List<ConfigEntry<*>>,
        /** サーバーから同期された設定（ファイルを持たない）を受け取った */
        val synced: Boolean = false,
    ) {
        /** 変わった値のうち、次にサーバー（ワールド）を開いたときに効くもの。同期された設定では空（このクライアントでは効かない） */
        val needsRestart: List<ConfigEntry<*>>
            get() = if (synced) emptyList() else changed.filter { it.apply == ConfigApply.WORLD_RESTART }
    }

    /** [refresh] を、イベントの代わりに直接呼ぶ（テスト用。[synced] は同期された設定を受け取った経路のつもりにする） */
    internal fun refreshForTest(side: ConfigSide, reload: Boolean, synced: Boolean) {
        val reg = registered[side] ?: return
        refresh(side, reg, reload, synced)
    }

    /** 最後に読み込み・読み直しをした結果（テスト用） */
    @Volatile
    internal var lastReport: Report? = null
        private set

    /** 項目のある分類の spec を作って登録する。MOD の初期化で1回だけ呼ぶ */
    fun register(container: ModContainer, modBus: IEventBus) {
        for (side in ConfigSide.entries) {
            val entries = KiBiConfig.entries(side)
            if (entries.isEmpty()) continue
            val built = build(entries)
            registered[side] = built
            container.registerConfig(typeOf(side), built.spec, side.fileName)
        }
        modBus.addListener(::onLoading)
        modBus.addListener(::onReloading)
    }

    private fun typeOf(side: ConfigSide): ModConfig.Type = when (side) {
        ConfigSide.SERVER -> ModConfig.Type.SERVER
        ConfigSide.CLIENT -> ModConfig.Type.CLIENT
        ConfigSide.COMMON -> ModConfig.Type.COMMON
    }

    private fun build(entries: List<ConfigEntry<*>>): Registered {
        val builder = ModConfigSpec.Builder()
        val values = IdentityHashMap<ConfigEntry<*>, ModConfigSpec.ConfigValue<*>>()
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
        val (side, reg) = registered.entries.firstOrNull { it.value.spec === config.spec }?.toPair() ?: return
        refresh(side, reg, reload, synced = reload && !isFileBacked(config))
    }

    /**
     * [config] がファイルから読んだものか。サーバーから同期された設定（と、ファイルのない既定のサーバー設定）は
     * ファイルを持たず、`getFullPath` が例外になる（FML 4.0 の `ModConfig`）
     */
    private fun isFileBacked(config: ModConfig): Boolean = runCatching { config.fullPath }.isSuccess

    private fun refresh(side: ConfigSide, reg: Registered, reload: Boolean, synced: Boolean) {
        // キャッシュしない値を読む（worldRestart の項目は get() だと読み直しで変わらず、「次の起動で効く」を出せない）。
        // 読み込む前（spec がまだ読まれていない）なら既定にする
        val source = ConfigSource { entry -> runCatching { reg.values[entry]?.getRaw() }.getOrNull() }
        val next = ConfigValues.resolve(source, KiBiConfig.entries(side))
        for (entry in next.rejected) LOGGER.warn("Ignoring an invalid value of {} in {}; using the default", entry.path, side.fileName)
        val changed = state(side).update(next)
        loaded += side
        val report = Report(side, reload, changed, next.rejected, synced)
        lastReport = report
        if (!reload) return
        if (synced) {
            LOGGER.debug("Received {} from the server ({} values differ from the previous copy)", side.fileName, changed.size)
            return
        }
        report.needsRestart.forEach {
            LOGGER.info("{} in {} changed; it takes effect the next time the server (world) starts", it.path, side.fileName)
        }
        if (changed.isNotEmpty()) LOGGER.info("Reloaded {} ({} values changed)", side.fileName, changed.size)
    }
}
