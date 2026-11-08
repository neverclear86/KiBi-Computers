// 独自拡張 kibi:kernel/timers・kibi:computer/clock（#426）の p1 の import を確かめる CLI。WasiP1TimersTest が動かす。
//
//   kibi-timers once <ms>     作って set-after(ms) → poll の POLLIN で待つ → read（"once ..." の行）→ [resource-drop]
//   kibi-timers repeat        50 ms ごとの繰り返し。poll と read を繰り返して 3 回以上数え、cancel する（"repeat ..." の行）
//   kibi-timers at            clock_time_get(MONOTONIC) + 100 ms に set-at して待つ（"at ..." の行）
//   kibi-timers queue         キューを渡して作り、キューを poll で待って take する（"event ..." の行）。タイマーの read は 0
//   kibi-timers invalid       不正な入力ごとの error-code を出す（"inval ..." の行）
//   kibi-timers nodisc        判別子 0 なら後ろの値が何でも作れる（"nodisc ..." の行）
//   kibi-timers drop          [resource-drop]timer と fd_close で閉じ、閉じた後の read が bad-descriptor（"drop ..." の行）
//   kibi-timers drop-bad      タイマーでない fd（標準出力）を [resource-drop]timer に渡す（トラップになるはず）
//   kibi-timers clock         uptime-ticks を出し、200 ms 眠ってもう一度出す。world-ticks も出す（"clock ..." の行）
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "kibi_clock.h"
#include "kibi_events.h"
#include "kibi_timers.h"

#define MS 1000000ull

// take が cabi_realloc を使うので export する
__attribute__((export_name("cabi_realloc")))
void *cabi_realloc(void *ptr, size_t old_size, size_t align, size_t new_size) {
    (void)ptr;
    (void)old_size;
    if (new_size == 0) return (void *)align;
    if (align > 16) abort();
    return malloc(new_size);
}

// 作って、ハンドル（失敗なら -1 - error-code）を返す
static int32_t create(int32_t is_some, int32_t queue, int64_t tag) {
    kibi_result_timer_t r;
    memset(&r, 0xAA, sizeof r);
    kibi_timer_create_raw(is_some, queue, tag, &r);
    return r.is_err ? -1 - (int32_t)r.val.err : r.val.handle;
}

// 0 なら成功、そうでなければ -1 - error-code（成功なのに err の場所が 0 でなければ 99）
static int set_after(int32_t t, uint64_t d, uint64_t i) {
    kibi_result_unit_t r;
    memset(&r, 0xAA, sizeof r);
    kibi_timer_set_after_raw(t, d, i, &r);
    return r.is_err ? -1 - (int)r.err : (r.err == 0 ? 0 : 99);
}

static int set_at(int32_t t, uint64_t at, uint64_t i) {
    kibi_result_unit_t r;
    memset(&r, 0xAA, sizeof r);
    kibi_timer_set_at_raw(t, at, i, &r);
    return r.is_err ? -1 - (int)r.err : (r.err == 0 ? 0 : 99);
}

static int cancel(int32_t t) {
    kibi_result_unit_t r;
    memset(&r, 0xAA, sizeof r);
    kibi_timer_cancel_raw(t, &r);
    return r.is_err ? -1 - (int)r.err : 0;
}

// 回数（失敗なら -1 - error-code）
static long long read_count(int32_t t) {
    kibi_result_u64_t r;
    memset(&r, 0xAA, sizeof r);
    kibi_timer_read_raw(t, &r);
    return r.is_err ? -1 - (long long)r.val.err : (long long)r.val.ok;
}

static uint64_t now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}

static int wait_readable(int32_t fd) {
    struct pollfd pfd = {.fd = fd, .events = POLLIN};
    return poll(&pfd, 1, -1) == 1 && (pfd.revents & POLLIN);
}

static int run_once(uint64_t ms) {
    int32_t t = create(0, 0, 0);
    if (t < 0) {
        printf("create failed %d\n", t);
        return 1;
    }
    // 止まっているうちは 0
    printf("idle %lld\n", read_count(t));
    uint64_t start = now_ns();
    if (set_after(t, ms * MS, 0) != 0) return 1;
    printf("armed\n");
    if (!wait_readable(t)) {
        printf("poll failed\n");
        return 1;
    }
    uint64_t waited = now_ns() - start;
    long long n = read_count(t);
    long long again = read_count(t);
    printf("once %lld %lld late %d\n", n, again, waited >= ms * MS);
    kibi_timer_drop(t);
    return 0;
}

static int run_repeat(void) {
    int32_t t = create(0, 0, 0);
    if (t < 0 || set_after(t, 0, 50 * MS) != 0) return 1;
    long long total = 0;
    while (total < 3) {
        if (!wait_readable(t)) return 1;
        long long n = read_count(t);
        if (n < 0) return 1;
        total += n;
    }
    printf("repeat %d\n", total >= 3);
    printf("cancel %d\n", cancel(t));
    // 止めた後は 0（間隔の 3 倍眠っても鳴らない）
    struct timespec ts = {0, 150 * MS};
    nanosleep(&ts, NULL);
    printf("after cancel %lld\n", read_count(t));
    close(t);
    return 0;
}

static int run_at(void) {
    int32_t t = create(0, 0, 0);
    if (t < 0) return 1;
    uint64_t target = now_ns() + 100 * MS;
    if (set_at(t, target, 0) != 0) return 1;
    if (!wait_readable(t)) return 1;
    long long n = read_count(t);
    printf("at %lld late %d\n", n, now_ns() >= target);
    // 過ぎた時刻ならすぐ鳴る
    if (set_at(t, 0, 0) != 0) return 1;
    if (!wait_readable(t)) return 1;
    printf("past %lld\n", read_count(t));
    kibi_timer_drop(t);
    return 0;
}

