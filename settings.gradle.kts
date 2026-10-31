pluginManagement {
    // WASM のビルド（Zig）などのビルド用プラグイン
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "kibicomputers"

// Minecraft 非依存のコア（WASMランタイム、カーネル、VFS、状態保存など）
include(":core")

// 開発環境でだけ使う、クライアントの自動操作の口（リリースの jar には入れない）
include(":dev-driver")

// バージョンごとの接続層
include(":platform-neoforge-1.21.1")
include(":platform-forge-1.20.1")

// 技術検証（PoC）の測定用コード。MOD の jar には入れない
include(":poc:screen-sync")
include(":poc:endive-bench")
include(":poc:exec-thread")
include(":poc:fuel-metering")
include(":poc:player-exec")
include(":poc:wait-point-save")
