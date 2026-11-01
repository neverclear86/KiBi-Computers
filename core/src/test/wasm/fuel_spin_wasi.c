// 燃料切れの待ち点化（#339）を確かめる、計算を続ける CLI。
//
// fuel-spin <mode> <rounds> <inner>
//   mode = main: main の中のループで計算する（main は printf → fd_write を呼ぶので止まりうる関数。燃料切れを巻き戻せる）
//   mode = leaf: 止まりうる関数を呼ばない関数（葉）の中のループで計算する（燃料切れはスレッドを持ったまま待つ）
// 1 ラウンドごとに、ラウンドの番号と計算の結果を出す。
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

__attribute__((noinline)) static uint32_t leaf_spin(uint32_t n, uint32_t x) {
    for (uint32_t i = 0; i < n; i++) x = x * 1103515245u + 12345u + (x >> 7);
    return x;
}

int main(int argc, char **argv) {
    const char *mode = argc > 1 ? argv[1] : "main";
    int rounds = argc > 2 ? atoi(argv[2]) : 10;
    uint32_t inner = argc > 3 ? (uint32_t)strtoul(argv[3], NULL, 10) : 10000;
    uint32_t x = 1;
    for (int r = 0; r < rounds; r++) {
        if (mode[0] == 'l') {
            x = leaf_spin(inner, x);
        } else {
            // volatile で、このループを main の中に残す（関数に括り出されない・畳まれない）
            volatile uint32_t n = inner;
            for (uint32_t i = 0; i < n; i++) x = x * 1103515245u + 12345u + (x >> 7);
        }
        printf("%d %u\n", r, (unsigned)x);
    }
    return 0;
}
