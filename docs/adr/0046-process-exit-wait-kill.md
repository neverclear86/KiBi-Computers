# 0046: プロセスの終了待ち・強制終了・終了コード

- 状態: 採用
- Issue: #46（task）。前提: ADR 0043（カーネル全体の設計）、0045（プロセスの生成）、0049（起動）、0060（TTY）
- 後続: #74（`proc_wait`・`proc_kill` 相当のシステムコール、p2/p3 の resource）、#73（WIT）、#63（シグナルをイベントで届ける形）、#82（保存のバイト形式）、#251（仮の値の確定）
- 番号は Issue 番号にそろえている

## 決めたこと（要約）

- どれも `core.kernel` の `internal`。公開 API・ABI・保存のバイト形式は足さない。`Machine` の `public` なメンバーも変えない
- プロセスごとにカーネルオブジェクト `ProcessObject` を作り、終了待ちを既存の `WaitKey` の待ちに乗せる
  - `WaitCondition.EXITED`: そのプロセスが終わった。特定の子はその子の `EXITED` を待つ
  - `WaitCondition.CHILD_EXITED`: 刈り取られていない、終わった子がある。どれかの子は**自分の** `CHILD_EXITED` を待つ（待っている間に子が増えても取りこぼさない）
  - p1 の `poll_oneoff`・p2 の `pollable`・p3 の waitable-set からも同じ key で待てる
- 終わりの記録は `ExitStatus`（終わり方 `EXITED` / `KILLED` / `CRASHED`、32 ビットの終了コード、シグナル）
- 刈り取りは親だけができる（`ProcessContext.tryReap`・`childExitKeys`・`waitChild`）
- シグナルは `INTERRUPT`・`QUIT`・`TERMINATE`・`KILL` の4つ。効き方は「無視」か「止める」だけ（ハンドラーはない）
- Ctrl+C・Ctrl+\ は、コンソールの TTY の前のプロセスに `INTERRUPT`・`QUIT` を送る
- 親が先に終わったら、子は親なし（`parentPid = 0`）に付け替える。init には付け替えない
- 終了待ち・kill に専用の燃料の費用は付けない（燃料の細かい計算は後で考える）

## 終わりの記録

`ProcessRunner.finishLocked` の終わり（カーネルロックの中）で `ProcessLifecycle.onFinishedLocked` が行う:

1. `Process.termSignal` を決め（`KILLED` のとき、止めたシグナル。`stop()` なら `null`）、`ProcessObject.status` に `ExitStatus` を入れて `EXITED` を起こす
2. 親がいれば、親の `ProcessObject.pendingChildExits` を増やして `CHILD_EXITED` を起こす。親がいなければ「親のないゾンビ」の並びに足す
3. 子の扱い（下の「親が終わったとき」）

| 終わり方 | `code` | `signal` |
| --- | --- | --- |
| `EXITED` | 終了コード（32 ビットそのまま。p1 の `proc_exit(u32)` はビット列のまま） | `null` |
| `KILLED` | 0 | シグナル。`stop()`・再起動なら `null` |
| `CRASHED` | 0 | `null`（理由の文字列は保存しない。実行時の `rt.lastError` はそのまま） |

0〜255 への切り詰め、p2 の `wasi:cli/exit`（`result`）への写し方、シェルの `130` のような整数1つへの写し方は ABI 層（#74）で決める。

## 刈り取り

- `tryReap(selector)`: 待たない（WNOHANG 相当）。`ChildSelector.Any` は pid の割り当ての順でいちばん古い、終わった子（終わった順ではない）
- `childExitKeys(selector)`: `Pid` → その子の `EXITED`、`Any` → 自分の `CHILD_EXITED`。待つ子がいなければ空。待機点型は `StepResult.Wait` で待ち、起きたら `tryReap` する
- `waitChild(selector)`: ブロッキング型だけ。`tryReap` が `Running` なら `block(childExitKeys)` で待ち直す
- 結果は `Reaped(status)`・`Running`・`NoChild`（子がない、`Pid` が自分の子でない・自分・孫）

刈り取ると（`Machine.reapLocked`）、表と実行時の部分（`runtimes`）から外し、親の `pendingChildExits` を減らし、カーネルが持つ `ProcessObject` の参照を返す（#74 の resource が参照を持っていれば、オブジェクトは `status` を持ったまま残る）。専用スレッドがまだ生きていれば `retiredThreads` に足し、**同時に終わったスレッドを外す**。再起動しないマシン（シェルがコマンドを起こしては刈り取り続ける）でも、スレッドの参照（とそこからつかむ `WasmModule` など）が増え続けない。

## シグナル

```kotlin
internal enum class KillSignal(val ignorable: Boolean) { INTERRUPT(true), QUIT(true), TERMINATE(true), KILL(false) }
internal enum class KillResult { DELIVERED, IGNORED, ALREADY_EXITED, NOT_FOUND, NOT_PERMITTED }
```

