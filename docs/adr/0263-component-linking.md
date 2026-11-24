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
- 残り: ホスト側の一時的な確保（lift した値。ゲストからゲストへの段・再入を深くすると段ごとに積み上がる）と、線形メモリのページの表は #753。
  - 追記（#753）: 線形メモリの区画の表は、core インスタンスごとに 1 ページ 128 バイトで同じ `ProcessMemory` に数える（ADR 0048 の追記）。lift した引数は、callee の線形メモリへ写し終えたら持たないので、ゲストからゲストへの段を深くしても、ホストが同時に持つ一時的な値は 1 段分で頭打ちになる（ADR 0264「予算」の追記）。再入の検査には頼らない（固定した仕様は同期の再入をトラップにしない。#705、ADR 0265）

### カーネル

- `Program.component(ComponentProgram)` でコンポーネントの `Program` を作る（`Program.module` は持たない。燃料の計測は `Program.isFuelMetered`）。
- プロセスの始まり（`ProcessRunner.load`）で、計画のとおりに core インスタンスを作る。メモリ・テーブルの上限と燃料切れの処理はインスタンスごとにカーネルが設定し、`ProcessDriver.configure` は呼ばない。作ったインスタンスは作るたびにプロセスに入れる（途中で失敗しても、プロセスの終わりで閉じる）。
- ABI 層のドライバーは `ProcessContext.component`（`LinkedComponent`）から export を呼ぶ。ホストの関数は `ComponentCall.env` を `KernelLinkEnv` として、プロセスの口（`ctx`）を使う。
- コンポーネントのプロセスは保存できないもの（`UNSAVEABLE`）として扱う（保存の形は #269）。`ctx.instantiate` は使えない。

### exec

コンポーネントを `exec` で動かす（ABI 層のドライバーを選び、`ModulePrescan` の `NOEXEC` の分岐を外す）のは、`wasi:cli/run` の版の扱い（p2 は ADR 0267 で決めた。p3 は #268）が決まってからにし、#687 で行う（検証の時間の止め方は #666 で決まった。ADR 0666）。

### 追記（#687）: exec につなぐ

