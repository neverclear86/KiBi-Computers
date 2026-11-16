/*
 * Lua の parallel ライブラリ（#95。ADR 0095）。CC:Tweaked の parallel の振る舞いに合わせて、自分で書いたもの（CC のコードは使わない）。
 *
 * - parallel.waitForAny(f, ...): 関数をそれぞれコルーチンで動かし、どれかが終わったら、その番号を返す
 * - parallel.waitForAll(f, ...): すべてが終わるまで動かす
 *
 * コルーチンが yield した値をフィルターとして覚え、次のイベントは、フィルターが nil か名前が合うか "terminate" のコルーチンにだけ渡す。
 * すべてが yield したら、次のイベントを kibi_lua_pull_raw で取る（メインスレッドならその場で待ち、コルーチンの中なら yield する。
 * 入れ子の parallel や、ユーザーのスケジューラーの中でも動く）。ユーザーが上書きした os.pullEventRaw は通さない。
 *
 * スケジューラーの本体は Lua で書き、初めて呼ばれたときに読み込んでレジストリにしまう（起動のたびに Lua のコードを読まない。
 * 起動時に作るのは C の関数 2 つのテーブルだけ）
 */
#include "lauxlib.h"
#include "lua.h"
#include "lualib.h"

#include "kibi_lua.h"

/* レジストリの key（静的な変数のアドレス）。{waitForAny の本体, waitForAll の本体} */
static char IMPL_KEY;

/*
 * 本体。引数は (次のイベントを取る関数, coroutine.create, resume, status, table.pack, unpack)。
 * 標準ライブラリの関数は読み込むときに受け取る（ユーザーがグローバルの coroutine・table を差し替えても動くように）
 */
static const char IMPL_SOURCE[] =
    "local next_event, create, resume, status, pack, unpack = ...\n"
    /* [limit] 個まで減ったら、最後に終わった関数の番号を返す */
    "local function run(limit, ...)\n"
    "  local count = select('#', ...)\n"
    "  if count == 0 then return 0 end\n"
    "  local cos, filters = {}, {}\n"
    "  for i = 1, count do cos[i] = create((select(i, ...))) end\n"
    "  local living = count\n"
    "  local ev = {n = 0}\n"
    "  while true do\n"
    "    local name = ev[1]\n"
    "    for i = 1, count do\n"
    "      local co = cos[i]\n"
    "      if co ~= nil then\n"
    "        local want = filters[i]\n"
    "        if want == nil or want == name or name == 'terminate' then\n"
    "          local ok, r = resume(co, unpack(ev, 1, ev.n))\n"
    "          if not ok then error(r, 0) end\n"
    "          if status(co) == 'dead' then\n"
    "            cos[i] = nil\n"
    "            living = living - 1\n"
    "            if living <= limit then return i end\n"
    "          else\n"
    "            filters[i] = r\n"
    "          end\n"
    "        end\n"
    "      end\n"
    "    end\n"
    "    ev = pack(next_event())\n"
    "  end\n"
    "end\n"
    "return function(...) return run(select('#', ...) - 1, ...) end,\n"
    "  function(...) run(0, ...) end\n";

/* 本体を読み込んで {waitForAny, waitForAll} のテーブルをレジストリにしまい、積む */
static void push_impl(lua_State *L) {
    if (lua_rawgetp(L, LUA_REGISTRYINDEX, &IMPL_KEY) == LUA_TTABLE) return;
    lua_pop(L, 1);
    if (luaL_loadbufferx(L, IMPL_SOURCE, sizeof IMPL_SOURCE - 1, "=parallel", "t") != LUA_OK) lua_error(L);
    lua_pushcfunction(L, kibi_lua_pull_raw);
    luaL_getsubtable(L, LUA_REGISTRYINDEX, LUA_LOADED_TABLE);
    lua_getfield(L, -1, LUA_COLIBNAME);
    lua_getfield(L, -2, LUA_TABLIBNAME);
    lua_remove(L, -3);
    /* [chunk, pull, coroutine, table] */
    lua_getfield(L, -2, "create");
    lua_getfield(L, -3, "resume");
    lua_getfield(L, -4, "status");
    lua_getfield(L, -4, "pack");
    lua_getfield(L, -5, "unpack");
    lua_remove(L, -6);
    lua_remove(L, -6);
    lua_call(L, 6, 2);
    lua_createtable(L, 2, 0);
    lua_insert(L, -3);
    lua_rawseti(L, -3, 2);
    lua_rawseti(L, -2, 1);
    lua_pushvalue(L, -1);
    lua_rawsetp(L, LUA_REGISTRYINDEX, &IMPL_KEY);
}

/* 本体が戻った（yield をはさんでもよい）後。スタックには本体の結果だけが残っている */
static int wait_k(lua_State *L, int status, lua_KContext ctx) {
    (void)status;
    (void)ctx;
    return lua_gettop(L);
}

/* parallel.waitForAny・waitForAll。upvalue 1 は本体の番号（1 か 2） */
static int l_wait(lua_State *L) {
    int n = lua_gettop(L);
    for (int i = 1; i <= n; i++) luaL_checktype(L, i, LUA_TFUNCTION);
    push_impl(L);
    lua_rawgeti(L, -1, (lua_Integer)lua_tointeger(L, lua_upvalueindex(1)));
    lua_remove(L, -2);
    lua_insert(L, 1);
    /* 本体の中のコルーチンが yield できるように、続きの関数を付けて呼ぶ（入れ子の parallel） */
    lua_callk(L, n, LUA_MULTRET, 0, wait_k);
    return wait_k(L, LUA_OK, 0);
}

int luaopen_parallel(lua_State *L) {
    lua_createtable(L, 0, 2);
    lua_pushinteger(L, 1);
    lua_pushcclosure(L, l_wait, 1);
    lua_setfield(L, -2, "waitForAny");
    lua_pushinteger(L, 2);
    lua_pushcclosure(L, l_wait, 1);
    lua_setfield(L, -2, "waitForAll");
    return 1;
}
