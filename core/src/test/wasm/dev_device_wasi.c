// /dev/device/<address> と /dev/tty を開いた fd の種類と fstat を確かめるプログラム（#865・#885。ADR 0054「fd とパスで種類が違う」）。
// テスト（DevDeviceFdProgramTest）が、p1 のまま・アダプターで包んだ p2・wasm32-wasip2 でビルドした p2 の3通りで動かす:
//   引数: "dev-device-wasi" <address>
//   標準出力: 出力のストリーム
// 失敗したら "FAIL ..." を出して 1 で終わる。最後まで進めば "OK" を出して 0 で終わる。
// p2 の fstat() は先に metadata-hash を呼ぶので、ファイル以外の fd の metadata-hash が ok であることもここで見る（#885）。
// st_dev は見ない（アダプターで包んだ p2 はいつも 1、ほかは 0 で、アダプターの都合を固定することになるため）。
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
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
    // device の fd は端末ではない。
    // p1 のままでは種類（UNKNOWN）で偽になる。包んだ p2 はアダプターが SEEK・TELL の権利を残すので種類に関係なく偽、
    // ネイティブの p2 は wasi-libc が種類を見ないので偽（どちらも後退の見張りだけ）
    CHECK(isatty(fd) == 0);
#if !defined(__wasip2__) && !defined(__wasip3__)
    // p1 の fd_fdstat_get（アダプターで包んだときは descriptor.get-type を写したもの）は UNKNOWN
    __wasi_fdstat_t st;
    CHECK(__wasi_fd_fdstat_get(fd, &st) == 0);
    CHECK(st.fs_filetype == __WASI_FILETYPE_UNKNOWN);
#endif
    // fstat は成功し、種類のビットは 0（UNKNOWN）、st_ino は 0（p2 は metadata-hash の lower。#885）
    struct stat sb;
    CHECK(fstat(fd, &sb) == 0);
    CHECK((sb.st_mode & S_IFMT) == 0);
    CHECK(sb.st_ino == 0);
    CHECK(close(fd) == 0);

    // /dev/tty の fd も fstat が成功し、文字デバイスで st_ino は 0。
    // isatty は3通りで結果が違う（p2 では偽になる）ので見ない
    int tty = open("/dev/tty", O_RDWR);
    CHECK(tty >= 0);
    CHECK(fstat(tty, &sb) == 0);
    CHECK(S_ISCHR(sb.st_mode));
    CHECK(sb.st_ino == 0);
    CHECK(close(tty) == 0);

    printf("OK\n");
    return 0;
}
