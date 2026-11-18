/*
 * Lua を WASI（wasm32-wasi）向けにビルドするための補い（#91）。
 */
#include <errno.h>
#include <stdio.h>

/* io.tmpfile: wasi-libc に tmpfile がないので、失敗を返す */
FILE *tmpfile(void) {
    errno = ENOTSUP;
    return NULL;
}

/*
 * Lua のエラー処理は setjmp/longjmp を使う。WASM では例外処理（exnref）で実装され
 * （-mllvm -wasm-enable-sjlj）、wasi-libc の __wasm_longjmp は __c_longjmp という
 * タグを投げる。そのタグの定義が wasi-libc にないので、ここで定義する。
 */
__asm__(
    ".globl __c_longjmp\n"
    ".tagtype __c_longjmp i32\n"
    "__c_longjmp:\n");

/*
 * RAM の上限で NULL を返すアロケーターの包み（#409。kibi_wasi.h のマクロで realloc・free を差し替える）。
 *
 * カーネルは書き換えた 4 KiB の区画を数え、上限を超える書き込みはトラップにする（#407）。
 * memory.grow は数えない（増やした所の最初と最後の区画だけを先に数える）ので、malloc は上限の近くでも成功する。
 * そこで、確保した所を書く前に kibi:computer/memory の commit で数えさせ、数えられなければ確保を取り消して NULL を返す。
 *
 * malloc は Zig の libc のもの（std.heap.BrkAllocator を包んだもの。dlmalloc ではない。#784）。振る舞いの写しと出どころは kibi_brk.h。
 * - 確保を 2 のべき乗の大きさの区画に置く（32 KiB 以下は大きさごとの 64 KiB のページから順に切り出し、それより大きいと
 *   64 KiB のページを 2 のべき乗の枚数）。空いた区画（大きさごとの空きリスト）を先に使い回し、なければ新しいページを memory.grow で取る。
 * - malloc は区画の先頭 KIBI_BRK_HEAD（16）バイトの後ろを返し、その前の 8 バイトに頭（要求した大きさ）を書く。確保した所の後ろには書かない。
 * - free は区画の最後の 4 バイト（sizeof(size_t)）に空きリストの次を書く（free_node）。
 * - realloc は、区画の大きさ（大きな区画はページの数）が変わらないときだけその場で頭を書き直す。変わると、縮めでも新しい区画を取って写し、古い区画を free する。
 *
 * - 新しく数える区画があるときは、数えた後に残りを KIBI_LUA_KEEP バイト残す（足りなければ断る）。
 *   残した分は、包みを通らない書き込み（C のスタックが深くなる分、ホストがスタックや静的な変数に書く結果など）に使われる。
 * - もう数えた所（解放した穴）だけに収まる確保は commit が必ず通すので、その前に残りが KIBI_LUA_MIN 以上あるかを見る。
 *   上限に達した後も、穴を使い回して続けられるように、KEEP より小さくしている。malloc が commit の前に書く頭（1 区画）も、この分で受ける。
 * - 広げる realloc は、確保し直して写す（その場で広げると、数える前に書かれてしまい、取り消せない）。
 * - 縮める realloc は、その場で済むなら（kibi_brk_in_place）そのまま realloc に渡す（頭を書き直すだけで、新しい所を書かない）。
 *   区画が変わるなら、広げるときと同じく確保し直して数えてから写す（#784。BrkAllocator に任せると、新しい区画の頭と写しを数えないまま書く）。
 *   確保できなければ、NULL ではなく元のブロックをそのまま返す（要求より大きいので realloc として正しく、新しい所を書かない。
 *   NULL を返すと、パーサーのベクターの縮め（luaM_shrinkvector）や表の配列の縮めが not enough memory になる）。
 *   頭は元の大きさのままなので、以後の free・realloc もその区画で正しく扱われる。
 *
 * - 残りが KIBI_LUA_KEEP + KIBI_LUA_STACK_GUARD より少なくなったら、確保の前に、今のスタックポインターから下
 *   KIBI_LUA_STACK_GUARD バイトの C のスタックを数えさせる（#729）。数えた後に KIBI_LUA_MIN を残せなければ NULL を返す
 *   （commit が断られるので、プロセスが終わったときの「メモリ不足だった」の注記も出る）。
 *   C のスタックの書き込みは包みを通らないので、上限の近くで初めてスタックが深くなる（pcall の中の pullEventRaw → take →
 *   lua_pcall の入れ子、緊急の GC など）と、そのままトラップになっていた。数えた区画は最高値として残るので、
 *   一度数えた深さ（stack_counted_low）より浅いところでは、もう import を呼ばない。普段の道（残りが多いとき）では何もしない。
 *   スタックを数えられないとき（数えていない区画があり、数えた後に KIBI_LUA_MIN を残せない。残りが約 22 KiB 以下）は、
 *   解放した穴に収まる確保も含めて NULL を返す。穴だけで続けさせると、守れていないスタックが深くなったときにトラップになるため、
 *   続けられることより、CRASHED にしないことを選んだ（ADR 0094）。残りが一度にその線より下まで落ちると（大きな確保、
 *   memory.grow の端の 2 区画、同じマシンのほかのプロセスや /tmp）、その後は、ほかが RAM を返すまで何も確保できない。
 *
 * KEEP・MIN・STACK_GUARD は「包みの外で起きうる書き込み」の見積もり。内訳は、memory.grow が増やした所の両端に先に数える
 * 2 区画（8 KiB。keep をかけない）、malloc が commit の前に書く頭、C のスタックの伸び、ホストが
 * スタックや静的な変数に書く結果（poll_oneoff の events、take の結果を受ける構造体など）。
 * memory.grow は、増やしたページ数の分の区画の表（1 ページ 128 バイト。#753）も、端の区画と一緒に keep なしで数える。
 * 大きな確保 1 回では、これが数十 KiB（アドレス空間の上限の 1,024 ページなら 128 KiB）になり、残りが確保の前の線を見た後に
 * 一度に落ちうる。そこで、malloc が実際に memory.grow したとき（線形メモリのページ数が増えたとき）だけ、malloc の後で
 * 残りを見直し、スタックを数える線（KEEP + STACK_GUARD）を割っていれば、その場で C のスタックを数えさせる。数えられないか、
 * 数えた後に MIN を残せなければ、確保を取り消して NULL を返す。grow しない確保（解放した穴や、もう増やしたページの空きに収まるもの）は、
 * 大きさにかかわらず今までの線（MIN）のままで、穴を使い回せる。grow の間に書かれるのは malloc の内側の浅いフレーム
 * （確保の前に書いた所）と、grow が先に数える両端の区画だけなので、見直しが malloc の後でも間に合う。
 * 失敗した大きな確保が grow を通したときは、その表の分が（ページは縮まないので）プロセスの終わりまで残る。
 * 同じ所は再び grow しないので増えず、全体でもアドレス空間の分（最大 128 KiB）で頭打ちになる。
 * マシンの RAM は同じマシンのほかのプロセスや /tmp と共有するので、remaining() はその時点の値でしかなく、
 * この見積もりが守れるのは、その台で RAM を食っているのが自分だけのとき（努力目標。規則はトラップ。#370）。
 *
 * - 確保した所の後ろ KIBI_LUA_LOOKAHEAD バイトも、残りがあれば keep をかけて一緒に数えさせる（数えられなくても確保は通す）。
 *   小さい区画は大きさごとのページから順に切り出すので、同じ大きさの次の確保（Lua では同じ大きさの文字列や表が続きやすい）は、
 *   区画の直後に頭を書く。その頭は commit の前に書かれ、keep をかけずに数えられるので、先に数えておかないと、残りが KEEP を割っても
 *   commit が一度も断られず（頭の区画はもう数えてあり、新しく数える所がない）、MIN の線で黙って NULL になる。先に数えておけば、
 *   上限の近くではこの commit が断られて、プロセスが終わったときの「メモリ不足だった」の注記も出る。
 *   ただし数えるのは確保した所の終わり（base + len）からで、次の頭は区画の終わり（base + 区画の大きさ）にあるので、効くのは
 *   その差が LOOKAHEAD に収まるときだけ: 8 KiB 以下の区画では常に、16 KiB の区画では actual（kibi_brk.h）が 8,212 バイト以上のとき
 *   （ほぼ常に）、32 KiB の区画では actual が 24,596 バイト以上のとき（数える単位は 4 KiB の区画なので、境目は少し前後する）。それより埋まりが少ない 32 KiB の区画や、64 KiB 以上の
 *   大きな区画（次の確保が直後に来るとは限らない）では、次の頭は数えた範囲の外になる。そのときも頭の書き込みは keep なしで数えられて
 *   MIN で受けるのでトラップにはならないが、注記は出ないことがある。
 *   dlmalloc を前提にしていたころの、確保した所の後ろの余分（TAIL 16 バイト。次のチャンクの頭）は、#784 で外した
 *   （BrkAllocator は確保した所の後ろに頭を書かない。直後の区画の頭は LOOKAHEAD に含まれる）。
 *
 * 解放しても数えた量は減らないので、上限に達した後に続けられるかは、穴の使い回しができるか次第（#370 の結果）。
 * 値は #370 の PoC のもの（KEEP 16 KiB、MIN 8 KiB、LOOKAHEAD 8 KiB）と、#729 で測ったもの（STACK_GUARD 16 KiB）。
 */
