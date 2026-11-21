# 0661: OS の既定の言語をクライアントの言語に合わせる

- 状態: 採用
- Issue: #661（task）。前提: ADR 0049（起動シーケンス）、0100（`/sbin/init`）、0106（man の言語）、0085（読み込みでの復元とケースの NBT）、0099（プログラムだけの起動）
- 方針: WIT・p1 の import・新しいシステムコール・パケット・公開 API（`pro.ikili.kibicomputers.api`）・スナップショットのバイト形式は変えない。作ったのは 1.21.1 NeoForge だけ（1.20.1 は #224 の配下）
- 番号は Issue 番号にそろえている

## 決めたこと（要約）

- **オーナーの判断: 案 (A)「起動のときに `LANG` を渡す」**（#661 のコメント）。新しいシステムコールで既定の言語を返す案 (B) は採らない
- カーネルは、言語が分かるときだけ init に `LANG=<POSIX の形>`（例: `ja_JP.UTF-8`）を渡す。分からなければ今までどおり空（ADR 0049 の約束は「空か `LANG` の1つ」に変わる）
- 言語は **電源ボタンを押して起動したプレイヤー** のもの。起動の時点で決め、後から画面を開いた人には合わせない。動いている間に `LANG` は変えない
- プレイヤーのいない起動（プログラムからの再起動、読み込みでの復元、復元できなかったときの冷えた起動、復元した後の再起動）は前回の値を使う。値がなければ英語（`LANG` を渡さない）
- 優先順位: カーネルの既定（`LANG`）< `/etc/inittab` の `env LANG=` < シェルの `export` < `man -L`。既定は `LC_ALL` ではなく `LANG` に入れる（利用者が `LANG` を上書きしやすいように）
- 言語の元は、バニラがもう送っている `ServerPlayer.clientInformation().language()`（例: `ja_jp`）。新しいパケットは足さない

## 正規化と検査（`core` の `BootLanguage`。`@ApiStatus.Internal`）

値はクライアントが決める（改造したクライアントなら何でも送れる）うえ、NBT にも `/data merge` で何でも書けるので、受け取るたびにここを通す。
形の合わない値は捨てる（英語）。`ProcessArgs.check` に断られて起動が `INIT_START_FAILED` になる値は通さない。

- `fromMinecraft(raw)`: 8 文字を超えるものは捨てる。Kotlin の `lowercase()`（Locale に左右されない）にしてから
  - `^[a-z]{2,3}_[a-z0-9]{2,3}$` → `<言語>_<地域を uppercase()>.UTF-8`（`ja_jp` → `ja_JP.UTF-8`、`es_419` → `es_419.UTF-8`）
  - `^[a-z]{2,3}$`（地域なし） → `<言語>.UTF-8`
  - それ以外は `null`。`en_ud` のような実在しない地域も形が合えば通す（man は `en_UD.UTF-8` → `en_UD` → `en` → 英語でフォールバックするので害はない）
- `isValid(posix)`: `^[a-z]{2,3}(_[A-Z0-9]{2,3})?\.UTF-8$` かつ `MAX_LENGTH`（16）文字以下
- 検査は二重に守る: 接続層がプレイヤーから受けるとき（`fromMinecraft`）、NBT から読むとき（`isValid`）、`BootConfig.Builder.language`（`isValid`。合わなければ `IllegalArgumentException`）

## 渡し方

- `BootConfig.language: String?`（既定 `null`）。`BootSequence.startInit` が `SpawnOptions.env` を `{"LANG": language}` か空にする。ディスクからの起動とプログラムだけの起動（ADR 0099）の両方
- 再起動は `lastBootConfig`（ADR 0049）をそのまま使うので、何もしなくても同じ言語を引き継ぐ。`onRebootRequested` は上書きしない
- `/sbin/init` は、自分の環境変数から `LANG` だけを拾い、空でなければ子の既定に入れる（`PATH`・`HOME` の既定の後、inittab を読む前。inittab の `env LANG=` が後から置き換える）。自分の環境をまるごと子に流さない。`MAX_ENV`（32）の枠を1つ使う
- 動いている init とその子の `LANG` は `Process.env` として既に保存の対象なので、復元をまたいでそのまま残る

## 接続層（1.21.1 NeoForge）

- `CaseBlockEntity.bootLanguage`（POSIX の形か `null`）を持ち、NBT の文字列タグ `BootLanguage` に書く（ADR 0085 の追記。値がなければ書かない。読み込みで検査し、合わなければ `null`。タグのない古いセーブは `null`）
- `pressPowerButton(player: ServerPlayer? = null)`: 起動の分岐（`status != RUNNING`）の中でだけ、`player` の言語を正規化して `bootLanguage` を更新する。
  値が変わったときだけ `setChanged()`。起動に使うのは「今回の値 ?: 前回の値」。止める操作では言語を触らない
- **`FakePlayer`（他の MOD の自動化）は「プレイヤーなし」と同じに扱う。** FakePlayer の `clientInformation()` は既定の `en_us` を返すので、そのまま使うと保存した言語を英語で上書きしてしまう
- `CaseMenu.clickMenuButton` は押したプレイヤーを `ServerPlayer` として渡す。ほかの呼び手（GameTest など）は引数なしで、前回の値を使う
- `startRestore` は保存した `bootLanguage` を復元の求めの `BootConfig` に入れる（戻したマシンの `lastBootConfig` になる。冷えた起動もこの値）
- 言語はメインスレッドで読み、不変の `BootConfig` に入れて渡す。`ServerPlayer`・`CaseBlockEntity` を起動用のスレッドに持たせない。tick ごとの処理は足さない（読むのは押したときに1回だけ、NBT は短い文字列1つ）

## 比べた案

- **(B) `kibi:computer` に「既定の言語」を返す関数を足す**: WIT・p1 の import が増え、p2・p3・p1 の3層に同じものを作ることになる。`LANG` は POSIX の慣習で、既存の man・シェルがそのまま使える。オーナーの判断で (A)
- **最後に画面を開いた人・最後に操作した人に合わせる**: 動いている間に `LANG` が変わり、同じ画面を複数人が見ると揺れる。tick ごとに見る必要があり、1,000 台規模の目標にも合わない
- **NBT に生の `ja_jp` で持つ**: 読むたびに変換が要る。ゲストに渡した形のまま持てば、再起動・復元で同じ値をそのまま再現できる

## 確かめ方

- `core`: `BootLanguageTest`（正規化・検査・Builder・トルコ語ロケールでも変わらない）、`BootTest`（init の環境が `{LANG}` だけ・再起動で引き継ぐ・プログラムだけの起動）、
  `InitTest`（init が子に `LANG` を渡す・inittab の `env LANG=C` が勝つ・言語なしなら子に `LANG` がない。`os/test/initprobe.zig` の `lang` で書かせる）
- GameTest（1.21.1）: `BootLanguageGameTests`（GUI の電源ボタンで `ja_jp` のプレイヤーが起動 → `ja_JP.UTF-8`、止める操作・プレイヤーなし・FakePlayer・形の合わない言語では前回の値、
  同じ値なら `setChanged` しない、NBT の往復と不正なタグ、置き直して読ませたケースが保存した言語で起動）

## 今の制限

- 1.20.1（Forge）の接続層は作っていない（#224）。`core` の変更は既定値つきなので、1.20.1 は `LANG` なしで今までどおり動く
- 置いたプレイヤー・持ち主の言語に合わせる設定、サーバーの既定の言語を設定で決める口は作っていない（要るなら別の Issue）
