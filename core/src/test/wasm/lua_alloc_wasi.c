// Lua のアロケーターの包み（core/src/main/wasm/lua/kibi_wasi.c の kibi_lua_realloc。#409・#729・#802）を、
// Lua を通さずに直接呼んで、決まった道を通すプログラム。Lua から確かめると、どの道を通るかが lua.wasm の配置や
// 緊急の GC のやり直し（断られた確保を、GC の後にもう一度呼ぶ）で変わるため。
// 使い方:
//   lua-alloc grow-refuse : 残りを 20〜24 KiB にしてから、64 ページの memory.grow を起こす確保をする。
//                           確保の前の線（MIN）は通り、grow が区画の表と両端を数えた後の見直しで、残りが MIN を割って断られる道。
//                           結果を出して 1 で終わる（断られた commit があれば、カーネルが「メモリ不足だった」の注記を出す）
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "kibi_memory.h"

void *kibi_lua_realloc(void *ptr, size_t size);
void kibi_lua_free(void *ptr);

#define KIB 1024u
#define PAGE 65536u

// 書いた中身を外から見えるようにする（書き込みを最適化で消させない）
static volatile unsigned char sink;

// C のスタックを深くまで書いておく（今のうちに数えさせる）。包みのスタックの計数（今のスタックポインターから下 16 KiB）が、
// 後で新しい区画を数えないようにする。スタックは 64 KiB なので、下端（note_refusal が数える所）までは書かない
__attribute__((noinline)) static void touch_stack(void) {
    volatile unsigned char buf[32 * KIB];
    // volatile のまま書く（memset に渡すと、使わない書き込みとして消されうる）。区画ごとに 1 バイトで足りるが、念のため 256 バイトおき
    for (size_t i = 0; i < sizeof(buf); i += 256) buf[i] = 1;
    sink = buf[0];
}

static unsigned long long remaining(void) { return (unsigned long long)kibi_memory_remaining(); }

// 残りを [low, low + 4 KiB) にする。線形メモリを増やし、その中の区画を 1 つずつ数えさせる（keep 0）。できなければ 0
static int squeeze(unsigned long long low) {
    unsigned long pages = (unsigned long)(remaining() / PAGE) + 2;
    unsigned long before = __builtin_wasm_memory_grow(0, pages);
    if (before == (unsigned long)-1) return 0;
    unsigned char *p = (unsigned char *)(before * PAGE) + 4 * KIB;
    unsigned char *end = (unsigned char *)((before + pages) * PAGE) - 4 * KIB;
    while (remaining() >= low + 4 * KIB) {
        if (p >= end || !kibi_memory_commit(p, 4 * KIB, 0)) return 0;
        p += 4 * KIB;
    }
    return remaining() >= low;
}

int main(int argc, char **argv) {
    if (argc < 2) return 64;
    if (strcmp(argv[1], "grow-refuse") == 0) {
        touch_stack();
        // stdout のバッファ（静的な変数）と、BrkAllocator の大きな区画の空きリスト（静的な変数）を先に書いておく
        printf("start\n");
        fflush(stdout);
        void *warm = kibi_lua_realloc(NULL, 100 * KIB);
        if (warm == NULL) return 70;
        kibi_lua_free(warm);
        // grow が数える分は、両端の 2 区画（8 KiB）と区画の表（1 ページ 128 バイトで 64 ページ = 8 KiB）の 16 KiB。
        // 残りが 20〜24 KiB なら、確保の前の線（MIN 8 KiB）とスタックの計数（数える所がない）は通り、grow も通って、
        // その後の残りは 4〜8 KiB（MIN 未満。grow の後に 1 区画の書き込みを受けられる）
        if (!squeeze(20 * KIB)) return 71;
        unsigned long long before = remaining();
        unsigned long pages = __builtin_wasm_memory_size(0);
        // 3,000,000 バイトは、BrkAllocator では 64 ページ（2 のべき乗）の区画になる（kibi_brk.h の kibi_brk_big_pages）
        void *big = kibi_lua_realloc(NULL, 3000000);
        unsigned long grew = __builtin_wasm_memory_size(0) - pages;
        unsigned long long after = remaining();
        printf("before %llu grew %lu null %d after %llu\n", before, grew, big == NULL, after);
        fflush(stdout);
        return 1;
    }
    return 64;
}
