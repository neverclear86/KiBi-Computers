// WASI p1 の cwd を、カーネルが渡す PWD から合わせる起動処理（kibi_cwd.c、#332）を確かめるプログラム。
// テスト（WasiP1CwdProgramTest）が次のように動かす:
//   引数: "cwd-wasi" <モード> <期待する cwd>
//   モード "follow": cwd がカーネルの cwd に合っていて、相対パスがそこから辿れることを確かめる。
//                    chdir("sub") の後、"WAIT" を出して標準入力の1行を待つ（その間にテストがカーネルの cwd を見る）
//   モード "fallback": cwd が使えないとき、"/" のまま動くことを確かめる
//   標準出力: 出力のストリーム
// 失敗したら "FAIL ..." を出して 1 で終わる。最後まで進めば "OK" を出して 0 で終わる。
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define CHECK(c)                                                  \
    do {                                                          \
        if (!(c)) {                                               \
            printf("FAIL %s (line %d)\n", #c, __LINE__);          \
            return 1;                                             \
        }                                                         \
    } while (0)

static int write_file(const char *path, const char *text) {
    FILE *f = fopen(path, "w");
    if (f == NULL) return 0;
    int ok = fputs(text, f) >= 0;
    return fclose(f) == 0 && ok;
}

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    CHECK(argc == 3);
    const char *expected = argv[2];
    char buf[256];

    // カーネルは PWD に、カーネルの cwd を入れる
    const char *pwd = getenv("PWD");
    CHECK(pwd != NULL && strcmp(pwd, expected) == 0);

    if (strcmp(argv[1], "follow") == 0) {
        CHECK(getcwd(buf, sizeof buf) != NULL);
        CHECK(strcmp(buf, expected) == 0);

        // 相対パスは cwd から辿る
        CHECK(write_file("rel.txt", "hi"));
        CHECK(mkdir("sub", 0755) == 0);
        CHECK(chdir("sub") == 0);
        char want[256];
        snprintf(want, sizeof want, "%s/sub", expected);
        CHECK(getcwd(buf, sizeof buf) != NULL);
        CHECK(strcmp(buf, want) == 0);
        CHECK(write_file("in_sub.txt", "deep"));
        CHECK(write_file("../up.txt", "up"));

        // ここで止まって、テストがカーネルの cwd を見る
        printf("WAIT\n");
        CHECK(fgets(buf, sizeof buf, stdin) != NULL);
    } else if (strcmp(argv[1], "fallback") == 0) {
        // PWD へ chdir できなかったので "/" のまま
        CHECK(getcwd(buf, sizeof buf) != NULL);
        CHECK(strcmp(buf, "/") == 0);
        CHECK(write_file("root.txt", "root"));
    } else {
        CHECK(0);
    }
    printf("OK\n");
    return 0;
}
