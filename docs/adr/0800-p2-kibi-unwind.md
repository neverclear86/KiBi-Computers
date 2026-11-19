# 0800: p2 の kibi:device の invoke と kibi:process の spawn を巻き戻して待つ

- 状態: 採用
- Issue: #800（task）。前提: ADR 0757（p2 の待機点）、0685（kibi:* の p2）、0077（p1 の invoke の待ち）、0074（p1 の spawn）

## 決めたこと（要約）

- `kibi:device/bus` の `[method]device.invoke`、`kibi:process/process` の `[static]process.spawn`、`kibi:process/process-poll` の `spawn-with-handles` を、待ちうる関数（`WasiP2Io.waiting`。`HostWait.UNWIND`）にする。これらを import するコンポーネントも待機点に変換でき、`WAIT_POINT` で動く（デバイスの結果や子の読み込みを待つ間、スレッドもブロッキング型の枠も使わない）。
- 待ち方の判定は、`wasi:*` と同じく本体から決める（`WasiP2Io.waitOf`）。`kibi:*` の名前の表（`BLOCKING_FUNCTIONS`）と、その WIT の名前の検査は消した。
- 待ち方は p1 の UNWIND（`WasiP1KibiDevice`・`WasiP1KibiProcess`）と同じ。巻き戻せないとき（ブロッキング型、core モジュールの start 関数の中の `LOADING`、ゲストからゲストの下）は、今までどおり `ctx.invokeDevice`・`ctx.execWithHandle` でスレッドを持って待つ。
- **ABI・保存形式・公開 API は変えない。** コンポーネントは今までどおり `UNSAVEABLE`。足した口（`ProcessContext.beginDeviceInvoke(HandleEntry, …, place)`・`finishExecWithHandle(ExecWait, place)`・`checkHandleRoom(place)`）は内部のもの。
- 変換できなかったコンポーネントを `BLOCKING` に落とす扱い（#757 のオーナーの回答）は変えない。そろえるかは #801 で決める（このタスクで、ホスト側の理由はなくなった。残るのはゲスト側の作りだけ）。

## invoke

- 新しい呼び出しで巻き戻せるとき: 引数を確かめ（p1 と同じ順・同じ上限）、`ctx.beginDeviceInvoke` でデバイスを 1 回だけ引いて始める。直接のメソッドはその場の結果を返す（`pending-call` を作らない）。
- メインスレッドのメソッドは、`pending-call` をプロセスの**ホストの resource の表**（`HostResourceTable`）に、ゲストに rep を渡さない隠れた項目（`KernelEntry`、label `pending-call`）として置く。p2 の resource は fd 表に入れない（ADR 0685）。`MAX_PENDING_CALLS` には数えない（p1 と同じ。1 プロセスに同時に 1 つ）。
- 待っている呼び出し（rep と `READABLE` の key）は、プロセスごとの `ComponentSuspend.pendingInvoke` に持つ。ホスト（`KibiP2Host`）はサーバー全体で 1 つで状態を持たないので、p1 のように ABI 層のフィールドには置けない。実行時だけの状態で、`AsyncWait`（カーネルの状態・写しに入る）には入れない。
- 巻き直して着いたら、引数を確かめ直さず（メソッドを二重に走らせない）に結果を受け取る。結果がまだなら同じ key で巻き戻し直す。受け取ったら表から外して閉じる（結果の燃料はこのときだけかかる）。着いたのに待ちがない（保存できないので起きないはず）なら、やり直さずに `failed("interrupted")`（p1 と同じ）。
- 待っている間に殺されたら、表を閉じるとき（`closeAll` → `PendingDeviceCall.onLastClose`）に呼び出しが取り消される（#532）。
- **待機点型で、メインスレッドのメソッドを呼んだときにホストの表が満杯なら `kernel(mfile)`**（`pending-call` を置けない。メソッドは走らせない）。ブロッキング型は置かないのでならない。p1 の待機点型の `invoke` が fd 表の満杯で `mfile` になるのと同じ差。

## spawn・spawn-with-handles

