package pro.ikili.kibicomputers.core.config

import org.jetbrains.annotations.ApiStatus
import pro.ikili.kibicomputers.core.display.ScreenSyncTraffic
import pro.ikili.kibicomputers.core.input.KeyboardEventCodec
import pro.ikili.kibicomputers.core.input.KeyboardInputBudget
import pro.ikili.kibicomputers.core.kernel.SchedulerConfig
import pro.ikili.kibicomputers.core.persist.MachineSaveConfig
import pro.ikili.kibicomputers.core.persist.ModuleStore

/**
 * KiBi Computers のコンフィグの項目の一覧と、値から core の設定を組み立てる関数（#202、ADR 0202）。
 *
 * 項目を足すときは、ここに宣言を1つ足し（[ENTRIES] に入れる）、1.21.1 の lang（`en_us`・`ja_jp`）に名前と説明を足す
 * （1.20.1 には lang がなく、移植のとき #224 で足す）。接続層は [ENTRIES] を走査して spec に写すので、接続層は直さなくてよい。
 *
 * 既定の値はすべて仮の値（確定は #251）。機械によって変わる既定（コア数・ヒープ）は数を書かず、「自動」の番兵（0）にする。
 * バイト数は MiB・KiB の整数で受け、バイトに直すのはここの組み立てだけにする（桁あふれを1か所で防ぐ）。
 */
@ApiStatus.Internal
public object KiBiConfig {
    private const val MIB: Long = 1024L * 1024
    private const val KIB: Long = 1024L

    /** 自動保存の周期の最小（1 秒）。これより短くしても、止めた台が書かれる前に周期が替わりやすいだけ */
    private const val MIN_AUTOSAVE_PERIOD_TICKS: Int = 20

    /**
     * 自動保存の周期は「待つ tick + これ」以上にする。周期が替わると、止めている台は書く前でも `close` して列に戻す（AutosaveRotation）。
     * 止めた tick の次の tick から待つ tick の間に書けるよう、止める tick と書く tick の2つ分を足す
     */
    private const val AUTOSAVE_PERIOD_MARGIN_TICKS: Int = 2

    private val entries = ArrayList<ConfigEntry<*>>()

    private fun <E : ConfigEntry<*>> add(entry: E): E = entry.also { entries += it }

    private fun serverInt(
        section: String,
        name: String,
        default: Int,
        min: Int,
        max: Int,
        apply: ConfigApply,
        en: String,
        ja: String,
        auto: Int? = null,
    ): IntEntry = add(IntEntry(ConfigSide.SERVER, section, name, default, min, max, apply, en, ja, auto))

    // ---- scheduler: スレッドと全体の上限（ADR 0027・0029・0037・0362）。スケジューラを作るときに決まるので、次の起動で効く ----

    /** 同時に WASM を実行するマシンの数の上限。0 は自動（CPU のスレッド数の半分。最低 1） */
    @JvmField
    public val MAX_CONCURRENT_MACHINES: IntEntry = serverInt(
        "scheduler", "maxConcurrentMachines", 0, 0, 4096, ConfigApply.WORLD_RESTART,
        "Maximum number of computers running WebAssembly at the same time. 0 = automatic (half of the CPU threads, at least 1).",
        "同時に WebAssembly を実行するコンピューターの数の上限。0 は自動（CPU のスレッド数の半分。最低 1）。",
        auto = 0,
    )

    /** カーネルのスレッドのスタックの大きさ（KiB） */
    @JvmField
    public val THREAD_STACK_KIB: IntEntry = serverInt(
        "scheduler", "threadStackKiB", 16 * 1024, 256, 1024 * 1024, ConfigApply.WORLD_RESTART,
        "Stack size of each kernel thread, in KiB. Only reserved; memory is used as needed.",
        "カーネルのスレッド1本のスタックの大きさ（KiB）。予約するだけで、使った分しかメモリを取らない。",
    )

