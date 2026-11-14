/*
 * Lua の peripheral ライブラリ（#94。ADR 0094）。CC:Tweaked の peripheral を手本にし、kibi:device/bus（ADR 0077）の上に作る。
 *
 * - 名前（CC の側面・ネットワーク名）は、デバイスの address（小文字の UUID）。大文字は小文字に直してから開く
 * - fd を持ち続けない。call・wrap で作った関数は、呼ぶたびに open → invoke → drop する（fd はプロセスあたり 256、デバイスは最大 256 なので、
 *   全デバイスの fd を持ちっぱなしにすると mfile に当たるため）
 * - wrap の関数は、作ったときの種類を覚えておき、呼ぶときに種類が違えば（同じ address に別のデバイスがつながったら）"No such peripheral"
 * - 値の写し方・エラーの文は ADR 0094 の表のとおり
 */
#include <stdint.h>
#include <string.h>

#include "lauxlib.h"
#include "lua.h"

#include "kibi_device.h"
#include "kibi_lua.h"

#define BYTES_MT "kibi.bytes"
#define PERIPHERAL_NAME "peripheral"

/* デバイスの上限（DeviceLimits） */
#define MAX_VALUES 32
#define MAX_PAYLOAD_BYTES (64 * 1024)
#define MAX_METHOD_BYTES 64
#define ADDRESS_BYTES 36

/* ホストが切り詰める上限（DeviceLimits）: call-error の message（バイト）、メソッドの数 */
#define MAX_ERROR_MESSAGE_BYTES 1024
#define MAX_METHODS 256

/*
 * 結果の見積もり（kibi_lua_check_memory に渡す）。invoke は値 32 個の並び＋文字列 64 KiB＋確保ごとの揃えの余り（エラーのときは
 * エラーの文 MAX_ERROR_MESSAGE_BYTES。成功と排他なので余りに含める）
 */
#define INVOKE_RESULT_BYTES (MAX_VALUES * 16 + MAX_PAYLOAD_BYTES + MAX_ERROR_MESSAGE_BYTES + 1024)
/* list-devices（最大 256 台。address 36 バイト＋種類＋並び 16 バイト）・methods・info の見積もり */
#define LIST_RESULT_BYTES (256 * (16 + ADDRESS_BYTES + 64))
/* methods は 1 件あたり 12 バイト＋名前 64 バイト（最大 MAX_METHODS 件）＋並びの揃え */
#define METHODS_RESULT_BYTES (MAX_METHODS * (12 + MAX_METHOD_BYTES) + 1024)
#define INFO_RESULT_BYTES 512

/* ---- 名前（address） ---- */

typedef struct {
    char buf[ADDRESS_BYTES + 1];
    const char *ptr;
    size_t len;
} address_t;

/* 名前 [s] を小文字にする（36 バイトでなければそのまま。open が detached を返す） */
static void address_of(address_t *a, const char *s, size_t len) {
    if (len != ADDRESS_BYTES) {
        a->ptr = s;
        a->len = len;
        return;
    }
    for (size_t i = 0; i < len; i++) {
        char c = s[i];
        a->buf[i] = (c >= 'A' && c <= 'Z') ? (char)(c - 'A' + 'a') : c;
    }
    a->buf[len] = '\0';
    a->ptr = a->buf;
    a->len = len;
}

/* wrap が返したテーブルなら、メタテーブルの [field]（name・type）を積んで 1。そうでなければ何も積まずに 0 */
static int wrapped_field(lua_State *L, int idx, const char *field) {
    if (lua_type(L, idx) != LUA_TTABLE || !lua_getmetatable(L, idx)) return 0;
    if (lua_getfield(L, -1, "__name") != LUA_TSTRING || strcmp(lua_tostring(L, -1), PERIPHERAL_NAME) != 0) {
        lua_pop(L, 2);
        return 0;
    }
    lua_pop(L, 1);
    lua_getfield(L, -1, field);
    lua_remove(L, -2);
    return 1;
}

/* 引数 [idx] の名前を読む（文字列か、wrap が返したテーブル） */
static void check_address(lua_State *L, int idx, address_t *a) {
    size_t len;
    const char *s;
    if (wrapped_field(L, idx, "name")) {
        s = lua_tolstring(L, -1, &len);
        lua_replace(L, idx);
        if (s == NULL) luaL_argerror(L, idx, "not a peripheral");
    } else {
        s = luaL_checklstring(L, idx, &len);
    }
    address_of(a, s, len);
}

