# 0757: p2 のコンポーネントをスレッドを持たずに待たせる

- 状態: 採用
- Issue: #757（task）。前提: ADR 0263（コンポーネントのリンク）、0267（WASI p2 のホスト）、0316・0329（p1 の待機点と asyncify）、0685（kibi:* の p2）

## 決めたこと（要約）

- コンポーネントの中の core モジュールを、p1 と同じ待機点への変換（asyncify。ADR 0316・0329）で変換し、`WasiP2Driver` を待機点型（`WAIT_POINT`）で動かす。待っている p2 のコマンドはスレッドを持たない（p1 と同じく、イベントのキーとタイマーだけを持つ）。
- 巻き戻せない待ちのホストの関数（`HostWait.BLOCK`）を import するコンポーネントは、変換しない（#757 の時点ではブロッキング型（`BLOCKING`）で動かした。#844 から、`exec` では `NOEXEC` で断る。下の「追記（#844）」）。理由は `ComponentProgram.waitPointRejection` に残る。#757 の時点では `kibi:device/bus` の `[method]device.invoke`、`kibi:process/process` の `[static]process.spawn`、`kibi:process/process-poll` の `spawn-with-handles` がそうだったが、#800（ADR 0800）でどれも巻き戻せるようにしたので、実物のホストには `BLOCK` の関数はもうない（判定と断る道は残す）。
- **ABI・保存形式・公開 API は変えない。** コンポーネントは今までどおり `UNSAVEABLE`。p1 の変換の出力（`Instrumentation.VERSION`）も変わらない。

## 変換

- 順番は p1 と同じく、燃料の注入 → asyncify（#341）。`ComponentProgram.load` は、計画（`ComponentPlanner.plan`）を、コンパイルの前のモジュールの形（`WasmRuntime.describe`。import・export だけ）から作り、変換した後のモジュールをコンパイルして計画に結ぶ（`Planned.link`）。変換で import・export は変わらない（検査する）。
- **止まりうる import はコンポーネント全体で決める**（`ComponentAsyncify`）。モジュールの import は、次のどれかなら止まりうる。
  - ホストの関数を lower したもので、その関数の待ち方（`HostWait`）が `NONE` でない
  - ほかのモジュールの、止まりうる export
  不動点まで回す。wit-component のアダプターの shim（自分の表を `call_indirect` で呼び、表は fixup が lower した import で埋める）のように、待ちが表を通るものがあるので、**import・export した表に対する `call_indirect` と `call_ref` は、その型が、コンポーネントのどこかの止まりうる関数の型と同じなら止まりうるとみなす**（`Asyncify.Prepared.analyze` の `externalSuspendTypes`）。ほかのモジュールの関数の参照は、import も export もしていない表にも入れられる（funcref のグローバルの import・export、funcref を引数・結果に持つ import・export した関数、funcref を運ぶタグ、import・export した表からの `table.get`・`table.copy`）ので、**そのどれかがあるモジュールでは、どの表への `call_indirect` にも外の型を当てる**（`Transformer.foreignFuncRefs`）。参照が入りうるかだけを見て、表への命令の流れは追わない（止まりうるとみなす関数が増えうるだけで、変換は正しい）。
- 変換しないもの（#757 では `BLOCKING` に落とした。#844 から `exec` では `NOEXEC` で断り、直接の `ComponentProgram.load` とテストの `asyncify = false` だけ `BLOCKING`）:
  - `HostWait.BLOCK` のホストの関数を lower している
  - 待ちが、ゲストからゲストへの呼び出し（lift した関数を lower した import）の下にある（中の lift を巻き戻すと、結果の lower・post-return の途中を残すため）
  - 止まりうる関数がゲストの resource の dtor になる
  - asyncify が扱えない形（`AsyncifyUnsupported`）
