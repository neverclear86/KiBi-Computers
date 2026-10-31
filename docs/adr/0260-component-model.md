# 0260: Component Model の実装方式

- 状態: 採用
- Issue: #260（PoC）。結果を使うのは Epic #259 の各タスク（#262〜#270）と #261（p1 モジュールの扱い）
- 番号は Issue 番号にそろえている

## 背景

#257 で、v0.1 で WASI p1・p2・p3 と Component Model に対応すると決めた。中身のコア wasm は Endive で動かし、Component Model（読み込み・リンク、Canonical ABI、resource、非同期）はホスト側で扱う（#259）。その層の作り方の案は2つ。

- A: [endive-cm](https://github.com/roastedroot/endive-cm)（Endive の Component Model の試作）を使う。足りない部分は上流に貢献する
- B: `core` に自前で実装する（WIT・バイナリの解析は Endive の `wasm-tools` モジュールなどを使う）

比べる観点は、対応範囲（Canonical ABI の型、resource、p3 の非同期）と完成度、状態保存（待機点保存）と燃料計測を組み込めるか、Java 17 で動くか・依存の大きさ、保守と上流との関係。

前提: 燃料は読み込み時に WASM へ計測コードを挿入し、燃料切れは import の関数でホストに知らせる（#28）。プレイヤーの WASM は常にランタイムコンパイラで動かし、挿入・コンパイルした結果を内容のハッシュで共有する（#29）。状態は待機点で保存する（#30）。

## 測定

測定用のコードは `poc/component-model/`。本体とは別のビルドで、`./gradlew -p poc/component-model :bench:run` で結果を出す（結果は `poc/component-model/results.md`）。

- endive-cm は Maven Central に出ていない（`999-SNAPSHOT`、リリースなし）。固定したコミット（`2e541b7`、2026-10-30）のソースを取得し、各モジュールを Gradle でビルドした
- 実際に動かして確かめたこと: Java 17、同梱の Rust（`wasm32-wasip2`）のコーパス、燃料（命令ごとのコールバックと、#28 の計測コードの挿入）、ランタイムコンパイラ、待機中の中断、状態の取り出し、非同期の canon、依存の大きさ
- ソースを読んで確かめたこと: 対応している canon の種類、ハンドル表の公開範囲、上流の活動

### 結果

| 観点 | endive-cm（A）の現状 |
| --- | --- |
| Java 17 | 動く（ソースは Java 11 で書かれている） |
| p2 の実プログラム | 同梱のコーパス 11 本すべて動く（引数、環境変数、時計、ファイル、ディレクトリ、標準入出力、乱数、終了コード）。2回目以降の読み込み 11 ms、実行 2〜3 ms（hello-stdout） |
| Canonical ABI の型と resource | lift・lower と `resource.new`・`resource.rep`・`resource.drop` はある。own / borrow の検査もある |
| **p3 の非同期** | **ない。** canon の型は読めるが、リンカーが扱うのは lift・lower・resource だけ。`task.return`・`stream.new`・`waitable-set.new` を使うと `UnsupportedOperationException`。lift の `callback` も未対応 |
| ランタイムコンパイラ | 使える（`ComponentStore.withCoreInstances` でコアインスタンスの Builder を差し替えられる） |
| **燃料（#28 の方式）** | **使えない。** コアモジュールに計測コードを挿入すると、増えた import（`kibi.fuel`）をリンカーが解決できず、インスタンス化に失敗する。リンカーはコアの import をコンポーネントの instantiate の引数からだけ作り、Builder に渡した import は上書きする。命令ごとのコールバック（インタプリタだけ）なら数えて止められるが、#28 で採らなかった方式 |
| 待機中の中断 | スレッド割り込みで 0.5 ms 以内に止まる |
| **状態保存** | コアインスタンス（線形メモリ・グローバル）には手が届く。**resource のハンドル表は意図して非公開**（「ハンドルを作る・壊すのは Canonical ABI の仕事で、埋め込む側のものではない」）で、読み出しも書き戻しもできない。p3 のタスク・waitable などの状態は、そもそもまだない |
| 依存の大きさ | 本体（types・parser・canonical-abi・runtime）で約 440 KiB。WASI p2 の実装は netty（約 3.3 MiB）を引く。リンクの前提になる検証は `run.endive:wasm-tools`（約 5.4 MiB、初回 1 秒・2回目以降 40 ms 前後） |
| 完成度・保守 | 2026-06 から試作中（人のコミット約 40、主に2人）。リリースの予定は書かれていない。Windows で WIT のパッケージディレクトリを読めない不具合があり、そのままでは `wasi-p2` がビルドできなかった（測定では手元で直した） |

endive-cm のコード量（`src/main` の Java）: types 7.8k 行、parser 2.0k 行、canonical-abi 4.0k 行、runtime 7.4k 行、wasi-p2 5.7k 行、bindgen 6.0k 行。

## 決定

AGENTS.md の優先順位（TPS → 状態保存 → 単純さ → 速度）で比べ、**B（`core` に自前で実装する）** にする。

1. TPS: どちらもコアの wasm を Endive のランタイムコンパイラで動かすので、差は燃料の計測にだけ出る。A は今のままでは #28 の方式（挿入）が使えず、使えるのはインタプリタの命令ごとのコールバックだけ（#28 で、コンパイルした方式の 10〜20 倍の CPU）。上流のリンカーを変えれば A でも使えるが、それまでは B が勝つ
2. 状態保存: **はっきり B。** コンポーネントの状態は、コアインスタンスに加えて、ホスト側のハンドル表・borrow の範囲・（p3 では）タスク・waitable set・stream・future にある。A ではハンドル表が設計として非公開で、p3 の状態はまだ設計もない。保存に合わせるには上流の設計を変えてもらうか、フォークを持つことになる。B なら、最初から保存できる形で持てる
3. 単純さ: p2 の同期の部分だけなら A のほうが書く量は少ない（約 2 万行を借りられる）。ただし最も重い p3 の非同期はどちらでも新しく書く。A ではリリースのないソースを取り込み、上流の設計に合わせて書き、上流の速さに左右される
4. 速度: 測った範囲では差がない（どちらもコアは同じ Endive で動く）

Java 17 と依存の大きさはどちらでも問題にならない（A は Java 11 で書かれている。B は `core` の Kotlin）。WASI p2 の実装は、どちらの方式でもカーネルの土台（fd・イベント待ち・stream）の上に自前で作る（#259）ので、endive-cm の `wasi-p2`（netty）は使わない。

### 実装の方針（設計メモ。#262〜#270 で使う）

- **層の置き場所**: `core` の内部（`internal`）に置く。公開 API（`pro.ikili.kibicomputers.api`）には出さない。Kotlin 2.2 の API 範囲、Java 17
- **読み込みと検証（#262）**: コンポーネントのバイナリは自前のパーサーで読む（Component Model のバイナリ形式。endive-cm の parser の 2k 行程度の規模）。プレイヤーのコンポーネントは信用しないので、リンクの前に検証する。検証は Endive がリリースしている `run.endive:wasm-tools`（wasm-tools を WASM にしたもの）の `validate` を使う（endive-cm の `ComponentValidate` と同じ使い方）。重い（初回 1 秒）ので、#29 のコンパイル用のスレッドで行い、内容のハッシュで結果を共有する。jar が約 5.4 MiB 増えるので、自前の検証に置き換えるかは #262 で大きさを見て決める
- **コアモジュールは #29 の一本道に流す**: コンポーネントの中のコアモジュールを取り出し、「挿入 → パース → コンパイル → 共有」をモジュールごとに行う。燃料の import（`kibi.fuel`）とグローバルは、リンカーがコアの import に足す（#270）。コンポーネント内のどのコアインスタンスも、同じコンピューターの燃料を引く
- **ホスト側の状態は、最初から保存できる形で持つ（#265・#266・#269）**: ハンドル表、borrow の数、タスク・subtask・waitable set・stream・future は、数値の ID と明示的なデータで持ち、Java のオブジェクトの参照（ラムダ、スレッド）に状態を閉じ込めない。待機点でコアインスタンスの状態と一緒に書き出す。保存形式そのものは #30・#269 で決める（人が決める項目）
- **非同期はコールバック方式（stackless）を先に作る（#266）**: イベント待ちのたびにスタックが空になってホストに戻るので、待機点保存と相性がよい（#257）。ブロックする方式（`waitable-set.wait` を同期で呼ぶ、stackful）はスレッドを止める形になり、待機点が呼び出しの途中になるので、保存との兼ね合いを #266 で決める
- **テスト**: Component Model の仕様のテスト（[component-model の `test/`](https://github.com/WebAssembly/component-model/tree/main/test) の wast）を `core` の単体テストで流す。endive-cm がやっているのと同じ形（`cm-test-gen-plugin`）。実際のツールチェーンのコンポーネントは、endive-cm のコーパスと同じ種類のもの（Rust の `wasm32-wasip2`）をテスト用に用意する
- **endive-cm との関係**: 実装の参考と、テストの集め方の参考にする（Apache 2.0）。見つけた不具合（Windows のパス）は上流に報告する候補。上流の Component Model がリリースされ、燃料（コアの import を足せる）と状態保存（ハンドル表などの読み書き）の要件を満たすようになったら、乗り換えを考え直す

### 測っていないこと

- B の試作。B の判断は、A の測定結果（燃料・状態保存で足りないところ）と、endive-cm のコード量から見積もった実装量による
- p3 のコンポーネントの実プログラム（Rust の `wasm32-wasip3` など）。A が p3 の canon をリンクできないので、最小の WAT で確かめた
- 大きなコンポーネント（数 MB の Rust のプログラム）の読み込みとコンパイルの時間。#29 と同じく大きさにほぼ比例する見込み
- Java 21 での測定。#26 で Java 17 と 21 の差が小さいと分かっている
