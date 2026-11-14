# 0049: 起動シーケンス（/sbin/init）と停止・再起動

- 状態: 採用
- Issue: #49（task）。前提: ADR 0043（カーネル全体）、0044（MC 層との境界）、0045（プロセスの生成）、0051（VFS）
- 後続: #99（起動部品の中身と探す順。ADR 0099 で決めた: 優先する起動ディスクを先に試す `BootConfig.preferredBootDisk`、ドローンなど向けのプログラムだけの起動 `BootConfig.firmwareProgram`）、#52・#55（起動ディスクのファイルシステム）、#306（停止・再起動のシステムコール。ABI は #73）、#81（戻したマシンの init）、#121（電源ボタン）
- 番号は Issue 番号にそろえている

## 決めたこと（要約）

- 起動はカーネル（`core`）の仕事。接続層は `BootConfig`（起動部品が挿さっているか、起動ディスクの候補の識別子）を渡すだけ。どのディスクがつながっているかはワールドの情報なので、接続層がメインスレッドで集める
- `Machine.boot(config)` は待たずに返す。ディスクを探し、`/sbin/init` を読み込むのは、スケジューラのワーカーのスレッド（重いため）
- init は常に pid 1、親なし、argv `["/sbin/init"]`、環境変数なし、cwd `/`、標準入出力はコンソール
- 起動のたびに、ゾンビを表から外し、pid を 1 から振り直し、マウントをすべて外す。コンソールの TTY は残す（前景のプロセスは 0 に戻す）
- init が `stop()`・再起動によらずに終わったら、コンソールに書いて残りのプロセスをすべて止め、`STOPPED` にする。自動では再起動しない（すぐ落ちる init で起動を繰り返さないため）
- 再起動は「すべて止まってから `MachineHost.onRebootRequested` を知らせる」形。既定の実装は前回の `BootConfig` で `boot` する。接続層は上書きして、その時点のディスクで設定を作り直せる
- 起動の失敗は、固定の英語のコンソールのメッセージと `MachineHost.onBootFailed` の両方で知らせる（コンソールが先）
- init の pid は `KernelState.initPid`（保存の対象）に置く（#365）。ほかに足す状態はどれも実行時だけ。起動の途中・再起動の待ちの間は `Machine.isAtWaitPoint` を `false` にして、保存しない

## 公開するもの（`pro.ikili.kibicomputers.core.kernel`。接続層向け）

ADR 0043・0044 と同じく、`pro.ikili.kibicomputers.api` には何も足さない。

- `BootConfig`（`builder()`、`isFirmwarePresent`、`bootDisks`、`INIT_PATH`）
- `BootFailure`（`NO_FIRMWARE`・`NO_BOOT_DISK`・`NO_INIT`・`INIT_NOT_EXECUTABLE`・`INIT_START_FAILED`）
- `Machine.boot`・`reboot`・`lastBootConfig`・`isBooting`
- `MachineHost.onBootFailed`・`onRebootRequested`（既定の実装つき。`fun interface` のまま、必須は `onStateChanged` だけ）

## 起動の手順（`BootSequence`）

1. `boot(config)` は、マシンが `STOPPED` で、起動中でなく、スケジューラが閉じていなければ受け付ける
2. ワーカーで: 前の起動のスレッドが登録を外すのを待ち（`STOPPED` になってからスレッドが抜けるまでのわずかな間）、ゾンビ・pid・マウントを片付ける
3. 起動部品がなければ `NO_FIRMWARE`
4. 候補を前から順に `BootVolumeOpener` で開く。開けない（`null`・例外）ものは飛ばす。`/sbin/init` が**普通のファイル**としてある最初のディスクを `/` にマウントする（`source` は `disk:<識別子>`）。1つも開けなければ `NO_BOOT_DISK`、開けたが init がなければ `NO_INIT`（ディレクトリの `/sbin/init` も「ない」扱い）
5. `Machine.exec("/sbin/init", KernelDrivers.DEFAULT, …, asInit = true)`。`ACCES`・`FBIG`・`NOEXEC` は `INIT_NOT_EXECUTABLE`、上限・燃料を計測しないモジュールなどは `INIT_START_FAILED`
6. 失敗したら、マウントを外して知らせる。マシンは `STOPPED` のまま（状態の通知は出ない）。ケース（`ComputerCase`）はこの知らせでマシンを外し、GUI をエラー（`BOOT_FAILED`）にする（#641、ADR 0085 の追記）