- 燃料の使い切りでの yield（`kibi.async_fuel`）も、p1 と同じ 2 段で使う（#803。下の「燃料切れの待ち点（#803）」）。#757 の時点では使わず、燃料が尽きるとワーカーのスレッドを持ったまま `WAITING_FUEL` で待っていた。

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
| `kibi:device/bus` の `[method]device.invoke`、`kibi:process/process` の `[static]process.spawn`、`kibi:process/process-poll` の `spawn-with-handles` | `UNWIND`（#800。ADR 0800。#757 の時点では `BLOCK`） |
| ほか | `NONE` |

- `UNWIND` は名前の表を持たず、本体が `WasiP2Io.WaitingBody`（`WasiP2Io.waiting { }`）かどうかで決める（巻き戻す本体と宣言が食い違わない）。`wasi:*`・`kibi:*` のどちらも同じ（`kibi:*` の名前の表は #800 で消した）。一覧は `HostWaitTest` で固定する。
- 止まりうる import の解析（`ComponentAsyncify`）は、lift と lower の連鎖を再帰せず、覚えながら 1 回ずつたどり（1 周あたり lower の数に比例）、長い連鎖の途中でも読み込みの時間の上限（`checkpoint`）を確かめる。

## スレッド

- 待機点型のコンポーネントは静的に保存できない（`UNSAVEABLE`）ので、チャンクのアンロード・サーバーの停止の写し（`captureCopy(KILL_TRANSIENT)`）は step の終わりを待たない。走っている途中のプロセスの `AsyncStack` をカーネルロックの中から写すことがあるので、`AsyncStack` の読み書きはそのインスタンスのモニターの中で行う（写した中身は途中のものでありうるが、写しの中で殺すので使わない）。

## 燃料切れの待ち点（#803）

- **変換は p1 と同じ 2 段**（ADR 0329 の「燃料切れの待ち点化」）。`ComponentProgram.transform` は、core モジュールごとに、止まりうる関数の中の `kibi.fuel` も待ち点にする変換（段1。`Asyncify.Prepared.transform` の `yieldImport` に `Instrumentation.YIELD_IMPORT`）を試し、`AsyncifyUnsupported` で断られたら、**そのモジュールだけ**燃料切れを待ち点にせずに変換する（段2。#757 と同じ）。段2も断られたら、どのモジュールにもかけない（`exec` では `NOEXEC`。#844。直接の読み込みでは `BLOCKING` に落とす）。段1・段2は同じ `Prepared` から変換する（`Transformer` を呼ぶたびに作り、読んだモジュールは書き換えない）。
  - 止まりうる関数の判定（`ComponentAsyncify` の不動点と `externalTypes`）は段1でも変えない。`kibi.fuel` を止まりうる import に入れると、ほぼ全関数が止まりうる関数になり、dtor やゲストからゲストの下の待ちとして断られて `BLOCKING` に落ちるものが増えるため。状態は全インスタンスに同時に入るので、`kibi.async_fuel` を持つモジュールと持たないモジュールが混ざってもよい。
  - 段1で燃料切れの待ち点（`yieldSites`）が 1 つ以上できたら `ComponentProgram.fuelYield` が真で、`Program.component` が `Program.fuelYield` に渡す（前借りはこれが真のときだけ）。段2に落ちたモジュールがあれば、最初の理由を `ComponentProgram.fuelYieldRejection` に残し、`FuelYieldRejectionLog`（コンポーネントの。同じコンポーネントでは 1 回だけ。`WaitPointRejectionLog` とは別に覚える）でログに出す。
  - `ModuleSource`（`fuelYield` は偽のまま）・`Instrumentation.VERSION`・p1 の変換の出力は変えない（コンポーネントは保存しない）。
