package mx.watchcompanion.integration.velora

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mx.watchcompanion.core.session.SessionStore
import mx.watchcompanion.core.session.StoredSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VeloraSessionTest {
    private class MemoryStore(var saved: StoredSession? = null) : SessionStore {
        override suspend fun read() = saved
        override suspend fun write(session: StoredSession) { saved = session }
        override suspend fun clear() { saved = null }
    }

    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val server = "https://velora.example"
    private var clock = 1_000_000L

    /** Velora simulada: cuenta llamadas por ruta y responde según [handler]. */
    private class FakeVelora(val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) {
        val calls = mutableListOf<String>()
        val engine = MockEngine { request ->
            calls += request.url.encodedPath.removePrefix("/api/v1")
            handler(request)
        }
    }

    private fun tokens(access: String, refresh: String, expiresIn: Int = 900) =
        """{"tokenType":"Bearer","accessToken":"$access","accessTokenExpiresIn":$expiresIn,"refreshToken":"$refresh","refreshTokenExpiresIn":2592000}"""

    private val me = """{"id":"6f1c3c62-5a0e-4a4e-9a51-0b2c8f8d2f10","email":"p@example.com","preferences":{"locale":"es-MX",
        "timeZone":"America/Mexico_City","distanceUnit":"km","elevationUnit":"m","trackPrivacyMeters":300,"maxHeartRateBpm":185}}"""

    private fun TestScope.session(store: SessionStore, velora: FakeVelora) =
        VeloraSession(store, backgroundScope, apiFor = { VeloraApi.create(it, engine = velora.engine) }, now = { clock })

    @Test
    fun loginGuardaSoloElRefreshTokenCifradoYQuedaDentro() = runTest {
        val store = MemoryStore()
        val velora = FakeVelora { respond(tokens("a1", "r1"), HttpStatusCode.OK, json) }
        val session = session(store, velora)

        assertEquals(LoginResult.Success, session.login(server, "p@example.com", "contraseña"))

        assertEquals(StoredSession(server, "p@example.com", "r1"), store.saved)
        assertEquals(SessionState.SignedIn(server, "p@example.com"), session.state.value)
    }

    @Test
    fun traduceLosErroresDelLogin() = runTest {
        var status = HttpStatusCode.Unauthorized
        val velora = FakeVelora {
            respond("""{"status":${status.value},"title":"x"}""", status, headersOf(HttpHeaders.RetryAfter, "60"))
        }
        val session = session(MemoryStore(), velora)
        assertEquals(LoginResult.InvalidCredentials, session.login(server, "p@example.com", "x"))
        status = HttpStatusCode.TooManyRequests
        assertEquals(LoginResult.TooManyAttempts(60), session.login(server, "p@example.com", "x"))
        status = HttpStatusCode.BadRequest
        assertEquals(LoginResult.InvalidInput, session.login(server, "correo", "x"))
        status = HttpStatusCode.InternalServerError
        assertEquals(LoginResult.Unavailable, session.login(server, "p@example.com", "x"))
        assertEquals(LoginResult.InsecureServer, session.login("http://velora.example", "p@example.com", "x"))
        assertTrue(session.state.value !is SessionState.SignedIn)
    }

    @Test
    fun restaurarNoTocaLaRed() = runTest {
        val velora = FakeVelora { error("no debe llamar a la red") }
        val session = session(MemoryStore(StoredSession(server, "p@example.com", "r1")), velora)
        session.restore()
        assertEquals(SessionState.SignedIn(server, "p@example.com"), session.state.value)
        assertTrue(velora.calls.isEmpty())
    }

    @Test
    fun dosLlamadasConElTokenVencidoRenuevanUnaSolaVez() = runTest {
        val store = MemoryStore(StoredSession(server, "p@example.com", "r1"))
        val velora = FakeVelora { request ->
            when (request.url.encodedPath) {
                "/api/v1/auth/refresh" -> {
                    // Velora rota el refresh token: reutilizar r1 revocaría la sesión.
                    assertTrue(request.body.toByteArray().decodeToString().contains("\"r1\""))
                    respond(tokens("a2", "r2"), HttpStatusCode.OK, json)
                }
                else -> respond(me, HttpStatusCode.OK, json)
            }
        }
        val session = session(store, velora)
        session.restore()

        listOf(async { session.call { getMe() } }, async { session.call { getMe() } }).awaitAll()

        assertEquals(1, velora.calls.count { it == "/auth/refresh" })
        assertEquals(2, velora.calls.count { it == "/me" })
        assertEquals("r2", store.saved?.refreshToken)
    }

    @Test
    fun unAccessTokenPorVencerSeRenuevaAntes() = runTest {
        val velora = FakeVelora { request ->
            if (request.url.encodedPath.endsWith("/auth/login")) respond(tokens("a1", "r1", expiresIn = 900), HttpStatusCode.OK, json)
            else if (request.url.encodedPath.endsWith("/auth/refresh")) respond(tokens("a2", "r2"), HttpStatusCode.OK, json)
            else respond(me, HttpStatusCode.OK, json)
        }
        val session = session(MemoryStore(), velora)
        session.login(server, "p@example.com", "x")
        session.call { getMe() }
        assertEquals(0, velora.calls.count { it == "/auth/refresh" })

        clock += 880_000 // quedan 20 s: menos que el margen de 30 s
        session.call { getMe() }
        assertEquals(1, velora.calls.count { it == "/auth/refresh" })
    }

    @Test
    fun siVeloraRechazaElRefreshLaSesionTermina() = runTest {
        val store = MemoryStore(StoredSession(server, "p@example.com", "r-revocado"))
        val velora = FakeVelora { respond("""{"status":401,"title":"x"}""", HttpStatusCode.Unauthorized, json) }
        val session = session(store, velora)
        session.restore()

        val error = runCatching { session.call { getMe() } }.exceptionOrNull()

        assertTrue(error is SessionExpiredException)
        assertEquals(SessionState.SignedOut(SignOutReason.EXPIRED), session.state.value)
        assertNull(store.saved)
    }

    @Test
    fun unErrorDelServidorAlRenovarNoCierraLaSesion() = runTest {
        val store = MemoryStore(StoredSession(server, "p@example.com", "r1"))
        val velora = FakeVelora { respond("""{"status":503,"title":"x"}""", HttpStatusCode.ServiceUnavailable, json) }
        val session = session(store, velora)
        session.restore()

        val error = runCatching { session.call { getMe() } }.exceptionOrNull()

        assertTrue(error is ServiceUnavailableException)
        assertTrue(session.state.value is SessionState.SignedIn)
        assertEquals("r1", store.saved?.refreshToken)
    }

    @Test
    fun cerrarSesionBorraDeInmediatoYAvisaAVelora() = runTest {
        val store = MemoryStore()
        val avisoRecibido = CompletableDeferred<String>()
        val velora = FakeVelora { request ->
            if (request.url.encodedPath.endsWith("/auth/logout")) {
                avisoRecibido.complete(request.body.toByteArray().decodeToString())
                respond("", HttpStatusCode.NoContent)
            } else {
                respond(tokens("a1", "r1"), HttpStatusCode.OK, json)
            }
        }
        val session = session(store, velora)
        session.login(server, "p@example.com", "x")

        session.logout()

        assertNull(store.saved)
        assertEquals(SessionState.SignedOut(), session.state.value)
        // El aviso corre en el alcance de la app y el motor simulado en su propio hilo: se espera en tiempo real.
        val cuerpo = withContext(Dispatchers.Default) { withTimeout(5_000) { avisoRecibido.await() } }
        assertTrue(cuerpo.contains("\"r1\""))
    }
}