#undef realloc
#undef free
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "kibi_brk.h"
#include "kibi_memory.h"

size_t malloc_usable_size(void *ptr);

#define KIBI_LUA_MIN (8 * 1024)
#define KIBI_LUA_LOOKAHEAD (8 * 1024)
/* KIBI_LUA_KEEP・KIBI_LUA_STACK_GUARD は kibi_wasi.h（kibi_lua_canon.c の reserve の余裕と合わせるため） */

/* リンカーが出すスタックの下端（wasm-ld。スタックは線形メモリの先頭に置かれ、下へ伸びる） */
extern unsigned char __stack_low;

/* 数えさせた C のスタックのいちばん低い番地（まだなら 0。スタックの上は実際に書いたので数えてある） */
static uintptr_t stack_counted_low = 0;

/* 今のスタックポインターから下 KIBI_LUA_STACK_GUARD バイトを数えさせる（後に KIBI_LUA_MIN を残す）。数えてあれば import を呼ばない。数えられれば 1 */
static int count_stack(void) {
    volatile unsigned char here = 0;
    uintptr_t sp = (uintptr_t)&here;
    uintptr_t floor = (uintptr_t)&__stack_low;
    uintptr_t low = sp - floor > KIBI_LUA_STACK_GUARD ? sp - KIBI_LUA_STACK_GUARD : floor;
    if (stack_counted_low != 0 && low >= stack_counted_low) return 1;
    uintptr_t high = stack_counted_low != 0 && stack_counted_low < sp ? stack_counted_low : sp;
    if (high > low && !kibi_memory_commit((void *)low, (uint32_t)(high - low), KIBI_LUA_MIN)) return 0;
    stack_counted_low = low;
    return 1;
}

