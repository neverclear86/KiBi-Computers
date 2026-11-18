/*
 * Lua の colors・colours ライブラリ（#724。ADR 0724）。CC: Tweaked の colors の振る舞いに合わせて、自分で書いたもの
 * （CC のコードは使わない）。色は 2 の 0〜15 乗のビット（white = 1 … black = 32768）で、組み合わせは OR。
 *
 * colours は colors と同じだが、gray・lightGray の代わりに grey・lightGrey を持つ（CC と同じ）。
 *
 * ライブラリの表を初めて触ったときに詰める仕組み（kibi_lua_lazy_lib）もここに置く。keys・colors・term・fs の表は、
 * 起動のたびには作らない（REPL 1,000 台の起動の RAM を増やさない）
 */
#include <math.h>
#include <stdint.h>
#include <string.h>

#include "lauxlib.h"
#include "lua.h"

#include "kibi_lua.h"

/* ---- 初めて触ったときに詰める表 ---- */

/* 引数 1 の表がまだ詰めていなければ詰め、メタテーブルを外す */
static void lazy_fill(lua_State *L) {
    if (!lua_getmetatable(L, 1)) return;
    lua_pushliteral(L, "__fill");
    lua_rawget(L, -2);
    lua_CFunction fill = lua_tocfunction(L, -1);
    lua_pop(L, 2);
    if (fill == NULL) return;
    lua_pushcfunction(L, fill);
    lua_pushvalue(L, 1);
    /* 詰める途中でメモリ不足になったら、メタテーブルを残したまま Lua のエラーにする（次に触ったときに続きを詰める） */
    lua_call(L, 1, 0);
    lua_pushnil(L);
    lua_setmetatable(L, 1);
}

/* __index(t, k) */
static int lazy_index(lua_State *L) {
    lazy_fill(L);
    lua_settop(L, 2);
    lua_rawget(L, 1);
    return 1;
}

/* pairs の next（グローバルの next はユーザーが差し替えられるので使わない） */
static int lazy_next(lua_State *L) {
    luaL_checktype(L, 1, LUA_TTABLE);
    lua_settop(L, 2);
    if (lua_next(L, 1)) return 2;
    lua_pushnil(L);
    return 1;
}

/* __pairs(t) */
static int lazy_pairs(lua_State *L) {
    lazy_fill(L);
    lua_pushcfunction(L, lazy_next);
    lua_pushvalue(L, 1);
    lua_pushnil(L);
    return 3;
}

void kibi_lua_lazy_lib(lua_State *L, lua_CFunction fill) {
    lua_newtable(L);
    lua_createtable(L, 0, 3);
    lua_pushcfunction(L, lazy_index);
    lua_setfield(L, -2, "__index");
    lua_pushcfunction(L, lazy_pairs);
    lua_setfield(L, -2, "__pairs");
    lua_pushcfunction(L, fill);
    lua_setfield(L, -2, "__fill");
    lua_setmetatable(L, -2);
}

void kibi_lua_lazy_set(lua_State *L, const char *name, lua_CFunction fn) {
    lua_pushstring(L, name);
    if (lua_rawget(L, 1) != LUA_TNIL) {
        lua_pop(L, 1);
        return;
    }
    lua_pop(L, 1);
    lua_pushstring(L, name);
    lua_pushcfunction(L, fn);
    lua_rawset(L, 1);
}

/* 表 1 の [name] がまだなければ整数 [v] を入れる */
static void lazy_set_integer(lua_State *L, const char *name, lua_Integer v) {
    lua_pushstring(L, name);
    if (lua_rawget(L, 1) != LUA_TNIL) {
        lua_pop(L, 1);
        return;
    }
    lua_pop(L, 1);
    lua_pushstring(L, name);
    lua_pushinteger(L, v);
    lua_rawset(L, 1);
}

/* ---- 色 ---- */

/* CC: Tweaked の文書の既定のパレット */
const uint32_t kibi_lua_default_palette[16] = {
    0xF0F0F0, 0xF2B233, 0xE57FD8, 0x99B2F2, 0xDEDE6C, 0x7FCC19, 0xF2B2CC, 0x4C4C4C,
    0x999999, 0x4C99B2, 0xB266E5, 0x3366CC, 0x7F664C, 0x57A64E, 0xCC4C4C, 0x111111,
};

/* 色の名前（[i] は 2^i）。colours は 7・8 番だけ綴りが違う */
static const char *const COLOR_NAMES[16] = {
    "white", "orange", "magenta", "lightBlue", "yellow", "lime", "pink", "gray",
    "lightGray", "cyan", "purple", "blue", "brown", "green", "red", "black",
};

static const char BLIT_DIGITS[] = "0123456789abcdef";

