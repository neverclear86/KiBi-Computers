// 燃料の計測（#38）のテスト用の WASM（WASI なし）。zig cc で wasm32-freestanding にビルドする。
// 一括メモリ命令（memory.fill・memory.copy）と末尾呼び出し（return_call）を出すため、-mbulk-memory と -mtail-call を付ける

static unsigned char buf[1 << 20];

int fib(int n) {
    return n < 2 ? n : fib(n - 1) + fib(n - 2);
}

// 終わらないループ（燃料切れで止める）
void forever(void) {
    volatile int x = 0;
    for (;;) x++;
}

// memory.fill
int fill(int n) {
    __builtin_memset(buf, 7, (unsigned)n);
    return n > 0 ? buf[n - 1] : -1;
}

// memory.copy
int copy(int n) {
    __builtin_memcpy(buf + (1 << 19), buf, (unsigned)n);
    return n > 0 ? buf[(1 << 19) + n - 1] : -1;
}

// memory.grow
int grow(int pages) {
    return (int)__builtin_wasm_memory_grow(0, (unsigned)pages);
}

// return_call（深い相互再帰。末尾呼び出しでなければスタックが溢れる）
__attribute__((noinline)) static int odd(int n);

__attribute__((noinline)) static int even(int n) {
    if (n == 0) return 1;
    __attribute__((musttail)) return odd(n - 1);
}

__attribute__((noinline)) static int odd(int n) {
    if (n == 0) return 0;
    __attribute__((musttail)) return even(n - 1);
}

int is_even(int n) {
    return even(n);
}