- **巻き戻しは全インスタンスに同時に入れる。** `ProcessRunner.onFuelExhaustedUnwindable` は、コンポーネントのプロセスなら `onComponentFuelExhaustedUnwindable` に分ける（p1 の道は変えない）。プロセスの状態で巻き戻せて（待機点型・作っている途中でない・ホスト関数の中からゲストを呼んでいない）、`LinkedComponent.canUnwind`（トップレベルの lift の 1 段の下で、`may_leave` がすべて立っている）なら、`LinkedComponent.requestUnwind` で全インスタンスを `UNWINDING` にする。ホストの関数の巻き戻しと同じく、外の `call` は結果を lift せず post-return も呼ばずに戻り、`WasiP2Driver` は `takeFuelUnwind` を見て `StepResult.WaitFuel` を返す（ホストの待ちの key は持たない）。その後は p1 と同じく、`RUNNABLE`・スタックなしで、スレッドを持たない燃料待ちの列に入る（`ProcessRunner.waitFuelUnwound`）。その場で足す・譲る・列の置き方（ADR 0047）は p1 と同じ処理を通る。
- **着いたときは `LinkedComponent.endFuelRewind`。** 燃料待ちからの巻き直し（`rewindingForFuel`）が `kibi.async_fuel` の呼び出しに着いたら、`rewinding` を下ろし全インスタンスを `NORMAL` に戻す。`LinkEnv.onRewound`（= `ComponentSuspend.onArrived`）は呼ばない。呼ぶと、次に呼ばれた待ちうるホストの関数（`blocking-write-and-flush` など）を「巻き直して着いた呼び出し」と取り違え、前の `asyncWait` や書き込みの進み具合を誤るため。巻き戻せるのはトップレベルの lift の 1 段の下だけなので、燃料の巻き直しが lower した呼び出し（`callLowered` の巻き直しの分岐）を通ることはない。
- **巻き戻せないところ**（ゲストからゲストへの呼び出しの下、realloc・post-return の中、core モジュールの start 関数の中（`LOADING`）、ホスト関数が呼んだゲストの中）では、今までどおり `onFuelExhausted` でスレッドを持って待つ。前借り（`onFuelExhaustedPlain`）は、コンポーネントでも p1 と同じくプロセスの状態（`unwindableLocked`）だけで決め、ゲストからゲストへの呼び出しの下（`canUnwind` が偽）でもする。前借りは巻き戻しを伴わないので Kotlin の層を巻き戻すことはなく、止まりうる関数でない callee が前借りの上限（`FUEL_OVERDRAFT_LIMIT`）の中で戻れば、外の lift の 1 段目の止まりうる関数の次の `kibi.async_fuel` で巻き戻せる（p1 の葉の関数と同じ）。戻らずに上限に届けば、そこでスレッドを持って待つ。realloc・post-return の中（`withoutLeave` が `LinkEnv.callGuest` で包むので `guestCallDepth > 0`）は前借りしない（戻った先の canon の Kotlin の層は巻き戻せない）。p2 のコマンドでは、起動の途中（アダプターが引数・環境変数を受け取るときにホストの関数が呼ぶ realloc の中）で燃料が切れたときに、ここに当たる。
- **燃料の勘定。** 巻き戻して `run` から戻るとき、`LinkedComponent.call` は外の lift の燃料（`chargeFuel`）を手持ちから引く。`waitFuelUnwound` は、コンポーネントでは巻き戻すと決めたときの残りでなく今の手持ちを書き戻す（この分を落とさない）。p1 は巻き戻しの間に燃料を引かないので、読み方を変えない。巻き直しのたびに `run` の lift の燃料がもう一度かかるのは、ホストの待ちの巻き直しと同じ（上の「巻き戻しと巻き直し」、ADR 0800）。
- **選ばなかったもの。** 燃料待ちで持つスレッドを `maxBlockingProcesses` に数える案（Issue の 2 つ目の案）は採らない。燃料が尽きたプロセスはどこかで待つしかなく、上限に当たったときにできるのは「数えずに待つ」（抑えられない）か「殺す・トラップする」（ふるまいが変わる）だけで、スレッドの数を本当には減らせない。exec のときに数える形にすると、プロンプトで待つだけのシェルまで 1 台 8 の枠を使い、子の `spawn` が `NFILE` になる（#757・#800 の後戻り）。優先順位（TPS → 状態保存 → 単純さ）でも、待ち点にする案が勝つ（保存は両案とも関係しない。実装は p1 の #339 の仕組みをほぼそのまま使える）。
- 速さ: 段1で止まりうる関数の loop の先頭が待ち点になる分、生成コードが増える。`AsyncP2Test`・`KibiP2WaitPointTest`・`NativeToolchainComponentTest` の時間は、段1を試すときと試さないときでほぼ同じだった（Windows・Java 17。それぞれ約 19.2 秒・12.1 秒・6.1 秒）。

