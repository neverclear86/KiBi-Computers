---
name: run-minecraft
description: KiBi Computers を開発環境の Minecraft（NeoForge 1.21.1 / Forge 1.20.1）で動かして確かめる手順。GameTest の実行と、開発クライアントの起動・自動操作（ワールド作成、コマンド、キー・文字入力、クリック、ブロックの右クリック）・スクリーンショット・終了をまとめている。「ゲームで確認して」「クライアント起動して」「GameTest を回して」「見た目を確認して」などで使う。
---

# Minecraft で動かして確かめる

作業ディレクトリはリポジトリのルートとする。以下は 1.21.1 の例。1.20.1 は `platform-neoforge-1.21.1` を `platform-forge-1.20.1` に置き換える。ログは `platform-*/run/logs/latest.log`。

## GameTest（ヘッドレス。まずはこれ）

```bash
./gradlew :platform-neoforge-1.21.1:runGameTestServer --console=plain
```

- 20秒ほどで終わる。全テストが通れば `All N required tests passed :)`、失敗すると `N required tests failed :(` が出てタスクが失敗する。
- 失敗の詳細はログの `LogTestReporter` の行に出る。
- テストの書き方は `AGENTS.md` の「つまずきやすい点」と `SmokeGameTests.kt` を参照。

## 開発クライアント（自動操作の口）

`runClient` には、開発環境でだけ動く HTTP の口（#252、`dev` ソースセット）が入っている。localhost だけで待ち受け、ポートは自動で選ばれて `platform-*/run/dev-driver.port` に書かれる。worktree ごとにポートが分かれるので、並行して動かしてもぶつからない。呼ぶときは補助スクリプトを使う:

```bash
M=.claude/skills/run-minecraft/scripts/mc.sh   # mc.sh <1.21.1|1.20.1> <endpoint> [JSON]
```

1. バックグラウンドで起動する（Bash の `run_in_background` を使う）:
   ```bash
   ./gradlew :platform-neoforge-1.21.1:runClient --console=plain
   ```
2. `bash $M 1.21.1 status` が `"ready":true` と `"screen":"TitleScreen"` を返すまで待つ（20〜30秒）。ポートのファイルができる前や読み込み中は、接続失敗や 503 になる。
3. ワールドに入る。平らなクリエイティブのワールド（`kibi-dev`、チート有効）を作る。`status` の `inWorld` が true になるまで待つ:
   ```bash
   bash $M 1.21.1 world '{"reset":true}'
   ```
4. 操作して確かめる。

   | endpoint | JSON | 内容 |
   | --- | --- | --- |
   | `status` | - | 画面のクラス名・タイトル、ワールドにいるか、座標、GUI の大きさ |
   | `widgets` | - | 開いている画面のボタンや入力欄（文字列・位置・入力欄の値） |
   | `command` | `{"command":"setblock ~2 ~ ~ minecraft:stone"}` | プレイヤーとして権限4でコマンドを実行し、出力を返す |
   | `use` | `{"x":0,"y":-60,"z":0,"face":"up"}` | ブロックを右クリックする（GUI を開く） |
   | `key` | `{"key":"e"}`（`escape`、`enter`、`left.shift` など。`keyCode`、`action: press/release` も可） | キーを押して離す |
   | `type` | `{"text":"こんにちは"}` | 開いている画面に文字を入力する（IME で確定した文字と同じ経路） |
   | `click` | `{"widget":"Done"}` か `{"index":0}` か `{"x":10,"y":20}` | GUI 座標でクリックする |
   | `scroll` | `{"x":200,"y":120,"amount":-1}` | スクロールする |
   | `close` | - | 画面を閉じる |
   | `screenshot` | `{"name":"my-check.png"}` | Minecraft の描画から撮る（前面に出さない）。返ったパスの画像を Read で見る |
   | `quit` | - | このクライアントを正常に終了する |

5. ログも確認する:
   ```bash
   grep -E "kibicomputers|KiBi|self-check|ERROR|Exception" platform-neoforge-1.21.1/run/logs/latest.log
   ```
6. 終わったら必ず `bash $M 1.21.1 quit` で閉じる。応答しないときだけ、`stop-client.ps1`（**すべての** Minecraft のクライアントを強制終了する。並行作業中の他のクライアントも閉じるので注意）を使う。

## 注意

- ウィンドウの上にユーザーの実際のマウスカーソルがあると、ホバー表示（ツールチップ）が撮影に写ることがある。
- 1.20.1 も同じ操作で動く（`mc.sh 1.20.1 ...`、`:platform-forge-1.20.1:runClient`）。
