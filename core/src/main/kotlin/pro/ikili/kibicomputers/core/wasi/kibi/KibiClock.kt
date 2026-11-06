package pro.ikili.kibicomputers.core.wasi.kibi

import pro.ikili.kibicomputers.core.kernel.process.ProcessContext

/**
 * 独自拡張 `kibi:computer/clock`（#426。ADR 0426）の意味。ABI の層に依らない部分。どちらも待たない。
 *
 * - `uptime-ticks`: [uptimeTicks]
 * - `world-ticks`: [worldTicks]
 *
 * 現在時刻・単調時計は `wasi:clocks`（p1 の `clock_time_get`）が受け持ち、ここには置かない。
 */
internal object KibiClock {
    /** 起動からの経過（tick。0 以上。カーネルの `uptimeTicks`） */
    fun uptimeTicks(ctx: ProcessContext): Long = ctx.uptimeTicks()

    /** ゲーム内時間（ワールドの tick。環境の `MachineClock.worldTicks`。負の値は 0 にする） */
    fun worldTicks(ctx: ProcessContext): Long = ctx.environment.clock.worldTicks().coerceAtLeast(0)
}
