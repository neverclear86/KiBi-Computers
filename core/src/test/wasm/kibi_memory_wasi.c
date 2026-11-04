// 独自拡張 kibi:memory（#408）の p1 の import を、カーネルの上で確かめるプログラム。
// 使い方:
//   kibi-memory remaining <KiB> : remaining を出し、malloc した <KiB> に書いて、もう一度出す
//   kibi-memory commit <KiB>    : malloc した範囲を commit(p, n, 0) し、前後の remaining を出す。続けて書き、remaining が変わらないことを出す
//   kibi-memory commit-keep     : 小さな範囲を、とても大きい keep で commit し、0 が返って remaining が変わらないことを出す
//   kibi-memory commit-oob      : 線形メモリの外の範囲で 0、len = 0 で 1、書いた範囲をもう一度（keep が大きくても）で 1 を出す
//   kibi-memory refuse <終了コード> : RAM より大きな範囲を commit して 0 を受け、その終了コードで終わる
//   kibi-memory refuse-trap     : 同じく断られた後に abort でトラップする
//   kibi-memory refuse-grow <終了コード> : commit を断られ、memory.grow も断られた後に、その終了コードで終わる
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "kibi_memory.h"

#define KIB 1024u

static unsigned long long remaining(void) { return (unsigned long long)kibi_memory_remaining(); }

// 書いた中身を外から見えるようにする（書き込みを最適化で消させない）
static volatile unsigned char sink;

static void fill(unsigned char *p, size_t n) {
    memset(p, 1, n);
    sink = p[0];
    sink = p[n - 1];
}

// 線形メモリの終わり（アドレス）
static unsigned long memory_end(void) { return __builtin_wasm_memory_size(0) * 65536ul; }

// RAM より大きな範囲を、まだ書いていない所（線形メモリを増やした所）で commit する。断られたら 0 を返す
static int commit_too_much(void) {
    unsigned long long left = remaining();
    // 増やした所の最初と最後の 4 KiB は grow で数えるので、その間を commit する
    unsigned long pages = (unsigned long)((left + 2 * 65536ull) / 65536ull) + 1;
    unsigned long before = __builtin_wasm_memory_grow(0, pages);
    if (before == (unsigned long)-1) return -1;
    unsigned char *base = (unsigned char *)(before * 65536ul);
    return kibi_memory_commit(base, (uint32_t)(pages * 65536ul), 0);
}

int main(int argc, char **argv) {
    if (argc < 2) return 64;
    if (strcmp(argv[1], "remaining") == 0 && argc >= 3) {
        size_t n = (size_t)atoi(argv[2]) * KIB;
        unsigned char *p = malloc(n);
        if (p == NULL) return 70;
        unsigned long long before = remaining();
        fill(p, n);
        unsigned long long after = remaining();
        printf("remaining decreased %llu\n", before - after);
        return 0;
    }
    if (strcmp(argv[1], "commit") == 0 && argc >= 3) {
        size_t n = (size_t)atoi(argv[2]) * KIB;
        unsigned char *p = malloc(n);
        if (p == NULL) return 70;
        unsigned long long before = remaining();
        int ok = kibi_memory_commit(p, (uint32_t)n, 0);
        unsigned long long committed = remaining();
        fill(p, n);
        unsigned long long written = remaining();
        printf("commit %d decreased %llu\n", ok, before - committed);
        printf("write decreased %llu\n", committed - written);
        return 0;
    }
    if (strcmp(argv[1], "commit-keep") == 0) {
        unsigned char *p = malloc(64 * KIB);
        if (p == NULL) return 70;
        unsigned long long before = remaining();
        int huge = kibi_memory_commit(p, 64 * KIB, ~0ull);
        int over = kibi_memory_commit(p, 64 * KIB, before);
        unsigned long long after = remaining();
        // 数えた後に keep 以上残るなら数える
        int fits = kibi_memory_commit(p, 4 * KIB, before - 64 * KIB);
        unsigned long long fitted = remaining();
        printf("keep %d %d decreased %llu\n", huge, over, before - after);
        printf("fits %d decreased %llu\n", fits, after - fitted);
        return 0;
    }
    if (strcmp(argv[1], "commit-oob") == 0) {
        unsigned char *p = malloc(16 * KIB);
        if (p == NULL) return 70;
        fill(p, 16 * KIB);
        unsigned long end = memory_end();
        int oob = kibi_memory_commit((void *)(end - 4), 8, 0);
        int wrap = kibi_memory_commit((void *)0xfffff000ul, 0x2000, 0);
        int empty_end = kibi_memory_commit((void *)end, 0, 0);
        int empty_past = kibi_memory_commit((void *)(end + 1), 0, 0);
        unsigned long long before = remaining();
        int again = kibi_memory_commit(p, 16 * KIB, ~0ull);
        unsigned long long after = remaining();
        printf("oob %d %d empty %d %d again %d decreased %llu\n", oob, wrap, empty_end, empty_past, again, before - after);
        return 0;
    }
    if (strcmp(argv[1], "refuse") == 0 && argc >= 3) {
        int code = atoi(argv[2]);
        int ok = commit_too_much();
        printf("refused %d\n", ok);
        fflush(stdout);
        return code;
    }
    if (strcmp(argv[1], "refuse-trap") == 0) {
        int ok = commit_too_much();
        printf("refused %d\n", ok);
        fflush(stdout);
        abort();
    }
    if (strcmp(argv[1], "refuse-grow") == 0 && argc >= 3) {
        int code = atoi(argv[2]);
        int ok = commit_too_much();
        printf("refused %d\n", ok);
        fflush(stdout);
        while (__builtin_wasm_memory_grow(0, 1) != (unsigned long)-1) {
        }
        return code;
    }
    return 64;
}