- **読み込みの見分けは 1 か所。** `Program.loadExecutable` がヘッダー（`WasmBinaryHeader`）で見分け、コンポーネントは `ComponentProgram.load`、core モジュールは今までの `Program.loadMetered`・`load` で読む。`Machine.beginExec` と復元（`MachineRestore`）の `ProgramCache` の loader は、どちらもこれを使う（同じキーで別の読み方をして、片方の結果・失敗を別の読み方に返さないため）。復元で保存データがコンポーネントを指していたら、読み込めても p1 のプロセスには戻せないので冷えた起動になる。
- **キーの止まりうる import は空にそろえる。** コンポーネントは、ドライバーが渡す止まりうる import や `asyncifyExecutables` に関係なく、常にコンポーネント全体で待機点への変換をかける（#757。ADR 0757）ので、キーの止まりうる import は常に空（`Program.cacheWaitImports`）。ドライバーの集合や `asyncifyExecutables` ごとに別のキーになって二重に読み込まないため。燃料は p1 と同じ `meterExecutables` で、キーの `metered` に入る。
- **ホストは固定で、状態を持たない。** exec のホストはサーバー全体で 1 つ（`KernelDrivers.COMPONENT_HOST`。今は `WasiP2Host`。`kibi:*`（#685）を足すときも、同じ固定の合成ホストにする）。ホストの解決は読み込みのときに 1 回だけで、計画（ホストの関数の本体・dtor を含む）は全マシンが共有するので、`ComponentHost`・`HostFunctionBody`・`HostResourceDrop` は状態を持たず、スレッド安全にする（KDoc に書いた）。プロセスの状態は `ComponentCall.env` から引く。`ProgramCache` のキーにはホストを入れない。マシンやデバイスの構成でホストを変えると、リンクの失敗（`WasmLoadException`）が覚えられて別のマシンにも返るので、デバイスがあるかどうかはリンクのときではなく呼び出しのときに見る。
- **ドライバー。** `KernelDrivers.DEFAULT` は、コンポーネントの `Program` に `WasiP2Driver`（`BLOCKING`。ADR 0267）を選ぶ（#757 からは、待機点に変換できれば `WAIT_POINT`。ADR 0757。#844 からは、変換できないものは読み込みで `NOEXEC` にするので、`exec` したコンポーネントは常に `WAIT_POINT`）。`wasi:cli/run@0.2.<n>` の `run` がなければ `NOEXEC`（読み込みは成功して共有したまま、ドライバーが毎回断る）。
- **割り込み。** 検証の途中でコンパイル用のスレッドが割り込まれた（`WasmTrapException` の `INTERRUPTED`。ADR 0262）ら、`Machine.loadedProgram` は `AGAIN` にする（内容の失敗ではないので `ProgramCache` も覚えない）。呼んだプロセスやマシンは壊さない。ほかの種類のトラップは来ない想定なので、内部の誤り（`IllegalStateException`）にする。
- **キャッシュの重さ。** `ProgramCache` は重さを `Program.cacheWeight` から取る（強い参照に入れるときと、弱い参照から持ち直すときの 2 か所で同じ値）。core モジュールは今までどおり読み込む前のバイト数。コンポーネントは「中の core モジュール（同じ中身は 1 つ）のランタイムに渡したバイト数 ＋ core モジュールの数 × 64 KiB ＋ 計画の項目（手順・lift・lower・resource）の数 × 256 バイト」（`ComponentProgram.weightOf`。定数は仮の値で、確定は #251）。core モジュールを最大 1,000 個持てるので、元のバイト数だけで数えると、小さな入力でコンパイル結果（Metaspace）を大量に使えるため。
- **制約（1,000 台規模）。** （#687 の時点の記述。#844 からは、`exec` したコンポーネントは `BLOCKING` にならないので、以下の上限には数えられない）待っている p2 のコマンド 1 つにつきスレッドを 1 本使う（`BLOCKING`）。上限はサーバー全体の `SchedulerConfig.maxBlockingProcesses`（既定 2048）と、1 台の `MachineConfig.maxBlockingProcesses`（仮: 8。確定は #251）の 2 つで、どちらかを超えると作れない（`ResourceLimitException`。`kibi:process` の `spawn` は `NFILE`）。1 台の上限は、少数の台（1 台は `maxProcesses` の 64 まで作れる）がサーバー全体の枠を使い切って、ほかの全台の p2 のコマンドを `NFILE` にすることを防ぐ。それでも、多くの台が 1 台の上限まで待つ p2 のコマンドを置けば全体の枠は尽きうる（2048 / 8 = 256 台）ので、規模の目標（待っているだけの台はスレッドをほぼ使わない）を満たすのは、スレッドを持たない待ち（#757）が済んでから（#757 で、`kibi:device` の `invoke`・`kibi:process` の `spawn` を使わないコマンドは、入力・出力・時計を待つ間スレッドを持たない。#800 で、これらを使うコマンドも、デバイスの結果や子の読み込みを待つ間スレッドを持たない（ADR 0800）。計算し続けて燃料が尽きたときも、#803 から止まりうる関数の中ならスレッドを持たずに待つ。巻き戻せないところ（葉の関数の前借りの上限を超えたとき、段2に落ちたモジュールの中など。p1 と同じ）の燃料待ちだけが、`WAIT_POINT` のままスレッドを持ち、この上限に数えられない。ADR 0757）。計画の共有で、ヒープとコンパイルは同じコンポーネントにつき 1 回で済む。
- 保存は今までどおり `UNSAVEABLE`（#269）。モジュールの置き場には元のバイト列を、既存の形（`<sha256>.wasm`）で書く。
- 確かめていないこと: ネイティブの `wasm32-wasip2`・`wasm32-wasip3` のツールチェーンで作ったコンポーネント（テストはアダプターで包んだもの）、製品の jar（jarJar）での検証器・リンカー・ホストのクラスの読み込み（GameTest `componentCommandLoadsOnServer` は開発環境の実行時クラスパスで、読み込み・ホストの組み立て・`run` の呼び出しまで確かめる）。この 2 件は #775 で追う。

### 追記（#775）: ネイティブのツールチェーンと製品の jar で確かめた

