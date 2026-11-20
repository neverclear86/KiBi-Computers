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
// dev の GameTest（#153 の他 MOD 役のデバイス）から、接続層の internal な型（CaseWiring など）を使う
kotlin.target.compilations.named("dev") {
    associateWith(kotlin.target.compilations.getByName("main"))
}

// 製品の jar を読む GameTest の run（runJarGameTestServer。#775）のクラスパス。MOD のソースセット（main・core など）と Endive を
// 載せず、KFF（MOD として読まれる）とゲームの依存だけにする。KiBi Computers 自身は mods/ に置いた jar から読む
val jarRun: SourceSet = sourceSets.create("jarRun")

// jar の GameTest（runJarGameTestServer。#775）の mods/ に、今のビルドの jar だけを置く（古い版の jar を残さない）
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
val loadTestWorld = "loadtest-world"
val cleanLoadTestWorld = cleanWorldTask("cleanLoadTestWorld", file("run/$loadTestWorld"))
// jar の GameTest（#775）は専用の run/jar-gametest で動くので、既定の名前のワールドをそのまま消す
val cleanJarGameTestWorld = cleanWorldTask("cleanJarGameTestWorld", file("run/jar-gametest/world"))
// Endive は全 run 共通の additionalRuntimeClasspath（レガシーのクラスパス）に載っている。jar の run ではそこから外し、jar に同梱したものだけを読ませる
configurations.matching { it.name == "jarGameTestServerLegacyClasspath" }.configureEach {
    exclude(group = "run.endive")
}

neoForge {
    version = prop("neoforge_version")
    // バージョンを決めたあとでないと呼べない
    addModdingDependenciesTo(dev)
    addModdingDependenciesTo(jarRun)

    parchment {
        minecraftVersion = mcVersion
        mappingsVersion = prop("parchment_mappings_version")
    }

    runs {
        configureEach {
            systemProperty("neoforge.enabledGameTestNamespaces", modId)
            // 調べるときは -PdevLogLevel=DEBUG（debug.log に debug の行が出る。#636）
            logLevel = providers.gradleProperty("devLogLevel").map { org.slf4j.event.Level.valueOf(it.uppercase()) }.getOrElse(org.slf4j.event.Level.INFO)
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
            // 他 MOD 役の試験用デバイス（dev ソースセット。#153）を登録する
            systemProperty("kibicomputers.dev.testDevices", "true")
            // CI のランナー（2コア）と同じ条件で試すときは -PgameTestProcessors=2（JVM に見せるコア数を絞る）
            providers.gradleProperty("gameTestProcessors").orNull?.let { jvmArgument("-XX:ActiveProcessorCount=$it") }
        }
        // ゲームの中での多数台の負荷試験（#345。docs/load-test.md）。測定用のテストだけを動かす。
        // 台数は -PloadTestCounts=100,500,1000 で渡す（既定は 1000）。初回のコンパイルを測るため、台数ごとに起動し直すとよい
        register("loadTestServer") {
            type = "gameTestServer"
            programArguments.addAll("--world", loadTestWorld)
            taskBefore(cleanLoadTestWorld)
            systemProperty("neoforge.enabledGameTestNamespaces", "${modId}_load")
            systemProperty("kibicomputers.loadTest.counts", providers.gradleProperty("loadTestCounts").getOrElse("1000"))
            // -PloadTestOs=standard で、Lua の REPL ではなく標準 OS のシェルで測る
            systemProperty("kibicomputers.loadTest.os", providers.gradleProperty("loadTestOs").getOrElse("lua"))
            // -PloadTestDevices=<n> で、n 台に1台の割合でケースの隣に他 MOD 役のデバイス（#153）を置く（既定は 0 = 置かない）
            systemProperty("kibicomputers.dev.testDevices", "true")
            systemProperty("kibicomputers.loadTest.devices", providers.gradleProperty("loadTestDevices").getOrElse("0"))
            // -PloadTestJfr=<ファイル> で、JFR のプロファイルを取る（メインスレッドで重いところを探すとき。#351）
            providers.gradleProperty("loadTestJfr").orNull?.let {
                jvmArgument("-XX:StartFlightRecording=filename=${file(it).absolutePath},settings=profile,dumponexit=true")
            }
            // -PloadTestQuietProgress=true で、GameTest のサーバーの進み具合のログ（20 tick ごと）を試験の間だけ止める（#600 の比べる回）
            systemProperty("kibicomputers.loadTest.quietProgress", providers.gradleProperty("loadTestQuietProgress").getOrElse("false"))
            // -PloadTestProductionAutosave=true で、自動保存の段階を本番の形（周期を止めず、バニラの自動保存と同じ tick に始める）で測る（#834）。
            // -PloadTestVanillaSaveOffset=<tick> でバニラの自動保存を一巡の始めから何 tick 後にするか、
            // -PloadTestSyntheticLogTicks=<tick> でメインスレッドに合成のログを何 tick ごとに書かせるか（0 は書かない）を決める
            systemProperty("kibicomputers.loadTest.productionAutosave", providers.gradleProperty("loadTestProductionAutosave").getOrElse("false"))
            systemProperty("kibicomputers.loadTest.vanillaSaveOffset", providers.gradleProperty("loadTestVanillaSaveOffset").getOrElse("0"))
            systemProperty("kibicomputers.loadTest.syntheticLogTicks", providers.gradleProperty("loadTestSyntheticLogTicks").getOrElse("0"))
            // -PloadTestJvmLog=<ファイル> で、GC と safepoint の JVM のログ（時刻と uptime つき）を取る（長い tick の突き合わせ。#600）。
            // -Xlog のファイル名にドライブの「:」を書くと区切りと混ざる（引用符は起動の引数ファイルで崩れる）ので、run/ からの相対パスで渡す
            providers.gradleProperty("loadTestJvmLog").orNull?.let {
                val runDir = file("run").toPath()
                val target = file(it).toPath()
                if (runDir.root != target.root) {
                    throw GradleException("-PloadTestJvmLog must be on the same drive as ${runDir.root} (the run/ directory): $it")
                }
                val log = runDir.relativize(target).toString().replace(File.separatorChar, '/')
                jvmArgument("-Xlog:gc*,safepoint:file=$log:time,uptime,level,tags")
            }
        }
        // 製品の jar（Jar-in-Jar で Endive を同梱したもの）を mods/ に置いて読み、GameTest を流す（#775）。
        // 開発環境の run と違い、MOD のソースセットも Endive もクラスパスに載せないので、同梱の漏れ・モジュールとしての読み込みの
        // 問題（開発環境の additionalRuntimeClasspath では気づけないもの）が出る。試験用のデバイス（dev ソースセット）は jar にないので登録しない
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

    implementation("thedarkcolour:kotlinforforge-neoforge:${prop("kff_version")}")
    "jarRunRuntimeOnly"("thedarkcolour:kotlinforforge-neoforge:${prop("kff_version")}")

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
