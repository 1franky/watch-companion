package mx.watchcompanion.integration.nexora

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import mx.watchcompanion.integration.nexora.api.models.LoginRequest
import mx.watchcompanion.integration.nexora.api.models.RefreshRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** WC-36: el cliente generado habla el contrato fijado de Nexora (rutas, cuerpos y respuestas). */
class NexoraClientTest {
    private val requests = mutableListOf<String>()
    private val idempotencyKeys = mutableListOf<String?>()
    private val contentTypes = mutableListOf<String?>()

    private val engine = MockEngine { request ->
        val body = request.body.toByteArray().decodeToString()
        requests += "${request.method.value} ${request.url.encodedPath} $body"
        when (request.url.encodedPath) {
            "/api/v1/auth/login" -> respond(
                """{"accessToken":"a","refreshToken":"r","tokenType":"Bearer","expiresInSeconds":900}""",
                HttpStatusCode.OK,
                // Nexora no siempre declara application/json.
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
            "/api/v1/auth/logout" -> respond("", HttpStatusCode.NoContent)
            "/api/v1/transactions" -> {
                idempotencyKeys += request.headers[NexoraCompanionApi.IDEMPOTENCY_KEY]
                contentTypes += request.body.contentType?.toString()
                respond(
                    """{"id":"0b6f8f1e-4f0a-4b47-9b8c-3f2a1c9d7e11","type":"EXPENSE","amount":250.0,"status":"POSTED"}""",
                    HttpStatusCode.Created,
                    headersOf(HttpHeaders.ContentType, "*/*"),
                )
            }
            "/api/v1/users/me" -> respond(
                """{"id":"6f1c3c62-5a0e-4a4e-9a51-0b2c8f8d2f10","email":"p@example.com","displayName":"P",
                   "createdAt":"2026-09-01T10:00:00Z","campoNuevo":1}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "*/*"),
            )
            else -> respond("", HttpStatusCode.NotFound)
        }
    }

    @Test
    fun laBaseEsElServidorYExigeHttps() {
        assertEquals("https://nexora.example", NexoraClient.baseUrl("https://nexora.example/"))
        assertEquals("http://10.0.2.2:8080", NexoraClient.baseUrl("http://10.0.2.2:8080", allowCleartext = true))
        assertTrue(runCatching { NexoraClient.baseUrl("http://nexora.example") }.isFailure)
    }

    @Test
    fun loginYPerfilSiguenElContrato() = runTest {
        val api = NexoraClient.create("https://nexora.example", engine = engine)

        val tokens = api.authLogin(LoginRequest(email = "p@example.com", password = "secreto")).body()
        assertEquals("a", tokens.accessToken)
        assertEquals(900L, tokens.expiresInSeconds)

        api.setBearerToken(tokens.accessToken!!)
        val me = api.userMe()
        assertTrue(me.success)
        // Respuesta declarada como */* y con un campo que el contrato fijado no conoce: se lee igual.
        assertEquals("p@example.com", me.body().email)

        assertEquals(204, api.authLogout(RefreshRequest(refreshToken = "r")).status)
        assertTrue(requests.first().startsWith("POST /api/v1/auth/login {"))
        assertTrue(requests.last().contains("\"refreshToken\":\"r\""))
        assertFalse("La contraseña no viaja fuera del login", requests.drop(1).any { "secreto" in it })
    }

    @Test
    fun elMovimientoLlevaSuIdempotencyKeyYElCuerpoExacto() = runTest {
        val api = NexoraClient.create("https://nexora.example", engine = engine)
        api.setBearerToken("a")
        val key = java.util.UUID.fromString("3d6f0a7e-1b2c-4d5e-8f90-a1b2c3d4e5f6")
        // Texto exacto: el IdempotencyFilter compara los bytes, así que el cliente no debe volver a serializarlo.
        val body = """{"type":"EXPENSE","accountId":"5b1d6a3e-0c1f-4a2b-9d3e-7f8a9b0c1d2e","amount":250.00,"date":"2026-11-25"}"""

        val response = api.transactionCreate(key, body)

        assertEquals(201, response.status)
        assertEquals("EXPENSE", response.body().type?.value)
        assertEquals(listOf(key.toString()), idempotencyKeys)
        assertEquals("POST /api/v1/transactions $body", requests.single())
        assertTrue(contentTypes.single()!!.startsWith("application/json"))
    }
}
