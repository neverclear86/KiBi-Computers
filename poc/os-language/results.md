# 標準 OS の実装言語の比較の測定結果（#32）

- CI（GitHub Actions、ubuntu-latest）の `./gradlew build` の中の `OsLanguageTest`（`測定の表を出す`）の出力を写したもの（2026-11-05、run 42047325055）。手元では MoonBit をビルドしないため（#32 のオーナーの回答）、CI の数字を使う
- 同じ表は `./gradlew :poc:os-language:run -Pkibi.moonbit=true`（または CI がビルドした WASM を `-Pkibi.moonbitWasmDir=...` で渡す）でも出る。時間は回数が少ない（3 回）ので目安
- Zig 0.17.0（`zig build-exe -O ReleaseSmall -fstrip`）、MoonBit 0.10.20+65efd5b93（`moon build --target wasm --release`）。MoonBit+wasm-opt は、MoonBit の出力に同梱の Binaryen（`moon-wasm-opt -all -O2`）をかけたもの
- プログラムはどれも同じ振る舞い（出力の SHA-256 が一致することをテストで確かめている）。ソースは `src/main/zig/` と `src/main/moonbit/`

## まとめ

| 観点 | Zig | MoonBit | MoonBit+wasm-opt |
| --- | --- | --- | --- |
| 大きさ（echo・cat・ls・shell） | 0.7〜1.2 KB | 10.9〜11.9 KB | 7.8〜8.4 KB |
| 起動だけで使う燃料（kmem） | 74 | 11,839 | 11,495 |
| 燃料（shell 2,000 行） | 6.84 M | 30.85 M（4.5 倍） | 26.41 M（3.9 倍） |
| 燃料（ls 200 ファイル） | 30.7 k | 198.6 k（6.5 倍） | 137.7 k（4.5 倍） |
| Endive のランタイムコンパイラ | 使える | 使えない（SIMD があるのでインタプリタ） | 使えない（同左） |
| 実行時間（shell 2,000 行） | 8.2 ms | 1,588 ms（約 190 倍） | 1,345 ms（約 160 倍） |
| 待機点への変換（#329・#339） | 全部できる（燃料切れも待ち点） | shell と kmem 以外は変換できない（引数を持つブロック）。変換できない echo・cat・ls は BLOCKING でしか動かない | 全部、入力待ちだけ変換できる（燃料切れは待ち点にできない: 複数の結果を持つブロック）。kmem だけ燃料切れも |
| 入力を待つまでに書き換える RAM（shell） | 4 KiB | 20 KiB | 20 KiB |
| p1 の上で `kibi.*` を呼ぶ | できる | できる | できる |

原因（MoonBit 側）:

- **SIMD**: MoonBit の標準ライブラリの配列の初期化・コピー（`FixedArray::make`・`blit`）が `v128.load`/`v128.store` を出す。SIMD の命令を1つでも含むと、Endive のランタイムコンパイラがモジュール全体を断り、インタプリタで動く（`EndiveRuntime.compileOrNull`）。`moonc` に SIMD を止める指定は見当たらない
- **multi-value**: MoonBit は引数を持つブロック・複数の結果を持つブロックを出す。カーネルの待機点への変換（`Asyncify`）は、止まりうる呼び出しを含むそのようなブロックに対応していない。wasm-opt で引数を持つブロックは消えるが、複数の結果を持つブロックが残るので、燃料切れを待ち点にできない（#339 の段2。燃料を待つ間スレッドを持つ）
- **燃料**: 起動時の初期化（start 関数。この PoC の `sys` のグローバルの初期化を含む）と終わりまでで約 1.2 万（kmem）。処理の燃料は 4〜6 倍（原因は未調査。配列の範囲検査・参照カウント・タプルの確保などと推測）
- **重複 import**: MoonBit の出力は `wasi_snapshot_preview1.fd_write` を2回 import する（標準ライブラリの出力の分）。カーネルの p1 は問題なく動かす

# 標準 OS の実装言語の比較（#32）

- `./gradlew :poc:os-language:run` の出力。OpenJDK 64-Bit Server VM 17.0.21+9、Linux amd64
- 言語: Zig / MoonBit / MoonBit+wasm-opt。時間は 3 回の中央値（1回目はウォームアップとして捨てる）。実行方式はランタイムコンパイラ（コンパイルできないモジュールはインタプリタ）

## モジュール

