import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// #29 プレイヤーの WASM の実行方式の比較（PoC）。MOD の jar には入れない。
//
// `./gradlew :poc:player-exec:run` で測定結果を表で出す（`build/bench/results.md` にも書く）。
// `-Pbench.args=quick` で回数を減らす。
// 比べる方式: A（常にインタプリタ）/ B（常にランタイムコンパイラ）/ C（インタプリタで始め、よく使われるものだけコンパイルする）
// どの方式でも、読み込み時に燃料の計測コードを挿入する（#28 の FuelInjector をそのまま使う）
plugins {
    application
    alias(libs.plugins.kotlin.jvm)
}

java.toolchain.languageVersion = JavaLanguageVersion.of(17)

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        apiVersion = KotlinVersion.KOTLIN_2_2
        languageVersion = KotlinVersion.KOTLIN_2_2
    }
}

application {
    mainClass = "pro.ikili.kibicomputers.poc.playerexec.MainKt"
}

dependencies {
    // 燃料の計測コードの挿入（FuelInjector）、bench.wasm・lua.wasm・Lua のスクリプトは #28 の PoC のものを使う
    implementation(project(":poc:fuel-metering"))
    implementation(libs.endive.runtime)
    implementation(libs.endive.wasm)
    implementation(libs.endive.wasi)
    implementation(libs.endive.compiler)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
}

// 測定は MC 1.21.1 と同じ Java 21 で行う（#26 で、Java 17 と 21 の差は小さいと分かっている）。
// MC のサーバーに近い条件（G1、ヒープは固定）
tasks.named<JavaExec>("run") {
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    jvmArgs("-Xms2g", "-Xmx2g", "-XX:+UseG1GC")
    args(project.findProperty("bench.args")?.toString()?.split(' ') ?: emptyList<String>())
}
