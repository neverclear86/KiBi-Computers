// WASI p1 の path_* をカーネルの上で確かめるプログラム（#71）。
// テスト（WasiP1PathProgramTest）が次のように用意して動かす:
//   0/2: 閉じたまま、1: 出力のストリーム
//   3: preopen の "/"（SpawnOptions.ROOT_PREOPEN）
//   VFS: /data/a.txt（中身 "abcde"）、/ro は読み取り専用
// 失敗したら "FAIL ..." を出して 1 を返す。最後まで進めば "OK" を出す。
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

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

    // 絶対パスで開く（preopen の "/" から辿る）
    int fd = open("/data/a.txt", O_RDONLY);
    CHECK(fd >= 0);
    CHECK(read(fd, buf, sizeof buf) == 5 && memcmp(buf, "abcde", 5) == 0);
    CHECK(close(fd) == 0);
    CHECK(open("/data/none.txt", O_RDONLY) == -1 && errno == ENOENT);

    // 作る・排他・切り詰め・追記
    fd = open("/data/b.txt", O_WRONLY | O_CREAT | O_EXCL, 0644);
    CHECK(fd >= 0);
    CHECK(write(fd, "12345", 5) == 5);
    CHECK(close(fd) == 0);
    CHECK(open("/data/b.txt", O_WRONLY | O_CREAT | O_EXCL, 0644) == -1 && errno == EEXIST);
    fd = open("/data/b.txt", O_WRONLY | O_TRUNC);
    CHECK(fd >= 0);
    CHECK(fstat(fd, &st) == 0 && st.st_size == 0);
    CHECK(write(fd, "xy", 2) == 2);
    CHECK(close(fd) == 0);
    fd = open("/data/b.txt", O_WRONLY | O_APPEND);
    CHECK(fd >= 0 && (fcntl(fd, F_GETFL) & O_APPEND));
    CHECK(write(fd, "z", 1) == 1);
    CHECK(close(fd) == 0);
    CHECK(stat("/data/b.txt", &st) == 0 && S_ISREG(st.st_mode) && st.st_size == 3);

    // stdio
    FILE *f = fopen("/data/c.txt", "w");
    CHECK(f != NULL);
    CHECK(fputs("line\n", f) >= 0);
    CHECK(fclose(f) == 0);
    f = fopen("/data/c.txt", "r");
    CHECK(f != NULL && fgets(buf, sizeof buf, f) != NULL && strcmp(buf, "line\n") == 0);
    CHECK(fclose(f) == 0);

    // ディレクトリ
    CHECK(mkdir("/data/d", 0755) == 0);
    CHECK(mkdir("/data/d", 0755) == -1 && errno == EEXIST);
    CHECK(stat("/data/d", &st) == 0 && S_ISDIR(st.st_mode));
    CHECK(rename("/data/c.txt", "/data/d/c.txt") == 0);
    CHECK(stat("/data/c.txt", &st) == -1 && errno == ENOENT);
    CHECK(rmdir("/data/d") == -1 && errno == ENOTEMPTY);
    DIR *d = opendir("/data/d");
    CHECK(d != NULL);
    int found = 0;
    struct dirent *e;
    while ((e = readdir(d)) != NULL) {
        if (strcmp(e->d_name, "c.txt") == 0 && e->d_type == DT_REG) found++;
    }
    CHECK(found == 1);
    CHECK(closedir(d) == 0);
    CHECK(unlink("/data/d/c.txt") == 0);
    CHECK(unlink("/data/d/c.txt") == -1 && errno == ENOENT);
    CHECK(rmdir("/data/d") == 0);
    CHECK(unlink("/data") == -1 && errno == EISDIR);

    // 読み取り専用
    CHECK(mkdir("/ro/x", 0755) == -1 && errno == EROFS);

    // 相対パス（作業ディレクトリは "/"）と chdir 後の相対パス
    CHECK(stat("data/a.txt", &st) == 0 && st.st_size == 5);
    CHECK(chdir("/data") == 0);
    CHECK(stat("a.txt", &st) == 0 && st.st_size == 5);
    CHECK(stat("../data/b.txt", &st) == 0 && st.st_size == 3);
    fd = open("rel.txt", O_WRONLY | O_CREAT, 0644);
    CHECK(fd >= 0 && write(fd, "R", 1) == 1 && close(fd) == 0);
    CHECK(stat("/data/rel.txt", &st) == 0 && st.st_size == 1);

    printf("OK\n");
    return 0;
}