## 制約と残り

- `kibi:device`・`kibi:process` を使うコンポーネントも、#800（ADR 0800）で巻き戻して待つようになった。
- p1 では、変換できない WASM を `NOEXEC` で断る（#364・#367）。コンポーネントでは `BLOCKING` に落とす（Issue #757 の本文の指定。`exec` の ABI は変えない）。そろえるかどうかは #801 で決め、オーナーの回答は (B)（理由を問わず `NOEXEC` にそろえる）。#844 で実装した（下の「追記（#844）」）。変換できない理由は、ホスト側の制限（`HostWait.BLOCK` の `invoke`・`spawn`。p1 では巻き戻せるので対応する場合がない。#800 で解消し、実物のホストには残っていない）と、ゲスト側の作り（`AsyncifyUnsupported`、ゲストからゲストの下の待ち、dtor。#364 と同じ性質）に分かれる。
- **燃料待ちでスレッドを持つのは、巻き戻せないところだけ。** #757 の時点では、計算し続ける p2 のコマンドは燃料が尽きると `WAITING_FUEL` でスレッドを持ち、`WAIT_POINT` なので `maxBlockingProcesses`（1 台 8・サーバー全体 2048。ADR 0263）に数えられず、1 台あたり `maxProcesses`（64）まで持てた。#803 で燃料切れを待ち点にした（上の「燃料切れの待ち点（#803）」）ので、止まりうる関数の中の燃料切れはスレッドを持たない。残るのは p1 と同じ性質のもの（葉の関数で前借りの上限 `FUEL_OVERDRAFT_LIMIT` を超えて計算し続けたとき、段2に落ちたモジュールの中、巻き戻せないところ）で、これらは今までどおり `maxBlockingProcesses` に数えずにスレッドを持って待つ。数える上限は足さない（上の「燃料切れの待ち点（#803）」の「選ばなかったもの」）。
- 変換できなかったコンポーネントは、直接の読み込み（`requireWaitPoints` が偽）の道でだけ、理由を `WaitPointRejectionLog`（同じコンポーネントでは 1 回だけ。p1 の `FuelYieldRejectionLog` に当たる）でログに出す（`exec` の道では断って、理由を呼んだ側に返すので出さない。#844）。`BLOCKING` に落ちたものは、待つ間スレッドを持ち、`maxBlockingProcesses` に数えられる（1 台 8 の枠で子の `spawn` が `NFILE` になる）ので、運用の側から原因を追えるようにする。理由には、どの core モジュール（計画の番号）の、どの関数（燃料の計測コードが足した import を除いた元の番号。p1 と同じ。#398）かを書く。
- 保存（#269）は今までどおり扱わない。

## 追記（#844）: 変換できないコンポーネントは exec で NOEXEC にする

#801 のオーナーの回答 (B) により、#757 の「変換できなければ `BLOCKING` に落とす」を置き換える。

