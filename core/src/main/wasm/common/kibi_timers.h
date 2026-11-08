// 独自拡張 kibi:kernel/timers（#426。WIT は core/src/main/wit/kibi-kernel/timers.wit、ABI は docs/adr/0426-kibi-timers-clock.md）を、
// WASI p1 のプログラムから呼ぶための手書きの宣言（wit-bindgen で作る束縛は SDK #116）。
//
// timer のハンドルはカーネルの fd 番号。鳴った回数が 1 以上の間、poll_oneoff（poll）の FD_READ（POLLIN）で待てる。
// 時間はナノ秒で、時計は clock_time_get(CLOCK_MONOTONIC) と同じ。
// 構造体の配置は Canonical ABI と同じ。どれも可変長の結果がないので、cabi_realloc は要らない。
#ifndef KIBI_TIMERS_H
#define KIBI_TIMERS_H

#include <stddef.h>
#include <stdint.h>

// kibi:kernel/types の error-code。p1 の errno = 判別子 + 1（kibi_events.h と同じ型。C11 から typedef は重ねてよい）
typedef uint8_t kibi_error_code_t;

// result<timer, error-code>（8 バイト、align 4）。is_err が 0 なら val.handle（= fd）
typedef struct {
    uint8_t is_err;
    union {
        int32_t handle;
        kibi_error_code_t err;
    } val;
} kibi_result_timer_t;

// result<_, error-code>（2 バイト、align 1）。is_err が 0 なら成功（kibi_io.h と共通。先に定義した方を使う）
#ifndef KIBI_RESULT_UNIT_DEFINED
#define KIBI_RESULT_UNIT_DEFINED
typedef struct {
    uint8_t is_err;
    kibi_error_code_t err;
} kibi_result_unit_t;
#endif

// result<u64, error-code>（16 バイト、align 8）。is_err が 0 なら val.ok
typedef struct {
    uint8_t is_err;
    union {
        uint64_t ok;
        kibi_error_code_t err;
    } val;
} kibi_result_u64_t;

_Static_assert(sizeof(kibi_result_timer_t) == 8 && _Alignof(kibi_result_timer_t) == 4, "result<timer>");
_Static_assert(offsetof(kibi_result_timer_t, val) == 4, "result<timer> payload");
_Static_assert(sizeof(kibi_result_unit_t) == 2 && _Alignof(kibi_result_unit_t) == 1, "result<_>");
_Static_assert(offsetof(kibi_result_unit_t, err) == 1, "result<_> err");
_Static_assert(sizeof(kibi_result_u64_t) == 16 && _Alignof(kibi_result_u64_t) == 8, "result<u64>");
_Static_assert(offsetof(kibi_result_u64_t, val) == 8, "result<u64> payload");

// [static]timer.create(events: option<tuple<borrow<event-queue>, s64>>) -> result<timer, error-code>
// events_is_some が 0 なら queue・tag は読まれない。1 なら鳴るたびに queue へ "timer" のイベント（値は integer(tag)・integer(回数)）を入れる
__attribute__((import_module("kibi:kernel/timers@0.1.0"), import_name("[static]timer.create")))
void kibi_timer_create_raw(int32_t events_is_some, int32_t queue, int64_t tag, kibi_result_timer_t *ret);

// [method]timer.set-after(self, duration: u64, interval: u64) -> result<_, error-code>
__attribute__((import_module("kibi:kernel/timers@0.1.0"), import_name("[method]timer.set-after")))
void kibi_timer_set_after_raw(int32_t self, uint64_t duration, uint64_t interval, kibi_result_unit_t *ret);

// [method]timer.set-at(self, instant: u64, interval: u64) -> result<_, error-code>
__attribute__((import_module("kibi:kernel/timers@0.1.0"), import_name("[method]timer.set-at")))
void kibi_timer_set_at_raw(int32_t self, uint64_t instant, uint64_t interval, kibi_result_unit_t *ret);

// [method]timer.cancel(self) -> result<_, error-code>
__attribute__((import_module("kibi:kernel/timers@0.1.0"), import_name("[method]timer.cancel")))
void kibi_timer_cancel_raw(int32_t self, kibi_result_unit_t *ret);

// [method]timer.read(self) -> result<u64, error-code>
__attribute__((import_module("kibi:kernel/timers@0.1.0"), import_name("[method]timer.read")))
void kibi_timer_read_raw(int32_t self, kibi_result_u64_t *ret);

// [resource-drop]timer（fd_close と同じく閉じる。不正なハンドルならトラップ）
__attribute__((import_module("kibi:kernel/timers@0.1.0"), import_name("[resource-drop]timer")))
void kibi_timer_drop(int32_t self);

#endif
