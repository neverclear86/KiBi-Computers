// 独自拡張 kibi:process/process（#74。WIT は core/src/main/wit/kibi-process/、ABI は docs/adr/0074-kibi-process.md）を、
// WASI p1 のプログラムから呼ぶための手書きの宣言（wit-bindgen で作る束縛と、posix_spawn・waitpid・kill 風の関数は SDK #116）。
//
// 構造体の配置は Canonical ABI と同じ。get-cwd・list-processes の結果は、プログラムが export する cabi_realloc で場所を取るので、
// これらを import するプログラムは cabi_realloc を export しなければならない（なければ読み込みで NOEXEC）。
// spawn は読み込み（コンパイル）が終わるまで待つ（止まりうる import。待機点への変換の対象）。
// process のハンドルはカーネルの fd 番号で、poll_oneoff の FD_READ（poll の POLLIN）で子の終わりを待てる。
#ifndef KIBI_PROCESS_H
#define KIBI_PROCESS_H

#include <stddef.h>
#include <stdint.h>

// kibi:kernel/types の error-code。p1 の errno = 判別子 + 1（kibi_events.h と同じ型。C11 から typedef は重ねてよい）
typedef uint8_t kibi_error_code_t;

// result<_, error-code>（2 バイト、align 1）。is_err が 0 なら成功（kibi_timers.h・kibi_io.h と共通。先に定義した方を使う）
#ifndef KIBI_RESULT_UNIT_DEFINED
#define KIBI_RESULT_UNIT_DEFINED
typedef struct {
    uint8_t is_err;
    kibi_error_code_t err;
} kibi_result_unit_t;
#endif

// enum signal（判別子）
typedef uint8_t kibi_process_signal_t;
#define KIBI_PROCESS_SIGNAL_INTERRUPT ((kibi_process_signal_t)0) // Ctrl+C
#define KIBI_PROCESS_SIGNAL_QUIT ((kibi_process_signal_t)1)      // Ctrl+\ (backslash)
#define KIBI_PROCESS_SIGNAL_TERMINATE ((kibi_process_signal_t)2) // 普通の終了の求め
#define KIBI_PROCESS_SIGNAL_KILL ((kibi_process_signal_t)3)      // 無視できない

// string（8 バイト、align 4）。引数の list<string> の要素、get-cwd の結果
typedef struct {
    const char *ptr;
    size_t len;
} kibi_process_string_t;

// tuple<string, string>（環境変数の名前と値。16 バイト、align 4）
typedef struct {
    kibi_process_string_t name;
    kibi_process_string_t value;
} kibi_process_env_t;

// variant fd-source（判別子）
#define KIBI_PROCESS_FD_SOURCE_PARENT 0 // 親の fd parent を渡す
#define KIBI_PROCESS_FD_SOURCE_CLOSED 1 // 割り当てない

// record fd-assign（12 バイト、align 4）
typedef struct {
    uint32_t fd;
    struct {
        uint8_t tag;
        uint32_t parent; // tag が KIBI_PROCESS_FD_SOURCE_PARENT のときだけ読む
    } source;
} kibi_process_fd_assign_t;

// result<process, error-code>（8 バイト、align 4）。is_err が 0 なら val.ok がハンドル（fd）
typedef struct {
    uint8_t is_err;
    union {
        uint32_t ok;
        kibi_error_code_t err;
    } val;
} kibi_result_process_t;

// variant exit-status（判別子）
#define KIBI_PROCESS_EXITED 0  // val.exited が終了コード
#define KIBI_PROCESS_KILLED 1  // val.killed（is_some が 0 ならマシンの停止・再起動）
#define KIBI_PROCESS_CRASHED 2 // トラップ・例外

// variant exit-status（8 バイト、align 4）
typedef struct {
    uint8_t tag;
    union {
        uint32_t exited;
        struct {
            uint8_t is_some;
            kibi_process_signal_t sig;
        } killed;
    } val;
} kibi_process_exit_status_t;

// option<exit-status>（12 バイト、align 4）
typedef struct {
    uint8_t is_some;
    kibi_process_exit_status_t val;
} kibi_process_option_exit_status_t;