- **`exec` の道では、待機点に変換できないコンポーネントを、理由を問わず `NOEXEC` で断る。** 理由はゲスト側の作りの 3 つ（`AsyncifyUnsupported`、ゲストからゲストへの呼び出しの下の待ち、resource の dtor の中の待ち）と、テストの合成のホストの `HostWait.BLOCK`。実物のホストには `BLOCK` はない（#800）。合成したコンポーネント（`wac`・`wasi-virt`）の中で待つものも動かなくなる（オーナーが了承）。
- **断る判断は `Program.loadExecutable` に置く。** `ComponentProgram.load` に `requireWaitPoints = true` を渡し、変換の段（段4）で理由が出た時点で、全 core モジュールのコンパイル（段5）の前に `WasmLoadException("待機点に変換できない: <理由>", AsyncifyUnsupported(<理由>))` を投げる。戻り値の `waitPointRejection` を見てから投げると、最大 1,000 モジュールを捨てるためにコンパイルし、TTL が切れるたびに繰り返すため。
- **文面と失敗の覚え方は p1（#367）と同じ。** 原因が `AsyncifyUnsupported` なので、`Machine` は `Program.unsuspendableReason` で見分けて「`<パス>` は待機点に変換できない作りなので動かせない: `<理由>`」の `NOEXEC` にする。`WasmLoadException` なので `ProgramCache` が失敗を覚え、多数の台が同じものを exec しても読み込みは 1 回にまとまる。理由には、どの core モジュールの、どの関数・命令か（canon lower・dtor の番号）が入る。
- **止まりうる関数がないものは、止まりうる関数の中の作り（v128 のローカルなど）では断らない**（p1 の #384 と同じ）。ただし、グローバル・要素セグメントの定数式の `v128.const` は、止まりうる関数がなくても断られる。`exec`（計測あり）では、変換より前に `FuelInjector` が `AsyncifyUnsupported` ではない `WasmLoadException` で断る（`unsuspendableReason` は `null`）。計測なしの読み込み（テスト）では `Asyncify`（`Reader.copyConstExpr`）が断る。p1 にも同じ制限があり、この PR の前から変わらない。扱えるようにするのは #851。燃料切れだけを待ち点にできなかった（段1だけ断られた）ものも、今までどおり `WAIT_POINT` で動かす。
- **`BLOCKING` の道は残す。** 直接の `ComponentProgram.load`（既定は `requireWaitPoints = false`）、テストの `asyncify = false`、`WasiP2Driver` の `BLOCKING` は、テストとブロッキングとの比較に使う。`WaitPointRejectionLog` はこの道でだけ出る。
- `kibi:process` の `spawn`・`spawn-with-handles` は、変換できないコンポーネントの子で `no-exec` を返す（今までは `BLOCKING` の枠が尽きていれば `nfile`、でなければ動いた）。init がそうなら `INIT_NOT_EXECUTABLE` で、コンソールに理由が出る（p1 と同じ道）。
- 保存形式には触れない（コンポーネントは `UNSAVEABLE`）。改ざんされた保存データが変換できないコンポーネントを指していれば、復元は `MODULE_MISMATCH` の冷えた起動になる（今までも冷えた起動で、原因の種類が変わるだけ）。
- `maxBlockingProcesses` の設定は消さない（p1 の `asyncifyExecutables = false` などでまだ使う。消すかは #251 と合わせた別の task）。

## テスト