- **ネイティブの `wasm32-wasip2`。** wasi-sdk 34（clang 23、`wasm-component-ld`。`gradle/wasi-sdk.properties` で版と全 OS 分の SHA-256 を固定し、`~/.gradle/kibicomputers/wasi-sdk/` に p2・p3 のビルドに要るものだけを展開する）で `hello_wasi.c` をビルドしたコンポーネント（約 28 KiB）を、アダプターなしで `exec` で動かした（`NativeToolchainComponentTest`）。wasi-libc が直接 import するのは `wasi:io/{error,poll,streams}`・`wasi:cli/{stdin,stdout,stderr,terminal-*}`・`wasi:clocks/{monotonic-clock,wall-clock}` で、版はすべて `@0.2.12`（基準と同じ）。LLVM の既定の機能（bulk-memory など）を有効にした core モジュールも、燃料の計測コードの挿入（`metered = true`）と `ModuleLimits` を通った。2 回目の exec は読み込み直さない。main が 0 以外（3）を返すと、wasm32-wasip2 の wasi-libc の crt1 は `wasi:cli/exit` を呼ばず（`exit3-p2` は `wasi:cli/exit` を import しない）、`wasi:cli/run` の `run` から err を返すだけで、カーネルの終了コードは `WasiP2Driver` の「`run` が err → 1」で 1 になる。main から戻らずに `exit()` を呼ぶと、wasi-libc は `wasi:cli/exit` を直接呼ぶ（`exit_native.c`。`exit-native-p2`）。`exit(0)`・`exit(1)`・`exit(3)` がそれぞれ 0・1・3 になることを確かめた。3 は `exit-with-code` を通ったときだけ 3 になる（`exit(err)` なら 1）ので、ネイティブでも `exit-with-code` の経路を通る。0・1 で `exit`・`exit-with-code` のどちらを呼ぶかは見分けていない。読み込みから終了まで約 80〜140 ms（測り直した値は 77〜139 ms。Windows・Java 17。`NativeToolchainComponentTest` は core の単体テストで、core のツールチェーンは Java 17 なので、Gradle の `:core:test` の温まっていない JVM。検証器の暖めを含み、実行ごとにばらつく）。最初に記録した約 270 ms は測定の条件（JVM の版）が記録されておらず、測り直した値と合わないので採らない。
- **ネイティブで確かめた範囲。** アダプターを通していないことは、world の import（アダプターで包んでも `wasi:*@0.2.x` だけになるので区別にならない）ではなく、コンポーネントの中に p1 のアダプターの印（`wasi_snapshot_preview1`）がないこと、対照のアダプターで包んだ同じソースには印があることで確かめる。exec で動かしたのは `hello_wasi.c`・`exit3.c`・`exit_native.c` に加えて、`path_wasi.c`（`wasi:filesystem` の preopen・descriptor・stream・ディレクトリの読み出し。アダプター経由の `path-wasi` と同じソース）と、`basic_native.c`（args・environ・単調時計・壁時計・乱数・`sched_yield`）。`wasi:sockets`・`wasi:http`・`kibi:*` と、`poll`・タイマーを使うプログラム（`poll_wasi.c` 相当）はネイティブでは試していない。
- **ネイティブの `wasm32-wasip3`。** wasi-sdk 34 には `wasm32-wasip3` の sysroot がある。同じソースをビルドしたものは `wasi:cli/types@0.3.0` などを import し（pre-release の版ではない）、読み込みで `WasmLoadException`（「wasi:* の 0.3 は #268 まで受け付けない」）になる。`exec` は `NOEXEC` で、失敗を覚えて読み込み直さない。
- **製品の jar。** 両バージョンに `runJarGameTestServer` を足した。ゲームのディレクトリは `run/jar-gametest`、`mods/` に今のビルドの jar（1.20.1 は reobf の前のもの）を `Sync` で置き、run のソースセットは空の `jarRun`（KFF とゲームの依存だけ）、`loadedMods` は空、Endive は全 run 共通のレガシーのクラスパスから外す（`jarGameTestServerLegacyClasspath` で `run.endive` を除く）。jar に入った main の GameTest がすべて通った（1.21.1 は 168 件、1.20.1 は 12 件）。GameTest `bundledLibrariesComeFromJarInJar` が、jar の run でだけ、Endive のランタイム・コンパイラ・WASI・wasm-tools のクラスが MOD の jar の `META-INF/jarjar/` から読んだ名前付きモジュール（wasm-tools は自動モジュール `wasm.tools`）であることと、`RuntimeSelfCheck.loadsComponentCommand()` が通ることを確かめる（Endive がクラスパスから読まれて偽の合格になるのを防ぐ）。1.21.1 は FML のモジュールのクラスローダー、1.20.1 は modlauncher の `TransformingClassLoader` から読まれた。同梱の漏れ・`requires` の不足・リソース（`WasmToolsModule.meta` など）の封じ込めの問題は出なかった。
- 確かめていないこと: インストーラーで入れた本物の製品サーバーでの起動（Mojang の EULA への同意が要るので、開発環境の GameTest の run で jar を読む形にした）。1.20.1 の製品物（reobfJar。SRG 名）そのものは読んでいない（reobf は MC の参照だけを付け替え、core と Endive には触れない前提）。

