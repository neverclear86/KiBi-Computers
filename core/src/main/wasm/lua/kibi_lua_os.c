/*
 * Lua の os ライブラリに足す KiBi の関数（#94。ADR 0094）。CC:Tweaked の os を手本にする。
 *
 * - イベント: os.pullEvent・pullEventRaw・queueEvent。kibi:kernel/events（ADR 0073）のキューを、初めて pullEvent・pullEventRaw を
 *   呼んだときに開く（起動時には開かない。読まないプロセスがイベントを溜め込まないように。開く前のイベントは受け取れない）
 * - タイマー: os.startTimer・cancelTimer・sleep。カーネルのタイマーは作らず、Lua の中に期限（単調時計の絶対時刻）を持ち、
 *   いちばん早い期限を poll_oneoff の時計（MONOTONIC、ABSTIME）で待つ。巻き直しで期限が延びず、保存・復元をまたいでも続く（ADR 0064・0329）
 * - 待つのは poll_oneoff だけ（busy loop にしない）。待機点型の Lua は poll_oneoff の中で巻き戻り、スレッドを持たずに待つ
 * - 時刻: os.epoch・os.day。標準の os.time・os.clock・os.date は上書きしない（意味が CC と違うため。ADR 0094）
 * - 電源: os.shutdown・os.reboot（kibi:computer/power）
 *
 * os.pullEventRaw は、今はその場でカーネルを待つ（コルーチンの中でも yield しない）。#95 でコルーチンのスケジューラーに置き換える前提
 */
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <wasi/api.h>

#include "lauxlib.h"
#include "lua.h"

#include "kibi_clock.h"
#include "kibi_events.h"
#include "kibi_lua.h"
#include "kibi_power.h"

/* タイマーの最短の期間（1 tick。仮の値。確定は #251）。0 や短すぎる期限で poll_oneoff を連打しないため */
#define MIN_DELAY_NS 50000000LL
/* 期限の最大（足して溢れないように） */
#define MAX_DELAY_NS (INT64_MAX / 4)
/* 1回の take で取り出す件数。結果の見積もりは 1 件あたり 並び 24 ＋ 値 32 × 16 ＋ 中身 4 KiB ＋ 種類 64 ＋ source 128 */
#define TAKE_MAX 8
#define TAKE_RESULT_BYTES (TAKE_MAX * (24 + 32 * 16 + 4096 + 64 + 128) + 64)
/* ゲーム内の時間: 1 tick = 3.6 秒（1 日 = 24000 tick） */
#define TICKS_PER_DAY 24000
#define INGAME_MS_PER_TICK 3600

/* レジストリの key（静的な変数のアドレス） */
static char FIFO_KEY;
static char TIMERS_KEY;

/* Lua の中の FIFO（レジストリのテーブル。[fifo_head, fifo_tail) に {n = 数, 名前, 引数...} が入る） */
static lua_Integer fifo_head = 1;
static lua_Integer fifo_tail = 1;
/* タイマー（レジストリのテーブル。id → 期限） */
static lua_Integer next_timer_id = 0;
static lua_Integer timer_count = 0;
/* キューの fd（開いていなければ -1） */
static int32_t queue_fd = -1;

static int64_t monotonic_now(void) {
    __wasi_timestamp_t t = 0;
    (void)__wasi_clock_time_get(__WASI_CLOCKID_MONOTONIC, 1, &t);
    return t > (uint64_t)INT64_MAX ? INT64_MAX : (int64_t)t;
}

static int64_t realtime_now(void) {
    __wasi_timestamp_t t = 0;
    (void)__wasi_clock_time_get(__WASI_CLOCKID_REALTIME, 1, &t);
    return t > (uint64_t)INT64_MAX ? INT64_MAX : (int64_t)t;
}

/* ---- FIFO ---- */

/* レジストリのテーブルを積む。初めて使うときに作る（起動時の RAM を増やさないため。128 KiB の RAM でも REPL が動くように） */
static void push_registry(lua_State *L, const void *key) {
    if (lua_rawgetp(L, LUA_REGISTRYINDEX, key) != LUA_TNIL) return;
    lua_pop(L, 1);
    lua_newtable(L);
    lua_pushvalue(L, -1);
    lua_rawsetp(L, LUA_REGISTRYINDEX, key);
}

/* スタックの上の [n] 個の値（名前, 引数...）を FIFO の後ろに積む（値は取り除く） */
static void fifo_push(lua_State *L, int n) {
    int base = lua_gettop(L) - n + 1;
    lua_createtable(L, n, 1);
    for (int i = 1; i <= n; i++) {
        lua_pushvalue(L, base + i - 1);
        lua_rawseti(L, -2, i);
    }
    lua_pushinteger(L, n);
    lua_setfield(L, -2, "n");
    push_registry(L, &FIFO_KEY);
    lua_insert(L, -2);
    lua_rawseti(L, -2, fifo_tail++);
    lua_settop(L, base - 1);
}

