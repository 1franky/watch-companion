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
}
