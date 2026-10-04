plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    application
}

kotlin { jvmToolchain(21) }

application { mainClass = "salt.ServerKt" }

dependencies {
    implementation(project(":shared"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.json)
    implementation(libs.logback)
    implementation(libs.bouncycastle.pkix) // X.509 generation for the HTTPS-decrypting proxy; the JDK has no public API for it
    testImplementation(libs.kotlin.test)
}

// Bundle the compose-wasm web UI into the server's static resources.
evaluationDependsOn(":webApp")
val webDist = project(":webApp").tasks.named("wasmJsBrowserDistribution")
// Compose desktop jars arrive twice via :shared; the server never loads Compose, so first-wins is safe.
tasks.withType<AbstractCopyTask>().configureEach { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }

tasks.processResources {
    dependsOn(webDist)
    from(webDist) { into("web") }
}
