/*
 * #30 の検証用: Lua 5.4 のライブラリとリンクする reactor。
 * 本番の lua.wasm（#91。lua.c の main を持つコマンド形式）の代わりではない。
 *
 * - poc_load(ptr, len): スクリプトを読み、メインチャンクをコルーチンとして最初の待ちまで動かす
 * - poc_resume(ptr, len): イベント（文字列）を渡してメインチャンクを再開し、次の待ちで戻る
 * - kibi.wait(): C 関数から lua_yield する。メインチャンクで呼ぶと poc_resume から戻る（待機点）。
 *   子のコルーチンで呼ぶと、それを resume した相手に kibi.WAIT が返る（スクリプト側で待ちを出し直す）
 * - kibi.block(n): ホストの import を同期で呼ぶ「ブロッキング型」の例（その中では保存できない）
 *
 * 名前（poc_*、kibi_poc.block）はこの PoC だけの仮のもので、システムコールの ABI ではない。
 */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

#include "lauxlib.h"
#include "lua.h"
#include "lualib.h"

__attribute__((import_module("kibi_poc"), import_name("block"))) int32_t kibi_block(int32_t arg);

static lua_State *L;
/* メインチャンクのコルーチン（レジストリから参照して GC させない） */
static lua_State *co;

/* kibi.WAIT（ただの番兵。レジストリのキーにも使う） */
static char wait_key;

static int l_wait(lua_State *S) {
    lua_settop(S, 0);
    lua_rawgetp(S, LUA_REGISTRYINDEX, &wait_key);
    return lua_yield(S, 1);
}

static int l_block(lua_State *S) {
    lua_Integer n = luaL_optinteger(S, 1, 0);
    lua_pushinteger(S, kibi_block((int32_t)n));
    return 1;
}

static const luaL_Reg kibi_funcs[] = {
    {"wait", l_wait},
    {"block", l_block},
    {NULL, NULL},
};

__attribute__((export_name("poc_alloc"))) void *poc_alloc(int32_t len) {
    return malloc((size_t)len);
}

/* lua_resume の結果を返す。1: 待っている、0: 最後まで動いた、-1: エラー */
static int32_t finish(int status, int nres) {
    if (status == LUA_YIELD) {
        lua_pop(co, nres);
        fflush(stdout);
        return 1;
    }
    if (status == LUA_OK) {
        lua_pop(co, nres);
        fflush(stdout);
        return 0;
    }
    fprintf(stderr, "lua error: %s\n", lua_tostring(co, -1));
    lua_pop(co, 1);
    fflush(stdout);
    return -1;
}

__attribute__((export_name("poc_load"))) int32_t poc_load(char *src, int32_t len) {
    L = luaL_newstate();
    luaL_openlibs(L);
    luaL_newlib(L, kibi_funcs);
    lua_newtable(L); /* kibi.WAIT */
    lua_pushvalue(L, -1);
    lua_rawsetp(L, LUA_REGISTRYINDEX, &wait_key);
    lua_setfield(L, -2, "WAIT");
    lua_setglobal(L, "kibi");

    co = lua_newthread(L);
    luaL_ref(L, LUA_REGISTRYINDEX);
    int st = luaL_loadbuffer(co, src, (size_t)len, "=script");
    free(src);
    if (st != LUA_OK) {
        fprintf(stderr, "lua load error: %s\n", lua_tostring(co, -1));
        return -1;
    }
    int nres = 0;
    return finish(lua_resume(co, L, 0, &nres), nres);
}

__attribute__((export_name("poc_resume"))) int32_t poc_resume(char *event, int32_t len) {
    lua_pushlstring(co, event, (size_t)len);
    free(event);
    int nres = 0;
    return finish(lua_resume(co, L, 1, &nres), nres);
}
