# 0067: デバイスの接続・切断のイベント

- 状態: 採用
- Issue: #67（task）。前提: ADR 0044（カーネルとホストの境界）、0063（イベントキュー）、0065（デバイスのモデル）、0066（接続数の上限）、0073（`kibi:*` の WIT）、0087（復元のイベント）
- 後続: #77（標準 OS で受ける）、#129（接続層が隣のブロックを見てつなぐ・外す）
- ABI（種類・source・値・出す条件）は、#67 へのオーナーの回答（Q1=A・Q2=A）で決めた

## 決めたこと

### イベントの形

`DeviceBus` で成功した接続・切断のたびに、カーネルが `kibi:kernel/events` のキューへ1件出す（同報。ADR 0063 の配り方のまま）。

```
event { kind: "device-attached", source: "", values: [text(address), text(種類)] }
event { kind: "device-detached", source: "", values: [text(address), text(種類)] }
```

- 種類は、つないだときに検査した値（`AttachedDevice.kind`）。切断でも `Device.kind` を読み直さず、同じ値を載せる
- 表の世代（`DeviceBus.generation`）は載せない。世代は実行時だけの値のまま
- 値の大きさは上限に収まる（address 36 バイト、種類は `DeviceKind.MAX_BYTES` の 64 バイトまで。`KernelEvent` の検査で投げない）
- 名前の定数は `DeviceEvents.ATTACHED`・`DETACHED`・`SOURCE`（内部）。WIT の型は変えない（`events.wit`・`bus.wit` の doc コメントに書き足しただけ）
- デバイスも `DeviceContext.postEvent` で同じ種類名を出せる（source がアドレス）。予約名として拒まない。受け手は source が空かどうかで見分ける（ADR 0087 と同じ）

### 出す条件

- 出す: `attach` が `true` を返したとき（`onAttached` が正常に返り、まだ表に載っている）、`detach` が `true` を返したとき（接続を知らせたデバイスだけ）
- 出さない: 上限で断った `attach`（`false`）、`onAttached` の例外、address の重複・形の誤り・種類の形の誤り（`IllegalArgumentException`）、つないでいない `detach`（`false`）
- `onDetached` が例外を投げても、外れたのは事実なので `device-detached` は出す（`finally`）。例外はそのまま呼び手に投げる。`finally` の中には post 以外を置かない（元の例外を隠さない）
- 再入: `DeviceBus` の書き込みのロックは同じスレッドなら再入できる。`onAttached` の中でデバイスが自分を `detach` すると、そのままでは「detached → attached」の順に逆転し、表にないデバイスの `device-attached` が残る。そこで:
  - `attach` は、`onAttached` の後で `table[address] === attached` のときだけ出し、`AttachedDevice.announced` を立てる
  - `detach` は `announced` が立っているときだけ出す
  - この場合はどちらも出ない（接続を知らせていないものの切断は出さない）
  - `onAttached` の中で別のデバイスをつないだときは、内側の `device-attached` が外側より先に並ぶ（受け入れる）
  - `onAttached` が投げたときに表から戻すのも、`table[address] === attached` のときだけ。中で自分を外して同じ address に別のデバイスをつないでいたら、そのデバイスは残す（外すと、接続だけ届いて切断が届かない）

### 順番とロック

- 表を入れ替えた後、書き込みのロックの中で `Machine.postEvent` を呼ぶ。そのため:
  - 同じ address の接続と切断は、起きた順にキューへ並ぶ（ロックの外で出すと、別スレッドの接続と切断が入れ替わりうる）
  - イベントで起きたゲストが `list-devices` を読むと、新しい表が見える
- ロックの順は **書き込みのロック → (デバイスのロック) → カーネルロック**。今の `ScreenDevice.onAttached` → `resizeConsole` と同じ向きで、ADR 0065 の「デバイスのロック → カーネルロック」とも合う。カーネルロックを持ったまま `attach`・`detach` を呼ぶ経路は作らない（`core` の中に呼び手はなく、接続層はメインスレッドからロックなしで呼ぶ）
- `onAttached` の中で出たイベント（画面の `tty-resize`、デバイス自身の `postEvent`）は `device-attached` より先に並ぶ。`onAttached` が例外で失敗したときも、その間に出たイベントは取り消さない
- キューがない・止まった・`close()` の後のマシンでも、`Machine.postEvent` は捨てて 0 を返すだけで、例外にならない（接続層の `disconnect()` は close の後にも呼ばれる）

### 起動・再起動・復元での見え方

- 起動前と再起動の前の接続は、受けるキューがないので結果として届かない。抑える規則は作らない。起動したプログラムは一覧を読む
- 復元（ADR 0087）: `completeRestore` は受け手より前に `restored` を出し、受け手（`ComputerCase.startRestore` の `onReady`）がデバイスをつなぎ直す。そのため、`restored` の後に全デバイスの `device-attached` が毎回届く（保存の前からつないでいた分と重なる）
  - 保存の前にあって復元の後にないデバイスの `device-detached` は出さない（デバイスの一覧は保存しない。ADR 0065 の Q6）
  - 復元時の `device-attached` を抑えることもしない
  - 復元の前段（`restoringValue` の間）に起きた接続のイベントは、復元前の空の索引に入り、`completeRestore` が索引を差し替えると消える。`restored` が後に来るので、受け手は一覧を読み直せば分かる。`Machine.postEvent` に復元中の抑止は足さない（ほかの出し手 #64・#143 に効くため）
  - 冷えた起動（COLD_BOOT）とソースなしの経路では、`onReady` が起動より先なので届かない
- 受け手の決まり: 一覧を正とし、イベントは差分の知らせとして使う。`restored` を受けたとき・`dropped > 0` のときは一覧を読み直す

## 決めないこと

- 標準 OS（`os/`）で受けて何をするか: #77
- 接続層が隣のブロックを見てつなぐ・外す: #129（どの経路も `DeviceBus.attach`・`detach` を通るので、イベントは自動で出る）
- SDK（#116）・Lua の `event`（#94・#95）での見せ方

## コスト

- tick ごとの処理は増えない。post は接続・切断のときだけで、1回の post で `KernelEvent` を1つ作り、手間はマシンのキューの数（最大 64）に比例するだけ。キューがなければすぐ戻る
- チャンクの読み込みで多数台が一斉に復元しても、1台につきデバイスの数だけカーネルロックを短く取るだけ。スレッドや仕事の列は増やさない。tick ごとに世代を見比べる方式は採らない
- 1件の footprint はおよそ 350〜400 バイト。キューの容量（既定 64）やマシン全体の上限（256 KiB）を越えたときは、既存の `drop`（`dropped` が増える）で済む
- 保存形式は変えない。イベントは既存の `EventQueue` に入り、そのまま保存される

## 確かめ方

- `core` の `DeviceEventTest`: 形（ALL のキューと種類を絞ったキュー）、出さない条件、`onDetached` の例外、再入、キューがないとき・`close()` の後、別スレッドからの同じ address のつなぐ・外すの順番、受けた時点の `list()`、デバイスが同じ名前を出したときの source、`tty-resize` が先に並ぶこと、復元の順番（`restored` の後に `device-attached`、消えた分の `device-detached` は出ない）、待機点型のプロセスが接続で起きること
- `DeviceBusTest` の `postEvent` のテストは、ALL のキューの先頭に `device-attached` が入る分を期待値に足した
