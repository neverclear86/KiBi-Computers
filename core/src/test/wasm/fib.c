// テスト用の WASM（WASI なし）。zig cc で wasm32-freestanding にビルドする（#9）

int fib(int n) {
    int a = 0;
    int b = 1;
    for (int i = 0; i < n; i++) {
        int next = a + b;
        a = b;
        b = next;
    }
    return a;
}
