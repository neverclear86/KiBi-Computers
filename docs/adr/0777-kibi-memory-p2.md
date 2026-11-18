# 0777: `kibi:computer/memory` の p2・p3 での形

- 状態: 採用
- Issue: #777（Epic #259）。前提: ADR 0073（`kibi:*` の WIT）、#370（`kibi:computer/memory` の ABI。オーナーの回答）、ADR 0685（`kibi:*` の p2）、ADR 0263（リンク）、ADR 0757（待機点）、ADR 0048（RAM の勘定）
- プラン: #777 のコメント（https://github.com/neverclear86/KiBi-Computers/issues/777#issuecomment-6976384670）
- 後続: #266・#268（p3 の層。p3 の world はそこで足す）、#269（コンポーネントのプロセスの保存）、#251（仮の値の確定）、#116（SDK の束縛）

## 背景

#685（ADR 0685）では、`kibi:computer/memory` を p2 で出さなかった。`commit(ptr: u32, len: u32, keep: u64) -> bool` が線形メモリの番地を受けるのに、戻り値が `bool` だけで平らにすると retptr がなく、wit-component はその `canon lower` に `(memory …)` を付けないので、ホストが `ptr` をどのメモリで解くかを canon の option から決められないためだった。

WIT（関数と型）は #370 の論点 3 の回答で決まっている。p2・p3 での出し方は、#74・#426・#306 と同じく WIT を変えない範囲の層の実装で、エージェントに委ねられている（先例: ADR 0427・0426・0306、PR #778 の取り下げのコメント）。

## 決定

### 形（A）

- `kibi:computer` の `imports-p2` の world に `import memory;` を足す。WIT の型は変えない（p1 の import の形 ADR 0073 Q2-B も変わらない）。
- p3 の world（`imports-p3`）はまだ置かない。p3 も同じ形を仮置きとし、#266 で p3 の層を作るときに見直してよい。
- `commit` の `ptr` は、**canon lower した `commit` を import した core インスタンスの 0 番の線形メモリ**の番地。wit-component が作る形では import した main モジュールが呼ぶので、p1 の「呼んだモジュールのメモリ」（`WasiP1Kibi` の `caller.memory`）と同じ意味になる（export し直したときの違いは下の「渡し方」）。
- `remaining` は線形メモリに依らない（プロセス単位の `rt.memory` の残り。p1 と同じ）。

### lower を import した core インスタンスのメモリの渡し方

- 計画（`ComponentPlanner`）では決めない。実行時に、`LinkedComponent.instantiateStep` で lower した関数を包むときに捨てていた呼び手（`WasmHostFunction` の `instance`）の `memory` を、`callLowered` → `callFunc` → `ComponentCall.callerMemory` に通す。
- **呼び手は「lower を import した core インスタンス」。** ランタイム（`EndiveRuntime.instantiate`）は、ホストの関数を呼ぶときに import を結んだインスタンスを固定で渡す。wit-component が作る形（main が `commit` を直接 import する）では、それが呼んだインスタンスと一致する。core モジュール A が import した `commit` を関数として export し直し、別の core インスタンス B が呼ぶと、ptr は B ではなく A のメモリで解かれる。A がメモリを定義していなければ `null`（`false`）。A の export し直しを `canon lift` した道も、core の呼び手がなくても A のメモリが渡る。ランタイムの口が変わらない限りこの形で、`ComponentCallerMemoryTest` で固定している。
- 計画は全マシンで共有するので、メモリの実物を持たせられない。呼び手は呼び出しごとに引くので、同じ lower を複数の core インスタンスが import していても、それぞれのメモリになる（「最初のメモリ」などの推測はしない）。
- lift した関数の中身として lower した関数を呼ぶ道（`callCore` の `CoreRef.Lowered`。lift(lower)、realloc・post-return に lower を渡したもの）、ホストから直接呼んだ export し直しのホストの関数、resource の dtor では、呼び手の core インスタンスがないので `null`。
- `WasmInstance.memory` は、そのインスタンスが自分で定義した 0 番のメモリ（import したメモリだけのインスタンスでは `null`）。multi-memory は検査で無効なので、メモリは高々 1 つ。
- `HostFunctionBody` の KDoc の「ゲストのメモリには触らない」に、`commit` だけの例外を書いた（区画を数えるだけで、中身は読み書きしない。参照は呼び出しの外に持ち出さない）。

### 振る舞い

