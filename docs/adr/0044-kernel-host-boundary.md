# 0044: カーネルと MC 層（接続層）の境界

- 状態: 採用
- Issue: #44（task）。前提: ADR 0043（カーネル全体の設計）
- 後続: #120（サーバー側のマシン管理）、#47（燃料の配分）、#52・#56（ディスク）、#58・#60（標準入出力・TTY）、#63（イベント）、#64・#69（時計・乱数）、#65・#77（デバイス）、#81・#82・#84（保存）
- 番号は Issue 番号にそろえている

## 決めたこと（要約）

カーネル（`core`）は Minecraft を知らない。接続層とのやりとりは、`core.kernel` の次の型だけで行う。
`pro.ikili.kibicomputers.api`（他 MOD 向けの公開 API）には何も足さない。

| 向き | もの | 口 | 呼ばれるスレッド |
| --- | --- | --- | --- |
| MC → カーネル | tick | `MachineScheduler.tick()` | メインスレッド（毎 tick） |
| MC → カーネル | 燃料 | `MachineConfig.fuelPerTick`（`tick()` の番号から遅延補充。ADR 0043） | — |
| MC → カーネル | 時計 | `MachineEnvironment.clock`（`MachineClock`） | どのスレッドからも |
| MC → カーネル | 乱数 | `MachineEnvironment.entropy`（`EntropySource`） | どのスレッドからも |
| MC → カーネル | ディスクの実体 | `MachineEnvironment.disks`（`DiskResolver`: 識別子 → ホストのディレクトリ） | どのスレッドからも |
| MC → カーネル | 保存先 | `MachineEnvironment.storageDirectory`（`Path?`） | — |
| MC → カーネル | つながったデバイス | `Machine.notifyDevicesChanged()`（変わったことだけを知らせる） | どのスレッドからも |
| カーネル → MC | 状態の変化（停止を含む） | `MachineHost.onStateChanged` | メインスレッド（`tick()` の中） |
| カーネル → MC | 画面出力 | `MachineHost.onConsoleOutput` | メインスレッド（`tick()` の中） |
| カーネル → MC | ビープ | `MachineHost.onBeep` | メインスレッド（`tick()` の中） |
| カーネル → MC | ワールド・デバイスの操作 | ホスト呼び出し（`ProcessContext.callOnMainThread`。内部） | メインスレッド（`tick()` の中） |

## 環境（`MachineEnvironment`）

- `MachineScheduler.createMachine(host, config, environment)` で渡す。`environment` を省いたときは `MachineEnvironment.DEFAULT`（JVM の時計、`SecureRandom`、ディスクなし、保存しない）
- 中身はどれもプロセスのスレッド（システムコールの中）から使われるので、**接続層の実装はワールドに直接触らない**。ゲーム内時間は、接続層が tick ごとに `volatile` のフィールドなどへ写し、`MachineClock.worldTicks()` はそれを返す
- 時計は3つ: 実時間（エポックからのナノ秒）、単調な時計（ナノ秒）、ゲーム内時間（ワールドの tick）。起動からの経過時間はカーネルが tick の番号から求める（#64）
- 乱数は暗号に使える質を求める（プログラムが鍵を作るため）
- ディスクとセーブの場所は**ホストのディレクトリ**で渡す。#52（ディレクトリを背面にしたディスク FS）と #84（別ファイルへの保存）の両方が、ファイルの並びを自分で決められるため。ディレクトリを作るのはカーネルの側。ディスクの識別子（アイテムに持たせる UUID など）は接続層が決める
- 保存先の中に置くファイルの形は決めない（#81・#82・#88）

## 通知（カーネル → MC）

