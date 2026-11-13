package pro.ikili.kibicomputers.core.computer

import org.jetbrains.annotations.ApiStatus
import pro.ikili.kibicomputers.core.kernel.BootConfig
import pro.ikili.kibicomputers.core.kernel.BootFailure
import pro.ikili.kibicomputers.core.kernel.DiskSnapshot
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.MachineConfig
import pro.ikili.kibicomputers.core.kernel.MachineEnvironment
import pro.ikili.kibicomputers.core.kernel.MachineHost
import pro.ikili.kibicomputers.core.kernel.MachineState
import pro.ikili.kibicomputers.core.machine.MachineManager
import pro.ikili.kibicomputers.core.machine.MachineSlot
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.core.persist.DeltaBase
import pro.ikili.kibicomputers.core.persist.MachineSaveConfig
import pro.ikili.kibicomputers.core.persist.MachineSnapshotSource
import pro.ikili.kibicomputers.core.persist.RestoreListener
import pro.ikili.kibicomputers.core.persist.RestoreOutcome
import pro.ikili.kibicomputers.core.persist.RestoreRequest
import pro.ikili.kibicomputers.core.persist.RestoreSource
import pro.ikili.kibicomputers.core.persist.SnapshotCapturer
import pro.ikili.kibicomputers.core.persist.SaveReadiness

/** コンピューター（ケース）の状態。GUI の状態表示に出す（#121）。 */
@ApiStatus.Internal
public enum class ComputerStatus {
    /** 電源が切れている */
    OFF,

    /** 動いている */
    RUNNING,

    /** 起動できない・止まった（理由は [ComputerError]） */
    ERROR,

    /** 電力が足りずに止まった（#128） */
    NO_POWER,
}

/** [ComputerStatus.ERROR] の理由。 */
@ApiStatus.Internal
public enum class ComputerError {
    /** 起動に要る部品が挿さっていない（どれかは [ComputerCase.missingParts]） */
    MISSING_PARTS,

    /** カーネルの不整合でマシンが止まった */
    CRASHED,

    /** マシンを作れなかった（サーバーの停止の途中など） */
    UNAVAILABLE,

    /** 起動に失敗した（起動できるディスクがない・init を実行できないなど。理由の詳細は画面のコンソールに出る。#641） */
    BOOT_FAILED,
}

/**
 * ケースブロック1つの電源と状態（#121）。電源ボタン・部品の抜き差し・電力の有無を、マシンの起動・停止に変える。
 *
 * - 電源を入れる（[turnOn]）と、起動に要る部品（[CaseTier.missingRequired]）がそろい、電力があれば、
 *   [MachineManager] にマシンを作る（[MachineSlot]）。そろっていなければ [ComputerStatus.ERROR]、電力がなければ
 *   [ComputerStatus.NO_POWER] にして、マシンは作らない
 * - 動いている間に起動に要る部品が抜けた・電源を入れたときにあったディスクが抜けた（[partsChanged]）・
 *   電力が尽きた（[powerLost]）ら、マシンを止める
 * - マシンが [MachineState.CRASHED] になったら [ComputerStatus.ERROR]（[ComputerError.CRASHED]）
 * - ゲストが止めた（再起動の途中でない [MachineState.STOPPED]。`poweroff`・init の終了）ら、[turnOff] と同じく
 *   マシンを外して [ComputerStatus.OFF] にする（#640）
 * - 起動に失敗した（[MachineHost.onBootFailed]。再起動・復元の後の起動を含む）ら、マシンを外して [ComputerStatus.ERROR]
 *   （[ComputerError.BOOT_FAILED]）にする（#641）
 * - ブロックエンティティの `onChunkUnloaded`・`setRemoved` で [chunkUnloaded]・[removed] を呼ぶ（[MachineSlot] と同じ）。
 *   どちらも状態を [ComputerStatus.OFF] にする
 *
 * 電源の状態（[poweredOn]）は、接続層がセーブデータ（`PoweredOn`）に書き、読み込んだら [loadPoweredOn] で戻す（#85、ADR 0085）。
 * 入っていたら、チャンクの読み込みで [beginRestore] → [startRestore] で保存した状態に戻す（戻せなければ冷えた起動）。
 * [poweredOn] が変わる出来事（ADR 0085 の表）:
 *
 * | 出来事 | [poweredOn] |
 * | --- | --- |
 * | [turnOn] でマシンを作った・[startRestore] でマシンを作った | `true` |
 * | [turnOff]・[partsChanged] で止めた・[powerLost] で止めた | `false` |
 * | マシンが CRASHED になった・ゲストが止めた（再起動の途中でない STOPPED）・起動に失敗した | `false` |
 * | 部品が足りない・電力がないので [turnOn]・[startRestore] で作れなかった | `false` |
 * | [chunkUnloaded]・[removed]・マシンを作れなかった（サーバーの停止の途中など） | 変えない |
 *
 * スレッド: メインスレッドから呼ぶ（マシンの通知もメインスレッドで届く）。
 *
 * @param K マシンのキー（[MachineManager] と同じ）
 * @property tier ケースのティア
 * @param events マシンからの通知（状態、コンソールの出力、起動の失敗、再起動）をそのまま受け取る相手。
 *   ケース自身の処理（CRASHED・起動の失敗でエラーにする、ゲストが止めたら外す）の後に呼ぶ。`null` なら渡さない（再起動の求めは、既定どおり前回の設定で起動し直す）
 */