    /** サーバー全体のブロッキング型プロセス（= 専用スレッド）の数の上限 */
    @JvmField
    public val MAX_BLOCKING_PROCESSES: IntEntry = serverInt(
        "scheduler", "maxBlockingProcesses", 2048, 0, 65536, ConfigApply.WORLD_RESTART,
        "Maximum number of blocking processes (each uses a dedicated thread) on the whole server.",
        "サーバー全体のブロッキング型プロセス（1つが専用スレッドを1本使う）の数の上限。",
    )

    /** 実行ファイルを読み込む（計測コードの挿入・コンパイル）スレッドの数 */
    @JvmField
    public val COMPILER_THREADS: IntEntry = serverInt(
        "scheduler", "compilerThreads", 1, 1, 64, ConfigApply.WORLD_RESTART,
        "Number of low-priority threads that load (instrument and compile) programs.",
        "実行ファイルを読み込む（計測コードの挿入・コンパイル）、優先度を下げたスレッドの数。",
    )

    /** 使われていなくても残す、読み込んだ実行ファイルの数 */
    @JvmField
    public val PROGRAM_CACHE_ENTRIES: IntEntry = serverInt(
        "scheduler", "programCacheEntries", 32, 1, 4096, ConfigApply.WORLD_RESTART,
        "Number of loaded programs kept in memory even when no computer uses them.",
        "どのコンピューターも使っていなくても、メモリに残しておく読み込み済みの実行ファイルの数。",
    )

    /** 使われていなくても残す実行ファイルの、WASM の大きさの合計の上限（MiB） */
    @JvmField
    public val PROGRAM_CACHE_MIB: IntEntry = serverInt(
        "scheduler", "programCacheMiB", 32, 1, 65536, ConfigApply.WORLD_RESTART,
        "Total WebAssembly size of the unused programs kept in memory, in MiB.",
        "メモリに残す、使われていない実行ファイルの WebAssembly の大きさの合計の上限（MiB）。",
    )

    /** サーバー全体の RAM の上限（MiB）。0 は自動（JVM のヒープの最大値の半分） */
    @JvmField
    public val TOTAL_MEMORY_MIB: IntEntry = serverInt(
        "scheduler", "totalMemoryMiB", 0, 0, Int.MAX_VALUE, ConfigApply.WORLD_RESTART,
        "Total RAM limit for all computers on the server, in MiB. 0 = automatic (half of the maximum JVM heap).",
        "サーバーの全コンピューターの RAM の合計の上限（MiB）。0 は自動（JVM のヒープの最大値の半分）。",
        auto = 0,
    )

    // ---- save: 待機点保存（ADR 0081・0083・0084・0085）。読み直しで効く ----

    /** 自動保存の周期（tick） */
    @JvmField
    public val AUTOSAVE_PERIOD_TICKS: IntEntry = serverInt(
        "save", "autosavePeriodTicks", 6000, MIN_AUTOSAVE_PERIOD_TICKS, Int.MAX_VALUE, ConfigApply.RELOAD,
        "Interval between autosaves of running computers, in ticks. Raised to at least autosaveQuiesceTicks + 2 (a shorter interval would close computers before they are written).",
        "動いているコンピューターを自動保存する周期（tick）。autosaveQuiesceTicks + 2 より短いときは、それに引き上げる（短いと、書く前に止めた台を戻してしまう）。",
    )

    /** 自動保存で、待機点に来るのを待つ tick の数 */
    @JvmField
    public val AUTOSAVE_QUIESCE_TICKS: IntEntry = serverInt(
        "save", "autosaveQuiesceTicks", 4, 1, 1200, ConfigApply.RELOAD,
        "Ticks an autosave waits for a computer to reach a safe point.",
        "自動保存で、コンピューターが待機点に来るのを待つ tick の数。",
    )

    /** 自動保存で、tick ごとに取り出して書き込みに回す台数の上限 */
    @JvmField
    public val AUTOSAVE_WRITES_PER_TICK: IntEntry = serverInt(
        "save", "autosaveWritesPerTick", 4, 1, 1000, ConfigApply.RELOAD,
        "Maximum number of computers captured for autosave per tick.",
        "自動保存で、1 tick に取り出して書き込みに回すコンピューターの数の上限。",
    )

