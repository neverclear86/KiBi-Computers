// 独自拡張 kibi:kernel/events（#73。WIT は core/src/main/wit/kibi-kernel/events.wit、ABI は docs/adr/0073-kibi-wit.md）を、
// WASI p1 のプログラムから呼ぶための手書きの宣言（wit-bindgen で作る束縛は SDK #116）。
//
// 構造体の配置は Canonical ABI と同じ。可変長の結果（take）は、プログラムが export する cabi_realloc で場所を取るので、
// take を import するプログラムは cabi_realloc を export しなければならない（なければ読み込みで NOEXEC）。
// 取った場所は呼んだ側のもの（free してよい。cabi_realloc を malloc で作ったとき）。
#ifndef KIBI_EVENTS_H
#define KIBI_EVENTS_H

#include <stddef.h>
#include <stdint.h>

// kibi:kernel/types の error-code。p1 の errno = 判別子 + 1
typedef uint8_t kibi_error_code_t;

typedef struct {
    const uint8_t *ptr;
    uint32_t len;
} kibi_string_t;

typedef struct {
    kibi_string_t *ptr;
    uint32_t len;
} kibi_list_string_t;

typedef struct {
    uint8_t *ptr;
    uint32_t len;
} kibi_list_u8_t;

#define KIBI_EVENT_VALUE_INTEGER 0
#define KIBI_EVENT_VALUE_FLOAT 1
#define KIBI_EVENT_VALUE_BOOLEAN 2
#define KIBI_EVENT_VALUE_TEXT 3
#define KIBI_EVENT_VALUE_BYTES 4

// variant event-value（16 バイト、align 8）。case の名前は WIT と同じ（#65 で s64・f64・bool から改名した）
typedef struct {
    uint8_t tag;
    union {
        int64_t integer;
        double floating; // float は C のキーワードなので floating（WIT の case は float）
        uint8_t boolean;
        kibi_string_t text;
        kibi_list_u8_t bytes;
    } val;
} kibi_event_value_t;

typedef struct {
    kibi_event_value_t *ptr;
    uint32_t len;
} kibi_list_event_value_t;

// record event（24 バイト、align 4）
typedef struct {
    kibi_string_t kind;
    kibi_string_t source;
    kibi_list_event_value_t values;
} kibi_event_t;

typedef struct {
    kibi_event_t *ptr;
    uint32_t len;
} kibi_list_event_t;

// record event-batch（16 バイト、align 8）
typedef struct {
    kibi_list_event_t events;
    uint64_t dropped;
} kibi_event_batch_t;

// result<event-queue, error-code>（8 バイト、align 4）。is_err が 0 なら val.handle（= fd）
typedef struct {
    uint8_t is_err;
    union {
        int32_t handle;
        kibi_error_code_t err;
    } val;
} kibi_result_event_queue_t;

// result<event-batch, error-code>（24 バイト、align 8）。is_err が 0 なら val.ok
typedef struct {
    uint8_t is_err;
    union {
        kibi_event_batch_t ok;
        kibi_error_code_t err;
    } val;
} kibi_result_event_batch_t;

_Static_assert(sizeof(kibi_event_value_t) == 16 && _Alignof(kibi_event_value_t) == 8, "event-value");
_Static_assert(offsetof(kibi_event_value_t, val) == 8, "event-value payload");
_Static_assert(sizeof(kibi_event_t) == 24 && _Alignof(kibi_event_t) == 4, "event");
_Static_assert(offsetof(kibi_event_t, source) == 8 && offsetof(kibi_event_t, values) == 16, "event fields");
_Static_assert(sizeof(kibi_event_batch_t) == 16 && offsetof(kibi_event_batch_t, dropped) == 8, "event-batch");
_Static_assert(sizeof(kibi_result_event_queue_t) == 8 && offsetof(kibi_result_event_queue_t, val) == 4, "result<event-queue>");
_Static_assert(sizeof(kibi_result_event_batch_t) == 24 && offsetof(kibi_result_event_batch_t, val) == 8, "result<event-batch>");
_Static_assert(offsetof(kibi_result_event_batch_t, val.ok.events.len) == 12, "events.len");
_Static_assert(offsetof(kibi_result_event_batch_t, val.ok.dropped) == 16, "dropped");

// [static]event-queue.open(kinds: list<string>, capacity: option<u32>) -> result<event-queue, error-code>
__attribute__((import_module("kibi:kernel/events@0.1.0"), import_name("[static]event-queue.open")))
void kibi_event_queue_open_raw(const kibi_string_t *kinds_ptr, uint32_t kinds_len, int32_t cap_is_some, uint32_t cap,
                               kibi_result_event_queue_t *ret);

// [method]event-queue.take(self, max: u32) -> result<event-batch, error-code>
__attribute__((import_module("kibi:kernel/events@0.1.0"), import_name("[method]event-queue.take")))
void kibi_event_queue_take_raw(int32_t self, uint32_t max, kibi_result_event_batch_t *ret);

// [resource-drop]event-queue（fd_close と同じく閉じる。不正なハンドルならトラップ）
__attribute__((import_module("kibi:kernel/events@0.1.0"), import_name("[resource-drop]event-queue")))
void kibi_event_queue_drop(int32_t self);

#endif
