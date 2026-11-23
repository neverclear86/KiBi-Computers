// スタックを使い切るまで再帰する WASI p1 の CLI（#858）。待機点型の本番の燃料切れの道を、尽きる位置をずらして通す。
//
// stack-deep <mode> <pad>
//   mode = a: 再帰する関数が（実行時は呼ばない）usleep → poll_oneoff を呼ぶので、待機点への変換で止まりうる関数になる
//             （燃料切れは kibi.async_fuel → onFuelExhaustedUnwindable。巻き戻せるときは巻き戻して燃料を待つ）
//   mode = l: 再帰する関数は葉（止まりうる関数を呼ばない）。燃料切れは kibi.fuel → 前借り（#477）か、スレッドを持って待つ
//   pad: 再帰の前に、形の違う関数で pad 段積む（尽きる位置をずらす）
// どちらも 1 段ごとに少し計算し（燃料切れの処理を深いところで呼ばせる）、clock_gettime（ホストの関数）を呼ぶ。
#include <stdint.h>
#include <stdlib.h>
#include <time.h>
#include <unistd.h>

// 実行時は変わらない。コンパイラが再帰や呼び出しを畳まないように volatile にする
static volatile int never = 0;
static volatile uint32_t limit = 0xFFFFFFFFu;

__attribute__((noinline)) static uint32_t spin(uint32_t x) {
    for (int i = 0; i < 64; i++) x = x * 1103515245u + 12345u + (x >> 7);
    return x;
}

__attribute__((noinline)) static uint32_t now(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint32_t)ts.tv_nsec;
}

__attribute__((noinline)) static uint32_t rec_leaf(uint32_t n, uint32_t x) {
    if (n == limit) return x;
    x = spin(x + n) ^ now();
    // 末尾呼び出し・ループにしない（LLVM は「戻ってから足す」再帰もループに直すので、呼び出しをまたいで生きる volatile の値を読む）
    volatile uint32_t keep[2] = {x, n};
    uint32_t r = rec_leaf(n + 1, x);
    return r - keep[0] + keep[1];
}

__attribute__((noinline)) static uint32_t rec_async(uint32_t n, uint32_t x) {
    if (n == limit) return x;
    x = spin(x + n) ^ now();
    if (never) usleep(1);
    volatile uint32_t keep[2] = {x, n};
    uint32_t r = rec_async(n + 1, x);
    return r - keep[0] + keep[1];
}

__attribute__((noinline)) static uint32_t pad_fn(int pad, char mode, uint32_t a, uint32_t b, uint32_t c) {
    if (pad <= 0) return mode == 'a' ? rec_async(0, a ^ b ^ c) : rec_leaf(0, a ^ b ^ c);
    volatile uint32_t keep = a;
    uint32_t r = pad_fn(pad - 1, mode, b + 1, c, a);
    return r + keep;
}

int main(int argc, char **argv) {
    char mode = argc > 1 ? argv[1][0] : 'l';
    int pad = argc > 2 ? atoi(argv[2]) : 0;
    return (int)(pad_fn(pad, mode, 1, 2, 3) & 1);
}