- `ProcessContext.kill(pid, signal)`: 待たずに返す（止まったことは終了待ちで知る）。自分に送って止まるときは `ProcessKilledException`
- `ProcessContext.setSignalIgnored(signal, ignored)`: `KILL` は `IllegalArgumentException`。無視の設定は**子に引き継がない**（シェルが Ctrl+C を無視しても、シェルが起こした前のプロセスは止まる）
- マシンの中のどのプロセスにも送れる（マシンの中は1人の持ち主なので、権限は分けない）。**init には送れない**（`NOT_PERMITTED`。init を止めるのは電源・`requestShutdown` の役目。Linux の init がシグナルを受けないのに合わせた）
- ハンドラーは作らない。受けて何かしたいとき（Lua の REPL の Ctrl+C を行の取り消しにするなど）は、イベントキュー（#63）で届ける形を後で足す

### 止め方

`Machine.stopLocked` の「1プロセスを止める」部分を `killRuntimeLocked(rt, signal)` に切り出し、`stop()` とシグナルの両方から使う。止める印・割り込み・スレッドのない待機点型はその場で終える、の今までの確認点のままなので、計算中・燃料待ち・イベント待ち・ホスト待ち・読み込み中のどこにいても止まる。止めてもほかのプロセスとマシンの状態は変えない。

### メインスレッドでのホスト呼び出しの取り消し

- `PendingHostCall` に出したプロセスの pid を持たせ、`submitHostCall(pid, call)` にした（pid 0 はプロセスでない呼び出し）
- 1プロセスを止める（kill・Ctrl+C・`stop()`）と、そのプロセスの**まだ列にある**呼び出しを `ProcessKilledException` で終えて外す。ほかのプロセスの呼び出しは順番を変えずに残す。普通に終わったとき（`finishLocked`）も同じ pid について取り消す。`stop()` の最後に全部を取り消す処理も残す
- メインスレッドがもう取り出して実行し始めた呼び出しは、最後まで実行する（途中で止めるとワールドが半端になるため。結果は捨てる）。`ProcessContext.callOnMainThread` の KDoc に書いた
- 止められたかの確かめと列への積み込みを、`submitHostCall` の同じロックの中で行う（前は確かめと積み込みのロックが別で、その間に kill されると、kill の後に積まれた呼び出しが実行されえた）

### Ctrl+C

`Machine.ttySignalHandler` の既定を「TTY の前のプロセス（`foregroundPid`）に、`TtySignal.INTERRUPT` → `KillSignal.INTERRUPT`、`QUIT` → `KillSignal.QUIT` を送る」にした。前のプロセスが 0・終わっている・init なら何もしない（結果は捨てる）。カーネルロックの中で呼ばれるので、待たない・WASM を呼ばない。差し替えの口（テスト用）は残す。

## 親が終わったとき

- 終わっていない子は親なし（`parentPid = 0`）に付け替える。子は止めない（POSIX と同じく、後ろで動く子は動き続ける。`stop()` はこれまでどおり全部止め、init が終わればマシンを止める）
- 刈り取られていない子のゾンビは、その場で刈り取る（待つ相手がいないため）
- init には付け替えない: init（`/sbin/init`）は利用者やディスクの持ち主が書くプログラムで、終わった子を刈り取り続ける作りを求めたくない。たまらないことはカーネルの側で保証する（下）

### 親のないゾンビと上限

親のないプロセス（init、付け替えた子、テストで直接作ったもの）は誰からも刈り取られない。表に残して状態を読めるようにする（#49 の `init exited with code` のメッセージ、テスト）が、数が増えたら古い順に自動で刈り取る:

- `ProcessTable` に「親のないゾンビ」の並び（終わった順）を持つ
- 上限（`maxProcesses`）は「終わっていないプロセス＋親のいるゾンビ」で数える（Unix のプロセス数の上限と同じく、親が刈り取らないゾンビも数える。子を作っては待たないプロセスで表が膨らまない）
- 生成のとき、表の大きさ（全部）が `maxProcesses` × 2 に達していたら、親のないゾンビを古い順に刈り取って空ける（2 倍は仮の値。確定は #251）
- 起動のとき（`beginBoot`）、前の起動のゾンビの `ProcessObject` を返してから表を空にする

## 保存（#82 への申し送り）

`KernelState` の下に足したもの（番号・列挙・`Int` だけ。`KernelStateIsolationTest` で確かめる）:

| 項目 | 型 | 内容 |
| --- | --- | --- |
| `Process.parentPid` | `Int`（`var` にした） | 親の pid（親なし・付け替えた後は 0） |
| `Process.objectId` | `Int` | `ProcessObject` のオブジェクトID |
| `Process.termSignal` | `KillSignal?` | シグナルで止められたときのシグナル |
| `Process.ignoredSignals` | `Set<KillSignal>` | 無視するシグナル |
| `ProcessObject.pid` / `status` / `pendingChildExits` | `Int` / `ExitStatus?` / `Int` | 上のとおり |
| `ProcessTable` の親のないゾンビの並び | pid の並び | 終わった順 |

実行時だけ（保存しない）: `ProcessRuntime.killSignal`。`pendingChildExits` と親のないゾンビの並びは表から作り直せる（保存しても作り直してもよい。並びは終わった順の情報なので、保存しないなら pid の順で代える）。

## 決めないこと

- ABI（`proc_wait`・`proc_kill` 相当、p2/p3 の resource、終了の理由の整数への写し方）: #74・#73
- シグナルをプロセスに届けて処理させる形: #63
- 仮の値（親のないゾンビを刈り始める表の大きさ `maxProcesses` × 2）の確定: #251
- 燃料の費用: 後で考える
