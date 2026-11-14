/*
 * Lua の linit.c の代わり（#94。ADR 0094）。linit.c は、ライブラリを足すときに差し替える前提のファイルなので、
 * ビルドから外してこれを使う（Lua のソースは書き換えない）。
 *
 * 標準ライブラリを linit.c と同じ順に開いたあと、KiBi の peripheral（グローバルと package.loaded）を開き、
 * os のテーブルに KiBi の関数を足す。どれも C で作るので、起動のたびに Lua のコードを読み込まない（REPL 1,000 台の起動の時間とメモリを増やさない）
 */
#include "lauxlib.h"
#include "lua.h"
#include "lualib.h"

#include "kibi_lua.h"

static const luaL_Reg LIBS[] = {
    {LUA_GNAME, luaopen_base},
    {LUA_LOADLIBNAME, luaopen_package},
    {LUA_COLIBNAME, luaopen_coroutine},
    {LUA_TABLIBNAME, luaopen_table},
    {LUA_IOLIBNAME, luaopen_io},
    {LUA_OSLIBNAME, luaopen_os},
    {LUA_STRLIBNAME, luaopen_string},
    {LUA_MATHLIBNAME, luaopen_math},
    {LUA_UTF8LIBNAME, luaopen_utf8},
    {LUA_DBLIBNAME, luaopen_debug},
    {"peripheral", luaopen_peripheral},
    {NULL, NULL},
};

LUALIB_API void luaL_openlibs(lua_State *L) {
    for (const luaL_Reg *lib = LIBS; lib->func; lib++) {
        luaL_requiref(L, lib->name, lib->func, 1);
        lua_pop(L, 1);
    }
    lua_getglobal(L, LUA_OSLIBNAME);
    kibi_lua_os_extend(L);
    lua_pop(L, 1);
}