### 追記（#844）: 待機点に変換できないものは exec で NOEXEC

#757 では、待機点に変換できないコンポーネントを `BLOCKING` で動かした（上の「ドライバー」「制約」）。#801 の結論（オーナーの回答 (B)）で、`Program.loadExecutable` が `ComponentProgram.load` に `requireWaitPoints = true` を渡し、変換できなければコンパイルの前に `WasmLoadException`（原因は `AsyncifyUnsupported`）で断るようにした。`exec` は p1（#367）と同じ文面の `NOEXEC` になり、失敗は `ProgramCache` が覚える。これで `exec` したコンポーネントがスレッドを持って待つのは、p1 と同じ性質の巻き戻せないところ（start 関数の中、前借りの上限を超えた燃料待ちなど）だけになる。詳しくは ADR 0757 の「追記（#844）」。

## テスト

- `ComponentExecTest`（#687）: アダプターで包んだ p2 のコマンドを `Machine.exec` で動かす、別のマシン・別のドライバーの集合から exec しても読み込みは 1 回、`run` のないものは `NOEXEC`（読み込みは共有）、リンクできないもの（p3・ホストにない import）は `NOEXEC` で失敗を覚える、読み込みの割り込みは `AGAIN` で覚えない、重さの見積もり、p1 の `kibi:process` の `spawn` でコンポーネントの子を作る、1 台のブロッキング型の上限で `NFILE`（#844 から p1 の子で確かめる）、待機点に変換できないコンポーネントは `exec`・`spawn` で `NOEXEC`（#844）、共有した計画とホストを 6 台のプロセスが同時に動かす。

- `ComponentLinkTest`: ホストの関数、core インスタンスどうしのメモリ・グローバル・テーブル・関数、文字列と realloc・post-return の順、`may_leave`（realloc の中と post-return の中。post-return から core どうしで直接呼ぶのはトラップしない）、トラップの後、入れ子のコンポーネント、同じ中身のモジュールのまとめ、燃料の共有、元のバイト列の検証。インスタンスの間の受け渡し（メモリ・グローバル・テーブル・関数、文字列、入れ子）は、計測なしとありの両方で回し、計測ありでは燃料が減って、どのインスタンスから読んでも同じことも確かめる（#692）。
- `ComponentSpecTest`: 仕様の `test/values`・`test/linking` の wast（同じコミット）を `wasm-tools json-from-wast` で動かす。計測なしとあり（燃料を共有する形）の両方で動かし、計測ありで燃料が切れたトラップは `assert_trap` の成功に数えない（#692。`json-from-wast` はファイルごとに 1 回）。通らないもの（map、async、複数のメモリ）は理由と一緒に挙げる（resource は #265 で通るようにした。`test/resources` の wast も足した）。
- `ToolchainComponentTest`: wasi-libc でビルドした p1 のプログラムを wasmtime のアダプター（`gradle/wasi-adapter.properties`）で p2 のコマンドにし、リンクして `wasi:cli/run` からホストの関数まで届き、標準入出力の own を受けた先まで進むことを確かめる（#265）。
- `ComponentProcessTest`: カーネルの中で、インスタンスをまたぐ呼び出しと燃料切れでマシンを壊さない。読み込みの途中の失敗（#692）: 2 つ目の start 関数のトラップ（プロセスだけが `CRASHED`）、作る途中の燃料待ちでの kill と、インスタンスを作る間の kill（`KILLED`）。どれもマシンを壊さず、作った core インスタンスを閉じ、プロセスとマシンの RAM を返す。
- `FuelInjectorSharedTest`: 燃料を共有する形の挿入（#692）。グローバルの import があるとき・ないときの、関数の本体・export・グローバルの初期値（import を指す形と、前に定義したグローバルを指す extended-const の形）・データと要素のオフセット・要素の式の番号の付け替えを、wasm-tools の検証、Endive の読み込み、実行の結果で確かめる。パッシブの要素の式は、Endive が `table.init` でほかのインスタンスの関数の参照を正しく置かず、ランタイムが読み込みで断る（#706）ので、wasm-tools で読み戻して確かめ、Endive の読み込みでは断ることを確かめる。共有しない形では、今までどおりグローバルの import を断る。
- `ForeignFuncRefTest`: ほかのインスタンスの関数の参照を Endive が自分の番号として置いてしまう定数式を、計測なしと共有の燃料の形の両方で、読み込みで断る（#706。下の「追記（#706）」）。正しく動く形（アクティブの要素の import、表の初期値の import、パッシブの要素の `ref.func`、externref の要素、宣言の要素）は断らず、動かして値を確かめる。
- GameTest `componentLinksOnServer`（両バージョン）。