    /** チャンクのアンロードで、tick ごとに待機点を待つ時間の合計の上限（ミリ秒） */
    @JvmField
    public val UNLOAD_WAIT_BUDGET_MILLIS: IntEntry = serverInt(
        "save", "unloadWaitBudgetMillis", 50, 0, 10_000, ConfigApply.RELOAD,
        "Total time per tick spent waiting for computers in unloading chunks to reach a safe point, in milliseconds.",
        "チャンクのアンロードで、1 tick にコンピューターが待機点に来るのを待つ時間の合計の上限（ミリ秒）。",
    )

    /** サーバーの停止で、全台が待機点に来るのを待つ上限（ミリ秒） */
    @JvmField
    public val STOP_QUIESCE_MILLIS: IntEntry = serverInt(
        "save", "stopQuiesceMillis", 500, 0, 600_000, ConfigApply.RELOAD,
        "Time the server waits on shutdown for all computers to reach a safe point, in milliseconds.",
        "サーバーの停止で、全コンピューターが待機点に来るのを待つ上限（ミリ秒）。",
    )

    /** 差分の代わりに全体保存にする、変わった区画の比の境目 */
    @JvmField
    public val FULL_SAVE_CHANGED_RATIO: DoubleEntry = add(
        DoubleEntry(
            ConfigSide.SERVER, "save", "fullSaveChangedRatio", MachineSaveConfig.DEFAULT_FULL_SAVE_CHANGED_RATIO, 0.0, 1.0,
            ConfigApply.RELOAD,
            "Write a full save instead of a delta when more than this fraction of the rewritten pages changed from the base.",
            "書き換えた区画のうち、基準から変わった区画の割合がこれを超えたら、差分ではなく全体を保存する。",
        ),
    )

    /** 書き込み待ちの写しの合計の上限（MiB） */
    @JvmField
    public val WRITER_MAX_PENDING_MIB: IntEntry = serverInt(
        "save", "writerMaxPendingMiB", (MachineSaveConfig.DEFAULT_WRITER_MAX_PENDING_BYTES / MIB).toInt(), 1, 65536,
        ConfigApply.RELOAD,
        "Maximum total size of saves waiting to be written, in MiB. Above this, autosaves are postponed.",
        "書き込み待ちの保存の大きさの合計の上限（MiB）。これ以上なら、自動保存を先に延ばす。",
    )

    /** 書き込みのスレッドを待つ上限（ミリ秒） */
    @JvmField
    public val WRITER_FLUSH_MILLIS: IntEntry = serverInt(
        "save", "writerFlushMillis", MachineSaveConfig.DEFAULT_WRITER_FLUSH_MILLIS.toInt(), 0, 600_000, ConfigApply.RELOAD,
        "Maximum time to wait for the save writer thread (on shutdown and when the pending limit is reached), in milliseconds.",
        "保存の書き込みのスレッドを待つ上限（停止のときと、書き込み待ちが上限に達したとき。ミリ秒）。",
    )

    /** tick ごとに復元を始める台数の上限 */
    @JvmField
    public val RESTORES_PER_TICK: IntEntry = serverInt(
        "save", "restoresPerTick", 16, 1, 1000, ConfigApply.RELOAD,
        "Maximum number of computers whose restore starts per tick when chunks load.",
        "チャンクの読み込みで、1 tick に復元を始めるコンピューターの数の上限。",
    )

    /** 復元の前に、その台の書き込みの終わりを待つ上限（ミリ秒） */
    @JvmField
    public val RESTORE_WRITE_WAIT_MILLIS: IntEntry = serverInt(
        "save", "restoreWriteWaitMillis", 2000, 0, 600_000, ConfigApply.RELOAD,
        "Maximum time a restore waits for a pending save of the same computer; after that it cold-boots, in milliseconds.",
        "復元の前に、同じコンピューターの書き込みの終わりを待つ上限（ミリ秒）。超えたら冷えた起動にする。",
    )

