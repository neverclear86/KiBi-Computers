// Zig の libc の malloc（BrkAllocator）の区画の計算（core/src/main/wasm/lua/kibi_brk.h。#784）が、
// 実際の malloc と合っていることを確かめるプログラム。Zig を上げて malloc の作りが変わったら、ここで落ちる。
//   - malloc が返す番地の前 KIBI_BRK_HEAD バイトが区画の先頭で、区画はその大きさ（大きな区画は 64 KiB）に揃っている
//   - realloc がその場で済む（同じ番地を返す）のは、kibi_brk_in_place が 1 のときだけ（区画の大きさ、大きな区画はページの数が同じとき）
//   - free は区画の最後の sizeof(size_t) バイトに空きリストの次（前に解放した区画の先頭）を書く
// 大きさは、区画の境目（2 のべき乗と 64 KiB のページの境目）の前後を選ぶ。合わなければ内容を出して 1 で終わる
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "kibi_brk.h"

size_t malloc_usable_size(void *ptr);

static size_t sizes[128];
static int count = 0;

static void add(size_t n) {
    if (count < (int)(sizeof(sizes) / sizeof(sizes[0]))) sizes[count++] = n;
}

static int failures = 0;

static void fail(const char *what, size_t a, size_t b) {
    if (failures++ < 20) printf("mismatch: %s %zu %zu\n", what, a, b);
}

int main(void) {
    add(0);
    add(1);
    // 小さい区画: actual = n + 20 が 2 のべき乗 S をちょうど満たす所と、1 つ越える所
    for (size_t s = 32; s <= KIBI_BRK_SMALL_MAX; s <<= 1) {
        add(s - 21);
        add(s - 20);
        add(s - 19);
    }
    // 大きな区画: k ページに収まるのは n <= k * 64 KiB - 24
    for (size_t k = 1; k <= 4; k <<= 1) {
        add(k * KIBI_BRK_BIGPAGE - 25);
        add(k * KIBI_BRK_BIGPAGE - 24);
        add(k * KIBI_BRK_BIGPAGE - 23);
    }
    add(3 * KIBI_BRK_BIGPAGE);

    // 区画の揃えと、free が書く所
    for (int i = 0; i < count; i++) {
        size_t n = sizes[i];
        size_t slot = kibi_brk_slot_bytes(n);
        size_t align = slot < KIBI_BRK_BIGPAGE ? slot : KIBI_BRK_BIGPAGE;
        unsigned char *a = malloc(n);
        unsigned char *b = malloc(n);
        if (a == NULL || b == NULL) {
            printf("malloc failed %zu\n", n);
            return 2;
        }
        if (malloc_usable_size(a) != n) fail("usable", n, malloc_usable_size(a));
        uintptr_t base_a = (uintptr_t)a - KIBI_BRK_HEAD;
        uintptr_t base_b = (uintptr_t)b - KIBI_BRK_HEAD;
        if (base_a % align != 0) fail("align", n, base_a);
        free(a);
        free(b);
        // b の区画の最後に、空きリストの次として a の区画の先頭が書かれる（読むのは free の後だが、ページは残っている）
        size_t next;
        memcpy(&next, (unsigned char *)base_b + slot - sizeof(size_t), sizeof(next));
        if (next != base_a) fail("free-node", n, next);
    }

    // realloc がその場で済むのは、kibi_brk_in_place が 1 のときだけ
    int checked = 0;
    for (int i = 0; i < count; i++) {
        for (int j = 0; j < count; j++) {
            size_t n = sizes[i], m = sizes[j];
            if (m == 0) continue;  // realloc(p, 0) は free する
            unsigned char *p = malloc(n);
            if (p == NULL) return 2;
            memset(p, 0x5a, n);
            unsigned char *q = realloc(p, m);
            if (q == NULL) return 2;
            int same = kibi_brk_in_place(n, m);
            if ((q == p) != same) fail(same ? "realloc-moved" : "realloc-stayed", n, m);
            size_t keep = n < m ? n : m;
            for (size_t k = 0; k < keep; k += 997) {
                if (q[k] != 0x5a) {
                    fail("realloc-copy", n, m);
                    break;
                }
            }
            free(q);
            checked++;
        }
    }
    if (failures != 0) return 1;
    printf("checked %d sizes %d pairs\n", count, checked);
    return 0;
}
