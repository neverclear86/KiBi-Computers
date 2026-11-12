// 独自拡張 kibi:fs/mounts（#56。WIT は core/src/main/wit/kibi-fs/mounts.wit、ABI は docs/adr/0056-disk-hotplug-and-mount.md）を、
// WASI p1 のプログラムから呼ぶための宣言。p1 向けの import（ADR 0073 の規則で、モジュール "kibi:fs/mounts@0.1.0"）として出している。
//
// どちらも待たない。cabi_realloc は要らない。文字列は (ptr, len) で渡す（NUL で終わらなくてよい）。
#ifndef KIBI_FS_H
#define KIBI_FS_H

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

// kibi:fs/mounts.mount(source, point, read-only) -> result<_, error-code>
__attribute__((import_module("kibi:fs/mounts@0.1.0"), import_name("mount")))
void kibi_fs_mount(const char *source, size_t source_len, const char *point, size_t point_len, int32_t read_only, kibi_result_unit_t *ret);

// kibi:fs/mounts.unmount(point) -> result<_, error-code>
__attribute__((import_module("kibi:fs/mounts@0.1.0"), import_name("unmount")))
void kibi_fs_unmount(const char *point, size_t point_len, kibi_result_unit_t *ret);

#endif