    // ---- screenSync: 画面の同期の送信量（#359、#352）。読み直しで効く ----

    /** プレイヤーごとの画面の同期の送信量の上限（バイト/秒） */
    @JvmField
    public val SCREEN_PLAYER_BYTES_PER_SECOND: IntEntry = serverInt(
        "screenSync", "playerBytesPerSecond", ScreenSyncTraffic.DEFAULT_PLAYER_BYTES_PER_SECOND, 1, Int.MAX_VALUE,
        ConfigApply.RELOAD,
        "Maximum screen update traffic sent to one player, in bytes per second.",
        "1人のプレイヤーに送る、画面の同期の送信量の上限（バイト/秒）。",
    )

    /** サーバー全体の画面の同期の送信量の上限（バイト/秒） */
    @JvmField
    public val SCREEN_SERVER_BYTES_PER_SECOND: IntEntry = serverInt(
        "screenSync", "serverBytesPerSecond", ScreenSyncTraffic.DEFAULT_SERVER_BYTES_PER_SECOND, 1, Int.MAX_VALUE,
        ConfigApply.RELOAD,
        "Maximum screen update traffic sent to all players together, in bytes per second.",
        "全プレイヤーの合計の、画面の同期の送信量の上限（バイト/秒）。",
    )

    // ---- keyboard: キーボードの入力の量（#142）。読み直した後に開いたキーボードから効く ----

    /** キーボードの入力の、ためておける量（バイト） */
    @JvmField
    public val KEYBOARD_INPUT_CAPACITY: IntEntry = serverInt(
        "keyboard", "inputCapacityBytes", KeyboardInputBudget.DEFAULT_CAPACITY, KeyboardEventCodec.MAX_PACKET_BYTES,
        Int.MAX_VALUE, ConfigApply.RELOAD,
        "Burst size of keyboard input a computer accepts, in bytes. Applies to keyboards opened after a change.",
        "キーボードの入力を一度に受け取れる量（バイト）。変えた後に開いたキーボードから効く。",
    )

    /** キーボードの入力の、tick ごとに戻る量（バイト） */
    @JvmField
    public val KEYBOARD_INPUT_REFILL_PER_TICK: IntEntry = serverInt(
        "keyboard", "inputRefillBytesPerTick", KeyboardInputBudget.DEFAULT_REFILL_PER_TICK, 0, Int.MAX_VALUE,
        ConfigApply.RELOAD,
        "Keyboard input allowance restored per tick, in bytes. Applies to keyboards opened after a change.",
        "キーボードの入力の、1 tick ごとに戻る量（バイト）。変えた後に開いたキーボードから効く。",
    )

    /** すべての項目（宣言の順） */
    @JvmField
    public val ENTRIES: List<ConfigEntry<*>> = entries.toList()

    /** [side] の項目（宣言の順） */
    @JvmStatic
    public fun entries(side: ConfigSide): List<ConfigEntry<*>> = ENTRIES.filter { it.side == side }

    /** 区画の説明（英語・日本語）。TOML の区画のコメントに出す */
    @JvmField
    public val SECTION_COMMENTS: Map<String, List<String>> = mapOf(
        "scheduler" to listOf(
            "Threads and server-wide limits. Changes take effect when the world is next opened.",
            "スレッドとサーバー全体の上限。変えた値は、次にワールドを開いたときに効く。",
        ),
        "save" to listOf("Saving and restoring running computers.", "動いているコンピューターの保存と復元。"),
        "screenSync" to listOf("Network budget for screen updates.", "画面の更新を送る量の予算。"),
        "keyboard" to listOf("Limits on keyboard input.", "キーボードの入力の上限。"),
    )

    /** [section] の区画の名前の lang のキー */
    @JvmStatic
    public fun sectionTranslationKey(section: String): String = ConfigEntry.TRANSLATION_PREFIX + section

    // ---- 値から core の設定を組み立てる ----

