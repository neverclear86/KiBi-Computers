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
- **燃料は変わらない**（4〜6 倍）。燃料は命令の数で数えるので、コンパイルされても同じ。プレイヤーから見た速さ（1 tick に使える燃料で進む量）は、Zig の 1/4〜1/6 のまま。原因と、書き方で縮められるかは、末尾の「燃料の内訳と原因（#32 の追加調査）」
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

# 燃料の内訳と原因（#32 の追加調査）

## 測り方

- **関数ごとの燃料**: `Profiler`（`src/main/kotlin/.../Profiler.kt`）。燃料の計測コードは入れず、元のモジュールを Endive のインタプリタで動かし、`ExecutionListener` で実行した命令を関数ごとに数える。`FuelInjector` と同じくセグメント（分岐の出入りのない命令の並び）の先頭でその長さを数え、`memory.copy`・`memory.fill` は長さ / 8 を足す。**合計は計測コードで測った燃料と 1 の位まで一致する**（テスト `関数ごとの内訳の合計は、計測コードで測った燃料と同じ`。上の表の燃料もすべて再現する）
- **名前**: CI の `moonbitExperiments` タスクが `moon build --release --no-strip` でビルドしたものの name セクションから引く（Zig は `-fstrip` なので番号）。`--no-strip` は名前のほかにコードも少し変わる（shell で燃料 +7%。wasm-opt をかけると差はほぼ消える）ので、**倍率は普通のリリースのビルドで出す**
- **書き換え版**: `src/experiments/moonbit/cmd/<名前>/`。`moonbitExperiments` が1つずつ元のモジュールに足してビルドし（コンパイルできなくてもビルドは止めない）、moon-wasm-opt を `-O2`・`-O3`・`-O4`・`-tnh` でかける。出力は CI の成果物 `os-language-moonbit-wasm` の `experiments/`（`<名前>-strip.wasm` が普通のリリースのビルド）
- 再現: `./gradlew :poc:os-language:run "-Pbench.args=profile <成果物の experiments>"`（表は `build/bench/profile.md`、ホットな関数の命令ごとの回数は `build/bench/profile/*.txt`）
- CI run: 42235598711（初回の書き換え版）、42236654989（ビットストリング・for-in・アロケーター）、42237539608（組み合わせ。下の数字はこの run の成果物を手元で測ったもの）。MoonBit 0.10.20+65efd5b93

## 内訳: shell（2,000 行）

Zig 6,843,964 の内訳は、本体（`main` に全部インライン化）96.9%、`writeAll` 3.1%。MoonBit（名前つきのビルド、32,966,048）:

| 分類 | 関数 | 燃料 | 割合 | Zig で対応するもの |
| --- | --- | ---: | ---: | --- |
| 範囲検査 | `moonbit.check_range`（543,010 回 × 11）・`moonbit.array_length`（555,084 回 × 5） | 8,748,530 | 26.5% | なし（ReleaseSmall は検査しない） |
| 本体: 語の写し | `main`（`out[n+1+j] = buf[s+j]` を 1 バイトずつ） | 8,165,094 | 24.8% | `@memcpy`（`memory.copy` 1 命令 + 長さ / 8） |
| 本体: 空白で区切る | `split` | 6,571,182 | 19.9% | `main` の中のループ |
| 本体: 改行を探す・詰め直す | `LineReader::read_line` | 4,968,831 | 15.1% | 同上（改行を探すのは 1 バイト 19 命令） |
| 確保と解放 | `tlsf/*`・`moonbit.malloc*`・`moonbit.gc.free`・`moonbit.free`・`Array::push` ほか（約 7,000 回） | 約 3,500,000 | 約 11% | なし（語は入力のバッファを指すスライス） |
| 参照カウント | `moonbit.incref`・`moonbit.decref` | 269,032 | 0.8% | なし |
| システムコールの包み | `sys.write_all_at`・`address`・`store_i32` ほか | 約 600,000 | 約 2% | `writeAll`（212,053） |
| 起動 | start 関数（アロケーターの初期化） | 9,872 | 0.0% | なし |

