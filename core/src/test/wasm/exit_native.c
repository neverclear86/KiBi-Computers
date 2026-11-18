// main から戻らずに exit() で終わる WASI のプログラム（#775）。wasi-libc が wasi:cli/exit を直接呼ぶ経路を確かめる。
// 引数の数値を終了コードにする（なければ 0）
#include <stdio.h>
#include <stdlib.h>

int main(int argc, char **argv) {
    int code = argc > 1 ? atoi(argv[1]) : 0;
    printf("exit(%d)\n", code);
    fflush(stdout);
    exit(code);
}