`KernelDrivers.DEFAULT` は、`_start` を export するモジュールを `WasiP1Driver` で動かし、それ以外は `NOEXEC`。Component Model は #259 の層が足す。

### 取り消し

- 起動の途中の `stop()`・`reboot()` は、印（`bootCancelled`）を立てるだけ。手順は区切りごとに印を見て、何も知らせずに終える
- init を作る `spawn` は、表に足すのと同じカーネルロックの中で印を確かめる（`asInit`）。作れたら、その場で init の pid を覚え、起動中の印を下ろす。これで「起動の途中の `stop()` が init を止め損ねる」隙間がない。起動中の印は、同じロックの区間で `RUNNING` にした**後**に下ろす。ロックを取らずに読む側（`Machine.isRunningOrBooting`。印を先に、状態を後に読む）が、区間の途中で「止まっていて起動中でもない」と見ないため（#625）。再起動の途中も含めて読む側（`Machine.isRunningBootingOrRebooting`。画面のつなぎ直しが使う）は、起動中の印 → 状態 → 再起動の求め（`rebootRequested`）→ 積んだ再起動の通知（`rebootNotified`）の順に読む。書く側は次の印を立ててから前の印を下ろす（`reboot()` は求めの印を立ててから止める。求めを通知の列に積むときは、通知の印を立ててから求めの印を下ろす）。CRASHED なら、求めの印が残っていても再起動の途中と見なさない（#535・#626）。区間の途中（表に足した後、印を下ろす前）で例外が出たら、表・オブジェクト・fd・`runtimes`・ブロッキング型の枠・`initPid` を同じロックの中で巻き戻してから（#629）、印が立ったまま起動の手順に返り、`endBoot` が `INIT_START_FAILED` として知らせる（巻き戻した後は動いているプロセスがないので、`endBoot` がマウントも外す）。専用スレッドを動かす（待ち行列に積む）のは `RUNNING` にする前で、そこが引き返せない点。新しいスレッドは登録でカーネルロックを取るので、区間が終わるまで動き出さない
- init の読み込みを待つ間の取り消しは、信号（`bootCancelSignal`。実行時だけの値）で伝える。信号は、起動中の印を下ろすのと同じロックの区間で終える（init を作ったとき・init を作らずに終えたとき）。起動の途中の `stop()` は、印（`bootCancelled`）を立てるのと同じ区間で信号も終える（起動中の印を下ろすのは、後から起動用のスレッドで走る `endBoot`）。終えると取り消しの登録が外れ、起動の手順が持っていたもの（init のバイト列・読み込んだ `Program`）が回収される（#592）

### ディスクを開く口

`BootVolumeOpener`（internal）。今の既定はどの識別子も開けない（= `NO_BOOT_DISK`）。#52（ディスク FS）・#55（同梱イメージ）で実装する。テストは `KernelTestHooks.bootVolumes` でメモリの FS を差し込む。init があるかは、マウントする前のファイルシステムを直接見る（まだ誰とも分け合っていないので、カーネルロックは要らない）。

## init が終わったとき

`ProcessRunner.finishLocked` から `Machine.onProcessFinishedLocked` を呼ぶ。

- init（覚えた pid で、親なし）が終わり、マシンが `RUNNING` のときだけ扱う。`STOPPING`（`stop()`・`reboot()`）と `CRASHED`（カーネルの不整合）では何も言わない
- コンソールに1行書き、残りのプロセスがあれば `stopLocked(null)` で止める。マシンは `STOPPED` になる

## 再起動

