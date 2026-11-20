// Transporte real del protocolo sobre la Wear OS Data Layer (00-architecture.md §5), compartido por reloj y teléfono.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "mx.watchcompanion.core.datalayer"
    compileSdk = 37
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric con SDK 36 sobre JDK 21 necesita acceso a los internos de FileDescriptor (SharedSecrets).
        unitTests.all { it.jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED") }
    }
    lint {
        warningsAsErrors = true
        abortOnError = true
        disable += setOf("NewerVersionAvailable", "AndroidGradlePluginVersion", "GradleDependency", "ObsoleteLintCustomCheck")
    }
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":core:phone-communication"))
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
}