- p1 の `spawnUnwinding` と同じ形: `ctx.beginExec` の `ExecWait` を `ComponentSuspend.pendingSpawn` に持って巻き戻し、巻き直して着いたらそれで子を作る（`ctx.finishExecWithHandle(wait, place)`）。`ExecWait` は実行時だけの状態（`AsyncWait` に入れない）。
- 子のハンドルは待ち終わってから表に置く（`HostWait.UNWIND` の約束）。置き場の空きは読み込みの前に確かめ（`checkHandleRoom(place)`）、子を作る前にカーネルがもう一度確かめる。
- 引数の確かめと `handles` の borrow の解決（`StdioSource.Object` にする）は始めるときの 1 回だけ。待っている間ゲストは止まっているので、借りたハンドルは閉じられない。
- 巻き直して着いたのに手元にない（起きないはず）なら `beginExec` からやり直す（子はまだない）。読み込みが始めから終わっていれば、巻き戻さずに子を作る。
- 待っている間に殺されたら、カーネルの後始末（`finishLocked` が `HostCompletion` を閉じ、始まっていない読み込みを取り消す）に任せる。`finishExecWithHandle` を呼ばないので子は作られない。

## 燃料

- `P2.guard` の呼び出しごとの燃料と lift の燃料は、巻き直しのたびにかかる（p1 も巻き直して着いた `invoke`・`spawn` を 1 回と数える）。デバイスの呼び出しの燃料は `beginDeviceInvoke` の 1 回だけ。

## 制約と残り

- `kibi:device`・`kibi:process` を使う p2 のコマンドも `WAIT_POINT` になり、`maxBlockingProcesses` に数えられなくなる。燃料待ちのスレッドが数えられない穴（ADR 0757 の「制約と残り」、#803）の対象になるコマンドが増える。直すのは #803 の範囲。
- ほかに、tick ごとに台数に比例するコストは増えない。待つ間に持つのは、`pending-call` の key か `HostCompletion` の key だけ。
- core モジュールの start 関数の中の `invoke`・`spawn`（`LOADING`。巻き戻さずにスレッドを持って待つ）は、コンポーネントの単体テストでは確かめていない。wit-component は、メモリ・realloc を使う lower を main のインスタンスの後に fixup が埋める表を通すので、start 関数からは呼べない（ADR 0757 のテストの注記と同じ）。巻き戻せるかの判定（`ComponentSuspend.canUnwind`）は `wasi:*` と同じものを使う。

## テスト

- `KibiP2WaitPointTest`: メインスレッドのメソッドの `invoke` が巻き戻して待つ（スレッド・専用スレッド・ブロッキング型の枠を使わない、待機点にある、`AsyncWait` に入れない）。起こされても結果がまだなら巻き戻し直し、メソッドは 1 回だけ。受け取ったら隠れた `pending-call` を外す（2 回目の待ちで表の大きさが元に戻っている）。直接のメソッドはその場で返る。待っている間に殺すと取り消される。表が満杯なら `kernel(mfile)` でメソッドを走らせない。待っている台の写しは、プロセスを殺し、ホストの resource・`pending-call` を残さず、元の呼び出しを取り消さない。`spawn-with-handles`・`spawn` は読み込みをゲートで止めて巻き戻して待ち、子の標準出力をパイプにでき、開くと子を 1 つずつ作る。待っている間に殺すと子を作らない。待っている台の写しで元の読み込みは続く。
- `KibiP2WaitPointTest`（失敗と「起きないはず」の守り）: `invoke` の `failed`・`detached` を巻き直して受け取った後、プロセスが生きている間（来ない時計で待たせて）に、隠れた `pending-call` が表からもカーネルからも外れている（終わった後の表は `closeAll` で必ず空になるので、終わる前に見る）。巻き直して着いたのに `pendingInvoke` がなければ、メソッドを呼び直さずに `failed("interrupted")`。新しい `invoke` の時点で `pendingInvoke` が残っていれば、それを閉じてから始める。`spawn` で巻き直して着いたのに `pendingSpawn` がなければ `beginExec` からやり直して子は 1 つだけ。新しい `spawn` の時点で残っていれば、その読み込みの待ちを閉じてから始め、残っていた読み込みで子を作らない（p1 の `forgetPendingInvokeForTest`・`forgetPendingSpawnForTest` のテストに当たる。p2 は `ComponentSuspend` の値を直接書き換える）。
- `HostWaitTest`: kibi の `UNWIND` が 3 つ、`BLOCK` は空。
- `ComponentExecTest`: `BLOCKING` のコマンドを、ゲスト側の理由（止まりうる関数の v128 のローカル）で変換できないものに差し替え、「`BLOCKING` は上限で `NFILE`」「`WAIT_POINT` は上限に関係なく動く」の両方を確かめ続ける。
