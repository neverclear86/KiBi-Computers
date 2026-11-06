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
 * 解放しても数えた量は減らないので、上限に達した後に続けられるかは、穴の使い回しができるか次第（#370 の結果）。
 * 値は #370 の PoC のもの（KEEP 16 KiB、MIN 8 KiB、LOOKAHEAD 8 KiB）。
 */
#undef realloc
#undef free
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "kibi_memory.h"

size_t malloc_usable_size(void *ptr);

#define KIBI_LUA_KEEP (16 * 1024)
#define KIBI_LUA_MIN (8 * 1024)
#define KIBI_LUA_LOOKAHEAD (8 * 1024)
/* dlmalloc のチャンクの頭（前）と、次のチャンクの頭（後ろ）の分 */
#define KIBI_LUA_HEAD 16
#define KIBI_LUA_TAIL 16

void *kibi_lua_realloc(void *ptr, size_t size) {
    size_t old = ptr != NULL ? malloc_usable_size(ptr) : 0;
    if (ptr != NULL && size <= old) return realloc(ptr, size);
    if (kibi_memory_remaining() < KIBI_LUA_MIN) return NULL;
    void *result = malloc(size);
    if (result == NULL) return NULL;
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