@ApiStatus.Internal
public class ComputerCase<K : Any> @JvmOverloads constructor(
    public val tier: CaseTier,
    private val events: MachineHost? = null,
    private val poweredOnChanged: Runnable? = null,
) {
    private val slot = MachineSlot<K>()

    /**
     * 電源が入っている（#85。接続層がセーブデータに書く）。マシンがなくても（チャンクのアンロードの後・復元の順番待ち）`true` のことがある。
     * 変わったら、コンストラクターの `poweredOnChanged` を呼ぶ（[loadPoweredOn] では呼ばない）
     */
    public var poweredOn: Boolean = false
        private set

    /** チャンクの読み込みで復元を待っている（[beginRestore] から [startRestore] まで） */
    public var isRestorePending: Boolean = false
        private set

    /** 今のマシンが復元の途中（[Machine.isRestoring]） */
    public val isRestoring: Boolean get() = slot.machine?.isRestoring == true

    /** 今の状態 */
    public var status: ComputerStatus = ComputerStatus.OFF
        private set

    /** [status] が [ComputerStatus.ERROR] のときの理由。ほかの状態では `null` */
    public var error: ComputerError? = null
        private set

    /** [ComputerError.MISSING_PARTS] のときに足りない部品の種類（[PartKind] の順）。ほかのときは空 */
    public var missingParts: List<PartKind> = emptyList()
        private set

    /** 動いているマシン。動いていなければ `null` */
    public val machine: Machine? get() = slot.machine

    /**
     * このケースがマシンに渡す [MachineHost]。ケース自身の処理（CRASHED・起動の失敗でエラーにする・ゲストが止めたら外す）をしてから、
     * 渡された `events` に中継する。接続層のテストが、マシンを実際に壊さずに通知の経路を確かめるために公開している（#640）
     */
    public val host: MachineHost = object : MachineHost {
        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) {
            onMachineStateChanged(machine, state)
            events?.onStateChanged(machine, state, cause)
        }

        override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
            events?.onConsoleOutput(machine, bytes)
        }

        override fun onBootFailed(machine: Machine, failure: BootFailure, detail: String) {
            onMachineBootFailed(machine)
            events?.onBootFailed(machine, failure, detail)
        }

        override fun onRebootRequested(machine: Machine) {
            val e = events
            if (e != null) e.onRebootRequested(machine) else super.onRebootRequested(machine)
        }
    }

    /**
     * 電源ボタン: 動いていれば [turnOff]、そうでなければ [turnOn]。
     *
     * @return 押した後の状態
     */
    @JvmOverloads
    public fun pressPowerButton(
        manager: MachineManager<K>?,
        key: K,
        parts: InstalledParts,
        hasPower: Boolean,
        config: MachineConfig,
        environment: MachineEnvironment = MachineEnvironment.DEFAULT,
    ): ComputerStatus =
        if (status == ComputerStatus.RUNNING) {
            turnOff()
            status
        } else {
            turnOn(manager, key, parts, hasPower, config, environment)
        }

    /**
     * 電源を入れる。もう動いていれば何もしない。
     *
     * @param manager マシンを作る先。`null`（サーバーが動いていない）なら [ComputerError.UNAVAILABLE]
     * @param key このケースのマシンのキー
     * @param parts 部品の挿さり具合
     * @param hasPower 起動に足りる電力があるか（#128。今は接続層が常に `true` を渡す）
     * @param config 作るマシンの設定（CPU・RAM で変わる。#122・#123）
     * @param environment 作るマシンの環境（挿さっているディスクの写しなど。#124）
     * @return 入れた後の状態
     */
    @JvmOverloads
    public fun turnOn(
        manager: MachineManager<K>?,
        key: K,
        parts: InstalledParts,
        hasPower: Boolean,
        config: MachineConfig,
        environment: MachineEnvironment = MachineEnvironment.DEFAULT,
    ): ComputerStatus {
        if (status == ComputerStatus.RUNNING) return status
        isRestorePending = false
        if (load(manager, key, parts, hasPower, config, environment) != null) setPoweredOn(true)
        return status
    }

    /** [turnOn]・[startRestore] の共通: 部品・電力・マシンを確かめてマシンを作る。作れなければ状態をエラーなどにして `null` */
    private fun load(
        manager: MachineManager<K>?,
        key: K,
        parts: InstalledParts,
        hasPower: Boolean,
        config: MachineConfig,
        environment: MachineEnvironment,
    ): Machine? {
        val missing = tier.missingRequired(parts)
        when {
            missing.isNotEmpty() -> {
                fail(ComputerError.MISSING_PARTS, missing)
                setPoweredOn(false)
            }
            !hasPower -> {
                set(ComputerStatus.NO_POWER)
                setPoweredOn(false)
            }
            manager == null -> fail(ComputerError.UNAVAILABLE)
            else -> {
                val machine = slot.load(manager, key, host, config, environment)
                if (machine == null) {
                    fail(ComputerError.UNAVAILABLE)
                } else {
                    set(ComputerStatus.RUNNING)
                    return machine
                }
            }
        }
        return null
    }

    /** セーブデータから読んだ電源の状態を入れる（#85。マシンがないときだけ。`poweredOnChanged` は呼ばない） */
    public fun loadPoweredOn(value: Boolean) {
        if (slot.machine != null || isRestorePending) return
        poweredOn = value
    }

    /**
     * チャンクの読み込み（#85）: 電源が入っていてマシンがなければ、復元の順番待ちにして [ComputerStatus.RUNNING] を見せる
     * （順番が来たら [startRestore]）。2回呼んでもよい。
     *
     * @return 順番待ちにした（もう順番待ちなら `true`）
     */
    public fun beginRestore(): Boolean {
        if (isRestorePending) return true
        if (!poweredOn || slot.machine != null) return false
        isRestorePending = true
        set(ComputerStatus.RUNNING)
        return true
    }

    /**
     * 復元を始める（#85、ADR 0085）。[beginRestore] で順番待ちでなければ何もしない。部品・電力・マシンを [turnOn] と同じに確かめて
     * マシンを作り、[source] があれば [Machine.restore] で戻す。終わったら（戻せた・冷えた起動にする）メインスレッドで、まだこのケースの
     * 今のマシンなら [onReady] を呼び（デバイスをつなぐ）、戻せたらまだ実行していないデバイスの呼び出しを回し直し、冷えた起動なら
     * [Machine.coldBootAfterRestore] を呼ぶ。[source] がない（ID がない・新しい ID・書き込みが終わらない）か、受け付けられなければ、
     * [onReady] の後に [bootConfig] で起動する。
     *
     * @return 始めた後の状態
     */
    @JvmOverloads
    public fun startRestore(
        manager: MachineManager<K>?,
        key: K,
        parts: InstalledParts,
        hasPower: Boolean,
        config: MachineConfig,
        environment: MachineEnvironment,
        bootConfig: BootConfig,
        source: RestoreSource?,
        onReady: RestoreReady,
        finished: RestoreListener? = null,
    ): ComputerStatus {
        if (!isRestorePending) return status
        isRestorePending = false
        val machine = load(manager, key, parts, hasPower, config, environment) ?: return status
        setPoweredOn(true)
        val listener = RestoreListener { m, outcome, cause ->
            // 知らせは close の後も届く: 今のマシンでなければ（電源を切った・アンロードした）何もしない（ADR 0085）
            if (m === slot.machine) {
                when (outcome) {
                    RestoreOutcome.RESTORED -> {
                        onReady.onReady(m)
                        m.redispatchRestoredDeviceCalls()
                    }
                    RestoreOutcome.COLD_BOOT -> {
                        onReady.onReady(m)
                        m.coldBootAfterRestore()
                    }
                    RestoreOutcome.CANCELLED -> {}
                }
            }
            finished?.onRestoreFinished(m, outcome, cause)
        }
        if (source == null || !machine.restore(RestoreRequest(source, bootConfig, listener))) {
            onReady.onReady(machine)
            machine.boot(bootConfig)
        }
        return status
    }

    /** 電源を切る。マシンを止めて [ComputerStatus.OFF] にする（エラーの表示も消す）。 */
    public fun turnOff() {
        isRestorePending = false
        slot.removed()
        set(ComputerStatus.OFF)
        setPoweredOn(false)
    }

    /**
     * 部品が挿された・抜かれた（#56）。[disks] は今挿さっているディスク（[DiskStore.snapshot]）。動いている間なら、マシンに今の表を渡す
     * （[Machine.disksChanged]。挿したディスクは `/mnt` に自動でマウントし、抜いたディスクは外す）。
     * 起動に要る部品がなくなった、またはマシンが止めるべきと答えた（`/`・`/home` のディスク、起動・復元の途中ならそれが頼るディスクが抜けた）
     * なら、マシンを止めて [ComputerError.MISSING_PARTS] にする。ディスクで止めたときは、ほかのディスクが残っていても [missingParts] に
     * [PartKind.STORAGE] を入れる。同じディスクを別のディスクのスロットに挿し直したとき（識別子が残る）は止めない。
     * マシンがない（復元の順番待ち）ときは部品だけを見る。動いていなければ何もしない（足りない部品の表示も変えない）。
     *
     * @return マシンを止めたら `true`
     */
    public fun partsChanged(parts: InstalledParts, disks: DiskSnapshot): Boolean {
        if (status != ComputerStatus.RUNNING) return false
        val missing = tier.missingRequired(parts)
        val diskGone = slot.machine?.disksChanged(disks) == true
        if (missing.isEmpty() && !diskGone) return false
        isRestorePending = false
        slot.removed()
        val kinds = if (diskGone) PartKind.entries.filter { it in missing || it == PartKind.STORAGE } else missing
        fail(ComputerError.MISSING_PARTS, kinds)
        setPoweredOn(false)
        return true
    }

    /**
     * 電力が尽きた（#128）。動いていればマシンを止めて [ComputerStatus.NO_POWER] にする（実行中の処理は失われる）。
     *
     * @return マシンを止めたら `true`
     */
    public fun powerLost(): Boolean {
        if (status != ComputerStatus.RUNNING) return false
        isRestorePending = false
        slot.removed()
        set(ComputerStatus.NO_POWER)
        setPoweredOn(false)
        return true
    }

    /** チャンクのアンロードの前に、マシンを止める（[MachineSlot.prepareUnload]。#81）。マシンがあれば `true` */
    public fun prepareUnload(): Boolean = slot.prepareUnload()

    /** [prepareUnload] で止めたのをやめて続ける（[MachineSlot.endQuiesce]） */
    public fun endQuiesce(): Unit = slot.endQuiesce()

    /** マシンの今の判定。マシンがなければ `null` */
    public fun saveReadiness(): SaveReadiness? = slot.readiness()

    /** マシンの保存の判定が、待てば READY に近づくか（[MachineSlot.isSettlingForSave]）。マシンがなければ `false` */
    public val isSettlingForSave: Boolean get() = slot.isSettlingForSave

    /** 止めている間に、もう取り出した（[MachineSlot.isCaptured]） */
    public val isCaptured: Boolean get() = slot.isCaptured

    /** マシンの写しを取る（[MachineSlot.capture]）。マシンがなければ `null` */
    @JvmOverloads
    public fun capture(
        policy: CapturePolicy,
        deltaBase: DeltaBase? = null,
        fullSaveChangedRatio: Double = MachineSaveConfig.DEFAULT_FULL_SAVE_CHANGED_RATIO,
    ): MachineSnapshotSource? = slot.capture(policy, deltaBase, fullSaveChangedRatio)

    /** 全体の写しを取り直す（[MachineSlot.recapture]。#83）。マシンがなければ `null` */
    public fun recapture(policy: CapturePolicy): MachineSnapshotSource? = slot.recapture(policy)

    /**
     * 置き場への書き込みの取り出し口（#83。[pro.ikili.kibicomputers.core.persist.MachineSaveStore.captureAndSubmit] に渡す）。
     * 最初は [policy] と基準で取り、取り直しは [recapture]
     */
    public fun capturer(policy: CapturePolicy, fullSaveChangedRatio: Double): SnapshotCapturer = SnapshotCapturer { deltaBase, retry ->
        if (retry) recapture(policy) else capture(policy, deltaBase, fullSaveChangedRatio)
    }

    /** チャンクがアンロードされる。マシンを外して [ComputerStatus.OFF] にする（休止は #132）。 */
    public fun chunkUnloaded() {
        isRestorePending = false
        slot.chunkUnloaded()
        set(ComputerStatus.OFF)
    }

    /** ブロックエンティティが消える。マシンを外して [ComputerStatus.OFF] にする。 */
    public fun removed() {
        isRestorePending = false
        slot.removed()
        set(ComputerStatus.OFF)
    }

    /**
     * マシンの状態が変わった（[MachineHost.onStateChanged]）。今のマシンが CRASHED になったらエラーにして電源を切り、
     * ゲストが止めた（再起動の途中でも起動の途中でもない STOPPED）なら、[turnOff] と同じくマシンを外して
     * [ComputerStatus.OFF] にする（#85・#640）。外したマシンは閉じる（[MachineSlot.removed]）
     */
    internal fun onMachineStateChanged(machine: Machine, state: MachineState) {
        if (machine !== slot.machine) return
        when (state) {
            MachineState.CRASHED -> {
                slot.removed()
                fail(ComputerError.CRASHED)
                setPoweredOn(false)
            }
            MachineState.STOPPED -> if (!machine.isRebootPending && !machine.isBooting) turnOff()
            else -> {}
        }
    }

    /**
     * 起動に失敗した（[MachineHost.onBootFailed]。#641）。今のマシンなら、CRASHED と同じくマシンを外して
     * [ComputerError.BOOT_FAILED] にし、電源を切る。起動の失敗では状態の通知が出ない（ADR 0049）ので、ここで拾う。
     * 知らせが届くまでに同じマシンがもう起動し直していたら（動いている・起動の途中）、その起動を巻き込まない
     */
    internal fun onMachineBootFailed(machine: Machine) {
        if (machine !== slot.machine || machine.isRunningOrBooting) return
        isRestorePending = false
        slot.removed()
        fail(ComputerError.BOOT_FAILED)
        setPoweredOn(false)
    }

    private fun setPoweredOn(value: Boolean) {
        if (poweredOn == value) return
        poweredOn = value
        poweredOnChanged?.run()
    }

    private fun set(newStatus: ComputerStatus) {
        status = newStatus
        error = null
        missingParts = emptyList()
    }

    private fun fail(reason: ComputerError, missing: List<PartKind> = emptyList()) {
        status = ComputerStatus.ERROR
        error = reason
        missingParts = missing
    }

    override fun toString(): String = "ComputerCase($tier, $status${error?.let { ", $it" } ?: ""})"
}

/** [ComputerCase.startRestore] の、マシンの用意ができた知らせ（デバイスをつなぐ。#85） */
@ApiStatus.Internal
public fun interface RestoreReady {
    /** [machine] にデバイスをつなぐ（メインスレッドで。この後に、戻したマシンが続くか、冷えた起動を始める） */
    public fun onReady(machine: Machine)
}
