/*
 * Lua 向けの KiBi API（#94。ADR 0094）の、C のファイルどうしで共有する宣言。
 *
 * - kibi_lua_canon.c: cabi_realloc と、呼び出し1回分の確保の記録（arena）、error-code の名前
 * - kibi_lua_peripheral.c: peripheral ライブラリ（kibi:device/bus）と、コルーチンの中の呼び出しが待つ表（#744）
 * - kibi_lua_os.c: os ライブラリの拡張（kibi:kernel/events・kibi:computer/clock・power と poll_oneoff）
 * - kibi_lua_parallel.c: parallel ライブラリ（#95。ADR 0095）
 * - kibi_lua_colors.c: colors・colours ライブラリと、ライブラリの表を初めて触ったときに詰める仕組み（#724。ADR 0724）
 * - kibi_lua_keys.c: keys ライブラリと、カーネルのキー名から CC のキーの番号への表（#724）
 * - kibi_lua_term.c: term ライブラリ（#724）
 * - kibi_lua_fs.c: fs ライブラリ（#724）
 * - kibi_lua_tty.c: キー待ちの間の端末のモード（#798。ADR 0798）
 * - kibi_linit.c: Lua の linit.c の代わり。標準ライブラリと上のライブラリを開く
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
 * arena にある結果を Lua の値に写している間は、hold から release までで囲む（入れ子にできる。数で数える）。
 * 写す途中の Lua の確保で GC が走ると、__gc の中から os.queueEvent・os.pullEvent などで回収（kibi_lua_os.c の collect）が入れ子で走り、
 * arena を戻して取り直したり、待っている呼び出しの表を詰めたりする。写している場所と表が動かないよう、囲んでいる間の回収は
 * arena を使うもの（カーネルのイベントの取り出しと、終わった peripheral.call の回収）をしない（#755）。
 * 写す処理は lua_pcall の中で行い、エラーで抜けても release する（kibi_lua_peripheral.c の copy_held）。
 * 囲んでいる間に呼ばれた peripheral の関数は、arena を戻す前に "cannot call a peripheral while a result is being copied" のエラーにする（#756）
 */
void kibi_lua_arena_hold(void);
void kibi_lua_arena_release(void);

/* kibi_lua_arena_hold の中なら 1 */
int kibi_lua_arena_held(void);

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

/*
 * 中身を初めて触ったときに詰めるライブラリの表（#724。ADR 0724）。空のテーブルを積み、メタテーブルの __index・__pairs で
 * 初めて触られたときに [fill]（スタックの上の表に関数・値を足す）を呼んで、メタテーブルを外す。
 * 起動のたびに REPL 1,000 台が keys・colors などの表を作らないため。[fill] はもうある key を上書きしない
 */
void kibi_lua_lazy_lib(lua_State *L, lua_CFunction fill);
/* スタックの上の表の [name] がまだなければ [fn] を入れる（fill から使う） */
void kibi_lua_lazy_set(lua_State *L, const char *name, lua_CFunction fn);

int luaopen_colors(lua_State *L);
int luaopen_colours(lua_State *L);
int luaopen_keys(lua_State *L);
int luaopen_term(lua_State *L);
int luaopen_fs(lua_State *L);

/* CC の色（2 の 0〜15 乗）の既定のパレット（0xRRGGBB）。[i] は 2^i の色 */
extern const uint32_t kibi_lua_default_palette[16];

/*
 * カーネルのキー名（ADR 0326。[name] は NUL で終わらなくてよい）を CC の keys の番号（GLFW のキーコード）にする。
 * 表にない名前と "unknown" は -1。import を呼ばず、Lua の確保もしない（kibi_lua_arena_hold の中から呼ぶ）
 */
int kibi_lua_key_code(const char *name, size_t len);

/* 保存から戻した（"restored" を受けた）ことを term に知らせる。印を立てるだけで、何も書かない（kibi_lua_arena_hold の中から呼ぶ） */
void kibi_lua_term_restored(void);

/*
 * キー待ちの間の端末のモード（#798。ADR 0798。kibi_lua_tty.c）。
 * - kibi_lua_tty_keys: メインスレッドがキーを待ち始めるときに呼ぶ。標準入力が端末で cooked なら、echo と canonical を切る
 * - kibi_lua_tty_before_wait: poll_oneoff で待つ直前に呼ぶ。切っている間は、今読める標準入力を待たずに読み捨てる
 * - kibi_lua_tty_restore: 標準入力を読む前と終わるときに呼ぶ（kibi_wasi.h の lua_readline・clearerr と atexit）。切っていなければ何もしない
 */
void kibi_lua_tty_keys(void);
void kibi_lua_tty_before_wait(void);
void kibi_lua_tty_restore(void);

#endif
