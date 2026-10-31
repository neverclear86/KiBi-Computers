import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// Minecraft 1.20.1 / Forge の接続層
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.moddev.legacyforge)
}

// サブプロジェクトの gradle.properties も読むため providers.gradleProperty ではなく property を使う
fun prop(name: String): String = property(name) as String

val modId = prop("mod_id")
val core = project(":core")
val mcVersion = prop("minecraft_version")

base.archivesName = "$modId-forge-$mcVersion"

java.toolchain.languageVersion = JavaLanguageVersion.of(17)

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        // KFF 4.x が同梱する Kotlin は 2.2 系なので、それより新しい API を使わない
        apiVersion = KotlinVersion.KOTLIN_2_2
        languageVersion = KotlinVersion.KOTLIN_2_2
    }
}

legacyForge {
    version = prop("forge_version")

    parchment {
        minecraftVersion = mcVersion
        mappingsVersion = prop("parchment_mappings_version")
    }

    runs {
        configureEach {
            systemProperty("forge.enabledGameTestNamespaces", modId)
            logLevel = org.slf4j.event.Level.INFO
        }
        register("client") {
            client()
        }
        register("server") {
            server()
            programArgument("--nogui")
        }
        register("gameTestServer") {
            type = "gameTestServer"
        }
        register("data") {
            data()
            programArguments.addAll(
                "--mod", modId, "--all",
                "--output", file("src/generated/resources/").absolutePath,
                "--existing", file("src/main/resources/").absolutePath,
            )
        }
    }

    // core のクラスも同じ MOD として読み込ませる
    mods {
        register(modId) {
            sourceSet(sourceSets.main.get())
            sourceSet(core.sourceSets.main.get())
        }
    }
}

sourceSets.main {
    resources.srcDir("src/generated/resources")
}

dependencies {
    // core は MOD の一部として jar に直接入れるので、ここではコンパイル時だけ参照する
    compileOnly(project(":core"))

    modImplementation("thedarkcolour:kotlinforforge:${prop("kff_version")}")

    // MOD ではない外部ライブラリは Jar-in-Jar で同梱する
    for (lib in listOf(libs.chicory.runtime, libs.chicory.wasm)) {
        jarJar(lib)
        implementation(lib)
        // 開発環境の実行時クラスパスにも載せる（Kotlin DSL では型安全アクセサが生成されないので文字列で指定）
        "additionalRuntimeClasspath"(lib)
    }
}

tasks.jar {
    from(core.sourceSets.main.get().output)
    from(rootProject.file("LICENSE")) { rename { "LICENSE_$modId" } }
}

tasks.withType<ProcessResources>().configureEach {
    val props = mapOf(
        "mod_id" to modId,
        "mod_name" to prop("mod_name"),
        "mod_version" to project.version.toString(),
        "mod_authors" to prop("mod_authors"),
        "mod_license" to prop("mod_license"),
        "minecraft_version_range" to prop("minecraft_version_range"),
        "forge_version_range" to prop("forge_version_range"),
        "kff_version_range" to prop("kff_version_range"),
    )
    inputs.properties(props)
    filesMatching("META-INF/mods.toml") {
        expand(props)
    }
}
