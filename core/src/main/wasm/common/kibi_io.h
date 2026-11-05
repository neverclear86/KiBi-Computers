// 独自拡張 kibi:io/fd・kibi:io/pipe（#75。WIT は core/src/main/wit/kibi-io/、ABI は docs/adr/0075-kibi-io-pipe-dup.md）を、
// WASI p1 のプログラムから呼ぶための手書きの宣言（wit-bindgen で作る束縛と、libc 風の dup・dup2・pipe は SDK #116）。
//
// 構造体の配置は Canonical ABI と同じ。どれも可変長の結果がないので、cabi_realloc は要らない。
#ifndef KIBI_IO_H
#define KIBI_IO_H

#include <stddef.h>
#include <stdint.h>

// kibi:kernel/types の error-code。p1 の errno = 判別子 + 1（kibi_events.h と同じ型。C11 から typedef は重ねてよい）
typedef uint8_t kibi_error_code_t;

// result<u32, error-code>（8 バイト、align 4）。is_err が 0 なら val.ok
typedef struct {
    uint8_t is_err;
    union {
        uint32_t ok;
        kibi_error_code_t err;
    } val;
} kibi_result_u32_t;

// result<_, error-code>（2 バイト、align 1）。is_err が 0 なら成功
typedef struct {
    uint8_t is_err;
    kibi_error_code_t err;
} kibi_result_unit_t;

// record pipe-ends（8 バイト、align 4）
typedef struct {
    uint32_t read;
    uint32_t write;
} kibi_pipe_ends_t;

// result<pipe-ends, error-code>（12 バイト、align 4）。is_err が 0 なら val.ok
typedef struct {
    uint8_t is_err;
    union {
        kibi_pipe_ends_t ok;
        kibi_error_code_t err;
    } val;
} kibi_result_pipe_ends_t;

_Static_assert(sizeof(kibi_result_u32_t) == 8 && _Alignof(kibi_result_u32_t) == 4, "result<u32>");
_Static_assert(offsetof(kibi_result_u32_t, val) == 4, "result<u32> payload");
_Static_assert(sizeof(kibi_result_unit_t) == 2 && _Alignof(kibi_result_unit_t) == 1, "result<_>");
_Static_assert(offsetof(kibi_result_unit_t, err) == 1, "result<_> err");
_Static_assert(sizeof(kibi_result_pipe_ends_t) == 12 && _Alignof(kibi_result_pipe_ends_t) == 4, "result<pipe-ends>");
_Static_assert(offsetof(kibi_result_pipe_ends_t, val.ok.read) == 4, "pipe-ends.read");
_Static_assert(offsetof(kibi_result_pipe_ends_t, val.ok.write) == 8, "pipe-ends.write");

// kibi:io/fd.dup(fd: u32) -> result<u32, error-code>
__attribute__((import_module("kibi:io/fd@0.1.0"), import_name("dup")))
void kibi_io_fd_dup(uint32_t fd, kibi_result_u32_t *ret);

// kibi:io/fd.dup-to(fd: u32, to: u32) -> result<_, error-code>
__attribute__((import_module("kibi:io/fd@0.1.0"), import_name("dup-to")))
void kibi_io_fd_dup_to(uint32_t fd, uint32_t to, kibi_result_unit_t *ret);

// kibi:io/pipe.create() -> result<pipe-ends, error-code>
__attribute__((import_module("kibi:io/pipe@0.1.0"), import_name("create")))
void kibi_io_pipe_create(kibi_result_pipe_ends_t *ret);

#endif
