# 0263: コンポーネントのインスタンス化とリンク

- 状態: 採用
- Issue: #263（Epic #259）。前提は ADR 0260（Component Model は `core` に自前で実装）、ADR 0262（コンポーネントの読み込みと検証）、ADR 0264（Canonical ABI の lift・lower）、ADR 0028（燃料）
- 後続: #265（resource のハンドル表）、#266（非同期・stream・future）、#267（`wasi:*` のホストのインターフェース）、#685（`kibi:*` の p2）、#270（インスタンスをまたぐ燃料の扱いの残り）、#269（コンポーネントのプロセスの保存）、#251（仮の値の確定）、コンポーネントの exec（#687）
- 番号は Issue 番号にそろえている
- 追記（#685）: `KernelDrivers.COMPONENT_HOST` は合成ホスト `KernelComponentHost`（`wasi:*` → `WasiP2Host`、`kibi:*` → `KibiP2Host`）になった（ADR 0685）

## 背景

#262 でコンポーネントを読み込んで検証でき、#264 で値の受け渡し（lift・lower）の部品ができた。#263 では、中の core インスタンスを作り、インスタンスどうしの import・export と、ホスト（カーネル）が出すインターフェースをつなぐ。

## 決定

### 2 段に分ける: 計画（読み込みのとき）と、インスタンス化（プロセスのスレッド）

- **計画（`ComponentPlanner` → `ComponentPlan`）**: コンパイル用のスレッドで 1 回だけ作る不変のもの。一番外のコンポーネントから宣言の順に添字空間を作りながらなぞり、入れ子のコンポーネントのインスタンス化はその場で平らに展開する（展開の数は ADR 0262 の `ComponentExpansion` が上限で抑えてある）。結果は「作る core インスタンスの並び（モジュールと、import ごとに渡すもの）」「canon lift・lower の表」「一番外の export」。型（resource）はインスタンス化・import のたびに新しく作る（生成的）。
- **インスタンス化（`LinkedComponent`）**: 計画のとおりに core インスタンスを順に作り、import に「前に作ったインスタンスの export（`WasmExtern`）」か「canon lower で作ったホストの関数」を渡す。プロセスごとの状態（インスタンス、`may_leave`、トラップしたか）だけを持つ。

計画は不変なので、exec につないだとき（下の「exec」）に `ProgramCache` で全マシンが共有できる。インスタンス化はプロセスのスレッドで、読み込みの重い処理（検証・挿入・コンパイル・型の解決）を含まない。

### 読み込み（`ComponentProgram.load`）

「ADR 0262 の検証 → 中の core モジュールをランタイムに読み込ませる → 計画」の順。全体で 1 つの時間の上限（`ComponentLimits.moduleLimits` の 8 秒。検証器の初回の初期化は測る前に済ませる）を守る。

- **core モジュールは、コンポーネントの中の元のバイト列を Endive に渡す。** wasm-tools の検証は関数の本体を除いた写し（ADR 0262 の `ValidationImage`）にしか当てていないので、本体の検証、core モジュールのカスタム セクションの壊れ（名前が UTF-8 でない・長さがあふれる）、コードのセクションの後ろの余り・本体の `end` の後ろの余りは、Endive のパーサーが断る（燃料の計測コードを挿入するときも、挿入の前に元のバイト列を Endive のパーサーで読む）。テスト（`ComponentLinkTest`）で、手で作ったバイト列で確かめた。
- **同じ中身の core モジュールは 1 つにまとめる**（読み込み・コンパイルは 1 回。インスタンスは別に作る）。
- **core モジュールの同じ (module, name) の import は断る**（#690）。コンポーネントの中の core モジュールは同じ import を 2 回書けない（Component Model の検証の規則。wasm-tools が `duplicate import name` で断る）。計画でも、種類・型を確かめた後に重なりを確かめて `WasmLoadException` にする（検証を差し替えて通しても、インスタンス化の `IllegalArgumentException` で CRASHED にならないための守り）。インスタンス化の設定（`WasmInstanceConfig`）の、ほかのインスタンスの export の鍵は `(module, name)` の組にする（文字列をつなぐと、U+0000 を含む別々の名前が重なる）。
- 失敗は `WasmLoadException`（`ProgramCache` が覚えてよい）。検証の途中の割り込みは ADR 0262 のまま `WasmTrapException`（`INTERRUPTED`）で、読み込みの失敗として覚えない。