// result<option<exit-status>, error-code>（16 バイト、align 4）
typedef struct {
    uint8_t is_err;
    union {
        kibi_process_option_exit_status_t ok;
        kibi_error_code_t err;
    } val;
} kibi_result_option_exit_status_t;

// list<string>（8 バイト、align 4）。process-info の args
typedef struct {
    kibi_process_string_t *ptr;
    size_t len;
} kibi_process_list_string_t;

// variant process-state（判別子。#452）
#define KIBI_PROCESS_STATE_STARTING 0 // 読み込みの途中
#define KIBI_PROCESS_STATE_RUNNING 1  // 動ける・動いている
#define KIBI_PROCESS_STATE_WAITING 2  // イベントや接続層の処理を待っている
#define KIBI_PROCESS_STATE_FINISHED 3 // 終わって刈り取られていない。val.finished が終わり方

// variant process-state（12 バイト、align 4）
typedef struct {
    uint8_t tag;
    union {
        kibi_process_exit_status_t finished;
    } val;
} kibi_process_state_t;

// record process-info（28 バイト、align 4）
typedef struct {
    uint32_t pid;
    uint32_t parent_pid; // 親がなければ 0
    kibi_process_state_t state;
    // argv の先頭から最大 16 個・合計 255 バイト（UTF-8）まで。最後の1つは文字の境目で切ってあることがある
    kibi_process_list_string_t args;
} kibi_process_info_t;

// list<process-info>（8 バイト、align 4）。list-processes の結果
typedef struct {
    kibi_process_info_t *ptr;
    size_t len;
} kibi_process_list_process_info_t;

_Static_assert(sizeof(kibi_process_string_t) == 8 && _Alignof(kibi_process_string_t) == 4, "string");
_Static_assert(sizeof(kibi_process_env_t) == 16 && offsetof(kibi_process_env_t, value) == 8, "tuple<string, string>");
_Static_assert(sizeof(kibi_process_fd_assign_t) == 12 && _Alignof(kibi_process_fd_assign_t) == 4, "fd-assign");
_Static_assert(offsetof(kibi_process_fd_assign_t, source.tag) == 4, "fd-assign.source.tag");
_Static_assert(offsetof(kibi_process_fd_assign_t, source.parent) == 8, "fd-assign.source.parent");
_Static_assert(sizeof(kibi_result_process_t) == 8 && offsetof(kibi_result_process_t, val) == 4, "result<process>");
_Static_assert(sizeof(kibi_result_unit_t) == 2 && offsetof(kibi_result_unit_t, err) == 1, "result<_>");
_Static_assert(sizeof(kibi_result_option_exit_status_t) == 16 && _Alignof(kibi_result_option_exit_status_t) == 4, "result<option<exit-status>>");
_Static_assert(offsetof(kibi_result_option_exit_status_t, val.ok.is_some) == 4, "option tag");
_Static_assert(offsetof(kibi_result_option_exit_status_t, val.ok.val.tag) == 8, "exit-status tag");
_Static_assert(offsetof(kibi_result_option_exit_status_t, val.ok.val.val.exited) == 12, "exited");
_Static_assert(offsetof(kibi_result_option_exit_status_t, val.ok.val.val.killed.is_some) == 12, "killed.is_some");
_Static_assert(offsetof(kibi_result_option_exit_status_t, val.ok.val.val.killed.sig) == 13, "killed.sig");
_Static_assert(offsetof(kibi_result_option_exit_status_t, val.err) == 4, "result err");
_Static_assert(sizeof(kibi_process_list_string_t) == 8 && _Alignof(kibi_process_list_string_t) == 4, "list<string>");
_Static_assert(sizeof(kibi_process_state_t) == 12 && offsetof(kibi_process_state_t, val.finished) == 4, "process-state");
_Static_assert(sizeof(kibi_process_info_t) == 28 && _Alignof(kibi_process_info_t) == 4, "process-info");
_Static_assert(offsetof(kibi_process_info_t, parent_pid) == 4, "process-info.parent-pid");
_Static_assert(offsetof(kibi_process_info_t, state) == 8, "process-info.state");
_Static_assert(offsetof(kibi_process_info_t, state.val.finished) == 12, "process-info.state.finished");
_Static_assert(offsetof(kibi_process_info_t, state.val.finished.val.exited) == 16, "process-info.state.finished.exited");
_Static_assert(offsetof(kibi_process_info_t, args) == 20, "process-info.args");
_Static_assert(sizeof(kibi_process_list_process_info_t) == 8 && _Alignof(kibi_process_list_process_info_t) == 4, "list<process-info>");

