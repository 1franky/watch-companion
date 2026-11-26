import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.openapi.generator)
}

android {
    namespace = "mx.watchcompanion.integration.nexora"
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
 * Cliente de Nexora API generado del contrato fijado en contract/nexora (WC-36, mismo esquema que Velora, WC-ADR-009):
 * si el contrato cambia y el código no, no compila. El contrato lo exporta nexora-api desde springdoc con operationIds
 * estables (nexora-api #50).
 *
 * Solo se generan las operaciones que usa el companion (`FILTER` por operationId). Agregar una aquí es parte de la
 * tarjeta que la necesita (03 §5): el dashboard (WC-38), cuentas y categorías y el movimiento (WC-40), las
 * notificaciones (WC-41). `NexoraScopeTest` vigila que no entre una escritura sin justificar.
 */
val companionOperations = listOf(
    "authLogin", "authRefresh", "authLogout", // sesión propia de app-phone (WC-37, WC-ADR-013)
    "userMe", // comprobar la sesión y mostrar con qué cuenta se entró
    "dashboardGet", // resumen financiero de solo lectura para el reloj (WC-38)
    "accountList", "categoryList", // opciones del borrador del reloj (WC-40)
    // transactionCreate (WC-40) no se genera: va escrito a mano en NexoraCompanionApi con su Idempotency-Key.
)

val generatedApi = layout.buildDirectory.dir("generated/openapi")

val generateNexoraApi by tasks.registering(GenerateTask::class) {
    generatorName.set("kotlin")
    library.set("jvm-ktor")
    inputSpec.set(rootProject.layout.projectDirectory.file("contract/nexora/openapi-v1.yaml").asFile.path)
    outputDir.set(generatedApi.get().asFile.path)
    packageName.set("mx.watchcompanion.integration.nexora.api")
    openapiNormalizer.set(
        mapOf(
            "FILTER" to "operationId:${companionOperations.joinToString("|")}",
            // Un solo cliente (NexoraClient), como el de Velora: las etiquetas del contrato son para la documentación.
            "SET_TAGS_FOR_ALL_OPERATIONS" to "Nexora",
        ),
    )
    configOptions.set(
        mapOf(
            "serializationLibrary" to "kotlinx_serialization",
            "dateLibrary" to "java8",
            "sourceFolder" to "src/main/kotlin",
            "omitGradleWrapper" to "true",
        ),
    )
    // FILTER no poda modelos: solo se generan los que alcanzan las operaciones de arriba.
    globalProperties.set(
        mapOf(
            "apis" to "",
            "supportingFiles" to "",
            "models" to listOf(
                "LoginRequest", "RefreshRequest", "TokenResponse", "ApiError", "FieldError", "UserResponse",
                // dashboardGet (WC-38): la respuesta completa, aunque el reloj solo use tres cifras.
                "DashboardResponse", "MonthlyPointResponse", "CategoryAmountResponse", "TransactionResponse",
                "UpcomingCardPaymentResponse",
                // WC-40: cuentas y categorías para el borrador, y el cuerpo del movimiento.
                "AccountResponse", "CategoryResponse", "CreateTransactionRequest",
            ).joinToString(","),
        ),
    )
    typeMappings.set(mapOf("number" to "kotlin.Double", "decimal" to "kotlin.Double"))
    cleanupOutput.set(true)
    generateApiTests.set(false)
    generateModelTests.set(false)
    generateApiDocumentation.set(false)
    generateModelDocumentation.set(false)
}

kotlin { jvmToolchain(21) }

androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addStaticSourceDirectory(generatedApi.get().dir("src/main/kotlin").asFile.path)
    }
}
tasks.named("preBuild") { dependsOn(generateNexoraApi) }

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
