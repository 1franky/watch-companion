import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Firma de release compartida con la otra app del companion (WC-ADR-011): en CI llega por variables de entorno; en local,
 * de `keystore.properties` (ignorado por Git, ver keystore.properties.example). Sin ninguna, solo falla
 * `assembleRelease`. En debug ambas apps usan la llave de depuración de la máquina que compila, que es la misma.
 */
val keystoreProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

fun releaseSigning(key: String, env: String): String? = System.getenv(env) ?: keystoreProperties.getProperty(key)

android {
    namespace = "mx.watchcompanion.watch"
    compileSdk = 37

    defaultConfig {
        // Mismo applicationId en ambas apps, definido una sola vez en gradle.properties (WC-ADR-011).
        applicationId = providers.gradleProperty("companion.applicationId").get()
        // Wear OS 6 / One UI 8 Watch, verificado en el Galaxy Watch Ultra (WC-ADR-014).
        minSdk = 36
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        androidResources.localeFilters += listOf("es-rMX", "es")
    }

    signingConfigs {
        create("release") {
            releaseSigning("storeFile", "KEYSTORE_PATH")?.let { storeFile = file(it) }
            storePassword = releaseSigning("storePassword", "KEYSTORE_PASSWORD")
            keyAlias = releaseSigning("keyAlias", "KEY_ALIAS")
            keyPassword = releaseSigning("keyPassword", "KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
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
    implementation(project(":core:data-layer"))
    implementation(project(":core:permissions"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear)
    implementation(libs.wear.ongoing)
    implementation(libs.health.services.client)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.wear.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
