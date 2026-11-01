/*
 * 起動時に、wasi-libc の cwd をカーネルの cwd に合わせる（#332、ADR 0332）。
 *
 * WASI p1 にはカーネルの cwd を読む関数がなく、wasi-libc は cwd を自分の中だけで持つ（初めは "/"）。
 * カーネルは p1 の環境変数に PWD=<カーネルの cwd> を必ず含めるので、main の前にそこへ chdir する。
 * 失敗したとき（cwd が消された、見せている preopen の外にある、など）は何もせず、cwd は "/" のまま。
 *
 * C で書く WASI p1 のプログラムにリンクする（lua.wasm など）。SDK（#114・#115・#116）も同じ規約に従う。
 */
#include <errno.h>
#include <stdlib.h>
#include <unistd.h>

__attribute__((constructor)) static void kibi_cwd_from_pwd(void) {
    int saved = errno;
    const char *pwd = getenv("PWD");
    if (pwd != NULL && pwd[0] == '/') {
        (void)chdir(pwd);
    }
    errno = saved;
}
