// Protocolo Watch ↔ Phone (00-architecture.md §5): Kotlin puro, sin Android, para probarlo rápido en la JVM.
// El transporte real (Data Layer) vive en core:data-layer; aquí el sobre, el catálogo, la validación y la sesión.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":core:diagnostics"))
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