## 内訳: ls（200 ファイル）

Zig 30,736 の内訳は、`writeAll`（400 回 × 51）66%、本体 34%（`u32At` は `i32.load` 1 つになっている）。MoonBit（名前つき、217,280）:

| 分類 | 関数 | 燃料 | 割合 | Zig で対応するもの |
| --- | --- | ---: | ---: | --- |
| dirent の整数を読む | `u32_at`（606 回 × 108）・`u64_at` | 71,710 | 33.0% | `i32.load` 1 つ（本体の中） |
| 範囲検査 | `moonbit.check_range`・`moonbit.array_length`（`u32_at` の 4 バイトの読み） | 40,180 | 18.5% | なし |
| システムコールの包み | `sys.write_all_at`（400 回 × 88）・`address`・`store_i32`・`write_bytes` ほか | 約 67,000 | 約 31% | `writeAll`（400 回 × 51 = 20,400） |
| 本体 | `main` | 21,413 | 9.9% | `main`（10,300） |
| 起動 | `tlsf/initialize`（7,959）とアロケーターの初期化・最初の確保 | 約 11,500 | 約 5% | なし |

## 内訳: 起動（kmem）

MoonBit の燃料 11,839 のうち 9,829 が start 関数。中身は **`tlsf/initialize` が 7,959**（TLSF の空きリストの表 23 × 16 を、1 語ずつ書くループで 0 にする）で、残りは最初の確保（`moonbit.bytes_make` → `malloc` → `tlsf/insertBlock`・`removeBlock`）。Zig は 74。プロセスを起こすたびに約 1 万を使う（今の仮の値 1 tick 100 万の約 1%）

## 原因（根拠）

1. **範囲検査が関数呼び出しになる**: `buf[i]`（`FixedArray::at` = `%fixedarray.get`）は、moonc の線形メモリのバックエンドでは次のようになる（shell の `read_line`。左の数字はその命令の実行回数）。添字 1 つで、呼び出し側 7 命令 + `array_length` 5 + `check_range` 11 = 約 23 命令。shell では 54 万回あり、燃料の 3 割を超える

   ```
   136,445  local_get 49, i32_const 0, local_get 49, call moonbit.array_length, i32_const 1, i32_sub, call moonbit.check_range
   136,445  local_get 49, local_get 50, i32_add, i32_load8_u        ;; ここが本当の読み
   ;; moonbit.check_range（11 命令）: local_get 0, local_get 2, i32_le_s, if, local_get 0, local_get 1, i32_ge_s, if, else, unreachable, end ...
   ;; moonbit.array_length（5 命令）: local_get 0, i32_const 4, i32_sub, i32_load, end
   ```

   moon-wasm-opt `-O2`（今の「MoonBit+wasm-opt」）は `check_range`・`array_length` のどちらもインライン化しない（54 万回の呼び出しが残る）。`-O3` ならインライン化される
