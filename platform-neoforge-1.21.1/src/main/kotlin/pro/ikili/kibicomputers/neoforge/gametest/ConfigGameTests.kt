package pro.ikili.kibicomputers.neoforge.gametest

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestAssertException
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.phys.Vec3
import net.neoforged.fml.ModList
import net.neoforged.fml.config.ModConfig
import net.neoforged.fml.config.ModConfigs
import net.neoforged.fml.event.config.ModConfigEvent
import net.neoforged.neoforge.gametest.GameTestHolder
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import pro.ikili.kibicomputers.core.KiBiComputers
import pro.ikili.kibicomputers.core.config.ConfigEntry
import pro.ikili.kibicomputers.core.config.ConfigSide
import pro.ikili.kibicomputers.core.config.IntEntry
import pro.ikili.kibicomputers.core.config.KiBiConfig
import pro.ikili.kibicomputers.core.persist.ModuleStore
import pro.ikili.kibicomputers.neoforge.config.KiBiConfigs
import pro.ikili.kibicomputers.neoforge.display.ScreenSync
import pro.ikili.kibicomputers.neoforge.input.KeyboardBlockEntity
import pro.ikili.kibicomputers.neoforge.input.KeyboardSessions
import pro.ikili.kibicomputers.neoforge.machine.ServerMachines
import pro.ikili.kibicomputers.neoforge.registry.KiBiBlocks

/**
 * コンフィグ（#202）の GameTest: spec の登録、サーバーを開くときの読み込み、読み直しの経路（ローダーのイベント）、
 * 読み直しが使う側（保存・画面の同期・キーボード）に当たること、lang のキー。
 *
 * 読み直しは、spec の値を差し替えてから、FML がファイルの変更で出すのと同じイベント（[ModConfigEvent.Reloading]）を
 * MOD のバスに流して試す（[reload]）。ファイルは書かない（ファイルを見張るスレッドとの競合を避ける）。
 * 値を変えるテストは、ほかのテストと同時に走らないよう、それぞれ別の batch にする。
 *
 * `runClient`・`runGameTestServer`・`runLoadTestServer` は同じ `run/config/kibicomputers-server.toml` を使うので、
 * ファイルの値が既定とは限らない（開発クライアントの設定画面で変えた後など）。値を変えるテストは、組み込みの既定ではなく、
 * 始めたときの値から変え（[otherValues]）、始めたときの値に戻ったことを確かめる
 */
@GameTestHolder(KiBiComputers.MOD_ID)
@PrefixGameTestTemplate(false)
object ConfigGameTests {
    private val KEYBOARD_POS = BlockPos(1, 1, 1)

    /** SERVER の spec が登録され、サーバーを開くときに（`ServerAboutToStartEvent` の前に）読まれている */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun serverConfigIsLoadedBeforeTheServerStarts(helper: GameTestHelper) {
        helper.assertTrue(KiBiConfigs.isRegistered(ConfigSide.SERVER), "the server config should be registered")
        helper.assertTrue(KiBiConfigs.isLoaded(ConfigSide.SERVER), "the server config should be loaded")
        helper.assertTrue(ServerMachines.configLoadedAtStart, "the server config should be read before ServerAboutToStartEvent")
        helper.assertTrue(ServerMachines.schedulerConfig != null, "the scheduler config should be recorded")
        helper.succeed()
    }

