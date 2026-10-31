// ビルド用のプラグイン（WASM のビルドなど）。本体の settings.gradle.kts から includeBuild で読む
rootProject.name = "build-logic"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