2. **moonc の出力そのものが冗長**: `--release` でも、(a) `local_set x` → `local_get x` の写しを消さない、(b) Unit の値を `i32_const 0` → `drop` で作って捨てる、(c) `extern "wasm"` の関数（`store_i32` など）と小さな `pub fn`（`sys.address`）をインライン化しない、(d) `self.buf` をループのたびに構造体から読み直す。範囲検査を外した shell_fast でも、空白で区切るループは 1 バイト約 38 命令（Zig の改行を探すループは 19 命令）。同じものに wasm-opt `-O3` をかけると 1.47 倍 → 1.22 倍になり、この分は wasm-opt で消せる
3. **語ごとの確保**: 語の範囲を `Array[(Int, Int)]` に `push` するとタプルを 1 つずつ確保し、行は `(Int, Int)?` で返す。TLSF の `malloc`・`free` が約 7,000 回で約 350 万（11%）。Zig は入力のバッファを指すスライスで、確保しない
4. **1 バイトずつの写し**: `for j in 0..<len { out[n+1+j] = buf[s+j] }` は 1 バイトに範囲検査 2 回。Zig は `@memcpy`（`memory.copy`）
5. **起動のアロケーターの初期化**: 上の「起動」。`moonc link-core -allocator tlsf-mbt`（MoonBit で書いた TLSF。moon.pkg の `link.wasm.flags` で渡せる）にすると start 関数は 9,829 → 4,740 になる
6. **ls は小さな関数の呼び出しが多い**: `u32_at` は検査つきの 4 バイトの読み。システムコールの包み（`write_all_at` → `address` → `raw_address`、`store_i32` → `inline$` の 2 段）はどれもインライン化されない。`-O3` で `write_all_at` は 1 回 53（Zig は 51）まで縮む
7. **原因ではなかったもの**: String（UTF-16）とバイト列の変換は、このプログラムでは起きない（String を使わず、`Bytes` のリテラルと `FixedArray[Byte]` だけ。内訳に String の関数は出ない）。参照カウントは元の shell では 0.8% だけ（ビューを使うと増える。下記）。SIMD（`blit` の `v128`）は燃料には効かない（命令の数で数えるため。時間はその関数がインタプリタになる分だけ遅れるが、下の表の時間では問題にならない）

## 試した変更

燃料は普通のリリースのビルド（`moon build --release`）と、それに `moon-wasm-opt -all -O3` をかけたもの。Zig 比は Zig の燃料に対する倍率（shell 6,843,964、ls 30,736）。時間はランタイムコンパイラでの `_start` の実行（5 回の中央値。目安。Zig は shell 7.45 ms、ls 0.46 ms）。どの版も出力は Zig と同じ（SHA-256 で確かめた）

### shell（2,000 行）

| 版 | 変えたこと | 書き方 | 燃料（そのまま） | Zig 比 | 燃料（+ -O3） | Zig 比 | 時間（+ -O3） |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |
| 元の shell | - | 自然 | 30,845,543 | 4.51 | 22,227,027 | 3.25 | 20.24 ms |
| shell_unsafe | 添字を全部 `unsafe_get`・`unsafe_set` にしただけ | 特殊 | 16,184,489 | 2.36 | 13,854,885 | 2.02 | 16.77 ms |
| shell_blit | 語の範囲を `FixedArray[Int]` 2 本に、行は構造体のフィールドに（確保をなくす）。写しは `FixedArray::blit_to`。添字は普通の `buf[i]` | 自然 | 17,508,129 | 2.56 | 12,305,450 | 1.80 | 12.35 ms |
| shell_iter | shell_blit + 改行は `ArrayView::search`、空白で区切るのはビューの `for i, c in buf[from:to]` | 自然 | 12,397,417 | 1.81 | 10,483,885 | 1.53 | 9.59 ms |
| **shell_natural** | shell_blit + 改行を探すのも空白で区切るのもビューの for-in | **自然** | **11,815,182** | **1.73** | **10,060,270** | **1.47** | 8.74 ms |
| shell_fast | shell_blit + 1 バイトずつ見る 2 つのループだけ `unsafe_get` | やや特殊 | 10,080,105 | 1.47 | 8,318,937 | 1.22 | 10.61 ms |

- shell_natural に wasm-opt `-O4` をかけると 9,222,346（1.35 倍。名前つきのビルド）。`-tnh`（トラップが起きないと仮定させて範囲検査を消す。参考）は 9,488,003（1.39 倍）で、for-in にした後は範囲検査はほとんど残っていない
- `-allocator tlsf-mbt` でリンクすると、どれも起動の分（約 5,100）だけ減る（shell では誤差）

### ls（200 ファイル）

