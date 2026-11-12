// 独自拡張 kibi:fs/mounts（#56。WIT は core/src/main/wit/kibi-fs/mounts.wit、ABI は docs/adr/0056-disk-hotplug-and-mount.md）を、
// WASI p1 のプログラムから呼ぶための宣言。p1 向けの import（ADR 0073 の規則で、モジュール "kibi:fs/mounts@0.1.0"）として出している。
//
// どれも待たない。mount・unmount だけなら cabi_realloc は要らない。list-mounts・list-disks の結果は、プログラムが export する
// cabi_realloc で場所を取るので、これらを import するプログラムは cabi_realloc を export しなければならない（なければ読み込みで NOEXEC）。
// 文字列は (ptr, len) で渡す（NUL で終わらなくてよい。結果の文字列にも NUL は付かない）。
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

// string（8 バイト、align 4）
typedef struct {
    const char *ptr;
    size_t len;
} kibi_fs_string_t;

// enum fs-kind（#453）
#define KIBI_FS_KIND_DISK 0
#define KIBI_FS_KIND_IMAGE 1
#define KIBI_FS_KIND_TMP 2
#define KIBI_FS_KIND_DEV 3

// record fs-usage（40 バイト、align 8）。使用量は管理の分を含むので du とは合わない。tmp の空きは目安
typedef struct {
    uint64_t total_bytes;
    uint64_t used_bytes;
    uint64_t available_bytes;
    uint8_t total_entries_is_some; // 0 なら上限なし（tmp）
    uint32_t total_entries;
    uint32_t used_entries; // 根を除く
} kibi_fs_usage_t;

// record mount-info（72 バイト、align 8）
typedef struct {
    kibi_fs_string_t point;
    kibi_fs_string_t source;
    uint8_t kind;      // KIBI_FS_KIND_*
    uint8_t read_only; // 0 か 1
    uint32_t id;       // filestat の dev と同じ値
    uint8_t usage_is_some;
    kibi_fs_usage_t usage;
} kibi_fs_mount_info_t;

// record disk-info（32 バイト、align 8）
typedef struct {
    kibi_fs_string_t source; // "disk:<識別子>"
    uint64_t capacity_bytes;
    uint32_t max_entries;
    uint8_t mount_point_is_some; // 0 ならマウントしていない
    kibi_fs_string_t mount_point;
} kibi_fs_disk_info_t;

// list<mount-info>・list<disk-info>（8 バイト、align 4）
typedef struct {
    kibi_fs_mount_info_t *ptr;
    size_t len;
} kibi_fs_list_mount_info_t;

typedef struct {
    kibi_fs_disk_info_t *ptr;
    size_t len;
} kibi_fs_list_disk_info_t;

_Static_assert(sizeof(kibi_fs_string_t) == 8 && _Alignof(kibi_fs_string_t) == 4, "string");
_Static_assert(sizeof(kibi_fs_usage_t) == 40 && _Alignof(kibi_fs_usage_t) == 8, "fs-usage");
_Static_assert(offsetof(kibi_fs_usage_t, used_bytes) == 8 && offsetof(kibi_fs_usage_t, available_bytes) == 16, "fs-usage.bytes");
_Static_assert(offsetof(kibi_fs_usage_t, total_entries_is_some) == 24 && offsetof(kibi_fs_usage_t, total_entries) == 28, "fs-usage.total-entries");
_Static_assert(offsetof(kibi_fs_usage_t, used_entries) == 32, "fs-usage.used-entries");
_Static_assert(sizeof(kibi_fs_mount_info_t) == 72 && _Alignof(kibi_fs_mount_info_t) == 8, "mount-info");
_Static_assert(offsetof(kibi_fs_mount_info_t, source) == 8 && offsetof(kibi_fs_mount_info_t, kind) == 16, "mount-info.source/kind");
_Static_assert(offsetof(kibi_fs_mount_info_t, read_only) == 17 && offsetof(kibi_fs_mount_info_t, id) == 20, "mount-info.read-only/id");
_Static_assert(offsetof(kibi_fs_mount_info_t, usage_is_some) == 24 && offsetof(kibi_fs_mount_info_t, usage) == 32, "mount-info.usage");
_Static_assert(sizeof(kibi_fs_disk_info_t) == 32 && _Alignof(kibi_fs_disk_info_t) == 8, "disk-info");
_Static_assert(offsetof(kibi_fs_disk_info_t, capacity_bytes) == 8 && offsetof(kibi_fs_disk_info_t, max_entries) == 16, "disk-info.capacity");
_Static_assert(offsetof(kibi_fs_disk_info_t, mount_point_is_some) == 20 && offsetof(kibi_fs_disk_info_t, mount_point) == 24, "disk-info.mount-point");
_Static_assert(sizeof(kibi_fs_list_mount_info_t) == 8 && sizeof(kibi_fs_list_disk_info_t) == 8, "list");

// kibi:fs/mounts.list-mounts() -> list<mount-info>（マウントを足した順。待たない。#453）。配列と各文字列の場所は cabi_realloc で取る
__attribute__((import_module("kibi:fs/mounts@0.1.0"), import_name("list-mounts")))
void kibi_fs_list_mounts(kibi_fs_list_mount_info_t *ret);

// kibi:fs/mounts.list-disks() -> list<disk-info>（スロットの順。待たない。#453）。配列と各文字列の場所は cabi_realloc で取る
__attribute__((import_module("kibi:fs/mounts@0.1.0"), import_name("list-disks")))
void kibi_fs_list_disks(kibi_fs_list_disk_info_t *ret);

#endif