### ホスト（`ComponentHost`）

一番外のコンポーネントの import（インターフェースの名前）を `ComponentHost.interfaceOf(name)` で引く。ゲストのインスタンス型は、ホストのインターフェースの部分型でなければならない（ゲストが宣言した関数・resource がすべてあり、関数の型が同じ形。ホストにだけあるものは見ない）。合わなければ `WasmLoadException`。resource は、ゲストの resource → ホストの resource の識別子の対応を作って比べる。

どの名前・どの版を受け付けるかはシステムコールの ABI なので、#263 では口だけを作り、実物の一覧は `wasi:*` が ADR 0267（`WasiP2Host`）、`kibi:*` が #685 で決める。`HostInterface.fallback` は、まだ実装していない関数を「呼ぶとトラップ」にしてリンクを通すとき（と、テストでゲストの宣言に合わせたホストを作るとき）に使う。

### canon と、仕様の約束

`definitions.py`（コミット `dd1fefc0c618382359feca6f2c758a2b3ef21c36`）の同期の呼び出しに従う。

- lower した import をゲストが呼んだら: 呼ぶ側のコンポーネントのインスタンスの `may_leave` が下りていればトラップ → 引数を lift → 呼ばれる側（ホストの関数、または lift した関数）を呼ぶ → 結果を lower。
- lift した関数を呼んだら: 引数を lower（必要なら realloc）→ core の関数を呼ぶ → 結果を lift → **呼ぶ側に結果を渡し終えてから**（ホストなら値として返す・ゲストどうしなら呼ぶ側のメモリに lower してから）post-return。
- realloc と post-return は `may_leave` を下ろして、`LinkEnv.callGuest`（カーネルでは `ProcessContext.callGuest`。中では止まる口を使えない。ADR 0073）の中で呼ぶ。コンポーネントどうしの呼び出し（ゲストからゲスト）は `callGuest` で包まない。
- 一度トラップしたら、それ以上は呼べない（仕様ではトラップしたストアは使えなくなる。以後の呼び出しはトラップ）。
- ホストからの `LinkedComponent.call` は入れ子にできない（#691）。ホストの関数の中・インスタンス化の途中（start 関数から呼ばれたホストの関数）から呼ぶと `IllegalStateException`。ホストの関数の中から呼べると、core の関数が `callGuest` で包まれずに動き、止まる口を使えてしまう（ADR 0073）。断るのは呼び出しの前なので、断った呼び出しはコンポーネントを壊さない（例外が外側まで伝われば、外側の呼び出しが壊す）。
- resource の canon（`resource.new`・`rep`・`drop`）と own・borrow の受け渡しは、ハンドル表（#265。ADR 0265）でつなぐ。
- async の canon・組み込み（stream・future など）、map 型などは `WasmLoadException`（#266 ほか）。

### 燃料: core インスタンスで 1 つのグローバルを共有する

コンポーネントの core モジュールには、燃料の計測コードを「共有の形」で挿入する（`FuelInjector.inject(sharedGlobal = true)`）。共有の形のモジュールは、燃料の残りのグローバルを定義せずに `kibi.fuel_global`（可変の i64）として import し、それを `kibi_fuel` として export し直す（`WasmModule.sharesFuel`）。1 つのプロセスの core インスタンスは、みな同じ `WasmSharedFuel`（Endive の `GlobalInstance` 1 つ）を import する。

- インスタンスをまたぐ呼び出しのたびに手持ちを移す（`switchFuelHolder`）形と比べ、呼び出しごとのロックと読み書きがなく、TPS（サーバーへの負荷）で有利。状態保存との相性・実装の単純さも、残りが 1 か所なので良い。
- どのインスタンスの `fuel` を読み書きしても同じ値なので、カーネルの「手持ちを持つインスタンス」（`fuelHolder`）は最初のインスタンスにしておけばよい。
- 燃料切れの処理・ホスト関数の入口の照合（`verifyInstanceLocked`）は、コンポーネントのプロセスでは「このプロセスのインスタンスのどれか」でよい（作っている途中は、作り終えていないインスタンスの start 関数からも呼ばれるので照合しない）。
- lift・lower で写した量は燃料に換算して引く: **値 1 つで 1、写したバイト 8 つで 1**（仮の値。確定は #251）。

