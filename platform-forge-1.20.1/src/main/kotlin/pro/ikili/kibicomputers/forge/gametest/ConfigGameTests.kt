package pro.ikili.kibicomputers.forge.gametest

import com.electronwill.nightconfig.core.CommentedConfig
import com.electronwill.nightconfig.toml.TomlFormat
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.common.ForgeConfigSpec
import net.minecraftforge.fml.ModList
import net.minecraftforge.fml.config.ConfigTracker
import net.minecraftforge.fml.config.IConfigEvent
import net.minecraftforge.fml.config.ModConfig
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.config.ConfigSide
import pro.ikili.kibicomputers.core.config.IntEntry
import pro.ikili.kibicomputers.core.config.KiBiConfig
import pro.ikili.kibicomputers.core.persist.ModuleStore
import pro.ikili.kibicomputers.forge.config.KiBiConfigs
import java.nio.file.Files

/**
 * コンフィグ（#202）の GameTest: SERVER の spec が登録され、ワールドを開くときに読まれて core の写しができている。
 * 読み込み・読み直しのイベントは、MOD のバスの購読（KFF のバスをリフレクションで取る）で受けていること。
 * 予備の購読（`ServerAboutToStartEvent`）でも読み込みは立つので、それとは分けて確かめる。
 */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object ConfigGameTests {
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun serverConfigIsLoaded(helper: GameTestHelper) {
        helper.assertTrue(KiBiConfigs.isRegistered(ConfigSide.SERVER), "the server config should be registered")
        helper.assertTrue(KiBiConfigs.isLoaded(ConfigSide.SERVER), "the server config should be loaded when the server starts")
        // MOD のバスが取れていなければ、読み直しも効かない（予備の購読は読み込みだけ）
        helper.assertTrue(
            KiBiConfigs.isLoadedByEvent(ConfigSide.SERVER),
            "ModConfigEvent.Loading should reach KiBiConfigs through the mod event bus",
        )
        // 写しから core の設定を組み立てられる（範囲は spec が直している）
        val values = KiBiConfigs.server
        helper.assertTrue(values.rejected.isEmpty(), "no value should be rejected: ${values.rejected}")
        KiBiConfig.machineSaveConfig(values)
        KiBiConfig.screenSyncTraffic<Any>(values)
        helper.succeed()
    }

    /**
     * 持ち主の仕組みを持たない 1.20.1 では、持ち主ごとの項目（[pro.ikili.kibicomputers.core.config.ConfigEntry.ownerScoped]）を
     * コンフィグに出さず、組み立てでも組ごとの上限を掛けない（#897）。全台が持ち主なしの1組になり、組の上限が全体の上限として効くため
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun ownerScopedEntriesAreNotExposed(helper: GameTestHelper) {
        helper.assertTrue(!KiBiConfigs.OWNER_GROUPS, "1.20.1 has no owner groups until #224")
        val scoped = KiBiConfig.ENTRIES.filter { it.ownerScoped }
        helper.assertTrue(KiBiConfig.MAX_BLOCKING_PROCESSES_PER_OWNER in scoped, "the per-owner limits should be owner scoped")
        for (entry in scoped) {
            helper.assertTrue(KiBiConfigs.specValue(entry) == null, "$entry should not be in the 1.20.1 config")
        }
        // 持ち主に依らない項目は今までどおり出す
        for (entry in KiBiConfig.entries(ConfigSide.SERVER).filterNot { it.ownerScoped }) {
            helper.assertTrue(KiBiConfigs.specValue(entry) != null, "$entry should be in the 1.20.1 config")
        }
        val scheduler = KiBiConfigs.schedulerConfig(ModuleStore.NONE)
        helper.assertTrue(scheduler.maxConcurrentMachinesPerGroup == 0, "no per-group permit limit")
        helper.assertTrue(scheduler.maxBlockingProcessesPerGroup == 0, "no per-group blocking limit")
        helper.assertTrue(scheduler.maxPendingLoadsPerGroup == 0, "no per-group load limit")
        helper.assertTrue(
            scheduler.maxBlockingProcesses == KiBiConfigs.server[KiBiConfig.MAX_BLOCKING_PROCESSES],
            "the server-wide blocking limit still applies",
        )
        val traffic = KiBiConfigs.screenSyncTraffic<Any>()
        helper.assertTrue(traffic.groupBytesPerSecond == 0, "no per-group screen traffic limit")
        helper.succeed()
    }

    /**
     * 読み直しのイベント（FML がファイルの変更で出すのと同じもの）で、新しい値の写しができる。
     *
     * 1.20.1 の `ForgeConfigSpec.ConfigValue.set` は、FML が autosave 付きで開いたファイルの設定に書くので、
     * そのたびに `serverconfig` の TOML を書き、ファイルを見張るスレッドが本物の `Reloading` を遅れて出す。
     * それがテストの読み直しと重なると、古い値の写しで上書きされて落ちる（#773）。
     * そこで、テストの間だけ spec をファイルを持たない写し（[detachedCopy]）につなぎ替えて値を変え、
     * 終わったらファイルの設定に戻す。ファイルは書かない（更新時刻と内容が変わらないことを確かめる。内容だけだと、途中で書いて元の値に戻した場合を見逃す）。
     * `ModConfig` の `configData` は差し替えないので、`KiBiConfigs` はファイルの読み直しとして扱う
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, batch = "kibicomputers_config_reload")
    fun reloadedValuesReachTheState(helper: GameTestHelper) {
        val entry = KiBiConfig.RESTORES_PER_TICK
        val value = KiBiConfigs.specValue(entry) ?: throw IllegalStateException("restoresPerTick should have a spec value")
        val modConfig = serverModConfig()
        val spec = modConfig.getSpec<ForgeConfigSpec>() as ForgeConfigSpec
        val fileData = modConfig.configData
        // 内容だけでなく更新時刻も見る。途中で書いて元の値に戻しても、TOML の書き出しが安定していれば
        // 前後のバイト列は同じになるが、書き込みがあれば更新時刻は進む
        val fileBefore = Files.readAllBytes(modConfig.fullPath)
        val modifiedBefore = Files.getLastModifiedTime(modConfig.fullPath)
        val original = KiBiConfigs.server[entry]
        val bumped = otherValue(entry, original)
        try {
            // 写しは FileConfig ではないので、set も補正も保存しない
            spec.acceptConfig(detachedCopy(fileData))
            value.set(bumped)
            dispatchReloading(modConfig)
            helper.assertTrue(
                KiBiConfigs.isReloadedByEvent(ConfigSide.SERVER),
                "ModConfigEvent.Reloading should reach KiBiConfigs through the mod event bus",
            )
            helper.assertTrue(
                KiBiConfigs.server[entry] == bumped,
                "the reloaded value should reach the state (was ${KiBiConfigs.server[entry]})",
            )
        } finally {
            // ファイルの設定に戻す（キャッシュも捨てる）。戻した値をまた読み直しで配る
            spec.acceptConfig(fileData)
            dispatchReloading(modConfig)
        }
        helper.assertTrue(
            KiBiConfigs.server[entry] == original,
            "restoring the value should be reflected (was ${KiBiConfigs.server[entry]}, expected $original)",
        )
        helper.assertTrue(
            Files.getLastModifiedTime(modConfig.fullPath) == modifiedBefore,
            "the test should not write the server config file (its modification time changed)",
        )
        helper.assertTrue(
            Files.readAllBytes(modConfig.fullPath).contentEquals(fileBefore),
            "the test should not change the server config file",
        )
        helper.succeed()
    }

    private fun serverModConfig(): ModConfig =
        ConfigTracker.INSTANCE.configSets()[ModConfig.Type.SERVER]!!.first { it.modId == KiBiComputers.MOD_ID }

    /**
     * [config] の、ファイルを持たない深い写し。`CommentedConfig.copy` は節（サブの設定）を共有する浅い写しで、
     * 写しへの `set` がファイルの設定のメモリ上の値まで変えてしまうので使わない。
     * TOML に書いて読み直す（専用サーバーからの同期 `ModConfig.acceptSyncedConfig` と同じ形。コメントも残る）
     */
    private fun detachedCopy(config: CommentedConfig): CommentedConfig =
        TomlFormat.instance().createParser().parse(TomlFormat.instance().createWriter().writeToString(config))

    private fun dispatchReloading(config: ModConfig) {
        ModList.get().getModContainerById(KiBiComputers.MOD_ID).orElseThrow().dispatchConfigEvent(IConfigEvent.reloading(config))
    }

    /** [current]（ファイルの値。既定とは限らない）と違う、範囲の中の値。上がなければ下を使う */
    private fun otherValue(entry: IntEntry, current: Int): Int = when {
        current < entry.max -> current + 1
        current > entry.min -> current - 1
        else -> error("${entry.path} has no value other than $current")
    }
}
