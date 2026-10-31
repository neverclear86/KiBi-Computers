pluginManagement {
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

// バージョンごとの接続層
include(":platform-neoforge-1.21.1")
