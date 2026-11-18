/*
 * Lua の peripheral ライブラリ（#94。ADR 0094）。CC:Tweaked の peripheral を手本にし、kibi:device/bus（ADR 0077）の上に作る。
 *
 * - 名前（CC の側面・ネットワーク名）は、デバイスの address（小文字の UUID）。大文字は小文字に直してから開く
 * - fd を持ち続けない。call・wrap で作った関数は、呼ぶたびに open → invoke → drop する（コルーチンの中は下のとおり）（fd はプロセスあたり 256、デバイスは最大 256 なので、
 *   全デバイスの fd を持ちっぱなしにすると mfile に当たるため）
 * - wrap の関数は、作ったときの種類を覚えておき、呼ぶときに種類が違えば（同じ address に別のデバイスがつながったら）"No such peripheral"
 * - 値の写し方・エラーの文は ADR 0094 の表のとおり
 *
 * コルーチンの中（#744。ADR 0744）: メインスレッドのメソッドは CC と同じく task id を取って "task_complete" を yield し、
 * 待つ間にほかのコルーチンが動く。open → start → device を drop → すぐ take（直接のメソッドと、その場で失敗するものはここで返る）→
 * 終わっていなければ pending-call の fd を「待っている呼び出しの表」（C の静的配列）に入れて yield する。
 * 表の fd は、os の待ち（kibi_lua_os.c の pull）が poll_oneoff の FD_READ で待ち、終わったものを kibi_lua_tasks_collect が
 * "task_complete" のイベントにして FIFO に積む（写し終えてから drop して表から消す）
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

/* ---- 待っている呼び出しの表（#744。ADR 0744） ---- */

/*
 * start で積んだ、まだ結果を Lua に写していない呼び出し。数はカーネルの上限（DeviceLimits.MAX_PENDING_CALLS）と同じ。
 * 線形メモリ（.bss）にあるので、保存・復元をまたいで fd・id とそろったまま戻る。起動時には何も作らない
 */
#define MAX_TASKS 16

typedef struct {
    int32_t fd;            /* pending-call の fd */
    int64_t id;            /* task id（"task_complete" の 2 つ目の値） */
    uint8_t method_len;    /* メソッドの名前（"No such method" の文に使う） */
    char method[MAX_METHOD_BYTES];
} task_t;

static task_t tasks[MAX_TASKS];
static int task_count = 0;
/* task id（単調に増やす。タイマーの id とは別） */
static int64_t next_task_id = 0;

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

/* call-error の文（位置なし）を積む。message は cabi_realloc の場所にあるので、積んだ後に呼んだ側が戻す */
static void push_call_error(lua_State *L, const kibi_device_call_error_t *e, const char *method, size_t method_len) {
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
}

/* ---- 結果を写す（#756。ADR 0744） ---- */

/*
 * 写す関数 [f] を、引数 [a]・[b]（lightuserdata）で lua_pcall し、積んだ値（[nresults] 個。LUA_MULTRET なら全部）を残して、その数を返す。
 * import の結果（arena にある）を Lua の値に写すところは、すべてここを通す。写す途中の Lua の確保で GC が走ると、__gc の中から
 * peripheral の関数や os.queueEvent などが入れ子で arena を戻したり、待っている呼び出しの表を詰めたりするので、写す間は
 * kibi_lua_arena_hold で囲む（囲んでいる間は、入れ子の peripheral の関数がエラーになり、os の回収は arena を使うものをしない）。
 * 写し終えたら（エラーでも）release して arena を戻し、エラーなら投げ直す。
 * 積む 3 つ（軽い C 関数と lightuserdata）は確保しないが、スタックの余裕は呼ぶ側が import の前に確かめておく（ensure_copy_stack）。
 * import の結果を受け取ってからここまでの間は、Lua の確保を伴う API を呼ばない
 */
