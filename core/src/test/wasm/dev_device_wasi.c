// /dev/device/<address> を開いた fd の種類を確かめるプログラム（#865。ADR 0054「fd とパスで種類が違う」）。
// テスト（DevDeviceFdProgramTest）が、p1 のまま・アダプターで包んだ p2・wasm32-wasip2 でビルドした p2 の3通りで動かす:
//   引数: "dev-device-wasi" <address>
//   標準出力: 出力のストリーム
// 失敗したら "FAIL ..." を出して 1 で終わる。最後まで進めば "OK" を出して 0 で終わる。
// fstat() は呼ばない（p2 の metadata-hash がファイル以外の fd で失敗するため。別 Issue）。
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#if !defined(__wasip2__) && !defined(__wasip3__)
#include <wasi/api.h>
#endif

#define CHECK(c)                                                  \
    do {                                                          \
        if (!(c)) {                                               \
            printf("FAIL %s (line %d)\n", #c, __LINE__);          \
            return 1;                                             \
        }                                                         \
    } while (0)

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    CHECK(argc == 2);
    char path[128];
    CHECK(snprintf(path, sizeof path, "/dev/device/%s", argv[1]) < (int)sizeof path);

    int fd = open(path, O_RDONLY);
    CHECK(fd >= 0);
    // device の fd は端末ではない
    CHECK(isatty(fd) == 0);
#if !defined(__wasip2__) && !defined(__wasip3__)
    // p1 の fd_fdstat_get（アダプターで包んだときは descriptor.get-type を写したもの）は UNKNOWN
    __wasi_fdstat_t st;
    CHECK(__wasi_fd_fdstat_get(fd, &st) == 0);
    CHECK(st.fs_filetype == __WASI_FILETYPE_UNKNOWN);
#endif
    CHECK(close(fd) == 0);

    printf("OK\n");
    return 0;
}
