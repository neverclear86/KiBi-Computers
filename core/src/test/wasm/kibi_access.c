// 独自拡張 kibi:computer/access（#110）の p1 の import を確かめる CLI。WasiP1AccessTest が動かす。
//
//   kac get                     get-access の結果を書く（cabi_realloc の呼び方、詰め物も）
//   kac add|remove|transfer S   request-* を呼び、"ok" か "err <判別子>" を書く
//   kac add-hex HEX             request-add に HEX のバイト列（不正な UTF-8 を渡すため）を渡す
//   kac add-oob                 request-add の文字列を線形メモリの外にする
//   kac realloc-add NAME        get-access の cabi_realloc の中で request-add NAME を呼び、両方の結果を書く
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "kibi_access.h"

static char log_buf[1024];
static size_t log_len = 0;
static const char *inner_name = NULL;
static kibi_result_unit_t inner_result;

static void say(const char *s) { write(1, s, strlen(s)); }

static void say_n(const char *s, size_t n) { write(1, s, n); }

static void say_u(unsigned long v) {
    char buf[24];
    int i = sizeof buf;
    do {
        buf[--i] = (char)('0' + v % 10);
        v /= 10;
    } while (v);
    say_n(buf + i, sizeof buf - i);
}

static void log_u(unsigned long v) {
    char buf[24];
    int i = sizeof buf;
    do {
        buf[--i] = (char)('0' + v % 10);
        v /= 10;
    } while (v);
    size_t n = sizeof buf - i;
    if (log_len + n + 1 < sizeof log_buf) {
        memcpy(log_buf + log_len, buf + i, n);
        log_len += n;
    }
}

static void log_c(char c) {
    if (log_len + 1 < sizeof log_buf) log_buf[log_len++] = c;
}

__attribute__((export_name("cabi_realloc")))
void *cabi_realloc(void *ptr, size_t old_size, size_t align, size_t new_size) {
    (void)ptr;
    (void)old_size;
    if (inner_name != NULL) {
        const char *n = inner_name;
        inner_name = NULL;
        kibi_access_request_add(n, strlen(n), &inner_result);
    }
    log_c(' ');
    log_u(align);
    log_c(':');
    log_u(new_size);
    void *p = aligned_alloc(align < sizeof(void *) ? sizeof(void *) : align, new_size == 0 ? 1 : new_size);
    memset(p, 0xEE, new_size);
    return p;
}

static void say_result(const kibi_result_unit_t *r) {
    if (r->is_err) {
        say("err ");
        say_u(r->err);
        say("\n");
    } else {
        say("ok\n");
    }
}

static void say_str(kibi_access_string_t s) { say_n(s.ptr, s.len); }

static int get(void) {
    kibi_access_result_t r;
    memset(&r, 0xAA, sizeof r);
    kibi_access_get(&r);
    const uint8_t *b = (const uint8_t *)&r;
    if (r.is_err) {
        say("err ");
        say_u(r.val.err);
        say("\npad");
        for (int i = 1; i < 44; i++) {
            if (i == 4) continue;
            if (b[i] != 0) {
                say(" ");
                say_u((unsigned long)i);
            }
        }
        say("\n");
        return 0;
    }
    const kibi_access_info_t *info = &r.val.info;
    say("state ");
    say_u(info->state);
    say("\nowner ");
    if (info->owner_is_some) {
        say_str(info->owner.name);
        say(" ");
        say_str(info->owner.id);
    } else {
        say("none");
    }
    say("\n");
    for (size_t i = 0; i < info->members_len; i++) {
        say("member ");
        say_str(info->members[i].name);
        say(" ");
        say_str(info->members[i].id);
        say("\n");
    }
    for (size_t i = 0; i < info->invites_len; i++) {
        say("invite ");
        say_str(info->invites[i]);
        say("\n");
    }
    // 詰め物（0 のはず）: 1..3、5..7、9..11。owner が none なら 12..27 も
    say("pad");
    int last = info->owner_is_some ? 12 : 28;
    for (int i = 1; i < last; i++) {
        if (i == 4 || i == 8) continue;
        if (b[i] != 0) {
            say(" ");
            say_u((unsigned long)i);
        }
    }
    say("\nrealloc");
    say_n(log_buf, log_len);
    say("\n");
    return 0;
}

static int hex(char c) { return c >= 'a' ? c - 'a' + 10 : c - '0'; }

int main(int argc, char **argv) {
    if (argc < 2) return 2;
    const char *mode = argv[1];
    kibi_result_unit_t r;
    if (strcmp(mode, "get") == 0) return get();
    if (strcmp(mode, "add-oob") == 0) {
        kibi_access_request_add((const char *)0x7FFFFFF0, 64, &r);
        say_result(&r);
        return 0;
    }
    if (argc < 3) return 2;
    const char *arg = argv[2];
    if (strcmp(mode, "add") == 0) kibi_access_request_add(arg, strlen(arg), &r);
    else if (strcmp(mode, "remove") == 0) kibi_access_request_remove(arg, strlen(arg), &r);
    else if (strcmp(mode, "transfer") == 0) kibi_access_request_transfer(arg, strlen(arg), &r);
    else if (strcmp(mode, "add-hex") == 0) {
        static char bytes[128];
        size_t n = strlen(arg) / 2;
        for (size_t i = 0; i < n && i < sizeof bytes; i++) bytes[i] = (char)(hex(arg[2 * i]) * 16 + hex(arg[2 * i + 1]));
        kibi_access_request_add(bytes, n, &r);
    } else if (strcmp(mode, "realloc-add") == 0) {
        inner_name = arg;
        get();
        say("inner ");
        say_result(&inner_result);
        return 0;
    } else {
        return 2;
    }
    say_result(&r);
    return 0;
}
