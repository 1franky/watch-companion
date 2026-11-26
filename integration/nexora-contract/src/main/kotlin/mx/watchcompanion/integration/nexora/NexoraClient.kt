package mx.watchcompanion.integration.nexora

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import mx.watchcompanion.integration.nexora.api.apis.NexoraApi

/**
 * Punto de entrada al cliente generado del contrato de Nexora (WC-36). Solo lo usa `app-phone` (WC-ADR-011,
 * WC-ADR-013). Las rutas del contrato ya incluyen `/api/v1`, así que la base es el servidor.
 */
object NexoraClient {
    /** Servidor desplegado de Nexora (el mismo que usa nexora-android). */
    const val DEFAULT_SERVER = "https://nexora-api.franciscolopez.uk"

    /**
     * Tolerante a campos nuevos. Un valor de enum nuevo hace fallar la lectura: quien llame debe tratarlo como error,
     * nunca como éxito.
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        serializersModule = ContractSerializers.module
    }

    /** Exige HTTPS salvo [allowCleartext], que solo se activa en debug para probar contra una API local (07 §1). */
    fun baseUrl(serverUrl: String, allowCleartext: Boolean = false): String {
        val server = serverUrl.trim().trimEnd('/')
        require(server.startsWith("https://") || (allowCleartext && server.startsWith("http://"))) {
            "Nexora exige HTTPS"
        }
        return server
    }

    fun create(
        serverUrl: String = DEFAULT_SERVER,
        allowCleartext: Boolean = false,
        engine: HttpClientEngine = OkHttp.create(),
    ): NexoraApi = NexoraApi(
        baseUrl = baseUrl(serverUrl, allowCleartext),
        httpClientEngine = engine,
        httpClientConfig = {
            // Nexora declara sus respuestas como `*/*` (springdoc): se leen como JSON aunque el tipo no lo diga.
            it.install(ContentNegotiation) {
                json(json)
                json(json, ContentType.Any)
            }
            it.install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 20_000
            }
        },
    )
}
