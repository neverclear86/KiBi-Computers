---
name: run-minecraft
description: KiBi Computers を開発環境の Minecraft（NeoForge 1.21.1）で動かして確かめる手順。GameTest の実行、開発クライアントの起動・ログ確認・スクリーンショット・終了をまとめている。「ゲームで確認して」「クライアント起動して」「GameTest を回して」「見た目を確認して」などで使う。
---

# Minecraft で動かして確かめる

作業ディレクトリはリポジトリのルートとする。ログは `platform-neoforge-1.21.1/run/logs/latest.log`。

## GameTest（ヘッドレス。まずはこれ）

```bash
./gradlew :platform-neoforge-1.21.1:runGameTestServer --console=plain
```

- 20秒ほどで終わる。全テストが通れば `All N required tests passed :)`、失敗すると `N required tests failed :(` が出てタスクが失敗する。
- 失敗の詳細はログの `LogTestReporter` の行に出る。
- テストの書き方は `AGENTS.md` の「つまずきやすい点」と `SmokeGameTests.kt` を参照。

## 開発クライアント

1. バックグラウンドで起動する（Bash の `run_in_background` を使う）:
   ```bash
   ./gradlew :platform-neoforge-1.21.1:runClient --console=plain
   ```
2. ログに `Sound engine started` が出るのを待つ（20〜30秒）。待つときは Monitor などで条件待ちにし、固定の sleep を重ねない。
3. ログを確認する。少なくとも次を見る:
   ```bash
   grep -E "kibicomputers|KiBi|self-check|ERROR|Exception" platform-neoforge-1.21.1/run/logs/latest.log
   ```
4. 見た目を確認するときはスクリーンショットを撮り、Read で画像を見る（ウィンドウを前面に出すので、ユーザーの画面に一瞬割り込む）:
   ```bash
   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .claude/skills/run-minecraft/scripts/screenshot.ps1
   ```
   保存先のパスが出力される（既定は `platform-neoforge-1.21.1/run/screenshots/`）。
5. 終わったら必ずクライアントを閉じる:
   ```bash
   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .claude/skills/run-minecraft/scripts/stop-client.ps1
   ```
   Gradle のタスクは強制終了扱いで `BUILD FAILED` になるが、問題ない。

## 限界

- エージェントはクライアントにマウス・キーボード操作を送れない。ワールドに入って操作する確認（GUI の操作、ブロックの設置）が必要なときは、手順をユーザーに伝えて確認してもらうか、GameTest で同じことを検査する。
- 初回起動ではアクセシビリティの案内画面が出る（`run/options.txt` ができると以後は出ない）。
