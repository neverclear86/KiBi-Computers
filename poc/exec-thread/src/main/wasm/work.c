// #27 実行スレッドの方式の比較に使う負荷（WASI なし）。zig cc で wasm32-freestanding にビルドする。
//
// 燃料の計測（#28）の代わりに、一定の計算ごとに `env.fuel(1)` を呼ぶ。ホストはここで燃料を減らし、
// 使い切ったら次の tick まで止める。ワールドの操作は `env.world_add` で、ホストのメインスレッドで行う。

#include <stdint.h>

__attribute__((import_module("env"), import_name("fuel"))) void fuel(int units);
__attribute__((import_module("env"), import_name("world_add"))) int world_add(int key, int delta);

static volatile uint32_t sink;

// 燃料 1 単位ぶんの計算（xorshift を 1000 回）
static uint32_t unit(uint32_t x) {
    for (int i = 0; i < 1000; i++) {
        x ^= x << 13;
        x ^= x >> 17;
        x ^= x << 5;
    }
    return x;
}

// units 単位ぶん計算する（燃料の較正と、tick の中で直接呼ぶ比較に使う）
uint32_t burn(int units) {
    uint32_t x = 2463534242u;
    for (int u = 0; u < units; u++) {
        x = unit(x);
        fuel(1);
    }
    sink = x;
    return x;
}

// 終わらない計算（CPU を使い続けるプログラム）
void run_forever(void) {
    uint32_t x = 2463534242u;
    for (;;) {
        x = unit(x);
        sink = x;
        fuel(1);
    }
}

// work 単位ぶん計算するたびに、ワールドを 1 回操作する。終わらない
void world_forever(int work) {
    uint32_t x = 2463534242u;
    for (int i = 0;; i++) {
        for (int u = 0; u < work; u++) {
            x = unit(x);
            fuel(1);
        }
        sink = x + (uint32_t)world_add(i & 15, 1);
    }
}

// 再帰の深さの上限を調べる（スレッドのスタックの大きさで決まる）
// （呼び出しの後で volatile を読み、最適化でループに変えられないようにする）
int recurse(int n) {
    volatile int depth = n;
    if (n == 0) return (int)sink;
    int r = recurse(n - 1);
    return r + (depth & 1);
}
