package pro.ikili.kibicomputers.core.kernel.time

import pro.ikili.kibicomputers.core.kernel.MachineClock

/**
 * マシンの時計の状態（`KernelState.clocks`。#64、ADR 0064）。カーネルロックの中で使う。
 *
 * マシンが持つ時計は4つ。
 *
 * | 時計 | 求め方 | 保存・復元をまたぐと |
 * | --- | --- | --- |
 * | 実時間 | 環境の [MachineClock.realtimeNanos] そのまま | 飛ぶ（止まっていた分進む） |
 * | ゲーム内時間 | 環境の [MachineClock.worldTicks] そのまま | 飛ぶ（ワールドが進んだ分） |
 * | 単調時計（[monotonicNanos]） | 環境の [MachineClock.monotonicNanos] + [monotonicOffsetNanos]。マシンを作ったときが 0 | 続く（止まっていた分は進まない） |
 * | 起動からの経過（[uptimeTicks]） | スケジューラの tick の番号 + [uptimeOffsetTicks]。起動したときが 0 | 続く |
 *
 * 単調時計と経過時間は、マシンが動いていた（読み込まれていた）時間だけを数える。タイマーの期限は単調時計で持つので、
 * チャンクが読み込まれていない間に期限が来たタイマーが、戻したときにまとめて鳴ることはない（残りの時間から続く）。
 *
 * 2つのずれ（[monotonicOffsetNanos]・[uptimeOffsetTicks]）は、今の JVM の時計と tick の番号に対する値なので、
 * そのままでは書き出せない（サーバーを起動し直すと起点が変わる）。保存（#81）は [snapshot] を書き出し、戻すときに [restore] する。
 */
internal class KernelClocks {
    /** 単調時計 = 環境の `monotonicNanos()` + これ（ナノ秒） */
    var monotonicOffsetNanos: Long = 0
        private set

    /** 起動からの経過 = スケジューラの tick の番号 + これ（tick） */
    var uptimeOffsetTicks: Long = 0
        private set

    /** マシンを作ったとき: 単調時計と経過時間を 0 から始める */
    fun start(clock: MachineClock, nowTick: Long) {
        monotonicOffsetNanos = -clock.monotonicNanos()
        uptimeOffsetTicks = -nowTick
    }

    /** 起動（再起動を含む）したとき: 経過時間を 0 に戻す。単調時計は戻さない（マシンを作ってから単調に増える） */
    fun resetUptime(nowTick: Long) {
        uptimeOffsetTicks = -nowTick
    }

    /** 単調時計（ナノ秒。0 以上） */
    fun monotonicNanos(clock: MachineClock): Long = saturatingAdd(clock.monotonicNanos(), monotonicOffsetNanos).coerceAtLeast(0)

    /** 起動からの経過（tick。0 以上） */
    fun uptimeTicks(nowTick: Long): Long = saturatingAdd(nowTick, uptimeOffsetTicks).coerceAtLeast(0)

    /** 今の4つの時計の値。保存（#81）はこれを書き出す */
    fun snapshot(clock: MachineClock, nowTick: Long): ClockSnapshot = ClockSnapshot(
        monotonicNanos = monotonicNanos(clock),
        uptimeTicks = uptimeTicks(nowTick),
        realtimeNanos = clock.realtimeNanos(),
        worldTicks = clock.worldTicks(),
    )

    /**
     * [snapshot] の時点から単調時計と経過時間が続くように、ずれを決め直す（復元。#81）。
     * 実時間とゲーム内時間がどれだけ飛んだかを返す（「時間が飛んだ」イベントは #87）
     */
    fun restore(snapshot: ClockSnapshot, clock: MachineClock, nowTick: Long): TimeJump {
        monotonicOffsetNanos = saturatingSub(snapshot.monotonicNanos, clock.monotonicNanos())
        uptimeOffsetTicks = saturatingSub(snapshot.uptimeTicks, nowTick)
        return TimeJump(
            realtimeNanos = saturatingSub(clock.realtimeNanos(), snapshot.realtimeNanos),
            worldTicks = saturatingSub(clock.worldTicks(), snapshot.worldTicks),
        )
    }
}

/** ある時点のマシンの時計の値（保存に書き出す。形は #81・#82） */
internal data class ClockSnapshot(
    /** 単調時計（ナノ秒） */
    val monotonicNanos: Long,
    /** 起動からの経過（tick） */
    val uptimeTicks: Long,
    /** 実時間（UNIX エポックからのナノ秒） */
    val realtimeNanos: Long,
    /** ゲーム内時間（tick） */
    val worldTicks: Long,
) {
    init {
        require(monotonicNanos >= 0) { "単調時計は 0 以上（$monotonicNanos）" }
        require(uptimeTicks >= 0) { "経過時間は 0 以上（$uptimeTicks）" }
    }
}

/** 復元で飛んだ時間（[KernelClocks.restore] の結果）。負のこともある（ホストの時計を戻した、別のワールドに移した） */
internal data class TimeJump(
    /** 実時間の飛び（ナノ秒） */
    val realtimeNanos: Long,
    /** ゲーム内時間の飛び（tick） */
    val worldTicks: Long,
)

internal fun saturatingAdd(a: Long, b: Long): Long {
    val r = a + b
    // 符号が同じ2つを足して符号が変わったらあふれた
    if (((a xor r) and (b xor r)) < 0) return if (a < 0) Long.MIN_VALUE else Long.MAX_VALUE
    return r
}

internal fun saturatingSub(a: Long, b: Long): Long {
    val r = a - b
    if (((a xor b) and (a xor r)) < 0) return if (a < 0) Long.MIN_VALUE else Long.MAX_VALUE
    return r
}

internal fun saturatingMul(a: Long, b: Long): Long {
    if (a == 0L || b == 0L) return 0
    val r = a * b
    if (r / b != a || (a == -1L && b == Long.MIN_VALUE) || (b == -1L && a == Long.MIN_VALUE)) {
        return if ((a < 0) == (b < 0)) Long.MAX_VALUE else Long.MIN_VALUE
    }
    return r
}
