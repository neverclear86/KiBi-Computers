# 0088: 保存データの版の比較と、合わないときの冷えた起動

- 状態: 採用
- Issue: #88（task）。前提: ADR 0081（保存データの外枠）、ADR 0082（節の中身・変換器の版）、ADR 0083（差分）、ADR 0085（復元）、ADR 0030（同じモジュールにしか戻せない）

## 決めたこと（要約）

- **保存形式は変えない。** MOD のバージョンは外枠に既に書いている（ADR 0081）。変換器の版も `PROCESS_ABI` 節に書いている（ADR 0082）。#88 はそれを戻すときに比べるだけ
- 戻すときの前段（ADR 0085。起動用のスレッド）で、次を比べる。合わなければ冷えた起動にする
  1. 外枠の MOD のバージョン（`MachineSaveStore.readGenerationModVersion` で外枠の頭だけを読んだ値。`MachineSnapshot.modVersion` と同じもの）と今の MOD のバージョン（`MachineSaveStore.modVersion`。接続層が `ModList` から渡す）。合わなければ `ColdBootCause.MOD_VERSION`
  2. 差分の世代なら、基準の世代の MOD のバージョンも同じように比べる（`MOD_VERSION`）
  3. 計測ありのモジュールの変換器の版（`ModuleSource.instrumentationVersion`）と今の `Instrumentation.VERSION`。合わなければ `MODULE_MISMATCH`
- 冷えた起動の理由 `MOD_VERSION` は実行時だけの `ColdBootCause` に足した。保存される `ColdBootReason`（`KILLED` 節の u8）には足さない
- ログは、MOD のバージョン違いだけ1台ごとに DEBUG に下げ、サーバーの起動ごとに1回だけ INFO の要約を出す（`ColdBootLog`）

## 比べる順番

1. 世代を決める（`restorableGeneration`）。ファイルはまだ読まない
2. **MOD のバージョンを比べる。** ファイル全体を読む前に、外枠の頭だけを読んで（`MachineSaveStore.readGenerationModVersion`。ファイル全体の読み込み・CRC・節の解釈はしない）比べる。`KILLED` 節・差分の基準・`PROCESS_ABI` 節より先にする。版の違うファイルの中身は一切解釈しない（今の読み手が昔の形を正しく読める保証がないため。中身が今の形と合わなくても、理由は `UNREADABLE` でなく `MOD_VERSION` になる）。モジュールのコンパイルも始めない（MOD を更新した直後は電源の入っていた全台が当たるので、無駄なコンパイルと読み込みを全台分させない）。外枠そのものの形式の版（`FORMAT_VERSION`）が違うときは MOD のバージョンの位置が分からないので `UNREADABLE` のまま（形式の版を上げる変更は MOD の更新と一緒に出るので、そのときの1台ごとのログは許す）
3. 版が合えば、ファイル全体を読む（`readGeneration`）。読めなければ `UNREADABLE`。続いて `KILLED` 節の理由（`KILLED_SECTION`）
4. 差分の基準は、外枠の頭だけを読んでその MOD のバージョンを比べてから、全体を読む。差分と基準は同じサーバーの中で書かれる（置き場は基準をメモリにしか覚えず、再起動の後の最初の保存は全体。ADR 0083）ので本来は一致するが、安く確かめられるので比べる
5. 読み込むモジュールを知り、1つずつ読み込む。**計測ありのモジュールは、読み込みを頼む前に変換器の版を比べる。** 違えば読み込まずに `MODULE_MISMATCH` として覚え、使う終わっていないプロセスがあるときだけ冷えた起動の理由にする（ADR 0085 の「使わないモジュールで落とさない」）。計測しないモジュールの版は 0 なので比べない。読み込み直した後のハッシュの照合はそのまま残す

比べるのは前段（起動用のスレッド）の中だけ。後段（`completeRestore`）で落とすと CRASHED の道に入るので、そこでは比べない。読み込みの完了・タイマーのスレッドでも何もしない（#577）。

