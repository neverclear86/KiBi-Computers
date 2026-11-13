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
val devDriver = project(":dev-driver")

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

// 開発環境でだけ読み込むコード（クライアントの自動操作の口など）。jar には入れない
val dev: SourceSet = sourceSets.create("dev") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

legacyForge {
    version = prop("forge_version")
    // バージョンを決めたあとでないと呼べない
    addModdingDependenciesTo(dev)

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

    modImplementation("thedarkcolour:kotlinforforge:${prop("kff_version")}")

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
    // コンポーネントの検証（#262）: Endive の WASI p1 と wasm-tools（WASM にしたもの）、その両方が依存するログ（run.endive:log）。
    // jarJar は推移的な依存を同梱しないので、log も明示する。仮想 FS の zerofs は使わないので入れない
    for (lib in listOf(libs.endive.wasi, libs.endive.wasm.tools, libs.endive.log)) {
        jarJar(lib) { exclude(group = "io.roastedroot") }
        implementation(lib) { exclude(group = "io.roastedroot") }
        "additionalRuntimeClasspath"(lib) { exclude(group = "io.roastedroot") }
    }
}

// 同梱漏れの確認（#262）: 実行時に要る Endive のライブラリ（run.endive）が、すべて Jar-in-Jar に入っていること。
// jarJar は推移的な依存を入れず、開発環境では additionalRuntimeClasspath が推移的に載せるので、漏れても GameTest では気づけない
val checkBundledLibraries = tasks.register("checkBundledLibraries") {
    group = "verification"
    description = "Checks that every run.endive runtime library is bundled with Jar-in-Jar"
    fun endiveModules(name: String) = configurations.named(name).map { c ->
        c.incoming.resolutionResult.allComponents.mapNotNull { (it.id as? ModuleComponentIdentifier)?.takeIf { id -> id.group == "run.endive" }?.module }.toSortedSet()
    }
    val needed = endiveModules("runtimeClasspath")
    val bundled = endiveModules("jarJar")
    doLast {
        val missing = needed.get() - bundled.get()
        if (missing.isNotEmpty()) throw GradleException("Jar-in-Jar に入っていない run.endive のライブラリ: $missing")
    }
}
tasks.check { dependsOn(checkBundledLibraries) }

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
        "minecraft_version_range" to prop("minecraft_version_range"),
        "forge_version_range" to prop("forge_version_range"),
        "kff_version_range" to prop("kff_version_range"),
    )
    inputs.properties(props)
    filesMatching("META-INF/mods.toml") {
        expand(props)
    }
}
