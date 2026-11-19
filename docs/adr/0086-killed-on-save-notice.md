# 0086: 保存で殺したプロセスの、復元の後の知らせ

- 状態: 採用
- Issue: #86（task）。前提: ADR 0030（待機点での保存）、0081（保存の手順と殺し方）、0082（`KILLED` 節）、0085（読み込みでの復元）、0087（`restored` イベント）
- 後続: #630（画面の中身の保存・画面がつながっていない台）、#269（Component Model のプロセスの保存）
- ABI・保存形式・公開 API は変えない（コンソールのメッセージと、今ある終了の状態だけを使う）

## 前提（#81・#82 で済んでいること）

- 保存できないプロセス（ブロッキング型で待機点にいない、`SnapshotCapableDriver` でない、コンポーネント、取り出しに失敗した、アンロードの待ちの予算を超えた `KILL_TRANSIENT`）は、**写しの中だけで** `SIGKILL` で殺す（`KernelStateCopy.killInCopy`）。元のマシンは止めない・変えない（ADR 0081）
- 写しの中の殺し方は、終わりの記録（`ExitBookkeeping.recordExit`）を通る。戻した表では、殺したプロセスは `KILLED`・`termSignal = KILL` のゾンビで、`ProcessObject.status` も入り、親には `CHILD_EXITED`（`pendingChildExits`）が数えてある。戻した親が `EXITED`・`CHILD_EXITED` を待っていれば、`installRestoredLocked` の `readyKeys` で起きる。**ゲストのプログラムへの知らせは、これで足りる**（新しいイベントの種類・終了の理由は足さない。足すならシステムコールの ABI）
- 殺した pid は `KILLED` 節に並ぶ（`MachineSnapshot.killedPids`）

## 決めたこと

### プレイヤーへの知らせ（コンソールの1行）

戻せたら（RESTORED）、殺した pid ごとに1行、カーネルのメッセージとしてコンソールに書く。

```
kibi: <argv[0]> (pid n) was killed: could not be saved
kibi: pid n was killed: could not be saved
kibi: K more processes were killed: could not be saved
```

- 名前は戻した表から引き、`processLabel`（制御文字を `?` に、64 文字で切る）を通す。表にない（刈り取られた）、`KILLED` でない（pid の使い回し）、引数がないときは `pid n`
- 文言は「ブロッキング型だから」ではなく「保存できなかったので」にする（`KILL_TRANSIENT` などで待機点型も殺されるため）
- 1回の復元で書くのは `Machine.MAX_KILLED_NOTICES`（8）行まで。残りは数だけの1行にまとめる（最大プロセス数まで殺しうる。コンソールの未配信の上限と画面の流れを抑える）
- 使うのは最上位の世代の `killedPids` だけ（差分でも、毎回の写しが完全な一覧を持つ。基準の世代のものは混ぜない）

init を殺した写し（`ColdBootReason.INIT_KILLED`）は、`KERNEL` 節がなく名前を引けないので、冷えた起動の前に1行だけ書く。

```
kibi: init could not be saved; starting fresh
```

- `BOOTING`・`REBOOT_PENDING` と、写しを読めない・モジュールがない・版が違うなどの冷えた起動では書かない（`ColdBootCause` は `KILLED_SECTION` にまとまったまま。詳細は `RestoreFallbackException.reason` で `restoreFellBack` に渡す。数え口の意味を変えないよう、`ColdBootCause` の値は増やさない）

### 書く時機

- RESTORED: `completeRestore` の中で、`postRestoreFinishedLocked(RESTORED)` の**後**に書く。通知の列で受け手（`ComputerCase` の `onReady` が画面をつなぐ）より後ろに並ぶので、つないだ画面に出る。前に書くと、受け手より前に `host.onConsoleOutput` へ流れて失われる。受け手の中では書かない（ADR 0087 の「受け手の中では何も書いていない」を保つ）。目印（`ttyMark`）は quiesce を閉じる前の TTY の位置なので、OS の描き直し（`restored` を受けた `sh` など）より前に出る
- 冷えた起動: `restoreFellBack` で印（`coldBootNotice`）を立て、`coldBootAfterRestore` が起動のメッセージより前に書く。印は `coldBootPending` と一緒に下ろす（取り消し・`stop()` で捨てる。あとの別の起動に古い知らせを出さない）
- CANCELLED・CRASHED では書かない

### 重ねて出さない

殺したプロセスは、戻した表ではゾンビなので、次の保存の `killedPids` に入らない。知らせは復元1回につき1回。

### 規模

足すのは「復元1回につき O(殺した数、上限 8 + 1 行)」の文字列づくりと書き込みだけ。tick ごと・保存のたびの処理とログは足さない（#269 が済むまで、コンポーネントのプロセスは自動保存のたびに写しの中で殺されるので、保存の側でログを足すと周期ごとに台数分出る）。

### 出ない・消えうる場合（#630 で扱う）

- 画面がつながっていない台（`primaryScreen == null`）では、`host.onConsoleOutput` に流れて失われる（ADR 0087 の描き直しと同じ）
- `nano` などの代替画面のプログラムは、`restored` で全体を描き直すので、知らせが上書きされうる

## 確かめ方

- `core` の `KilledOnSaveNoticeTest`: 待機点型の init（`prompt`）の子に保存できないブロッキング型の子を2つ作って書く（元のマシンの子は殺さない）。戻すと子は `KILLED`・`SIGKILL` のゾンビで、親の `pendingChildExits` が子の数。受け手の中ではまだ配っておらず、受け手の後に名前つき（制御文字は `?`）と `pid n` の2行が出る。次の保存の `killedPids` は空で、それを戻しても出ない。上限を超える数を殺したら、8 行と数の1行。init を殺した写しの冷えた起動では、起動のメッセージより前に1行だけ出て、受け手の中で止めたら出ない。殺していない写しの冷えた起動（UNSUPPORTED_PROCESS）では出ない
