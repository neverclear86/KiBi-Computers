// 独自拡張 kibi:device/bus（#77。WIT は core/src/main/wit/kibi-device/、ABI は docs/adr/0077-kibi-device.md）を、
// WASI p1 のプログラムから呼ぶための手書きの宣言（wit-bindgen で作る束縛は SDK #116、標準 OS の束縛は #108）。
//
// 構造体の配置は Canonical ABI と同じ。結果の文字列・リストはプログラムが export する cabi_realloc で場所を取るので、
// drop 以外を import するプログラムは cabi_realloc を export しなければならない（なければ読み込みで NOEXEC）。
// device・pending-call のハンドルはカーネルの fd 番号。/dev/device/<address> を path_open で開いた fd も device として使える。
// pending-call は poll_oneoff の FD_READ（poll の POLLIN）で終わりを待てる。
// invoke は止まりうる import（メインスレッドのメソッドは次の tick まで待つ。待機点への変換の対象）。cabi_realloc の中で呼ぶとトラップ。
#ifndef KIBI_DEVICE_H
#define KIBI_DEVICE_H

#include <stddef.h>
#include <stdint.h>

// kibi:kernel/types の error-code。p1 の errno = 判別子 + 1（kibi_events.h と同じ型。C11 から typedef は重ねてよい）
typedef uint8_t kibi_error_code_t;

// string（8 バイト、align 4）
typedef struct {
    const char *ptr;
    size_t len;
} kibi_device_string_t;

// list<u8>（8 バイト、align 4）
typedef struct {
    const uint8_t *ptr;
    size_t len;
} kibi_device_bytes_t;

// variant value の判別子（kibi:kernel/events の event-value とは番号が違う。ADR 0065）
#define KIBI_DEVICE_VALUE_NONE 0
#define KIBI_DEVICE_VALUE_INTEGER 1
#define KIBI_DEVICE_VALUE_FLOAT 2
#define KIBI_DEVICE_VALUE_BOOLEAN 3
#define KIBI_DEVICE_VALUE_TEXT 4
#define KIBI_DEVICE_VALUE_BYTES 5

// variant value（16 バイト、align 8）
typedef struct {
    uint8_t tag;
    union {
        int64_t integer;
        double floating; // float は C のキーワードなので floating（WIT の case は float）
        uint8_t boolean;
        kibi_device_string_t text;
        kibi_device_bytes_t bytes;
    } val;
} kibi_device_value_t;

// list<value>（8 バイト、align 4）
typedef struct {
    kibi_device_value_t *ptr;
    size_t len;
} kibi_device_list_value_t;

// record device-info（16 バイト、align 4）
typedef struct {
    kibi_device_string_t address;
    kibi_device_string_t kind;
} kibi_device_info_t;

// list<device-info>（8 バイト、align 4）
typedef struct {
    kibi_device_info_t *ptr;
    size_t len;
} kibi_device_list_info_t;

// record method-info（12 バイト、align 4）
typedef struct {
    kibi_device_string_t name;
    uint8_t main_thread;
} kibi_device_method_info_t;

// list<method-info>（8 バイト、align 4）
typedef struct {
    kibi_device_method_info_t *ptr;
    size_t len;
} kibi_device_list_method_info_t;

// variant call-error の判別子
#define KIBI_DEVICE_CALL_ERROR_KERNEL 0         // val.kernel が error-code
#define KIBI_DEVICE_CALL_ERROR_DETACHED 1       // デバイスが外れた
#define KIBI_DEVICE_CALL_ERROR_NO_SUCH_METHOD 2 // そのメソッドがない
#define KIBI_DEVICE_CALL_ERROR_BAD_ARGUMENTS 3  // val.message
#define KIBI_DEVICE_CALL_ERROR_FAILED 4         // val.message

// variant call-error（12 バイト、align 4）
typedef struct {
    uint8_t tag;
    union {
        kibi_error_code_t kernel;
        kibi_device_string_t message;
    } val;
} kibi_device_call_error_t;

// result<list<value>, call-error>（16 バイト、align 4）。is_err が 0 なら val.ok
typedef struct {
    uint8_t is_err;
    union {
        kibi_device_list_value_t ok;
        kibi_device_call_error_t err;
    } val;
} kibi_device_result_values_t;

// result<device, call-error>・result<pending-call, call-error>（16 バイト、align 4）。is_err が 0 なら val.ok がハンドル（fd）
typedef struct {
    uint8_t is_err;
    union {
        uint32_t ok;
        kibi_device_call_error_t err;
    } val;
} kibi_device_result_handle_t;

// option<result<list<value>, call-error>>（20 バイト、align 4）。is_some が 0 ならまだ終わっていない
typedef struct {
    uint8_t is_some;
    kibi_device_result_values_t val;
} kibi_device_option_result_values_t;