/* ---- エラー ---- */

/* call-error を Lua のエラーにする（戻らない）。message は cabi_realloc の場所にあるので、積んだ後に戻す */
static int raise_call_error(lua_State *L, const kibi_device_call_error_t *e, const char *method, size_t method_len) {
    luaL_where(L, 1);
    switch (e->tag) {
    case KIBI_DEVICE_CALL_ERROR_DETACHED:
        lua_pushliteral(L, "No such peripheral");
        break;
    case KIBI_DEVICE_CALL_ERROR_NO_SUCH_METHOD:
        lua_pushliteral(L, "No such method ");
        lua_pushlstring(L, method, method_len);
        lua_concat(L, 2);
        break;
    case KIBI_DEVICE_CALL_ERROR_BAD_ARGUMENTS:
    case KIBI_DEVICE_CALL_ERROR_FAILED:
        lua_pushlstring(L, e->val.message.ptr, e->val.message.len);
        break;
    default:
        lua_pushfstring(L, "device call failed: %s", kibi_lua_error_name(e->val.kernel));
        break;
    }
    kibi_lua_arena_reset();
    lua_concat(L, 2);
    return lua_error(L);
}

/* ---- 開く ---- */

/* 開いて fd を返す。外れている（見つからない）なら -1。ほかの誤りは Lua のエラー */
static int64_t open_device(lua_State *L, const address_t *a) {
    kibi_lua_arena_reset();
    kibi_lua_check_memory(L, INFO_RESULT_BYTES);
    kibi_device_result_handle_t r;
    kibi_device_open(a->ptr, a->len, &r);
    if (!r.is_err) {
        kibi_lua_arena_reset();
        return r.val.ok;
    }
    if (r.val.err.tag == KIBI_DEVICE_CALL_ERROR_DETACHED) {
        kibi_lua_arena_reset();
        return -1;
    }
    raise_call_error(L, &r.val.err, NULL, 0);
    return -1;
}

/* 開いた fd の結果の場所を取る。取れなければ fd を閉じてから "not enough memory" の Lua のエラーにする */
static void reserve_or_close(lua_State *L, uint32_t fd, size_t need) {
    kibi_lua_arena_reset();
    if (!kibi_lua_reserve(need)) {
        kibi_device_drop(fd);
        kibi_lua_check_memory(L, need);
    }
}

/*
 * 開いたデバイスの種類を積む。fd は閉じる（import の結果を読んだらすぐ閉じてから Lua の値に写す。
 * 写す途中の Lua の確保でメモリ不足の longjmp が起きても、fd が漏れないように）
 */
static void push_kind(lua_State *L, uint32_t fd) {
    reserve_or_close(L, fd, INFO_RESULT_BYTES);
    kibi_device_info_t info;
    kibi_device_info(fd, &info);
    kibi_device_drop(fd);
    lua_pushlstring(L, info.kind.ptr, info.kind.len);
    kibi_lua_arena_reset();
}

/* ---- 値の写し方 ---- */

/* peripheral.bytes が包んだテーブルなら、中の文字列を返す（なければ NULL） */
static const char *bytes_of(lua_State *L, int idx, size_t *len) {
    if (lua_type(L, idx) != LUA_TTABLE || !lua_getmetatable(L, idx)) return NULL;
    luaL_getmetatable(L, BYTES_MT);
    int same = lua_rawequal(L, -1, -2);
    lua_pop(L, 2);
    if (!same) return NULL;
    lua_rawgeti(L, idx, 1);
    const char *s = lua_tolstring(L, -1, len);
    lua_pop(L, 1); /* 文字列はテーブルが持っているので、ポインタは引数が積まれている間は有効 */
    return s;
}

