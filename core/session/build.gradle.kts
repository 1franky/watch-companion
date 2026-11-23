// Sesiones de los backends en app-phone (WC-ADR-013): refresh token cifrado con una llave del Android Keystore, un archivo
// y una llave por backend para que Velora y Nexora no compartan nada (00-architecture.md §6).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "mx.watchcompanion.core.session"
    compileSdk = 37
    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    lint {
        warningsAsErrors = true
        abortOnError = true
        disable += setOf("NewerVersionAvailable", "AndroidGradlePluginVersion", "GradleDependency", "ObsoleteLintCustomCheck")
    }
}

kotlin { jvmToolchain(21) }

dependencies {
    api(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    // El Keystore solo existe en un dispositivo o emulador: `./gradlew :core:session:connectedDebugAndroidTest`.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
