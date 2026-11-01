// #316 の検証用: 普通のコマンド形式の C の CLI。
//
// - 引数なし: 「> 」を出して fgets で1行待ち、行番号・行・ハッシュを出す。EOF か "exit" で終わる
// - `deep N`: 同じだが、N 段の再帰の底で fgets する（深いスタックを退避・再開する確認と測定）
// - `bench N`: 関数ポインタ経由の呼び出し（call_indirect）を含む計算を N 回して結果を出す（変換による速度の差を測る）
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static unsigned long hash(const char *s) {
    unsigned long h = 5381;
    while (*s) h = h * 33u + (unsigned char)*s++;
    return h;
}

// 各段で線形メモリのスタック（__stack_pointer）にも少し積む
static int read_deep(int n, char *buf, int len) {
    if (n <= 0) return fgets(buf, len, stdin) != NULL;
    volatile char pad[32];
    pad[0] = (char)n;
    int r = read_deep(n - 1, buf, len);
    return r + (pad[0] - (char)n);
}

static long add(long a, long b) { return a + b; }
static long mul(long a, long b) { return (a * b) % 1000003; }

static long (*volatile ops[2])(long, long) = {add, mul};

static int cmp_long(const void *a, const void *b) {
    long x = *(const long *)a, y = *(const long *)b;
    return (x > y) - (x < y);
}

static long bench(int n) {
    long acc = 1;
    long arr[64];
    for (int i = 0; i < n; i++) {
        acc = ops[i & 1](acc, i + 7);
        if ((i & 1023) == 0) {
            for (int j = 0; j < 64; j++) arr[j] = (acc * (j + 13)) % 977;
            qsort(arr, 64, sizeof arr[0], cmp_long);
            acc += arr[i & 63];
        }
    }
    return acc;
}

int main(int argc, char **argv) {
    if (argc > 2 && strcmp(argv[1], "bench") == 0) {
        printf("%ld\n", bench(atoi(argv[2])));
        return 0;
    }
    int depth = (argc > 2 && strcmp(argv[1], "deep") == 0) ? atoi(argv[2]) : 0;
    char line[256];
    unsigned long count = 0;
    for (;;) {
        fputs("> ", stdout);
        fflush(stdout);
        if (!read_deep(depth, line, sizeof line)) break;
        count++;
        line[strcspn(line, "\n")] = 0;
        if (strcmp(line, "exit") == 0) break;
        printf("%lu %s %lu\n", count, line, hash(line));
    }
    return 0;
}