/* スタックの [idx] の Lua の値を value にする（引数の番号 [argn] はエラーの文に使う）。text・bytes の長さを返す */
static size_t to_value(lua_State *L, int idx, int argn, kibi_device_value_t *v) {
    memset(v, 0, sizeof *v);
    switch (lua_type(L, idx)) {
    case LUA_TNIL:
    case LUA_TNONE:
        v->tag = KIBI_DEVICE_VALUE_NONE;
        return 0;
    case LUA_TBOOLEAN:
        v->tag = KIBI_DEVICE_VALUE_BOOLEAN;
        v->val.boolean = (uint8_t)lua_toboolean(L, idx);
        return 0;
    case LUA_TNUMBER:
        if (lua_isinteger(L, idx)) {
            v->tag = KIBI_DEVICE_VALUE_INTEGER;
            v->val.integer = (int64_t)lua_tointeger(L, idx);
        } else {
            double d = (double)lua_tonumber(L, idx);
            /* 整数の値の float（10/2 など）は integer にする（getLong のメソッドが受けられるように。getDouble は integer も受ける）。
             * -0.0・NaN・±inf・int64 に入らないものは float のまま */
            if (d == d && d >= -9223372036854775808.0 && d < 9223372036854775808.0 && d == (double)(int64_t)d &&
                !(d == 0.0 && 1.0 / d < 0)) {
                v->tag = KIBI_DEVICE_VALUE_INTEGER;
                v->val.integer = (int64_t)d;
            } else {
                v->tag = KIBI_DEVICE_VALUE_FLOAT;
                v->val.floating = d;
            }
        }
        return 0;
    case LUA_TSTRING: {
        size_t len;
        const char *s = lua_tolstring(L, idx, &len);
        if (!kibi_lua_utf8_valid(s, len)) luaL_argerror(L, argn, "string is not valid UTF-8 (wrap bytes with peripheral.bytes)");
        v->tag = KIBI_DEVICE_VALUE_TEXT;
        v->val.text.ptr = s;
        v->val.text.len = len;
        return len;
    }
    case LUA_TTABLE: {
        size_t len;
        const char *s = bytes_of(L, idx, &len);
        if (s != NULL) {
            v->tag = KIBI_DEVICE_VALUE_BYTES;
            v->val.bytes.ptr = (const uint8_t *)s;
            v->val.bytes.len = len;
            return len;
        }
        break;
    }
    default:
        break;
    }
    luaL_argerror(L, argn, lua_pushfstring(L, "cannot pass a %s to a device", luaL_typename(L, idx)));
    return 0;
}

static void push_value(lua_State *L, const kibi_device_value_t *v) {
    switch (v->tag) {
    case KIBI_DEVICE_VALUE_INTEGER:
        lua_pushinteger(L, (lua_Integer)v->val.integer);
        break;
    case KIBI_DEVICE_VALUE_FLOAT:
        lua_pushnumber(L, (lua_Number)v->val.floating);
        break;
    case KIBI_DEVICE_VALUE_BOOLEAN:
        lua_pushboolean(L, v->val.boolean != 0);
        break;
    case KIBI_DEVICE_VALUE_TEXT:
        lua_pushlstring(L, v->val.text.ptr, v->val.text.len);
        break;
    case KIBI_DEVICE_VALUE_BYTES:
        lua_pushlstring(L, (const char *)v->val.bytes.ptr, v->val.bytes.len);
        break;
    default:
        lua_pushnil(L);
        break;
    }
}

/* ---- メソッドの名前 ---- */

/* キャメルケース（getSize）をケバブケース（get-size）に直す。大文字がなければそのまま。直したものは buf に入れる */
static const char *method_name(const char *m, size_t len, char *buf, size_t cap, size_t *out_len) {
    int upper = 0;
    for (size_t i = 0; i < len; i++) {
        if (m[i] >= 'A' && m[i] <= 'Z') upper = 1;
    }
    if (!upper) {
        *out_len = len;
        return m;
    }
    size_t n = 0;
    for (size_t i = 0; i < len; i++) {
        char c = m[i];
        if (c >= 'A' && c <= 'Z') {
            if (n + 2 > cap) return NULL;
            buf[n++] = '-';
            buf[n++] = (char)(c - 'A' + 'a');
        } else {
            if (n + 1 > cap) return NULL;
            buf[n++] = c;
        }
    }
    *out_len = n;
    return buf;
}

/* ケバブケース（get-size）のキャメルケースの別名（getSize）を積んで 1。付けられない（- がない・変えた後も - が残る）なら 0 */
static int push_camel_alias(lua_State *L, const char *m, size_t len) {
    if (memchr(m, '-', len) == NULL) return 0;
    luaL_Buffer b;
    luaL_buffinit(L, &b);
    for (size_t i = 0; i < len; i++) {
        char c = m[i];
        if (c == '-') {
            if (i + 1 >= len || m[i + 1] < 'a' || m[i + 1] > 'z') {
                luaL_pushresult(&b);
                lua_pop(L, 1);
                return 0;
            }
            luaL_addchar(&b, (char)(m[i + 1] - 'a' + 'A'));
            i++;
        } else {
            luaL_addchar(&b, c);
        }
    }
    luaL_pushresult(&b);
    return 1;
}

