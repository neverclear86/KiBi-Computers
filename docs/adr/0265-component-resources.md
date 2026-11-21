# 0265: Component Model の resource（own / borrow、ハンドル表、破棄）

- 状態: 採用
- Issue: #265（Epic #259）。前提は ADR 0260（Component Model は `core` に自前で実装。ホスト側の状態は保存できる形で持つ）、ADR 0263（リンク）、ADR 0264（Canonical ABI。仕様の版）、ADR 0043（カーネル全体）、ADR 0075（p2/p3 のハンドルはカーネルの fd 表に入れない）
- 後続: #266（非同期。Waitable などを同じハンドル表に足す）、#267（`wasi:*` のホストの resource）、#685（`kibi:*` の p2 の resource）、#269（コンポーネントのプロセスの保存）、#251（仮の値の確定）
- 番号は Issue 番号にそろえている
- 追記（#685）: `kibi:*` の p2 の resource（`event-queue`・`timer`・`process`・`device`・`pending-call`）は、`HostResourceTable` の `KernelEntry` としてカーネルのオブジェクトに結び付けた（ADR 0685）。保存は #269

## 背景

#263・#264 で、コンポーネントのリンクと Canonical ABI の lift・lower はできたが、resource（`own<R>`・`borrow<R>`、`resource.new`・`rep`・`drop`）はトラップにしていた。WASI p2 のプログラムは、最初に標準入出力の `own<output-stream>` などを受け取るので、resource がないと先へ進めない。

仕様は ADR 0264 で固定した `definitions.py`（コミット `dd1fefc`）の `Table`・`ResourceHandle`・`lift_own`・`lift_borrow`・`add_own`・`add_borrow`・`pass_rep_not_handle`・`canon_resource_{new,drop,rep}`・`Task.return_`・`Subtask.deliver_resolve` を、同期の呼び出しの範囲で写す。

## 決定

### 置き場所

すべて `core` の内部（`pro.ikili.kibicomputers.core.runtime.component.link`・`canon`。`internal`）。公開 API・システムコールの ABI（ゲストから見える番号の振り方・検査は仕様のとおり）・保存形式は変えない。コンポーネントのプロセスは今も `UNSAVEABLE`（ADR 0263）なので、保存データには何も足さない。

| 型 | 役目 |
| --- | --- |
| `ResourceInfo`・`ResourceImpl`（`ComponentPlan.resources`） | resource の型ごとの不変の記録。実装したもの（ゲストのインスタンスと dtor、ホスト、実体なし）と正規の番号 |
| `CoreRef.ResourceOp` | canon `resource.new`・`rep`・`drop` の組み込み（それを書いたインスタンスの表を使う） |
| `ResourceTable` | コンポーネントのインスタンス 1 つのハンドル表（仕様の `Table`） |
| `TaskIds` | borrow の範囲（仕様の同期の Task）の番号 |
| `HostResource`・`HostResourceDrop` | ホストの resource と、その dtor |
| `LinkedComponent` | インスタンスごとの表、borrow の範囲のスタック、`CanonResources` の実装、組み込みと dtor の呼び出し |

### ハンドル表は `HandleTable` と別の型にする

ADR 0043 は「`HandleTable` は fd と resource ハンドルの共通の形」としていたが、別の型（`ResourceTable`）にする。

- 番号の振り方がゲストから見える仕様で、違う。仕様は 1 から振り、空きは最後に空いたものから使う（LIFO。仕様のテスト `handle-table.wast` が番号まで比べる）。p1 の fd 表は POSIX どおり 0 から振り、いちばん小さい空きを使う。
- p2/p3 のハンドルはカーネルの fd 表に入れない（ADR 0075）。

表は**コンポーネントのインスタンスごとに 1 つ**（仕様の `ComponentInstance.handles`）で、使ったインスタンスの分だけ遅延して作る。エントリはプリミティブの配列（種類 `ByteArray`、正規の resource の番号・rep・lends の `IntArray`、borrow の範囲の `LongArray`）で持ち、1 エントリは約 21 バイト。配列は 8 から倍に広げる。空き番号は `IntArray` のスタック。

種類のタグ（`FREE`・`OWN`・`BORROW`）を置き、#266 が Waitable・WaitableSet・ErrorContext を同じ番号の空間に足せる形にした（#266 の中身は作っていない）。

### own と borrow、borrow の範囲

