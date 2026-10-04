import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName = "salt-web"
        browser { commonWebpackConfig { outputFileName = "salt-web.js" } }
        binaries.executable()
    }
    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.runtime)
            implementation(compose.ui)
        }
    }
}
