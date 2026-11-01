// 待機点への変換（#329）を確かめる、普通のコマンド形式の C の CLI（#316 の PoC の prompt.c を元にした）。
//
// - 引数なし: 「> 」を出して fgets で1行待ち、行番号・行・ハッシュを出す。EOF か "exit" で終わる
// - `deep N`: 同じだが、N 段の再帰の底で fgets する（深いスタックを退避・再開する確認）
// - `sort`: 同じだが、1行ごとに関数ポインタ（call_indirect）と qsort の計算をしてから出す
// - `sleep MS`: nanosleep で MS ミリ秒待ってから「slept」を出す（poll_oneoff の相対の時計）
// - `write N`: N バイト（'a'〜'z' の繰り返し）を標準出力に書いて「\ndone\n」を出す（パイプがいっぱいのときの fd_write）
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

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

static long compute(const char *line) {
    long acc = (long)hash(line) % 1000003;
    long arr[32];
    for (int i = 0; i < 64; i++) acc = ops[i & 1](acc, i + 7);
    for (int j = 0; j < 32; j++) arr[j] = (acc * (j + 13)) % 977;
    qsort(arr, 32, sizeof arr[0], cmp_long);
    return acc + arr[7];
}

int main(int argc, char **argv) {
    if (argc > 2 && strcmp(argv[1], "sleep") == 0) {
        long ms = atol(argv[2]);
        struct timespec d = {.tv_sec = ms / 1000, .tv_nsec = (ms % 1000) * 1000000L};
        if (nanosleep(&d, NULL) != 0) return 2;
        printf("slept\n");
        return 0;
    }
    if (argc > 2 && strcmp(argv[1], "write") == 0) {
        long n = atol(argv[2]);
        char *buf = malloc((size_t)n);
        for (long i = 0; i < n; i++) buf[i] = (char)('a' + i % 26);
        if (fwrite(buf, 1, (size_t)n, stdout) != (size_t)n) return 3;
        printf("\ndone\n");
        return 0;
    }
    int depth = (argc > 2 && strcmp(argv[1], "deep") == 0) ? atoi(argv[2]) : 0;
    int sort = argc > 1 && strcmp(argv[1], "sort") == 0;
    char line[256];
    unsigned long count = 0;
    for (;;) {
        fputs("> ", stdout);
        fflush(stdout);
        if (!read_deep(depth, line, sizeof line)) break;
        count++;
        line[strcspn(line, "\n")] = 0;
        if (strcmp(line, "exit") == 0) break;
        if (sort) {
            printf("%lu %s %ld\n", count, line, compute(line));
        } else {
            printf("%lu %s %lu\n", count, line, hash(line));
        }
    }
    return 0;
}
