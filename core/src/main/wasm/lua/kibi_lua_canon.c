/*
 * Lua 向けの KiBi API（#94。ADR 0094）の cabi_realloc と、呼び出し1回分の確保の記録。
 *
 * 可変長の結果を返す kibi:* の import（events の take、device の list-devices・open・info・methods・invoke）は、
 * ホストがこの cabi_realloc で場所を取って書く（ADR 0073・0077）。
 *
 * - 取った場所は、ヘッダーで前の確保につないで記録し、kibi_lua_arena_reset でまとめて戻す。
 *   RAM は一度書いた区画を解放しても減らない（#370・#409）ので、戻し忘れると上限まで食いつぶす。戻した穴は次の確保で使い回す
 * - 確保は Lua と同じ包み（kibi_lua_realloc）を通す。書く前に kibi:computer/memory の commit で数えさせるため
 * - 確保できなければ線形メモリの外（OUT_OF_MEMORY）を返し、ホストにトラップさせる。0 を返すと、ホストがアドレス 0 から書いてメモリを壊す。
 *   トラップを避けるため、呼ぶ側は import の前に kibi_lua_check_memory で見積もりを確かめる
 * - ここでは Lua の API・エラー（longjmp）・止まりうる import（fd_write・poll_oneoff・device.invoke）を呼ばない（ADR 0073）
 */
#include <stdint.h>
#include <string.h>

#include "lauxlib.h"
#include "kibi_lua.h"
#include "kibi_memory.h"

/* 足りないときに返す場所（線形メモリの外。align 8 に揃えてあるので、ホストは範囲外としてトラップする。canon.zig と同じ値） */
#define KIBI_LUA_OUT_OF_MEMORY ((void *)(uintptr_t)0xFFFFFFF8u)

/* 確保ごとの頭（前の確保へのつなぎ）。結果の揃えは 8 まで（Canonical ABI の最大）。malloc は 8 に揃えて返すので、頭も 8 の倍数にする */
#define KIBI_LUA_ARENA_HEAD 8
#define KIBI_LUA_MAX_ALIGN 8

static void *arena_last = NULL;

/*
 * 先に取っておいた場所（kibi_lua_reserve）。あれば cabi_realloc はここから前へ切り出し、入らなければ1つずつ取る。
 * RAM の残りが少ないとき（一度上限に達した後など）にだけ取る。解放した穴は RAM の残りに数えないので、
 * 残りだけを見ると、穴があっても以後ずっと断ることになるため
 */
static unsigned char *reserve_base = NULL;
static size_t reserve_size = 0;
static size_t reserve_used = 0;

__attribute__((export_name("cabi_realloc")))
void *cabi_realloc(void *ptr, size_t old_size, size_t align, size_t new_size) {
    (void)ptr;
    (void)old_size;
    if (align == 0 || (align & (align - 1)) != 0 || align > KIBI_LUA_MAX_ALIGN) return KIBI_LUA_OUT_OF_MEMORY;
    /* 大きさ 0 のリストでも呼ばれる（Canonical ABI）。読み書きされないので、揃った 0 でない場所を返す */
    if (new_size == 0) return (void *)align;
    if (reserve_base != NULL) {
        size_t start = (reserve_used + align - 1) & ~(align - 1);
        if (start <= reserve_size && new_size <= reserve_size - start) {
            reserve_used = start + new_size;
            return reserve_base + start;
        }
    }
    if (new_size > SIZE_MAX - KIBI_LUA_ARENA_HEAD) return KIBI_LUA_OUT_OF_MEMORY;
    unsigned char *block = kibi_lua_realloc(NULL, new_size + KIBI_LUA_ARENA_HEAD);
    if (block == NULL || ((uintptr_t)block & (KIBI_LUA_MAX_ALIGN - 1)) != 0) {
        if (block != NULL) kibi_lua_free(block);
        return KIBI_LUA_OUT_OF_MEMORY;
    }
    memcpy(block, &arena_last, sizeof arena_last);
    arena_last = block;
    return block + KIBI_LUA_ARENA_HEAD;
}

