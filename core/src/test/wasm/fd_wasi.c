// WASI p1 の fd_* をカーネルの上で確かめるプログラム（#70）。
// テスト（WasiP1FdProgramTest）が次のように fd を用意して動かす:
//   0: 入力のストリーム（最初は空。テストが後から "hello\n" を書いて閉じる）
//   1: 出力のストリーム
//   3: preopen のディレクトリ "/"（中身は data/）
//   4: /data/a.txt（中身 "abcde"、読み書き）
// 失敗したら "FAIL ..." を出して 1 を返す。最後まで進めば "OK" を出す。
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include <wasi/api.h>

#define CHECK(c)                                                              \
    do {                                                                      \
        if (!(c)) {                                                           \
            printf("FAIL %s (line %d, errno %d)\n", #c, __LINE__, errno);     \
            return 1;                                                         \
        }                                                                     \
    } while (0)

int main(void) {
    setvbuf(stdout, NULL, _IONBF, 0);
    char buf[256];
    struct stat st;

    // 標準入力: 書かれるまで待ち、閉じられたら EOF
    ssize_t n = read(0, buf, sizeof buf);
    CHECK(n == 6 && memcmp(buf, "hello\n", 6) == 0);
    CHECK(read(0, buf, sizeof buf) == 0);
    CHECK(!isatty(1));

    // 普通のファイル
    CHECK(fstat(4, &st) == 0 && S_ISREG(st.st_mode) && st.st_size == 5);
    CHECK(read(4, buf, 3) == 3 && memcmp(buf, "abc", 3) == 0);
    CHECK(lseek(4, 0, SEEK_CUR) == 3);
    __wasi_filesize_t pos = 0;
    CHECK(__wasi_fd_tell(4, &pos) == 0 && pos == 3);
    CHECK(lseek(4, 0, SEEK_END) == 5);
    CHECK(write(4, "XY", 2) == 2);
    CHECK(pread(4, buf, 16, 0) == 7 && memcmp(buf, "abcdeXY", 7) == 0);
    CHECK(pwrite(4, "Z", 1, 0) == 1);
    CHECK(lseek(4, 0, SEEK_CUR) == 7);
    CHECK(pread(4, buf, 1, 0) == 1 && buf[0] == 'Z');
    CHECK(ftruncate(4, 4) == 0);
    CHECK(fstat(4, &st) == 0 && st.st_size == 4);
    CHECK(fsync(4) == 0 && fdatasync(4) == 0);
    CHECK(__wasi_fd_allocate(4, 0, 10) == 0);
    CHECK(fstat(4, &st) == 0 && st.st_size == 10);
    CHECK(__wasi_fd_advise(4, 0, 10, __WASI_ADVICE_SEQUENTIAL) == 0);
    CHECK(__wasi_fd_filestat_set_times(4, 111, 222, __WASI_FSTFLAGS_ATIM | __WASI_FSTFLAGS_MTIM) == 0);
    CHECK(fstat(4, &st) == 0 && st.st_atim.tv_nsec == 111 && st.st_mtim.tv_nsec == 222);

    // 追記
    int fl = fcntl(4, F_GETFL);
    CHECK(fl >= 0 && !(fl & O_APPEND));
    CHECK(fcntl(4, F_SETFL, fl | O_APPEND) == 0);
    CHECK(fcntl(4, F_GETFL) & O_APPEND);
    CHECK(lseek(4, 0, SEEK_SET) == 0);
    CHECK(write(4, "Q", 1) == 1);
    CHECK(lseek(4, 0, SEEK_CUR) == 11);

    // エラー
    CHECK(lseek(1, 0, SEEK_CUR) == -1 && errno == ESPIPE);
    CHECK(lseek(4, -100, SEEK_SET) == -1 && errno == EINVAL);
    CHECK(write(99, "x", 1) == -1 && errno == EBADF);
    CHECK(read(1, buf, 1) == -1 && errno == EBADF);

    // preopen
    __wasi_prestat_t pre;
    CHECK(__wasi_fd_prestat_get(3, &pre) == 0 && pre.tag == __WASI_PREOPENTYPE_DIR && pre.u.dir.pr_name_len == 1);
    char name[8] = {0};
    CHECK(__wasi_fd_prestat_dir_name(3, (uint8_t *)name, sizeof name) == 0 && strcmp(name, "/") == 0);
    CHECK(__wasi_fd_prestat_get(4, &pre) == __WASI_ERRNO_BADF);

    // ディレクトリの中身（入りきらない dirent は途中で切る）
    __wasi_size_t used = 0;
    CHECK(__wasi_fd_readdir(3, (uint8_t *)buf, 30, 0, &used) == 0 && used == 30);
    CHECK(fstat(3, &st) == 0 && S_ISDIR(st.st_mode));

    // 番号の付け替え
    CHECK(__wasi_fd_renumber(4, 10) == 0);
    CHECK(fstat(4, &st) == -1 && errno == EBADF);
    CHECK(fstat(10, &st) == 0 && st.st_size == 11);
    CHECK(close(10) == 0);
    CHECK(close(10) == -1 && errno == EBADF);

    DIR *d = fdopendir(3);
    CHECK(d != NULL);
    int dot = 0, dotdot = 0, data = 0, other = 0;
    struct dirent *e;
    while ((e = readdir(d)) != NULL) {
        if (strcmp(e->d_name, ".") == 0) dot++;
        else if (strcmp(e->d_name, "..") == 0) dotdot++;
        else if (strcmp(e->d_name, "data") == 0 && e->d_type == DT_DIR) data++;
        else other++;
    }
    CHECK(dot == 1 && dotdot == 1 && data == 1 && other == 0);
    CHECK(closedir(d) == 0);

    printf("OK\n");
    return 0;
}
