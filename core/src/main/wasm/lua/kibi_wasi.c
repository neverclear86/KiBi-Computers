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

#ifdef KIBI_RAM_POC
/*
 * #370 の PoC: RAM が足りないときに NULL を返すアロケーターの包み（kibi_wasi.h）。
 *
 * カーネルは書き換えた 4 KiB を数え、上限を超える書き込みはトラップにする。包みは、確保した所を書く前に
 * kibi_poc.ram_commit で数えさせ、数えられなければ確保を取り消して NULL を返す。Lua は緊急の GC をしてやり直し、
 * それでもだめなら "not enough memory" のエラーにする。解放した穴（もう数えた所）に収まる確保は、上限の近くでも通る。
 *
 * - 新しく数える所があるときは、数えた後に残りを KIBI_POC_KEEP バイト残す（足りなければ断る）。
 *   もう書いた所（解放した穴）だけに収まるなら、残りが KIBI_POC_MIN 以上あれば通す（not enough memory の後も続けられるように）。残した分は、包みを通らない書き込み
 *   （C のスタックが深くなる分、malloc 自身がチャンクの頭や dlmalloc の top の頭を書く分）に使われる。
 * - dlmalloc は top から順に切り出すので、確保した所の後ろ KIBI_POC_LOOKAHEAD バイトも、残りがあれば一緒に数えさせ、
 *   次の malloc が頭を書く所を先に数えておく（数えられなくても確保は通す）。
 * - 広げる realloc は、確保し直して写す（その場で広げると、数える前に書かれてしまい、取り消せない）。
 *
 * 試した別の形（#370 のコメント）: 残りの量だけを見て「生きているバイト数の最高値を超える分」や「確保の大きさ」と比べる形は、
 * dlmalloc が確保する場所が飛び飛び（memory.grow の単位、大きな realloc の穴）なので、断る前にトラップになることがあった。
 */
#undef realloc
#undef free
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
size_t malloc_usable_size(void *ptr);

__attribute__((import_module("kibi_poc"), import_name("ram_remaining")))
long long kibi_poc_ram_remaining(void);

__attribute__((import_module("kibi_poc"), import_name("ram_commit")))
int kibi_poc_ram_commit(void *ptr, size_t len, long long keep);

#ifndef KIBI_POC_LOOKAHEAD
#define KIBI_POC_LOOKAHEAD (8 * 1024)
#endif
#ifndef KIBI_POC_KEEP
#define KIBI_POC_KEEP (16 * 1024)
#endif
#ifndef KIBI_POC_MIN
#define KIBI_POC_MIN (8 * 1024)
#endif

void *kibi_poc_realloc(void *ptr, size_t size) {
    size_t old = ptr != NULL ? malloc_usable_size(ptr) : 0;
    /* 縮める（その場で切り詰める）ときは、新しい所を書かない */
    if (ptr != NULL && size <= old) return realloc(ptr, size);
    /* もう書いた所に収まる確保でも、malloc 自身が新しい所に頭を書くことがあるので、残りが KIBI_POC_MIN より少なければ断る */
    if (kibi_poc_ram_remaining() < KIBI_POC_MIN) return NULL;
    void *result = malloc(size);
    if (result == NULL) return NULL;
    unsigned char *base = (unsigned char *)result - 16;
    size_t len = malloc_usable_size(result) + 32;
    if (!kibi_poc_ram_commit(base, len, KIBI_POC_KEEP)) {
        free(result);
        return NULL;
    }
    (void)kibi_poc_ram_commit(base + len, KIBI_POC_LOOKAHEAD, KIBI_POC_KEEP);
    if (ptr != NULL) {
        memcpy(result, ptr, old);
        free(ptr);
    }
    return result;
}

void kibi_poc_free(void *ptr) {
    free(ptr);
}
#endif