変換後: 燃料の計測コードの挿入と待機点への変換（`Machine.exec` と同じ。#38・#329）をかけた後。燃料待ち点: 燃料切れも待ち点にできたか（#339 の段1）。
関数: 関数の数（import を除く）/ SIMD（v128）の命令を含む関数の数。SIMD を含むと Endive のランタイムコンパイラが使えず、モジュール全体がインタプリタで動く

| プログラム | 言語 | サイズ | 関数 / SIMD | 最初のメモリ | 変換後 | 待機点への変換 | 読み込み（変換・コンパイル） | import |
| --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| echo | Zig | 736 B | 3 / 0 | 128 KiB | 1915 B | できる（燃料切れも） | 13.571 ms | wasi_snapshot_preview1.proc_exit, wasi_snapshot_preview1.args_sizes_get, wasi_snapshot_preview1.args_get, wasi_snapshot_preview1.fd_write |
| echo | MoonBit | 10898 B | 97 / 2 | 64 KiB | - | できない: 止まりうる関数 #53 の中: 止まりうる呼び出しを含む、引数を持つブロック（multi-value）には対応していない | - | wasi_snapshot_preview1.fd_write, wasi_snapshot_preview1.fd_write, wasi_snapshot_preview1.args_get, wasi_snapshot_preview1.proc_exit, wasi_snapshot_preview1.args_sizes_get |
| echo | MoonBit+wasm-opt | 7766 B | 40 / 1 | 64 KiB | 18734 B | 入力待ちだけ: 止まりうる関数 #37 の中: 止まりうる呼び出しを含む、複数の結果を持つブロック（multi-value）には対応していない | 63.351 ms | wasi_snapshot_preview1.fd_write, wasi_snapshot_preview1.args_get, wasi_snapshot_preview1.proc_exit, wasi_snapshot_preview1.args_sizes_get |
| cat | Zig | 1142 B | 5 / 0 | 128 KiB | 3421 B | できる（燃料切れも） | 16.463 ms | wasi_snapshot_preview1.proc_exit, wasi_snapshot_preview1.args_sizes_get, wasi_snapshot_preview1.args_get, wasi_snapshot_preview1.path_open, wasi_snapshot_preview1.fd_close, wasi_snapshot_preview1.fd_write, wasi_snapshot_preview1.fd_read |
| cat | MoonBit | 11384 B | 103 / 2 | 64 KiB | - | できない: 止まりうる関数 #60 の中: 止まりうる呼び出しを含む、引数を持つブロック（multi-value）には対応していない | - | wasi_snapshot_preview1.fd_write, wasi_snapshot_preview1.fd_write, wasi_snapshot_preview1.fd_read, wasi_snapshot_preview1.args_get, wasi_snapshot_preview1.fd_close, wasi_snapshot_preview1.proc_exit, wasi_snapshot_preview1.path_open, wasi_snapshot_preview1.args_sizes_get |
| cat | MoonBit+wasm-opt | 8076 B | 42 / 1 | 64 KiB | 20322 B | 入力待ちだけ: 止まりうる関数 #42 の中: 止まりうる呼び出しを含む、複数の結果を持つブロック（multi-value）には対応していない | 54.887 ms | wasi_snapshot_preview1.fd_write, wasi_snapshot_preview1.fd_read, wasi_snapshot_preview1.args_get, wasi_snapshot_preview1.fd_close, wasi_snapshot_preview1.proc_exit, wasi_snapshot_preview1.path_open, wasi_snapshot_preview1.args_sizes_get |
| ls | Zig | 1167 B | 4 / 0 | 128 KiB | 3069 B | できる（燃料切れも） | 19.492 ms | wasi_snapshot_preview1.proc_exit, wasi_snapshot_preview1.args_sizes_get, wasi_snapshot_preview1.args_get, wasi_snapshot_preview1.path_open, wasi_snapshot_preview1.fd_readdir, wasi_snapshot_preview1.fd_close, wasi_snapshot_preview1.fd_write |
| ls | MoonBit | 11935 B | 105 / 2 | 64 KiB | - | できない: 止まりうる関数 #63 の中: 止まりうる呼び出しを含む、引数を持つブロック（multi-value）には対応していない | - | wasi_snapshot_preview1.fd_write, wasi_snapshot_preview1.fd_write, wasi_snapshot_preview1.fd_readdir, wasi_snapshot_preview1.args_get, wasi_snapshot_preview1.fd_close, wasi_snapshot_preview1.proc_exit, wasi_snapshot_preview1.path_open, wasi_snapshot_preview1.args_sizes_get |
| ls | MoonBit+wasm-opt | 8391 B | 43 / 1 | 64 KiB | 20526 B | 入力待ちだけ: 止まりうる関数 #43 の中: 止まりうる呼び出しを含む、複数の結果を持つブロック（multi-value）には対応していない | 37.934 ms | wasi_snapshot_preview1.fd_write, wasi_snapshot_preview1.fd_readdir, wasi_snapshot_preview1.args_get, wasi_snapshot_preview1.fd_close, wasi_snapshot_preview1.proc_exit, wasi_snapshot_preview1.path_open, wasi_snapshot_preview1.args_sizes_get |
| shell | Zig | 1173 B | 3 / 0 | 128 KiB | 3712 B | できる（燃料切れも） | 10.863 ms | wasi_snapshot_preview1.proc_exit, wasi_snapshot_preview1.fd_read, wasi_snapshot_preview1.fd_write |
| shell | MoonBit | 11486 B | 102 / 2 | 64 KiB | 33466 B | 入力待ちだけ: 止まりうる関数 #53 の中: 止まりうる呼び出しを含む、引数を持つブロック（multi-value）には対応していない | 40.649 ms | wasi_snapshot_preview1.fd_write, wasi_snapshot_preview1.fd_write, wasi_snapshot_preview1.fd_read, wasi_snapshot_preview1.proc_exit |
| shell | MoonBit+wasm-opt | 8109 B | 40 / 1 | 64 KiB | 19820 B | 入力待ちだけ: 止まりうる関数 #36 の中: 止まりうる呼び出しを含む、複数の結果を持つブロック（multi-value）には対応していない | 29.862 ms | wasi_snapshot_preview1.fd_write, wasi_snapshot_preview1.fd_read, wasi_snapshot_preview1.proc_exit |
| kmem | Zig | 462 B | 3 / 0 | 128 KiB | 1532 B | できる（燃料切れも） | 7.608 ms | wasi_snapshot_preview1.proc_exit, kibi.memory_commit, kibi.memory_remaining, wasi_snapshot_preview1.fd_write |
| kmem | MoonBit | 3397 B | 43 / 0 | 64 KiB | 8117 B | できる（燃料切れも） | 17.747 ms | wasi_snapshot_preview1.fd_write, kibi.memory_commit, wasi_snapshot_preview1.proc_exit, kibi.memory_remaining |
| kmem | MoonBit+wasm-opt | 2890 B | 19 / 0 | 64 KiB | 6258 B | できる（燃料切れも） | 13.250 ms | wasi_snapshot_preview1.fd_write, kibi.memory_commit, wasi_snapshot_preview1.proc_exit, kibi.memory_remaining |

