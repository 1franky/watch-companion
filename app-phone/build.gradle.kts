plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "mx.watchcompanion.phone"
    compileSdk = 37

    defaultConfig {
        // Mismo applicationId que app-watch: la Data Layer solo comunica apps con el mismo id y firma (ADR-011).
        applicationId = "mx.watchcompanion"
        // Android 12 o posterior, igual que velora-android (ADR-014).
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        androidResources.localeFilters += listOf("es-rMX", "es")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures { compose = true }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric con SDK 36 sobre JDK 21 necesita acceso a los internos de FileDescriptor (SharedSecrets).
        unitTests.all { it.jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED") }
    }
    lint {
        warningsAsErrors = true
        abortOnError = true
        // Cambian con cada versión nueva: las actualizaciones van en PRs propios, no rompen el CI.
        disable += setOf("NewerVersionAvailable", "AndroidGradlePluginVersion", "GradleDependency", "ObsoleteLintCustomCheck")
    }
}

// Java 21: Robolectric exige Java 21 para emular el SDK 36.
kotlin { jvmToolchain(21) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}
