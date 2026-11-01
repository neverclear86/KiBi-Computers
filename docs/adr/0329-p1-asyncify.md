# 0329: p1 のコマンドを読み込み時の変換で待機点型にする

- 状態: 採用
- Issue: #329（task）。前提: ADR 0316（方式の選択。PoC は `poc/threadless-wait/`）、0028（燃料の計測）、0030（待機点での保存）、0043（カーネル）、0072（`poll_oneoff`）
- 後続: #81・#82（保存。退避した値などの書き出す形）、#251（仮の値の確定）、#338（変換の速さの改善）、#339（燃料切れの待ち点化）
- 番号は Issue 番号にそろえている

## 決めたこと（要約）

- `Machine.exec` は、読み込むときに待機点への変換（`core.runtime.asyncify.Asyncify`、`internal`）をかける。順番は「構造の上限の確認 → 変換 → 燃料の計測コードの挿入 → パースと検証」で、1つの `LoadDeadline`（#40）で全体を測る
- 止まりうる import は ABI 層が決める（`ProcessDriverFactory.waitImports`）。p1 は `fd_read`・`fd_write`・`poll_oneoff`（`WasiP1.WAIT_IMPORTS`）
- 変換できたモジュールは `WAIT_POINT` のプロセスとして動かす。変換できない作りのモジュールは、変換せずに今の `BLOCKING` で動かす（理由は `Program.asyncRejection`）
- 公開 API（`pro.ikili.kibicomputers.api`）・p1 の ABI・保存のバイト形式は変えない。`core.runtime` に、変換したモジュールを動かすための型を足した（下記）

## 変換したモジュールとカーネルの取り決め（プログラムには見せない）

| もの | 名前 | 型 |
| --- | --- | --- |
| 値を積む import | `kibi.async_push` | `(i64) -> ()` |
| 値を取り出す import | `kibi.async_pop` | `() -> (i64)` |
| 状態のグローバル（export） | `kibi_async_state` | 可変の i32。0 = NORMAL、1 = UNWINDING、2 = REWINDING |

- モジュール名の `kibi` は燃料（`kibi.fuel`・`kibi_fuel`）と同じ予約の空間。プログラムがこの名前を import・export していたら読み込みを断る（`NOEXEC`）。変換済みのモジュールをもう一度変換しても断る
- 1つの関数が巻き戻しで積む値の並びは PoC と同じ: 退避するローカル（i32・f32・f64 は 64 ビットに広げる。参照型は null だけで、null でなければ trap）、最後に「どの呼び出しで止まったか」の番号。内側の関数から積み、巻き直しは外側（`_start`）から取り出す
- ランタイム（`EndiveRuntime`）は燃料と同じ形で、2つの import を `WasmInstanceConfig.asyncStack` につなぎ、状態のグローバルを `WasmInstance.asyncState` で読み書きさせる。3つとも `imports`・`exports` に出さない。3つの一部しかない、型が違うモジュールは `WasmLoadException`

`core.runtime` に足した型（`explicitApi` の公開の型。他 MOD 向けの API ではない）:

- `WasmModule.isAsyncified`、`WasmInstance.asyncState`
- `enum class WasmAsyncState { NORMAL, UNWINDING, REWINDING }`
- `interface WasmAsyncStack { push(Long); pop(): Long }`（WASM の実行の中から呼ばれる。待たない・WASM を呼ばない）
- `object WasmAsync`（予約した名前と型の定数）
- `WasmInstanceConfig.asyncStack` と `Builder.asyncStack(stack)`。変換したモジュールを `null` のまま作ると `WasmLinkException`

## 動かし方

- p1 のドライバー（`WasiP1Driver`）は、`_start` から戻ったときに状態が `UNWINDING` なら `NORMAL` に戻して `StepResult.Wait(待つ key)` を返す。起こされたら状態を `REWINDING` にして `_start` を呼び直す
- 止まりうる import の入口（`WasiP1Suspend.enter`）: 状態が `REWINDING` なら、退避した値が空になっていることを確かめて（残っていれば `IllegalStateException` でそのプロセスだけ `CRASHED`）`NORMAL` に戻し、その呼び出しを最初からやり直す
- 出口（`WasiP1Suspend.unwind`）: 待つ key と `AsyncWait` を記録し、状態を `UNWINDING` にしてダミーの戻り値（errno 0）で戻る
- やり直しの扱い:
  - `fd_read`: 読む前に戻るので、もう一度読むだけ
  - `fd_write`: 1バイトも書けないときだけ巻き戻す。少しでも書けたら、書けるだけ書いてその数を返す（p1 では一部だけ書いてよい。wasi-libc は残りを書き直す）。途中まで書いた分の状態を持たない
  - `poll_oneoff`: 時計で待つときは、一時的なタイマーを開いたまま巻き戻し、`AsyncWait` に「タイマーの key」「相対の期限の基準にした単調時計と実時間の時刻」を記録する。やり直しは、記録した基準時刻で期限を計算し（呼び直すたびに相対の期限が先に延びない）、同じタイマーを引き継いで `resetWaitTimer` で期限を合わせ直す