## 燃料と速さ（カーネルを通さない最小の WASI ホスト）

燃料の計測コードだけを挿入して Endive で直接動かす（待機点への変換はかけない。燃料は変換の前に挿入するので、カーネルの上と同じ量）。
生成: インスタンスを作る時間（MoonBit は start 関数の初期化を含む）。実行: `_start` の呼び出し。出力: 標準出力の SHA-256 の先頭

| シナリオ | 言語 | 実行方式 | 終了コード | 燃料 | 生成 | 実行 | 出力 |
| --- | --- | --- | ---: | ---: | ---: | ---: | --- |
| echo（引数 3 つ） | Zig | コンパイル | 0 | 573 | 0.195 ms | 0.141 ms | `d86345ed` (17 B) |
| echo（引数 3 つ） | MoonBit | インタプリタ | 0 | 21,683 | 0.626 ms | 0.668 ms | `d86345ed` (17 B) |
| echo（引数 3 つ） | MoonBit+wasm-opt | インタプリタ | 0 | 20,370 | 0.652 ms | 0.886 ms | `d86345ed` (17 B) |
| cat（256 KiB のファイル） | Zig | コンパイル | 0 | 6,019 | 0.254 ms | 0.435 ms | `5584afbd` (262144 B) |
| cat（256 KiB のファイル） | MoonBit | インタプリタ | 0 | 36,189 | 0.762 ms | 2.421 ms | `5584afbd` (262144 B) |
| cat（256 KiB のファイル） | MoonBit+wasm-opt | インタプリタ | 0 | 26,733 | 0.511 ms | 1.081 ms | `5584afbd` (262144 B) |
| cat（256 KiB の標準入力） | Zig | コンパイル | 0 | 5,802 | 0.246 ms | 0.384 ms | `5584afbd` (262144 B) |
| cat（256 KiB の標準入力） | MoonBit | インタプリタ | 0 | 33,940 | 0.536 ms | 2.595 ms | `5584afbd` (262144 B) |
| cat（256 KiB の標準入力） | MoonBit+wasm-opt | インタプリタ | 0 | 24,809 | 0.660 ms | 1.235 ms | `5584afbd` (262144 B) |
| ls（200 ファイル） | Zig | コンパイル | 0 | 30,736 | 0.226 ms | 1.197 ms | `8045ee53` (2600 B) |
| ls（200 ファイル） | MoonBit | インタプリタ | 0 | 198,613 | 0.698 ms | 12.172 ms | `8045ee53` (2600 B) |
| ls（200 ファイル） | MoonBit+wasm-opt | インタプリタ | 0 | 137,732 | 0.468 ms | 7.675 ms | `8045ee53` (2600 B) |
| shell（2,000 行） | Zig | コンパイル | 0 | 6,843,964 | 0.193 ms | 8.244 ms | `bec7d2ed` (139169 B) |
| shell（2,000 行） | MoonBit | インタプリタ | 0 | 30,845,543 | 0.590 ms | 1587.863 ms | `bec7d2ed` (139169 B) |
| shell（2,000 行） | MoonBit+wasm-opt | インタプリタ | 0 | 26,408,580 | 0.670 ms | 1344.563 ms | `bec7d2ed` (139169 B) |
| kmem（kibi の import） | Zig | コンパイル | 0 | 74 | 0.148 ms | 0.068 ms | `99bfda6c` (8 B) |
| kmem（kibi の import） | MoonBit | コンパイル | 0 | 11,839 | 0.347 ms | 0.057 ms | `99bfda6c` (8 B) |
| kmem（kibi の import） | MoonBit+wasm-opt | コンパイル | 0 | 11,495 | 0.290 ms | 0.053 ms | `99bfda6c` (8 B) |

