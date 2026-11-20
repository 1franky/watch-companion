package mx.watchcompanion.integration.velora

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import mx.watchcompanion.integration.velora.api.models.ImportJob
import mx.watchcompanion.integration.velora.api.models.LoginRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Respuestas con la forma del contrato fijado en contract/velora (nulos explícitos y campos que el cliente no conoce). */
class VeloraApiTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun construyeLaUrlBaseYExigeHttps() {
        assertEquals("https://velora.example/api/v1", VeloraApi.baseUrl(" https://velora.example/ "))
        assertEquals("http://10.0.2.2:8090/api/v1", VeloraApi.baseUrl("http://10.0.2.2:8090", allowCleartext = true))
        val error = runCatching { VeloraApi.baseUrl("http://velora.example") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun loginEnviaCredencialesSinBearerYLeeLosTokens() = runTest {
        var cuerpo = ""
        var authorization: String? = "sin leer"
        var ruta = ""
        val engine = MockEngine { request ->
            cuerpo = request.body.toByteArray().decodeToString()
            authorization = request.headers[HttpHeaders.Authorization]
            ruta = request.url.encodedPath
            respond(
                """{"tokenType":"Bearer","accessToken":"a","accessTokenExpiresIn":900,"refreshToken":"r","refreshTokenExpiresIn":2592000}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }
        val tokens = VeloraApi.create("https://servidor", engine = engine)
            .login(LoginRequest(email = "propietario@example.com", password = "contraseña-de-prueba"))
            .body()

        assertEquals("/api/v1/auth/login", ruta)
        assertNull(authorization)
        assertTrue(cuerpo, cuerpo.contains("\"email\":\"propietario@example.com\""))
        assertEquals(900, tokens.accessTokenExpiresIn)
        assertEquals("r", tokens.refreshToken)
    }

    @Test
    fun leeElPerfilConBearerFcMaximaNulaYCamposNuevos() = runTest {
        var authorization: String? = null
        val engine = MockEngine { request ->
            authorization = request.headers[HttpHeaders.Authorization]
            respond(
                """
                {"id":"6f1c3c62-5a0e-4a4e-9a51-0b2c8f8d2f10","email":"propietario@example.com",
                 "preferences":{"locale":"es-MX","timeZone":"America/Mexico_City","distanceUnit":"km",
                 "elevationUnit":"m","trackPrivacyMeters":300,"maxHeartRateBpm":null,"nuevoCampo":1}}
                """.trimIndent(),
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }
        val api = VeloraApi.create("https://servidor", engine = engine)
        api.setBearerToken("token-de-prueba")

        val me = api.getMe().body()

        assertEquals("Bearer token-de-prueba", authorization)
        assertEquals(300, me.preferences.trackPrivacyMeters)
        assertNull(me.preferences.maxHeartRateBpm)
    }

    @Test
    fun leeLaPaginaDeActividadesConFechasYNulos() = runTest {
        val engine = MockEngine {
            respond(
                """
                {"items":[{"id":"a0000000-0000-4000-8000-000000000001","title":"Rodada","startedAt":"2026-11-08T13:00:00Z",
                 "localStartDate":"2026-11-08","subType":"GRAVEL","distanceMeters":42195.5,"movingTimeSeconds":5400,
                 "elapsedTimeSeconds":6000,"elevationGainMeters":null,"averageSpeedMps":7.8,"bikeId":null}],
                 "nextCursor":null}
                """.trimIndent(),
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }
        val pagina = VeloraApi.create("https://servidor", engine = engine)
            .listActivities(null, null, null, null, null, null, null, 1)
            .body()
        val rodada = pagina.items.single()
        assertEquals("2026-11-08", rodada.localStartDate.toString())
        assertEquals(2026, rodada.startedAt.year)
        assertNull(rodada.elevationGainMeters)
        assertNull(rodada.bikeId)
    }

    @Test
    /**
     * El generador 7.14 ignora `enumUnknownDefaultCase` con kotlinx.serialization: un estado nuevo de Velora no se puede
     * leer. Debe fallar (y mostrarse como error), nunca convertirse en un estado conocido.
     */
    fun leeUnTrabajoDeImportacionRechazadoYFallaConEstadosDesconocidos() = runTest {
        var respuesta = """
            {"jobId":"b0000000-0000-4000-8000-000000000002","kind":"IMPORT","status":"REJECTED","warnings":[],
             "possibleDuplicateActivityIds":[],"activityId":null,"errorCode":"NO_SAMPLES","errorDetail":"El archivo no tiene muestras"}
        """.trimIndent()
        val engine = MockEngine { respond(respuesta, HttpStatusCode.OK, jsonHeaders) }
        val api = VeloraApi.create("https://servidor", engine = engine)

        val rechazado = api.getImportJob(java.util.UUID.fromString("b0000000-0000-4000-8000-000000000002")).body()
        assertEquals(ImportJob.Status.REJECTED, rechazado.status)
        assertEquals("NO_SAMPLES", rechazado.errorCode)

        respuesta = respuesta.replace("REJECTED", "ESTADO_FUTURO")
        val error = runCatching {
            api.getImportJob(java.util.UUID.fromString("b0000000-0000-4000-8000-000000000002")).body()
        }.exceptionOrNull()
        assertTrue("Un estado desconocido no debe leerse como uno conocido", error != null)
    }

    @Test
    fun unErrorDelServidorNoSePresentaComoExito() = runTest {
        val engine = MockEngine {
            respond("""{"status":401,"title":"No autenticado"}""", HttpStatusCode.Unauthorized,
                headersOf(HttpHeaders.ContentType, "application/problem+json"))
        }
        val respuesta = VeloraApi.create("https://servidor", engine = engine).getMe()
        assertFalse(respuesta.success)
        assertEquals(401, respuesta.status)
    }
}
