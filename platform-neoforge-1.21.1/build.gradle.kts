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
val devDriver = project(":dev-driver")

base.archivesName = "$modId-neoforge-$mcVersion"

java.toolchain.languageVersion = JavaLanguageVersion.of(21)

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget = JvmTarget.JVM_21
}

// 開発環境でだけ読み込むコード（クライアントの自動操作の口など）。jar には入れない
val dev: SourceSet = sourceSets.create("dev") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

neoForge {
    version = prop("neoforge_version")
    // バージョンを決めたあとでないと呼べない
    addModdingDependenciesTo(dev)

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
            // 開発クライアントの自動操作の口（#252）。ポートは既定で自動、実際の番号は run/dev-driver.port に書く
            systemProperty("kibicomputers.devDriver.port", providers.gradleProperty("devDriverPort").getOrElse("0"))
            systemProperty("kibicomputers.devDriver.portFile", file("run/dev-driver.port").absolutePath)
        }
        register("server") {
            server()
            programArgument("--nogui")
        }
        register("gameTestServer") {
            type = "gameTestServer"
        }
        // ゲームの中での多数台の負荷試験（#345。docs/load-test.md）。測定用のテストだけを動かす。
        // 台数は -PloadTestCounts=100,500,1000 で渡す（既定は 1000）。初回のコンパイルを測るため、台数ごとに起動し直すとよい
        register("loadTestServer") {
            type = "gameTestServer"
            systemProperty("neoforge.enabledGameTestNamespaces", "${modId}_load")
            systemProperty("kibicomputers.loadTest.counts", providers.gradleProperty("loadTestCounts").getOrElse("1000"))
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
            sourceSet(dev)
            sourceSet(devDriver.sourceSets.main.get())
        }
    }
}

sourceSets.main {
    resources.srcDir("src/generated/resources")
}

dependencies {
    // core は MOD の一部として jar に直接入れるので、ここではコンパイル時だけ参照する
    compileOnly(project(":core"))
    "devCompileOnly"(project(":core"))
    "devCompileOnly"(project(":dev-driver"))

    implementation("thedarkcolour:kotlinforforge-neoforge:${prop("kff_version")}")

    // MOD ではない外部ライブラリは Jar-in-Jar で同梱する
    for (lib in listOf(libs.endive.runtime, libs.endive.wasm)) {
        jarJar(lib)
        implementation(lib)
        // 開発環境の実行時クラスパスにも載せる（Kotlin DSL では型安全アクセサが生成されないので文字列で指定）
        "additionalRuntimeClasspath"(lib)
    }
    // ランタイムコンパイラ（#37）。使う ASM は、ローダーがすでに持っているもの（ゲームのレイヤーに見える）を使うので同梱しない
    jarJar(libs.endive.compiler) { isTransitive = false }
    implementation(libs.endive.compiler) { exclude(group = "org.ow2.asm") }
    "additionalRuntimeClasspath"(libs.endive.compiler) { isTransitive = false }
}

// dev ソースセットも build でコンパイルする（jar には入れない）
tasks.build {
    dependsOn(dev.classesTaskName)
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
