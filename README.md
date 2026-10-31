# KiBi Computers

**KiBi Computers**（キビコンピューターズ、MOD ID: `kibicomputers`）は、WebAssembly で動く、OpenComputers 寄りのコンピューター MOD です。部品を組み立ててコンピューターやロボットを作り、Lua でも Rust・Zig・C などの言語でもプログラムを書けます。

> 名前は、サトウキビの「キビ」と、2進接頭辞「Ki（キビ、1024倍）」を掛けたものです。Bを大文字にした表記で「KiB（キビバイト）」を表します。

**KiBi Computers** is a computer mod for Minecraft powered by WebAssembly, in the spirit of OpenComputers. Assemble computers and robots from parts, and program them in Lua, Rust, Zig, C, or any language that targets WASI.

> 🚧 開発初期段階です。まだ遊べる状態ではありません。 / Early development — not playable yet.

## 対応バージョン / Supported versions

| Minecraft | Loader | 状態 / Status |
| --- | --- | --- |
| 1.21.1 | NeoForge | 開発中（メイン） / In development (primary) |
| 1.20.1 | Forge | 開発中（雛形のみ） / In development (skeleton) |

[Kotlin for Forge](https://github.com/thedarkcolour/KotlinForForge) が必要です。 / Requires Kotlin for Forge.

## 構成 / Project layout

| モジュール | 内容 |
| --- | --- |
| `core` | Minecraft に依存しないコア（WASMランタイム、カーネル、VFS、状態保存など）。Java 17 ターゲット |
| `platform-neoforge-1.21.1` | Minecraft 1.21.1 / NeoForge の接続層。Java 21 |
| `platform-forge-1.20.1` | Minecraft 1.20.1 / Forge の接続層。Java 17 |

本体は Kotlin で書いていますが、公開 API は Java からも自然に使えるようにしています。

## ビルド / Building

JDK 21 と JDK 17 が必要です。見つからない場合は Gradle が自動でダウンロードします。

```bash
./gradlew build
```

成果物は `platform-*/build/libs/` に出力されます。

開発環境での起動:

```bash
./gradlew :platform-neoforge-1.21.1:runClient
```

## 開発の進め方 / Development

タスクと決めるべきことは [Issues](https://github.com/neverclear86/KiBi-Computers/issues) で管理しています。機能ごとの Epic（`epic` ラベル）の下に、1タスク単位のサブ issue があります。

## ライセンス / License

[MIT License](LICENSE)
