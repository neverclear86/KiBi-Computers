// ネイティブの wasm32-wasip2 で、args・environ・時計・乱数・sched_yield を確かめるプログラム（#775）。
// basic_wasi.c の p1 専用の呼び出し（__wasi_*）を、標準 C・POSIX の呼び出しにしたもの。テスト（NativeToolchainComponentTest）が次のように動かす:
//   引数: "basic" "alpha" "ベータ"
//   環境変数: GREETING=hello、EMPTY=（空）
//   標準出力: 出力のストリーム
// 失敗したら "FAIL ..." を出して 1 で終わる。最後まで進めば "OK" を出して終わる。
#include <sched.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

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
    CHECK(strcmp(argv[0], "basic") == 0);
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

    // 乱数（getentropy は wasi:random/random の get-random-bytes）
    unsigned char buf[64];
    memset(buf, 0, sizeof buf);
    CHECK(getentropy(buf, sizeof buf) == 0);
    int nonzero = 0;
    for (size_t i = 0; i < sizeof buf; i++) nonzero |= buf[i];
    CHECK(nonzero != 0);

    // sched_yield
    CHECK(sched_yield() == 0);

    printf("OK\n");
    return 0;
}
