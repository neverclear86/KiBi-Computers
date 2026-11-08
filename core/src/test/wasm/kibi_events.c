// 独自拡張 kibi:kernel/events（#73）の p1 の import を確かめる CLI。WasiP1KibiTest が動かす。
//
//   kibi-events wait [close]   開く → poll で待つ → 取り出して標準出力に出す → [resource-drop]（close なら fd_close）で閉じる
//   kibi-events invalid        不正な入力ごとの error-code を出す（"inval ..." の行）
//   kibi-events max            max に 0・1・2^31・2^32-1 を渡したときの結果を出す（"max ..." の行）
//   kibi-events spin           wait と同じだが、取り出すときの cabi_realloc の中で重い計算をする
//   kibi-events realloc-poll   取り出すときの cabi_realloc の中で poll_oneoff を呼ぶ（トラップになるはず）
//   kibi-events realloc-write  取り出すときの cabi_realloc の中で fd_write を呼ぶ（トラップになるはず）
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <wasi/api.h>

#include "kibi_events.h"

#define MODE_NONE 0
#define MODE_SPIN 1
#define MODE_POLL 2
#define MODE_WRITE 3

static volatile int realloc_mode = MODE_NONE;
static int realloc_calls = 0;
static volatile uint64_t spin_sink = 0;

__attribute__((export_name("cabi_realloc")))
void *cabi_realloc(void *ptr, size_t old_size, size_t align, size_t new_size) {
    (void)ptr;
    (void)old_size;
    realloc_calls++;
    if (realloc_mode == MODE_SPIN) {
        uint64_t x = spin_sink;
        for (uint32_t i = 0; i < 3000000u; i++) x = x * 6364136223846793005ull + i;
        spin_sink = x;
    } else if (realloc_mode == MODE_POLL) {
        __wasi_subscription_t sub;
        memset(&sub, 0, sizeof sub);
        sub.u.tag = __WASI_EVENTTYPE_CLOCK;
        sub.u.u.clock.id = __WASI_CLOCKID_MONOTONIC;
        sub.u.u.clock.timeout = 1000;
        __wasi_event_t ev;
        __wasi_size_t n;
        (void)__wasi_poll_oneoff(&sub, &ev, 1, &n);
    } else if (realloc_mode == MODE_WRITE) {
        (void)write(1, "x", 1);
    }
    if (new_size == 0) return (void *)align;
    if (align > 16) abort();
    return malloc(new_size);
}

static kibi_string_t str(const char *s) {
    kibi_string_t r = {(const uint8_t *)s, (uint32_t)strlen(s)};
    return r;
}

// 開いて、ハンドル（失敗なら -1 - error-code）を返す
static int32_t open_queue(const kibi_string_t *kinds, uint32_t n, int has_cap, uint32_t cap) {
    kibi_result_event_queue_t r;
    memset(&r, 0xAA, sizeof r);
    kibi_event_queue_open_raw(kinds, n, has_cap, cap, &r);
    return r.is_err ? -1 - (int32_t)r.val.err : r.val.handle;
}

static void print_bytes(const uint8_t *p, uint32_t n) {
    for (uint32_t i = 0; i < n; i++) printf("%02x", p[i]);
}

static void print_batch(const kibi_event_batch_t *b) {
    printf("batch %u dropped %llu\n", b->events.len, (unsigned long long)b->dropped);
    for (uint32_t i = 0; i < b->events.len; i++) {
        const kibi_event_t *e = &b->events.ptr[i];
        printf("event %.*s [%.*s]", (int)e->kind.len, (const char *)e->kind.ptr, (int)e->source.len, (const char *)e->source.ptr);
        for (uint32_t j = 0; j < e->values.len; j++) {
            const kibi_event_value_t *v = &e->values.ptr[j];
            switch (v->tag) {
            case KIBI_EVENT_VALUE_INTEGER: printf(" integer:%lld", (long long)v->val.integer); break;
            case KIBI_EVENT_VALUE_FLOAT: printf(" float:%g", v->val.floating); break;
            case KIBI_EVENT_VALUE_BOOLEAN: printf(" boolean:%u", v->val.boolean); break;
            case KIBI_EVENT_VALUE_TEXT:
                printf(" text:");
                print_bytes(v->val.text.ptr, v->val.text.len);
                break;
            case KIBI_EVENT_VALUE_BYTES:
                printf(" bytes:");
                print_bytes(v->val.bytes.ptr, v->val.bytes.len);
                break;
            default: printf(" ?%u", v->tag); break;
            }
        }
        printf("\n");
    }
}

