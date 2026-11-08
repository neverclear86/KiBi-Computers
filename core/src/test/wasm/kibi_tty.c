// 独自拡張 kibi:io/tty（#427）の p1 の import を確かめる CLI。WasiP1TtyTest が動かす。
// 標準入力（fd 0）と標準エラー（fd 2）はコンソールの TTY、標準出力（fd 1）は TTY でないストリームにつないで動かす。
//
//   kibi-tty raw      raw にして fd 0 を1回ずつ読み、読んだバイトを出す（q で終わる。最後に cooked に戻す）
//   kibi-tty cooked   cooked のまま fd 0 を1回読み、読んだバイトを出す
//   kibi-tty values   is-tty・get-mode・set-mode・get-size・get-foreground・set-foreground の値
//   kibi-tty errors   bad-descriptor・no-tty・invalid と、mode の定義外のビット
//   kibi-tty resize   tty-resize のキューを poll で待ち、取り出したイベントと get-size を出す
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "kibi_events.h"
#include "kibi_io.h"

// take の結果の場所を取る
__attribute__((export_name("cabi_realloc")))
void *cabi_realloc(void *ptr, size_t old_size, size_t align, size_t new_size) {
    (void)ptr;
    (void)old_size;
    if (new_size == 0) return (void *)align;
    if (align > 16) abort();
    return malloc(new_size);
}

// 失敗なら -1 - error-code
static int64_t get_mode(uint32_t fd) {
    kibi_result_tty_mode_t r;
    memset(&r, 0xAA, sizeof r);
    kibi_io_tty_get_mode(fd, &r);
    return r.is_err ? -1 - (int64_t)r.val.err : (int64_t)r.val.ok;
}

static int64_t set_mode(uint32_t fd, int32_t mode) {
    kibi_result_unit_t r;
    memset(&r, 0xAA, sizeof r);
    kibi_io_tty_set_mode(fd, mode, &r);
    return r.is_err ? -1 - (int64_t)r.err : (r.err == 0 ? 0 : -1000);
}

static int64_t get_foreground(uint32_t fd) {
    kibi_result_u32_t r;
    memset(&r, 0xAA, sizeof r);
    kibi_io_tty_get_foreground(fd, &r);
    return r.is_err ? -1 - (int64_t)r.val.err : (int64_t)r.val.ok;
}

static int64_t set_foreground(uint32_t fd, uint32_t pid) {
    kibi_result_unit_t r;
    memset(&r, 0xAA, sizeof r);
    kibi_io_tty_set_foreground(fd, pid, &r);
    return r.is_err ? -1 - (int64_t)r.err : (r.err == 0 ? 0 : -1000);
}

// 成功なら "size C R G Z"、失敗なら "size err E Z"（Z は tag の後ろの詰め物が 0 なら 1）
static void print_size(uint32_t fd) {
    kibi_result_tty_size_t r;
    memset(&r, 0xAA, sizeof r);
    kibi_io_tty_get_size(fd, &r);
    const uint8_t *b = (const uint8_t *)&r;
    int padding_zero = 1;
    for (int i = 1; i < 8; i++) padding_zero &= b[i] == 0;
    if (r.is_err) {
        printf("size err %u %d\n", r.val.err, padding_zero);
    } else {
        printf("size %u %u %llu %d\n", r.val.ok.columns, r.val.ok.rows, (unsigned long long)r.val.ok.generation, padding_zero);
    }
}

static void print_read(const char *label, const uint8_t *buf, ssize_t n) {
    printf("%s %zd", label, n);
    for (ssize_t i = 0; i < n; i++) printf(" %02x", buf[i]);
    printf("\n");
}

static int run_raw(void) {
    if (set_mode(0, KIBI_TTY_MODE_RAW) != 0) return 1;
    printf("raw %lld\n", (long long)get_mode(0));
    uint8_t buf[16];
    for (;;) {
        ssize_t n = read(0, buf, sizeof buf);
        print_read("got", buf, n);
        if (n <= 0) return 1;
        if (buf[n - 1] == 'q') break;
    }
    if (set_mode(0, KIBI_TTY_MODE_COOKED) != 0) return 1;
    printf("cooked %lld\n", (long long)get_mode(0));
    return 0;
}

static int run_cooked(void) {
    printf("cooked %lld\n", (long long)get_mode(0));
    uint8_t buf[16];
    ssize_t n = read(0, buf, sizeof buf);
    print_read("got", buf, n);
    return n > 0 ? 0 : 1;
}

