// #260 の PoC: endive-cm（Endive の Component Model 試作）を Java 17 でビルドし、
// KiBi Computers で要る点（Java 17、燃料計測、中断、状態の取り出し、依存の大きさ）を測る。
//
// 本体のビルドには含めない独立したビルド。実行はリポジトリのルートから:
//   ./gradlew -p poc/component-model :bench:run
//
// endive-cm は Maven Central に出ていない（999-SNAPSHOT）ので、固定したコミットのソースを
// GitHub から取ってきて、各モジュールをそのままサブプロジェクトとしてビルドする。

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "component-model-poc"

// 測った endive-cm のコミット（2026-10-30、"Depend on the released Endive 1.2.0"）
val endiveCmCommit = "2e541b7b463aeb8b6271d711c43d759fca7ad0cb"
val endiveCmDir = file("build/endive-cm-$endiveCmCommit")

if (!endiveCmDir.resolve("pom.xml").isFile) {
    val url = java.net.URI("https://codeload.github.com/roastedroot/endive-cm/zip/$endiveCmCommit").toURL()
    val prefix = "endive-cm-$endiveCmCommit/"
    url.openStream().use { raw ->
        java.util.zip.ZipInputStream(raw).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.removePrefix(prefix)
                // zip の外へ書き出さない
                if (name.isEmpty() || name.split('/').any { it == ".." }) continue
                val target = endiveCmDir.resolve(name)
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                }
            }
        }
    }
}

// endive-cm のモジュール（Maven のディレクトリ構成のまま使う）
val endiveCmModules = listOf(
    "types",
    "parser",
    "canonical-abi",
    "runtime",
    "wasm-tools",
    "bindgen-processor",
    "wasi-p2",
    "component-corpus",
)
for (module in endiveCmModules) {
    include(":cm-$module")
    project(":cm-$module").projectDir = endiveCmDir.resolve(module)
}

// 測定のコード
include(":bench")