    /** [values] からスケジューラの設定を作る。「自動」の項目は [SchedulerConfig] の既定に任せる */
    @JvmStatic
    public fun schedulerConfig(values: ConfigValues, moduleStore: ModuleStore): SchedulerConfig {
        val builder = SchedulerConfig.builder()
            .threadStackBytes(values[THREAD_STACK_KIB] * KIB)
            .maxBlockingProcesses(values[MAX_BLOCKING_PROCESSES])
            .compilerThreads(values[COMPILER_THREADS])
            .programCacheEntries(values[PROGRAM_CACHE_ENTRIES])
            .programCacheBytes(values[PROGRAM_CACHE_MIB] * MIB)
            .moduleStore(moduleStore)
        values[MAX_CONCURRENT_MACHINES].let { if (it != MAX_CONCURRENT_MACHINES.auto) builder.maxConcurrentMachines(it) }
        values[TOTAL_MEMORY_MIB].let { if (it != TOTAL_MEMORY_MIB.auto) builder.totalMemoryBytes(it * MIB) }
        return builder.build()
    }

    /** [values] から待機点保存の設定を作る */
    @JvmStatic
    public fun machineSaveConfig(values: ConfigValues): MachineSaveConfig = MachineSaveConfig(
        autosavePeriodTicks = maxOf(
            values[AUTOSAVE_PERIOD_TICKS].toLong(),
            values[AUTOSAVE_QUIESCE_TICKS].toLong() + AUTOSAVE_PERIOD_MARGIN_TICKS,
        ),
        autosaveQuiesceTicks = values[AUTOSAVE_QUIESCE_TICKS],
        autosaveWritesPerTick = values[AUTOSAVE_WRITES_PER_TICK],
        unloadWaitBudgetMillis = values[UNLOAD_WAIT_BUDGET_MILLIS].toLong(),
        stopQuiesceMillis = values[STOP_QUIESCE_MILLIS].toLong(),
        fullSaveChangedRatio = values[FULL_SAVE_CHANGED_RATIO],
        writerMaxPendingBytes = values[WRITER_MAX_PENDING_MIB] * MIB,
        writerFlushMillis = values[WRITER_FLUSH_MILLIS].toLong(),
        restoresPerTick = values[RESTORES_PER_TICK],
        restoreWriteWaitMillis = values[RESTORE_WRITE_WAIT_MILLIS].toLong(),
    )

    /** [values] から画面の同期の送信量の勘定を作る（作り直すと、プレイヤーごとの勘定は空に戻る） */
    @JvmStatic
    public fun <P : Any> screenSyncTraffic(values: ConfigValues): ScreenSyncTraffic<P> =
        ScreenSyncTraffic(values[SCREEN_PLAYER_BYTES_PER_SECOND], values[SCREEN_SERVER_BYTES_PER_SECOND])

    /** [values] から、キーボード1つの入力の予算を作る */
    @JvmStatic
    public fun keyboardInputBudget(values: ConfigValues): KeyboardInputBudget =
        KeyboardInputBudget(values[KEYBOARD_INPUT_CAPACITY], values[KEYBOARD_INPUT_REFILL_PER_TICK])

    /**
     * [values] に合うキーボードの入力の予算。[existing]（使いかけの予算）が今の値（ためておける量・戻る量）と同じならそれを返し、
     * 違えば作り直す（使った分は戻る）。使いかけの予算は満タンに戻るまで残るので、値を変えた後に開いたキーボードにも、
     * 古い値の予算が残らないようにする
     */
    @JvmStatic
    public fun keyboardInputBudget(values: ConfigValues, existing: KeyboardInputBudget?): KeyboardInputBudget {
        val capacity = values[KEYBOARD_INPUT_CAPACITY]
        val refill = values[KEYBOARD_INPUT_REFILL_PER_TICK]
        return if (existing != null && existing.capacity == capacity && existing.refillPerTick == refill) {
            existing
        } else {
            KeyboardInputBudget(capacity, refill)
        }
    }
}
