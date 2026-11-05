// 独自拡張 kibi:computer/clock（#426。WIT は core/src/main/wit/kibi-computer/clock.wit、ABI は docs/adr/0426-kibi-timers-clock.md）を、
// WASI p1 のプログラムから呼ぶための宣言。p1 向けの import（ADR 0073 の規則で、モジュール "kibi:computer/clock@0.1.0"）として出している。
//
// 現在時刻と単調時計は clock_time_get（wasi:clocks）を使う。ここにあるのは tick で数える時計だけ。
#ifndef KIBI_CLOCK_H
#define KIBI_CLOCK_H

#include <stdint.h>

// 起動からの経過（tick。起動したときが 0。マシンが動いていた tick だけを数え、保存・復元をまたいで続く）
__attribute__((import_module("kibi:computer/clock@0.1.0"), import_name("uptime-ticks")))
uint64_t kibi_clock_uptime_ticks(void);

// ゲーム内時間（ワールドの tick。ワールドがなければ 0）。保存・復元で飛ぶ
__attribute__((import_module("kibi:computer/clock@0.1.0"), import_name("world-ticks")))
uint64_t kibi_clock_world_ticks(void);

#endif