_Static_assert(sizeof(kibi_device_value_t) == 16 && _Alignof(kibi_device_value_t) == 8, "value");
_Static_assert(offsetof(kibi_device_value_t, val) == 8, "value payload");
_Static_assert(offsetof(kibi_device_value_t, val.text.len) == 12, "value text.len");
_Static_assert(sizeof(kibi_device_info_t) == 16 && _Alignof(kibi_device_info_t) == 4, "device-info");
_Static_assert(offsetof(kibi_device_info_t, kind) == 8, "device-info.kind");
_Static_assert(sizeof(kibi_device_method_info_t) == 12 && _Alignof(kibi_device_method_info_t) == 4, "method-info");
_Static_assert(offsetof(kibi_device_method_info_t, main_thread) == 8, "method-info.main-thread");
_Static_assert(sizeof(kibi_device_call_error_t) == 12 && _Alignof(kibi_device_call_error_t) == 4, "call-error");
_Static_assert(offsetof(kibi_device_call_error_t, val) == 4, "call-error payload");
_Static_assert(sizeof(kibi_device_result_values_t) == 16 && _Alignof(kibi_device_result_values_t) == 4, "result<list<value>>");
_Static_assert(offsetof(kibi_device_result_values_t, val.ok.len) == 8, "result ok.len");
_Static_assert(offsetof(kibi_device_result_values_t, val.err.val.message.len) == 12, "result err.message.len");
_Static_assert(sizeof(kibi_device_result_handle_t) == 16 && offsetof(kibi_device_result_handle_t, val) == 4, "result<device>");
_Static_assert(sizeof(kibi_device_option_result_values_t) == 20 && _Alignof(kibi_device_option_result_values_t) == 4, "option<result>");
_Static_assert(offsetof(kibi_device_option_result_values_t, val) == 4, "option payload");

#define KIBI_DEVICE_MODULE "kibi:device/bus@0.1.0"

// list-devices() -> list<device-info>（待たない。接続を変えたときだけ作り直す一覧を写す）
__attribute__((import_module(KIBI_DEVICE_MODULE), import_name("list-devices")))
void kibi_device_list_devices(kibi_device_list_info_t *ret);

// [static]device.open(address: string) -> result<device, call-error>（36 バイトでない・見つからない address は detached）
__attribute__((import_module(KIBI_DEVICE_MODULE), import_name("[static]device.open")))
void kibi_device_open(const char *addr_ptr, size_t addr_len, kibi_device_result_handle_t *ret);

// [method]device.info() -> device-info（外れていても返す。不正なハンドルはトラップ）
__attribute__((import_module(KIBI_DEVICE_MODULE), import_name("[method]device.info")))
void kibi_device_info(uint32_t self, kibi_device_info_t *ret);

// [method]device.methods() -> list<method-info>（外れている・種類が違うなら空。不正なハンドルはトラップ）
__attribute__((import_module(KIBI_DEVICE_MODULE), import_name("[method]device.methods")))
void kibi_device_methods(uint32_t self, kibi_device_list_method_info_t *ret);

// [method]device.invoke(method: string, args: list<value>) -> result<list<value>, call-error>（結果まで待つ。args は align 8）
__attribute__((import_module(KIBI_DEVICE_MODULE), import_name("[method]device.invoke")))
void kibi_device_invoke(uint32_t self, const char *m_ptr, size_t m_len, const kibi_device_value_t *a_ptr, size_t a_len,
                        kibi_device_result_values_t *ret);

// [method]device.start(method: string, args: list<value>) -> result<pending-call, call-error>（待たずに積む。1プロセス 16 個まで）
__attribute__((import_module(KIBI_DEVICE_MODULE), import_name("[method]device.start")))
void kibi_device_start(uint32_t self, const char *m_ptr, size_t m_len, const kibi_device_value_t *a_ptr, size_t a_len,
                       kibi_device_result_handle_t *ret);

// [method]pending-call.take() -> option<result<list<value>, call-error>>（待たない。閉じない）
__attribute__((import_module(KIBI_DEVICE_MODULE), import_name("[method]pending-call.take")))
void kibi_device_take(uint32_t self, kibi_device_option_result_values_t *ret);

// [resource-drop]device（close(fd) と同じ。不正なハンドルはトラップ）
__attribute__((import_module(KIBI_DEVICE_MODULE), import_name("[resource-drop]device")))
void kibi_device_drop(uint32_t self);

// [resource-drop]pending-call（終わる前に閉じると取り消す。不正なハンドルはトラップ）
__attribute__((import_module(KIBI_DEVICE_MODULE), import_name("[resource-drop]pending-call")))
void kibi_device_pending_call_drop(uint32_t self);

#endif