- own か borrow かは、種類のタグだけで決める。borrow の範囲（task の番号）の値からは推しはからない。task の番号がどんな値でも、borrow が `lift_own` や `resource.drop` の dtor を通ることはない。
- borrow の範囲は task の番号（`Long`）で持つ（Java の参照は持たない。ADR 0260）。task の番号は、プロセスごとに 1 から振る通し番号で、次の値は `Math.addExact` で進め、あふれたらトラップにする（折り返さない）。生きている task の番号はどれも違うので、borrow を drop したときに数を減らす相手の task を、番号で必ず正しく見つけられる。
  - `Int` で 1 から振り、own を 0 にする形は採らない。2^32 回の呼び出しで番号が 0 に戻ると、その task で作った borrow が own と区別できなくなる（ゲストが意図して起こせる）。折り返したときに 0 を飛ばす形も採らない。own との取り違えは防げるが、生きている task どうしの番号の重なりが残る。
- 同期の呼び出しの範囲は `LinkedComponent` のスタックで持つ。
  - Task: task の番号と、受けた borrow のうちまだ drop していない数。**引数に borrow を含む関数（`CanonFunction.paramsHaveBorrow`。作るときに 1 回だけ計算）のときだけ積む**。含まない関数（大多数）は、今と同じ道を通り、何も作らない。
  - Subtask の lenders: 貸したハンドルの（インスタンスの番号、ハンドルの番号）の組。

### 呼び出しの順

- lift した関数（`callFunc`）: borrow を含むなら Task を積む → 引数を lower → core を呼ぶ → 結果を lift → **受けた borrow が残っていればトラップ**（`Task.return_`）→ Task を降ろす → 呼ぶ側に結果を渡す（呼ぶ側への lower。呼ぶ側の realloc が動くので、検査はその前）→ post-return。
- lower した import（`callLowered`）: 引数を lift（borrow なら lenders に足す）→ 呼ばれる側を呼ぶ（post-return まで済む）→ lenders の貸し出しを戻す（`deliver_resolve`。try/finally）。
- `lower_borrow` は、受ける側のインスタンスがその resource を実装したインスタンスなら、ハンドルではなく rep をそのまま渡す（`pass_rep_not_handle`）。そうでなければ、今の Task を範囲にした borrow を足す。
- `resource.new` と `resource.drop` は `may_leave` が下りていればトラップ（realloc・post-return の中）。`resource.rep` は見ない。
- `resource.drop` の own は dtor を呼ぶ。ゲストの dtor は、実装したインスタンスの core 関数を直接呼ぶ（同じインスタンスでも呼ぶ。ゲストからゲストへの呼び出しなので `callGuest` で包まない）。ホストの dtor は `HostResourceDrop.drop`。実体なし、または dtor がないなら何もしない。borrow の drop は、範囲の task の数を減らすだけ。

### 再入（#705）

実装の前（#704 のレビュー）では、仕様の `canon_lift` の入口に `may_enter` の検査が、`canon_resource_drop` に `trap_if_on_the_stack` があるとして、写していない穴として挙げていた。これは固定した仕様（ADR 0264 の `dd1fefc`）より古い版の記述だった。

- 追記（#705）: `dd1fefc` の `definitions.py` には、`may_enter` も `trap_if_on_the_stack` もない。
  - `ComponentInstance` のフィールドは `handles, threads, may_leave, backpressure, num_waiting_to_enter, exclusive_thread` で、再入を覚えるものはない。
  - `canon_lift` の入口の検査は `task.enter_implicit_thread()` だけ。待つのは async 型の関数（backpressure と exclusive lock）だけで、同期の関数の再入はトラップにしない。
  - `canon_resource_drop` は、own なら dtor があってもなくても `store.lift(dtor, ft, opts, rt.impl_inst)` → `store.lower(…)` → `caller([])` を通す。dtor がないときは何もしない関数で、トラップしない。実装したインスタンスがスタックの上にあるとき（A が B を呼び、その下で B が A の dtor なしの resource の own を drop する）も成功することを、`ComponentResourceTest` で固定する。
  - `design/mvp/Concurrency.md` の「Reentrance」節は、再帰的な再入（donut wrapping を含む）を起こりうるものとして書いている。
  - 仕様のテスト `test/async/reentrance.wast` の前半（同期の 5 つ。子→親、親→子、donut の相互再帰、兄弟のインスタンスを通る再入、dtor の再入）は、再入が成功することを確かめる。
