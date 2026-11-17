// 独自拡張 kibi:computer/access（#110。WIT は core/src/main/wit/kibi-computer/access.wit、ABI は docs/adr/0110-player-access-commands.md）を、
// WASI p1 のプログラムから呼ぶための宣言。p1 向けの import（ADR 0073 の規則で、モジュール "kibi:computer/access@0.1.0"）として出している。
//
// どれも待たない。request-* だけなら cabi_realloc は要らない。get-access の結果は、プログラムが export する cabi_realloc で場所を取るので、
// get-access を import するプログラムは cabi_realloc を export しなければならない（なければ読み込みで NOEXEC）。
// 文字列は (ptr, len) で渡す（NUL で終わらなくてよい。結果の文字列にも NUL は付かない）。
// request-* は依頼を出すだけで、反映はゲーム内で持ち主か OP が承認したとき（ok は「受け付けた」の意味）。
#ifndef KIBI_ACCESS_H
#define KIBI_ACCESS_H

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

// enum access-state
#define KIBI_ACCESS_STATE_UNOWNED 0
#define KIBI_ACCESS_STATE_OWNED 1
#define KIBI_ACCESS_STATE_LOCKED 2

// string（8 バイト、align 4）
typedef struct {
    const char *ptr;
    size_t len;
} kibi_access_string_t;

// record player（16 バイト、align 4）。id は UUID の小文字の正規形
typedef struct {
    kibi_access_string_t name;
    kibi_access_string_t id;
} kibi_access_player_t;

// record access-info（40 バイト、align 4）。unowned・locked なら owner は none、members・invites は空
typedef struct {
    uint8_t state;
    uint8_t pad0[3];
    uint8_t owner_is_some; // option<player> の判別子（@4。payload は @8）
    kibi_access_player_t owner;
    const kibi_access_player_t *members;
    size_t members_len;
    const kibi_access_string_t *invites; // 名前で足した保留（小文字）
    size_t invites_len;
} kibi_access_info_t;

// result<access-info, error-code>（44 バイト、align 4）。is_err なら err は info の先頭の 1 バイト（state の場所）
typedef struct {
    uint8_t is_err;
    union {
        kibi_access_info_t info;
        kibi_error_code_t err;
    } val;
} kibi_access_result_t;

_Static_assert(sizeof(kibi_access_string_t) == 8 && _Alignof(kibi_access_string_t) == 4, "string");
_Static_assert(sizeof(kibi_access_player_t) == 16 && offsetof(kibi_access_player_t, id) == 8, "player");
_Static_assert(offsetof(kibi_access_info_t, owner_is_some) == 4 && offsetof(kibi_access_info_t, owner) == 8, "access-info.owner");
_Static_assert(offsetof(kibi_access_info_t, members) == 24 && offsetof(kibi_access_info_t, invites) == 32, "access-info.lists");
_Static_assert(sizeof(kibi_access_info_t) == 40 && _Alignof(kibi_access_info_t) == 4, "access-info");
_Static_assert(sizeof(kibi_access_result_t) == 44 && offsetof(kibi_access_result_t, val) == 4, "result<access-info>");

// kibi:computer/access.get-access() -> result<access-info, error-code>（cabi_realloc が要る）
__attribute__((import_module("kibi:computer/access@0.1.0"), import_name("get-access")))
void kibi_access_get(kibi_access_result_t *ret);

// kibi:computer/access.request-add(name) -> result<_, error-code>
__attribute__((import_module("kibi:computer/access@0.1.0"), import_name("request-add")))
void kibi_access_request_add(const char *name, size_t name_len, kibi_result_unit_t *ret);

// kibi:computer/access.request-remove(name-or-id) -> result<_, error-code>
__attribute__((import_module("kibi:computer/access@0.1.0"), import_name("request-remove")))
void kibi_access_request_remove(const char *name_or_id, size_t len, kibi_result_unit_t *ret);

// kibi:computer/access.request-transfer(name) -> result<_, error-code>
__attribute__((import_module("kibi:computer/access@0.1.0"), import_name("request-transfer")))
void kibi_access_request_transfer(const char *name, size_t name_len, kibi_result_unit_t *ret);

#endif