/* colors.combine(...) */
static int l_combine(lua_State *L) {
    int n = lua_gettop(L);
    lua_Integer r = 0;
    for (int i = 1; i <= n; i++) r |= luaL_checkinteger(L, i);
    lua_pushinteger(L, r);
    return 1;
}

/* colors.subtract(colors, ...) */
static int l_subtract(lua_State *L) {
    lua_Integer r = luaL_checkinteger(L, 1);
    int n = lua_gettop(L);
    for (int i = 2; i <= n; i++) r &= ~luaL_checkinteger(L, i);
    lua_pushinteger(L, r);
    return 1;
}

/* colors.test(colors, color) */
static int l_test(lua_State *L) {
    lua_Integer set = luaL_checkinteger(L, 1);
    lua_Integer c = luaL_checkinteger(L, 2);
    lua_pushboolean(L, (set & c) == c);
    return 1;
}

/* 0〜1 の値を 0〜255 にする（切り捨て。範囲の外は端に寄せる） */
static lua_Integer channel(lua_State *L, int idx) {
    lua_Number v = luaL_checknumber(L, idx);
    if (!(v > 0)) return 0;
    if (v >= 1) return 255;
    return (lua_Integer)floor(v * 255);
}

/* colors.packRGB(r, g, b) */
static int l_packRGB(lua_State *L) {
    lua_pushinteger(L, (channel(L, 1) << 16) | (channel(L, 2) << 8) | channel(L, 3));
    return 1;
}

/* colors.unpackRGB(rgb) */
static int l_unpackRGB(lua_State *L) {
    lua_Integer rgb = luaL_checkinteger(L, 1);
    lua_pushnumber(L, (lua_Number)((rgb >> 16) & 0xFF) / 255);
    lua_pushnumber(L, (lua_Number)((rgb >> 8) & 0xFF) / 255);
    lua_pushnumber(L, (lua_Number)(rgb & 0xFF) / 255);
    return 3;
}

/* colors.rgb8(...): 引数が 1 つなら unpackRGB、ほかは packRGB（CC では非推奨） */
static int l_rgb8(lua_State *L) {
    if (lua_gettop(L) == 1) return l_unpackRGB(L);
    return l_packRGB(L);
}

/* colors.toBlit(color): 2^i の i を 16 進の 1 文字にする（いちばん上のビットを見る。0 以下と 2^16 以上はエラー） */
static int l_toBlit(lua_State *L) {
    lua_Integer c = luaL_checkinteger(L, 1);
    if (c <= 0 || c > 0xFFFF) return luaL_error(L, "Colour out of range");
    int i = 15;
    while (!(c & ((lua_Integer)1 << i))) i--;
    lua_pushlstring(L, &BLIT_DIGITS[i], 1);
    return 1;
}

/* colors.fromBlit(hex): 16 進の 1 文字を色にする。ほかは nil */
static int l_fromBlit(lua_State *L) {
    size_t len;
    const char *s = luaL_checklstring(L, 1, &len);
    int v = -1;
    if (len == 1) {
        char c = s[0];
        if (c >= '0' && c <= '9') v = c - '0';
        else if (c >= 'a' && c <= 'f') v = c - 'a' + 10;
    }
    if (v < 0) {
        lua_pushnil(L);
        return 1;
    }
    lua_pushinteger(L, (lua_Integer)1 << v);
    return 1;
}

static void fill_common(lua_State *L, int british) {
    for (int i = 0; i < 16; i++) {
        const char *name = COLOR_NAMES[i];
        if (british && i == 7) name = "grey";
        if (british && i == 8) name = "lightGrey";
        lazy_set_integer(L, name, (lua_Integer)1 << i);
    }
    kibi_lua_lazy_set(L, "combine", l_combine);
    kibi_lua_lazy_set(L, "subtract", l_subtract);
    kibi_lua_lazy_set(L, "test", l_test);
    kibi_lua_lazy_set(L, "packRGB", l_packRGB);
    kibi_lua_lazy_set(L, "unpackRGB", l_unpackRGB);
    kibi_lua_lazy_set(L, "rgb8", l_rgb8);
    kibi_lua_lazy_set(L, "toBlit", l_toBlit);
    kibi_lua_lazy_set(L, "fromBlit", l_fromBlit);
}

static int fill_colors(lua_State *L) {
    fill_common(L, 0);
    return 0;
}

static int fill_colours(lua_State *L) {
    fill_common(L, 1);
    return 0;
}

int luaopen_colors(lua_State *L) {
    kibi_lua_lazy_lib(L, fill_colors);
    return 1;
}

int luaopen_colours(lua_State *L) {
    kibi_lua_lazy_lib(L, fill_colours);
    return 1;
}