/*
 * free が空きリストの次を書く所（#753。kibi_brk.h）。区画はその大きさに揃えて置かれるので、4 KiB 以下の区画は 4 KiB の区画の中に収まり、
 * 確保した所と一緒に数えてある。64 KiB 以上の区画の最後は、memory.grow が先に数えた端か、前に free が書いた所。
 * 8〜32 KiB の区画だけ、最後が数えていない区画に残りうるので、その番地を返す（ほかは NULL）。数えないまま上限の近くで free すると、そこでトラップになる
 */
static unsigned char *free_node(void *result) {
    size_t slot = kibi_brk_slot_bytes(malloc_usable_size(result));
    if (slot <= 4096 || slot > KIBI_BRK_SMALL_MAX) return NULL;
    return (unsigned char *)result - KIBI_BRK_HEAD + slot - sizeof(size_t);
}

/* 確保を取り消す。free が書く所を数えられなければ、トラップにしないよう、解放せずに捨てる（その区画は使い回されない） */
static void discard(void *result) {
    unsigned char *node = free_node(result);
    if (node != NULL && !kibi_memory_commit(node, sizeof(size_t), 0)) return;
    free(result);
}

/* size バイトを確保し、書く所（頭から確保した所の終わりまでと、free が書く所）を数えさせる。数えられなければ取り消して NULL */
static void *counted_alloc(size_t size) {
    uint64_t remaining = kibi_memory_remaining();
    if (remaining < KIBI_LUA_KEEP + KIBI_LUA_STACK_GUARD) {
        if (!count_stack()) return NULL;
        remaining = kibi_memory_remaining();
    }
    if (remaining < KIBI_LUA_MIN) return NULL;
    size_t pages = __builtin_wasm_memory_size(0);
    void *result = malloc(size);
    if (result == NULL) return NULL;
    /* memory.grow は区画の表（#753）と両端の区画を keep なしで数える。残りが線を割ったら、ここでスタックを数え直す */
    if (__builtin_wasm_memory_size(0) != pages && kibi_memory_remaining() < KIBI_LUA_KEEP + KIBI_LUA_STACK_GUARD) {
        if (!count_stack() || kibi_memory_remaining() < KIBI_LUA_MIN) {
            discard(result);
            return NULL;
        }
    }
    unsigned char *node = free_node(result);
    if (node != NULL && !kibi_memory_commit(node, sizeof(size_t), KIBI_LUA_KEEP)) {
        discard(result);
        return NULL;
    }
    unsigned char *base = (unsigned char *)result - KIBI_BRK_HEAD;
    size_t len = malloc_usable_size(result) + KIBI_BRK_HEAD;
    if (!kibi_memory_commit(base, (uint32_t)len, KIBI_LUA_KEEP)) {
        discard(result);
        return NULL;
    }
    /* 確保した所の後ろを、keep をかけて先に数えておく（同じ大きさの次の確保が頭を書く所を含むのは、冒頭のコメントの区画だけ。数えられなくても確保は通す） */
    (void)kibi_memory_commit(base + len, KIBI_LUA_LOOKAHEAD, KIBI_LUA_KEEP);
    return result;
}

void *kibi_lua_realloc(void *ptr, size_t size) {
    if (ptr == NULL) return counted_alloc(size);
    if (size == 0) {
        free(ptr);
        return NULL;
    }
    size_t old = malloc_usable_size(ptr);
    if (size <= old) {
        /* その場で済む縮めは、BrkAllocator が頭（数えた区画の中）を書き直すだけ */
        if (kibi_brk_in_place(old, size)) return realloc(ptr, size);
        void *result = counted_alloc(size);
        /* 新しい区画を数えられなければ、元のブロック（size 以上ある）をそのまま使わせる */
        if (result == NULL) return ptr;
        memcpy(result, ptr, size);
        free(ptr);
        return result;
    }
    void *result = counted_alloc(size);
    if (result == NULL) return NULL;
    memcpy(result, ptr, old);
    free(ptr);
    return result;
}

void kibi_lua_free(void *ptr) {
    free(ptr);
}
