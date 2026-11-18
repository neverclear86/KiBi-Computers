// 0・1 以外の終了コードで終わる WASI のプログラム（#775）。p2 の wasi:cli/exit は ok・err だけなので、どう写るかを確かめる
#include <stdio.h>

int main(void) {
    printf("exit 3\n");
    return 3;
}
