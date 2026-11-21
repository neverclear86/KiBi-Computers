plugins {
    `kotlin-dsl`
}

dependencies {
    // Zig の配布アーカイブ（.tar.xz）を展開するため
    implementation(libs.commons.compress)
    implementation(libs.xz)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

gradlePlugin {
    plugins {
        register("wasm") {
            id = "kibicomputers.wasm"
            implementationClass = "pro.ikili.kibicomputers.buildlogic.WasmPlugin"
        }
        register("gametestLock") {
            id = "kibicomputers.gametest-lock"
            implementationClass = "pro.ikili.kibicomputers.buildlogic.GameTestLockPlugin"
        }
    }
}
