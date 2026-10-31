import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    application
    alias(libs.plugins.kotlin.jvm)
}

// core と同じく Java 17 で動かす（1.20.1 の条件）
java.toolchain.languageVersion = JavaLanguageVersion.of(17)
kotlin {
    jvmToolchain(17)
    compilerOptions.jvmTarget = JvmTarget.JVM_17
}

dependencies {
    implementation(project(":cm-parser"))
    implementation(project(":cm-runtime"))
    implementation(project(":cm-wasi-p2"))
    implementation(project(":cm-component-corpus"))
    // 検証（wasm-tools validate）を使う場合の依存の大きさも測る
    implementation(project(":cm-wasm-tools"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.4")
    // #28 で決めた燃料の計測（コードの挿入）がコンポーネントの中で使えるか
    implementation(libs.endive.compiler)
}

// #28 の PoC の挿入処理（FuelInjector）をそのまま使う
val copyFuelInjector by tasks.registering(Copy::class) {
    from("../../fuel-metering/src/main/kotlin/pro/ikili/kibicomputers/poc/fuel/FuelInjector.kt")
    into(layout.buildDirectory.dir("fuel-src/pro/ikili/kibicomputers/poc/fuel"))
}
kotlin.sourceSets.main {
    kotlin.srcDir(copyFuelInjector.map { layout.buildDirectory.dir("fuel-src").get() })
}

application {
    mainClass = "pro.ikili.kibicomputers.poc.cm.BenchKt"
}