void kibi_lua_arena_reset(void) {
    while (arena_last != NULL) {
        void *prev;
        memcpy(&prev, arena_last, sizeof prev);
        kibi_lua_free(arena_last);
        arena_last = prev;
    }
    if (reserve_base != NULL) {
        kibi_lua_free(reserve_base);
        reserve_base = NULL;
        reserve_size = reserve_used = 0;
    }
}

/* kibi_lua_realloc が残す分（KIBI_LUA_KEEP）と、確保ごとの頭・dlmalloc のチャンクの頭の分 */
#define KIBI_LUA_MEMORY_MARGIN (24 * 1024)

int kibi_lua_reserve(size_t need) {
    if (kibi_memory_remaining() >= (uint64_t)need + KIBI_LUA_MEMORY_MARGIN) return 1;
    /* 残りが少ない: 結果の最大の大きさを先に取る（解放した穴に入れば、新しく数えずに済む） */
    kibi_lua_arena_reset();
    unsigned char *block = kibi_lua_realloc(NULL, need);
    if (block == NULL) return 0;
    reserve_base = block;
    reserve_size = need;
    reserve_used = 0;
    return 1;
}

void kibi_lua_check_memory(lua_State *L, size_t need) {
    if (!kibi_lua_reserve(need)) {
        lua_pushliteral(L, "not enough memory");
        lua_error(L);
    }
}

/* kibi:kernel/types の error-code（WIT の順。core/src/main/wit/kibi-kernel/types.wit） */
static const char *const ERROR_NAMES[] = {
    "too-big", "access", "addr-in-use", "addr-not-avail", "af-not-supported", "again", "already", "bad-descriptor",
    "bad-message", "busy", "canceled", "child", "conn-aborted", "conn-refused", "conn-reset", "deadlock",
    "dest-addr-required", "domain", "quota", "exist", "fault", "file-too-large", "host-unreachable",
    "identifier-removed", "illegal-byte-sequence", "in-progress", "interrupted", "invalid", "io", "is-connected",
    "is-directory", "loop", "mfile", "too-many-links", "message-size", "multihop", "name-too-long", "net-down",
    "net-reset", "net-unreachable", "nfile", "no-buffers", "no-device", "no-entry", "no-exec", "no-lock", "no-link",
    "no-memory", "no-message", "no-protocol-option", "no-space", "no-sys", "not-connected", "not-directory",
    "not-empty", "not-recoverable", "not-socket", "not-supported", "no-tty", "no-such-device", "overflow",
    "owner-dead", "not-permitted", "pipe", "protocol", "protocol-not-supported", "protocol-type", "range",
    "read-only", "invalid-seek", "no-such-process", "stale", "timed-out", "text-file-busy", "cross-device",
    "not-capable",
};

const char *kibi_lua_error_name(uint8_t code) {
    return code < sizeof ERROR_NAMES / sizeof ERROR_NAMES[0] ? ERROR_NAMES[code] : "unknown";
}

int kibi_lua_utf8_valid(const char *str, size_t len) {
    const unsigned char *s = (const unsigned char *)str;
    size_t i = 0;
    while (i < len) {
        unsigned c = s[i];
        if (c < 0x80) {
            i++;
            continue;
        }
        size_t n;
        uint32_t cp;
        uint32_t min;
        if ((c & 0xE0) == 0xC0) {
            n = 1, cp = c & 0x1F, min = 0x80;
        } else if ((c & 0xF0) == 0xE0) {
            n = 2, cp = c & 0x0F, min = 0x800;
        } else if ((c & 0xF8) == 0xF0) {
            n = 3, cp = c & 0x07, min = 0x10000;
        } else {
            return 0;
        }
        if (len - i <= n) return 0;
        for (size_t k = 1; k <= n; k++) {
            unsigned d = s[i + k];
            if ((d & 0xC0) != 0x80) return 0;
            cp = (cp << 6) | (d & 0x3F);
        }
        if (cp < min || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) return 0;
        i += n + 1;
    }
    return 1;
}