- `ComponentWaitPointTest`: shim の形のモジュールをまたいだ巻き戻し・巻き直し（燃料あり・なし）、export していない表に移した参照への `call_indirect`、`NONE` のホスト、`BLOCK` のホストで変換しない、変換しない指定、ゲストからゲストの下の待ち、dtor が止まりうる、変換できない作り（`AsyncifyUnsupported`。元の関数の番号とモジュールが理由に入る）、変換の決定性、巻き戻しでハンドルを作らない。
- `AsyncifyTest`: 外の型を `call_ref` と、外の参照が入りうる表への `call_indirect` に当てる範囲。
- `AsyncP2Test`: プロンプトで待つ p2 のコマンドがスレッドを持たない、p1 の `BLOCKING` と出力が同じ（深い再帰・並べ替えを含む）、燃料、`poll` のタイマーの引き継ぎ、待っている間に殺したときの片付け、パイプがいっぱいのときの書き込み。アダプターを通らない WAT のコマンドで、`write`・`write-zeroes`（巻き直したら許可量をもう一度引かず、残りだけを順に書く）、`blocking-skip`、`pollable.block`、`blocking-splice`（入力待ち・出力の空き待ちで巻き直して最初からやり直す。巻き戻した書き込みに読んだバイト（`pendingSplice`）が残っていれば読まずに書く）。`splice` が読んだ後に書けずに巻き戻す道（`pendingSplice` を作るところ）は、読んだ後の書き込みの前に出力を埋める手段が race 以外にないので、置いた状態から巻き直す道だけを確かめている。core モジュールの start 関数（`LOADING`）の中の待ちは巻き戻さずスレッドを持って待つこと。時計で待っている・書きかけの `splice` を持つ待機点型のコンポーネントの台の写しで、プロセスを殺し、退避した値・やり直しの値・一時的なタイマー・ホストの resource を写しに残さないこと。
- `HostWaitTest`: 実物のホストの `UNWIND`・`BLOCK` の一覧（#800 の後は `BLOCK` は空）。
- `ComponentFuelYieldTest`（#803）: カーネルを通さず、モジュールをまたいだ燃料切れの巻き戻し・巻き直し（`endFuelRewind` で戻り、`onRewound` を呼ばない）、段1を断られたモジュールだけ段2で変換して待機点型のまま動くこと、理由のログが 1 回だけで `WaitPointRejectionLog` と別であること、段1を試さない指定。
- `AsyncFuelP2Test`（#803）: アダプターで包んだ p2 とネイティブの p2（`fuel-spin-p2`）で、止まりうる関数の中の燃料切れはスレッドを持たずに待機点で待つこと、2 本が譲り合うこと、書き込みの待ちと燃料の巻き直しが交互に来ても出力が正しいこと、葉の関数の前借りと、前借りの上限を超えたときにスレッドを持って待つこと（残る性質）、ゲストからゲストへの呼び出しの下（`canUnwind` が偽）の燃料切れは前借りで callee から戻り、外の止まりうる関数で巻き戻すこと、callee が前借りの上限を超えて計算し続けたらスレッドを持って待つこと（手で書いたコンポーネント。`onComponentFuelExhaustedUnwindable` の `!canUnwind` の分岐は、ゲストからゲストの下に止まりうる関数があれば変換を断るので、今は通らない守りの分岐）、ホストの関数が呼んだゲスト（realloc・post-return。止まりうる関数か否かの両方）の中の燃料切れは、前借りも巻き戻しもせずスレッドを持って待ち、正しく終わること（手で書いたコンポーネント。p1 の `WasiP1KibiTest` の cabi_realloc の確かめに当たる）、燃料待ちの間の kill（退避した値と RAM を残さない）と写し、多数の台（12 台 × 4 本、許可 4）でスレッドを持つプロセスが許可の数程度に収まること（変える前は 48 本すべてが持った）。
- ゲームの中の多数台（#821）: `runLoadTestServer -PloadTestOs=standard-p2` で、標準 OS のシェルから計算し続ける `fuel-spin-p2` を 1/10 の台に動かし、燃料の数え口とカーネルのスレッドを測る（結果は `docs/load-test.md` の「#821 の結果」）。
- #844: `ComponentWaitPointTest` で、`requireWaitPoints` なら 3 つの理由（ゲストからゲスト・dtor・v128）を `AsyncifyUnsupported` を原因にした `WasmLoadException` で断り、`Program.unsuspendableReason` で取り出せ、コンパイルせず、`WaitPointRejectionLog` も出さないこと。変換できるもの・止まりうる関数がないものは断らないこと。`ComponentExecTest` で、変換できないコンポーネントの `exec` が 2 回とも `NOEXEC` で、失敗を覚えて読み込みは 1 回、プロセスもブロッキング型の枠も専用スレッドも使わないこと、p1 の親の `spawn` が `no-exec` を返すこと。