## カーネルの上（待機点型で動くか、入力を待つまでに書き換える RAM）

RAM: 入力を待っているときに書き換えた 4 KiB の区画（#407。RAM に数える）。ROM: データセグメントで書いた区画（数えない）。宣言: 線形メモリの大きさ

| プログラム | 言語 | 種類 | 燃料待ち点 | RAM | ROM | 宣言 | 入力の後 |
| --- | --- | --- | --- | ---: | ---: | ---: | --- |
| shell | Zig | WAIT_POINT | できる | 4 KiB | 4 KiB | 128 KiB | `echo  a	b⏎[echo][a][b]⏎>` |
| shell | MoonBit | WAIT_POINT | できない | 20 KiB | 4 KiB | 64 KiB | `echo  a	b⏎[echo][a][b]⏎>` |
| shell | MoonBit+wasm-opt | WAIT_POINT | できない | 20 KiB | 4 KiB | 64 KiB | `echo  a	b⏎[echo][a][b]⏎>` |
| echo | Zig | WAIT_POINT | できる | - | - | - | `hello kibi`（EXITED, 0） |
| echo | MoonBit | BLOCKING | できない | - | - | - | `hello kibi`（EXITED, 0） |
| echo | MoonBit+wasm-opt | WAIT_POINT | できない | - | - | - | `hello kibi`（EXITED, 0） |
| cat | Zig | WAIT_POINT | できる | - | - | - | `alpha`（EXITED, 0） |
| cat | MoonBit | BLOCKING | できない | - | - | - | `alpha`（EXITED, 0） |
| cat | MoonBit+wasm-opt | WAIT_POINT | できない | - | - | - | `alpha`（EXITED, 0） |
| ls | Zig | WAIT_POINT | できる | - | - | - | `a.txt⏎b.txt`（EXITED, 0） |
| ls | MoonBit | BLOCKING | できない | - | - | - | `a.txt⏎b.txt`（EXITED, 0） |
| ls | MoonBit+wasm-opt | WAIT_POINT | できない | - | - | - | `a.txt⏎b.txt`（EXITED, 0） |
| kmem | Zig | WAIT_POINT | できる | - | - | - | `kibi ok`（EXITED, 0） |
| kmem | MoonBit | WAIT_POINT | できる | - | - | - | `kibi ok`（EXITED, 0） |
| kmem | MoonBit+wasm-opt | WAIT_POINT | できる | - | - | - | `kibi ok`（EXITED, 0） |