/* FIFO の先頭を取り出して、値（名前, 引数...）を積む。数を返す（空なら 0） */
static int fifo_pop(lua_State *L) {
    if (fifo_head == fifo_tail) return 0;
    push_registry(L, &FIFO_KEY);
    lua_rawgeti(L, -1, fifo_head);
    lua_pushnil(L);
    lua_rawseti(L, -3, fifo_head++);
    lua_remove(L, -2);
    if (fifo_head == fifo_tail) fifo_head = fifo_tail = 1;
    lua_getfield(L, -1, "n");
    int n = (int)lua_tointeger(L, -1);
    lua_pop(L, 1);
    luaL_checkstack(L, n, "too many event values");
    for (int i = 1; i <= n; i++) lua_rawgeti(L, -i, i);
    lua_remove(L, -n - 1);
    return n;
}

/* ---- タイマー ---- */

/* いちばん早い期限（id の小さい順に同点を崩す）。なければ 0 を返す */
static int earliest_timer(lua_State *L, int64_t *deadline, lua_Integer *id) {
    if (timer_count == 0) return 0;
    int found = 0;
    push_registry(L, &TIMERS_KEY);
    lua_pushnil(L);
    while (lua_next(L, -2)) {
        lua_Integer d = lua_tointeger(L, -1);
        lua_Integer k = lua_tointeger(L, -2);
        if (!found || d < *deadline || (d == *deadline && k < *id)) {
            *deadline = d;
            *id = k;
            found = 1;
        }
        lua_pop(L, 1);
    }
    lua_pop(L, 1);
    return found;
}

static void remove_timer(lua_State *L, lua_Integer id) {
    push_registry(L, &TIMERS_KEY);
    if (lua_rawgeti(L, -1, id) != LUA_TNIL) {
        timer_count--;
        lua_pushnil(L);
        lua_rawseti(L, -3, id);
    }
    lua_pop(L, 2);
}

/* 期限の来たタイマーを、期限の早い順に "timer" のイベントにして FIFO に積む */
static void fire_timers(lua_State *L) {
    int64_t now = monotonic_now();
    int64_t deadline;
    lua_Integer id;
    while (earliest_timer(L, &deadline, &id) && deadline <= now) {
        remove_timer(L, id);
        lua_pushliteral(L, "timer");
        lua_pushinteger(L, id);
        fifo_push(L, 2);
    }
}

/* 秒 [idx] を期間（ns）にする（最短 1 tick） */
static int64_t delay_of(lua_State *L, int idx) {
    lua_Number s = luaL_optnumber(L, idx, 0);
    if (!(s == s)) luaL_argerror(L, idx, "not a number");
    lua_Number ns = s * 1e9;
    if (ns < (lua_Number)MIN_DELAY_NS) return MIN_DELAY_NS;
    if (ns > (lua_Number)MAX_DELAY_NS) return MAX_DELAY_NS;
    return (int64_t)ns;
}

/* ---- カーネルのイベント ---- */

static int32_t open_queue(lua_State *L) {
    if (queue_fd >= 0) return queue_fd;
    kibi_result_event_queue_t r;
    kibi_event_queue_open_raw(NULL, 0, 0, 0, &r);
    if (r.is_err) return luaL_error(L, "cannot open the event queue: %s", kibi_lua_error_name(r.val.err)), -1;
    queue_fd = r.val.handle;
    return queue_fd;
}

static void push_event_value(lua_State *L, const kibi_event_value_t *v) {
    switch (v->tag) {
    case KIBI_EVENT_VALUE_INTEGER:
        lua_pushinteger(L, (lua_Integer)v->val.integer);
        break;
    case KIBI_EVENT_VALUE_FLOAT:
        lua_pushnumber(L, (lua_Number)v->val.floating);
        break;
    case KIBI_EVENT_VALUE_BOOLEAN:
        lua_pushboolean(L, v->val.boolean != 0);
        break;
    case KIBI_EVENT_VALUE_TEXT:
        lua_pushlstring(L, (const char *)v->val.text.ptr, v->val.text.len);
        break;
    case KIBI_EVENT_VALUE_BYTES:
        lua_pushlstring(L, (const char *)v->val.bytes.ptr, v->val.bytes.len);
        break;
    default:
        lua_pushnil(L);
        break;
    }
}

static int str_is(const kibi_string_t *s, const char *lit) {
    size_t n = strlen(lit);
    return s->len == n && memcmp(s->ptr, lit, n) == 0;
}

