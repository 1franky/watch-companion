package mx.watchcompanion.integration.velora

import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.forms.formData
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import java.util.UUID
import mx.watchcompanion.integration.velora.api.apis.DefaultApi
import mx.watchcompanion.integration.velora.api.infrastructure.HttpResponse
import mx.watchcompanion.integration.velora.api.infrastructure.RequestConfig
import mx.watchcompanion.integration.velora.api.infrastructure.RequestMethod
import mx.watchcompanion.integration.velora.api.infrastructure.wrap
import mx.watchcompanion.integration.velora.api.models.SecondaryRecording

/**
 * Cliente de Velora del companion: el generado ([DefaultApi]) más las operaciones que el generador no sabe armar.
 * Cada una sigue el contrato fijado y lleva su `operationId` como nombre, para que `ModeAContractTest` la vea.
 */
open class VeloraClient(
    baseUrl: String,
    httpClientEngine: HttpClientEngine?,
    httpClientConfig: ((HttpClientConfig<*>) -> Unit)?,
) : DefaultApi(baseUrl, httpClientEngine, httpClientConfig) {

    /**
     * `POST /secondary-recordings` (ADR-032 de Velora). Responde **200** con [SecondaryRecording] si ya existía igual,
     * o **202** con `ImportAccepted` (léelo con `typedBody`); **409** con `code` en el `Problem`.
     */
    open suspend fun uploadSecondaryRecording(
        file: ByteArray,
        fileName: String,
        source: String,
        clientRecordingId: UUID,
        completeness: String,
    ): HttpResponse<SecondaryRecording> {
        val body = formData {
            append(
                "file",
                file,
                Headers.build {
                    append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                },
            )
            append("source", source)
            append("clientRecordingId", clientRecordingId.toString())
            append("completeness", completeness)
        }
        val config = RequestConfig<Any?>(
            RequestMethod.POST,
            "/secondary-recordings",
            requiresAuthentication = true,
        )
        return multipartFormRequest(config, body, listOf("bearerAuth")).wrap()
    }
}