## 追記（#706）: ほかのインスタンスの関数の参照を置く定数式を断る

Endive 1.2.0 は、import した funcref のグローバルの値（渡した側のインスタンスでの関数の番号）を、次の 3 つの定数式で、受け取った側の関数の番号として置いてしまう（インタプリタ・コンパイルとも。上流の main でも同じ）。別の関数が呼ばれるか、型が合わずにトラップする。

- パッシブの要素の式（`table.init` で置く。`OpcodeImpl.TABLE_INIT` が、アクティブの要素と違って `ConstantEvaluators.computeConstantInstance` で持ち主を引かない）
- 関数の参照の型の、定義したグローバルの初期値（`Instance.initialize` が持ち主を自分にする。そのグローバルを要素の式で使うと、アクティブ・パッシブのどちらも違う関数になる）
- GC のオブジェクトを作る定数式（`struct.new`・`array.new`・`array.new_fixed`。グローバルの初期値、アクティブ・パッシブどちらの要素の式、表の初期値の式（`(table 1 (ref null $S) (struct.new $S (global.get $fr)))`。`Instance` のコンストラクターが同じ `computeConstant` で作って `setObjRef` で置く）も）が、funcref・関数の型の参照の import したグローバルを `global.get` してフィールド・要素に入れる形（例: `(global (ref $S) (struct.new $S (global.get $fr)))`）。`ConstantEvaluators.computeConstant` が、funcref のフィールドを渡した側での番号のまま int で入れるので、`struct.get` → `call_ref`・`table.set` で、受け取った側の同じ番号の関数になる。ソースを読んだ限りで、動かして値を確かめてはいない（今は struct・array の型を持つモジュールが製品の経路で読めない（#891）ので、判定だけをテストで確かめる）

import した funcref のグローバルを置ける定数式の場所のうち、次は正しく動くので断らない: アクティブの要素の式と、表の初期値の式（`(table 8 funcref (global.get $fr))`。`Instance` のコンストラクターが `computeConstantInstance` で持ち主を引いて `setRef` する）。宣言の要素（`(elem declare funcref (global.get $fr))`）は、`table.init` で使えず値が置かれないので見ない。どれも `ForeignFuncRefTest` で、断らないこと（表の初期値は、動かして渡した側の関数が呼ばれること。燃料を共有する形の挿入は、初期値の式を持つ表を別の理由で断るので、計測なしの形だけ）を確かめる。

直るまでは、`EndiveRuntime.parseModule` が `foreignFuncRefInitializer` で見つけて `WasmLoadException` で断る（対応しない作りとして扱う。`AsyncifyUnsupported` ではない）。最初の 2 つの対象は、番号（int）で持つ参照の型（funcref・関数の型の参照）が、import したグローバルを `global.get` する形だけ。externref・GC の参照そのもの（struct・array の参照）は参照ごと置くので断らない（3 つ目は、オブジェクトの中に入れる funcref だけが番号のままになる）。判定は読み込みのときに 1 回だけで、結果は `ProgramCache` が共有するので、実行中のコストはない。グローバルの import を持てるのは、計測しないモジュールと燃料を共有する形（コンポーネントの core モジュール）だけなので、p1 のプログラムには当たらない。

比べた方式: 読み込みで動く形に書き換える（隠した表に入れて `table.init` を `table.copy` にするなど）は、`elem.drop` の後のトラップの条件・表の番号・RAM の数え方・状態保存の表の一覧・Asyncify の `sharedTables` に波及し、めったに当たらない形に対して重すぎる。Endive を差し替える（fork・shade）は、Jar-in-Jar とコンパイルしたコードの呼び出しを壊しやすい。funcref のグローバルの import を丸ごと断ると、正しく動くアクティブの要素まで断る。

関数の本体で参照を値として運ぶ形（import した funcref のグローバルを `global.get` して `table.set`・`call_ref` する、import した表から `table.get` して自分の表に置く）も、同じく違う関数になることを確かめた（`table.copy` は正しい）。定数式でなく、命令の流れを追わないと分からないので、#706 では扱わず、#932 に分けた。Endive が直ったら、この判定を外し、`FuelInjectorSharedTest` のパッシブの要素を動かして確かめる形に戻す。
