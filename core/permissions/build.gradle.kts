// Permisos por módulo (07-security-and-privacy.md §1): se piden solo al activar el módulo y se revocan al desactivarlo.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "mx.watchcompanion.core.permissions"
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
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit4)
}
