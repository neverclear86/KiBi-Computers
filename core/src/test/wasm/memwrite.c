// #370 の PoC: メモリを多く書くプログラム（4 KiB 単位と 64 KiB 単位の記録の速度の比較に使う）
// 使い方: memwrite <memset|memcpy|stride|scatter> <MiB> <回数>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main(int argc, char **argv) {
    if (argc < 4) return 64;
    const char *mode = argv[1];
    size_t n = (size_t)atoi(argv[2]) * 1024 * 1024;
    int reps = atoi(argv[3]);
    unsigned char *a = malloc(n);
    unsigned char *b = malloc(n);
    if (a == NULL || b == NULL) return 70;
    uint32_t sum = 0;
    if (strcmp(mode, "memset") == 0) {
        for (int i = 0; i < reps; i++) memset(a, i, n);
        sum = a[n / 2];
    } else if (strcmp(mode, "memcpy") == 0) {
        memset(a, 7, n);
        for (int i = 0; i < reps; i++) {
            if (i & 1) memcpy(a, b, n); else memcpy(b, a, n);
        }
        sum = a[1] + b[n - 1];
    } else if (strcmp(mode, "stride") == 0) {
        // 4 KiB ごとに 1 バイト（初めての書き込みの例外がいちばん多い形）
        for (int i = 0; i < reps; i++) {
            for (size_t off = 0; off < n; off += 4096) a[off] = (unsigned char)(i + off);
        }
        for (size_t off = 0; off < n; off += 4096) sum += a[off];
    } else if (strcmp(mode, "scatter") == 0) {
        // 範囲の中に飛び飛びに 4 バイトずつ書く
        uint32_t x = 12345;
        for (int i = 0; i < reps; i++) {
            for (int k = 0; k < 1000000; k++) {
                x = x * 1103515245u + 12345u;
                size_t off = (x % (uint32_t)(n / 4)) * 4;
                *(uint32_t *)(a + off) = x;
                sum += x;
            }
        }
    } else {
        return 64;
    }
    printf("%u\n", sum);
    return 0;
}
