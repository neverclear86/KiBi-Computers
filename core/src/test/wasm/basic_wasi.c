// WASI p1 のファイル以外の基本的な関数（args・environ・clock・random・sched_yield・proc_exit）を
// カーネルの上で確かめるプログラム（#69）。テスト（WasiP1BasicProgramTest）が次のように動かす:
//   引数: "basic-wasi" "alpha" "ベータ"
//   環境変数: GREETING=hello、EMPTY=（空）
//   標準出力: 出力のストリーム
// 失敗したら "FAIL ..." を出して 1 で終わる。最後まで進めば "OK" を出して exit(42) で終わる。
#include <sched.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <wasi/api.h>

#define CHECK(c)                                                  \
    do {                                                          \
        if (!(c)) {                                               \
            printf("FAIL %s (line %d)\n", #c, __LINE__);          \
            return 1;                                             \
        }                                                         \
    } while (0)

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IONBF, 0);

    // 引数
    CHECK(argc == 3);
    CHECK(strcmp(argv[0], "basic-wasi") == 0);
    CHECK(strcmp(argv[1], "alpha") == 0);
    CHECK(strcmp(argv[2], "\xe3\x83\x99\xe3\x83\xbc\xe3\x82\xbf") == 0);
    CHECK(argv[3] == NULL);

    // 環境変数
    const char *g = getenv("GREETING");
    CHECK(g != NULL && strcmp(g, "hello") == 0);
    const char *e = getenv("EMPTY");
    CHECK(e != NULL && e[0] == '\0');
    CHECK(getenv("MISSING") == NULL);

    // 時計
    struct timespec res, a, b, rt;
    CHECK(clock_getres(CLOCK_MONOTONIC, &res) == 0 && res.tv_sec == 0 && res.tv_nsec > 0);
    CHECK(clock_gettime(CLOCK_MONOTONIC, &a) == 0);
    CHECK(clock_gettime(CLOCK_MONOTONIC, &b) == 0);
    CHECK(b.tv_sec > a.tv_sec || (b.tv_sec == a.tv_sec && b.tv_nsec >= a.tv_nsec));
    CHECK(clock_gettime(CLOCK_REALTIME, &rt) == 0 && rt.tv_sec > 1600000000);  // 2020 年より後
    CHECK(time(NULL) > 1600000000);
    __wasi_timestamp_t t = 0;
    CHECK(__wasi_clock_time_get(__WASI_CLOCKID_PROCESS_CPUTIME_ID, 1, &t) == 0);
    CHECK(__wasi_clock_time_get(99, 1, &t) == __WASI_ERRNO_INVAL);

    // 乱数（64 バイトがすべて 0 になることはまずない）
    unsigned char buf[64];
    memset(buf, 0, sizeof buf);
    CHECK(__wasi_random_get(buf, sizeof buf) == 0);
    int nonzero = 0;
    for (size_t i = 0; i < sizeof buf; i++) nonzero |= buf[i];
    CHECK(nonzero != 0);

    // sched_yield
    CHECK(sched_yield() == 0);

    printf("OK\n");
    exit(42);
}
