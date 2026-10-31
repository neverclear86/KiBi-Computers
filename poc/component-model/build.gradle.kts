// endive-cm の各モジュールを Java 17 でビルドする（pom.xml の依存を Gradle に書き写したもの）

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

val endive = libs.versions.endive.get()
val netty = "4.2.19.Final"
val nettyCompile = listOf(
    "netty-buffer",
    "netty-common",
    "netty-resolver",
    "netty-resolver-dns",
    "netty-transport",
    "netty-transport-classes-epoll",
    "netty-transport-classes-io_uring",
    "netty-transport-classes-kqueue",
).map { "io.netty:$it:$netty" }

// pom.xml の依存（テスト用を除く）
val cmDependencies: Map<String, List<Any>> = mapOf(
    "cm-types" to listOf("run.endive:wasm:$endive"),
    "cm-parser" to listOf("run.endive:wasm:$endive", ":cm-types"),
    "cm-canonical-abi" to listOf("run.endive:runtime:$endive", "run.endive:wasm:$endive", ":cm-types"),
    "cm-runtime" to listOf(
        "run.endive:runtime:$endive",
        "run.endive:wasm:$endive",
        ":cm-canonical-abi",
        ":cm-types",
    ),
    "cm-wasm-tools" to listOf(
        "io.roastedroot:zerofs:0.1.0",
        "run.endive:log:$endive",
        "run.endive:runtime:$endive",
        "run.endive:wasi:$endive",
        "run.endive:wasm:$endive",
        "run.endive:wasm-tools:$endive",
    ),
    "cm-bindgen-processor" to listOf(
        "com.github.javaparser:javaparser-core:3.28.2",
        ":cm-parser",
        ":cm-runtime",
        ":cm-wasm-tools",
    ),
    "cm-wasi-p2" to listOf(":cm-canonical-abi", ":cm-runtime") + nettyCompile,
    "cm-component-corpus" to emptyList(),
)

subprojects {
    val deps = cmDependencies[name] ?: return@subprojects
    apply(plugin = "java-library")
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion = JavaLanguageVersion.of(17)
    }
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = 11
    }
    // endive-cm のテストは Maven の別プラグインで生成するので、ここではビルドしない
    tasks.named("compileTestJava") { enabled = false }
    tasks.named("test") { enabled = false }
    dependencies {
        for (dep in deps) {
            val notation = if (dep is String && dep.startsWith(":")) project(dep) else dep
            add("api", notation)
        }
        if (name == "cm-wasi-p2") {
            add("annotationProcessor", project(":cm-bindgen-processor"))
        }
    }
    if (name == "cm-wasi-p2") {
        // bindgen-processor は WIT をクラスパスから読む。Maven と違い Gradle は resources を
        // コンパイル前のクラス出力に置かないので、resources のディレクトリをクラスパスに足す
        tasks.named<JavaCompile>("compileJava") {
            classpath += files("src/main/resources")
        }
    }
}