static int run_wait(int close_with_fd_close) {
    kibi_string_t kinds[2] = {str("test"), str("other")};
    int32_t q = open_queue(kinds, 2, 1, 8);
    if (q < 0) {
        printf("open failed %d\n", -1 - q);
        return 1;
    }
    printf("opened\n");
    struct pollfd pfd = {.fd = q, .events = POLLIN};
    if (poll(&pfd, 1, -1) != 1 || !(pfd.revents & POLLIN)) {
        printf("poll failed\n");
        return 1;
    }
    kibi_result_event_batch_t r;
    kibi_event_queue_take_raw(q, 16, &r);
    if (r.is_err) {
        printf("take failed %u\n", r.val.err);
        return 1;
    }
    print_batch(&r.val.ok);
    printf("realloc calls %d\n", realloc_calls);
    if (close_with_fd_close) {
        if (close(q) != 0) return 1;
    } else {
        kibi_event_queue_drop(q);
    }
    // 閉じた後は take が bad-descriptor（判別子 7）
    kibi_event_queue_take_raw(q, 1, &r);
    printf("after close %u %u\n", r.is_err, r.is_err ? r.val.err : 0);
    return 0;
}

static int run_invalid(void) {
    static kibi_string_t many[17];
    for (int i = 0; i < 17; i++) many[i] = str("k");
    static char long_kind[66];
    memset(long_kind, 'a', 65);
    kibi_string_t k65 = {(const uint8_t *)long_kind, 65};
    kibi_string_t k64 = {(const uint8_t *)long_kind, 64};
    kibi_string_t empty = {(const uint8_t *)long_kind, 0};
    static const uint8_t bad1[] = {0xff};
    static const uint8_t bad2[] = {0xe3, 0x81};
    static const uint8_t bad3[] = {0xed, 0xa0, 0x80};
    kibi_string_t b1 = {bad1, 1}, b2 = {bad2, 2}, b3 = {bad3, 3};
    kibi_string_t oob = {(const uint8_t *)0xfffffff0u, 8};

    printf("inval");
    printf(" %d", open_queue(many, 17, 0, 0));
    printf(" %d", open_queue(&empty, 1, 0, 0));
    printf(" %d", open_queue(&k65, 1, 0, 0));
    printf(" %d", open_queue(&b1, 1, 0, 0));
    printf(" %d", open_queue(&b2, 1, 0, 0));
    printf(" %d", open_queue(&b3, 1, 0, 0));
    printf(" %d", open_queue((const kibi_string_t *)0xfffffff8u, 2, 0, 0));
    printf(" %d", open_queue(&oob, 1, 0, 0));
    printf(" %d", open_queue(&k64, 0x7fffffffu, 0, 0));
    printf(" %d", open_queue(NULL, 0, 1, 0));
    printf(" %d", open_queue(NULL, 0, 1, 257));
    printf(" %d", open_queue(NULL, 0, 1, 0x80000000u));
    printf("\n");

    int32_t a = open_queue(many, 16, 1, 1);
    int32_t b = open_queue(&k64, 1, 1, 256);
    printf("ok %d %d\n", a >= 0, b >= 0);
    kibi_event_queue_drop(a);
    kibi_event_queue_drop(b);

    kibi_result_event_batch_t r;
    kibi_event_queue_take_raw(1, 1, &r);
    printf("badf %u %u\n", r.is_err, r.val.err);
    return 0;
}

static int run_max(void) {
    int32_t q = open_queue(NULL, 0, 0, 0);
    if (q < 0) return 1;
    static const uint32_t maxes[] = {0, 1, 0x80000000u, 0xffffffffu};
    printf("max");
    for (int i = 0; i < 4; i++) {
        kibi_result_event_batch_t r;
        kibi_event_queue_take_raw(q, maxes[i], &r);
        if (r.is_err) {
            printf(" err:%u", r.val.err);
        } else {
            printf(" ok:%u", r.val.ok.events.len);
        }
    }
    printf("\n");
    kibi_event_queue_drop(q);
    return 0;
}

static int run_in_realloc(int mode) {
    int32_t q = open_queue(NULL, 0, 0, 0);
    if (q < 0) return 1;
    printf("opened\n");
    realloc_mode = mode;
    kibi_result_event_batch_t r;
    kibi_event_queue_take_raw(q, 1, &r);
    realloc_mode = MODE_NONE;
    printf("returned %u\n", r.is_err);
    return 0;
}

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    if (argc < 2) return 2;
    const char *mode = argv[1];
    if (strcmp(mode, "wait") == 0) return run_wait(argc > 2 && strcmp(argv[2], "close") == 0);
    if (strcmp(mode, "spin") == 0) {
        realloc_mode = MODE_SPIN;
        return run_wait(0);
    }
    if (strcmp(mode, "invalid") == 0) return run_invalid();
    if (strcmp(mode, "max") == 0) return run_max();
    if (strcmp(mode, "realloc-poll") == 0) return run_in_realloc(MODE_POLL);
    if (strcmp(mode, "realloc-write") == 0) return run_in_realloc(MODE_WRITE);
    return 2;
}
