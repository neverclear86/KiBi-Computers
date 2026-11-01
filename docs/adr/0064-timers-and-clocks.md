# 0064: タイマー・sleep・時計

- 状態: 採用
- Issue: #64（task）。前提: ADR 0043（カーネル全体の設計）、0044（MC 側との境界。`MachineClock`）、0063（イベントキュー）
- 後続: #72（p1 の `poll_oneoff` の時計の購読）、#73（`kibi:*` の WIT と p1 の import）、#81・#82（保存）、#87（復元時の「時間が飛んだ」イベント）、#264・#266（p2 の pollable・p3 の waitable-set）、#251（仮の値の確定）
- 番号は Issue 番号にそろえている

## 決めたこと（要約）

- コードは `core.kernel.time`。どれも `internal`。公開 API・ABI・保存のバイト形式は足さない
- マシンの時計は4つ: **実時間**（環境の `realtimeNanos`）、**ゲーム内時間**（環境の `worldTicks`）、**単調時計**（マシンを作ったときが 0。WASI の `MONOTONIC`）、**起動からの経過**（tick。起動したときが 0。ADR 0044 の「tick の番号から求める」）
- **単調時計と経過時間は、マシンが動いていた（読み込まれていた）時間だけを数える。** 保存・復元をまたいでも続く（止まっていた分は進まない）。実時間とゲーム内時間は飛ぶ
- **タイマーはプロセスが開くカーネルオブジェクト**（`Timer`。ハンドル表に読む権利だけで入る）。期限は単調時計で持ち、鳴った回数を数え、1 以上の間は `READABLE`（Linux の timerfd と同じ形）。1回だけと繰り返しがある
- **期限を待つのはサーバーに1本のスレッド `KiBi-timer`**（`ScheduledThreadPoolExecutor`。最初に使うときに作る）。メインスレッドの tick には乗らない。精度は tick（50 ms）より細かい
- `sleep` は、ブロッキング型では `ctx.sleep(nanos)`（一時的なタイマーを作って `block`）、待機点型ではタイマーを作って `StepResult.Wait` に `timerWaitKey` を入れる
- タイマーのイベント: タイマーを作るときにイベントキューを指定すると、鳴るたびに**そのキューにだけ** `timer` のイベント（値は `I64(tag)`・`I64(回数)`）を入れる（同報しない。ほかのプロセスのタイマーが届かない）
- 燃料の費用（システムコールの課金）は付けない（#47・#251）

## 構造

```
KernelState（保存の対象）
 ├─ clocks: KernelClocks（単調時計・経過時間のずれ）
 └─ objects: ObjectTable
     └─ Timer（プロセスがハンドルで持つ）
         ├─ armed、deadlineNanos（単調時計）、intervalNanos（0 なら1回だけ）
         ├─ expirations（前回の read から鳴った回数）
         ├─ generation（arm・disarm のたびに増える）
         └─ eventQueueId・eventTag（イベントを出すときだけ）

Machine（実行時だけ）
 └─ timers: MachineTimers（タイマーのID → KiBi-timer の予定）

MachineScheduler
 └─ timerService: TimerService（KiBi-timer 1本。テストでは時計を手で進めるものに差し替える）

期限 → KiBi-timer → Machine.onTimerDue（カーネルロックを短く取る）
  → generation を照合 → Timer.expire（回数を足し、(id, READABLE) を知らせる）
  → イベントを出すタイマーなら、そのキューへ timer のイベント
  → 繰り返しなら次の期限の予定を入れる
```

### カーネルの中の口

| 口 | 振る舞い |
| --- | --- |
| `ctx.monotonicNanos()`・`ctx.uptimeTicks()` | 単調時計（ナノ秒）・起動からの経過（tick）。WASI p1 の `clock_time_get(MONOTONIC)` もこれ |
| `ctx.createTimer(eventQueue, tag)` | 止まった状態のタイマーを作ってハンドル表に入れる。キューでない・読めないハンドルは `BADF`、`timer` を受け取らないキューは `INVAL`、満杯は `MFILE` |
| `ctx.setTimer(handle, time, intervalNanos, absolute)` | `absolute` なら単調時計の期限、そうでなければ今からの時間（0 以下ならすぐ）。数えた回数は 0 に戻す。タイマーでないハンドルは `BADF`、間隔が負か `MIN_INTERVAL_NANOS` 未満の正は `INVAL`、動いているタイマーが上限なら `ResourceLimitException` |
| `ctx.cancelTimer(handle)` | 止める（数えた回数も 0 に戻す） |
| `ctx.readTimer(handle)` | 鳴った回数を取り出して 0 に戻す。待たない |
| `ctx.timerWaitKey(handle)` | `WaitKey(id, READABLE)` |
| `ctx.sleep(nanos)` | ブロッキング型だけ。ハンドル表を使わない一時的なタイマーで `block` し、終わったら（止められたときも）閉じる |
| `Machine.captureClocks()`・`restoreClocks(snapshot)` | 保存・復元（#81）が使う。下の「保存・復元と時間の飛び」 |

- 実時間での期限（p1 の `poll_oneoff` の `REALTIME` の `ABSTIME`）は、設定するときに単調時計の期限へ写す（#72。実時間がその後飛んでも追わない）
- タイマーの fd への `fd_read`・`fd_write` はイベントキューと同じ扱い（読み書きを持たない種類のオブジェクト。ADR 0058）

