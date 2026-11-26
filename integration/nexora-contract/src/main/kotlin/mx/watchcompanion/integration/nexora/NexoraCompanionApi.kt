package mx.watchcompanion.integration.nexora

import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import java.util.UUID
import mx.watchcompanion.integration.nexora.api.apis.NexoraApi
import mx.watchcompanion.integration.nexora.api.infrastructure.HttpResponse
import mx.watchcompanion.integration.nexora.api.infrastructure.RequestConfig
import mx.watchcompanion.integration.nexora.api.infrastructure.RequestMethod
import mx.watchcompanion.integration.nexora.api.infrastructure.wrap
import mx.watchcompanion.integration.nexora.api.models.TransactionResponse

/**
 * Cliente de Nexora del companion: el generado ([NexoraApi]) más lo que el generador no sabe armar. Cada operación
 * escrita a mano sigue el contrato fijado y lleva su `operationId` como nombre, para que `NexoraScopeTest` la vea.
 */
open class NexoraCompanionApi(
    baseUrl: String,
    httpClientEngine: HttpClientEngine?,
    httpClientConfig: ((HttpClientConfig<*>) -> Unit)?,
) : NexoraApi(baseUrl, httpClientEngine, httpClientConfig) {

    /**
     * `POST /api/v1/transactions` con la cabecera `Idempotency-Key` (WC-40, 03 §2). La cabecera no está en el contrato:
     * la lee el `IdempotencyFilter` de nexora-api, que compara **los bytes del cuerpo**. Por eso el cuerpo llega ya
     * serializado ([body], un `CreateTransactionRequest` en JSON) y quien reintenta manda exactamente el mismo texto.
     *
     * Responde **201** con [TransactionResponse] (o la respuesta guardada si la clave ya se aplicó) y **409** si la
     * clave se usó con otro cuerpo.
     */
    open suspend fun transactionCreate(idempotencyKey: UUID, body: String): HttpResponse<TransactionResponse> {
        val config = RequestConfig<Any?>(
            RequestMethod.POST,
            "/api/v1/transactions",
            headers = mutableMapOf(IDEMPOTENCY_KEY to idempotencyKey.toString()),
            requiresAuthentication = true,
        )
        return request(config, TextContent(body, ContentType.Application.Json), listOf("bearerAuth")).wrap()
    }

    companion object {
        const val IDEMPOTENCY_KEY = "Idempotency-Key"
    }
}
