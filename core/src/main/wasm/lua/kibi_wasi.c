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
