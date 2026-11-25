package mx.watchcompanion.integration.velora

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.Json

/**
 * Punto de entrada al cliente generado del contrato de Velora (WC-ADR-009). Solo lo usa `app-phone` (WC-ADR-011, WC-ADR-013).
 */
object VeloraApi {
    /** Servidor desplegado de Velora (el mismo que usa velora-android por omisión). */
    const val DEFAULT_SERVER = "https://velora-api.franciscolopez.uk"

    /**
     * Tolerante a campos nuevos. Un valor de enum nuevo, en cambio, hace fallar la lectura (el generador no crea caso
     * desconocido con kotlinx.serialization): quien llame debe tratarlo como error, nunca como éxito.
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        serializersModule = ContractSerializers.module
    }

    /**
     * `https://servidor` → `https://servidor/api/v1`. Exige HTTPS salvo [allowCleartext], que solo se activa en debug
     * para probar contra una API local (07-security-and-privacy.md §1).
     */
    fun baseUrl(serverUrl: String, allowCleartext: Boolean = false): String {
        val server = serverUrl.trim().trimEnd('/')
        require(server.startsWith("https://") || (allowCleartext && server.startsWith("http://"))) {
            "Velora exige HTTPS"
        }
        return "$server/api/v1"
    }

    fun create(
        serverUrl: String = DEFAULT_SERVER,
        allowCleartext: Boolean = false,
        engine: HttpClientEngine = OkHttp.create(),
    ): VeloraClient = VeloraClient(
        baseUrl = baseUrl(serverUrl, allowCleartext),
        httpClientEngine = engine,
        httpClientConfig = {
            it.install(ContentNegotiation) { json(json) }
            it.install(RequestIdPlugin)
            it.install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 20_000
            }
        },
    )
}

/**
 * Correlation ID de la solicitud en curso: viaja a Velora como `X-Request-Id` y Velora lo usa en sus logs y en el
 * `instance` de sus errores, así un evento del registro local se encuentra en el servidor.
 */
class RequestId(val value: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RequestId> {
        const val HEADER = "X-Request-Id"
    }
}

private val RequestIdPlugin = createClientPlugin("RequestId") {
    onRequest { request, _ ->
        currentCoroutineContext()[RequestId]?.let { request.headers[RequestId.HEADER] = it.value }
    }
}