- インスタンスを作っている途中（モジュールの start 関数の中）は巻き戻せないので、今と同じく `ctx.block` で待つ。fd に `NONBLOCK` があれば今と同じく `AGAIN`
- 退避した値が `AsyncStack.MAX_VALUES`（65,536 個 = 512 KiB。仮の値、確定は #251）を超えたら `AsyncStackOverflowException` でそのプロセスだけ `CRASHED`
- 変換したモジュールは、最初のインスタンスにしかできない（`ctx.instantiate` は断る）

## `KernelState` に持つもの（メモリの上。書き出す形は #81・#82）

- `Process.asyncStack`: 退避した値（`LongArray` と個数）。変換したプロセスだけ
- `Process.asyncWait`: 巻き戻しで待っている呼び出しを、やり直すときに要る値（待っている間だけ）。タイマーの key は参照するだけ
- `Process.waitTimers`: 開いている一時的なタイマーのオブジェクトIDの集合。**一時的なタイマーの持ち主はここだけ**（ブロッキング型の `sleep`・`poll_oneoff` も、待機点型の巻き戻しの `poll_oneoff` も）。`ProcessContext` は状態を持たず、`openWaitTimer`・`resetWaitTimer`・`closeWaitTimer` はこの集合を通す。プロセスが終わるとき（`finishLocked`）に残りを閉じる。不変条件: `asyncWait` のタイマーは `waitTimers` に入っている
- 状態のグローバルは、待っている間は常に `NORMAL`。再開が巻き直しかどうかは `asyncStack` が空かどうかで分かる
- 待っている key は今の `Process.wait`。待っている変換したプロセスは、今の待機点の判定（`WAIT_POINT`・`WAITING_EVENT`・スタックがない）をそのまま満たす

## 測ったこと

環境: Windows 10 / x86_64、Java 17（Gradle のテスト）、Endive のインタプリタ（core はまだランタイムコンパイラを使わない。#37）。

| | lua.wasm | prompt-wasi.wasm |
| --- | ---: | ---: |
| 変換した関数 / 全体 | 567 / 839 | 33 / 88 |
| 止まりうる呼び出し | 3,200 | 167 |
| コード | +80%（281 KB → 508 KB） | +38% |
| 変換の時間 | 134 ms | 14 ms |
| 読み込み全体（変換・挿入・パース・検証）。変換なし → あり | 222 → 578 ms | 29 → 62 ms |

- 待たない計算（Lua で数値のループと `table.sort`）の遅さ: インタプリタで 1.34〜1.43 倍（PoC はインタプリタで 1.16 倍、ランタイムコンパイラで 1.69 倍）。`fd_write` を止まりうる import に入れたので、PoC より変換する関数が少し多い
- 待っている間のスレッド: 0 本（テストで確かめた。`AsyncP1Test`・`LuaReplTest`）
- 読み込みが重くなった主な理由は、変換したコードに燃料の計測コードを挿入すると細かいセグメントが多く、大きさが倍になること（lua.wasm: 元 314 KB → 変換 540 KB → 挿入 1,070 KB。変換しないときは挿入で 485 KB）。
  その分ランタイムのパースと検証も遅くなる（温まった JVM で 125 → 296 ms）
- 温まっていない JVM（GameTest のサーバー）で lua.wasm を 4 台同時に読み込むと、1 台あたり CPU 時間で 2.5〜3.0 秒かかり、
  検証の時間の上限（#40。2 秒）を超えて `NOEXEC` になった。上限の仮の値を 8 秒にした（`ModuleLimits.DEFAULT_TIME_LIMIT_NANOS`。確定は #251）。
  読み込みを軽くするのは #338（変換の改善）と #37（読み込みの共有）

## 残したこと

- 変換の速さの改善（生きている一時ローカルだけを退避する、ランをまとめる、止まらない呼び出し先を外す解析）。#338
- 燃料切れの待ち（`kibi.fuel`）を待ち点にすること（ADR 0316 の最後の項目）。step の途中で燃料が尽きると、今と同じくワーカーのスレッドを持ったまま次の tick を待つ。#339
- カーネルロックを持たない呼び出しの待ち（`callOnMainThread`・`awaitHost`）は今のまま。p1 からは今のところ使わない
- 型・import・グローバル・export のセクションのどれかがないモジュールは、変換せずに `BLOCKING` で動かす（普通の C の CLI はどれも持つ）
- ランタイムコンパイラでの速さ（#37）、Rust の CLI（手元に `wasm32-wasip1` のターゲットがない）は確かめていない