## 比べ方

- **文字列の完全一致。** semver を解釈してパッチ違いを許すことはしない（Issue の「合わない場合は再起動」どおり）。互換を確かめる手段がないまま、古いゲストの状態を新しいカーネルで動かすことになるため
- 両側が `"unknown"`（MOD のコンテナが見つからない・テスト）なら一致として戻す
- **版の合う古い世代を探さない。** `restorableGeneration` が選んだ世代の版が合わなければ、そこで冷えた起動にする。古い世代に戻すと、プレイヤーが既に先に進めた状態を、古い状態で蘇らせてしまう

## 開発中（MOD のバージョンが変わらない）の守り

開発中は `mod_version`（`gradle.properties`）が変わらないので、MOD のバージョンの比較ではコードの変更を捕まえられない。開発中にカーネルや変換の意味を変えたときの守りは、今までどおり次が受け持つ。**MOD のバージョンの比較に頼って、これらを上げ忘れないこと。**

- 節の版（`KERNEL_VERSION`・`P1_LAYER_VERSION` など）。知らない版は `UNREADABLE` になる。節の形や意味を変えたら必ず上げる
- `Instrumentation.VERSION`。挿入・変換の出力が変わる変更をしたら必ず上げる（ADR 0082）

## 同梱のもの（`image:os`・`lua.wasm`）が変わったとき

- **MOD を更新したとき**（バージョンが変わる）: 電源の入っていた全台が冷えた起動になるので、同梱のイメージや `lua.wasm` の中身が変わっても、古い状態と混ざらない
- **同じバージョンのとき**（開発中）: モジュールは置き場（`ModuleStore`）の元のバイト列から読み込み直すので、`lua.wasm` が変わっても、保存したメモリと整合する古いバイト列で戻る（ADR 0030 の規則 4）。同梱のイメージ（`image:os`）は今の MOD の `os.zip` で開き直すので、途中まで読んでいたファイルの中身が変わっていれば、前と後が混ざる（消えたファイルは `STALE`）。開発中だけの問題として残す

## ログと数え口

MOD を更新して初めてワールドを読み込むと、電源の入っていた全台（1,000 台規模なら 1,000 台）が `MOD_VERSION` で冷えた起動になる。1台ごとに WARNING・INFO を出すとログが埋まるので:

- `MachineRestore` の1台ごとのログは、`MOD_VERSION` のときだけ DEBUG（ほかの理由は今までどおり WARNING）
- 接続層（`ServerMachines.recordRestoreFinished`）は、`ColdBootLog.classify` で出し方を決める。`MOD_VERSION` の最初の1回は INFO の要約（台の位置は DEBUG）、2回目からは台の位置を DEBUG だけ。ほかの理由は今までどおり1台ごとに INFO。`ColdBootLog` はサーバーの起動ごとに作り直す（要約はサーバーの起動ごとに1回）
- 判定は `core` の `ColdBootLog` に置き、数え口（`Stats`）から切り離す（MC 非依存なので単体テストで確かめる。GameTest がグローバルな数え口を動かすと、同じ batch のほかのテストを乱すため）
- 数え口 `Stats.restoreVersionMismatches`（`restoreFallbacks` の内訳）を足した

## 比べた案

- **外枠か節に互換の番号を足し、パッチ更新では戻す**: MOD の更新のたびに全台が再起動するのを避けられるが、保存形式の変更になる（AGENTS.md で人に聞く項目）。今は要らないので採らない。必要になったら別の Issue で決める
- **`FORMAT_VERSION` を上げる**: 既存のファイルが全部 `UNREADABLE` になるだけで、得るものがない
- **semver を解釈して緩く比べる**: 上の「比べ方」の理由で採らない
- **後段か接続層で比べる**: 後段では CRASHED の道に入る。接続層では 1.20.1 と重複する

## 1.20.1