/* カーネルのイベントを Lua のイベント（名前, 引数...）にして FIFO に積む。名前の写し方は ADR 0094 の表 */
static void push_kernel_event(lua_State *L, const kibi_event_t *e) {
    int no_source = e->source.len == 0;
    const kibi_event_value_t *vals = e->values.ptr;
    uint32_t nvals = e->values.len;
    luaL_checkstack(L, (int)nvals + 2, "too many event values");
    if (no_source && str_is(&e->kind, "device-attached") && nvals >= 1) {
        lua_pushliteral(L, "peripheral");
        push_event_value(L, &vals[0]);
        fifo_push(L, 2);
        return;
    }
    if (no_source && str_is(&e->kind, "device-detached") && nvals >= 1) {
        lua_pushliteral(L, "peripheral_detach");
        push_event_value(L, &vals[0]);
        fifo_push(L, 2);
        return;
    }
    if (str_is(&e->source, "console") && str_is(&e->kind, "tty-resize")) {
        lua_pushliteral(L, "term_resize");
        fifo_push(L, 1);
        return;
    }
    int n = 1;
    lua_pushlstring(L, (const char *)e->kind.ptr, e->kind.len);
    if (!no_source) {
        lua_pushlstring(L, (const char *)e->source.ptr, e->source.len);
        n++;
    }
    for (uint32_t i = 0; i < nvals; i++) push_event_value(L, &vals[i]);
    fifo_push(L, n + (int)nvals);
}

/* 開いたキューから、待たずに取れるイベントをすべて FIFO に移す。移した数を返す */
static int take_kernel_events(lua_State *L) {
    if (queue_fd < 0) return 0;
    int moved = 0;
    for (;;) {
        kibi_lua_arena_reset();
        kibi_lua_check_memory(L, TAKE_RESULT_BYTES);
        kibi_result_event_batch_t r;
        kibi_event_queue_take_raw(queue_fd, TAKE_MAX, &r);
        if (r.is_err) {
            kibi_lua_arena_reset();
            return luaL_error(L, "cannot take events: %s", kibi_lua_error_name(r.val.err));
        }
        uint32_t n = r.val.ok.events.len;
        uint64_t dropped = r.val.ok.dropped;
        for (uint32_t i = 0; i < n; i++) push_kernel_event(L, &r.val.ok.events.ptr[i]);
        kibi_lua_arena_reset();
        if (dropped > 0) {
            /* あふれて捨てたイベントの数（黙って捨てない） */
            lua_pushliteral(L, "events_dropped");
            lua_pushinteger(L, dropped > (uint64_t)INT64_MAX ? INT64_MAX : (lua_Integer)dropped);
            fifo_push(L, 2);
        }
        moved += (int)n + (dropped > 0);
        if (n < TAKE_MAX) return moved;
    }
}

/* 期限の来たタイマーと、取れるカーネルのイベントを FIFO に移す */
static void collect(lua_State *L) {
    fire_timers(L);
    take_kernel_events(L);
}

/*
 * キュー [fd]（-1 なら見ない）が読めるか、単調時計が [deadline]（0 以下なら見ない）に達するまで待つ。
 * どちらもなければ何もしない。起きた理由は見ない（呼んだ側が調べ直す）
 */
static void wait_for(lua_State *L, int32_t fd, int64_t deadline) {
    __wasi_subscription_t subs[2];
    __wasi_event_t events[2];
    __wasi_size_t n = 0;
    memset(subs, 0, sizeof subs);
    if (fd >= 0) {
        subs[n].userdata = 1;
        subs[n].u.tag = __WASI_EVENTTYPE_FD_READ;
        subs[n].u.u.fd_read.file_descriptor = (__wasi_fd_t)fd;
        n++;
    }
    if (deadline > 0) {
        subs[n].userdata = 2;
        subs[n].u.tag = __WASI_EVENTTYPE_CLOCK;
        subs[n].u.u.clock.id = __WASI_CLOCKID_MONOTONIC;
        subs[n].u.u.clock.timeout = (__wasi_timestamp_t)deadline;
        subs[n].u.u.clock.flags = __WASI_SUBCLOCKFLAGS_SUBSCRIPTION_CLOCK_ABSTIME;
        n++;
    }
    if (n == 0) return;
    __wasi_size_t got = 0;
    __wasi_errno_t err = __wasi_poll_oneoff(subs, events, n, &got);
    if (err != 0) luaL_error(L, "cannot wait for events: %s", kibi_lua_error_name((uint8_t)(err - 1)));
}

/* ---- os の関数 ---- */

/* os.pullEventRaw([filter]) の中身。filter に合わないイベントは捨てる（CC と同じ） */
static int pull(lua_State *L) {
    const char *filter = luaL_optstring(L, 1, NULL);
    lua_settop(L, 1);
    open_queue(L);
    for (;;) {
        int n;
        while ((n = fifo_pop(L)) > 0) {
            const char *name = lua_tostring(L, -n);
            if (filter == NULL || (name != NULL && strcmp(name, filter) == 0)) return n;
            lua_settop(L, 1);
        }
        collect(L);
        if (fifo_head != fifo_tail) continue;
        int64_t deadline = 0;
        lua_Integer id;
        if (!earliest_timer(L, &deadline, &id)) deadline = 0;
        else if (deadline <= 0) deadline = 1;
        wait_for(L, queue_fd, deadline);
    }
}