### 追記（#270）: インスタンスをまたぐ燃料と RAM

- #270 の燃料の答えは、上の共有の形。インスタンスをまたぐたびに手持ちを移す口（`ProcessContext.switchFuelHolder`）は消した。作っている途中の入れ子の `instantiate` は許さない（core インスタンスは計画だけが作る）。
- 手持ちはプロセスで 1 つなので、一時停止の上限（手持ち ≤ スライス。ADR 0047）はコンポーネントでもそのまま成り立つ。インスタンスごとに配分を分ける形は、この上限がインスタンスの数の倍になるので採らない。
- lift・lower の燃料は呼び出しが済んでから引くので、ゲストからゲストへの呼び出しが連なると手持ちが大きく負になりうる。借りとして後の tick で返す（今までの負の手持ちと同じ扱い）。
- RAM: 線形メモリとテーブルは、どの core インスタンスの分も同じ `ProcessMemory` に数える（import して共有したものは、定義したインスタンスで 1 回だけ）。加えて、core インスタンス 1 つあたりの固定費 `CORE_INSTANCE_OVERHEAD_BYTES`（仮に 4 KiB。確定は #251）を、作る前にカーネルロックの中で 3 段に数え、プロセスの終わりにまとめて返す。Endive のインスタンスやリンクの表は線形メモリの外にあり、1 つのコンポーネントで最大 1,000 個（`maxExpandedCoreInstances`）作れるため。断られたら作れず、メモリ不足（`cannot instantiate module (out of memory, …)`）と出る。カーネルロックの中で数えるのは、別のスレッドからのプロセスの終わり（kill）と取り違えずに 1 回だけ返すため（作るたびに 1 回だけで、`memory.grow` の道はロックを使わないまま）。
- 残り: ホスト側の一時的な確保（lift した値。再入の検査 #705 がないと段ごとに積み上がる）と、線形メモリのページの表は #753。
  - 追記（#753）: 線形メモリの区画の表は、core インスタンスごとに 1 ページ 128 バイトで同じ `ProcessMemory` に数える（ADR 0048 の追記）。lift した引数は、callee の線形メモリへ写し終えたら持たないので、ゲストからゲストへの段を深くしても、ホストが同時に持つ一時的な値は 1 段分で頭打ちになる（ADR 0264「予算」の追記）。#705 には頼らない

### カーネル

- `Program.component(ComponentProgram)` でコンポーネントの `Program` を作る（`Program.module` は持たない。燃料の計測は `Program.isFuelMetered`）。
- プロセスの始まり（`ProcessRunner.load`）で、計画のとおりに core インスタンスを作る。メモリ・テーブルの上限と燃料切れの処理はインスタンスごとにカーネルが設定し、`ProcessDriver.configure` は呼ばない。作ったインスタンスは作るたびにプロセスに入れる（途中で失敗しても、プロセスの終わりで閉じる）。
- ABI 層のドライバーは `ProcessContext.component`（`LinkedComponent`）から export を呼ぶ。ホストの関数は `ComponentCall.env` を `KernelLinkEnv` として、プロセスの口（`ctx`）を使う。
- コンポーネントのプロセスは保存できないもの（`UNSAVEABLE`）として扱う（保存の形は #269）。`ctx.instantiate` は使えない。

### exec

コンポーネントを `exec` で動かす（ABI 層のドライバーを選び、`ModulePrescan` の `NOEXEC` の分岐を外す）のは、`wasi:cli/run` の版の扱い（p2 は ADR 0267 で決めた。p3 は #268）が決まってからにし、#687 で行う（検証の時間の止め方は #666 で決まった。ADR 0666）。

### 追記（#687）: exec につなぐ

