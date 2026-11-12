# 0452: プロセスの一覧（kibi:process の list-processes）

- 状態: 採用
- Issue: #452（task。`ps` のためのシステムコール。#103 から分けた）。前提: ADR 0073（`kibi:*` の WIT と p1 の import の導き方）、0074（`kibi:process`。Q6-A で一覧を先送りした）、0046（終了待ち・親なしのゾンビ）
- 後続: #454（`ps` コマンド）、#251（仮の値の確定）、#262〜#268（p2・p3 の層）
- ABI の選択（Q1〜Q5）は、#452 のプランへのオーナーの回答（Q3 は D、ほかは A）で決めた

## 決めたこと（要約）

- 既存の `kibi:process/process@0.1.0` の `interface process` に足す（Q1-A。interface・p1 のモジュールは増やさない）
- `list-processes: func() -> list<process-info>`。`result` で包まず、待たない（Q2-A）
- `record process-info { pid: u32, parent-pid: u32, state: process-state, args: list<string> }`。`args` は argv の先頭から、個数 16・UTF-8 の合計 255 バイトまで（Q3-D。どちらも仮の値で、確定は #251）
- `variant process-state { starting, running, waiting, finished(exit-status) }`（Q4-A。終わったものだけが終わり方を持つ）
- マシンの全プロセス（自分・ゾンビ・親なしのゾンビを含む）を pid の昇順で返す（Q5-A。`kill-pid` が届く範囲と同じ）。親なしのゾンビ（`finished` かつ `parent-pid == 0`）を隠すのは `ps`（#454）の表示の役目
- メモリ量・起動時刻・CPU 時間・切り詰めの印は出さない
- 公開 API（`pro.ikili.kibicomputers.api`）と保存形式は変えない。一覧は保存済みの欄（`pid`・`parentPid`・`state`・`args`・`exitCode`・`termSignal`）だけで作る

## 状態の写し方

| カーネルの `ProcessState` | `process-state` |
| --- | --- |
| `CREATED`・`LOADING` | `starting` |
| `RUNNABLE`・`RUNNING`・`WAITING_FUEL` | `running` |
| `WAITING_EVENT`・`WAITING_HOST` | `waiting` |
| `EXITED`・`CRASHED`・`KILLED` | `finished(exit-status)` |

- 呼んだプロセス自身は、その時点で `RUNNING` なので `running`
- 終わり方は、子のハンドルで読むもの・刈り取りで返すものと同じ作り方（`ProcessObject.status`、なければ `ExitBookkeeping.statusOf`）。`termSignal` のない `KILLED`（マシンの停止）は `killed(none)`
- `KibiProcess.stateOf` は全列挙の `when` で書く（状態が増えたらコンパイルで気づく）

## args の切り詰め（ABI の意味）

- 数え方は各文字列の UTF-8 のバイト数の和（NUL・区切りは数えない）。対になっていないサロゲートは U+FFFD（3 バイト）にしてから数える（p1 の文字列の lower と同じ。`ProcessArgs.utf8Length` の数え方とは違う）
- 先頭から順に見て、入れた要素が 16 個になったら止める
- 残りの予算に収まる要素は丸ごと入れる。空文字列は残りが 0 でも入る
- 収まらない要素は、残りの予算以下に UTF-8 の文字の境目で切って入れ、そこで止める（末尾から継続バイト `10xxxxxx` を戻って、文字の先頭バイトの手前で切る）。切って空になる（残りが 0、または最初の文字が入らない）なら入れずに止める（実在しない空の引数に見えるのを避ける）
- 切ったことはゲストには分からない（印の欄を足すのは ABI の変更になるので足さない）
- 実装: UTF-8 化は `WasiUtf8.encode` の1つだけ。意味の層（`KibiProcess.truncateArgs`）が切った結果を UTF-8 の `ByteArray` で持ち、p1 の層はそれをそのまま書く（変換を2回しない。数えたバイト列と書くバイト列が同じものなので、合計 255 バイトの上限と文字の境目がずれない）
- シバンで起動したものの argv は `[インタプリタ, (シバンの引数), スクリプトの絶対パス, 元の argv[1..]]`（`Machine.loadExecutable`）。Lua のスクリプトでも、3 番目の要素でスクリプトを見分けられる

## p1 の import

- モジュール `kibi:process/process@0.1.0`、名前 `list-processes`、型 `(ret_ptr: i32) -> ()`。`cabi_realloc` が要る（export していなければ読み込みで `NOEXEC`）
- 止まらない（`WasiP1.WAIT_IMPORTS` に入らない）。`cabi_realloc` の中からも呼べる

配置（Canonical ABI。wasm32、little endian）:

| 型 | 配置 | 大きさ / align |
| --- | --- | --- |
| 結果 `list<process-info>`（retptr） | `{ ptr @0, len @4 }` | 8 / 4 |
| `process-info` | `{ pid: u32 @0, parent-pid: u32 @4, state.tag: u8 @8, state.finished: exit-status @12, args.ptr @20, args.len @24 }` | 28 / 4 |
| `process-state` | `{ tag: u8 @0, finished: exit-status @4 }`（0 starting・1 running・2 waiting・3 finished） | 12 / 4 |
| `exit-status`（既存） | `{ tag: u8 @0, exited: u32 @4 \| killed: { is_some @4, sig @5 } }` | 8 / 4 |
| `args` の要素 `string` | `{ ptr @0, len @4 }` | 8 / 4 |

- 判別子の後ろの詰め物と、使わない payload（`finished` 以外の @12..19 など）は 0 で埋める
- `exited(u32)` は 32 ビットのまま書く
- 書く順:
  1. retptr の範囲と align を確かめる（違えばトラップ。`cabi_realloc` は呼ばない）
  2. 外側の配列（align 4、28 × 件数）を `cabi_realloc` で取る
  3. 要素の順に、`args` の配列（align 4、8 × 個数）、続けてその各文字列（align 1）を取って書く
  4. retptr に (ptr, len) を書く
- 0 件・0 バイトでも `cabi_realloc` を呼ぶ（ほかの結果の lower と同じ）。大きさは `Long` で計算し、`Int` を超えるならトラップ（現実には起きない）。返した場所が範囲外・align に揃っていなければトラップ

C の宣言は `kibi_process.h` の `kibi_process_info_t`・`kibi_process_list_process_info_t`・`kibi_process_list_processes`（`_Static_assert` で配置を確かめる）。

## ロックと順序

1. カーネルロックの中で表を1回なめ、数値と不変の参照（`args`）だけを `ProcessListing` に写す（`ProcessLifecycle.listProcessesLocked`。`Process` 自体はロックの外へ出さない）
2. ロックの外で、pid の昇順に並べ替え、`args` を切り詰め、燃料を引く（`KibiProcess.list`）
3. `cabi_realloc` と書き込み（`P1Canonical.lowerProcessListResult`）

- 表は割り当ての順（`LinkedHashMap`）で、pid が `Int.MAX_VALUE` から 1 に戻ると昇順にならないので、意味の層で並べ替える。`ProcessTable` の並びは変えない（`childrenOf` などが割り当ての順を使う）。pid はどれも正の `Int` なので、`Int` の比較が u32 の昇順と一致する
- `cabi_realloc` の中でゲストが `kill-pid` などを呼んでも、返すのは呼んだ時点の写し

## 負荷・燃料・上限

- ホストの CPU は呼んだときだけ O(表の大きさ)。tick ごとの処理・スレッド・保存する状態・キャッシュは足さない。ロックはマシンごとなので、台数が増えても競合しない
- 燃料（仮の値。確定は #251）: 呼び出しごとの `WasiP1Fd.CALL_FUEL` に、件数 × `KibiProcess.PROCESS_LIST_FUEL_PER_ENTRY`（10）を足す
- 表の大きさは親なしのゾンビの刈り取りで、だいたい `maxProcesses × 2` に収まる（ADR 0046）。既定（表 128 件）で、ゲストに取らせる量は最悪でも 128 × (28 + 16 × 8 + 255) バイト ≒ 51 KiB

## p2/p3

同じ `interface process` の関数として、Component Model の lower で返す（ADR 0074 の「p2/p3 への写し方」）。切り詰め・並び・状態の写し方は ABI に依らない層（`KibiProcess.list`）が受け持つので、p2/p3 の層も同じものを使う。

## 後続（#454 への申し送り。#454 で済んだ）

- 標準 OS（Zig）の `cabi_realloc` は `os/lib/kibi/canon.zig` にまとめ、`ps` も export する（ADR 0098・0103）
- `ps` の既定の表示では親なしのゾンビ（`finished` かつ `parent-pid == 0`）を隠し、`-e`（`-A`）で出す（ADR 0103）

## 確かめ方

- `KibiProcessTest`: 10 状態の写し方、`killed(none)`、pid が巻き戻ったときの昇順、切り詰めの各場合（2・3・4 バイトの文字、空になる要素、対になっていないサロゲート）
- `P1CanonicalTest`: 配置、詰め物が 0、`cabi_realloc` の順・align・大きさ、トラップ
- `WasiP1KibiProcessTest`（`kibi_process.c` の `kp ps`・`kp ps-realloc`）: 自分・眠る子・ゾンビ・親なしのゾンビ・シバンの子が pid の昇順に出ること、止めた後の `killed`、`cabi_realloc` の中からも呼べること、待たない設定でも使えること
- `WasiP1ImportCheckTest`: `cabi_realloc` がない・型が違うと `NOEXEC`
- `KibiWitTableTest`・`KibiWitParseTest`: WIT と p1 の表がずれていないこと
