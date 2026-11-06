# 標準 OS の実装言語の比較の測定結果（#32）

- CI（GitHub Actions、ubuntu-latest）の `./gradlew build` の中の `OsLanguageTest`（`測定の表を出す`）の出力を写したもの（2026-11-06、run 42204253023）。手元では MoonBit をビルドしないため（#32 のオーナーの回答）、CI の数字を使う
- #424（SIMD のモジュールのコンパイル）と #425（multi-value のブロックの待機点への変換）を入れた main で測り直した（オーナーの回答「3: 基盤を直して再測定」）
- 同じ表は `./gradlew :poc:os-language:run -Pkibi.moonbit=true`（または CI がビルドした WASM を `-Pkibi.moonbitWasmDir=...` で渡す）でも出る。時間は回数が少ない（3 回）ので目安。CI の機械の揺れで、Zig の shell も前回の 8.2 ms から 24.5 ms になっている
- Zig 0.17.0（`zig build-exe -O ReleaseSmall -fstrip`）、MoonBit 0.10.20+65efd5b93（`moon build --target wasm --release`）。MoonBit+wasm-opt は、MoonBit の出力に同梱の Binaryen（`moon-wasm-opt -all -O2`）をかけたもの
- プログラムはどれも同じ振る舞い（出力の SHA-256 が一致することをテストで確かめている）。ソースは `src/main/zig/` と `src/main/moonbit/`

## まとめ（測り直し）

| 観点 | Zig | MoonBit | MoonBit+wasm-opt |
| --- | --- | --- | --- |
| 大きさ（echo・cat・ls・shell） | 0.7〜1.2 KB | 10.9〜11.9 KB | 7.8〜8.4 KB |
| 起動だけで使う燃料（kmem） | 74 | 11,839 | 11,495 |
| 燃料（shell 2,000 行） | 6.84 M | 30.85 M（4.5 倍） | 26.41 M（3.9 倍） |
| 燃料（ls 200 ファイル） | 30.7 k | 198.6 k（6.5 倍） | 137.7 k（4.5 倍） |
| Endive のランタイムコンパイラ | 使える | **使える**（SIMD の関数 2 つだけインタプリタ） | **使える**（同 1 つ） |
| 実行時間（shell 2,000 行） | 24.5 ms | 178.9 ms（約 7 倍） | 54.0 ms（約 2.2 倍） |
| 実行時間（ls 200 ファイル） | 0.80 ms | 5.56 ms（約 7 倍） | 4.59 ms（約 5.7 倍） |
| 待機点への変換（#329・#339） | 全部できる（燃料切れも待ち点） | **全部できる（燃料切れも待ち点）** | **全部できる（燃料切れも待ち点）** |
| 読み込み（変換・コンパイル。shell） | 13 ms | 94 ms | 130 ms |
| 入力を待つまでに書き換える RAM（shell） | 4 KiB | 20 KiB | 20 KiB |
| p1 の上で `kibi:*` を呼ぶ | できる | できる | できる |

前回（#424・#425 の前）との違い:

- **SIMD**: MoonBit の標準ライブラリの配列の初期化・コピー（`FixedArray::make`・`blit`）が `v128.load`/`v128.store` を出すのは変わらない。#424 で SIMD を使う関数だけをインタプリタで動かすようになり、モジュールの残りはコンパイルされる。shell 2,000 行は 1,588 ms → 179 ms、wasm-opt 版は 1,345 ms → 54 ms
- **multi-value**: #425 で、止まりうる呼び出しを含む multi-value のブロックも待機点に変換できるようになった。echo・cat・ls も待機点型で動き、燃料切れも待ち点にできる（前回は BLOCKING でしか動かない、または燃料切れを待ち点にできなかった）
- **燃料は変わらない**（4〜6 倍）。燃料は命令の数で数えるので、コンパイルされても同じ。プレイヤーから見た速さ（1 tick に使える燃料で進む量）は、Zig の 1/4〜1/6 のまま。原因は未調査（配列の範囲検査・参照カウント・タプルの確保などと推測）
- **重複 import**: MoonBit の出力は `wasi_snapshot_preview1.fd_write` を2回 import する（標準ライブラリの出力の分）。カーネルの p1 は問題なく動かす

# 標準 OS の実装言語の比較（#32）