`platform-forge-1.20.1` には今、保存一式（`MachineSaveStore`）がない。移植するとき（#224 配下）に、`ModList` の版を同じように渡し、`ColdBootLog` も同じように使う。両方の接続層で版の文字列が同じなら、同じ core として戻る。違えば冷えた起動になり、安全側に倒れる。

## 追記（#680）: Endive の実行時ログ

#673 で `kibicomputers.*` のログは `latest.log` に流れるようになったが、Endive（WASM ランタイム）のログは対象の外だった。Endive 1.2.0 のソースを洗い出した結果、Issue の前提（Endive は `System.getLogger("endive")` で出す）は半分しか合っていなかった。

### 洗い出し（Endive 1.2.0）

| 出どころ | 出し方 | 本番で出るか | 運用者に要るか | 扱い |
| --- | --- | --- | --- | --- |
| `compiler` の `InterpreterFallback.WARN`（JVM のメソッドの上限を超えた関数をインタプリタに回した） | **`System.err.println` に直に**（`Warning: using interpreted mode for WASM function index: N`）。1.21.1 の GameTest のサーバーで確かめると、`latest.log` には出ず、プロセスの標準エラー（Gradle・サーバーのコンソールの出力）にだけ1回ごとに出た。バニラの `Bootstrap.wrapStreams` が `System.err` を log4j に流す環境なら、`[STDERR]` の INFO になる。どちらでも、運用者のログ（`latest.log`）の外か、1回ごとの行になる | 出る（大きな関数を持ち、例外処理を使わない WASM を、コンパイルするたび） | 要る（性能の苦情を調べる手がかり）。ただし1回ごとの行は要らない | `InterpreterFallback.SILENT` にし、`kibicomputers.wasm` に自分で出して間引く（下） |
| `wasi` の `WasiPreview1`（`System.getLogger("endive")`。ほぼ `tracef`、`errorf` は `fd_fdstat_set_rights` の1つ） | `System.Logger` | 出ない（本番で作るのは `ComponentValidator` だけで、黙らせるロガーを渡している） | 要らない（trace） | `endive` の根にも経路を付ける（保険。下） |
| `runtime` の `CompilerInterpreterMachine`（インタプリタの関数を呼ぶたび） | `System.err.println` | 出ない（システムプロパティ `endive.compiler.printUseOfInterpretedFunctions` のときだけ） | 要らない | そのまま |
| runtime・wasm・codegen のほかの箇所 | ロガーも標準エラーも使わない | — | — | — |

### 決めたこと