### 鳴り方

- 遅れて鳴ったとき（サーバーが重い、シングルプレイの一時停止の後など）は、繰り返しのタイマーは過ぎた回数をまとめて数え、次の期限を「今より後のいちばん近い回」に進める。溜まった回を続けて鳴らさない
- **読まれていない繰り返しのタイマー**（イベントを出さないもの）は、鳴って回数が 1 以上になったら次の予定を入れない。`readTimer` のときに過ぎた回をまとめて数え、予定を入れ直す。読まれない繰り返しのタイマーが `KiBi-timer` を起こし続けないため
- イベントを出すタイマーは、鳴るたびにイベントを入れ、数えた回数はイベントで知らせたものとして 0 に戻す（`readTimer` は 0、`READABLE` にもならない）。キューが満杯・マシン全体の量が上限なら、イベントキューの規則どおり捨てて数える（ADR 0063。`EventHub.deliverTo`）。キューが閉じていれば捨てる
- タイマーが閉じられたら（参照カウント 0）、`WakeSink.timerClosed` で予定を取り消す。予定はヒープからすぐ外す（長いタイマーを作っては閉じても溜まらない）。期限の知らせが取り消しと行き違っても、generation の照合で何もしない
- `Machine.close()` で、そのマシンの予定をすべて取り消す。スケジューラの `close()` で `KiBi-timer` を終える（`awaitTermination` が待つ）

## 保存・復元と時間の飛び

チャンクがアンロードされるとマシンは外れ（#120）、読み込まれたら保存から戻す（#81）。その間について:

| 時計・状態 | 戻したとき |
| --- | --- |
| 単調時計・経過時間 | 保存した時点の値から続く（`KernelClocks.restore` がずれを決め直す） |
| タイマーの期限 | 単調時計で持つので、残りの時間から続く。止まっていた間に期限が来るはずだったタイマーも、戻した瞬間にまとめて鳴らない |
| 実時間・ゲーム内時間 | 環境の値そのもの（飛ぶ）。`restoreClocks` が飛んだ量（`TimeJump`）を返し、#87 がイベントにする |

- `KernelClocks` のずれは今の JVM の時計と tick の番号に対する値なので、そのままでは書き出せない（サーバーを起動し直すと起点が変わる）。**#81 は `captureClocks()` の `ClockSnapshot`（単調時計・経過時間・実時間・ゲーム内時間）を書き出し、戻すときに `restoreClocks` を呼ぶ**。`restoreClocks` は動いているタイマーの予定も入れ直す（予定は実行時だけで保存しない）
- 保存の前の一時停止（`pauseAll`）の間も単調時計は進む（短いため）。シングルプレイの一時停止（tick が止まる）の間も単調時計は進み、経過時間（tick）は進まない。解いた後、期限の過ぎたタイマーは1回にまとめて鳴る

## 規模

- 待っているだけのタイマーに tick ごとの処理はない。期限は `KiBi-timer` 1本がヒープで待つ（入れる・取り消すのは O(log n)）。台数によらずスレッドは1本で、待っている待機点型のプロセスはスレッドを持たない（`ScaleTest` で 1,000 台がタイマーを待つのを確かめる）
- 鳴らす仕事（カーネルロックを短く取る）は `KiBi-timer` の上で、メインスレッドに乗らない
- 1台で動かせるタイマーは `MAX_ARMED_TIMERS` 個まで、繰り返しの間隔は `MIN_INTERVAL_NANOS`（1 tick）以上。最悪でも 1台あたり 1 秒に 128 × 20 回。読まれていない繰り返しのタイマー（イベントを出さないもの）は1回鳴ったら止まる

仮の値（確定は #251）: 動かせるタイマー 1台 128 個、繰り返しの最小の間隔 50 ms、`KiBi-timer` のスタック 1 MiB。

## データ形式

保存のバイト形式は決めない（#81・#82）。メモリ上の持ち方だけを決める。

- `Timer`: `armed`、`deadlineNanos`、`intervalNanos`、`expirations`、`generation`、`eventQueueId`、`eventTag`。どれも番号で、実行時だけのものを参照しない（`KernelStateIsolationTest`）。`generation` は書き出さなくてもよい（戻したら予定を作り直すため）
- `KernelClocks`: 上のとおり、書き出すのは `ClockSnapshot`
- 待っているプロセスは、これまでどおり `Process.wait` に `WaitKey` の並びを持つ。ブロッキング型の `sleep` は保存できる待ちではない（ADR 0043。保存できるのは待機点型だけ）

## 決めないこと

- WASM から見える ABI: p1 の `poll_oneoff` の時計の購読（#72）、`kibi:*` の WIT と p1 の import（タイマー・sleep・`uptime`・ゲーム内時間を見せる形、イベントの値の写し方。#73）、p2 の `wasi:clocks`・pollable（#264）、p3（#266）
- 保存のバイト形式（#81・#82）、復元時の「時間が飛んだ」イベントの種類と中身（#87）
- Lua の `os.sleep`・`event` との対応（#94・#95）
- 燃料の費用（#47・#251）、仮の値の確定（#251）
