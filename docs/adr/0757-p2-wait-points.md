# 0757: p2 のコンポーネントをスレッドを持たずに待たせる

- 状態: 採用
- Issue: #757（task）。前提: ADR 0263（コンポーネントのリンク）、0267（WASI p2 のホスト）、0316・0329（p1 の待機点と asyncify）、0685（kibi:* の p2）

## 決めたこと（要約）

- コンポーネントの中の core モジュールを、p1 と同じ待機点への変換（asyncify。ADR 0316・0329）で変換し、`WasiP2Driver` を待機点型（`WAIT_POINT`）で動かす。待っている p2 のコマンドはスレッドを持たない（p1 と同じく、イベントのキーとタイマーだけを持つ）。
- 巻き戻せない待ちのホストの関数（`kibi:device/bus` の `[method]device.invoke`、`kibi:process` の `[static]process.spawn`・`spawn-with-handles`）を import するコンポーネントは、変換せずに今までどおりブロッキング型（`BLOCKING`）で動かす。理由は `ComponentProgram.waitPointRejection` に残る。
- **ABI・保存形式・公開 API は変えない。** コンポーネントは今までどおり `UNSAVEABLE`。p1 の変換の出力（`Instrumentation.VERSION`）も変わらない。

## 変換

- 順番は p1 と同じく、燃料の注入 → asyncify（#341）。`ComponentProgram.load` は、計画（`ComponentPlanner.plan`）を、コンパイルの前のモジュールの形（`WasmRuntime.describe`。import・export だけ）から作り、変換した後のモジュールをコンパイルして計画に結ぶ（`Planned.link`）。変換で import・export は変わらない（検査する）。
- **止まりうる import はコンポーネント全体で決める**（`ComponentAsyncify`）。モジュールの import は、次のどれかなら止まりうる。
  - ホストの関数を lower したもので、その関数の待ち方（`HostWait`）が `NONE` でない
  - ほかのモジュールの、止まりうる export
  不動点まで回す。wit-component のアダプターの shim（自分の表を `call_indirect` で呼び、表は fixup が lower した import で埋める）のように、待ちが表を通るものがあるので、**import・export した表に対する `call_indirect` と `call_ref` は、その型が、コンポーネントのどこかの止まりうる関数の型と同じなら止まりうるとみなす**（`Asyncify.Prepared.analyze` の `externalSuspendTypes`）。
- 変換しないもの（`BLOCKING` に落とす）:
  - `HostWait.BLOCK` のホストの関数を lower している
  - 待ちが、ゲストからゲストへの呼び出し（lift した関数を lower した import）の下にある（中の lift を巻き戻すと、結果の lower・post-return の途中を残すため）
  - 止まりうる関数がゲストの resource の dtor になる
  - asyncify が扱えない形（`AsyncifyUnsupported`）
- 燃料の使い切りでの yield（`kibi.async_fuel`）は、コンポーネントには使わない。燃料はプロセスの燃料を共有し、尽きればトラップのまま（ADR 0263）。

## 巻き戻しと巻き直し

- 状態のグローバルはインスタンスごとにあるので、巻き戻し・巻き直しの状態は、変換した全インスタンスに同時に入れる（`LinkedComponent.setAsyncState`）。保存先のスタック（`AsyncStack`）はプロセスに 1 つで、全モジュールが共有する（呼び出しの入れ子の順に積み、逆の順に取るので、1 本で足りる）。
- 巻き戻せるのは、トップレベルの lift の中（`liftDepth == 1`）で、`may_leave` がすべて立っているときだけ（`LinkedComponent.canUnwind`）。巻き戻すときは、ホストの関数は結果を作らず、lower は結果を書かない（core の結果の数だけ 0 を返す）。外の lift も、結果の lift・borrow の検査・post-return をしない。
- 巻き直しはトップレベルの `run` を同じ引数で呼び直す。着いた先のホストの関数は**最初からやり直す**ので、待つ前に副作用を残さない（`HostWait.UNWIND` の約束）。lift の燃料は巻き直しのたびにもう一度かかる。
- 書き込みの途中の進み具合は、ストリームの表の項目（`StreamEntry.writeProgress`・`pendingSplice`）に残し、巻き直しで続きから書く（書いたバイトを二重に書かない・燃料を二重に取らない）。
- `poll` のタイマーは、巻き直したときに前のものを引き継ぐ（期限を延ばさない）。

## 待ちの判定（ホストの関数）

| 関数 | 待ち方 |
| --- | --- |
| `wasi:io/poll` の `poll`・`[method]pollable.block` | `UNWIND` |
| `wasi:io/streams` の `blocking-read`・`blocking-skip`・`write`・`blocking-write-and-flush`・`write-zeroes`・`blocking-write-zeroes-and-flush`・`splice`・`blocking-splice` | `UNWIND` |
| `kibi:device/bus` の `[method]device.invoke`、`kibi:process` の `[static]process.spawn`・`spawn-with-handles` | `BLOCK` |
| ほか | `NONE` |

## 制約と残り

- `BLOCK` の関数を import するコンポーネント（`kibi:device`・`kibi:process` を使うもの）は、今までどおりスレッドを 1 本持って待つ（ADR 0263 の上限）。巻き戻せるようにするのは別の Issue で扱う。
- p1 では、変換できない WASM を `NOEXEC` で断る（#364・#367）。コンポーネントでは `BLOCKING` に落とすので、扱いがそろっていない（PR の「確認してほしいこと」）。
- 保存（#269）は今までどおり扱わない。

## テスト

- `ComponentWaitPointTest`: shim の形のモジュールをまたいだ巻き戻し・巻き直し（燃料あり・なし）、`NONE` のホスト、`BLOCK` のホストで変換しない、変換しない指定、ゲストからゲストの下の待ち、変換の決定性、巻き戻しでハンドルを作らない。
- `AsyncP2Test`: プロンプトで待つ p2 のコマンドがスレッドを持たない、p1 の `BLOCKING` と出力が同じ（深い再帰・並べ替えを含む）、燃料、`poll` のタイマーの引き継ぎ、待っている間に殺したときの片付け、パイプがいっぱいのときの書き込み。
