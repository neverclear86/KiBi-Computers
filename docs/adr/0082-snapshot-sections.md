# 0082: 待機点保存の節の中身（KERNEL・PROCESS_ABI・TMPFS）とモジュールの置き場

- 状態: 採用
- Issue: #82（task）。前提: ADR 0030（状態保存の PoC）、ADR 0081（ライフサイクルと世代のファイル）、ADR 0053（`/tmp`）、#407（書き換えた 4 KiB の区画で数えるメモリ）
- 後続: #83（差分）、#84（書き込みのスレッド・モジュールの掃除）、#85（復元）、#86（殺したプロセスの通知）、#88（版の違い）、#89（復元の結合テスト）、#269（Component Model）

## 決めたこと（要約）

- 世代のファイル（ADR 0081）の節に、`KERNEL`（1）・`PROCESS_ABI`（2）・`TMPFS`（4）の中身を書く。`KILLED`（3）は ADR 0081 のまま
- 冷えた起動の理由が `NONE` のときだけ中身を書く。`NONE` でなければ `KILLED` 節だけ（待機点にいないプロセスのインスタンスを読まない）
- 数値はビッグエンディアン、文字列は u32 のバイト数 + UTF-8。数は読むときに上限と残りの長さで先に確かめてから確保する。壊れていれば `SnapshotFormatException`（`IOException` の子）
- 写し（`KernelStateCopy`）は**すべての**カーネルオブジェクトを `copyForSnapshot` で写す（ADR 0081 の `closedInCopy` はなくした）。写しの中で殺す手順は、写した実体の側で後始末まで走らせる（`NoopWakeSink`）
- モジュールは**元のバイト列**をワールドごとの content-addressed な置き場（`<world>/kibicomputers/modules/<SHA-256>.wasm`）に置き、保存データはハッシュで指す

## 節の版

| 節 | 種類 | 版 | 中身 |
| --- | --- | --- | --- |
| `KERNEL` | 1 | 1 | 先頭（燃料・コンソール・init・時計）→ プロセス表 → オブジェクト表 → イベントの索引 → マウントの表 |
| `PROCESS_ABI` | 2 | 1 | pid（i32）・層の ID（u16）・層の版（u16）・層の中身。プロセス1つに1節 |
| `TMPFS` | 4 | 1 | `/tmp` のマウント1つに1節 |

層の ID: 0 = テスト用（予約。読む側は拒む）、1 = p1（版 1）。

## KERNEL 節

- プロセス: pid・親・種類・状態・終了状態・モジュールのハッシュ（挿入・変換の後）・引数・環境変数・cwd・fd 表・`wait`（`0xFFFF` は待っていない）・`waitTimers`・`wakeOnRestore`
- オブジェクト: ID・参照の数・種類の番号（`KernelObjectCodec` の表。列挙の順番は使わない）・長さ・中身。開いたファイルは開いたときのパスに加えて、`captureCopy` で引いた **FS の中の今のパス**（`FileSystem.pathHint`。ロックも I/O もしない。ディスクは node → パスの表を持つ）を添える
- `PendingDeviceCall` の `Running` は `Failed("interrupted")` として書く
- **タイマーの `eventQueueId` は参照ではない**: キューが閉じていても番号をそのまま書き、読むときも確かめず、0 に直さない。`sendsEvents` は鳴ったときの予定の入れ直しと `read()` するかを左右するので、0 にすると振る舞いが変わる（イベントを入れる側は、キューがなければ何もしない）
- ホスト側の処理の待ち（`hostWaits`。#358）は写しの中で外し、そのプロセスに `wakeOnRestore` を立てる（戻したら起こして、プログラムが結果を取り直す）

## PROCESS_ABI 節の p1 の層

- モジュールの参照: 元のハッシュ（32 バイト）・後のハッシュ（32 バイト。照合用）・計測したか・止まりうる import の集合（名前の順）・変換器の版（`Instrumentation.VERSION`）・段1で変換できたか
- 入口（u8）: 0 = インスタンスがない、1 = `_start` をまだ呼んでいない（退避した値がない）、2 = 巻き直して再開する
- インスタンスの状態（入口が 0 でないとき）・退避した値（`AsyncStack`）・`AsyncWait`

