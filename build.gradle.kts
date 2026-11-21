plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.moddev) apply false
    alias(libs.plugins.moddev.legacyforge) apply false
    // GameTest のサーバーを、マシンの中で同時に 1 本だけにする（#855。build-logic の GameTestLockPlugin）
    id("kibicomputers.gametest-lock")
    // ルートの build（./gradlew build）で build-logic の単体テストも回すため
    base
}

val modVersion = providers.gradleProperty("mod_version").get()
val modGroupId = providers.gradleProperty("mod_group_id").get()

// build-logic は included build なので、ルートの build からは明示しないとテストが走らない
tasks.check {
    dependsOn(gradle.includedBuild("build-logic").task(":test"))
}

subprojects {
    version = modVersion
    group = modGroupId

    // ModDevGradle がプロジェクト側にリポジトリを追加するため、settings ではなくここで宣言する
    repositories {
        mavenCentral()
        maven("https://thedarkcolour.github.io/KotlinForForge/") {
            name = "Kotlin for Forge"
            content { includeGroup("thedarkcolour") }
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }
}
