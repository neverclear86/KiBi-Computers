# 0329: p1 のコマンドを読み込み時の変換で待機点型にする

- 状態: 採用
- Issue: #329（task）。前提: ADR 0316（方式の選択。PoC は `poc/threadless-wait/`）、0028（燃料の計測）、0030（待機点での保存）、0043（カーネル）、0072（`poll_oneoff`）
- 後続: #81・#82（保存。退避した値などの書き出す形）、#251（仮の値の確定）、#338（変換の速さの改善）、#339（燃料切れの待ち点化。下の節）
- 番号は Issue 番号にそろえている

## 決めたこと（要約）

- `Machine.exec` は、読み込むときに待機点への変換（`core.runtime.asyncify.Asyncify`、`internal`）をかける。順番は「構造の上限の確認 → 燃料の計測コードの挿入 → 変換 → パースと検証」で（#341 で入れ替えた。下の「#341 での変更」）、1つの `LoadDeadline`（#40）で全体を測る。順番は `core.runtime.Instrumentation` の1か所で決める
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
- 燃料切れの待ち（`kibi.fuel`）を待ち点にすること（ADR 0316 の最後の項目）。#339 で、止まりうる関数の中の燃料切れを待ち点にした（下の「燃料切れの待ち点化」）。葉の関数の中の燃料切れは、今もスレッドを持ったまま待つ
- カーネルロックを持たない呼び出しの待ち（`callOnMainThread`・`awaitHost`）は今のまま。p1 からは今のところ使わない
- 型・import・グローバル・export のセクションのどれかがないモジュールは、変換せずに `BLOCKING` で動かす（普通の C の CLI はどれも持つ）。#341 で燃料の計測コードを先に挿入するようにしたので、`exec` では挿入がこの4つを必ず作り、この理由で変換できないことはなくなった
- ランタイムコンパイラでの速さ（#37）、Rust の CLI（手元に `wasm32-wasip1` のターゲットがない）は確かめていない

## #341 での変更（挿入と変換の順番）

変換の後に燃料の計測コードを挿入すると、変換で足した細かい `if`（ランと呼び出しを包むもの）の分だけ燃料のセグメントが増え、lua.wasm の `luaV_execute` が JVM のメソッドの上限（64 KB）を超えた。ASM は上限超えを見分けられずに落ち、例外処理を使うモジュールなのでモジュール全体がインタプリタで動いていた（#37）。

順番を「挿入 → 変換」に入れ替えた。

| lua.wasm | 変換 → 挿入（前） | 挿入 → 変換（今） |
| --- | ---: | ---: |
| `luaV_execute` の WASM の命令の数 | 29,148 | 21,356 |
| 生成した JVM のメソッドの大きさ | 上限超え（コンパイルできない） | 49,890 バイト（上限の 76%） |
| モジュールの大きさ | 1,070 KB | 737 KB |
| ランタイムコンパイラ | 使えない（全体をインタプリタ） | 使える |

（参考: 挿入だけで 15,761 命令・34,707 バイト。変換だけで 15,164 命令）

この順の性質:

- 燃料を引くコードは、変換で包んだ `if (state == NORMAL)` の中に入るので、巻き直しの間は引かない。変換が足したコード（退避・復元・状態の確かめ）も数えない。数えるのは元のプログラムの命令だけになった（前は巻き直しのたびに、通ったコードの分を引いていた）
- 燃料の残りを写したローカル変数（ADR 0028）も、ほかのローカルと同じく退避・復元される。巻き直しで戻る値は古いが、使う前（呼び出しから戻った直後）にグローバルから読み直すので、古い値で数えることはない
- 燃料切れの import（`kibi.fuel`）は、変換のときには普通の import として見える。止まりうる import に加えれば待ち点にできる（#339）
- 識別子（`ModuleHash`）は書き換えた後のバイト列から作るので、同じプログラムでも前と値が変わる（v0.1.0 の前なので、互換は考えない）

