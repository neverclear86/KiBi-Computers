/*
 * Lua 向けの KiBi API（#94。ADR 0094）の、C のファイルどうしで共有する宣言。
 *
 * - kibi_lua_canon.c: cabi_realloc と、呼び出し1回分の確保の記録（arena）、error-code の名前
 * - kibi_lua_peripheral.c: peripheral ライブラリ（kibi:device/bus）と、コルーチンの中の呼び出しが待つ表（#744）
 * - kibi_lua_os.c: os ライブラリの拡張（kibi:kernel/events・kibi:computer/clock・power と poll_oneoff）
 * - kibi_lua_parallel.c: parallel ライブラリ（#95。ADR 0095）
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
/*
 * os.pullEventRaw の C 関数。メインスレッドならその場で待ち、コルーチンの中なら filter を yield する。
 * parallel が次のイベントを取るのに使う（ユーザーが上書きした os.pullEventRaw を通さないため）
 */
int kibi_lua_pull_raw(lua_State *L);
int luaopen_parallel(lua_State *L);

/* 今動いているのがメインスレッド（コルーチンではない）なら 1。lua_isyieldable だけでは分けない（ADR 0095） */
int kibi_lua_on_main_thread(lua_State *L);
/* スタックの上の [n] 個の値（名前, 引数...）を os のイベントの FIFO の後ろに積む（値は取り除く） */
void kibi_lua_fifo_push(lua_State *L, int n);

/*
 * コルーチンの中の peripheral.call が待っている呼び出し（#744。ADR 0744）。
 * 数と、[i] 番目（0 から）の pending-call の fd。os の待ち（poll_oneoff）が FD_READ で待つ
 */
int kibi_lua_task_count(void);
int32_t kibi_lua_task_fd(int i);
/* 終わった呼び出しを "task_complete" のイベントにして FIFO に積み、pending-call を閉じる。表が空なら何もしない */
void kibi_lua_tasks_collect(lua_State *L);

#endif