- `reboot()`: 一度も起動していない・`CRASHED`・スケジューラが閉じているなら `false`。そうでなければ印を立てて `stop()` と同じく止める（起動の途中なら取り消す）
- プロセスもスレッドもなくなり、起動中でもなくなったら、通知の列に「再起動の求め」を1つ積む。次の `tick()` で `onRebootRequested` を呼ぶ
- 求めた後の `stop()` は、再起動を取り消す（積んだ後・配る前でも、配るときに確かめて呼ばない）
- `boot()` を受け付けると、それまでの再起動の求めは消える
- 追記（#635）: 短い間に再起動を繰り返したマシンでは、止まった後、通知を積まずに期限の tick まで遅らせてから積む（起動ループの抑止。ADR 0635）。待つ間も `rebootNotified` は立てたままなので、再起動の途中（保存しない・`isRebootPending`・`stop()` で取り消す）の扱いは変わらない
- `MachineManager` の包み（`ManagedHost`）は、外した後のマシンの `onBootFailed`・`onRebootRequested` を捨てる（外したマシンを起動し直さない）

## プロセスから呼ぶ口

内部の `ProcessContext.requestShutdown()`（= `Machine.stop`）・`requestReboot()`（= `Machine.reboot`）。WASM から呼ぶシステムコールの形は ABI に当たるので、#306（ABI は #73）で決める。

- 追記（#306）: ADR 0306 で決めた（`kibi:computer/power`）。受け付けたら呼んだプロセスも止まり、戻らない（`requestShutdown(): Nothing`、`requestReboot()` は受け付けたら `ProcessKilledException`）。止められていないことを確かめてから `stop()`・`reboot()` を呼ぶまでを1つのカーネルロックの区間にした

## コンソールのメッセージ

カーネルが固定の英語の1行を書く（改行は LF。コンソールへの書き出しで CR LF になる）。翻訳した表示が要るときは、接続層が `onBootFailed` を受けて自分で出す。

| 場面 | メッセージ |
| --- | --- |
| 起動を始めた | `kibi: booting from disk <識別子>`（起動部品のプログラムだけの起動は `kibi: booting from firmware`。#99、ADR 0099） |
| `NO_FIRMWARE` | `kibi: no boot firmware` |
| `NO_BOOT_DISK` | `kibi: no bootable disk` |
| `NO_INIT` | `kibi: /sbin/init not found` |
| `INIT_NOT_EXECUTABLE`・`INIT_START_FAILED` | `kibi: cannot start /sbin/init: <errno の名前か理由>` |
| init が終わった | `kibi: init exited with code <n>` / `kibi: init crashed` / `kibi: init was killed` |

## データ形式

- `KernelState`（保存の対象）に `initPid` を足す（#365。0 = init がいない、それ以外 = init の pid）。バイト形式は #82。マウントの表は今のまま（`source` に `disk:<識別子>`）
- ほかに足したものはどれも実行時だけ: 最後の `BootConfig`、起動中・取り消し・再起動の求め（`rebootRequested`・`rebootNotified`。ロックなしで読むため `@Volatile` にしたが、保存しない。#626）の印、前の起動の専用スレッド（`awaitTermination` で待つ）
- #81 への申し送り:
  - init の pid は `KernelState.initPid` に保存する（戻したマシンでも、init が終わったら止める・init にシグナルを送れない、が続く）
  - 起動の途中・再起動の途中（`reboot()` を受け付けてから `onRebootRequested` を呼ぶまで）は保存しない。この間は `isAtWaitPoint` が `false`
  - 戻したマシンでは、接続層が `BootConfig` を渡し直す（`lastBootConfig` を決め直す）。渡し直すまでは `reboot()` が `false` を返す。渡し直す口の形は #81 で決める。`BootConfig` は保存しない（その時点のワールドで作り直すほうが正しい）
    - 訂正（#306）: 今のコードでは、復元（`Machine.restore`）が復元の求めの `bootConfig` を `lastBootConfig` に入れるので、戻したマシンでも `reboot()` を受け付ける。`false` になるのは、起動も復元も通らずに作ったマシン・スケジューラが閉じている・`CRASHED` のとき（ADR 0306）。戻したマシンでのプロセスの再起動・停止の求めは `MachineRestoreTest` で確かめる

## 今の制限

- 実際の起動ディスクはまだ開けない（#52・#55）。燃料を計測しないモジュール（計測の挿入は #36）は init にできない（`INIT_START_FAILED`）
- ブロック・GUI の電源ボタンは #121
