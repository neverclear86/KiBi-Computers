plugins {
    `kotlin-dsl`
}

dependencies {
    // Zig の配布アーカイブ（.tar.xz）を展開するため
    implementation(libs.commons.compress)
    implementation(libs.xz)
}

gradlePlugin {
    plugins {
        register("wasm") {
            id = "kibicomputers.wasm"
            implementationClass = "pro.ikili.kibicomputers.buildlogic.WasmPlugin"
        }
    }
}
