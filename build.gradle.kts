plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.moddev) apply false
}

val modVersion = providers.gradleProperty("mod_version").get()
val modGroupId = providers.gradleProperty("mod_group_id").get()

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
