# 0075: 独自拡張 kibi:io のパイプと fd の複製

- 状態: 採用
- Issue: #75（task）。前提: ADR 0058（fd 表）、0059（パイプ）、0070（p1 の fd_*）、0072（p1 の `poll_oneoff`）、0073（`kibi:*` の WIT と p1 の import の導き方）
- 後続: #427（`kibi:io/tty`）、#74（`spawn-options.fds` で子に渡す）、#101（シェルの `|`）、#264（Canonical ABI。ADR 0264）・#685・#266（p2・p3 の層）、#116（SDK の `dup`・`dup2`・`pipe`）、#251（仮の値の確定）
- ABI の選択（p1 だけの `fd`・`pipe`、p2/p3 は仮置き、複製は p1 だけ など）は、#75 のプランへのオーナーの回答（Q1〜Q8）で決めた

## 決めたこと（要約）

- `kibi:io@0.1.0` に、p1 だけの `fd`（`dup`・`dup-to`）と `pipe`（`create`）、p2 だけの `pipe-streams`、p3 だけの `pipe-async` を置く
- p1 向けの import は ADR 0073 の規則で WIT から導く。どれも待たず、`cabi_realloc` も要らない
- p2・p3 の分（`pipe-streams`・`pipe-async`）は WIT とこの ADR だけで、実装しない。**仮置き**: interface・関数・型・`wasi:*` の版は、p2/p3 の層（#685・#266）を作るときに直してよい（v0.1.0 までは版を変えずに直す。ADR 0073 の Q5-A）
- 公開 API（`pro.ikili.kibicomputers.api`）と保存のバイト形式は変えない。足した型はすべて Kotlin の `internal`。新しく持つ状態はない（パイプの端・中身は既に `KernelState` のオブジェクト表にあり、fd 表の項目の形も同じ）

## WIT

`core/src/main/wit/kibi-io/`:

```wit
interface fd {                // p1 だけ
    use kibi:kernel/types@0.1.0.{error-code};
    dup: func(fd: u32) -> result<u32, error-code>;
    dup-to: func(fd: u32, to: u32) -> result<_, error-code>;
}

interface pipe {              // p1 だけ
    use kibi:kernel/types@0.1.0.{error-code};
    record pipe-ends { read: u32, write: u32 }
    create: func() -> result<pipe-ends, error-code>;
}

interface pipe-streams {      // p2 だけ（仮置き）
    use kibi:kernel/types@0.1.0.{error-code};
    use wasi:io/streams@0.2.3.{input-stream, output-stream};
    create: func() -> result<tuple<input-stream, output-stream>, error-code>;
}

interface pipe-async {        // p3 だけ（仮置き）
    use kibi:kernel/types@0.1.0.{error-code};
    create: func(data: stream<u8>) -> result<stream<u8>, error-code>;
}

world imports { import fd; import pipe; }        // p1（wasi:* を参照しない）
world imports-p2 { import pipe-streams; }
world imports-p3 { import pipe-async; }
```

- `dup-to` の引数は `fd`・`to`。プランの第3版では `from` だったが、`from` は WIT のキーワードで wasm-tools も wit-bindgen も読めない。`%from` で逃がすより、`dup(fd)` とそろえて改名する方が、wit-bindgen が作る C・Rust の引数名も素直になる。引数名は p1 の core の形・モジュール名・関数名に出ないので、ABI は変わらない
- `kibi:kernel/types` は複製せず参照する。`wasi:*` の WIT は `src/main/wit` に置かない（`KibiWitParseTest` はテストのリソースの最小のスタブで解決する）
- TTY（`interface tty`）は #427 で同じパッケージに足す

## p1 向けの import

| モジュール / 名前 | core の型 |
| --- | --- |
| `kibi:io/fd@0.1.0` / `dup` | `(fd: i32, ret_ptr: i32) -> ()` |
| `kibi:io/fd@0.1.0` / `dup-to` | `(fd: i32, to: i32, ret_ptr: i32) -> ()` |
| `kibi:io/pipe@0.1.0` / `create` | `(ret_ptr: i32) -> ()` |

- どれも待たないので `WasiP1.WAIT_IMPORTS` に入れない（待機点型・Asyncify の巻き戻しの対象外）
- `u32` は符号なしで読む。2^31 以上の fd は範囲外なので `bad-descriptor`
- 書き先（`ret_ptr`）が線形メモリの外・align に揃っていなければトラップ（ADR 0073 と同じ）
- 燃料（仮の値。確定は #251）: 呼び出しごとに `WasiP1Fd.CALL_FUEL`
- p1 のゲストが `kibi:io/pipe-streams@0.1.0`・`kibi:io/pipe-async@0.1.0` を import したら、表にないので `NOEXEC`

### メモリ上の配置（Canonical ABI どおり）

| 型 | 配置 | 大きさ / align |
| --- | --- | --- |
| `result<u32, error-code>`（`dup`） | `{ tag: u8 @0, ok: u32 @4 \| err: u8 @4 }` | 8 / 4（events のハンドルの結果と同じ） |
| `result<_, error-code>`（`dup-to`） | `{ tag: u8 @0, err: u8 @1 }` | 2 / 1 |
| `result<pipe-ends, error-code>`（`create`） | `{ tag: u8 @0, ok: { read: u32 @4, write: u32 @8 } \| err: u8 @4 }` | 12 / 4 |

`error-code` は判別子（p1 の errno − 1）。C の手書きの宣言は `core/src/main/wasm/common/kibi_io.h`（`_Static_assert` でオフセットを確かめる）。libc 風の `dup`・`dup2`・`pipe` は SDK #116 でこのヘッダの上に作る。

