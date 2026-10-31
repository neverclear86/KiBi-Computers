import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Minecraft 1.21.1 / NeoForge の接続層
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.moddev)
}

// サブプロジェクトの gradle.properties も読むため providers.gradleProperty ではなく property を使う
fun prop(name: String): String = property(name) as String

val modId = prop("mod_id")
val core = project(":core")
val mcVersion = prop("minecraft_version")

base.archivesName = "$modId-neoforge-$mcVersion"

java.toolchain.languageVersion = JavaLanguageVersion.of(21)

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget = JvmTarget.JVM_21
}

neoForge {
    version = prop("neoforge_version")

    parchment {
        minecraftVersion = mcVersion
        mappingsVersion = prop("parchment_mappings_version")
    }

    runs {
        configureEach {
            systemProperty("neoforge.enabledGameTestNamespaces", modId)
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

    implementation("thedarkcolour:kotlinforforge-neoforge:${prop("kff_version")}")

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
        "minecraft_version" to mcVersion,
        "minecraft_version_range" to prop("minecraft_version_range"),
        "neoforge_version_range" to prop("neoforge_version_range"),
        "kff_version_range" to prop("kff_version_range"),
        "neoforge_version" to prop("neoforge_version"),
        "kff_version" to prop("kff_version"),
    )
    inputs.properties(props)
    filesMatching("META-INF/neoforge.mods.toml") {
        expand(props)
    }
}
