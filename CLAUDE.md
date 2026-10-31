@AGENTS.md

## Claude Code 向け

- Issue を1つ片付けるときは `/work-issue <番号>` スキルの手順に従う。
- Issue を実装から PR・CI・レビューまで自動で進めるときは、保存済みの Workflow `issue-to-pr` を使う（args に Issue 番号。配列なら並行して進める）。
- ゲームを起動して確かめるとき（GameTest、開発クライアント、スクリーンショット）は `run-minecraft` スキルを使う。
- 公開 API を足したり変えたりしたら、PR の前に `java-api-reviewer` サブエージェントでレビューする。
