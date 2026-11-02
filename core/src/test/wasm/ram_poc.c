// #370 の PoC: 書き換えた 4 KiB で RAM を数えるときの、残りの RAM の問い合わせ（kibi_poc.ram_remaining）を確かめる
// 引数なし: 256 KiB を書いて、書く前と後の残りを出す。"exhaust": さらに書き続ける（上限でトラップになる）
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

__attribute__((import_module("kibi_poc"), import_name("ram_remaining")))
long long ram_remaining(void);

static char buf[256 * 1024];

/* 確保したものを最適化で消されないように、外から見える所に残す */
char *volatile kept;

int main(int argc, char **argv) {
    long long before = ram_remaining();
    memset(buf, 1, sizeof buf);
    long long after = ram_remaining();
    printf("before=%lld after=%lld\n", before, after);
    fflush(stdout);
    if (argc > 1 && strcmp(argv[1], "exhaust") == 0) {
        for (;;) {
            char *p = malloc(65536);
            if (p == NULL) {
                puts("malloc returned NULL");
                return 2;
            }
            memset(p, 2, 65536);
            kept = p;
        }
    }
    return buf[100] == 1 ? 0 : 1;
}
