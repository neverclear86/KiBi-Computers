#ifndef KIBI_BRK_H
#define KIBI_BRK_H
/*
 * Zig の libc の malloc（std.heap.BrkAllocator）が、確保を置く区画の大きさ（#784）。
 * kibi_wasi.c のアロケーターの包みが、realloc が区画を変えるか（写しと新しい区画への書き込みが起きるか）と、
 * free が空きリストの次を書く所を求めるのに使う。テスト（core/src/test/wasm/brk_slots_wasi.c）が、実際の malloc と合うかを確かめる。
 *
 * Zig 0.17 の次の所の写し。Zig を上げたら、ここと kibi_wasi.c の冒頭のコメントを見直す（gradle/zig.properties）。
 * - lib/c/malloc.zig: malloc(n) は、頭の KIBI_BRK_HEAD バイト（alignment_bytes = max(alignof(max_align_t), 8)。wasm32 では 16）を
 *   足した n + 16 バイトを BrkAllocator から取り、区画の先頭 + 16 を返す。Header（要求した大きさ。malloc_usable_size が返すもの）は、
 *   返した番地の前の 8 バイトに書く。realloc は、BrkAllocator の remap が通れば（区画が同じなら）その場で Header だけを書き直し、
 *   通らなければ新しい区画を取って min(新, 旧) バイトを写し、古い区画を free する。realloc(p, 0) は free して NULL を返す。
 * - lib/std/heap/BrkAllocator.zig: 長さ len の区画は actual = len + sizeof(usize)（空きリストの次の分）。
 *   actual が 32 KiB 以下なら 2 のべき乗の小さい区画（8〜32768 バイト。大きさごとの 64 KiB のページから順に切り出すので、
 *   区画はその大きさに揃う）。それより大きいと、64 KiB のページを (actual + 65536 + 3) / 65536 枚（を 2 のべき乗に切り上げた数）。
 *   resize・remap は、区画の大きさが変わらないときだけ通る。free は区画の最後の sizeof(usize) バイト（wasm32 では 4）に空きリストの次を書く。
 */
#include <stddef.h>

/* malloc が返す番地の前にある頭（区画の先頭からの距離） */
#define KIBI_BRK_HEAD 16
/* 小さい区画のいちばん大きいもの */
#define KIBI_BRK_SMALL_MAX (32 * 1024)
/* 大きな区画のページ */
#define KIBI_BRK_BIGPAGE (64 * 1024)

/* BrkAllocator の区画の長さ actual（len + sizeof(usize)）が要る 64 KiB のページの数を、2 のべき乗に切り上げたもの（bigPagesNeeded） */
static inline size_t kibi_brk_big_pages(size_t actual) {
    size_t pages = (actual + KIBI_BRK_BIGPAGE + sizeof(size_t) - 1) / KIBI_BRK_BIGPAGE;
    size_t pow2 = 1;
    while (pow2 < pages) pow2 <<= 1;
    return pow2;
}

/* 2 のべき乗に切り上げる（小さい区画。8 以上） */
static inline size_t kibi_brk_small_slot(size_t actual) {
    size_t slot = 8;
    while (slot < actual) slot <<= 1;
    return slot;
}

/*
 * malloc(n) が置かれる区画の大きさ（バイト）。free が空きリストの次を書く所（区画の先頭 + これ - sizeof(size_t)）も、
 * free のときの頭の大きさ（malloc_usable_size）からこれで決まる。n は今ある確保の大きさか、それより小さいもの（足して桁あふれしない）
 */
static inline size_t kibi_brk_slot_bytes(size_t n) {
    size_t actual = n + KIBI_BRK_HEAD + sizeof(size_t);
    if (actual <= KIBI_BRK_SMALL_MAX) return kibi_brk_small_slot(actual);
    return kibi_brk_big_pages(actual) * KIBI_BRK_BIGPAGE;
}

/*
 * 大きさ old の確保を realloc で new にするとき、BrkAllocator の remap（resize）が通り、その場で済む（頭を書き直すだけ）なら 1。
 * 小さい区画は、区画の大きさが同じときだけ。大きな区画は、ページの数（2 のべき乗）が同じときで、new が 32 KiB 以下でも
 * ページの数で比べる（その場で済んだ後は、頭の大きさから小さい区画として扱われる。free も kibi_brk_slot_bytes の所に書く）
 */
static inline int kibi_brk_in_place(size_t old, size_t new_size) {
    size_t old_actual = old + KIBI_BRK_HEAD + sizeof(size_t);
    size_t new_actual = new_size + KIBI_BRK_HEAD + sizeof(size_t);
    if (old_actual <= KIBI_BRK_SMALL_MAX) return kibi_brk_small_slot(new_actual) == kibi_brk_small_slot(old_actual);
    return kibi_brk_big_pages(new_actual) == kibi_brk_big_pages(old_actual);
}

#endif