- 呼び手のメモリがない（メモリを定義していない、lift(lower) など間に core インスタンスがない）ときは `false`。範囲外も `false`（区画の燃料も払わない。p1 と同じ `KibiMemory.commit`）。
- RAM の上限で断ったら `false` で、`ProcessContext.commitMemory` が `memoryCommitRefusal` に残す。異常終了（`err` での 0 以外の終了・トラップ）の表示に `kibi:memory commit was refused: out of memory, …` が付く（#370 の追加の要件。コンポーネントのプロセスでも同じ文）。
- `ptr`・`len`（u32）と `keep`（u64）はビットのまま `KibiMemory.commit` に渡す（p1 と同じ。2^63 以上の `keep` は必ず断る。`P2.u64` の丸めは使わない）。
- 燃料は呼び出しごとの `CALL_FUEL`（`P2.guard`）と、範囲にかかる区画の数（`KibiMemory.commitFuel`）。どちらも仮の値（#251）。
- 待たない（`HostWait.NONE`）。`BLOCKING_FUNCTIONS` に入れないので、`memory` を import するコンポーネントも待機点型（`WAIT_POINT`）で動く。

### 読み込みで `NOEXEC` にしない理由

メモリの有無で読み込みを断る案は採らなかった。lower した `commit` がどの core インスタンスから呼ばれるかは、表を通した呼び出しなどがあり読み込みの時点では決めきれない。また、export していないメモリは `WasmModuleShape`（import・export だけ）から見えない。p1 でもメモリがないときは `false` なので、それにそろえた。

## 仕様との関係

- Component Model の仕様では、ホストの関数が呼び手の core インスタンスのメモリを見る口はない（`canon lower` の `memory` option だけ）。この形は、wit-component が option の要らない関数（引数・結果がすべて平らに収まる）を **shim を挟まずに、import した core モジュールへ直接 lower する**ことに依る。テスト（`KibiP2MemoryTest`）で、WIT から作ったコンポーネントとアダプターで包んだ p1 の C（`kibi_memory.h`）の両方で、`commit` が main モジュールに直接届くことを確かめている。ツールが shim を挟むようになったら、呼び手が shim（メモリなし）になり `commit` が `false` を返すようになるので、そのときは形を見直す（下の E）。
- p1 のプログラムをアダプターや wasi-sdk の `wasm32-wasip2` でコンポーネントにしたものでは、`commit` を呼ぶのは C の main モジュールで、そのメモリは C のヒープのメモリ（アダプターは自分のメモリを main から import するので、`commit` を import しない）。

## 採らなかったもの

- **B: p2・p3 は `remaining` だけ。** interface を分けると p1 の `commit` のモジュール名も変わり、同梱の Lua・OS を作り直すことになる。p2 のゲストは RAM の上限で行儀よく失敗できない。
- **C: 番地を取らない `reserve(bytes, keep)`（先払いの残高）。** プロセスに残高という新しい状態が増え、保存形式（#269、p1 にも入れるなら #81・#85）に触れ、#370 の項目 5（保存するのは区画だけ）とぶつかる。p1 と p2 で意味が分かれる。
- **D: 出さない（今のまま）。** 一番単純だが、#116 の `wasm32-wasip2` の SDK はトラップしかできず、#370 の「努力目標」を担えない。
- **E: `commit` に `list<u8>` を渡す形。** canon の `memory` option が付き、ホストがメモリを決められるが、WIT と p1 の形が変わる（Lua・OS・SDK の作り直し）。lift で中身を写すので、大きな範囲ほど遅い。ツールが shim を挟むようになったときの代わりの候補として残す。
- **計画で `CoreRef` としてメモリを決める案。** 呼び手が決まらない（0 個・2 つ以上）コンポーネントを読み込みで断ることになり、上の理由で決めきれない。実行時の呼び手で引けば、計画に何も足さずに済む。

## テスト

- `ComponentCallerMemoryTest`: `callerMemory` が lower を import した core インスタンスのメモリ（export していないものも）で、同じ lower を別のインスタンスが import すればそのメモリ、メモリを定義していないインスタンス（メモリを import しただけのものも）・lift(lower)・export し直したホストの関数では `null`。import した関数を export し直して別の core インスタンスが呼ぶと、渡るのは import したインスタンスのメモリ（呼んだインスタンスのメモリではない）で、export し直しを `canon lift` した道もそのメモリが渡る。
- `KibiP2MemoryTest`: WIT から作ったコンポーネントで、`commit` が main モジュールに直接 lower され canon の `memory` を持たないこと、区画を数えること、keep（u64 の最大・2^63）・範囲外・長さ 0 の意味、`remaining`。RAM の上限で断った後の `err`・トラップで、メモリ不足の文が付くこと。待機点型で動くこと。メモリを定義していない main モジュールからの `commit` と、lower した `commit` をそのまま lift して外から呼んだ `commit`（`callerMemory` が `null`）が、トラップも例外もなく `false` を返すこと。アダプターで包んだ p1 の C（`kibi-memory-wasi.wasm`）の `commit` と、断ったときの表示。
- `KibiP2HostTest`: 出す interface の一覧に `kibi:computer/memory` を足し、待たないこと。`HostWaitTest` の一覧は変わらない。

## 完了の条件

`./gradlew build` と、両バージョンの `runGameTestServer` が通る。
