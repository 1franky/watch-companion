// Protocolo Watch ↔ Phone (00-architecture.md §5): Kotlin puro, sin Android, para probarlo rápido en la JVM.
// El transporte por Data Layer vive en las apps (WC-19); aquí solo el sobre, el catálogo y las reglas de validación.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit4)
}