`EndiveRuntime` が、例外処理を使うモジュールでインタプリタとコンパイルしたコードを関数ごとに混ぜない作り（#37）は残した（Endive の不具合と思われる。報告は別の Issue）。

## 燃料切れの待ち点化（#339）

step の途中で燃料が尽きたときも、止まりうる関数の中なら巻き戻して待ち点にし、燃料を待つ間ワーカーのスレッドを持たない。
公開 API（`pro.ikili.kibicomputers.api`）・p1 の ABI・保存のバイト形式は変えない。

### 決めたこと

- **燃料切れの import は、止まりうる関数の判定には使わない**。燃料の確かめ（`if (fuel < 0) fuel = kibi.fuel(fuel)`）はすべての関数の入口と loop の先頭にあるので、
  判定に使うと全関数が変換されてしまう。止まりうる関数の集合は今と同じ（`fd_read`・`fd_write`・`poll_oneoff` から決まる。lua.wasm で 567 / 839）
- **止まりうる関数の中の `call kibi.fuel` だけを待ち点にし、呼び先を新しい予約 import `kibi.async_fuel`（型は `kibi.fuel` と同じ `(i64) -> (i64)`）にする**
  （`Asyncify.transform` の `yieldImport`）。ホストは、どちらの import が呼ばれたかで巻き戻せるかを知る。葉の関数の `kibi.fuel` はそのまま残し、今と同じくスレッドを持ったまま次の tick を待つ（`WAITING_FUEL`）
- 挿入 → 変換の順（#341）なので、変換は挿入された `kibi.fuel` の呼び出しをそのまま見られる。燃料の確かめの `if` の条件は変換が一時ローカルに入れるので、巻き直しで同じ枝に入る。
  止まった `kibi.async_fuel` の呼び出しは、巻き直しで着いたときに新しい残り（step が渡した grant）を返し、挿入したコードがそれを残りのローカル変数（`try_table` を含む関数ではグローバル）に入れる
- **2段の変換**（`Instrumentation.applyDetailed`）: 燃料切れも待ち点にする（段1）→ 変換できない作りで断られたら、同じ挿入の結果を燃料切れを待ち点にせずに変換する（段2。#329 と同じ）→ それも断られたら、段2の理由で `exec` が断る（#367）。
  段1だけで断られうるのは、loop の先頭の燃料の確かめが待ち点になるため、待ち点を含まなかった「引数を持つ loop」や「複数の結果を持つブロック」が止まりうる構造になるとき。
  段1で断った理由は `Program.fuelYieldRejection` に残す（`Program.fuelYield` は段1で読み込んだか）
- **巻き戻したプロセスは `RUNNABLE`・スタックなしで、燃料待ちの列（スレッドを持たない側）に入る**（`StepResult.WaitFuel`）。`WAITING_FUEL` は「スレッドを持ったまま燃料を待つ」の意味のまま残す。
  イベントの後に燃料がないとき（#329 の `NO_FUEL`）と同じ形なので、待機点の判定（`Machine.isAtWaitPointLocked`）と一時停止・停止の仕組みにそのまま乗る
- **巻き戻し・巻き直しの費用**: 燃料が切れるたび（ふつう tick に1回）に巻き戻す。退避した値の数 × `FuelCosts.ASYNC_VALUE`（仮の値 2。確定は #251）を、巻き戻した時点でマシンの燃料から引く（借りになり、後の補充で返す）。
  再開のときの grant から引かないのは、費用が grant より大きいと、巻き直してすぐにまた切れて先へ進めないため。巻き直しの間は燃料を引かないので、grant が小さくても止まった呼び出しまで着く