static int copy_held(lua_State *L, lua_CFunction f, const void *a, const void *b, int nresults) {
    int base = lua_gettop(L);
    lua_pushcfunction(L, f);
    lua_pushlightuserdata(L, (void *)a);
    lua_pushlightuserdata(L, (void *)b);
    kibi_lua_arena_hold();
    int status = lua_pcall(L, 2, nresults, 0);
    kibi_lua_arena_release();
    kibi_lua_arena_reset();
    if (status != LUA_OK) return lua_error(L);
    return lua_gettop(L) - base;
}

/* copy_held が積む分（関数と引数 2 つ）のスタックの余裕を確かめる。import の前（fd を開く前）に呼ぶ */
static void ensure_copy_stack(lua_State *L) {
    luaL_checkstack(L, 3, NULL);
}

/*
 * 結果を写している途中（写す途中の __gc から入れ子で呼ばれたとき）なら、エラーにする。arena を戻す前、fd を開く前に呼ぶ。
 * 入れ子の呼び出しは arena を戻して取り直し、外側が写している結果の場所を解放してしまうため（#756）。
 * os.pullEvent の "cannot wait for events while a result is being copied" と同じ形（位置なし）
 */
static void refuse_while_copying(lua_State *L) {
    if (kibi_lua_arena_held()) {
        lua_pushliteral(L, "cannot call a peripheral while a result is being copied");
        lua_error(L);
    }
}

/* メソッドの名前（copy_call_error に渡す） */
typedef struct {
    const char *ptr;
    size_t len;
} method_ref_t;

/* copy_held で呼ぶ。引数 1 の call-error と、引数 2 のメソッドの名前から、文（位置なし）を積む */
static int copy_call_error(lua_State *L) {
    const kibi_device_call_error_t *e = (const kibi_device_call_error_t *)lua_touserdata(L, 1);
    const method_ref_t *m = (const method_ref_t *)lua_touserdata(L, 2);
    lua_settop(L, 0);
    push_call_error(L, e, m->ptr, m->len);
    return 1;
}

/*
 * call-error を Lua のエラーにする（戻らない）。位置は呼んだ Lua の行。
 * message（arena にある）を写して arena を戻してから位置を付ける（luaL_where の確保で走る __gc に、写す前の message を壊させない）
 */
static int raise_call_error(lua_State *L, const kibi_device_call_error_t *e, const char *method, size_t method_len) {
    method_ref_t m = {method, method_len};
    copy_held(L, copy_call_error, e, &m, 1);
    luaL_where(L, 1);
    lua_insert(L, -2);
    lua_concat(L, 2);
    return lua_error(L);
}

/* ---- 開く ---- */