- `./gradlew :poc:os-language:run` の出力。OpenJDK 64-Bit Server VM 17.0.21+9、Linux amd64
- 言語: Zig / MoonBit / MoonBit+wasm-opt。時間は 3 回の中央値（1回目はウォームアップとして捨てる）。実行方式はランタイムコンパイラ（コンパイルできないモジュールはインタプリタ）

## モジュール

変換後: 燃料の計測コードの挿入と待機点への変換（`Machine.exec` と同じ。#38・#329）をかけた後。燃料待ち点: 燃料切れも待ち点にできたか（#339 の段1）。
関数: 関数の数（import を除く）/ SIMD（v128）の命令を含む関数の数。SIMD を含むと Endive のランタイムコンパイラが使えず、モジュール全体がインタプリタで動く

| プログラム | 言語 | サイズ | 関数 / SIMD | 最初のメモリ | 変換後 | 待機点への変換 | 読み込み（変換・コンパイル） | import |
| --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| echo | Zig | 736 B | 3 / 0 | 128 KiB | 1915 B | できる（燃料切れも） | 42.720 ms | wasi_snapshot_preview1.proc_exit, wasi_snapshot_preview1.args_sizes_get, wasi_snapshot_preview1.args_get, wasi_snapshot_preview1.fd_write |
| echo | MoonBit | 10898 B | 97 / 2 | 64 KiB | 34150 B | できる（燃料切れも） | 344.247 ms | wasi_snapshot_preview1.fd_write, wasi_snapshot_preview1.fd_write, wasi_snapshot_preview1.args_get, wasi_snapshot_preview1.proc_exit, wasi_snapshot_preview1.args_sizes_get |
| echo | MoonBit+wasm-opt | 7766 B | 40 / 1 | 64 KiB | 21128 B | できる（燃料切れも） | 137.430 ms | wasi_snapshot_preview1.fd_write, wasi_snapshot_preview1.args_get, wasi_snapshot_preview1.proc_exit, wasi_snapshot_preview1.args_sizes_get |
| cat | Zig | 1142 B | 5 / 0 | 128 KiB | 3421 B | できる（燃料切れも） | 43.705 ms | wasi_snapshot_preview1.proc_exit, wasi_snapshot_preview1.args_sizes_get, wasi_snapshot_preview1.args_get, wasi_snapshot_preview1.path_open, wasi_snapshot_preview1.fd_close, wasi_snapshot_preview1.fd_write, wasi_snapshot_preview1.fd_read |
| cat | MoonBit | 11384 B | 103 / 2 | 64 KiB | 36305 B | できる（燃料切れも） | 163.886 ms | wasi_snapshot_preview1.fd_write, wasi_snapshot_preview1.fd_write, wasi_snapshot_preview1.fd_read, wasi_snapshot_preview1.args_get, wasi_snapshot_preview1.fd_close, wasi_snapshot_preview1.proc_exit, wasi_snapshot_preview1.path_open, wasi_snapshot_preview1.args_sizes_get |
| cat | MoonBit+wasm-opt | 8076 B | 42 / 1 | 64 KiB | 23042 B | できる（燃料切れも） | 111.400 ms | wasi_snapshot_preview1.fd_write, wasi_snapshot_preview1.fd_read, wasi_snapshot_preview1.args_get, wasi_snapshot_preview1.fd_close, wasi_snapshot_preview1.proc_exit, wasi_snapshot_preview1.path_open, wasi_snapshot_preview1.args_sizes_get |
| ls | Zig | 1167 B | 4 / 0 | 128 KiB | 3069 B | できる（燃料切れも） | 27.592 ms | wasi_snapshot_preview1.proc_exit, wasi_snapshot_preview1.args_sizes_get, wasi_snapshot_preview1.args_get, wasi_snapshot_preview1.path_open, wasi_snapshot_preview1.fd_readdir, wasi_snapshot_preview1.fd_close, wasi_snapshot_preview1.fd_write |
| ls | MoonBit | 11935 B | 105 / 2 | 64 KiB | 36805 B | できる（燃料切れも） | 147.597 ms | wasi_snapshot_preview1.fd_write, wasi_snapshot_preview1.fd_write, wasi_snapshot_preview1.fd_readdir, wasi_snapshot_preview1.args_get, wasi_snapshot_preview1.fd_close, wasi_snapshot_preview1.proc_exit, wasi_snapshot_preview1.path_open, wasi_snapshot_preview1.args_sizes_get |
| ls | MoonBit+wasm-opt | 8391 B | 43 / 1 | 64 KiB | 23172 B | できる（燃料切れも） | 49.413 ms | wasi_snapshot_preview1.fd_write, wasi_snapshot_preview1.fd_readdir, wasi_snapshot_preview1.args_get, wasi_snapshot_preview1.fd_close, wasi_snapshot_preview1.proc_exit, wasi_snapshot_preview1.path_open, wasi_snapshot_preview1.args_sizes_get |
| shell | Zig | 1173 B | 3 / 0 | 128 KiB | 3712 B | できる（燃料切れも） | 13.133 ms | wasi_snapshot_preview1.proc_exit, wasi_snapshot_preview1.fd_read, wasi_snapshot_preview1.fd_write |
| shell | MoonBit | 11486 B | 102 / 2 | 64 KiB | 37154 B | できる（燃料切れも） | 93.926 ms | wasi_snapshot_preview1.fd_write, wasi_snapshot_preview1.fd_write, wasi_snapshot_preview1.fd_read, wasi_snapshot_preview1.proc_exit |
| shell | MoonBit+wasm-opt | 8109 B | 40 / 1 | 64 KiB | 22638 B | できる（燃料切れも） | 129.791 ms | wasi_snapshot_preview1.fd_write, wasi_snapshot_preview1.fd_read, wasi_snapshot_preview1.proc_exit |
| kmem | Zig | 493 B | 3 / 0 | 128 KiB | 1562 B | できる（燃料切れも） | 10.204 ms | wasi_snapshot_preview1.proc_exit, kibi:computer/memory@0.1.0.commit, kibi:computer/memory@0.1.0.remaining, wasi_snapshot_preview1.fd_write |
| kmem | MoonBit | 3428 B | 43 / 0 | 64 KiB | 8147 B | できる（燃料切れも） | 38.674 ms | wasi_snapshot_preview1.fd_write, kibi:computer/memory@0.1.0.remaining, kibi:computer/memory@0.1.0.commit, wasi_snapshot_preview1.proc_exit |
| kmem | MoonBit+wasm-opt | 2921 B | 19 / 0 | 64 KiB | 6288 B | できる（燃料切れも） | 19.063 ms | wasi_snapshot_preview1.fd_write, kibi:computer/memory@0.1.0.remaining, kibi:computer/memory@0.1.0.commit, wasi_snapshot_preview1.proc_exit |

