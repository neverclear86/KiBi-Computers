// 独自拡張 kibi:io/fd・kibi:io/pipe・kibi:io/tty（#75・#427。WIT は core/src/main/wit/kibi-io/、
// ABI は docs/adr/0075-kibi-io-pipe-dup.md・docs/adr/0427-kibi-tty.md）を、WASI p1 のプログラムから呼ぶための手書きの宣言
// （wit-bindgen で作る束縛と、libc 風の dup・dup2・pipe・tcgetattr・tcsetattr・ioctl(TIOCGWINSZ) は SDK #116）。
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

// result<_, error-code>（2 バイト、align 1）。is_err が 0 なら成功（kibi_timers.h と共通。先に定義した方を使う）
#ifndef KIBI_RESULT_UNIT_DEFINED
#define KIBI_RESULT_UNIT_DEFINED
typedef struct {
    uint8_t is_err;
    kibi_error_code_t err;
} kibi_result_unit_t;
#endif

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

// flags mode（kibi:io/tty。引数は i32 の下位 5 ビット、結果は u8）。ビットは個別に効く
typedef uint8_t kibi_tty_mode_t;
#define KIBI_TTY_MODE_CANONICAL ((kibi_tty_mode_t)1)   // ICANON: 行単位の入力と行編集
#define KIBI_TTY_MODE_ECHO ((kibi_tty_mode_t)2)        // ECHO
#define KIBI_TTY_MODE_SIGNALS ((kibi_tty_mode_t)4)     // ISIG: Ctrl+C・Ctrl+\ をシグナルにする
#define KIBI_TTY_MODE_CR_TO_NL ((kibi_tty_mode_t)8)    // ICRNL: 入力の CR を LF にする
#define KIBI_TTY_MODE_NL_TO_CRNL ((kibi_tty_mode_t)16) // OPOST|ONLCR: 出力の LF を CR LF にする
#define KIBI_TTY_MODE_COOKED ((kibi_tty_mode_t)0x1f)   // 全部オン（既定）
#define KIBI_TTY_MODE_RAW ((kibi_tty_mode_t)0)         // 全部オフ（cfmakeraw 相当）

// result<mode, error-code>（2 バイト、align 1）。is_err が 0 なら val.ok
typedef struct {
    uint8_t is_err;
    union {
        kibi_tty_mode_t ok;
        kibi_error_code_t err;
    } val;
} kibi_result_tty_mode_t;

// record size（16 バイト、align 8）
typedef struct {
    uint32_t columns;
    uint32_t rows;
    uint64_t generation;
} kibi_tty_size_t;

// result<size, error-code>（24 バイト、align 8）。is_err が 0 なら val.ok
typedef struct {
    uint8_t is_err;
    union {
        kibi_tty_size_t ok;
        kibi_error_code_t err;
    } val;
} kibi_result_tty_size_t;

_Static_assert(sizeof(kibi_result_u32_t) == 8 && _Alignof(kibi_result_u32_t) == 4, "result<u32>");
_Static_assert(offsetof(kibi_result_u32_t, val) == 4, "result<u32> payload");
_Static_assert(sizeof(kibi_result_unit_t) == 2 && _Alignof(kibi_result_unit_t) == 1, "result<_>");
_Static_assert(offsetof(kibi_result_unit_t, err) == 1, "result<_> err");
_Static_assert(sizeof(kibi_result_pipe_ends_t) == 12 && _Alignof(kibi_result_pipe_ends_t) == 4, "result<pipe-ends>");
_Static_assert(offsetof(kibi_result_pipe_ends_t, val.ok.read) == 4, "pipe-ends.read");
_Static_assert(offsetof(kibi_result_pipe_ends_t, val.ok.write) == 8, "pipe-ends.write");
_Static_assert(sizeof(kibi_result_tty_mode_t) == 2 && _Alignof(kibi_result_tty_mode_t) == 1, "result<mode>");
_Static_assert(offsetof(kibi_result_tty_mode_t, val) == 1, "result<mode> payload");
_Static_assert(sizeof(kibi_result_tty_size_t) == 24 && _Alignof(kibi_result_tty_size_t) == 8, "result<size>");
_Static_assert(offsetof(kibi_result_tty_size_t, val.ok.columns) == 8, "size.columns");
_Static_assert(offsetof(kibi_result_tty_size_t, val.ok.rows) == 12, "size.rows");
_Static_assert(offsetof(kibi_result_tty_size_t, val.ok.generation) == 16, "size.generation");

// kibi:io/fd.dup(fd: u32) -> result<u32, error-code>
__attribute__((import_module("kibi:io/fd@0.1.0"), import_name("dup")))
void kibi_io_fd_dup(uint32_t fd, kibi_result_u32_t *ret);

// kibi:io/fd.dup-to(fd: u32, to: u32) -> result<_, error-code>
__attribute__((import_module("kibi:io/fd@0.1.0"), import_name("dup-to")))
void kibi_io_fd_dup_to(uint32_t fd, uint32_t to, kibi_result_unit_t *ret);

// kibi:io/pipe.create() -> result<pipe-ends, error-code>
__attribute__((import_module("kibi:io/pipe@0.1.0"), import_name("create")))
void kibi_io_pipe_create(kibi_result_pipe_ends_t *ret);

// kibi:io/tty.is-tty(fd: u32) -> bool
__attribute__((import_module("kibi:io/tty@0.1.0"), import_name("is-tty")))
int32_t kibi_io_tty_is_tty(uint32_t fd);

// kibi:io/tty.get-mode(fd: u32) -> result<mode, error-code>
__attribute__((import_module("kibi:io/tty@0.1.0"), import_name("get-mode")))
void kibi_io_tty_get_mode(uint32_t fd, kibi_result_tty_mode_t *ret);

// kibi:io/tty.set-mode(fd: u32, mode: mode) -> result<_, error-code>（定義外の上位ビットは読まれない）
__attribute__((import_module("kibi:io/tty@0.1.0"), import_name("set-mode")))
void kibi_io_tty_set_mode(uint32_t fd, int32_t mode, kibi_result_unit_t *ret);

// kibi:io/tty.get-size(fd: u32) -> result<size, error-code>
__attribute__((import_module("kibi:io/tty@0.1.0"), import_name("get-size")))
void kibi_io_tty_get_size(uint32_t fd, kibi_result_tty_size_t *ret);

// kibi:io/tty.get-foreground(fd: u32) -> result<u32, error-code>（0 = なし）
__attribute__((import_module("kibi:io/tty@0.1.0"), import_name("get-foreground")))
void kibi_io_tty_get_foreground(uint32_t fd, kibi_result_u32_t *ret);

// kibi:io/tty.set-foreground(fd: u32, pid: u32) -> result<_, error-code>（0 = なし。2^31 以上は invalid）
__attribute__((import_module("kibi:io/tty@0.1.0"), import_name("set-foreground")))
void kibi_io_tty_set_foreground(uint32_t fd, uint32_t pid, kibi_result_unit_t *ret);

#endif