/* ---- 呼び出し ---- */

/*
 * [a] のデバイスの [method] を、スタックの [first] から上の引数で呼び、結果を積んで数を返す。
 * [kind] が NULL でなければ、開いたデバイスの種類がそれと違うとき "No such peripheral"。
 * 引数の番号は [first] を [argbase] として数える
 */
static int invoke(lua_State *L, const address_t *a, const char *kind, size_t kind_len, const char *method, size_t method_len,
                  int first, int argbase) {
    char mbuf[MAX_METHOD_BYTES * 2];
    size_t mlen;
    const char *m = method_name(method, method_len, mbuf, sizeof mbuf, &mlen);
    if (m == NULL || mlen > MAX_METHOD_BYTES) return luaL_error(L, "method name too long (max %d bytes)", MAX_METHOD_BYTES);
    int top = lua_gettop(L);
    int n = top >= first ? top - first + 1 : 0;
    if (n > MAX_VALUES) return luaL_error(L, "too many arguments (max %d)", MAX_VALUES);
    kibi_device_value_t args[MAX_VALUES];
    size_t payload = 0;
    for (int i = 0; i < n; i++) {
        payload += to_value(L, first + i, argbase + i, &args[i]);
    }
    if (payload > MAX_PAYLOAD_BYTES) return luaL_error(L, "arguments too large (max %d bytes of strings)", MAX_PAYLOAD_BYTES);

    int64_t fd = open_device(L, a);
    if (fd < 0) return luaL_error(L, "No such peripheral");
    if (kind != NULL) {
        reserve_or_close(L, (uint32_t)fd, INFO_RESULT_BYTES);
        kibi_device_info_t info;
        kibi_device_info((uint32_t)fd, &info);
        int same = info.kind.len == kind_len && memcmp(info.kind.ptr, kind, kind_len) == 0;
        kibi_lua_arena_reset();
        if (!same) {
            kibi_device_drop((uint32_t)fd);
            return luaL_error(L, "No such peripheral");
        }
    }
    reserve_or_close(L, (uint32_t)fd, INVOKE_RESULT_BYTES);
    kibi_device_result_values_t r;
    kibi_device_invoke((uint32_t)fd, m, mlen, args, (size_t)n, &r);
    kibi_device_drop((uint32_t)fd);
    if (r.is_err) return raise_call_error(L, &r.val.err, m, mlen);
    size_t count = r.val.ok.len;
    if (count > INT32_MAX || !lua_checkstack(L, (int)count + LUA_MINSTACK)) {
        kibi_lua_arena_reset();
        return luaL_error(L, "too many results");
    }
    for (size_t i = 0; i < count; i++) push_value(L, &r.val.ok.ptr[i]);
    kibi_lua_arena_reset();
    return (int)count;
}

/* peripheral.call(name, method, ...) */
static int l_call(lua_State *L) {
    address_t a;
    check_address(L, 1, &a);
    size_t mlen;
    const char *m = luaL_checklstring(L, 2, &mlen);
    return invoke(L, &a, NULL, 0, m, mlen, 3, 3);
}

/* wrap が作る関数。上位値は address・種類・メソッドの名前 */
static int l_wrapped(lua_State *L) {
    size_t alen;
    size_t klen;
    size_t mlen;
    const char *addr = lua_tolstring(L, lua_upvalueindex(1), &alen);
    const char *kind = lua_tolstring(L, lua_upvalueindex(2), &klen);
    const char *m = lua_tolstring(L, lua_upvalueindex(3), &mlen);
    address_t a;
    address_of(&a, addr, alen);
    return invoke(L, &a, kind, klen, m, mlen, 1, 1);
}

/* ---- 列挙 ---- */

/* デバイスの一覧を、address の並びと種類の並びの2つのテーブルにして積む */
static void push_device_list(lua_State *L) {
    kibi_lua_arena_reset();
    kibi_lua_check_memory(L, LIST_RESULT_BYTES);
    kibi_device_list_info_t l;
    kibi_device_list_devices(&l);
    lua_createtable(L, (int)l.len, 0);
    lua_createtable(L, (int)l.len, 0);
    for (size_t i = 0; i < l.len; i++) {
        lua_pushlstring(L, l.ptr[i].address.ptr, l.ptr[i].address.len);
        lua_rawseti(L, -3, (lua_Integer)i + 1);
        lua_pushlstring(L, l.ptr[i].kind.ptr, l.ptr[i].kind.len);
        lua_rawseti(L, -2, (lua_Integer)i + 1);
    }
    kibi_lua_arena_reset();
}