- なので、同期の再入はトラップにせず、検査を足さない。`ComponentSpecTest` に `reentrance.wast` を書き換えずに入れ、前半が計測なし・ありの両方で通ることを確かめた。
- ゲストの dtor は、今のまま実装したインスタンスの core 関数を直接呼ぶ。同期では、仕様の lift/lower を通すのと観測できる差はない（`canon_lower` の `may_leave` は drop の入口で検査済み。引数は rep の i32 だけで borrow を受けないので、新しい Task の borrow の検査は何もしない。threads 表への登録は #266 の範囲）。lift の道に通すと、`liftDepth`（ADR 0757 の巻き戻しの判定）・post-return・燃料の勘定が変わるので、通さない。
- 呼び出しごとの状態（スタックの上のインスタンスなど）も足さない。仕様が要らないとしている検査のために、すべての lift・lower の道に費用を足すことになるため。
- 相互再帰の深さに上限はない。Java のスタックが尽きたら、Endive の中なら `STACK_OVERFLOW` のトラップ、リンカーの中なら `StackOverflowError` のまま上がる（どちらになるかは尽きた位置で決まり、テストはどちらでも通す）。どちらでもコンポーネントは使えなくなり、プロセスのクラッシュ（`CrashReason` の `call stack exhausted`）になる。サーバーは止めない（`isFatal` は `StackOverflowError` を除く）。`ComponentLinkTest` はリンカー単体で、`ComponentProcessTest` はカーネルの下（`KernelLinkEnv` の計測・共有の燃料）で、深い相互再帰がそのプロセスだけを `call stack exhausted` で終わらせ、ほかのプロセスと次のプロセスは動き続けることを確かめる。カーネルの下のテストは、`BLOCKING`（spawn のたびに作る専用のスレッド）と `WAIT_POINT`（`WorkerPool` のワーカーで step ごとに動く）の両方で回し、`WAIT_POINT` ではスタックを使い切ったワーカーが生きていて、そのワーカーに回った次の step が最後まで動くことも確かめる。`WAIT_POINT` の側は、待機点への変換（ADR 0757）をかけたプログラム（`waitPoints`）で回す。スタックが尽きる位置がカーネルのロックの途中だと、ロック・CPU・許可が壊れる（#828 で再現した。燃料切れの処理の中で尽きると CPU が返らず、その台が止まった）。そこで、ゲストからカーネルに入る口で、ロックを取る前にスタックの余裕を確かめる（ADR 0043 の「ゲストからの入口のスタックの余裕」）。
- ホストからの入れ子の `call`（#691）を断るのは、ADR 0073 の安全のための独自の規則で、仕様の再入の検査ではない。残す。
- #266 への申し送り: async 型の関数の再入は、仕様の `enter_implicit_thread` のとおり、exclusive lock（`needs_exclusive`・`exclusive_thread`）と自動の backpressure（`backpressure`・`num_waiting_to_enter`）、同期で待つときの deadlock のトラップで写す。`reentrance.wast` の 202 行より後（async の関数・`task.return`・`subtask.cancel`・`thread.*`・`waitable-set.*` を使う部分。今は `KNOWN` に `ASYNC` で挙げた）で確かめる。

### resource の同一性

resource の型の照合は、plan の `ResourceInfo.canonical`（正規の番号）で比べる。ゲストの型の番号（`CanonType.Handle.resource`）は、インスタンス化・import のたびに新しく振る（生成的）が、別のインターフェースの import が同じホストの resource（`HostResource.id`）に結び付いたら、どれも最初に結び付いた番号を正規の番号にする。ハンドル表には正規の番号を書く。

- Types セクションの resource の定義は、その Frame の中で記録する。dtor は、定義した時点のその Frame の core func の添字空間で引く。dtor の型が `(i32) -> ()` でない、rep が i32 でないものは `WasmLoadException`（検証を通ったものには来ない。`CanonType.Handle` は 4 バイト・i32 の固定で、i64 を通すと `pass_rep_not_handle` の borrow の配置が変わるための守り）。
- ホストの `HostInterface.fallback` のインスタンス型にだけある resource は「実体なし」。

### ホストとの受け渡し

