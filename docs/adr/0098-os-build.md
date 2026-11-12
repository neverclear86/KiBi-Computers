# 0098: 標準 OS のソース構成とビルド

- 状態: 採用
- Issue: #98（task）。前提: ADR 0032（言語は Zig）、#9（Zig のツールチェーン）、#55（同梱イメージ `ImageFileSystem`）
- 後続: #99（起動部品）、#100（`/sbin/init`）、#101〜#112（シェル・コマンド）、#36（同梱 WASM のビルド時 AOT）、#107（インストーラ）
- 番号は Issue 番号にそろえている

## 決めたこと（要約）

- ソースはリポジトリの `os/` に置く。Gradle のサブプロジェクトにはせず、`core/build.gradle.kts` がビルドして core のリソースに入れる
  （MOD の jar には core の出力がそのまま入るので、jarJar などの追加が要らない）
- プログラムは「1 ファイル = 1 コマンド」。ファイルを置けばイメージに入る（ビルドの設定を書き足さない）
- 共通ライブラリ `kibi`（`os/lib/kibi/`）は Zig のモジュールとして渡し、プログラムは `@import("kibi")` で使う
- できたものは同梱イメージ `os`（リソース `/kibicomputers/images/os.zip`、起動ディスクの識別子 `image:os`）。OS 入りのフロッピーの中身はこのイメージにする
- ビルド時 AOT は #36 で行う（#36 の範囲に「標準 OS」が入っている）。このイメージのプログラムを変換の対象に足す

## 配置

| パス | 中身 | 行き先 |
| --- | --- | --- |
| `os/lib/kibi/` | 共通ライブラリ（`kibi.zig` が根） | 各プログラムに `--dep kibi -Mkibi=...` で渡す |
| `os/bin/<名前>.zig` | コマンド | イメージの `/bin/<名前>` |
| `os/bin/<名前>/` | 大きなコマンドの分けたソース（#101 のシェル。`@import("<名前>/x.zig")`）。コマンドにはならない | — |
| `os/sbin/<名前>.zig` | 管理用のコマンド・init（#100） | イメージの `/sbin/<名前>` |
| `os/etc/` | 設定ファイル（`inittab` など。#100） | イメージの `/etc/` にそのまま |
| `os/test/<名前>.zig` | ライブラリを確かめるプログラム | core のテストのリソース `/kibicomputers/wasm/os-<名前>.wasm` |
| `compileLua` の出力（C の Lua を `zig cc` でビルドしたもの。#91） | Lua の処理系 | イメージの `/bin/lua`（#603） |

「1 ファイル = 1 コマンド」は Zig のプログラムの規則。`/bin/lua`（#603）は既存の C の Lua（#91）。ADR 0032 は標準 OS 自身のプログラムの言語の決定で、Lua の処理系（#91）はその対象外。
`os/bin/` には置かず、`zipOsImage` に明示的に足す。中身は `testboot` の `/bin/lua` と同じビルドの出力（`luaBinary` の `copySpec` を共有する）で、
同じバイト列なので `ProgramCache` のコンパイルはサーバー全体で1回に共有される（#37）。

#98 で入れたコマンドは、パイプラインを確かめるための `echo`・`true`・`false` だけ。ほかの基本コマンドは #103（ADR 0103）。

## ビルド

- `kibiWasm.zigModule`（`ZigBuildExe`。`zig build-exe`）で 1 ファイルずつ `wasm32-wasi`・`ReleaseSmall`・`-fstrip` にする。
  タスク名は `compileOs<Dir><Name>Wasm`（例: `compileOsBinEchoWasm`）
- `ZigBuildExe` に `modules`（名前 → 根のファイル）を足した。`-target`・`-O` はモジュールごとの設定なので、`-M` より前に書く
- `zigModule` の `sourceSet` に `null` を渡すと、リソースに入れない（イメージにだけ入るプログラム）
- `zipOsImage` がイメージを作る。時刻を入れず、順を固定する（同じソースから同じ zip）
- 実行ファイルの印（x ビット）は持たない。同梱イメージはどのファイルも実行できる（今の `ImageFileSystem` のまま）

## 共通ライブラリ `kibi`

Zig の標準ライブラリの高い層（`std.Io`・`std.fs`・アロケーター）は使わない。版ごとに形が変わり（Zig は 1.0 前）、大きさも増えるため。
import は自分で宣言し、構造体の配置は `comptime` で確かめる。バッファは静的に置く（.bss はゼロなので、触るまで RAM に数えない。#407）。