static kibi_string_t str(const char *s) {
    kibi_string_t r = {(const uint8_t *)s, (uint32_t)strlen(s)};
    return r;
}

static int32_t open_queue(const char *kind) {
    kibi_string_t k = str(kind);
    kibi_result_event_queue_t r;
    kibi_event_queue_open_raw(&k, 1, 0, 0, &r);
    return r.is_err ? -1 - (int32_t)r.val.err : r.val.handle;
}

static int run_queue(void) {
    int32_t q = open_queue("timer");
    if (q < 0) return 1;
    int32_t t = create(1, q, -42);
    if (t < 0) {
        printf("create failed %d\n", t);
        return 1;
    }
    if (set_after(t, 50 * MS, 0) != 0) return 1;
    if (!wait_readable(q)) return 1;
    kibi_result_event_batch_t r;
    kibi_event_queue_take_raw(q, 16, &r);
    if (r.is_err) return 1;
    for (uint32_t i = 0; i < r.val.ok.events.len; i++) {
        const kibi_event_t *e = &r.val.ok.events.ptr[i];
        printf("event %.*s [%.*s]", (int)e->kind.len, (const char *)e->kind.ptr, (int)e->source.len, (const char *)e->source.ptr);
        for (uint32_t j = 0; j < e->values.len; j++) {
            const kibi_event_value_t *v = &e->values.ptr[j];
            if (v->tag == KIBI_EVENT_VALUE_INTEGER) {
                printf(" integer:%lld", (long long)v->val.integer);
            } else {
                printf(" ?%u", v->tag);
            }
        }
        printf("\n");
    }
    printf("timer read %lld\n", read_count(t));
    kibi_timer_drop(t);
    kibi_event_queue_drop(q);
    return 0;
}

static int run_invalid(void) {
    int32_t other = open_queue("other");
    if (other < 0) return 1;
    // invalid は -28、bad-descriptor は -8（-1 - 判別子）
    printf("inval create");
    printf(" %d", create(2, 0, 0));
    printf(" %d", create(1, 1, 0));
    printf(" %d", create(1, 9999, 0));
    printf(" %d", create(1, other, 0));
    printf("\n");
    int32_t t = create(0, 0, 0);
    if (t < 0) return 1;
    printf("inval interval");
    printf(" %d", set_after(t, 0, 1));
    printf(" %d", set_after(t, 0, 50 * MS - 1));
    printf(" %d", set_at(t, 0, 50 * MS - 1));
    printf(" %d", set_after(t, 1000 * MS, 50 * MS));
    printf(" %d", set_after(t, UINT64_MAX, UINT64_MAX));
    printf(" %d", set_at(t, UINT64_MAX, 0));
    printf(" %d", set_after(t, 0x8000000000000000ull, 0x8000000000000000ull));
    printf("\n");
    printf("inval self");
    printf(" %d", set_after(1, 0, 0));
    printf(" %d", set_at(other, 0, 0));
    printf(" %d", cancel(1));
    printf(" %lld", read_count(1));
    printf(" %lld", read_count(other));
    printf("\n");
    int c = cancel(t);
    printf("cancel %d %lld\n", c, read_count(t));
    kibi_timer_drop(t);
    kibi_event_queue_drop(other);
    return 0;
}

static int run_nodisc(void) {
    int32_t t = create(0, 12345, (int64_t)0x7eadbeefcafef00dll);
    int32_t u = create(0, -1, -1);
    printf("nodisc %d %d\n", t >= 0, u >= 0);
    if (t >= 0) kibi_timer_drop(t);
    if (u >= 0) kibi_timer_drop(u);
    return 0;
}

static int run_drop(void) {
    int32_t a = create(0, 0, 0);
    int32_t b = create(0, 0, 0);
    if (a < 0 || b < 0) return 1;
    if (set_after(a, 1000 * MS, 50 * MS) != 0) return 1;
    kibi_timer_drop(a);
    int closed = close(b);
    long long ra = read_count(a);
    long long rb = read_count(b);
    printf("drop %lld %d %lld\n", ra, closed, rb);
    return 0;
}

static int run_drop_bad(void) {
    printf("before\n");
    kibi_timer_drop(1);
    printf("returned\n");
    return 0;
}

static int run_clock(void) {
    uint64_t u1 = kibi_clock_uptime_ticks();
    struct timespec ts = {0, 200 * MS};
    nanosleep(&ts, NULL);
    uint64_t u2 = kibi_clock_uptime_ticks();
    printf("clock %d world %llu\n", u2 > u1, (unsigned long long)kibi_clock_world_ticks());
    return 0;
}

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    if (argc < 2) return 2;
    const char *mode = argv[1];
    if (strcmp(mode, "once") == 0) return run_once(argc > 2 ? strtoull(argv[2], NULL, 10) : 100);
    if (strcmp(mode, "repeat") == 0) return run_repeat();
    if (strcmp(mode, "at") == 0) return run_at();
    if (strcmp(mode, "queue") == 0) return run_queue();
    if (strcmp(mode, "invalid") == 0) return run_invalid();
    if (strcmp(mode, "nodisc") == 0) return run_nodisc();
    if (strcmp(mode, "drop") == 0) return run_drop();
    if (strcmp(mode, "drop-bad") == 0) return run_drop_bad();
    if (strcmp(mode, "clock") == 0) return run_clock();
    return 2;
}