- **読み込みの見分けは 1 か所。** `Program.loadExecutable` がヘッダー（`WasmBinaryHeader`）で見分け、コンポーネントは `ComponentProgram.load`、core モジュールは今までの `Program.loadMetered`・`load` で読む。`Machine.beginExec` と復元（`MachineRestore`）の `ProgramCache` の loader は、どちらもこれを使う（同じキーで別の読み方をして、片方の結果・失敗を別の読み方に返さないため）。復元で保存データがコンポーネントを指していたら、読み込めても p1 のプロセスには戻せないので冷えた起動になる。
- **キーの止まりうる import は空にそろえる。** コンポーネントは待機点への変換を使わない（`Program.cacheWaitImports`）。ドライバーの集合や `asyncifyExecutables` ごとに別のキーになって二重に読み込まないため。燃料は p1 と同じ `meterExecutables` で、キーの `metered` に入る。
- **ホストは固定で、状態を持たない。** exec のホストはサーバー全体で 1 つ（`KernelDrivers.COMPONENT_HOST`。今は `WasiP2Host`。`kibi:*`（#685）を足すときも、同じ固定の合成ホストにする）。ホストの解決は読み込みのときに 1 回だけで、計画（ホストの関数の本体・dtor を含む）は全マシンが共有するので、`ComponentHost`・`HostFunctionBody`・`HostResourceDrop` は状態を持たず、スレッド安全にする（KDoc に書いた）。プロセスの状態は `ComponentCall.env` から引く。`ProgramCache` のキーにはホストを入れない。マシンやデバイスの構成でホストを変えると、リンクの失敗（`WasmLoadException`）が覚えられて別のマシンにも返るので、デバイスがあるかどうかはリンクのときではなく呼び出しのときに見る。
- **ドライバー。** `KernelDrivers.DEFAULT` は、コンポーネントの `Program` に `WasiP2Driver`（`BLOCKING`。ADR 0267）を選ぶ（#757 からは、待機点に変換できれば `WAIT_POINT`。ADR 0757）。`wasi:cli/run@0.2.<n>` の `run` がなければ `NOEXEC`（読み込みは成功して共有したまま、ドライバーが毎回断る）。
- **割り込み。** 検証の途中でコンパイル用のスレッドが割り込まれた（`WasmTrapException` の `INTERRUPTED`。ADR 0262）ら、`Machine.loadedProgram` は `AGAIN` にする（内容の失敗ではないので `ProgramCache` も覚えない）。呼んだプロセスやマシンは壊さない。ほかの種類のトラップは来ない想定なので、内部の誤り（`IllegalStateException`）にする。
- **キャッシュの重さ。** `ProgramCache` は重さを `Program.cacheWeight` から取る（強い参照に入れるときと、弱い参照から持ち直すときの 2 か所で同じ値）。core モジュールは今までどおり読み込む前のバイト数。コンポーネントは「中の core モジュール（同じ中身は 1 つ）のランタイムに渡したバイト数 ＋ core モジュールの数 × 64 KiB ＋ 計画の項目（手順・lift・lower・resource）の数 × 256 バイト」（`ComponentProgram.weightOf`。定数は仮の値で、確定は #251）。core モジュールを最大 1,000 個持てるので、元のバイト数だけで数えると、小さな入力でコンパイル結果（Metaspace）を大量に使えるため。
- **制約（1,000 台規模）。** 待っている p2 のコマンド 1 つにつきスレッドを 1 本使う（`BLOCKING`）。上限はサーバー全体の `SchedulerConfig.maxBlockingProcesses`（既定 2048）と、1 台の `MachineConfig.maxBlockingProcesses`（仮: 8。確定は #251）の 2 つで、どちらかを超えると作れない（`ResourceLimitException`。`kibi:process` の `spawn` は `NFILE`）。1 台の上限は、少数の台（1 台は `maxProcesses` の 64 まで作れる）がサーバー全体の枠を使い切って、ほかの全台の p2 のコマンドを `NFILE` にすることを防ぐ。それでも、多くの台が 1 台の上限まで待つ p2 のコマンドを置けば全体の枠は尽きうる（2048 / 8 = 256 台）ので、規模の目標（待っているだけの台はスレッドをほぼ使わない）を満たすのは、スレッドを持たない待ち（#757）が済んでから（#757 で、`kibi:device` の `invoke`・`kibi:process` の `spawn` を使わないコマンドはスレッドを持たずに待つ。ADR 0757）。計画の共有で、ヒープとコンパイルは同じコンポーネントにつき 1 回で済む。
- 保存は今までどおり `UNSAVEABLE`（#269）。モジュールの置き場には元のバイト列を、既存の形（`<sha256>.wasm`）で書く。
- 確かめていないこと: ネイティブの `wasm32-wasip2`・`wasm32-wasip3` のツールチェーンで作ったコンポーネント（テストはアダプターで包んだもの）、製品の jar（jarJar）での検証器・リンカー・ホストのクラスの読み込み（GameTest `componentCommandLoadsOnServer` は開発環境の実行時クラスパスで、読み込み・ホストの組み立て・`run` の呼び出しまで確かめる）。この 2 件は #775 で追う。