/* peripheral.getNames() */
static int l_getNames(lua_State *L) {
    push_device_list(L);
    lua_pop(L, 1);
    return 1;
}

/* peripheral.isPresent(name) */
static int l_isPresent(lua_State *L) {
    address_t a;
    check_address(L, 1, &a);
    int64_t fd = open_device(L, &a);
    if (fd >= 0) kibi_device_drop((uint32_t)fd);
    lua_pushboolean(L, fd >= 0);
    return 1;
}

/* name の種類を積んで 1。外れていれば 0（何も積まない） */
static int type_of(lua_State *L, int idx) {
    if (wrapped_field(L, idx, "type")) return 1;
    address_t a;
    check_address(L, idx, &a);
    int64_t fd = open_device(L, &a);
    if (fd < 0) return 0;
    push_kind(L, (uint32_t)fd); /* fd は閉じる */
    return 1;
}

/* peripheral.getType(name) */
static int l_getType(lua_State *L) {
    if (!type_of(L, 1)) lua_pushnil(L);
    return 1;
}

/* 種類 [kind] が [type] と合うか（完全一致か、名前空間を除いた名前の一致） */
static int type_matches(const char *kind, size_t klen, const char *type, size_t tlen) {
    if (klen == tlen && memcmp(kind, type, tlen) == 0) return 1;
    const char *colon = memchr(kind, ':', klen);
    if (colon == NULL || memchr(type, ':', tlen) != NULL) return 0;
    size_t rest = klen - (size_t)(colon + 1 - kind);
    return rest == tlen && memcmp(colon + 1, type, tlen) == 0;
}

/* peripheral.hasType(name, type) */
static int l_hasType(lua_State *L) {
    size_t tlen;
    const char *type = luaL_checklstring(L, 2, &tlen);
    if (!type_of(L, 1)) {
        lua_pushnil(L);
        return 1;
    }
    size_t klen;
    const char *kind = lua_tolstring(L, -1, &klen);
    lua_pushboolean(L, kind != NULL && type_matches(kind, klen, type, tlen));
    return 1;
}

/*
 * 開いた fd のメソッドの並びと種類を積む（methods, kind の順）。fd は閉じる。
 * メモリが足りないときも fd を閉じてから Lua のエラーにする。import の結果を読んだらすぐ fd を閉じてから Lua の値に写す
 * （写す途中の Lua の確保でメモリ不足の longjmp が起きても、fd が漏れないように）
 */
static void push_methods_and_kind(lua_State *L, uint32_t fd) {
    reserve_or_close(L, fd, METHODS_RESULT_BYTES + INFO_RESULT_BYTES);
    kibi_device_list_method_info_t l;
    kibi_device_methods(fd, &l);
    kibi_device_info_t info;
    kibi_device_info(fd, &info);
    kibi_device_drop(fd);
    lua_createtable(L, (int)l.len, 0);
    for (size_t i = 0; i < l.len; i++) {
        lua_pushlstring(L, l.ptr[i].name.ptr, l.ptr[i].name.len);
        lua_rawseti(L, -2, (lua_Integer)i + 1);
    }
    lua_pushlstring(L, info.kind.ptr, info.kind.len);
    kibi_lua_arena_reset();
}

/* peripheral.getMethods(name) */
static int l_getMethods(lua_State *L) {
    address_t a;
    check_address(L, 1, &a);
    int64_t fd = open_device(L, &a);
    if (fd < 0) {
        lua_pushnil(L);
        return 1;
    }
    push_methods_and_kind(L, (uint32_t)fd); /* fd は閉じる */
    lua_pop(L, 1);
    return 1;
}

/* peripheral.getName(wrapped) */
static int l_getName(lua_State *L) {
    if (!wrapped_field(L, 1, "name")) return luaL_argerror(L, 1, "not a peripheral");
    return 1;
}

