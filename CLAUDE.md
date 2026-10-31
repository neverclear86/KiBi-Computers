@AGENTS.md

## Claude Code 向け

- Issue を1つ片付けるときは `/work-issue <番号>` スキルの手順に従う。
- Issue を実装から PR・CI・レビューまで自動で進めるときは、保存済みの Workflow `issue-to-pr` を使う（args は Issue 番号・番号の配列・`{ issues, window, merge }`。省略するとエージェントが選ぶ）。結果の `questions` は、チャットでユーザーに聞く。回答をもらったら、該当する Issue / PR に回答を書き、`needs-human` を外す。
- ゲームを起動して確かめるとき（GameTest、開発クライアント、スクリーンショット）は `run-minecraft` スキルを使う。
- 公開 API を足したり変えたりしたら、PR の前に `java-api-reviewer` サブエージェントでレビューする。