## 振る舞い

- **create**: カーネルの `FdTable.pipe()`。容量は既定（`Pipes.DEFAULT_CAPACITY`。仮の値 4096 バイト。確定は #251）。読む端は `READ`、書く端は `WRITE`、fd のフラグは 0（待つ）。両端は小さい空き番号から順。空き番号が2つなければ、何も作らずに `mfile`。待たない読み書きは既存の `fd_fdstat_set_flags` の `NONBLOCK` で。両端の `fd_fdstat_get` の種類は `UNKNOWN`（ADR 0059・0070 のまま）
- **dup**: `FdTable.dup(fd)`。同じオブジェクトを指し（ファイルの位置・ストリームの中身を共有）、権利とフラグを写す。満杯なら `mfile`
- **dup-to**: `FdTable.dup2(fd, to)`。`to` を閉じるのは `fd_close` と同じ経路（最後の参照ならパイプの端が閉じて相手が起きる。ディスクのファイルを閉じる処理は ADR 0052 のとおり `WakeSink.fileClosed` → `Vfs.releaseLater` でロックの外へ回る）。`to` がイベントキューやプロセスのハンドルでも、種類によらずただの fd として閉じる。`fd` と `to` が同じなら、開いているか確かめるだけ
- **エラー**: `BADF` → `bad-descriptor`、`MFILE` → `mfile`。`fd` が開いていない・`to` が範囲外（上限以上、2^31 以上）なら `bad-descriptor` で、`to` は閉じない。パイプの数は fd の上限で抑えられる（ADR 0059）ので `nfile` は返さない
- **POSIX との違い**: `NONBLOCK` はこのカーネルでは fd ごとの属性（ADR 0070）。`dup`・`dup-to` は写すが共有しないので、複製の後はそれぞれ別に変えられる（POSIX ではファイルの記述の属性で、複製と共有する）

## p2/p3 への写し方（仮置き）

| | p1 | p2 | p3 |
| --- | --- | --- | --- |
| 作る | `pipe.create` → 両端の fd | `pipe-streams.create` → `input-stream`・`output-stream` | `pipe-async.create(data)` → 読む端の `stream<u8>` |
| 待つ | `poll_oneoff` の `FD_READ`・`FD_WRITE` | stream の `subscribe()` | waitable-set（#266） |
| EOF | 0 バイトの読み込み | `stream-error.closed` | stream の終わり |
| 相手がいない | `pipe` | `stream-error.closed` | 渡した stream が捨てられる |
| 待たない読み書き | `NONBLOCK` → `again` | `read`・`write` は元から待たない | なし（async） |
| 複製 | `fd.dup`・`fd.dup-to` | なし | なし |
| 子に渡す | #74 の `spawn-options.fds` | #74 の `handle-source` | #74 の `handle-source` |

- どの層でも下にあるのは同じカーネルのパイプ（`Pipes.DEFAULT_CAPACITY`）。p2/p3 のハンドルはカーネルの fd 表に入らない（#74 の回答に合わせる。#74 の回答が違えばこの ADR を直す）
- `wasi:io/streams@0.2.3` の版は仮。p3 は `wasi:*` に依存しない
- p2/p3 の細かい選択は、p2/p3 の層を作るときにエージェントが決めてこの ADR に残す

## 実装

- WIT: `core/src/main/wit/kibi-io/{fd,pipe,pipe-streams,pipe-async,world}.wit`
- `core.wasi.kibi.KibiIo`（意味の部分。ABI の層に依らない）、`core.wasi.p1.P1Canonical`（`lowerUnitResult`・`lowerPipeResult`）、`WasiP1Kibi`（`IO_FD`・`IO_PIPE` の表）
- C: `core/src/main/wasm/common/kibi_io.h`

## テスト

- `KibiWitParseTest`: `src/main/wit/` の全パッケージを wasm-tools（`run.endive:wasm-tools`。`core` の testImplementation だけ。jarJar には入れない）で解析・解決する。p3 の `stream<u8>` も機能フラグなしで読めた。予約語の引数名（`from`）と存在しない型の参照が失敗することも確かめる
- `KibiWitTableTest`: p1 の表が WIT と一致、`world imports` が `wasi:*` を参照しない、`pipe-streams` が `wasi:io/streams` の `input-stream`・`output-stream` だけを使う、予約語の識別子がない
- `P1CanonicalTest`: `result<_, …>`・`result<pipe-ends, …>` の配置、書き先の誤りのトラップ
- `KibiIoTest`（カーネルだけ）: 両端の権利と番号、空きが1つ・0 のときの `mfile` とオブジェクト表、`dup` の共有と権利・フラグ、`bad-descriptor`、`dup-to` の上書き（最後の書く端を上書きすると EOF）・同じ番号・範囲外
- `WasiP1KibiIoTest`（`kibi_io.c` を Endive とカーネルで。待機点型・ブロッキング型）: 書いて読む、向きの違う使い方、`NONBLOCK` で `again`、EOF と `pipe`、`dup-to(書く端, 1)` でのリダイレクト、空の読む端を `poll_oneoff` で待ってホストの書き込みで起きる、`bad-descriptor`・`mfile`
- `WasiP1ImportCheckTest`: 型違い・版違い・表にない関数・p2/p3 の interface が `NOEXEC`

プロセスをまたぐパイプの確認は #74（`spawn-options.fds`）と #101（シェル）の後に回す。

## 決めないこと

- TTY: #427
- 子プロセスへの渡し方: #74
- p2・p3 の層の実装と、仮置きの WIT の確定: p2 は #685、p3 は #266
- 生成した束縛と libc 風の関数: SDK #116
- パイプの容量と燃料の確定: #251
