// #26 Endive の性能測定用の CPU ベンチマーク（WASI なし）。zig cc で wasm32-freestanding にビルドする。
// どれも結果を返すので、測定側で正しく動いたかを確かめられる。

#include <stdint.h>

// 関数呼び出しが多い: 再帰のフィボナッチ
int fib(int n) {
    return n < 2 ? n : fib(n - 1) + fib(n - 2);
}

// メモリの読み書きが多い: エラトステネスのふるいで n 以下の素数を数える
#define SIEVE_MAX 4000000
static uint8_t composite[SIEVE_MAX + 1];

int sieve(int n) {
    if (n > SIEVE_MAX) n = SIEVE_MAX;
    for (int i = 0; i <= n; i++) composite[i] = 0;
    int count = 0;
    for (int i = 2; i <= n; i++) {
        if (composite[i]) continue;
        count++;
        for (int64_t j = (int64_t)i * i; j <= n; j += i) composite[j] = 1;
    }
    return count;
}

// 浮動小数点: n x n の行列積。結果の対角成分の和を返す
#define MAT_MAX 256
static double ma[MAT_MAX * MAT_MAX];
static double mb[MAT_MAX * MAT_MAX];
static double mc[MAT_MAX * MAT_MAX];

double matmul(int n) {
    if (n > MAT_MAX) n = MAT_MAX;
    for (int i = 0; i < n; i++) {
        for (int j = 0; j < n; j++) {
            ma[i * n + j] = (double)(i + j) / n;
            mb[i * n + j] = (double)(i - j) / n;
        }
    }
    for (int i = 0; i < n; i++) {
        for (int j = 0; j < n; j++) {
            double s = 0;
            for (int k = 0; k < n; k++) s += ma[i * n + k] * mb[k * n + j];
            mc[i * n + j] = s;
        }
    }
    double trace = 0;
    for (int i = 0; i < n; i++) trace += mc[i * n + i];
    return trace;
}

// 整数のビット演算: 擬似乱数で埋めた len バイトの CRC32 を rounds 回計算する
#define CRC_MAX (1 << 20)
static uint8_t crc_buf[CRC_MAX];

uint32_t crc32(int len, int rounds) {
    if (len > CRC_MAX) len = CRC_MAX;
    uint32_t x = 2463534242u;
    for (int i = 0; i < len; i++) {
        x ^= x << 13;
        x ^= x >> 17;
        x ^= x << 5;
        crc_buf[i] = (uint8_t)x;
    }
    uint32_t crc = 0;
    for (int r = 0; r < rounds; r++) {
        crc = 0xFFFFFFFFu;
        for (int i = 0; i < len; i++) {
            crc ^= crc_buf[i];
            for (int b = 0; b < 8; b++) crc = (crc >> 1) ^ (0xEDB88320u & -(crc & 1u));
        }
        crc = ~crc;
    }
    return crc;
}

// 終わらないループ（割り込みで止められるかを確かめる）。volatile で最適化で消えないようにする
static volatile int spin_counter;

void spin(void) {
    for (;;) spin_counter++;
}