/* 開いて fd を返す。外れている（見つからない）なら -1。ほかの誤りは Lua のエラー */
static int64_t open_device(lua_State *L, const address_t *a) {
    refuse_while_copying(L);
    ensure_copy_stack(L);
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

/* copy_held で呼ぶ。引数 1 の info の種類を積む */
static int copy_kind(lua_State *L) {
    const kibi_device_info_t *info = (const kibi_device_info_t *)lua_touserdata(L, 1);
    lua_settop(L, 0);
    lua_pushlstring(L, info->kind.ptr, info->kind.len);
    return 1;
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
    copy_held(L, copy_kind, &info, NULL, 1);
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

/* kibi:kernel/types の error-code の again（判別子） */
#define ERROR_CODE_AGAIN 5

/* 引数を value に写した、呼び出し1回分（文字列は Lua のスタックの値を指すので、引数が積まれている間だけ有効） */
typedef struct {
    char mbuf[MAX_METHOD_BYTES * 2];
    const char *m;
    size_t mlen;
    kibi_device_value_t args[MAX_VALUES];
    int n;
} call_t;

/* メソッドの名前と、スタックの [first] から上の引数を [c] に写す。引数の番号は [first] を [argbase] として数える */
static void prepare_call(lua_State *L, const char *method, size_t method_len, int first, int argbase, call_t *c) {
    c->m = method_name(method, method_len, c->mbuf, sizeof c->mbuf, &c->mlen);
    if (c->m == NULL || c->mlen > MAX_METHOD_BYTES) luaL_error(L, "method name too long (max %d bytes)", MAX_METHOD_BYTES);
    int top = lua_gettop(L);
    c->n = top >= first ? top - first + 1 : 0;
    if (c->n > MAX_VALUES) luaL_error(L, "too many arguments (max %d)", MAX_VALUES);
    size_t payload = 0;
    for (int i = 0; i < c->n; i++) {
        payload += to_value(L, first + i, argbase + i, &c->args[i]);
    }
    if (payload > MAX_PAYLOAD_BYTES) luaL_error(L, "arguments too large (max %d bytes of strings)", MAX_PAYLOAD_BYTES);
}

/*
 * [a] のデバイスを開いて fd を返す。外れていれば "No such peripheral"。
 * [kind] が NULL でなければ、開いたデバイスの種類がそれと違うとき（fd を閉じてから）"No such peripheral"
 */
static uint32_t open_checked(lua_State *L, const address_t *a, const char *kind, size_t kind_len) {
    int64_t fd = open_device(L, a);
    if (fd < 0) return (uint32_t)luaL_error(L, "No such peripheral");
    if (kind != NULL) {
        reserve_or_close(L, (uint32_t)fd, INFO_RESULT_BYTES);
        kibi_device_info_t info;
        kibi_device_info((uint32_t)fd, &info);
        int same = info.kind.len == kind_len && memcmp(info.kind.ptr, kind, kind_len) == 0;
        kibi_lua_arena_reset();
        if (!same) {
            kibi_device_drop((uint32_t)fd);
            return (uint32_t)luaL_error(L, "No such peripheral");
        }
    }
    return (uint32_t)fd;
}

/* 結果の値を積んで数を返す。copy_held の中で呼ぶ（写し終えたら・エラーでも、arena を戻すのは copy_held） */
static int push_results(lua_State *L, const kibi_device_list_value_t *ok) {
    size_t count = ok->len;
    if (count > INT32_MAX || !lua_checkstack(L, (int)count + LUA_MINSTACK)) return luaL_error(L, "too many results");
    for (size_t i = 0; i < count; i++) push_value(L, &ok->ptr[i]);
    return (int)count;
}

/* copy_held で呼ぶ。引数 1 の値の並びを積んで、その数を返す */
static int copy_values(lua_State *L) {
    const kibi_device_list_value_t *ok = (const kibi_device_list_value_t *)lua_touserdata(L, 1);
    lua_settop(L, 0);
    return push_results(L, ok);
}

/* 開いた [fd] で [c] を invoke し、結果を積んで数を返す（結果が出るまでプロセスごと待つ）。fd は閉じる */
static int invoke_now(lua_State *L, uint32_t fd, const call_t *c) {
    reserve_or_close(L, fd, INVOKE_RESULT_BYTES);
    kibi_device_result_values_t r;
    kibi_device_invoke(fd, c->m, c->mlen, c->args, (size_t)c->n, &r);
    kibi_device_drop(fd);
    if (r.is_err) return raise_call_error(L, &r.val.err, c->m, c->mlen);
    return copy_held(L, copy_values, &r.val.ok, NULL, LUA_MULTRET);
}

/* 表の [i] 番目の pending-call を閉じて、表から外す（後ろを詰めて、積んだ順を保つ） */
static void finish_task(int i) {
    kibi_device_pending_call_drop((uint32_t)tasks[i].fd);
    task_count--;
    if (i < task_count) memmove(&tasks[i], &tasks[i + 1], (size_t)(task_count - i) * sizeof tasks[0]);
}

int kibi_lua_task_count(void) {
    return task_count;
}

int32_t kibi_lua_task_fd(int i) {
    return tasks[i].fd;
}

/* lua_pcall で呼ぶ。引数 1 の take の結果（lightuserdata）と、引数 2 の表の要素から "task_complete" を作って FIFO に積む */
static int push_task_complete(lua_State *L) {
    const kibi_device_option_result_values_t *o = (const kibi_device_option_result_values_t *)lua_touserdata(L, 1);
    const task_t *t = (const task_t *)lua_touserdata(L, 2);
    lua_settop(L, 0);
    lua_pushliteral(L, "task_complete");
    lua_pushinteger(L, (lua_Integer)t->id);
    if (o->val.is_err) {
        lua_pushboolean(L, 0);
        push_call_error(L, &o->val.val.err, t->method, t->method_len);
        kibi_lua_fifo_push(L, 4);
        return 0;
    }
    lua_pushboolean(L, 1);
    int n = push_results(L, &o->val.val.ok);
    kibi_lua_fifo_push(L, 3 + n);
    return 0;
}

/*
 * 表の呼び出しのうち、終わったものを "task_complete", id, true, 値... か "task_complete", id, false, 文 にして FIFO に積む
 * （表の順に。文に位置は付けない。位置は待っていた側が付ける）。写し終えてから drop して表から消す（写す途中でメモリ不足に
 * なっても、take は何度でも同じ結果を返すので、次の回収でやり直せる）。表が空なら何もしない
 */
void kibi_lua_tasks_collect(lua_State *L) {
    /* 写す途中の確保で GC が __gc を呼び、その中で os.queueEvent などから入れ子で回収すると、表と arena が動くので入れ子では回収しない
     * （写す間は kibi_lua_arena_hold で囲む。os.c の collect も、その間はここを呼ばない） */
    if (kibi_lua_arena_held() || task_count == 0) return;
    ensure_copy_stack(L);
    int i = 0;
    while (i < task_count) {
        kibi_lua_arena_reset();
        if (!kibi_lua_reserve(INVOKE_RESULT_BYTES)) {
            lua_pushliteral(L, "not enough memory");
            lua_error(L);
        }
        kibi_device_option_result_values_t o;
        kibi_device_take((uint32_t)tasks[i].fd, &o);
        if (!o.is_some) {
            kibi_lua_arena_reset();
            i++;
            continue;
        }
        copy_held(L, push_task_complete, &o, &tasks[i], 0);
        finish_task(i);
    }
}

/*
 * yield した呼び出しの続き。スタックは [task id, resume の値...]。
 * id の合う "task_complete" なら、成功は値を返し、失敗は文に呼んだ Lua の行を付けたエラーにする。
 * "terminate" なら "Terminated" のエラー（位置なし。呼び出しは取り消さない。CC と同じ）。ほかは捨ててまた yield する
 */
static int call_k(lua_State *L, int status, lua_KContext ctx) {
    (void)status;
    (void)ctx;
    int n = lua_gettop(L);
    if (n >= 2 && lua_type(L, 2) == LUA_TSTRING && strcmp(lua_tostring(L, 2), "terminate") == 0) {
        lua_pushliteral(L, "Terminated");
        return lua_error(L);
    }
    /* id は float で resume されても合う（sleep と同じく Lua の数値の等しさで見る） */
    if (n >= 4 && lua_type(L, 2) == LUA_TSTRING && strcmp(lua_tostring(L, 2), "task_complete") == 0 &&
        lua_type(L, 3) == LUA_TNUMBER && lua_compare(L, 1, 3, LUA_OPEQ)) {
        if (lua_toboolean(L, 4)) return n - 4;
        lua_settop(L, 5);
        if (lua_type(L, 5) == LUA_TSTRING) {
            luaL_where(L, 1);
            lua_insert(L, 5);
            lua_concat(L, 2);
        }
        return lua_error(L);
    }
    lua_settop(L, 1);
    lua_pushliteral(L, "task_complete");
    return lua_yieldk(L, 1, 0, call_k);
}

/* lua_pcall で呼ぶ。引数 1 の take の結果（lightuserdata）と、引数 2 の call_t から、成功なら値を、失敗なら文（位置なし）を積み、積んだ数を返す */
static int copy_taken(lua_State *L) {
    const kibi_device_option_result_values_t *o = (const kibi_device_option_result_values_t *)lua_touserdata(L, 1);
    const call_t *c = (const call_t *)lua_touserdata(L, 2);
    lua_settop(L, 0);
    if (o->val.is_err) {
        push_call_error(L, &o->val.val.err, c->m, c->mlen);
        return 1;
    }
    return push_results(L, &o->val.val.ok);
}

/*
 * その場で終わった呼び出しの結果 [o]（take の結果。pending-call は閉じ済み）を、値として返すか、エラーにする。
 * 写す途中の確保で GC が走ると、__gc の中から os.queueEvent などで回収（カーネルのイベントの取り出しと kibi_lua_tasks_collect）が
 * 入れ子で走り、arena（今写している結果の場所）と表を動かすので、写す間は kibi_lua_arena_hold で入れ子の回収を止める。
 * 写す途中でメモリ不足になっても、pcall で受けてから release する
 */
static int return_taken(lua_State *L, kibi_device_option_result_values_t *o, const call_t *c) {
    int count = copy_held(L, copy_taken, o, c, LUA_MULTRET);
    if (o->val.is_err) {
        luaL_where(L, 1);
        lua_insert(L, -2);
        lua_concat(L, 2);
        return lua_error(L);
    }
    return count;
}

/*
 * 開いた [fd] で [c] を start し、すぐ take する。fd（device）は閉じる。
 * その場で終わったもの（直接のメソッド、その場で失敗するもの）は結果を返し、終わっていなければ待っている呼び出しの表に入れて
 * "task_complete" を yield する。カーネルの上限（again）に当たったら、invoke でその場で待つ。
 * start の前に take の結果の場所（INVOKE_RESULT_BYTES）まで確かめる（invoke_now と同じ。足りなければメソッドは動かさずに
 * "not enough memory" のエラー）。start の後は arena を戻さず、取った場所を take に使う（Lua の確保は間にない）。
 * yield する "task_complete" の文字列は、呼び出し側（call_device）が device を開く前にスタックの一番上へ積んである
 * （lua_pushliteral は内部化の確保や GC の 1 歩で longjmp しうるので、start の後・表に入れた後には積まない）
 */
static int start_and_yield(lua_State *L, uint32_t fd, const call_t *c) {
    const int complete = lua_gettop(L);
    reserve_or_close(L, fd, INVOKE_RESULT_BYTES);
    kibi_device_result_handle_t r;
    kibi_device_start(fd, c->m, c->mlen, c->args, (size_t)c->n, &r);
    if (r.is_err) {
        if (r.val.err.tag == KIBI_DEVICE_CALL_ERROR_KERNEL && r.val.err.val.kernel == ERROR_CODE_AGAIN) {
            kibi_lua_arena_reset();
            return invoke_now(L, fd, c);
        }
        kibi_device_drop(fd);
        return raise_call_error(L, &r.val.err, c->m, c->mlen);
    }
    kibi_device_drop(fd);
    int32_t call = (int32_t)r.val.ok;

    /* その場で終わったか（直接のメソッドと、その場で失敗するものは start の中で終わっている）。take は何度でも同じ結果を返すが、
     * ここで表に入れずに閉じて写す（写す途中のメモリ不足で、持ち主のいない "task_complete" を後に残さない。invoke_now と同じ） */
    kibi_device_option_result_values_t o;
    kibi_device_take((uint32_t)call, &o);
    if (o.is_some) {
        kibi_device_pending_call_drop((uint32_t)call);
        return return_taken(L, &o, c);
    }
    kibi_lua_arena_reset();
    /* 表へ入れてから yield する。ここから yield までは Lua の確保を伴う API（pushstring・pushliteral など）を呼ばない
     * （lua_pushvalue・lua_pushinteger・lua_copy・lua_settop は確保も GC もしない）。"task_complete" は積んである文字列を使う */
    task_t *t = &tasks[task_count++];
    t->fd = call;
    t->id = ++next_task_id;
    t->method_len = (uint8_t)c->mlen;
    memcpy(t->method, c->m, c->mlen);
    int64_t id = t->id;
    lua_pushvalue(L, complete);
    lua_pushinteger(L, (lua_Integer)id);
    lua_copy(L, -1, 1);
    lua_copy(L, -2, 2);
    lua_settop(L, 2);
    return lua_yieldk(L, 1, 0, call_k);
}

/*
 * [a] のデバイスの [method] を、スタックの [first] から上の引数で呼び、結果を積んで数を返す。
 * [kind] が NULL でなければ、開いたデバイスの種類がそれと違うとき "No such peripheral"。
 * 引数の番号は [first] を [argbase] として数える。
 * [may_yield] なら、コルーチンの中（yield できるところ）では start して "task_complete" を yield する（#744）。
 * メインスレッドと yield できない文脈（gsub のコールバックなど）、表がいっぱいのときは invoke でその場で待つ
 */
static int call_device(lua_State *L, const address_t *a, const char *kind, size_t kind_len, const char *method,
                       size_t method_len, int first, int argbase, int may_yield) {
    call_t c;
    prepare_call(L, method, method_len, first, argbase, &c);
    int yield = may_yield && !kibi_lua_on_main_thread(L) && lua_isyieldable(L);
    /* 表がいっぱいなら、終わったものを回収してから（device を開く前に。回収のエラーで fd を漏らさない） */
    if (yield && task_count >= MAX_TASKS) kibi_lua_tasks_collect(L);
    if (task_count >= MAX_TASKS) yield = 0;
    /* yield する文字列は、device を開く前に積む（longjmp しても fd を漏らさず、start の後に Lua の確保をしない） */
    if (yield) lua_pushliteral(L, "task_complete");
    uint32_t fd = open_checked(L, a, kind, kind_len);
    if (!yield) return invoke_now(L, fd, &c);
    return start_and_yield(L, fd, &c);
}

/* peripheral.call(name, method, ...) */
static int l_call(lua_State *L) {
    address_t a;
    check_address(L, 1, &a);
    size_t mlen;
    const char *m = luaL_checklstring(L, 2, &mlen);
    return call_device(L, &a, NULL, 0, m, mlen, 3, 3, 1);
}

/*
 * wrap が作る関数。上位値は address・種類・メソッドの名前・メインスレッドのメソッドか。
 * 直接のメソッドは start せずに invoke する（fd も pending-call も作らない。印が古くても、どちらの道でも正しく動く）
 */
static int l_wrapped(lua_State *L) {
    size_t alen;
    size_t klen;
    size_t mlen;
    const char *addr = lua_tolstring(L, lua_upvalueindex(1), &alen);
    const char *kind = lua_tolstring(L, lua_upvalueindex(2), &klen);
    const char *m = lua_tolstring(L, lua_upvalueindex(3), &mlen);
    int main = lua_toboolean(L, lua_upvalueindex(4));
    address_t a;
    address_of(&a, addr, alen);
    return call_device(L, &a, kind, klen, m, mlen, 1, 1, main);
}

/* ---- 列挙 ---- */

/* copy_held で呼ぶ。引数 1 の一覧を、address の並びと種類の並びの2つのテーブルにして積む */
static int copy_device_list(lua_State *L) {
    const kibi_device_list_info_t *l = (const kibi_device_list_info_t *)lua_touserdata(L, 1);
    lua_settop(L, 0);
    lua_createtable(L, (int)l->len, 0);
    lua_createtable(L, (int)l->len, 0);
    for (size_t i = 0; i < l->len; i++) {
        lua_pushlstring(L, l->ptr[i].address.ptr, l->ptr[i].address.len);
        lua_rawseti(L, -3, (lua_Integer)i + 1);
        lua_pushlstring(L, l->ptr[i].kind.ptr, l->ptr[i].kind.len);
        lua_rawseti(L, -2, (lua_Integer)i + 1);
    }
    return 2;
}

/* デバイスの一覧を、address の並びと種類の並びの2つのテーブルにして積む */
static void push_device_list(lua_State *L) {
    refuse_while_copying(L);
    ensure_copy_stack(L);
    kibi_lua_arena_reset();
    kibi_lua_check_memory(L, LIST_RESULT_BYTES);
    kibi_device_list_info_t l;
    kibi_device_list_devices(&l);
    copy_held(L, copy_device_list, &l, NULL, 2);
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

/* copy_methods_kind・copy_methods_mains_kind の中身。引数 1 のメソッドの一覧と引数 2 の info から積む */
static int copy_methods(lua_State *L, int with_main) {
    const kibi_device_list_method_info_t *l = (const kibi_device_list_method_info_t *)lua_touserdata(L, 1);
    const kibi_device_info_t *info = (const kibi_device_info_t *)lua_touserdata(L, 2);
    lua_settop(L, 0);
    lua_createtable(L, (int)l->len, 0);
    for (size_t i = 0; i < l->len; i++) {
        lua_pushlstring(L, l->ptr[i].name.ptr, l->ptr[i].name.len);
        lua_rawseti(L, -2, (lua_Integer)i + 1);
    }
    if (with_main) {
        lua_createtable(L, (int)l->len, 0);
        for (size_t i = 0; i < l->len; i++) {
            lua_pushboolean(L, l->ptr[i].main_thread != 0);
            lua_rawseti(L, -2, (lua_Integer)i + 1);
        }
    }
    lua_pushlstring(L, info->kind.ptr, info->kind.len);
    return with_main ? 3 : 2;
}

/* copy_held で呼ぶ。methods, kind を積む */
static int copy_methods_kind(lua_State *L) {
    return copy_methods(L, 0);
}

/* copy_held で呼ぶ。methods, メインスレッドのメソッドかの並び, kind を積む */
static int copy_methods_mains_kind(lua_State *L) {
    return copy_methods(L, 1);
}

/*
 * 開いた fd のメソッドの並びと種類を積む（methods, kind の順。[with_main] なら methods, メインスレッドのメソッドかの並び, kind）。
 * fd は閉じる。
 * メモリが足りないときも fd を閉じてから Lua のエラーにする。import の結果を読んだらすぐ fd を閉じてから Lua の値に写す
 * （写す途中の Lua の確保でメモリ不足の longjmp が起きても、fd が漏れないように）
 */
static void push_methods_and_kind(lua_State *L, uint32_t fd, int with_main) {
    reserve_or_close(L, fd, METHODS_RESULT_BYTES + INFO_RESULT_BYTES);
    kibi_device_list_method_info_t l;
    kibi_device_methods(fd, &l);
    kibi_device_info_t info;
    kibi_device_info(fd, &info);
    kibi_device_drop(fd);
    copy_held(L, with_main ? copy_methods_mains_kind : copy_methods_kind, &l, &info, with_main ? 3 : 2);
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
    push_methods_and_kind(L, (uint32_t)fd, 0); /* fd は閉じる */
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
    push_methods_and_kind(L, (uint32_t)fd, 1); /* methods, mains, kind。fd は閉じる */
    int methods = lua_gettop(L) - 2;
    int mains = lua_gettop(L) - 1;
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
        lua_rawgeti(L, mains, i);
        lua_pushcclosure(L, l_wrapped, 4);
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
