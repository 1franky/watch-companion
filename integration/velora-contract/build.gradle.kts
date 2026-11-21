import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.openapi.generator)
}

android {
    namespace = "mx.watchcompanion.integration.velora"
    compileSdk = 37
    defaultConfig { minSdk = 31 }
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

/**
 * Cliente de Velora API generado del contrato fijado en contract/velora (ADR-009): si el contrato cambia y el código no,
 * no compila. Mismo generador y opciones que velora-android (Ktor + kotlinx.serialization, fechas con java.time).
 *
 * Solo se generan las operaciones que usa el companion (`FILTER` por operationId). Agregar una aquí es parte de la
 * tarjeta que la necesita.
 */
val companionOperations = listOf(
    "login", "refreshTokens", "logout", // sesión de app-phone (WC-22)
    "getMe", // FC máxima para las zonas (WC-32)
    "listActivities", "getStravaStatus", // estado de la última actividad (WC-33)
)

val generatedApi = layout.buildDirectory.dir("generated/openapi")

val generateVeloraApi by tasks.registering(GenerateTask::class) {
    generatorName.set("kotlin")
    library.set("jvm-ktor")
    inputSpec.set(rootProject.layout.projectDirectory.file("contract/velora/openapi-v1.yaml").asFile.path)
    outputDir.set(generatedApi.get().asFile.path)
    packageName.set("mx.watchcompanion.integration.velora.api")
    openapiNormalizer.set(mapOf("FILTER" to "operationId:${companionOperations.joinToString("|")}"))
    configOptions.set(
        mapOf(
            "serializationLibrary" to "kotlinx_serialization",
            "dateLibrary" to "java8",
            "sourceFolder" to "src/main/kotlin",
            "omitGradleWrapper" to "true",
        ),
    )
    // FILTER no poda modelos: solo se generan los que alcanzan las operaciones de arriba (al agregar una operación,
    // agrega aquí sus modelos; si falta uno, no compila). Cadena vacía = todas las APIs filtradas y archivos de soporte.
    globalProperties.set(
        mapOf(
            "apis" to "",
            "supportingFiles" to "",
            "models" to listOf(
                "LoginRequest", "RefreshRequest", "TokenResponse", "Problem",
                "Me", "Preferences",
                "ActivitySummary", "ActivitySubType", "StravaStatus",
                // Esquemas en línea: se nombran por su clave interna, no por la clase que generan.
                "listActivities_200_response",
            ).joinToString(","),
        ),
    )
    typeMappings.set(mapOf("number" to "kotlin.Double", "decimal" to "kotlin.Double"))
    // Enum entero de privacidad: el generador lo emite como enum de String con valores Int (igual que en velora-android).
    schemaMappings.set(mapOf("TrackPrivacyMeters" to "kotlin.Int"))
    cleanupOutput.set(true)
    generateApiTests.set(false)
    generateModelTests.set(false)
    generateApiDocumentation.set(false)
    generateModelDocumentation.set(false)
}

kotlin { jvmToolchain(21) }

// AGP 9 compila Kotlin por sí mismo: el código generado se agrega con la Variant API y se genera antes de compilar.
androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addStaticSourceDirectory(generatedApi.get().dir("src/main/kotlin").asFile.path)
    }
}
tasks.named("preBuild") { dependsOn(generateVeloraApi) }

dependencies {
    api(project(":core:session"))
    api(project(":core:diagnostics"))
    api(libs.ktor.client.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    testImplementation(libs.junit4)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
}
