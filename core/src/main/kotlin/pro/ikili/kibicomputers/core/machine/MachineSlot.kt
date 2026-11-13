package pro.ikili.kibicomputers.core.machine

import org.jetbrains.annotations.ApiStatus
import pro.ikili.kibicomputers.core.kernel.Machine
import pro.ikili.kibicomputers.core.kernel.MachineConfig
import pro.ikili.kibicomputers.core.kernel.MachineEnvironment
import pro.ikili.kibicomputers.core.kernel.MachineHost
import pro.ikili.kibicomputers.core.persist.CapturePolicy
import pro.ikili.kibicomputers.core.persist.DeltaBase
import pro.ikili.kibicomputers.core.persist.MachineSaveConfig
import pro.ikili.kibicomputers.core.persist.MachineSnapshotSource
import pro.ikili.kibicomputers.core.persist.SaveReadiness

/**
 * ブロックエンティティ1つが持つ、マシンの席（#120）。ブロックエンティティの出来事を [MachineManager] の操作に変える。
 *
 * 接続層のブロックエンティティ（サーバー側）は、次のように呼ぶ。
 *
 * | ブロックエンティティ | 呼ぶもの |
 * | --- | --- |
 * | `onLoad`（置かれた・チャンクが読み込まれた） | [load] |
 * | `onChunkUnloaded`（チャンクのアンロード。`setRemoved` の前に呼ばれる） | [chunkUnloaded] |
 * | `setRemoved`（壊された・置き換えられた・アンロード） | [removed] |
 *
 * 外すときは「自分が作ったマシン」のときだけ外す（[MachineManager.release] の `machine` つき）ので、
 * 同じ場所に新しいブロックエンティティが先にマシンを作っていても、それを止めない。
 *
 * スレッド: メインスレッドから呼ぶ。
 */
@ApiStatus.Internal
public class MachineSlot<K : Any> {
    private var manager: MachineManager<K>? = null
    private var key: K? = null
    private var current: Machine? = null

    /** 今持っているマシン。[load] の前・外した後は `null` */
    public val machine: Machine? get() = current

    /**
     * [manager] に [key] のマシンを作る。もう持っていれば、それを外してから作る。
     * [manager] が閉じていれば何もせず `null` を返す（サーバーの停止の途中など）。
     *
     * @return 作ったマシン
     */
    @JvmOverloads
    public fun load(
        manager: MachineManager<K>,
        key: K,
        host: MachineHost,
        config: MachineConfig,
        environment: MachineEnvironment = MachineEnvironment.DEFAULT,
    ): Machine? {
        release(MachineReleaseReason.REMOVED)
        if (manager.isClosed) return null
        val machine = try {
            manager.attach(key, host, config, environment)
        } catch (e: IllegalStateException) {
            // 確かめた後に閉じた
            return null
        }
        this.manager = manager
        this.key = key
        current = machine
        return machine
    }

    /**
     * チャンクのアンロードの前の取り出しの用意: マシンを止める（[MachineManager.beginQuiesce]）。
     * 続けて [readiness] で待ち、[capture] で取り出し、[chunkUnloaded] で外す（#81）
     *
     * @return マシンがあれば `true`
     */
    public fun prepareUnload(): Boolean {
        val manager = manager ?: return false
        val key = key ?: return false
        return manager.beginQuiesce(key)
    }

    /** [prepareUnload] で止めたのをやめて続ける（取り出し済みの印も消す） */
    public fun endQuiesce() {
        val manager = manager ?: return
        val key = key ?: return
        manager.endQuiesce(key)
    }

    /** 今の判定。マシンがなければ `null` */
    public fun readiness(): SaveReadiness? = current?.saveReadiness()

    /** マシンの保存の判定が、待てば READY に近づくか（[Machine.isSettlingForSave]）。マシンがなければ `false` */
    public val isSettlingForSave: Boolean get() = current?.isSettlingForSave == true

    /** 止めている間に、もう取り出した */
    public val isCaptured: Boolean get() = key?.let { manager?.isCaptured(it) } == true

    /** マシンの写しを取る（[MachineManager.capture]）。マシンがなければ `null` */
    @JvmOverloads
    public fun capture(
        policy: CapturePolicy,
        deltaBase: DeltaBase? = null,
        fullSaveChangedRatio: Double = MachineSaveConfig.DEFAULT_FULL_SAVE_CHANGED_RATIO,
    ): MachineSnapshotSource? {
        val manager = manager ?: return null
        val key = key ?: return null
        return manager.capture(key, policy, deltaBase, fullSaveChangedRatio)
    }

    /** 全体の写しを取り直す（[MachineManager.recapture]。#83）。マシンがなければ `null` */
    public fun recapture(policy: CapturePolicy): MachineSnapshotSource? {
        val manager = manager ?: return null
        val key = key ?: return null
        return manager.recapture(key, policy)
    }

    /**
     * チャンクがアンロードされる（[MachineReleaseReason.UNLOADED] で外す）。
     *
     * @return 外したら `true`
     */
    public fun chunkUnloaded(): Boolean = release(MachineReleaseReason.UNLOADED)

    /**
     * ブロックエンティティが消える。[chunkUnloaded] の後なら何もしない。そうでなければ壊された・置き換えられたとして
     * [MachineReleaseReason.REMOVED] で外す。
     *
     * @return 外したら `true`
     */
    public fun removed(): Boolean = release(MachineReleaseReason.REMOVED)

    private fun release(reason: MachineReleaseReason): Boolean {
        val manager = manager ?: return false
        val key = key ?: return false
        val machine = current ?: return false
        this.manager = null
        this.key = null
        current = null
        return manager.release(key, machine, reason)
    }

    override fun toString(): String = "MachineSlot(${key ?: "empty"})"
}