## インスタンスの状態

- 線形メモリ: ページ数と**書き換えた 4 KiB の区画**だけ（ROM の区画と書いていない区画は書かない）。`memory.grow` が先に数えた端の区画も書く区画に入る（戻した後の数えた量を合わせるため）
- 可変のグローバル（export しないものも）・テーブル（funcref の関数の番号、`null` は -1）・drop 済みのパッシブなデータ・要素のセグメント
- 戻す口は `WasmModule.instantiateForRestore`。能動なセグメントの初期化はし、その後でメモリの大きさ・区画・グローバル・テーブル・drop 済みを上書きする。区画は1つずつ予算で数え、断られたら `WasmLinkException`
- **start 関数を持つモジュールは保存できない**: Endive 1.2.0 はインスタンスを作るときに start 関数を必ず動かし、飛ばす口がない（実装で確かめた）。`captureState` は `WasmStateException`、`instantiateForRestore` は `WasmLinkException` を投げ、そのプロセスは写しの中で殺す。wasi-libc のコマンドは start セクションを使わないので、標準 OS のコマンドには当たらない
- 取り出せないもの（`WasmStateException`）: グローバル・テーブルの import、v128・参照型の可変のグローバル、externref のテーブル、ほかのインスタンスの関数を指す要素

## TMPFS 節

mountId・`nextNode`・node の並び（id・種類・大きさ・3つの時刻、ディレクトリなら子の名前と id、ファイルなら塊の番号と 4,096 バイト）。`usedBytes` は書かず、戻すときに中身から数え直す（足りなければ冷えた起動）。読むときに木の形（根から1回ずつ指される・塊が大きさの中）を確かめる。

## 保存できないものの判定

- **静的なもの**（待っても直らない）は `processReadinessLocked` で `UNSAVEABLE`: ドライバーが `SnapshotCapableDriver` でない、モジュールが置き場にない（`ModuleStore.contains`。メモリの集合を引くだけ）、インスタンスが2つ以上
- 終わったプロセス（ゾンビ）は判定せず `READY`。ABI 層の写しも取らない
- **取り出してみないと分からないもの**（`captureAbi` が `null` か例外）は、`captureCopy` の中で warn を出して写しの中で殺す。init を殺して冷えた起動になったら、取った中身を捨てる

## モジュールの置き場

- `ModuleStore`（`put`・`contains`・`get`）。`FileModuleStore` は tmp → fsync → `ATOMIC_MOVE` で書く。置き場がなければ `ModuleStore.NONE`（すべて「ない」。プロセスは保存できない）
- `contains` の集合に入るのは、(a) `put` で書けたもの、(b) `put` で既にあるファイルの中身を確かめたもの、(c) `get` で確かめて返せたもの、(d) `FileModuleStore` を作るときの、置き場の 16 進 64 文字のファイル名（中身は `get` で確かめ、合わなければ `null` にして集合から外す）。種は中身を確かめていないので、`put` は種だけのハッシュを書き直す
- 書くのは `Machine.beginExec` の読み込みの続き（読み込みのスレッド）と `finishExec`。メインスレッドの tick では書かない。**ストアを通らずにモジュールを作る経路を足すときは `put` を呼ぶこと**
- 挿入・変換は決定的であること（同じ元のバイト列から同じ後のハッシュ）。出力を変える変更をしたら `Instrumentation.VERSION` を上げる

## #85 への申し送り

- 戻すときは置き場の元のバイト列から同じ読み込み方（計測・止まりうる import）をやり直し、後のハッシュが `PROCESS_ABI` 節のものと合わなければ冷えた起動（#88）
- 置き場の `get` が `null`（種だけで中身が壊れていた）なら冷えた起動
- `wakeOnRestore` のプロセスは戻したら起こす
- ABI 層 0（テスト用）の節は拒む
- 読んだ `KERNEL` 節は参照（ハンドル・`wait`・`waitTimers`・`PipeEnd.streamId`・`consoleTtyId`）がすべて表にあることを確かめてある。タイマーの `eventQueueId` は確かめない（上のとおり）