- **変換が足すコードの燃料は、今と同じく数えない**（#341 のまま）。プランでは、変換が NORMAL の道筋で毎回実行する命令（入口の確かめ、ランの包み、呼び出しの包み、引数・結果の一時ローカルの出し入れ）を数えて、
  NORMAL のときだけ通るところ（入口の `else`、ランの終わり）で引く形を作って測った。燃料 1 あたりの CPU 時間をそろえるのが目的だったが、結果は逆になった（下の表）。
  数えた分は元の命令の 0.8〜2.2 倍になり、引くコードそのものも重く、Lua の数値のループは 7 倍遅く、燃料 1 あたりの時間も 3.9 → 8.5 ns に増えた。TPS を最優先にすると、数えない今の形のほうがよい。
  燃料の重みで変換の分を見込むこと（止まりうる関数のセグメントの重みを上げるなど）は、#251 の確定と合わせて別に検討する

### カーネルの中の取り決め（メモリの上。書き出す形は #81・#82）

| もの | 内容 |
| --- | --- |
| `WasmAsync.FUEL`（`kibi.async_fuel`） | 止まりうる関数の中の燃料切れ。ランタイムは `WasmFuelHandler.onFuelExhaustedUnwindable` につなぐ。燃料を計測し、変換もしたモジュールだけが持てる（そうでなければ `WasmLoadException`）。`imports` には出さない |
| `WasmFuelHandler.onFuelExhaustedUnwindable` | 既定は `onFuelExhausted` と同じ（巻き戻さない）。カーネルは、巻き戻せる（`WAIT_POINT`・作っている途中でない）なら状態を `UNWINDING` にして戻る。巻き直し（`REWINDING`）で呼ばれたら `NORMAL` に戻して今の手持ちを返す |
| `StepResult.WaitFuel` | ドライバーが `_start` から `UNWINDING` で戻ったとき、`ProcessContext.takeFuelUnwind()` が真なら返す。補充されたら `resume(ctx, emptyList())` で巻き直す |
| 燃料待ちで巻き戻したプロセス | `state = RUNNABLE`、`Process.wait = null`、`Process.asyncWait = null`、`Process.asyncStack` が空でない、状態のグローバルは `NORMAL`、燃料のグローバルは 0 |

- 「`wait` がなく、退避した値がある」なら燃料待ちから巻き直して再開する、という不変条件にした（印を `Process` に足さないので、保存する値も増えない）。#81・#82 で復元するときは、燃料待ちの列に入れ直せばよい
- `ProcessRuntime.fuelUnwound`・`fuelUnwindRemaining`・`rewindingForFuel` は実行時だけの印（step の中でだけ立ち、待っている間はすべて消えている）
- 数え口（テスト・測定用）: `MachineScheduler.fuelUnwinds`（巻き戻した燃料切れ）、`fuelBlockingWaits`（スレッドを持って待った燃料切れ）

### 測ったこと（#339）

環境: Windows 10 / x86_64、Java 17（Gradle のテスト）、ランタイムコンパイラ。

| lua.wasm | 今まで（#341） | 燃料切れも待ち点（今） | 参考: 変換の分も数える形（やめた） |
| --- | ---: | ---: | ---: |
| 待ち点にした燃料切れの呼び出し | 0 | 1,093（止まりうる呼び出し 4,293 のうち） | 1,093 |
| モジュールの大きさ | 739 KB | 814 KB | 919 KB |
| `luaV_execute` の WASM の命令の数 | 21,356 | 21,827 | 24,992 |
| ランタイムコンパイラ | 使える | 使える | 使える |
| 数値のループ（100 万回）の時間 | 2,375 ms | 2,381 ms | 16,558 ms |
| `table.sort`（20 万要素）の時間 | 3,730 ms | 3,749 ms | 8,171 ms |

- 待たない計算の遅さは、今までと変わらない（測った差は 1% 未満）
- Lua の計算（数値のループ・`table.sort`・文字列の処理。`fuelPerTick` 50,000）で、燃料切れ 530 回のうち 490 回（92%）を巻き戻した。残りは葉の関数（止まりうる関数を呼ばない関数）の中
- 燃料を待つ間のスレッド: 0 本（`AsyncFuelTest`）
