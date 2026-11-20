// Versiones en gradle/libs.versions.toml (alineadas con velora-android, ADR-014). Los módulos aplican lo que necesitan.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