- **大きすぎる関数の自動の切り替えは `InterpreterFallback.SILENT` にする**（`EndiveRuntime.compileModule`。#931 の前の名前は `compileOrNull`）。どの関数を回したかは Endive の公開の口に出ない（`CompilerResult` は `internal` パッケージ）ので、コンパイル後のモジュール（`CompiledModule.wasmModule()`）に本体が残った関数（`EndiveRuntime.retainedFunctions`）から読む。Endive はインタプリタで動かす関数と、そこから末尾呼び出しで届く関数の本体だけを残す（`WasmModule.stripped`）ので、本当の集合と同じか、それより広い。読んだ集合は `kibicomputers.wasm` のログと、テスト用の `interpretedFunctions` に使う（今までは自動で回した分が空だった）。広がる場合（間接の末尾呼び出しがあれば、定義した関数の全部。`ModuleLimits.maxFunctions` は 100,000）があるので、ログには先頭の 16 個だけ並べて残りは個数にし（`EndiveRuntime.describeFunctions`）、理由の文言も「上限を超える関数と、そこから末尾呼び出しで届く関数」にする。インタプリタに回したことのログで関数を並べる箇所（SIMD を使う関数、やり直しで足した関数、例外処理を使うモジュールの SIMD の関数も）は、すべてこれを通すので、2回目からの DEBUG の行（`<Root level="all">` の既定では書かれる）も同じ大きさに収まる
- **ログの行でモジュールと関数を見分けられるようにする**。行の頭に、ランタイムに渡したバイト列（燃料の計測コードの挿入などの後）の SHA-256 の先頭 16 桁（`EndiveRuntime.moduleLabel`。`ProgramCache` のキーは変換の前のバイト列なので別の値だが、同じプログラムなら同じになる）を付け、関数の番号には name セクションの名前を `番号(名前)` の形で添える（Endive の `WARN` の行にあった手がかりの代わり。名前はプレイヤーが決めるので、48 文字で切り、制御文字は `?` にする）。どの台のものかは、コンパイルが中身ごとに1回で台をまたいで共有される（`ProgramCache`）ので、ランタイムの層では決まらない（exec の側で数える。下の「追記（#931）」）
- **インタプリタに回したことのログは、理由ごとに最初の1回だけ WARNING、2回目からは DEBUG**（`CompileFallbackLog`。DEBUG には何回目かを添える）。理由は「関数の型に v128」「例外処理を使うモジュールの SIMD」「SIMD を使う関数だけ」「大きすぎる関数（やり直しで足した関数・Endive が自動で回した関数）」「モジュール全体（コンパイルの失敗）」の5つ。SIMD を使う関数だけの切り替え（#424）は SIMD を使うモジュールなら毎回起きる普通の経路なので、大きすぎる関数（上の表で「運用者に要る」としたもの）とは分ける（1つにまとめると、状態が JVM 全体で1つなので、先に SIMD のモジュールが読まれたサーバーでは、大きすぎる関数の切り替えがずっと WARNING にならない）。#673 の前からあった `kibicomputers.wasm` の WARNING も同じく間引く。コンパイルは中身の SHA-256 ごとに1回（`ProgramCache`）だが、プレイヤーはバイト列を少し変えて exec し直せば何度でもコンパイルさせられるので、1回ごとの WARNING はこの節の方針（1台ごと・1件ごとの行でログを埋めない）に触れる
  - 理由の数が決まっているので、覚える数の上限は要らない（ADR 0044・0065 の 64 個の上限は、キーの数が入力しだいのときのもの）
  - JVM 全体で1つ（`EndiveRuntime` の companion）。サーバーの起動ごとには作り直さない。開発クライアントで統合サーバーを立て直すと、2回目のサーバーでは WARNING が出ず DEBUG だけになる。コンパイル結果のキャッシュも JVM 全体なので、それに合わせる
  - 数え口（`Stats`）はここでは足さない。最初の1回の後に運用者が気づく手がかり（理由ごとの回数を見る口、どの台のものか）は、下の「追記（#931）」
- **`endive` の `System.Logger` にも経路を付ける**（`CoreLogRouting.install(sink, CoreLogRouting.ENDIVE_ROOT, …)`。両バージョンの `CoreLogBridge` が `kibicomputers` と並べて取り付ける）。今は出す元がないが、Endive を上げてロガーを使う箇所が増えたときに、標準エラー（JUL の root の `ConsoleHandler`）でなく `latest.log` に出るようにする。JUL 側の `endive` のレベルは log4j の設定（`endive` とその下の `LoggerConfig`）を写す。ローダー同梱の log4j2.xml（NeoForge の fancymodloader loader 4.0.45、Forge の fmlloader 1.20.1-47.4.28）は `<Root level="all">` で `endive` 用の `LoggerConfig` がないので、既定では JUL の `endive` も `Level.ALL` になり、`isLoggable(TRACE)` は真になる。Endive が `System.Logger` を使う箇所が増えれば、TRACE の書式も作って SLF4J まで渡し、DEBUG の行は `debug.log` に出る（今は本番で出す元がないので実害はない。ログを黙らせたい箇所は、`ComponentValidator` のように `isLoggable` を false にしたロガーを渡す）。GameTest `endiveSystemLoggerReachesLog4j` は JUL と log4j の判定が一致することを見る
- `System.err` の差し替え、JUL の root の `ConsoleHandler` の付け替え、`System.LoggerFinder` の差し替え（#673 で採らないと決めた）はしない。どれもプロセス全体の状態で、MC やほかの MOD とぶつかる
- `InterpreterFallback.FAIL` にそろえて自前のやり直しだけで回す案は採らない。大きすぎる関数が多いと、やり直しの上限でモジュール全体がインタプリタになる（測っていない速度の低下）

