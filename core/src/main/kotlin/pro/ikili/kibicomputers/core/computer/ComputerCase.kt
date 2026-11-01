package pro.ikili.kibicomputers.core.computer

import org.jetbrains.annotations.ApiStatus
import pro.ikili.kibicomputers.core.kernel.BootFailure
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.MachineConfig
import pro.ikili.kibicomputers.core.kernel.MachineHost
import pro.ikili.kibicomputers.core.kernel.MachineState
import pro.ikili.kibicomputers.core.machine.MachineManager
import pro.ikili.kibicomputers.core.machine.MachineSlot

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
}

/**
 * ケースブロック1つの電源と状態（#121）。電源ボタン・部品の抜き差し・電力の有無を、マシンの起動・停止に変える。
 *
 * - 電源を入れる（[turnOn]）と、起動に要る部品（[CaseTier.missingRequired]）がそろい、電力があれば、
 *   [MachineManager] にマシンを作る（[MachineSlot]）。そろっていなければ [ComputerStatus.ERROR]、電力がなければ
 *   [ComputerStatus.NO_POWER] にして、マシンは作らない
 * - 動いている間に起動に要る部品が抜けた（[partsChanged]）・電力が尽きた（[powerLost]）ら、マシンを止める
 * - マシンが [MachineState.CRASHED] になったら [ComputerStatus.ERROR]（[ComputerError.CRASHED]）
 * - ブロックエンティティの `onChunkUnloaded`・`setRemoved` で [chunkUnloaded]・[removed] を呼ぶ（[MachineSlot] と同じ）。
 *   どちらも状態を [ComputerStatus.OFF] にする
 *
 * 電源の状態はセーブデータに書かない（読み込んだら [ComputerStatus.OFF]。マシンの状態の保存は #81）。
 *
 * スレッド: メインスレッドから呼ぶ（マシンの通知もメインスレッドで届く）。
 *
 * @param K マシンのキー（[MachineManager] と同じ）
 * @property tier ケースのティア
 * @param events マシンからの通知（状態、コンソールの出力、ビープ、起動の失敗、再起動）をそのまま受け取る相手。
 *   ケース自身の処理（CRASHED でエラーにする）の後に呼ぶ。`null` なら渡さない（再起動の求めは、既定どおり前回の設定で起動し直す）
 */
@ApiStatus.Internal
public class ComputerCase<K : Any> @JvmOverloads constructor(
    public val tier: CaseTier,
    private val events: MachineHost? = null,
) {
    private val slot = MachineSlot<K>()

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

    private val host = object : MachineHost {
        override fun onStateChanged(machine: Machine, state: MachineState, cause: Throwable?) {
            onMachineStateChanged(machine, state)
            events?.onStateChanged(machine, state, cause)
        }

        override fun onConsoleOutput(machine: Machine, bytes: ByteArray) {
            events?.onConsoleOutput(machine, bytes)
        }

        override fun onBeep(machine: Machine, frequencyHz: Int, durationMillis: Int) {
            events?.onBeep(machine, frequencyHz, durationMillis)
        }

        override fun onBootFailed(machine: Machine, failure: BootFailure, detail: String) {
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
    public fun pressPowerButton(
        manager: MachineManager<K>?,
        key: K,
        parts: InstalledParts,
        hasPower: Boolean,
        config: MachineConfig,
    ): ComputerStatus =
        if (status == ComputerStatus.RUNNING) {
            turnOff()
            status
        } else {
            turnOn(manager, key, parts, hasPower, config)
        }

    /**
     * 電源を入れる。もう動いていれば何もしない。
     *
     * @param manager マシンを作る先。`null`（サーバーが動いていない）なら [ComputerError.UNAVAILABLE]
     * @param key このケースのマシンのキー
     * @param parts 部品の挿さり具合
     * @param hasPower 起動に足りる電力があるか（#128。今は接続層が常に `true` を渡す）
     * @param config 作るマシンの設定（CPU・RAM で変わる。#122・#123）
     * @return 入れた後の状態
     */
    public fun turnOn(
        manager: MachineManager<K>?,
        key: K,
        parts: InstalledParts,
        hasPower: Boolean,
        config: MachineConfig,
    ): ComputerStatus {
        if (status == ComputerStatus.RUNNING) return status
        val missing = tier.missingRequired(parts)
        when {
            missing.isNotEmpty() -> fail(ComputerError.MISSING_PARTS, missing)
            !hasPower -> set(ComputerStatus.NO_POWER)
            manager == null -> fail(ComputerError.UNAVAILABLE)
            slot.load(manager, key, host, config) == null -> fail(ComputerError.UNAVAILABLE)
            else -> set(ComputerStatus.RUNNING)
        }
        return status
    }

    /** 電源を切る。マシンを止めて [ComputerStatus.OFF] にする（エラーの表示も消す）。 */
    public fun turnOff() {
        slot.removed()
        set(ComputerStatus.OFF)
    }

    /**
     * 部品が挿された・抜かれた。動いている間に起動に要る部品がなくなったら、マシンを止めて
     * [ComputerError.MISSING_PARTS] にする。動いていなければ何もしない（足りない部品の表示も変えない）。
     *
     * @return マシンを止めたら `true`
     */
    public fun partsChanged(parts: InstalledParts): Boolean {
        if (status != ComputerStatus.RUNNING) return false
        val missing = tier.missingRequired(parts)
        if (missing.isEmpty()) return false
        slot.removed()
        fail(ComputerError.MISSING_PARTS, missing)
        return true
    }

    /**
     * 電力が尽きた（#128）。動いていればマシンを止めて [ComputerStatus.NO_POWER] にする（実行中の処理は失われる）。
     *
     * @return マシンを止めたら `true`
     */
    public fun powerLost(): Boolean {
        if (status != ComputerStatus.RUNNING) return false
        slot.removed()
        set(ComputerStatus.NO_POWER)
        return true
    }

    /** チャンクがアンロードされる。マシンを外して [ComputerStatus.OFF] にする（休止は #132）。 */
    public fun chunkUnloaded() {
        slot.chunkUnloaded()
        set(ComputerStatus.OFF)
    }

    /** ブロックエンティティが消える。マシンを外して [ComputerStatus.OFF] にする。 */
    public fun removed() {
        slot.removed()
        set(ComputerStatus.OFF)
    }

    /** マシンの状態が変わった（[MachineHost.onStateChanged]）。今のマシンが CRASHED になったらエラーにする */
    internal fun onMachineStateChanged(machine: Machine, state: MachineState) {
        if (state != MachineState.CRASHED || machine !== slot.machine) return
        slot.removed()
        fail(ComputerError.CRASHED)
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
