# 0427: 独自拡張 kibi:io/tty（TTY のモード・大きさ・前のプロセス）

- 状態: 採用
- Issue: #427（task）。前提: ADR 0060（TTY と `TtyControl`）、0063（イベントキュー）、0073（`kibi:*` の WIT と p1 の import の導き方）、0075（`kibi:io` のパッケージ、`kibi_io.h`）
- 後続: #54（`/dev/tty`。ADR 0054 で済んだ）、#65（接続層から画面の大きさを渡す。`ScreenDevice` で済んだ）、#102（シェルの raw モード）、#116（SDK の `tcgetattr`・`tcsetattr`・`ioctl(TIOCGWINSZ)` 風の束縛）、#262〜#268（p2・p3 の層）、#251（仮の値の確定）
- ABI の選択（モードの表し方、関数の分け方、大きさと世代の返し方、前のプロセスの表し方と検査、`tty-resize` の形、p2/p3 で同じ interface を使うこと）は、#427 のプランへのオーナーの回答（Q1〜Q6 はすべて A。fd は `u32`。p2/p3 の WIT は仮置き）で決めた

## 決めたこと（要約）

- `kibi:io@0.1.0` に `interface tty` を置く。p1・p2・p3 で同じ interface（`-poll`・`-async` の版を置かない）。どの関数も待たない
- モードは `flags mode`（`TtyMode` のビットと同じ順）。大きさは `record size { columns, rows, generation }` を1回のロックで読む
- 画面の大きさが変わると、`kibi:kernel/events` のキューに種類 `tty-resize` のイベントが届く（`tty` に `subscribe` は置かない）
- 公開 API（`pro.ikili.kibicomputers.api`）と保存のバイト形式は変えない。足した型はすべて Kotlin の `internal`。モード・大きさ・世代・前のプロセスは既に `Tty` の状態にあり（ADR 0060）、イベントは `KernelState` のイベントキューに入るだけ
- p2・p3 は WIT（world の `import tty;`）とこの ADR だけで、実装しない。**仮置き**: p2/p3 での形（interface・関数・型・依存する `wasi:*` の版）は、p2/p3 の層（#262〜#268）を作るときに直してよい（v0.1.0 までは版を変えずに直す。ADR 0073 の Q5-A）。p1 の ABI は対象外

## WIT

`core/src/main/wit/kibi-io/tty.wit`:

```wit
interface tty {
    use kibi:kernel/types@0.1.0.{error-code};

    flags mode { canonical, echo, signals, cr-to-nl, nl-to-crnl }
    record size { columns: u32, rows: u32, generation: u64 }

    is-tty: func(fd: u32) -> bool;
    get-mode: func(fd: u32) -> result<mode, error-code>;
    set-mode: func(fd: u32, mode: mode) -> result<_, error-code>;
    get-size: func(fd: u32) -> result<size, error-code>;
    get-foreground: func(fd: u32) -> result<u32, error-code>;
    set-foreground: func(fd: u32, pid: u32) -> result<_, error-code>;
}
```

- `kibi-io/world.wit` の `imports`・`imports-p2`・`imports-p3` のすべてに `import tty;` を足した。`tty.wit` は `wasi:*` を参照しない
- fd はそのプロセスのカーネルの fd 番号（p1 の fd と同じ。p2/p3 では spawn で受け取った 0/1/2）
- 関数名は `get-`/`set-` の対。`is-tty` だけ isatty に合わせる。`is-tty` は `bool`（閉じた fd でも false）
- モードの全部オンが既定（cooked）、全部オフが raw（`cfmakeraw` 相当）。ビットは個別に効く（意味は ADR 0060）

## p1 向けの import

| モジュール / 名前 | core の型 |
| --- | --- |
| `kibi:io/tty@0.1.0` / `is-tty` | `(fd: i32) -> i32` |
| 〃 / `get-mode` | `(fd, ret_ptr: i32) -> ()` |
| 〃 / `set-mode` | `(fd, mode, ret_ptr: i32) -> ()` |
| 〃 / `get-size` | `(fd, ret_ptr: i32) -> ()` |
| 〃 / `get-foreground` | `(fd, ret_ptr: i32) -> ()` |
| 〃 / `set-foreground` | `(fd, pid, ret_ptr: i32) -> ()` |

- どれも待たないので `WasiP1.WAIT_IMPORTS` に入れない。可変長の結果がないので `cabi_realloc` は要らない
- 燃料（仮の値。確定は #251）: 呼び出しごとに `WasiP1Fd.CALL_FUEL`
- 型違い・表にない名前・版違いは、読み込みの検査（`WasiP1.checkImports`）で `NOEXEC`

### メモリ上の配置（Canonical ABI どおり）

| 型 | 配置 | 大きさ / align |
| --- | --- | --- |
| `flags mode`（引数） | i32 の下位 5 ビット（`canonical`=1・`echo`=2・`signals`=4・`cr-to-nl`=8・`nl-to-crnl`=16） | — |
| `result<mode, error-code>` | `{ tag: u8 @0, ok: u8 @1 \| err: u8 @1 }` | 2 / 1 |
| `result<_, error-code>` | `{ tag: u8 @0, err: u8 @1 }` | 2 / 1 |
| `result<size, error-code>` | `{ tag: u8 @0, ok: { columns: u32 @8, rows: u32 @12, generation: u64 @16 } \| err: u8 @8 }` | 24 / 8 |
| `result<u32, error-code>` | `{ tag: u8 @0, ok: u32 @4 \| err: u8 @4 }` | 8 / 4 |