### Endive を上げるとき

Endive を上げたら、`System.err`・`System.out`・`run.endive.log`（`System.Logger`）を使う箇所を grep し直し、この表を直す（AGENTS.md の「つまずきやすい点」にも書いた）。今回の前提の食い違いは、これをしていなかったことから起きた。

## 追記（#931）: インタプリタに回したことの数え口と、どの台のものか

#680 の後、最初の1回の WARNING を過ぎると、運用者の手がかりは `debug.log` の行だけになり、どの台のプログラムかも分からなかった。

### 決めたこと

- **理由は1つの enum にする**（`core.runtime.InterpreterReason`。#680 の `CompileFallbackLog.Reason` を移したもの。5つは同じ）。読み込みのログと、下の数え口で同じものを使う
- **インタプリタで動くコードを持つことを、読み込んだモジュールに残す**（`WasmModule.interpreterUse`。理由の集合・全体をインタプリタで動かす core モジュールの数・インタプリタで動かす関数の数）。読み込みのとき（コンパイル用のスレッド）に1回だけ決める変わらない値で、プロセスを作るたびに O(1) で読む（`retainedFunctions` は間接の末尾呼び出しがあると 100,000 個になりうるので、exec のたびには数え直さない）。モジュール全体をインタプリタにした3通り（v128 の型・例外処理と SIMD・コンパイルの失敗）も理由を残す（`compileModule` が理由を返す）。コンパイルしない設定のランタイム（テスト用）は「回した」のではないので持たない。コンポーネントは、中の core モジュールの和を `ComponentProgram` を作るときに1回だけ集める（`Program.interpreterUse`）
- **数え口は2つで、意味が違う**（`core.kernel.InterpreterCounts`）
  - **コンパイルの回数**（`CompileFallbackLog.count`。JVM 全体の累計）。プログラムの数ではない: 燃料の計測の有無・止まりうる import が違う読み込み、`ProgramCache` から外れた後の読み込み直し、復元のための読み込みは、同じプログラムでも別に数える。コンポーネントは中の core モジュールごとに数える。GameTest の起動の自己診断（`RuntimeSelfCheck.compilesTooLargeFunction`）も `TOO_LARGE_FUNCTIONS` を1つ増やす
  - **プロセスの数**（`MachineScheduler.interpretedProcessCount`。スケジューラ（サーバー）の累計）。インタプリタで動くコードを持つプログラムでプロセスを作った回数。理由が複数あるプログラム（コンポーネント）は理由ごとに数え、合計（`interpretedProcessTotal`）では1つ
  - どちらも reset しない（燃料の数え口と同じく、読む側が差を取る）。`CompileFallbackLog` は #680 のまま JVM 全体で1つで、サーバーの起動ごとに作り直さない
