// テスト用の WASM（WASI p1）。wasi-libc をリンクできることを確かめる（#9）
#include <stdio.h>

int main(void) {
    printf("Hello from WASI\n");
    return 0;
}
