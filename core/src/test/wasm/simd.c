// SIMD（v128）を含むモジュールを、その関数だけインタプリタに回してコンパイルするテスト用の WASM（#424）。
// wasm32-freestanding に -msimd128 でビルドする。sum・fill_seq は自動ベクトル化で v128 の命令を出し、fib は出さない

#include <wasm_simd128.h>

static int buf[1 << 16];

// 配列を 0, 1, 2, ... で埋める（v128.store を出す）
__attribute__((noinline)) void fill_seq(int n) {
    for (int i = 0; i < n; i++) buf[i] = i;
}

// 配列の和（i32x4.add などを出す）
__attribute__((noinline)) int sum(int n) {
    int s = 0;
    for (int i = 0; i < n; i++) s += buf[i];
    return s;
}

// SIMD の命令を明示して使う（i32x4 の掛け算と、レーンの取り出し）
__attribute__((noinline)) int dot4(int a, int b) {
    v128_t x = wasm_i32x4_make(a, a + 1, a + 2, a + 3);
    v128_t y = wasm_i32x4_splat(b);
    v128_t p = wasm_i32x4_mul(x, y);
    return wasm_i32x4_extract_lane(p, 0) + wasm_i32x4_extract_lane(p, 1) + wasm_i32x4_extract_lane(p, 2) + wasm_i32x4_extract_lane(p, 3);
}

// SIMD を使わない関数（コンパイルしたコードで動く）
__attribute__((noinline)) int fib(int n) {
    return n < 2 ? n : fib(n - 1) + fib(n - 2);
}

// SIMD を使う関数と使わない関数を、コンパイルしたコードから呼ぶ
int run(int n) {
    fill_seq(n);
    return sum(n) + dot4(n, 3) + fib(10);
}
