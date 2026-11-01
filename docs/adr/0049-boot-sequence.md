# 0049: 起動シーケンス（/sbin/init）と停止・再起動

- 状態: 採用
- Issue: #49（task）。前提: ADR 0043（カーネル全体）、0044（MC 層との境界）、0045（プロセスの生成）、0051（VFS）
- 後続: #99（起動部品の中身と探す順）、#52・#55（起動ディスクのファイルシステム）、#306（停止・再起動のシステムコール。ABI は #73）、#81（戻したマシンの init）、#121（電源ボタン）
- 番号は Issue 番号にそろえている

## 決めたこと（要約）

- 起動はカーネル（`core`）の仕事。接続層は `BootConfig`（起動部品が挿さっているか、起動ディスクの候補の識別子）を渡すだけ。どのディスクがつながっているかはワールドの情報なので、接続層がメインスレッドで集める
- `Machine.boot(config)` は待たずに返す。ディスクを探し、`/sbin/init` を読み込むのは、スケジューラのワーカーのスレッド（重いため）
- init は常に pid 1、親なし、argv `["/sbin/init"]`、環境変数なし、cwd `/`、標準入出力はコンソール
- 起動のたびに、ゾンビを表から外し、pid を 1 から振り直し、マウントをすべて外す。コンソールの TTY は残す（前景のプロセスは 0 に戻す）
- init が `stop()`・再起動によらずに終わったら、コンソールに書いて残りのプロセスをすべて止め、`STOPPED` にする。自動では再起動しない（すぐ落ちる init で起動を繰り返さないため）
- 再起動は「すべて止まってから `MachineHost.onRebootRequested` を知らせる」形。既定の実装は前回の `BootConfig` で `boot` する。接続層は上書きして、その時点のディスクで設定を作り直せる
- 起動の失敗は、固定の英語のコンソールのメッセージと `MachineHost.onBootFailed` の両方で知らせる（コンソールが先）
- 足す状態はどれも実行時だけ。`KernelState`（保存の対象）は変えない

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
6. 失敗したら、マウントを外して知らせる。マシンは `STOPPED` のまま（状態の通知は出ない）

`KernelDrivers.DEFAULT` は、`_start` を export するモジュールを `WasiP1Driver` で動かし、それ以外は `NOEXEC`。Component Model は #259 の層が足す。

### 取り消し

- 起動の途中の `stop()`・`reboot()` は、印（`bootCancelled`）を立てるだけ。手順は区切りごとに印を見て、何も知らせずに終える
- init を作る `spawn` は、表に足すのと同じカーネルロックの中で印を確かめる（`asInit`）。作れたら、その場で init の pid を覚え、起動中の印を下ろす。これで「起動の途中の `stop()` が init を止め損ねる」隙間がない

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
- `MachineManager` の包み（`ManagedHost`）は、外した後のマシンの `onBootFailed`・`onRebootRequested` を捨てる（外したマシンを起動し直さない）

## プロセスから呼ぶ口

内部の `ProcessContext.requestShutdown()`（= `Machine.stop`）・`requestReboot()`（= `Machine.reboot`）。WASM から呼ぶシステムコールの形は ABI に当たるので、#306（ABI は #73）で決める。

## コンソールのメッセージ

カーネルが固定の英語の1行を書く（改行は LF。コンソールへの書き出しで CR LF になる）。翻訳した表示が要るときは、接続層が `onBootFailed` を受けて自分で出す。

| 場面 | メッセージ |
| --- | --- |
| 起動を始めた | `kibi: booting from disk <識別子>` |
| `NO_FIRMWARE` | `kibi: no boot firmware` |
| `NO_BOOT_DISK` | `kibi: no bootable disk` |
| `NO_INIT` | `kibi: /sbin/init not found` |
| `INIT_NOT_EXECUTABLE`・`INIT_START_FAILED` | `kibi: cannot start /sbin/init: <errno の名前か理由>` |
| init が終わった | `kibi: init exited with code <n>` / `kibi: init crashed` / `kibi: init was killed` |

## データ形式

- `KernelState`（保存の対象）は変えない。マウントの表は今のまま（`source` に `disk:<識別子>`）
- 足したものはどれも実行時だけ: init の pid、最後の `BootConfig`、起動中・取り消し・再起動の求めの印、前の起動の専用スレッド（`awaitTermination` で待つ）
- #81 への申し送り: 戻したマシンでは、親のない pid 1 を init とみなして init の pid を決め直す。起動の途中・再起動の求めの途中は保存しない（プロセスがないので、戻したら止まったマシンとして扱い、接続層がもう一度 `boot` する）

## 今の制限

- 実際の起動ディスクはまだ開けない（#52・#55）。燃料を計測しないモジュール（計測の挿入は #36）は init にできない（`INIT_START_FAILED`）
- ブロック・GUI の電源ボタンは #121