`result<size>` は書く前に 24 バイトを 0 にする（使わない所に前の中身を残さない。`result<u64>` と同じ）。C の手書きの宣言は `core/src/main/wasm/common/kibi_io.h`（`KIBI_TTY_MODE_*`、`KIBI_TTY_MODE_COOKED`（0x1f）・`KIBI_TTY_MODE_RAW`（0）、`_Static_assert` でオフセットを確かめる）。

### 引数の検査

| 条件 | 返すもの |
| --- | --- |
| `fd` が 2^31 以上（符号なし）・開いていない | `bad-descriptor`（`is-tty` は false） |
| `fd` が TTY でない | `no-tty` |
| `mode` の定義外の上位ビット | 読まない（flags の lift は宣言したビットだけ）。エラーにしない |
| `pid` が 2^31 以上（符号なし） | `invalid`（fd より先に確かめる。`TtyControl.setForeground` と同じ順） |
| `ret_ptr` が線形メモリの外・align 違い | トラップ（ADR 0073） |

- fd の権利（読み・書き）は問わない（termios と同じ。ADR 0060）
- `set-foreground` の pid が生きているかは確かめない（受け取った側が確かめる。ADR 0060・0046）。v0.1 ではどのプロセスでも変えられる
- canonical から外すと、編集中の行はそのまま読める入力になる（ADR 0060）

## `tty-resize` のイベント

`Machine.resizeConsole` で `Tty.resize` が true（大きさが実際に変わった）のときだけ、同じロックの中で次を入れる:

```
event { kind: "tty-resize", source: "console", values: [ integer(columns), integer(rows), integer(generation) ] }
```

（値の case は #65 で `s64` から改名した `integer`）

- 受け取るには `event-queue.open(["tty-resize"], …)` で開き、p1 では `poll_oneoff` の `FD_READ` で待つ。`dropped` があれば `get-size` で読み直す（世代で取りこぼしがわかる）
- `source` は TTY の名前（fd 番号は子で変わるので使わない）。名前は `TtyLimits.RESIZE_EVENT_TYPE`・`TtyLimits.CONSOLE_SOURCE`
- キューがなければ捨てる（ADR 0063 の同報）。同じ大きさを渡しても入れない

## p2/p3 への写し方（仮置き）

| | p1 | p2 | p3 |
| --- | --- | --- | --- |
| 関数 | `kibi:io/tty` の 6 関数 | 同じ（同期） | 同じ（同期。`async` にしない） |
| fd | カーネルの fd 番号 | spawn で受け取った 0/1/2 | 同じ |
| raw で読む | `fd_read(0)` | `wasi:cli/stdin` の `input-stream` そのもの | `wasi:cli/stdin` の stream そのもの |
| 大きさの変更を待つ | `tty-resize` のキューを `poll_oneoff` | `tty-resize` を `kibi:kernel/events-poll` の `pollable` で | `tty-resize` を `kibi:kernel/events-async` で |

- `wasi:cli/terminal-stdin`・`terminal-stdout`・`terminal-stderr` が `some` になる条件は `is-tty(0/1/2)` と同じ
- p2/p3 の層は意味の層（`KibiTty`）を共有し、lift/lower だけが違う

## 実装

- WIT: `core/src/main/wit/kibi-io/{tty,world}.wit`
- `core.kernel.tty`: `TtyControl.sizeInfo`、`TtySizeInfo`、`TtyLimits`。`Machine.resizeConsole` が `tty-resize` を入れる
- `core.wasi.kibi.KibiTty`（意味の部分）、`core.wasi.p1.P1Canonical`（`liftFlags`・`lowerU8Result`・`lowerTtySizeResult`。`result<_>` は `lowerUnitResult`、`result<u32>` は `lowerHandleResult`）、`WasiP1Kibi`（`IO_TTY` の表）
- C: `core/src/main/wasm/common/kibi_io.h`

## テスト

- `KibiTtyTest`（カーネルだけ）: fd 0/1/2 が TTY、パイプ・閉じた fd・2^31 以上が TTY でない、モードの往復と同じ TTY のほかの fd から見えること、上位ビット、`no-tty`・`bad-descriptor`、`get-size` の大きさと世代、`set-foreground` の境界と Ctrl+C が設定した pid に届くこと
- `TtyResizeEventTest`: `resizeConsole` で `tty-resize` のキューに列・行・世代が入る、同じ大きさでは入らない、キューがなくてもよい
- `WasiP1TtyTest`（`kibi_tty.c` を Endive とカーネルで。待機点型・ブロッキング型）: raw で LF を待たずに1バイトずつ読め、Ctrl+C・DEL・CR もそのまま届く、canonical では行が確定するまで読めない、待機点型は入力を待つ間スレッドを持たない、値の読み出しと設定、エラー、`tty-resize` を `poll_oneoff` で待って起きる
- `P1CanonicalTest`: `liftFlags`、`result<mode>`・`result<size>` の配置、書き先の誤りのトラップ
- `WasiP1ImportCheckTest`: 6 関数の型違い・版違い・表にない名前が `NOEXEC`、`cabi_realloc` なしで通る
- `KibiWitTableTest`: 表と `tty.wit` の一致、3 つの world が `tty` を import、`tty.wit` が `wasi:*` を参照しない

## 決めないこと

- `/dev/tty`: #54（ADR 0054）
- 接続層から画面の大きさを渡す口（`resizeConsole` を呼ぶ側）: #65（`ScreenDevice` がつないだときに呼ぶ。済んだ）
- シェルでの raw モードの使い方: #102
- 生成した束縛と termios 風の関数: SDK #116
- p2・p3 の層の実装と、仮置きの形の確定: #262〜#268
- 燃料の確定: #251
