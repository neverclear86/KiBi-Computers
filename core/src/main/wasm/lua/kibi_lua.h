/*
 * Lua 向けの KiBi API（#94。ADR 0094）の、C のファイルどうしで共有する宣言。
 *
 * - kibi_lua_canon.c: cabi_realloc と、呼び出し1回分の確保の記録（arena）、error-code の名前
 * - kibi_lua_peripheral.c: peripheral ライブラリ（kibi:device/bus）
 * - kibi_lua_os.c: os ライブラリの拡張（kibi:kernel/events・kibi:computer/clock・power と poll_oneoff）
 * - kibi_linit.c: Lua の linit.c の代わり。標準ライブラリと上の2つを開く
 */
#ifndef KIBI_LUA_H
#define KIBI_LUA_H

#include <stddef.h>
#include <stdint.h>

#include "lua.h"

/*
 * cabi_realloc で取った場所を、まとめて戻す（呼び出し1回分の結果を Lua の値に写し終えたら呼ぶ）。
 * import を呼ぶ前にも呼ぶ（写す途中の Lua のエラーで戻し損ねた分を、次の呼び出しで戻すため）
 */
void kibi_lua_arena_reset(void);

/*
 * 可変長の結果を返す import を呼ぶ前に、結果の最大の大きさ [need] が RAM に入るようにする。入れば 1。
 * RAM の残りが十分ならそれだけで 1。少なければ [need] の場所を先に取り（解放した穴を使い回せる）、cabi_realloc はそこから切り出す。
 * 取れなければ 0（cabi_realloc の中で足りなくなるとトラップになり、プロセスが CRASHED になるため、先に断る）。
 * 1 を返した後、import を呼ぶまで kibi_lua_arena_reset を呼ばない
 */
int kibi_lua_reserve(size_t need);

/* kibi_lua_reserve が 0 なら "not enough memory" の Lua のエラーにする（pcall で受けられる） */
void kibi_lua_check_memory(lua_State *L, size_t need);

/* kibi:kernel/types の error-code の名前（判別子から。範囲外なら "unknown"） */
const char *kibi_lua_error_name(uint8_t code);

/* 正しい UTF-8（過長・サロゲート・U+10FFFF 超えを含まない）なら 1 */
int kibi_lua_utf8_valid(const char *s, size_t len);

int luaopen_peripheral(lua_State *L);
/* os のテーブルに KiBi の関数を足す（スタックの上に os のテーブルがあること） */
void kibi_lua_os_extend(lua_State *L);

#endif