- `CanonValue.Handle.value` は rep（ハンドルの番号ではない）。
- ホストが lift で own を受けたら、所有はホストに移る（閉じる責任もホスト）。borrow の rep は、その呼び出しの中でだけ使える。
- ホストの resource の実体（fd・`KernelObject` など）は、カーネルの側の、プロセスの終わりに必ず閉じるものに持たせる。ゲストの表は「番号 → rep」の写しだけにする。プロセスが終わる・トラップしたとき（`finishLocked`・`LinkedComponent.close()`）に、ゲストの dtor もホストの dtor も呼ばない（呼ぶスレッドがプロセスのスレッドとは限らず、ロックの順も崩れるため）。
- ホストの dtor は、プロセスのスレッドで、ゲストの core が呼んだ組み込みの中から呼ばれる。待たない・すぐ戻る。カーネルロックが要るなら自分で短く取る。状態は持たず rep で引く。
- `LinkedComponent.call`（ホストがゲストの export を呼ぶ）の**引数**に、ゲストが実装した resource の own・borrow があるときは、呼ぶ前に `IllegalArgumentException`（ホストの誤り。コンポーネントは使えなくならない）。ホストはゲストの dtor を呼べず、rep を偽れるため。WASI p2 で要る場面はない。
  - **結果**の own は受けてよい（rep で返り、所有はホストに移る。ホストからは dtor を呼べないので、捨てても dtor は呼ばれない。仕様の埋め込みと同じ）。実装の前のプランでは結果も断るとしていたが、仕様のテスト（`handle-table.wast` の `return-R1-as-R2`。結果の lift で型の違いがトラップになることを確かめる）を通すため、結果は断らないことにした。
  - ホストの関数の型は、ホストの resource か実体のない resource しか指せない（リンクの照合でそうなる）ので、canon lower の側には起きない。
- どの `wasi:*`・`kibi:*` の resource を出すか、実体を何に結び付けるかは、#267・#685 に残す。

### 上限（仮の値）

1 つのプロセス（全インスタンスの合計）で同時に持てるハンドルの数を、仮に 4,096 にする（`CanonLimits.MAX_HANDLES`。確定は #251）。超える追加はトラップ。仕様の上限（`Table.MAX_LENGTH` = 2^28-1。`CanonLimits.MAX_TABLE_LENGTH`）より早く止める。p1 の fd 表の上限（256）より大きいのは、pollable・stream とコンポーネントの内部の resource が増えるため。上限まで使っても、配列を倍に広げる余りを入れて 1 台 200KB 未満に収まる。

### #269 への申し送り（保存の材料。形は決めない）

保存に要るのは次のもの。

- インスタンスごとの表の中身: 種類のタグ（own / borrow）、正規の resource の番号、rep、borrow の範囲（task の番号。`Long`）、lends、空き番号の並び（LIFO の順はゲストから見える）
- task の番号の次の値（`TaskIds.next`。`Long`。折り返さない）
- 同期の呼び出しのスタック（Task の番号と borrow の数、lenders）。待機点で保存するなら、そのときは空のはず

ホストの resource の id と Java の参照は保存しない。正規の番号は plan の中の番号なので、同じ plan を作り直せば同じになる（plan の作り方を変えたら変わる）。ホストの resource の rep を保存し直す方法は、#267・#685 と一緒に決める。

## テスト

- `ComponentSpecTest`: 仕様の `test/resources/{borrows,handle-table,multiple-resources}.wast`（同じコミット）を足した。追記（#705）: `test/async/reentrance.wast` も足した（前半の同期の再入が通る。後半の async は `KNOWN`）。`test/linking` の resource の分（`linking-unit.wast`）も通るようになったので、`KNOWN` から外した。
- `ResourceTableTest`: 番号の振り方（1 から、LIFO）、表にない番号、own と borrow を範囲の値に関係なくタグで区別すること、task の番号を使い切るとトラップ。
- `ComponentResourceTest`: ホストの resource の own・borrow の受け渡し、ホストの dtor が 1 回だけ呼ばれ borrow の drop では呼ばれないこと、2 つのインターフェースの resource が同じホストの resource として通ること、borrow を残して返るとトラップ、borrow を own として渡すとトラップ、上限、ゲストの dtor（同じインスタンス）、realloc の中の `resource.new` のトラップと `resource.rep`、ゲストの resource をホストから渡せないこと、dtor の型の違い。
- `ToolchainComponentTest`: アダプターで作った p2 のコマンドが、標準入出力の own を 3 つ受けて、その次のホストの関数まで進む。
