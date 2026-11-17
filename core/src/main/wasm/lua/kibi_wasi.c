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
 * - 新しく数える区画があるときは、数えた後に残りを KIBI_LUA_KEEP バイト残す（足りなければ断る）。
 *   残した分は、包みを通らない書き込み（C のスタックが深くなる分、malloc 自身がチャンクの頭や top の頭を書く分）に使われる。
 * - もう数えた所（解放した穴）だけに収まる確保は commit が必ず通すので、その前に残りが KIBI_LUA_MIN 以上あるかを見る。
 *   上限に達した後も、穴を使い回して続けられるように、KEEP より小さくしている。
 * - dlmalloc は top から順に切り出すので、確保した所の後ろ KIBI_LUA_LOOKAHEAD バイトも、残りがあれば一緒に数えさせ、
 *   次の malloc が頭を書く所を先に数えておく（数えられなくても確保は通す）。
 * - 広げる realloc は、確保し直して写す（その場で広げると、数える前に書かれてしまい、取り消せない）。
 *   縮める realloc は新しい所を書かないので、そのまま realloc に渡す。
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
 * 2 区画（8 KiB。keep をかけない）、malloc がチャンクの頭と top の頭を書く 2 区画、C のスタックの伸び、ホストが
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
 * 解放しても数えた量は減らないので、上限に達した後に続けられるかは、穴の使い回しができるか次第（#370 の結果）。
 * 値は #370 の PoC のもの（KEEP 16 KiB、MIN 8 KiB、LOOKAHEAD 8 KiB）と、#729 で測ったもの（STACK_GUARD 16 KiB）。
 */
#undef realloc
#undef free
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "kibi_memory.h"

size_t malloc_usable_size(void *ptr);

#define KIBI_LUA_MIN (8 * 1024)
#define KIBI_LUA_LOOKAHEAD (8 * 1024)
/* dlmalloc のチャンクの頭（前）と、次のチャンクの頭（後ろ）の分 */
#define KIBI_LUA_HEAD 16
#define KIBI_LUA_TAIL 16
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
 * free が空きリストの次を書く所（#753）。Zig の libc の malloc（std.heap.BrkAllocator）は、確保を 2 のべき乗の大きさの区画
 * （malloc の頭 KIBI_LUA_HEAD バイトから始まる）に置き、free のときに区画の最後の size_t に空きリストの次を書く。
 * 4 KiB 以下の区画は 4 KiB の区画の中に収まり（大きさで揃えて置かれる）、確保した所と一緒に数えてある。
 * 64 KiB 以上の区画の最後は、memory.grow が先に数えた端か、前に free が書いた所。8〜32 KiB の区画だけ、最後が数えていない区画に
 * 残りうるので、その番地を返す（ほかは NULL）。数えないまま上限の近くで free すると、そこでトラップになる
 */
static unsigned char *free_node(void *result) {
    size_t actual = malloc_usable_size(result) + KIBI_LUA_HEAD + sizeof(size_t);
    if (actual <= 4096 || actual > 32 * 1024) return NULL;
    size_t slot = 8192;
    while (slot < actual) slot <<= 1;
    return (unsigned char *)result - KIBI_LUA_HEAD + slot - sizeof(size_t);
}

/* 確保を取り消す。free が書く所を数えられなければ、トラップにしないよう、解放せずに捨てる（その区画は使い回されない） */
static void discard(void *result) {
    unsigned char *node = free_node(result);
    if (node != NULL && !kibi_memory_commit(node, sizeof(size_t), 0)) return;
    free(result);
}

void *kibi_lua_realloc(void *ptr, size_t size) {
    size_t old = ptr != NULL ? malloc_usable_size(ptr) : 0;
    if (ptr != NULL && size <= old) return realloc(ptr, size);
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
    unsigned char *base = (unsigned char *)result - KIBI_LUA_HEAD;
    size_t len = malloc_usable_size(result) + KIBI_LUA_HEAD + KIBI_LUA_TAIL;
    if (!kibi_memory_commit(base, (uint32_t)len, KIBI_LUA_KEEP)) {
        free(result);
        return NULL;
    }
    (void)kibi_memory_commit(base + len, KIBI_LUA_LOOKAHEAD, KIBI_LUA_KEEP);
    if (ptr != NULL) {
        memcpy(result, ptr, old);
        free(ptr);
    }
    return result;
}

void kibi_lua_free(void *ptr) {
    free(ptr);
}
