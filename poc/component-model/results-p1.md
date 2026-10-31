# p1 モジュールの扱いの測定結果（#261）

`./gradlew -p poc/component-model :bench:runP1Adapter` の出力（Java 17、1回）。コードは `bench/src/main/kotlin/pro/ikili/kibicomputers/poc/cm/P1Adapter.kt`。

- A: `lua.wasm`（#91、zig cc の WASI p1）を Endive の `WasiPreview1`（p1 のホスト関数）で直接動かす
- B: `lua.wasm` を公式の `wasi_snapshot_preview1` アダプタ（Wasmtime v49.0.4 のリリースの `wasi_snapshot_preview1.command.wasm`、SHA-256 を固定）で `wasm-tools component new --adapt` してコンポーネントにし、endive-cm の `WasiPreview2`（p2 のホスト）で動かす
- 包むのには Endive が WASM にした wasm-tools（`run.endive:wasm-tools`）を使った。B の p2 の層は endive-cm だが、#260 で自前に決めたので、B の数字は「アダプタを通すと何が増えるか」の目安として読む
- どちらもコアモジュールはランタイムコンパイラ（#29）で動かし、コンパイルは1回だけ（結果を使い回す）。3 は毎回インスタンスを作って `lua -` を最後まで動かした時間で、`syscalls.lua` はバッファなしの1文字の書き込みと `os.time()` を 2000 回ずつ呼ぶもの
- 4 の呼び出し数は A の p1 のホスト関数を数えたもの。B では同じ数の p1 呼び出しがアダプタの中で p2 の呼び出し（Canonical ABI の lower、resource のハンドル）に変わる
- 6 は、#73 の「p1 向けに同じ機能を core の import でも出す」形のモジュールを包めるか。最小の WAT で確かめた
- 環境: Windows 10 / AMD Ryzen 9 3950X / Amazon Corretto 17.0.12。3 の Lua の計算だけのスクリプトは ±10% ほど揺れる

```text
java.version=17.0.12
lua.wasm 306.4 KiB / アダプタ wasi_snapshot_preview1.command-49.0.4.wasm 50.6 KiB
== 1. 包む（wasm-tools component new --adapt）
component new: 1回目 1710 ms、2・3回目 340 / 292 ms
コンポーネント 344.2 KiB（lua.wasm より +37.8 KiB）
validate: 1回目 239 ms、2・3回目 165 / 140 ms
コンポーネントのパース（中央値）: 90.4 ms
比較: lua.wasm のパース（中央値）: 71.4 ms
ランタイムコンパイル: A（lua.wasm）553 ms / B（コンポーネント内のコアモジュール 4 個、初回の実行を含む）849 ms
B のコアモジュール: 関数 839, 関数 0, 関数 43, 関数 53
== 2. インスタンス生成（_start を呼ばない。中央値）
A: 0.528 ms / B: 2.424 ms（4.6 倍）
== 3. 実行時間（毎回インスタンスを作って lua - を最後まで実行。中央値）
| スクリプト | A: p1 を直接 [ms] | B: アダプタ + p2 [ms] | B / A |
| --- | ---: | ---: | ---: |
| （空のスクリプト） | 3.13 | 4.66 | 1.49 |
| fib.lua | 186.96 | 206.57 | 1.10 |
| tables.lua | 308.62 | 301.19 | 0.98 |
| strings.lua | 292.21 | 287.32 | 0.98 |
| syscalls.lua | 10.72 | 18.93 | 1.77 |
== 4. ホスト呼び出しの数（A の p1 呼び出し）
（空のスクリプト）: 合計 8 回 clock_time_get=3, environ_sizes_get=1, args_sizes_get=1, fd_prestat_get=1
fib.lua: 合計 11 回 clock_time_get=3, fd_read=2, environ_sizes_get=1, args_sizes_get=1
tables.lua: 合計 11 回 clock_time_get=3, fd_read=2, environ_sizes_get=1, args_sizes_get=1
strings.lua: 合計 11 回 clock_time_get=3, fd_read=2, environ_sizes_get=1, args_sizes_get=1
syscalls.lua: 合計 4012 回 clock_time_get=2003, fd_write=2003, fd_read=2, environ_sizes_get=1
== 5. 状態（実行後）
A: コアインスタンス 1 個、線形メモリ 24 ページ、グローバル 1 個（ほかはホストの fd 表）
B: コアインスタンス 4 個、線形メモリ 1 個（26 ページ）、グローバル [0, 1, 3, 0]、テーブル 3 個を持つ
== 6. p1 の独自 import（#73）を持つモジュールを包む
基準（wasi_snapshot_preview1 だけ）: 包めた（6988 バイト）
p1 の独自モジュール名（kibi_computer.beep）: 包めない ← error: failed to encode a component from module / 0: failed to decode world from module / 1: module was not valid / 2: failed to resolve import `kibi_computer::beep` / 3: module requires an import interface named `kibi_computer`
WIT のインターフェース名（kibi:computer/beep@0.1.0）、WIT の埋め込みなし: 包めない ← error: failed to encode a component from module / 0: failed to decode world from module / 1: module was not valid / 2: failed to resolve import `kibi:computer/beep@0.1.0::beep` / 3: module requires an import interface named `kibi:computer/beep@0.1.0`
WIT のインターフェース名、WIT を埋め込む（component embed）: 包めた（7313 バイト）
```
