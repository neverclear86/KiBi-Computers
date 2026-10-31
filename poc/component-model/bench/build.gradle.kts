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
}

application {
    mainClass = "pro.ikili.kibicomputers.poc.cm.BenchKt"
}