- 状態の変化・コンソール出力・ビープは、マシンごとの1つの通知の列に、起きた順に積む。`tick()` は通知のあるマシンだけを取り出して、ロックの外で順に配る（ADR 0043 と同じ。台数によらない）
- 続けて積まれたコンソール出力は、1回の `onConsoleOutput` にまとめる（ほかの通知を挟むと分ける。順番を保つため）
- コンソール出力はバイト列。端末としての解釈（制御文字、エスケープ）は #60。画面のデバイスとの関係（どの画面に出すか）は接続層が決める。カーネルのコンソールは、起動のログ・カーネルのメッセージ・画面のデバイスがないときの標準出力の行き先
- 配っていない分の上限（仮の値）: コンソール 64 KiB、ビープ 8 個。超えた分は捨てる（数え口 `droppedConsoleBytes`・`droppedBeeps`）。書き手を待たせる流量の制御は、ABI 層が `ByteStream` で行う（#58・#60）。この上限は、ABI 層のバグや、通知を配らないまま（`tick()` が呼ばれない間）でもメモリが際限なく増えないためのもの
- ビープの値は範囲に丸める: 周波数 20〜20,000 Hz、長さ 1〜5,000 ms（長さの上限は仮の値）
- `MachineHost` は `fun interface` のまま、`onStateChanged` だけを必須にし、`onConsoleOutput`・`onBeep` は何もしない既定の実装を持つ（JVM の default メソッド。Java のクラスでも実装を省ける）
- 接続層の例外は捨てる。同じマシンの後の通知と、ほかのマシンには伝えない
- 停止はこれまでどおり `onStateChanged(STOPPED / CRASHED)` で知らせる

## ホスト呼び出し（ワールド・デバイスの操作）

- プロセスのスレッドはワールドに触れない。触る処理は `HostCall` にして `Machine.submitHostCall` で列に積み、`tick()` の中でメインスレッドが実行する。プロセスは `ProcessContext.callOnMainThread(call)` で、CPU と許可を返して結果を待つ（`awaitHost` の上に作る。ADR 0043）
- 1 tick に1台が実行するのは `MachineConfig.maxHostCallsPerTick`（仮に 64）まで。残りは出した順に次の tick へ。`tick()` は呼び出しを待つマシンだけを見る（通知と同じ形。台数によらない）
- 呼び出しの例外は、CPU を取り直してからプロセスに投げる
- マシンを止める（`stop()`・不整合で `CRASHED`）と、まだ実行していない呼び出しは実行せずに `ProcessKilledException` で終える。止めている途中・`CRASHED`・スケジューラを閉じた後に出された呼び出しも同じ。止められたプロセス（停止の印が立っている）は、マシンが `STOPPED` に戻った後でも呼び出しを出さない
- `HostCall` と `callOnMainThread` は `internal`。接続層が使うのは、ABI 層が中で呼ぶ接続層の実装（デバイスの操作など。#65・#77）で、接続層が `HostCall` を直接作ることはない

## デバイス

- デバイスの識別子・種類・呼び出し方は #65 で決める。ここでは、つながっているデバイスが変わったことだけを `Machine.notifyDevicesChanged()` で知らせる口を作る（どのスレッドからも呼べる。待たない）
- カーネルは変わった回数（実行時だけ。保存しない）を持つ。何が変わったかは、カーネルがホスト呼び出しで接続層に尋ねる。プロセスへのイベントにするのは #63
- 列挙と操作をメインスレッドで行うので、デバイスの実装（接続層）はスレッドを気にしなくてよい

## 燃料

- 燃料はこれまでどおり `MachineConfig.fuelPerTick`（作るときに決める）と `tick()` の番号から遅延補充する（ADR 0043）。性能（CPU のティア）や電力に応じて変える口は #47 で足す

## 決めないこと

- システムコールの ABI（時計・乱数・コンソールをどの import で見せるか）: #63・#69・#72・#79
- 保存のバイト形式と、保存先の中のファイルの並び: #81・#82・#84・#88
- デバイスの識別子・呼び出し方・公開 API: #65・#77
- 端末としての出力の解釈: #60。どの画面に出すか: 接続層（#120 と画面の Issue）
- 仮の値の確定（`maxHostCallsPerTick` 64、コンソール 64 KiB、ビープ 8 個・5,000 ms）: #251
