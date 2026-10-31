# AGENTS.md

AIエージェント（Claude Code など）と人間の開発者向けの作業ガイド。ここに書いたルールは、Issue に個別の指示がない限り常に守る。

## プロジェクト

KiBi Computers（MOD ID `kibicomputers`）は、WebAssembly（[Chicory](https://github.com/dylibso/chicory)）で動く、OpenComputers 寄りのコンピューター MOD。本体は Kotlin で書き、[Kotlin for Forge](https://github.com/thedarkcolour/KotlinForForge)（KFF）に依存する。

- **仕様と作業の一次情報は GitHub Issues。** 企画書はリポジトリに入っていない。仕様で迷ったら、該当 Issue → 親 Epic → `decision` ラベルの Issue の順に読む。
- 対応バージョン: Minecraft 1.21.1 / NeoForge（メイン）と 1.20.1 / Forge（#222 配下。今は雛形のみ）。
- **全バージョンを `main` の1ブランチで扱う**（#16）。バージョンごとのブランチは作らず、`platform-<ローダー>-<MC>` サブプロジェクトを並べる。

## 構成

| パス | 内容 |
| --- | --- |
| `core/` | Minecraft 非依存のコア（WASMランタイム、カーネル、VFS、状態保存など）。Java 17 ターゲット |
| `platform-neoforge-1.21.1/` | 1.21.1 / NeoForge の接続層（ブロック、GUI、ネットワークなど）。Java 21 |
| `platform-forge-1.20.1/` | 1.20.1 / Forge の接続層。Java 17。ModDevGradle の Legacy Forge プラグイン、KFF 4.x |
| `gradle/libs.versions.toml` | 共通ライブラリのバージョン |
| `platform-*/gradle.properties` | Minecraft・ローダー・KFF のバージョン |
| `gradle.properties` | MOD ID、名前、バージョン、グループ |

パッケージは `pro.ikili.kibicomputers.<モジュール>`（`core`、`neoforge`、`forge` など）。他 MOD 向けの公開 API は `pro.ikili.kibicomputers.api` 以下に置く（#17）。

## コマンド

Windows では Git Bash から `./gradlew`、PowerShell から `.\gradlew.bat` を使う。

| 目的 | コマンド |
| --- | --- |
| ビルドと単体テスト | `./gradlew build` |
| core の単体テストだけ | `./gradlew :core:test` |
| ゲーム内テスト（GameTest、ヘッドレス。失敗すると終了コードが非0） | `./gradlew :platform-neoforge-1.21.1:runGameTestServer`<br>`./gradlew :platform-forge-1.20.1:runGameTestServer` |
| 開発クライアント起動 | `./gradlew :platform-neoforge-1.21.1:runClient` |
| データ生成（`src/generated/resources/` に出力） | `./gradlew :platform-neoforge-1.21.1:runData` |

- 起動ログは `platform-*/run/logs/latest.log`。起動すると `WASM runtime self-check passed` が出る。
- CI（`.github/workflows/build.yml`）は `build` と、両バージョンの `runGameTestServer` を実行する。

## 完了の条件

変更を「できた」と言う前に、次を満たす。

1. `./gradlew build` が通る。
2. `platform-*` を触ったら、そのバージョンの `runGameTestServer` も通る。`core` を触ったら両バージョンで通す。
3. 新しい振る舞いにはテストを付ける。Minecraft に依存しないものは `core` の単体テスト（JUnit 5）、依存するものは GameTest。
4. 見た目や操作に関わる変更（GUI、描画）は、開発クライアントで確認する。確認できなかったときは、PR にそう書く。

## アーキテクチャのルール

- **`core` に Minecraft・ローダーのクラスを持ち込まない。** ゲーム側の情報が要るときは、`core` にインターフェースを置き、`platform-*` で実装する。
- **`platform-*` は薄く保つ。** ロジックは `core` に置き、接続層はブロック・アイテム・GUI・パケットなどの登録と橋渡しに留める。接続層はバージョンごとに書く（#16 で決定。共通化のための共通モジュールやプリプロセッサは使わない）ので、厚くなるほど重複が増える。
- 機能を足すときは、まず 1.21.1 NeoForge で作り、1.20.1 への移植は #224 の配下で行う。
- **`core` は Kotlin 2.2 の API 範囲で書く。** 1.20.1 用の KFF 4.x が同梱する Kotlin が 2.2 系のため、`apiVersion`/`languageVersion` を 2.2 に固定している。それより新しい標準ライブラリ API はコンパイルエラーになる。
- 新しい実行時ライブラリを追加するときは、`core` の `api`/`implementation` に加えて、`platform-*` の `jarJar` と `"additionalRuntimeClasspath"` にも追加する（開発環境の実行時に見えなくなるため）。

## 公開 API と Java 相互運用

本体は Kotlin だが、他 MOD から使う公開 API は Java から自然に使えなければならない（#6）。

- 公開 API は `pro.ikili.kibicomputers.api` 以下に置く。MC 非依存のものは `core`、MC 依存のものは各接続層の `...api` パッケージ。将来、API だけの jar（`-api`）を別に出す。
- `core` は `explicitApi()` が有効。公開するものには `public` と型を明示し、それ以外は `internal`/`private` にする。
- object・companion の関数は `@JvmStatic`、定数は `const val`、公開フィールドは `@JvmField`。
- デフォルト引数を持つ公開関数・コンストラクターには `@JvmOverloads`。
- 公開 API に `suspend` 関数、value class、Kotlin の関数型（`(A) -> B`）を出さない。コールバックは `fun interface` にする。
- 例外を投げる公開関数には `@Throws` を付ける。
- 公開 API を足したら、`core/src/test/java/` に Java から呼ぶテストを足す（コンパイルできることが検査になる）。

## コードスタイル

- Kotlin 公式スタイル（`kotlin.code.style=official`）、4スペース、末尾カンマあり。`.editorconfig` に従う。
- コメントと KDoc は日本語。公開 API には KDoc を書く。
- `gradle.properties` は ISO-8859-1 で読まれるので ASCII だけにする（日本語は `neoforge.mods.toml` などに書く）。

## Issue の扱い

- 構造: 機能ごとの Epic（`epic`）の下に、1タスク単位のサブ Issue（`task` / `decision` / `poc`）。
- ラベル:
  - `task`: 実装する
  - `decision`: 決めるべきこと
  - `poc`: 試作して測ってから決めるもの
  - `area: *`: 領域
  - `scope: v1`: 初版に入れる
  - `scope: v1-undecided`: 初版に入れるか未定
  - `scope: future`: 将来
- 依存は本文に「依存: #n」と書いてある。依存先が閉じていない Issue には着手しない。
- **`decision` Issue の結論を、エージェントが勝手に決めない。** 選択肢と推奨をコメントで提案し、人間の判断を待つ。実装中に未決の論点にぶつかったら、同じように Issue にコメントして止まる。
- 作業中に見つけた別の作業は、新しい Issue にして適切な Epic のサブ Issue にする（`gh api repos/neverclear86/KiBi-Computers/issues/<epic>/sub_issues -f sub_issue_id=<id>`。`id` は番号ではなく Issue の数値ID）。

## Git

- `main` から `issue-<番号>-<短い英語名>` のブランチを切り、PR で `main` に入れる。`main` への直接 push と force push はしない。
- コミットメッセージは英語の命令形（例: `Add VFS path normalization`）。PR の本文と Issue のコメントは日本語。
- PR 本文に `Closes #<番号>` を書く。Issue の一部だけ済んだときは `Refs #<番号>` にし、済んだ項目を Issue のチェックリストに反映する。
- 企画書、秘密情報、`run/` 以下の生成物はコミットしない。

## つまずきやすい点

- `platform-*/build.gradle.kts` でサブプロジェクトの `gradle.properties` を読むときは `property(name)` を使う（`providers.gradleProperty` はルートの値しか読まない）。
- リポジトリはルート `build.gradle.kts` の `subprojects {}` で宣言している（ModDevGradle がプロジェクト側にリポジトリを足すため、settings 側の宣言は無視される）。
- 1.20.1（Forge）は 1.21.1 と細部が違う: GameTest の構造物は `data/kibicomputers/structures/`（複数形）に置く。データを読ませるには `pack.mcmeta` が必要。`mods.toml` は `META-INF/mods.toml` で、依存の指定は `mandatory = true`。jar は `reobfJar` で SRG 名に変換される。
- GameTest はクラスに `@GameTestHolder(KiBiComputers.MOD_ID)` と `@PrefixGameTestTemplate(false)`、メソッドに `@JvmStatic` と `@GameTest(template = "empty")` を付ける。`empty` は `data/kibicomputers/structure/empty.nbt`（空の 3x3x3）。例: `platform-neoforge-1.21.1/src/main/kotlin/pro/ikili/kibicomputers/neoforge/gametest/SmokeGameTests.kt`。