- **どの台かは exec の側で数える**。コンパイルは台をまたいで共有されるので、`ProgramCache` の最初に頼んだ台を使うと、相乗りした台とキャッシュに当たった台が漏れる。`Machine.spawnInternal` の引き返せない点の後（カーネルロックの中。プロセスの数の上限・MFILE などで断った spawn は数えない）と、保存からの復元を表に入れた後（`completeRestore`。exec を通らないので、ここで数えないと、サーバーを立て直した後は手がかりが 0 のままになる）で、台ごとに回数・理由の和・最後のプログラムの名前（`Program.label`。`Program.hash` の16進の先頭16桁。core モジュールなら、読み込みのログの `EndiveRuntime.moduleLabel` の先頭と同じ値になる。コンポーネントは元のバイト列の SHA-256 で、中の core モジュールのログの名前とは別）を持つ（`Machine.interpretedProcessCount`・`interpretedReasons`・`lastInterpretedProgram`）
- **台ごとの数は実行時だけで、保存しない**（保存形式を変えない。`fuelUnwindsDone`・`hostFailuresLogged` と同じ）。サーバーを立て直す・チャンクを読み直すと 0 から数え直す（復元したプロセスはまた数える）
- **台ごとのログは DEBUG だけで、`Machine` のオブジェクトにつき1理由1回**（`Machine.noteProcessProgramLocked`。この節の方針どおり、WARNING・INFO は台ごとに出さない）。シェルの `while` で外部コマンドを回しても `debug.log` は埋まらない。文には `Machine.id` とプログラムの名前を入れる。間引く状態（`Machine.interpretedReasonBits`）は台ごとの数と同じく実行時だけで、`Machine` を作り直すと消える: 電源の入れ直し・チャンクの読み直し・サーバーの立て直しのたびに新しい `Machine` になり、復元（`completeRestore`）でも数えるので、同じ台の行がまた出る。そのため行の数の上限は「台を起動・復元した回数 × 理由の数」で、台数 × 理由の数ではない（チャンクの読み込みの境目を行き来する台や、全台がインタプリタのプログラムを持つサーバーの立て直しでは、そのたびに出る）。DEBUG だけで、1回の起動・復元の中では exec を何度回しても増えないので、間引く状態を保存したり `Machine` の外に持ったりはしない
- **運用者の口は OP のコマンド**（`/kibicomputers runtime interpreter [<reason>|all [<limit>]]`。1.21.1 の `KiBiCommands`。`saves` と同じく `opPermissionLevel` とコンソールだけ）。出すのは、理由ごとのコンパイルの回数、理由ごとのプロセスの数、プロセスの数の多い上位の台（既定は 10 台。`InterpreterRanking.DEFAULT_LIMIT`。仮の値）と残りの台数。台の行には `Machine.id`・位置（ディメンションと座標）・ID（`currentIdentity`）を並べる。`Machine.id` は作るたびに振る実行時の番号で、ほかのログ（接続層は位置と ID で書く）に出ないので、DEBUG の行の「マシン N」はこのコマンドの出力で位置に結ぶ。**並べ方は理由で絞れ、台数は増やせる**: 既定（`all`）は理由を問わない回数だが、既定の経路の `SIMD_FUNCTIONS`（SIMD を使うプログラムを何度も exec する台）が 11 台以上あると、回数の少ない `TOO_LARGE_FUNCTIONS` の台が「ほか N 台」に隠れる。そこで、理由（`InterpreterReason` の名前。大文字小文字を問わない）を渡すと、その理由の回数（`Machine.interpretedProcessCount(reason)`。台ごとに理由別も持つ）で並べ、`limit`（1〜1000。目標の台数）で台数を増やせる。上位に入らない台の DEBUG の行の `Machine.id` も、`limit` を上げれば位置に結べる。空の要約（0 件）は core が空文字列で返し、接続層が lang の鍵で言語ごとの文言（`なし`・`none`）にする（core には特定の言語の文字列を持たない）。全台をなめるのはコマンドを打ったときだけで、tick ごとには見ない。集計と選び方、要約の文は core（`InterpreterCounts`・`InterpreterRanking`）、接続層は台と位置の対応だけ
- **負荷試験の出力**に、段階ごとの増分（段階に入ってから終わりまで。起動の段階のコンパイルも入るよう、測る区間ではなく段階の全体）を足した（`docs/load-test.md`）
- 台のコンソール（ゲストの TTY）には書かない。プレイヤーの画面と出力を比べるテストを変え、運用者の手がかりにもならない。ゲストから見える一覧（`kibi:process`）・WIT・公開 API（`pro.ikili.kibicomputers.api`）・コンフィグの項目も足さない
- 1.20.1（Forge）にはまだマシンもコマンドもないので、口は 1.21.1 だけ。移植（#224 配下）で同じ core の型を使う