| ファイル | 中身 |
| --- | --- |
| `wasi.zig` | WASI p1 の生の import と型（`Subscription`・`Event`・`Filestat`・`Dirent`） |
| `errno.zig` | p1 の errno（`Errno`）と Zig のエラー（`Error`。名前は errno と同じ）、`describe`（strerror 相当） |
| `io.zig` | 読み書き・seek・close、`kibi:io/fd`（dup・dup-to）・`kibi:io/pipe` |
| `fs.zig` | パスの解決（cwd につないで `.`・`..` を畳む）、open・stat・mkdir・rmdir・unlink・rename・ディレクトリの一覧、chdir |
| `process.zig` | 引数・環境変数・終了、`kibi:process/process`（spawn・wait・kill・pid・シグナルの無視・set-cwd） |
| `tty.zig` | `kibi:io/tty`（モード・大きさ・前景） |
| `memory.zig` | `kibi:computer/memory`（remaining・commit） |
| `time.zig` | 時計と眠り（clock_time_get・poll_oneoff） |
| `cli.zig` | コマンドの小物: オプション・知らせ・まとめて書く出力・行の読み方・パス（#103） |
| `tree.zig` | ディレクトリの木を写す・消す（#103） |
| `text.zig` | 厳しい UTF-8 の読み方と、文字画面での文字の幅（全角の表は core の `CharWidth` と同じ。#105） |
| `keys.zig` | raw の TTY から読んだバイト列をキー（xterm 形式）に分ける（#105） |

細かい決めごと:

- エラーは `kibi.Error!T` で返す。独自拡張の `error-code` は「判別子 + 1 = p1 の errno」（ADR 0073）なので、同じ `Error` にする
- パスはすべて根の preopen（fd 3）からの相対にして渡す。相対パスは cwd（起動時は `PWD`。ADR 0332）につなぐ。`fs.chdir` はカーネルの cwd（`set-cwd`）とライブラリの cwd を両方変える
- `process.spawn` は、根の preopen（fd 3）を既定で子に渡す（`inherit_root`）。標準 OS のプログラムはパスを fd 3 から開くため。カーネルの規則（fd 3 以降は書いたものだけ。ADR 0045）は変えない
- `Child.wait` は `poll_oneoff` の FD_READ で待ってから `status` で受け取る（ADR 0074 の Q1-A）。待つ間は待機点になる
- 引数・環境変数は 64 KiB（カーネルの上限と同じ。#101 で 16 KiB から上げた）・4,096 個まで（足りなければ `TOOBIG`）。`spawn` に渡す引数も 4,096 個まで（`argv[0]` を含む。どちらもカーネルの上限に合わせた。#101 で 64 → 256、#501 で 256 → 4,096）。数に比例する表と `spawn` の写しは静的に置く（スタックに置くと 64 KiB のスタックをはみ出すため）。パスは 4,096 バイトまで（`NAMETOOLONG`）
- まだ出していないもの: `kibi:kernel/events`・`kibi:kernel/timers`・`kibi:computer/clock`・`get-cwd`（`cabi_realloc` の export が要る）。使うプログラムができたときに足す
  - 追記（#87）: `kibi:kernel/events` を `events.zig`（`kibi.events`）で出した。`cabi_realloc` は 8 KiB の静的な領域から切り出すだけのもので、`take` は1回に1件だけ取り出す。`kibi.events` を使うプログラム（`sh`・`nano`）だけを `--export=cabi_realloc` でビルドして `cabi_realloc` を export する（`core/build.gradle.kts` の `osEventsPrograms`。ほかのプログラムの export は `memory`・`_start` のまま。`-rdynamic` は `__stack_pointer` まで全プログラムに足すので使わない）。`keys.Reader.idle()` も足した（ADR 0087）

ABI（import の名前と形）は既存の ADR 0070〜0075・0408・0427 のまま。新しいシステムコールは足していない。

## 確かめ方

`core` の `StandardOsTest`: イメージの中身、`echo`・`true`・`false` を Endive とカーネルで動かす（待機点型になる）、
`os/test/libtest.zig` で共通ライブラリの各機能（引数・環境変数・パス・ファイル・errno・パイプ・子プロセス・TTY・RAM・時計）。

## 大きさ

`ReleaseSmall`・`-fstrip` で、`true`・`false` は 118 バイト、`echo` は約 3.5 KB（errno の説明の表を含む）、`libtest` は約 17 KB。

`/bin/lua` は 315,754 バイト（#603）。これでイメージの展開後の大きさは 338,282 → 654,036 バイト（40 → 41 項目）、`os.zip` は 167,726 → 284,800 バイトになった。

`/bin/nano` は約 31 KB（#105）。これで展開後の大きさは 654,036 → 685,480 バイト（41 → 42 項目）、`os.zip` は 284,800 → 約 299,100 バイトになった。
