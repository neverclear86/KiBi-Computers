# 0461: ゲームの中で標準 OS を起動する仮の起動経路と、HDD の /home

- 状態: 採用
- Issue: #461（task）。前提: ADR 0049（起動の流れ）、0053（/tmp）、0098（OS のビルド）、0100（/sbin/init）、0124（HDD）
- 後続: #107・#145（OS 入りのフロッピーから HDD へのインストール。この経路を置き換える）、#99（起動部品の設定）、#56（起動ディスク以外のマウント）

## 決めたこと（要約）

- ケースの起動ディスクの候補は「ディスクのスロットの順のディスク → `image:os`」（ADR 0124 の `image:testboot` を置き換える）。
  `testboot`（Lua の REPL）は GameTest と負荷試験のために残し、ケースの `fallbackBootDisk`（internal。保存しない）で差し替えて使う
- 根が読み取り専用（同梱イメージ）で起動したときは、`BootConfig.homeDisk` のディスクを書き込める形で `/home` にマウントする。
  ケースは、ディスクのスロットの順で最初のディスク（識別子のあるもの）を `homeDisk` にする。ディスクがなければマウントしない
- **HDD をつなぐ場所は `/home`。** `HOME` を `/home` にする（`os/etc/inittab`）ので、`~` とシェルの履歴（`~/.sh_history`）が HDD に残る。
  `/` 全体を HDD に重ねる形（overlay）は作りが大きく、正式なインストール（#107）で HDD が根になるので採らない
- **HDD に `/sbin/init` があるときは、今のとおり HDD から起動する**（根が書き込めるので `/home` はマウントしない）
- `/tmp` は今のとおり RAM のファイルシステム（ADR 0053）
- イメージ `os` には、空の `/home`・`/tmp` を入れる（ビルドで作る。Git は空のディレクトリを持てないため）

## カーネル（core）

- `BootConfig.homeDisk`（`Builder.homeDisk(String?)`。既定 `null`）を足した。`BootConfig` は `@ApiStatus.Internal` で、`pro.ikili.kibicomputers.api` は変えない
- `BootSequence` は、根と `/tmp` をマウントした後、根が読み取り専用なら `homeDisk` を開き、`Machine.mountHome` で `/home` にマウントする
  （`source` は `disk:<識別子>`）。マウントは起動のたびに作り直し、止めたらほかのマウントと一緒に外す
- マウントしないとき（起動は続ける。コンソールに1行書く）:

| 場面 | メッセージ |
| --- | --- |
| 開けない（知らない識別子・例外） | `kibi: /home not mounted (cannot open)` |
| 読み取り専用でしか開けない（同梱イメージ） | `kibi: /home not mounted (read-only)` |
| 根の `/home` がディレクトリ以外 | `kibi: /home not mounted (<errno の名前>)` |

- `homeDisk` が根にしたディスクと同じなら、何もしない（書かない）

## 変えないもの

システムコール、セーブデータの形式（ケースは部品だけを書く。`fallbackBootDisk` は保存しない）、公開 API。
