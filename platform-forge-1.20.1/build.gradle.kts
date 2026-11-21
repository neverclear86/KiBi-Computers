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
    // 公開 API のダンプ（api/<プロジェクト名>.api）と食い違ったら check（build）で落とす（#845）。
    // 公開 API を変えたら ./gradlew updateKotlinAbi で作り直してコミットする。
    // まだ公開 API がないのでダンプは空。#224 で ...forge.api を足すと差分が出る。
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        filters {
            include {
                byNames.add("pro.ikili.kibicomputers.forge.api.**")
            }
        }
    }
}

// 開発環境でだけ読み込むコード（クライアントの自動操作の口など）。jar には入れない
val dev: SourceSet = sourceSets.create("dev") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

// 製品の jar を読む GameTest の run（runJarGameTestServer。#775）のクラスパス。MOD のソースセット（main・core など）と Endive を
// 載せず、KFF（MOD として読まれる）とゲームの依存だけにする。KiBi Computers 自身は mods/ に置いた jar から読む
val jarRun: SourceSet = sourceSets.create("jarRun")

// jar の GameTest の mods/ に、今のビルドの jar だけを置く（古い版の jar を残さない）。
// 製品物は reobfJar（SRG 名）だが、開発環境の実行時は Mojang 名なので、reobf の前の jar（tasks.jar）を使う
val syncJarGameTestMods = tasks.register<Sync>("syncJarGameTestMods") {
    from(tasks.jar.flatMap { it.archiveFile })
    into(file("run/jar-gametest/mods"))
}
// GameTest の run のワールドを、起動の前に消す（#792）。ワールドを回をまたいで使い回すと、途中で止めた回の強制読み込み
// （data/chunks.dat）が残り、次の回の起動でそのチャンクのケースが復元されて動き出す。CI と同じく、毎回まっさらなワールドで走らせる。
// 消すのは GameTest 専用のワールドだけ（run/world は runServer のもの）。失敗した回のワールドを調べるときは -PkeepGameTestWorld=true
val keepGameTestWorld = providers.gradleProperty("keepGameTestWorld").map { it.toBoolean() }.getOrElse(false)
fun cleanWorldTask(name: String, world: File) = tasks.register(name) {
    group = "verification"
    description = "Deletes the world of a GameTest run before it starts"
    doLast {
        if (keepGameTestWorld || !world.exists()) return@doLast
        // deleteRecursively は失敗しても止まらないので、残っていないことを確かめる。半分消えたワールドでは起動しない
        if (!world.deleteRecursively() || world.exists()) {
            throw GradleException(
                "GameTest のワールド $world を消せなかった。前の GameTest サーバーのプロセスが残っていないか確かめる",
            )
        }
    }
}
// run/ の中のワールドの名前。--world で渡す（gameDirectory は変えない。ログとコンフィグは run/ のものを使う）
val gameTestWorld = "gametest-world"
val cleanGameTestWorld = cleanWorldTask("cleanGameTestWorld", file("run/$gameTestWorld"))
// jar の GameTest（#775）は専用の run/jar-gametest で動くので、既定の名前のワールドをそのまま消す
val cleanJarGameTestWorld = cleanWorldTask("cleanJarGameTestWorld", file("run/jar-gametest/world"))
// Endive は全 run 共通の additionalRuntimeClasspath（レガシーのクラスパス）に載っている。jar の run ではそこから外し、jar に同梱したものだけを読ませる
configurations.matching { it.name == "jarGameTestServerLegacyClasspath" }.configureEach {
    exclude(group = "run.endive")
}

legacyForge {
    version = prop("forge_version")
    // バージョンを決めたあとでないと呼べない
    addModdingDependenciesTo(dev)
    addModdingDependenciesTo(jarRun)

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
            // 既定では起動時にマスター音量を 0 にする。音を確かめるときは -PdevSound=true（#786）
            systemProperty("kibicomputers.dev.sound", providers.gradleProperty("devSound").getOrElse("false"))
        }
        register("server") {
            server()
            programArgument("--nogui")
        }
        register("gameTestServer") {
            type = "gameTestServer"
            programArguments.addAll("--world", gameTestWorld)
            taskBefore(cleanGameTestWorld)
        }
        // 製品の jar（Jar-in-Jar で Endive を同梱したもの）を mods/ に置いて読み、GameTest を流す（#775）。
        // 開発環境の run と違い、MOD のソースセットも Endive もクラスパスに載せないので、同梱の漏れ・モジュールとしての読み込みの
        // 問題（開発環境の additionalRuntimeClasspath では気づけないもの）が出る
        register("jarGameTestServer") {
            type = "gameTestServer"
            sourceSet = jarRun
            loadedMods = emptySet()
            gameDirectory = file("run/jar-gametest")
            systemProperty("kibicomputers.jarTest", "true")
            taskBefore(syncJarGameTestMods)
            taskBefore(cleanJarGameTestWorld)
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
    // jar の GameTest の run（#775）のクラスパスにも、Mojang 名に付け替えた KFF を載せる
    obfuscation.createRemappingConfiguration(configurations.getByName("jarRunRuntimeOnly"))
    "modJarRunRuntimeOnly"("thedarkcolour:kotlinforforge:${prop("kff_version")}")

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
