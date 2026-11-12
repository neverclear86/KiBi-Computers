// 独自拡張 kibi:computer/power（#306。WIT は core/src/main/wit/kibi-computer/power.wit、ABI は docs/adr/0306-kibi-power.md）を、
// WASI p1 のプログラムから呼ぶための宣言。p1 向けの import（ADR 0073 の規則で、モジュール "kibi:computer/power@0.1.0"）として出している。
//
// どちらも、受け付けたら呼んだプロセスもその場で止まるので戻らない。どのプロセスからでも呼べる。cabi_realloc は要らない。
#ifndef KIBI_POWER_H
#define KIBI_POWER_H

#include <stddef.h>
#include <stdint.h>

// kibi:kernel/types の error-code。p1 の errno = 判別子 + 1（kibi_events.h と同じ型。C11 から typedef は重ねてよい）
typedef uint8_t kibi_error_code_t;

// result<_, error-code>（2 バイト、align 1）。is_err が 0 なら成功（kibi_io.h・kibi_process.h と共通。先に定義した方を使う）
#ifndef KIBI_RESULT_UNIT_DEFINED
#define KIBI_RESULT_UNIT_DEFINED
typedef struct {
    uint8_t is_err;
    kibi_error_code_t err;
} kibi_result_unit_t;
#endif

_Static_assert(sizeof(kibi_result_unit_t) == 2 && offsetof(kibi_result_unit_t, err) == 1, "result<_>");

// kibi:computer/power.shutdown()。マシンを止める。戻らない
__attribute__((import_module("kibi:computer/power@0.1.0"), import_name("shutdown"), noreturn))
void kibi_power_shutdown(void);

// kibi:computer/power.reboot() -> result<_, error-code>。受け付けたら戻らない。
// 戻るのは断ったときだけで、ret に err（not-supported: 起動も復元も通らずに作ったマシン、サーバーを閉じる途中）を書く
__attribute__((import_module("kibi:computer/power@0.1.0"), import_name("reboot")))
void kibi_power_reboot(kibi_result_unit_t *ret);

#endif