#define KIBI_PROCESS_MODULE "kibi:process/process@0.1.0"

// [static]process.spawn(path: string, options: spawn-options) -> result<process, error-code>（平らにした 12 個の引数と書き先）
__attribute__((import_module(KIBI_PROCESS_MODULE), import_name("[static]process.spawn")))
void kibi_process_spawn(
    const char *path_ptr, size_t path_len,
    const kibi_process_string_t *args_ptr, size_t args_len,
    int32_t env_is_some, const kibi_process_env_t *env_ptr, size_t env_len,
    int32_t cwd_is_some, const char *cwd_ptr, size_t cwd_len,
    const kibi_process_fd_assign_t *fds_ptr, size_t fds_len,
    kibi_result_process_t *ret);

// [method]process.pid() -> u32（不正なハンドルはトラップ）
__attribute__((import_module(KIBI_PROCESS_MODULE), import_name("[method]process.pid")))
uint32_t kibi_process_pid(uint32_t self);

// [method]process.status() -> result<option<exit-status>, error-code>（待たない。親が some を受け取ったら刈り取る）
__attribute__((import_module(KIBI_PROCESS_MODULE), import_name("[method]process.status")))
void kibi_process_status(uint32_t self, kibi_result_option_exit_status_t *ret);

// [method]process.kill(sig: signal) -> result<_, error-code>（待たない）
__attribute__((import_module(KIBI_PROCESS_MODULE), import_name("[method]process.kill")))
void kibi_process_kill(uint32_t self, int32_t sig, kibi_result_unit_t *ret);

// [resource-drop]process（close(fd) と同じ。不正なハンドルはトラップ）
__attribute__((import_module(KIBI_PROCESS_MODULE), import_name("[resource-drop]process")))
void kibi_process_drop(uint32_t self);

// kill-pid(pid: u32, sig: signal) -> result<_, error-code>
__attribute__((import_module(KIBI_PROCESS_MODULE), import_name("kill-pid")))
void kibi_process_kill_pid(uint32_t pid, int32_t sig, kibi_result_unit_t *ret);

// list-processes() -> list<process-info>（マシンの全プロセスを pid の昇順。ゾンビも含む。待たない。#452）。
// 配列・args の配列・各文字列の場所は cabi_realloc で取る（文字列に NUL は付かない）
__attribute__((import_module(KIBI_PROCESS_MODULE), import_name("list-processes")))
void kibi_process_list_processes(kibi_process_list_process_info_t *ret);

// self-pid() -> u32
__attribute__((import_module(KIBI_PROCESS_MODULE), import_name("self-pid")))
uint32_t kibi_process_self_pid(void);

// parent-pid() -> u32（親がなければ 0）
__attribute__((import_module(KIBI_PROCESS_MODULE), import_name("parent-pid")))
uint32_t kibi_process_parent_pid(void);

// set-signal-ignored(sig: signal, ignored: bool) -> result<_, error-code>（kill は invalid）
__attribute__((import_module(KIBI_PROCESS_MODULE), import_name("set-signal-ignored")))
void kibi_process_set_signal_ignored(int32_t sig, int32_t ignored, kibi_result_unit_t *ret);

// get-cwd() -> string（カーネルの cwd。中身の場所は cabi_realloc で取る。NUL は付かない）
__attribute__((import_module(KIBI_PROCESS_MODULE), import_name("get-cwd")))
void kibi_process_get_cwd(kibi_process_string_t *ret);

// set-cwd(path: string) -> result<_, error-code>（カーネルの cwd。libc の chdir は別に呼ぶ）
__attribute__((import_module(KIBI_PROCESS_MODULE), import_name("set-cwd")))
void kibi_process_set_cwd(const char *path_ptr, size_t path_len, kibi_result_unit_t *ret);

#endif