## 燃料と速さ（カーネルを通さない最小の WASI ホスト）

燃料の計測コードだけを挿入して Endive で直接動かす（待機点への変換はかけない。燃料は変換の前に挿入するので、カーネルの上と同じ量）。
生成: インスタンスを作る時間（MoonBit は start 関数の初期化を含む）。実行: `_start` の呼び出し。出力: 標準出力の SHA-256 の先頭

| シナリオ | 言語 | 実行方式 | 終了コード | 燃料 | 生成 | 実行 | 出力 |
| --- | --- | --- | ---: | ---: | ---: | ---: | --- |
| echo（引数 3 つ） | Zig | コンパイル | 0 | 573 | 0.150 ms | 0.100 ms | `d86345ed` (17 B) |
| echo（引数 3 つ） | MoonBit | コンパイル | 0 | 21,683 | 0.373 ms | 0.283 ms | `d86345ed` (17 B) |
| echo（引数 3 つ） | MoonBit+wasm-opt | コンパイル | 0 | 20,370 | 0.356 ms | 0.211 ms | `d86345ed` (17 B) |
| cat（256 KiB のファイル） | Zig | コンパイル | 0 | 6,019 | 0.247 ms | 0.623 ms | `5584afbd` (262144 B) |
| cat（256 KiB のファイル） | MoonBit | コンパイル | 0 | 36,189 | 0.363 ms | 0.857 ms | `5584afbd` (262144 B) |
| cat（256 KiB のファイル） | MoonBit+wasm-opt | コンパイル | 0 | 26,733 | 0.408 ms | 0.673 ms | `5584afbd` (262144 B) |
| cat（256 KiB の標準入力） | Zig | コンパイル | 0 | 5,802 | 0.236 ms | 0.483 ms | `5584afbd` (262144 B) |
| cat（256 KiB の標準入力） | MoonBit | コンパイル | 0 | 33,940 | 0.426 ms | 0.812 ms | `5584afbd` (262144 B) |
| cat（256 KiB の標準入力） | MoonBit+wasm-opt | コンパイル | 0 | 24,809 | 0.363 ms | 3.053 ms | `5584afbd` (262144 B) |
| ls（200 ファイル） | Zig | コンパイル | 0 | 30,736 | 0.208 ms | 0.801 ms | `8045ee53` (2600 B) |
| ls（200 ファイル） | MoonBit | コンパイル | 0 | 198,613 | 0.374 ms | 5.562 ms | `8045ee53` (2600 B) |
| ls（200 ファイル） | MoonBit+wasm-opt | コンパイル | 0 | 137,732 | 0.340 ms | 4.588 ms | `8045ee53` (2600 B) |
| shell（2,000 行） | Zig | コンパイル | 0 | 6,843,964 | 0.327 ms | 24.529 ms | `bec7d2ed` (139169 B) |
| shell（2,000 行） | MoonBit | コンパイル | 0 | 30,845,543 | 0.500 ms | 178.946 ms | `bec7d2ed` (139169 B) |
| shell（2,000 行） | MoonBit+wasm-opt | コンパイル | 0 | 26,408,580 | 0.518 ms | 54.020 ms | `bec7d2ed` (139169 B) |
| kmem（kibi の import） | Zig | コンパイル | 0 | 74 | 0.228 ms | 0.079 ms | `99bfda6c` (8 B) |
| kmem（kibi の import） | MoonBit | コンパイル | 0 | 11,839 | 0.414 ms | 0.066 ms | `99bfda6c` (8 B) |
| kmem（kibi の import） | MoonBit+wasm-opt | コンパイル | 0 | 11,495 | 0.261 ms | 0.043 ms | `99bfda6c` (8 B) |