| 版 | 変えたこと | 書き方 | 燃料（そのまま） | Zig 比 | 燃料（+ -O3） | Zig 比 | 時間（+ -O3） |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |
| 元の ls | - | 自然 | 198,613 | 6.46 | 112,675 | 3.67 | 0.53 ms |
| ls_unsafe | `u32_at` の 4 バイトを `unsafe_get` に | 特殊 | 133,165 | 4.33 | 73,285 | 2.38 | 0.38 ms |
| ls_pattern | dirent の頭をビットストリングのパターン `buf[i:used] is [u64le(next), u64le(_), u32le(len), ..]` で読む | 自然 | 133,569 | 4.35 | 81,769 | 2.66 | 0.80 ms |
| ls_pattern_mbt | ls_pattern + `-allocator tlsf-mbt` | 自然 | 127,931 | 4.16 | 75,539 | 2.46 | 0.58 ms |
| ls_load | dirent の整数を `extern "wasm"` の `i32.load` で線形メモリから直接読む | C に近い | 110,743 | 3.60 | 54,701 | 1.78 | 0.35 ms |
| ls_load_mbt | ls_load + `-allocator tlsf-mbt` | C に近い | 105,105 | 3.42 | 48,471 | 1.58 | 0.41 ms |

- ビットストリングのパターンは、整数の読みそのものは `i64.load` 1 つになる（速い）が、dirent ごとにビュー（`clamped_view`）を作り、その参照カウント（`incref` と `decref` 3 回）とはみ出しの切り詰め（`select` 5 つ）で 1 項目に約 165 命令かかる
- ls は全体が小さい（Zig で 3 万）ので、起動の 5,000〜10,000 とシステムコールの包みの分が倍率に大きく効く

### 起動（kmem）

| 版 | 燃料（そのまま） | 燃料（+ -O3） | うち start 関数 |
| --- | ---: | ---: | ---: |
| Zig | 74 | - | 0 |
| 元の kmem | 11,839 | 11,414 | 9,829 |
| kmem_mbt（`-allocator tlsf-mbt`） | 6,842 | 6,111 | 4,740 |

## まとめと見立て

- **shell（計算の重いループ）は、自然な MoonBit の書き方で Zig の 2 倍以内に入る**。確保を避け（`FixedArray` と構造体のフィールド）、写しは `blit_to`、1 バイトずつ見るのは添字ではなくビューの for-in にした shell_natural で、そのままで **1.73 倍**、wasm-opt `-O3` で **1.47 倍**（`-O4` で 1.35 倍）。どれも標準ライブラリの普通の道具で、`unsafe_*` も `extern "wasm"` も使わない。`unsafe_get` を使ってよいなら 1.47 倍 / 1.22 倍
- **ls（システムコールが多く、全体が小さいもの）は、自然な書き方では 2 倍に届かない**。ビットストリングのパターン + tlsf-mbt + `-O3` で 2.46 倍、線形メモリを直接読む C に近い書き方で 1.58 倍。ただし燃料の絶対量は 5〜13 万（1 tick 100 万の 5〜13%）で、どの版も 1 tick に収まる
- **起動は 1 プロセス約 1 万（tlsf-mbt で約 6,000）**。Zig の 74 には届かないが、1 tick の約 1%
- **残る差は MoonBit のコード生成とランタイムに由来する**と見る。根拠: (1) 範囲検査が関数呼び出しになる（moonc は `check_range` を出し、インライン化しない）、(2) moonc の出力は写しの伝播・小さな関数のインライン化をしておらず、同じソースに wasm-opt `-O3` をかけるだけで shell_natural が 1.73 → 1.47 倍、元の shell が 4.51 → 3.25 倍に縮む（この分はコード生成の冗長さ）、(3) ビューは参照カウント（`incref`・`decref`）とはみ出しの切り詰めを伴う（ランタイムの設計。shell_natural で約 70 万 = 6%）、(4) 起動の TLSF の初期化。アルゴリズムの差ではない（どの版も Zig と同じ手順・同じシステムコール）
- **MoonBit を選ぶときに要るもの**（2 倍を満たすため）: (a) OS のコードを「確保しない・for-in と `blit_to`」の書き方でそろえる（コーディング規約にする）、(b) ビルドで moon-wasm-opt を `-O3` 以上でかける（今の PoC の `-O2` では範囲検査の呼び出しが残る）、(c) 必要なら `-allocator tlsf-mbt`。素直に `Array` とタプルと添字で書くと 4〜6 倍になる
