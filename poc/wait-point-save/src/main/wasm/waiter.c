/*
 * #30 の検証用: C だけで書いた最小の reactor。
 *
 * ホストが poc_step(kind, arg) でイベントを1つ渡すと、処理して戻る（＝ WASM のスタックが空になる）。
 * 状態は malloc したリストと静的変数、libc の stdout のバッファ（改行まで出力されない）に持つ。
 * 名前（poc_step、kibi_poc.block）はこの PoC だけの仮のもので、システムコールの ABI ではない。
 */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ホスト関数の中で待つ「ブロッキング型」の例 */
__attribute__((import_module("kibi_poc"), import_name("block"))) int32_t kibi_block(int32_t arg);

typedef struct node {
    int32_t value;
    struct node *next;
} node;

static node *head;
static int32_t count;
static int64_t sum;
static unsigned char *big;
static size_t big_size;

enum {
    /* 値をリストに足し、改行なしで出力する（stdout のバッファに残る） */
    EV_PUSH = 0,
    /* arg バイトを確保して模様で埋める（memory.grow が起こる） */
    EV_ALLOC = 1,
    /* 集計を出力して改行する（バッファが流れる） */
    EV_REPORT = 2,
    /* ホスト関数を同期で呼ぶ（その中では保存できない） */
    EV_BLOCK = 3,
};

__attribute__((export_name("poc_step"))) int32_t poc_step(int32_t kind, int32_t arg) {
    switch (kind) {
    case EV_PUSH: {
        node *n = malloc(sizeof(node));
        n->value = arg;
        n->next = head;
        head = n;
        count++;
        sum += arg;
        printf("[%d]", arg);
        return count;
    }
    case EV_ALLOC: {
        unsigned char *p = realloc(big, big_size + (size_t)arg);
        if (p == NULL) return -1;
        for (size_t i = big_size; i < big_size + (size_t)arg; i++) p[i] = (unsigned char)(i * 31 + 7);
        big = p;
        big_size += (size_t)arg;
        return (int32_t)(big_size / 1024);
    }
    case EV_REPORT: {
        uint32_t check = 0;
        for (size_t i = 0; i < big_size; i += 4096) check = check * 33 + big[i];
        int64_t walk = 0;
        for (node *n = head; n != NULL; n = n->next) walk += n->value;
        printf(" count=%d sum=%lld walk=%lld big=%zu check=%u\n", count, (long long)sum, (long long)walk, big_size, check);
        return count;
    }
    case EV_BLOCK:
        return kibi_block(arg);
    default:
        return -1;
    }
}
