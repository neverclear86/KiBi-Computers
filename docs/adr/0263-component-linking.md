# 0263: コンポーネントのインスタンス化とリンク

- 状態: 採用
- Issue: #263（Epic #259）。前提は ADR 0260（Component Model は `core` に自前で実装）、ADR 0262（コンポーネントの読み込みと検証）、ADR 0264（Canonical ABI の lift・lower）、ADR 0028（燃料）
- 後続: #265（resource のハンドル表）、#266（非同期・stream・future）、#267（`wasi:*` のホストのインターフェース）、#685（`kibi:*` の p2）、#270（インスタンスをまたぐ燃料の扱いの残り）、#269（コンポーネントのプロセスの保存）、#251（仮の値の確定）、コンポーネントの exec（#687）
- 番号は Issue 番号にそろえている

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

どの名前・どの版を受け付けるかはシステムコールの ABI なので、#263 では口だけを作り、実物の一覧は #267（`wasi:*`）・#685（`kibi:*`）が決める。`HostInterface.fallback` は、まだ実装していない関数を「呼ぶとトラップ」にしてリンクを通すとき（と、テストでゲストの宣言に合わせたホストを作るとき）に使う。

### canon と、仕様の約束

`definitions.py`（コミット `dd1fefc0c618382359feca6f2c758a2b3ef21c36`）の同期の呼び出しに従う。

- lower した import をゲストが呼んだら: 呼ぶ側のコンポーネントのインスタンスの `may_leave` が下りていればトラップ → 引数を lift → 呼ばれる側（ホストの関数、または lift した関数）を呼ぶ → 結果を lower。
- lift した関数を呼んだら: 引数を lower（必要なら realloc）→ core の関数を呼ぶ → 結果を lift → **呼ぶ側に結果を渡し終えてから**（ホストなら値として返す・ゲストどうしなら呼ぶ側のメモリに lower してから）post-return。
- realloc と post-return は `may_leave` を下ろして、`LinkEnv.callGuest`（カーネルでは `ProcessContext.callGuest`。中では止まる口を使えない。ADR 0073）の中で呼ぶ。コンポーネントどうしの呼び出し（ゲストからゲスト）は `callGuest` で包まない。
- 一度トラップしたら、それ以上は呼べない（仕様ではトラップしたストアは使えなくなる。以後の呼び出しはトラップ）。
- resource の canon（`resource.new`・`rep`・`drop`）は、呼ぶとトラップする関数としてつなぐ（ハンドル表は #265）。own・borrow の受け渡しも ADR 0264 のままトラップ。
- async の canon・組み込み（stream・future など）、map 型などは `WasmLoadException`（#266 ほか）。

### 燃料: core インスタンスで 1 つのグローバルを共有する

コンポーネントの core モジュールには、燃料の計測コードを「共有の形」で挿入する（`FuelInjector.inject(sharedGlobal = true)`）。共有の形のモジュールは、燃料の残りのグローバルを定義せずに `kibi.fuel_global`（可変の i64）として import し、それを `kibi_fuel` として export し直す（`WasmModule.sharesFuel`）。1 つのプロセスの core インスタンスは、みな同じ `WasmSharedFuel`（Endive の `GlobalInstance` 1 つ）を import する。

- インスタンスをまたぐ呼び出しのたびに手持ちを移す（`switchFuelHolder`）形と比べ、呼び出しごとのロックと読み書きがなく、TPS（サーバーへの負荷）で有利。状態保存との相性・実装の単純さも、残りが 1 か所なので良い。
- どのインスタンスの `fuel` を読み書きしても同じ値なので、カーネルの「手持ちを持つインスタンス」（`fuelHolder`）は最初のインスタンスにしておけばよい。
- 燃料切れの処理・ホスト関数の入口の照合（`verifyInstanceLocked`）は、コンポーネントのプロセスでは「このプロセスのインスタンスのどれか」でよい（作っている途中は、作り終えていないインスタンスの start 関数からも呼ばれるので照合しない）。
- lift・lower で写した量は燃料に換算して引く: **値 1 つで 1、写したバイト 8 つで 1**（仮の値。確定は #251）。

### カーネル

- `Program.component(ComponentProgram)` でコンポーネントの `Program` を作る（`Program.module` は持たない。燃料の計測は `Program.isFuelMetered`）。
- プロセスの始まり（`ProcessRunner.load`）で、計画のとおりに core インスタンスを作る。メモリ・テーブルの上限と燃料切れの処理はインスタンスごとにカーネルが設定し、`ProcessDriver.configure` は呼ばない。作ったインスタンスは作るたびにプロセスに入れる（途中で失敗しても、プロセスの終わりで閉じる）。
- ABI 層のドライバーは `ProcessContext.component`（`LinkedComponent`）から export を呼ぶ。ホストの関数は `ComponentCall.env` を `KernelLinkEnv` として、プロセスの口（`ctx`）を使う。
- コンポーネントのプロセスは保存できないもの（`UNSAVEABLE`）として扱う（保存の形は #269）。`ctx.instantiate` は使えない。

### exec

コンポーネントを `exec` で動かす（ABI 層のドライバーを選び、`ModulePrescan` の `NOEXEC` の分岐を外す）のは、`wasi:cli/run` の版の扱い（#267・#268）と、検証の時間の止め方（#666）が決まってからにし、#687 で行う。`KernelDrivers.DEFAULT` は、コンポーネントの `Program` を `NOEXEC` で断る。

## テスト

- `ComponentLinkTest`: ホストの関数、core インスタンスどうしのメモリ・グローバル・テーブル・関数、文字列と realloc・post-return の順、`may_leave`、トラップの後、入れ子のコンポーネント、同じ中身のモジュールのまとめ、燃料の共有、元のバイト列の検証。
- `ComponentSpecTest`: 仕様の `test/values`・`test/linking` の wast（同じコミット）を `wasm-tools json-from-wast` で動かす。通らないもの（map、async、複数のメモリ、resource）は理由と一緒に挙げる。
- `ToolchainComponentTest`: wasi-libc でビルドした p1 のプログラムを wasmtime のアダプター（`gradle/wasi-adapter.properties`）で p2 のコマンドにし、リンクして `wasi:cli/run` から最初のホストの関数まで届くことを確かめる（resource が要るところから先は #265）。
- `ComponentProcessTest`: カーネルの中で、インスタンスをまたぐ呼び出しと燃料切れでマシンを壊さない。
- GameTest `componentLinksOnServer`（両バージョン）。
