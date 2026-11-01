// WASI p1 の poll_oneoff をカーネルの上で確かめるプログラム（#72）。
// テスト（WasiP1PollProgramTest）が次のように fd を用意して動かす:
//   0: 入力のストリーム（最初は空。テストが待ちを見てから "ping\n" を書く）
//   1: 出力のストリーム（大きい）
// 失敗したら "FAIL ..." を出して 1 を返す。最後まで進めば "OK" を出す。
#include <errno.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <wasi/api.h>

#define CHECK(c)                                                              \
    do {                                                                      \
        if (!(c)) {                                                           \
            printf("FAIL %s (line %d, errno %d)\n", #c, __LINE__, errno);     \
            return 1;                                                         \
        }                                                                     \
    } while (0)

static int64_t now_ns(clockid_t id) {
    struct timespec ts;
    if (clock_gettime(id, &ts) != 0) return -1;
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

int main(void) {
    setvbuf(stdout, NULL, _IONBF, 0);
    char buf[64];

    // poll(): 標準入力に書かれるまで待つ
    struct pollfd pfd = {.fd = 0, .events = POLLIN};
    CHECK(poll(&pfd, 1, 20000) == 1);
    CHECK(pfd.revents & POLLIN);
    CHECK(read(0, buf, sizeof buf) == 5 && memcmp(buf, "ping\n", 5) == 0);

    // poll(): 何も来なければタイムアウトで 0
    int64_t start = now_ns(CLOCK_MONOTONIC);
    pfd.revents = 0;
    CHECK(poll(&pfd, 1, 30) == 0);
    CHECK(now_ns(CLOCK_MONOTONIC) - start >= 30000000LL);

    // nanosleep（REALTIME の相対）
    start = now_ns(CLOCK_MONOTONIC);
    struct timespec d = {.tv_sec = 0, .tv_nsec = 20000000};
    CHECK(nanosleep(&d, NULL) == 0);
    CHECK(now_ns(CLOCK_MONOTONIC) - start >= 20000000LL);

    // clock_nanosleep（MONOTONIC の絶対時刻）
    int64_t until = now_ns(CLOCK_MONOTONIC) + 20000000LL;
    struct timespec abs = {.tv_sec = until / 1000000000LL, .tv_nsec = until % 1000000000LL};
    CHECK(clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &abs, NULL) == 0);
    CHECK(now_ns(CLOCK_MONOTONIC) >= until);

    // __wasi_poll_oneoff を直接: 空の標準入力と 10 ms の時計 → 時計だけ
    __wasi_subscription_t subs[2];
    __wasi_event_t events[2];
    __wasi_size_t n = 0;
    memset(subs, 0, sizeof subs);
    subs[0].userdata = 11;
    subs[0].u.tag = __WASI_EVENTTYPE_FD_READ;
    subs[0].u.u.fd_read.file_descriptor = 0;
    subs[1].userdata = 22;
    subs[1].u.tag = __WASI_EVENTTYPE_CLOCK;
    subs[1].u.u.clock.id = __WASI_CLOCKID_MONOTONIC;
    subs[1].u.u.clock.timeout = 10000000;
    CHECK(__wasi_poll_oneoff(subs, events, 2, &n) == 0);
    CHECK(n == 1 && events[0].userdata == 22 && events[0].type == __WASI_EVENTTYPE_CLOCK && events[0].error == 0);

    // 書ける標準出力と 1 秒の時計 → fd だけ。nbytes は空き
    memset(subs, 0, sizeof subs);
    subs[0].userdata = 33;
    subs[0].u.tag = __WASI_EVENTTYPE_FD_WRITE;
    subs[0].u.u.fd_write.file_descriptor = 1;
    subs[1].userdata = 44;
    subs[1].u.tag = __WASI_EVENTTYPE_CLOCK;
    subs[1].u.u.clock.id = __WASI_CLOCKID_MONOTONIC;
    subs[1].u.u.clock.timeout = 1000000000;
    CHECK(__wasi_poll_oneoff(subs, events, 2, &n) == 0);
    CHECK(n == 1 && events[0].userdata == 33 && events[0].type == __WASI_EVENTTYPE_FD_WRITE && events[0].fd_readwrite.nbytes > 0);

    // 開いていない fd は BADF のイベント
    subs[0].u.u.fd_write.file_descriptor = 42;
    CHECK(__wasi_poll_oneoff(subs, events, 1, &n) == 0);
    CHECK(n == 1 && events[0].userdata == 33 && events[0].error == __WASI_ERRNO_BADF);

    // 購読が 0 個は INVAL
    CHECK(__wasi_poll_oneoff(subs, events, 0, &n) == __WASI_ERRNO_INVAL);

    printf("OK\n");
    return 0;
}