static int run_values(void) {
    printf("is-tty %d %d %d %d %d\n", kibi_io_tty_is_tty(0), kibi_io_tty_is_tty(1), kibi_io_tty_is_tty(2), kibi_io_tty_is_tty(200),
           kibi_io_tty_is_tty(0x80000000u));
    printf("mode %lld %lld\n", (long long)get_mode(0), (long long)get_mode(2));
    // fd 2 から変えても、同じ TTY なので fd 0 から見える
    int32_t no_echo = KIBI_TTY_MODE_COOKED & ~KIBI_TTY_MODE_ECHO;
    printf("set %lld %lld %lld\n", (long long)set_mode(2, no_echo), (long long)get_mode(0), (long long)get_mode(2));
    printf("restore %lld %lld\n", (long long)set_mode(0, KIBI_TTY_MODE_COOKED), (long long)get_mode(0));
    print_size(0);
    printf("fg %lld %lld %lld\n", (long long)get_foreground(0), (long long)set_foreground(0, 5), (long long)get_foreground(2));
    printf("fg max %lld %lld\n", (long long)set_foreground(0, 0x7fffffffu), (long long)get_foreground(0));
    printf("fg none %lld %lld\n", (long long)set_foreground(2, 0), (long long)get_foreground(0));
    return 0;
}

static int run_errors(void) {
    // bad-descriptor は判別子 7（-8）、invalid は 27（-28）、no-tty は 58（-59）
    printf("badf %lld %lld %lld %lld %lld\n", (long long)get_mode(200), (long long)get_mode(0x80000000u), (long long)set_mode(200, 0),
           (long long)get_foreground(0xffffffffu), (long long)set_foreground(200, 1));
    printf("notty %lld %lld %lld %lld\n", (long long)get_mode(1), (long long)set_mode(1, 0), (long long)get_foreground(1),
           (long long)set_foreground(1, 1));
    print_size(1);
    print_size(200);
    printf("inval %lld %lld %lld %lld\n", (long long)set_foreground(0, 0x80000000u), (long long)set_foreground(0, 0xffffffffu),
           (long long)set_foreground(200, 0x80000000u), (long long)get_foreground(0));
    // 定義外の上位ビットは読まない
    printf("high %lld %lld %lld %lld\n", (long long)set_mode(0, (int32_t)0xffffffe0u), (long long)get_mode(0),
           (long long)set_mode(0, -1), (long long)get_mode(0));
    return 0;
}

static int run_resize(void) {
    kibi_string_t kinds[1] = {{(const uint8_t *)"tty-resize", 10}};
    kibi_result_event_queue_t q;
    kibi_event_queue_open_raw(kinds, 1, 0, 0, &q);
    if (q.is_err) {
        printf("open failed %u\n", q.val.err);
        return 1;
    }
    print_size(0);
    printf("opened\n");
    struct pollfd pfd = {.fd = q.val.handle, .events = POLLIN};
    if (poll(&pfd, 1, -1) != 1 || !(pfd.revents & POLLIN)) {
        printf("poll failed\n");
        return 1;
    }
    kibi_result_event_batch_t r;
    kibi_event_queue_take_raw(q.val.handle, 16, &r);
    if (r.is_err) {
        printf("take failed %u\n", r.val.err);
        return 1;
    }
    printf("batch %u dropped %llu\n", r.val.ok.events.len, (unsigned long long)r.val.ok.dropped);
    for (uint32_t i = 0; i < r.val.ok.events.len; i++) {
        const kibi_event_t *e = &r.val.ok.events.ptr[i];
        printf("event %.*s [%.*s]", (int)e->kind.len, (const char *)e->kind.ptr, (int)e->source.len, (const char *)e->source.ptr);
        for (uint32_t j = 0; j < e->values.len; j++) {
            const kibi_event_value_t *v = &e->values.ptr[j];
            if (v->tag == KIBI_EVENT_VALUE_INTEGER) {
                printf(" %lld", (long long)v->val.integer);
            } else {
                printf(" ?%u", v->tag);
            }
        }
        printf("\n");
    }
    print_size(0);
    kibi_event_queue_drop(q.val.handle);
    return 0;
}

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    if (argc < 2) return 2;
    const char *mode = argv[1];
    if (strcmp(mode, "raw") == 0) return run_raw();
    if (strcmp(mode, "cooked") == 0) return run_cooked();
    if (strcmp(mode, "values") == 0) return run_values();
    if (strcmp(mode, "errors") == 0) return run_errors();
    if (strcmp(mode, "resize") == 0) return run_resize();
    return 2;
}