/* address [a] を wrap したテーブルを積んで 1。外れていれば 0 */
static int wrap(lua_State *L, const address_t *a) {
    int64_t fd = open_device(L, a);
    if (fd < 0) return 0;
    push_methods_and_kind(L, (uint32_t)fd); /* methods, kind。fd は閉じる */
    int methods = lua_gettop(L) - 1;
    int kind = lua_gettop(L);
    lua_Integer n = luaL_len(L, methods);
    lua_createtable(L, 0, (int)n);
    int result = lua_gettop(L);
    for (lua_Integer i = 1; i <= n; i++) {
        lua_rawgeti(L, methods, i);
        int name = lua_gettop(L);
        lua_pushlstring(L, a->ptr, a->len);
        lua_pushvalue(L, kind);
        lua_pushvalue(L, name);
        lua_pushcclosure(L, l_wrapped, 3);
        int fn = lua_gettop(L);
        size_t len;
        const char *s = lua_tolstring(L, name, &len);
        if (push_camel_alias(L, s, len)) {
            lua_pushvalue(L, fn);
            lua_rawset(L, result);
        }
        lua_pushvalue(L, name);
        lua_pushvalue(L, fn);
        lua_rawset(L, result);
        lua_settop(L, result);
    }
    lua_createtable(L, 0, 3);
    lua_pushliteral(L, PERIPHERAL_NAME);
    lua_setfield(L, -2, "__name");
    lua_pushlstring(L, a->ptr, a->len);
    lua_setfield(L, -2, "name");
    lua_pushvalue(L, kind);
    lua_setfield(L, -2, "type");
    lua_setmetatable(L, result);
    lua_replace(L, methods);
    lua_settop(L, methods);
    return 1;
}

/* peripheral.wrap(name) */
static int l_wrap(lua_State *L) {
    address_t a;
    check_address(L, 1, &a);
    if (!wrap(L, &a)) lua_pushnil(L);
    return 1;
}

/* peripheral.find(type [, filter]) */
static int l_find(lua_State *L) {
    size_t tlen;
    const char *type = luaL_checklstring(L, 1, &tlen);
    int has_filter = !lua_isnoneornil(L, 2);
    if (has_filter) luaL_checktype(L, 2, LUA_TFUNCTION);
    lua_settop(L, 2);
    push_device_list(L); /* 3: address の並び、4: 種類の並び */
    lua_newtable(L);     /* 5: 結果 */
    lua_Integer found = 0;
    lua_Integer n = luaL_len(L, 3);
    for (lua_Integer i = 1; i <= n; i++) {
        lua_rawgeti(L, 4, i);
        size_t klen;
        const char *kind = lua_tolstring(L, -1, &klen);
        int match = type_matches(kind, klen, type, tlen);
        lua_pop(L, 1);
        if (!match) continue;
        lua_rawgeti(L, 3, i);
        size_t alen;
        const char *addr = lua_tolstring(L, -1, &alen);
        address_t a;
        address_of(&a, addr, alen);
        if (!wrap(L, &a)) {
            lua_pop(L, 1);
            continue;
        }
        /* スタック: ..., address, wrapped */
        if (has_filter) {
            lua_pushvalue(L, 2);
            lua_pushvalue(L, -3);
            lua_pushvalue(L, -3);
            lua_call(L, 2, 1);
            int ok = lua_toboolean(L, -1);
            lua_pop(L, 1);
            if (!ok) {
                lua_pop(L, 2);
                continue;
            }
        }
        lua_rawseti(L, 5, ++found);
        lua_pop(L, 1);
    }
    luaL_checkstack(L, (int)found, "too many peripherals");
    for (lua_Integer i = 1; i <= found; i++) lua_rawgeti(L, 5, i);
    return (int)found;
}

/* peripheral.bytes(s): bytes としてデバイスに渡す文字列を包む */
static int l_bytes(lua_State *L) {
    luaL_checkstring(L, 1);
    lua_createtable(L, 1, 0);
    lua_pushvalue(L, 1);
    lua_rawseti(L, -2, 1);
    /* メタテーブルは初めて使うときに作る（起動時の RAM を増やさないため） */
    if (luaL_newmetatable(L, BYTES_MT)) {
        lua_pushliteral(L, "bytes");
        lua_setfield(L, -2, "__name");
    }
    lua_setmetatable(L, -2);
    return 1;
}

static const luaL_Reg FUNCS[] = {
    {"getNames", l_getNames},
    {"isPresent", l_isPresent},
    {"getType", l_getType},
    {"hasType", l_hasType},
    {"getMethods", l_getMethods},
    {"getName", l_getName},
    {"call", l_call},
    {"wrap", l_wrap},
    {"find", l_find},
    {"bytes", l_bytes},
    {NULL, NULL},
};

int luaopen_peripheral(lua_State *L) {
    luaL_newlib(L, FUNCS);
    return 1;
}