    /**
     * 読み直しで変えた SERVER のコンフィグの値が、ローダーのイベントを通ってスケジューラの設定に届く。
     * スレッドの数などは作るときに決まるので、動いているスケジューラは変わらず、「次の起動で効く」と報告される。
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, batch = "kibicomputers_config_scheduler")
    fun serverConfigValuesReachTheSchedulerConfig(helper: GameTestHelper) {
        val running = ServerMachines.schedulerConfig ?: throw GameTestAssertException("the scheduler config should be recorded")
        val initialThreads = KiBiConfigs.server[KiBiConfig.COMPILER_THREADS]
        val threads = otherValues(KiBiConfig.COMPILER_THREADS, 1).single()
        // 0（自動）でない値にする
        val concurrent = otherValues(KiBiConfig.MAX_CONCURRENT_MACHINES, 2).first { it != KiBiConfig.MAX_CONCURRENT_MACHINES.auto }
        val originals = originalsOf(KiBiConfig.COMPILER_THREADS, KiBiConfig.MAX_CONCURRENT_MACHINES)
        try {
            reload(KiBiConfig.COMPILER_THREADS to threads, KiBiConfig.MAX_CONCURRENT_MACHINES to concurrent)
            val report = KiBiConfigs.lastReport ?: throw GameTestAssertException("a reload should be reported")
            helper.assertTrue(report.reload && report.side == ConfigSide.SERVER, "the report should be of a server reload")
            helper.assertTrue(!report.synced, "a reload of the file should not be taken as a synced config")
            helper.assertTrue(
                report.changed.toSet() == setOf(KiBiConfig.MAX_CONCURRENT_MACHINES, KiBiConfig.COMPILER_THREADS),
                "both values should be reported as changed: ${report.changed}",
            )
            helper.assertTrue(
                report.needsRestart == report.changed,
                "scheduler values should be reported as taking effect on the next start: ${report.needsRestart}",
            )
            // 次にサーバーを開くときに作る設定には、読み直した値が入る
            val next = ServerMachines.schedulerConfigFromConfig(ModuleStore.NONE)
            helper.assertTrue(next.compilerThreads == threads, "compilerThreads should come from the reloaded config: ${next.compilerThreads}")
            helper.assertTrue(next.maxConcurrentMachines == concurrent, "maxConcurrentMachines should come from the config: ${next.maxConcurrentMachines}")
            // 動いているスケジューラは作り直さない
            helper.assertTrue(ServerMachines.schedulerConfig === running, "the running scheduler config should not change")
        } finally {
            restore(originals)
        }
        helper.assertTrue(
            ServerMachines.schedulerConfigFromConfig(ModuleStore.NONE).compilerThreads == initialThreads,
            "restoring the value should be reflected",
        )
        helper.succeed()
    }

    /**
     * 読み直した `save` の値が、ローダーのイベントを通って次の tick に保存の設定と自動保存の列に当たる。
     * テストの上書き（`saveConfig` の差し替え）が載っている間はそちらが優先され、元の値に戻すと、読み直した値が効く。
     * 上書きが残ったまま新しいサーバーを開いたときは、上書きを捨てて、設定と自動保存の列がそろう
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, batch = "kibicomputers_config_save", timeoutTicks = 100)
    fun reloadedSaveValuesApply(helper: GameTestHelper) {
        val entry = KiBiConfig.RESTORES_PER_TICK
        val initial = KiBiConfigs.server[entry]
        val originals = originalsOf(entry)
        val (first, second, third) = otherValues(entry, 3)
        reload(entry to first)
        helper.assertTrue(
            KiBiConfigs.lastReport?.changed == listOf<ConfigEntry<*>>(entry),
            "only restoresPerTick should change: ${KiBiConfigs.lastReport?.changed}",
        )
        helper.assertTrue(KiBiConfigs.lastReport?.needsRestart?.isEmpty() == true, "restoresPerTick should take effect without a restart")
        // 次の tick の始めに当たる
        helper.runAfterDelay(2) {
            try {
                helper.assertTrue(
                    ServerMachines.saveConfig.restoresPerTick == first,
                    "the reloaded value should apply on the next tick: ${ServerMachines.saveConfig.restoresPerTick}",
                )
                helper.assertTrue(ServerMachines.rotationConfig == ServerMachines.saveConfig, "the autosave rotation should follow the config")
                overrideLayers(helper, entry, second, third)
                newServerDropsTheOverride(helper)
            } finally {
                restore(originals)
                ServerMachines.applyConfigIfChanged()
            }
            helper.assertTrue(
                ServerMachines.saveConfig == KiBiConfig.machineSaveConfig(KiBiConfigs.server) &&
                    ServerMachines.saveConfig.restoresPerTick == initial,
                "restoring the value should be reflected",
            )
            helper.succeed()
        }
    }

    private fun overrideLayers(helper: GameTestHelper, entry: IntEntry, second: Int, third: Int) {
        // テストの定型: 差し替えて、戻す。間にコンフィグが読み直されても、戻したら新しい値が効く
        val before = ServerMachines.saveConfig
        ServerMachines.saveConfig = before.copy(writerFlushMillis = 200)
        reload(entry to second)
        ServerMachines.applyConfigIfChanged()
        helper.assertTrue(
            ServerMachines.saveConfig == before.copy(writerFlushMillis = 200),
            "the override should win over the reloaded config while it is set",
        )
        ServerMachines.saveConfig = before
        helper.assertTrue(
            ServerMachines.saveConfig.restoresPerTick == second,
            "after putting the old value back, the reloaded value should apply: ${ServerMachines.saveConfig.restoresPerTick}",
        )
        helper.assertTrue(ServerMachines.rotationConfig == ServerMachines.saveConfig, "the autosave rotation should follow the config")
        // コンフィグと同じ値に戻したら、上書きは外れる（その後の読み直しが効く）
        ServerMachines.saveConfig = ServerMachines.saveConfig.copy(writerFlushMillis = 300)
        ServerMachines.saveConfig = KiBiConfig.machineSaveConfig(KiBiConfigs.server)
        reload(entry to third)
        ServerMachines.applyConfigIfChanged()
        helper.assertTrue(
            ServerMachines.saveConfig.restoresPerTick == third,
            "setting the configured value should drop the override: ${ServerMachines.saveConfig.restoresPerTick}",
        )
    }

    private fun newServerDropsTheOverride(helper: GameTestHelper) {
        val configured = KiBiConfig.machineSaveConfig(KiBiConfigs.server)
        ServerMachines.saveConfig = configured.copy(autosaveWritesPerTick = 9)
        helper.assertTrue(ServerMachines.rotationConfig.autosaveWritesPerTick == 9, "the rotation should follow an override")
        // 戻されずに残った上書きは、新しいサーバーを開くときに捨てる
        ServerMachines.resetConfigForNewServer()
        helper.assertTrue(ServerMachines.saveConfig == configured, "a new server should start from the configured values")
        helper.assertTrue(ServerMachines.rotationConfig == configured, "the autosave rotation should match the config of the new server")
    }

    /** 画面の同期の予算を読み直すと、予算が変わったときだけ次の tick に勘定を作り直す */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, batch = "kibicomputers_config_screen", timeoutTicks = 100)
    fun reloadedScreenSyncBudgetRebuildsTheTraffic(helper: GameTestHelper) {
        ScreenSync.applyConfigIfChanged()
        val player = KiBiConfig.SCREEN_PLAYER_BYTES_PER_SECOND
        val unrelated = KiBiConfig.RESTORES_PER_TICK
        val originals = originalsOf(player, unrelated)
        val before = ScreenSync.traffic
        val bumped = otherValues(player, 1).single()
        reload(player to bumped)
        helper.runAfterDelay(2) {
            try {
                val rebuilt = ScreenSync.traffic
                helper.assertTrue(rebuilt !== before, "the traffic should be rebuilt when the budget changes")
                helper.assertTrue(rebuilt.playerBytesPerSecond == bumped, "the player budget should come from the config: ${rebuilt.playerBytesPerSecond}")
                // 予算に関係のない値の読み直しでは、作り直さない（プレイヤーごとの勘定を消さない）
                reload(unrelated to otherValues(unrelated, 1).single())
                ScreenSync.applyConfigIfChanged()
                helper.assertTrue(ScreenSync.traffic === rebuilt, "an unrelated reload should keep the traffic")
            } finally {
                restore(originals)
                ScreenSync.applyConfigIfChanged()
            }
            helper.assertTrue(ScreenSync.traffic.playerBytesPerSecond == before.playerBytesPerSecond, "restoring the value should be reflected")
            helper.succeed()
        }
    }

    /**
     * キーボードの予算は、開くときにコンフィグの値から作る。使いかけの予算（満タンに戻るまで残る）が古い値なら作り直す。
     * 戻る量が 0 の予算も、値を直した後に開き直せば、新しい値になる
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, batch = "kibicomputers_config_keyboard")
    fun keyboardBudgetFollowsTheConfig(helper: GameTestHelper) {
        helper.setBlock(KEYBOARD_POS, KiBiBlocks.KEYBOARD.get())
        helper.getBlockEntity<KeyboardBlockEntity>(KEYBOARD_POS)
        val abs = helper.absolutePos(KEYBOARD_POS)
        val player = helper.makeMockServerPlayerInLevel()
        player.moveTo(Vec3.atCenterOf(abs.east()))
        val capacity = KiBiConfig.KEYBOARD_INPUT_CAPACITY
        val refill = KiBiConfig.KEYBOARD_INPUT_REFILL_PER_TICK
        val originals = originalsOf(capacity, refill)
        val initialCapacity = KiBiConfigs.server[capacity]
        val bigger = otherValues(capacity, 1).single()
        try {
            reload(refill to 0)
            val stuck = KeyboardSessions.open(player, abs, null) ?: throw GameTestAssertException("the keyboard should open")
            helper.assertTrue(stuck.budget.refillPerTick == 0 && stuck.budget.capacity == initialCapacity, "the budget should come from the config")
            helper.assertTrue(stuck.budget.tryConsume(100, 0), "some of the budget should be usable")
            KeyboardSessions.closeFromClient(player, stuck.id)

            reload(refill to 7, capacity to bigger)
            val fixed = KeyboardSessions.open(player, abs, null) ?: throw GameTestAssertException("the keyboard should reopen")
            helper.assertTrue(fixed.budget !== stuck.budget, "a partly used budget with old values should be rebuilt")
            helper.assertTrue(
                fixed.budget.refillPerTick == 7 && fixed.budget.capacity == bigger,
                "the rebuilt budget should use the reloaded values: ${fixed.budget.capacity}/${fixed.budget.refillPerTick}",
            )
            helper.assertTrue(fixed.budget.tryConsume(100, 0), "the rebuilt budget should be usable")
            // 値が同じなら、使いかけの予算を残す（開き直しで戻さない）。戻る量が 0 なら、満タンに戻って捨てられることもない
            KeyboardSessions.closeFromClient(player, fixed.id)
            reload(refill to 0)
            val empty = KeyboardSessions.open(player, abs, null) ?: throw GameTestAssertException("the keyboard should reopen")
            helper.assertTrue(empty.budget.tryConsume(100, 0), "the rebuilt budget should be usable")
            KeyboardSessions.closeFromClient(player, empty.id)
            val same = KeyboardSessions.open(player, abs, null) ?: throw GameTestAssertException("the keyboard should reopen")
            helper.assertTrue(same.budget === empty.budget, "a partly used budget with the current values should be kept")
        } finally {
            restore(originals)
            KeyboardSessions.loggedOut(player)
        }
        helper.succeed()
    }

    /**
     * 専用サーバーから同期された設定（ファイルを持たない）を受け取ったクライアントの経路では、写しは差し替えるが、
     * 「次の起動で効く」とは報告しない（クライアントでは何も変えていない）
     */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY, batch = "kibicomputers_config_synced")
    fun syncedServerConfigIsNotReportedAsNeedingARestart(helper: GameTestHelper) {
        val entry = KiBiConfig.COMPILER_THREADS
        val originals = originalsOf(entry)
        val synced = otherValues(entry, 1).single()
        try {
            (KiBiConfigs.specValue(entry) ?: error("no spec value for ${entry.path}")).set(synced)
            KiBiConfigs.refreshForTest(ConfigSide.SERVER, reload = true, synced = true)
            val report = KiBiConfigs.lastReport ?: throw GameTestAssertException("a reload should be reported")
            helper.assertTrue(report.synced, "the report should be of a synced config")
            helper.assertTrue(report.changed == listOf<ConfigEntry<*>>(entry), "the synced value should be taken: ${report.changed}")
            helper.assertTrue(report.needsRestart.isEmpty(), "a synced config should not be reported as needing a restart: ${report.needsRestart}")
            helper.assertTrue(KiBiConfigs.server[entry] == synced, "the client copy should hold the synced value")
        } finally {
            restore(originals)
        }
        helper.succeed()
    }

    /** すべての項目と区画に、en_us・ja_jp の名前と説明（`.tooltip`）がある */
    @JvmStatic
    @GameTest(template = SmokeGameTests.EMPTY)
    fun everyEntryIsTranslated(helper: GameTestHelper) {
        val keys = buildList {
            add(KiBiComputers.MOD_ID + ".configuration.title")
            for (section in KiBiConfig.SECTION_COMMENTS.keys) {
                add(KiBiConfig.sectionTranslationKey(section))
                add(KiBiConfig.sectionTranslationKey(section) + ".tooltip")
            }
            for (entry in KiBiConfig.ENTRIES) {
                add(entry.translationKey)
                add(entry.translationKey + ".tooltip")
            }
        }
        for (lang in listOf("en_us", "ja_jp")) {
            val json = lang(lang)
            val missing = keys.filter { !json.has(it) || json.get(it).asString.isBlank() }
            helper.assertTrue(missing.isEmpty(), "$lang is missing config translations: $missing")
        }
        helper.succeed()
    }

    // ---- 読み直しの経路 ----

    private fun serverModConfig(): ModConfig =
        ModConfigs.getModConfigs(KiBiComputers.MOD_ID).first { it.type == ModConfig.Type.SERVER }

    /**
     * [entry] の今の値（ファイルの値。既定とは限らない）と違う、範囲の中の値を [count] 個（重ならない）。
     * 今の値より大きい値を先に、足りなければ小さい値を使う
     */
    private fun otherValues(entry: IntEntry, count: Int): List<Int> {
        val current = KiBiConfigs.server[entry].toLong()
        val above = (current + 1..minOf(entry.max.toLong(), current + count)).map { it.toInt() }
        val below = (maxOf(entry.min.toLong(), current - count) until current).reversed().map { it.toInt() }
        val values = (above + below).take(count)
        check(values.size == count) { "${entry.path} has no $count values other than $current" }
        return values
    }

    private fun originalsOf(vararg entries: ConfigEntry<*>): List<Pair<ConfigEntry<*>, Any>> =
        entries.map { it to (KiBiConfigs.specValue(it)?.getRaw() ?: error("no spec value for ${it.path}")) }

    /** spec の値を差し替えて、FML が読み直しで出すイベントを MOD のバスに流す（`KiBiConfigs` の読み直しの経路を通す） */
    private fun reload(vararg changes: Pair<ConfigEntry<*>, Any>) {
        for ((entry, value) in changes) (KiBiConfigs.specValue(entry) ?: error("no spec value for ${entry.path}")).set(value)
        ModList.get().getModContainerById(KiBiComputers.MOD_ID).orElseThrow()
            .acceptEvent(ModConfigEvent.Reloading(serverModConfig()))
    }

    private fun restore(originals: List<Pair<ConfigEntry<*>, Any>>) {
        reload(*originals.toTypedArray())
    }

    private fun lang(name: String): JsonObject {
        val stream = ConfigGameTests::class.java.getResourceAsStream("/assets/${KiBiComputers.MOD_ID}/lang/$name.json")
            ?: error("lang file $name.json not found")
        return stream.reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
    }
}