## テスト

- `ComponentExecTest`（#687）: アダプターで包んだ p2 のコマンドを `Machine.exec` で動かす、別のマシン・別のドライバーの集合から exec しても読み込みは 1 回、`run` のないものは `NOEXEC`（読み込みは共有）、リンクできないもの（p3・ホストにない import）は `NOEXEC` で失敗を覚える、読み込みの割り込みは `AGAIN` で覚えない、重さの見積もり、p1 の `kibi:process` の `spawn` でコンポーネントの子を作る、ブロッキング型の上限（サーバー全体・1 台）で `NFILE`、共有した計画とホストを 6 台のプロセスが同時に動かす。

- `ComponentLinkTest`: ホストの関数、core インスタンスどうしのメモリ・グローバル・テーブル・関数、文字列と realloc・post-return の順、`may_leave`（realloc の中と post-return の中。post-return から core どうしで直接呼ぶのはトラップしない）、トラップの後、入れ子のコンポーネント、同じ中身のモジュールのまとめ、燃料の共有、元のバイト列の検証。インスタンスの間の受け渡し（メモリ・グローバル・テーブル・関数、文字列、入れ子）は、計測なしとありの両方で回し、計測ありでは燃料が減って、どのインスタンスから読んでも同じことも確かめる（#692）。
- `ComponentSpecTest`: 仕様の `test/values`・`test/linking` の wast（同じコミット）を `wasm-tools json-from-wast` で動かす。計測なしとあり（燃料を共有する形）の両方で動かし、計測ありで燃料が切れたトラップは `assert_trap` の成功に数えない（#692。`json-from-wast` はファイルごとに 1 回）。通らないもの（map、async、複数のメモリ）は理由と一緒に挙げる（resource は #265 で通るようにした。`test/resources` の wast も足した）。
- `ToolchainComponentTest`: wasi-libc でビルドした p1 のプログラムを wasmtime のアダプター（`gradle/wasi-adapter.properties`）で p2 のコマンドにし、リンクして `wasi:cli/run` からホストの関数まで届き、標準入出力の own を受けた先まで進むことを確かめる（#265）。
- `ComponentProcessTest`: カーネルの中で、インスタンスをまたぐ呼び出しと燃料切れでマシンを壊さない。読み込みの途中の失敗（#692）: 2 つ目の start 関数のトラップ（プロセスだけが `CRASHED`）、作る途中の燃料待ちでの kill と、インスタンスを作る間の kill（`KILLED`）。どれもマシンを壊さず、作った core インスタンスを閉じ、プロセスとマシンの RAM を返す。
- `FuelInjectorSharedTest`: 燃料を共有する形の挿入（#692）。グローバルの import があるとき・ないときの、関数の本体・export・グローバルの初期値（import を指す形と、前に定義したグローバルを指す extended-const の形）・データと要素のオフセット・要素の式の番号の付け替えを、wasm-tools の検証、Endive の読み込み、実行の結果で確かめる。パッシブの要素の式は、Endive が `table.init` で `global.get` を正しく置かない（#706）ので、wasm-tools で読み戻して確かめる。共有しない形では、今までどおりグローバルの import を断る。
- GameTest `componentLinksOnServer`（両バージョン）。