static int l_pullEventRaw(lua_State *L) {
    return pull(L);
}

/* os.pullEvent([filter]): pullEventRaw と同じだが、"terminate" を受けたら "Terminated" のエラーにする（CC と同じ） */
static int l_pullEvent(lua_State *L) {
    int n = pull(L);
    const char *name = lua_tostring(L, -n);
    if (name != NULL && strcmp(name, "terminate") == 0) {
        lua_pushliteral(L, "Terminated");
        return lua_error(L);
    }
    return n;
}

/* os.queueEvent(name, ...): この Lua の中の FIFO の後ろに積む（ほかのプロセスには届かない） */
static int l_queueEvent(lua_State *L) {
    luaL_checkstring(L, 1);
    int n = lua_gettop(L);
    /* その時点で届いているイベントの後ろに積む（CC と同じ順） */
    collect(L);
    lua_settop(L, n);
    fifo_push(L, n);
    return 0;
}

/* os.startTimer(seconds) -> id */
static int l_startTimer(lua_State *L) {
    int64_t delay = delay_of(L, 1);
    int64_t deadline = monotonic_now() + delay;
    lua_Integer id = ++next_timer_id;
    push_registry(L, &TIMERS_KEY);
    lua_pushinteger(L, (lua_Integer)deadline);
    lua_rawseti(L, -2, id);
    lua_pop(L, 1);
    timer_count++;
    lua_pushinteger(L, id);
    return 1;
}

/* os.cancelTimer(id) */
static int l_cancelTimer(lua_State *L) {
    remove_timer(L, luaL_checkinteger(L, 1));
    return 0;
}

/* os.sleep(seconds): その間、時計だけを待つ（イベントは取り出さないので捨てない。CC と違う） */
static int l_sleep(lua_State *L) {
    int64_t deadline = monotonic_now() + delay_of(L, 1);
    while (monotonic_now() < deadline) wait_for(L, -1, deadline);
    return 0;
}

static const char *const EPOCH_KINDS[] = {"ingame", "utc", "local", NULL};

/* os.epoch([kind]): ミリ秒。"ingame"（既定）はワールドの tick から（1 tick = 3.6 秒）、"utc"・"local" は実時間（タイムゾーンはないので同じ） */
static int l_epoch(lua_State *L) {
    int kind = luaL_checkoption(L, 1, "ingame", EPOCH_KINDS);
    if (kind == 0) {
        uint64_t t = kibi_clock_world_ticks();
        lua_pushinteger(L, (lua_Integer)(t * INGAME_MS_PER_TICK));
    } else {
        lua_pushinteger(L, (lua_Integer)(realtime_now() / 1000000));
    }
    return 1;
}

/* os.day([kind]): 日数。"ingame"（既定）はワールドの tick ÷ 24000、"utc"・"local" は 1970-01-01 からの日数 */
static int l_day(lua_State *L) {
    int kind = luaL_checkoption(L, 1, "ingame", EPOCH_KINDS);
    if (kind == 0) {
        lua_pushinteger(L, (lua_Integer)(kibi_clock_world_ticks() / TICKS_PER_DAY));
    } else {
        lua_pushinteger(L, (lua_Integer)(realtime_now() / 1000000000LL / 86400));
    }
    return 1;
}

/* os.shutdown(): マシンを止める。戻らない。止める前に標準出力などを書き出す */
static int l_shutdown(lua_State *L) {
    (void)L;
    fflush(NULL);
    kibi_power_shutdown();
}

/* os.reboot(): マシンを再起動する。受け付けたら戻らない。断られたら false と理由 */
static int l_reboot(lua_State *L) {
    fflush(NULL);
    kibi_result_unit_t r;
    kibi_power_reboot(&r);
    lua_pushboolean(L, 0);
    lua_pushstring(L, kibi_lua_error_name(r.err));
    return 2;
}

static const luaL_Reg FUNCS[] = {
    {"pullEvent", l_pullEvent},
    {"pullEventRaw", l_pullEventRaw},
    {"queueEvent", l_queueEvent},
    {"startTimer", l_startTimer},
    {"cancelTimer", l_cancelTimer},
    {"sleep", l_sleep},
    {"epoch", l_epoch},
    {"day", l_day},
    {"shutdown", l_shutdown},
    {"reboot", l_reboot},
    {NULL, NULL},
};

void kibi_lua_os_extend(lua_State *L) {
    luaL_setfuncs(L, FUNCS, 0);
}