## カーネルの上（待機点型で動くか、入力を待つまでに書き換える RAM）

RAM: 入力を待っているときに書き換えた 4 KiB の区画（#407。RAM に数える）。ROM: データセグメントで書いた区画（数えない）。宣言: 線形メモリの大きさ

| プログラム | 言語 | 種類 | 燃料待ち点 | RAM | ROM | 宣言 | 入力の後 |
| --- | --- | --- | --- | ---: | ---: | ---: | --- |
| shell | Zig | WAIT_POINT | できる | 4 KiB | 4 KiB | 128 KiB | `echo  a	b⏎[echo][a][b]⏎>` |
| shell | MoonBit | WAIT_POINT | できる | 20 KiB | 4 KiB | 64 KiB | `echo  a	b⏎[echo][a][b]⏎>` |
| shell | MoonBit+wasm-opt | WAIT_POINT | できる | 20 KiB | 4 KiB | 64 KiB | `echo  a	b⏎[echo][a][b]⏎>` |
| echo | Zig | WAIT_POINT | できる | - | - | - | `hello kibi`（EXITED, 0） |
| echo | MoonBit | WAIT_POINT | できる | - | - | - | `hello kibi`（EXITED, 0） |
| echo | MoonBit+wasm-opt | WAIT_POINT | できる | - | - | - | `hello kibi`（EXITED, 0） |
| cat | Zig | WAIT_POINT | できる | - | - | - | `alpha`（EXITED, 0） |
| cat | MoonBit | WAIT_POINT | できる | - | - | - | `alpha`（EXITED, 0） |
| cat | MoonBit+wasm-opt | WAIT_POINT | できる | - | - | - | `alpha`（EXITED, 0） |
| ls | Zig | WAIT_POINT | できる | - | - | - | `a.txt⏎b.txt`（EXITED, 0） |
| ls | MoonBit | WAIT_POINT | できる | - | - | - | `a.txt⏎b.txt`（EXITED, 0） |
| ls | MoonBit+wasm-opt | WAIT_POINT | できる | - | - | - | `a.txt⏎b.txt`（EXITED, 0） |
| kmem | Zig | WAIT_POINT | できる | - | - | - | `kibi ok`（EXITED, 0） |
| kmem | MoonBit | WAIT_POINT | できる | - | - | - | `kibi ok`（EXITED, 0） |
| kmem | MoonBit+wasm-opt | WAIT_POINT | できる | - | - | - | `kibi ok`（EXITED, 0） |
