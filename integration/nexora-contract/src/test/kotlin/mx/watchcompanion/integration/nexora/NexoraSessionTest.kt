package mx.watchcompanion.integration.nexora

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mx.watchcompanion.core.session.LoginResult
import mx.watchcompanion.core.session.SessionExpiredException
import mx.watchcompanion.core.session.SessionState
import mx.watchcompanion.core.session.SessionStore
import mx.watchcompanion.core.session.SignOutReason
import mx.watchcompanion.core.session.StoredSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** WC-37: sesión propia de app-phone en Nexora, con rotación del refresh token sin carreras y revocación. */
class NexoraSessionTest {
    private class MemoryStore(var saved: StoredSession? = null) : SessionStore {
        override suspend fun read() = saved
        override suspend fun write(session: StoredSession) { saved = session }
        override suspend fun clear() { saved = null }
    }

    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val server = "https://nexora.example"
    private var clock = 1_000_000L

    /** Nexora simulada: registra ruta y cuerpo, y responde según [handler]. */
    private class FakeNexora(val handler: suspend MockRequestHandleScope.(HttpRequestData, String) -> HttpResponseData) {
        val calls: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        val engine = MockEngine { request ->
            val body = request.body.toByteArray().decodeToString()
            calls += "${request.url.encodedPath.removePrefix("/api/v1")} $body".trim()
            handler(request, body)
        }
    }

    private fun tokens(access: String, refresh: String, expiresIn: Int = 900) =
        """{"accessToken":"$access","refreshToken":"$refresh","tokenType":"Bearer","expiresInSeconds":$expiresIn}"""

    private val me = """{"id":"6f1c3c62-5a0e-4a4e-9a51-0b2c8f8d2f10","email":"p@example.com"}"""

    private fun TestScope.session(store: SessionStore, nexora: FakeNexora) =
        NexoraSession(store, backgroundScope, apiFor = { NexoraClient.create(it, engine = nexora.engine) }, now = { clock })

    @Test
    fun loginGuardaSoloElRefreshTokenYQuedaDentro() = runTest {
        val store = MemoryStore()
        val session = session(store, FakeNexora { _, _ -> respond(tokens("a1", "r1"), HttpStatusCode.OK, json) })

        assertEquals(LoginResult.Success, session.login(server, "p@example.com", "contraseña"))

        assertEquals(StoredSession(server, "p@example.com", "r1"), store.saved)
        assertEquals(SessionState.SignedIn(server, "p@example.com"), session.state.value)
    }

    @Test
    fun unLoginSinTokensNoEsUnLogin() = runTest {
        // El contrato no marca campos obligatorios: una respuesta 200 sin refresh token no deja una sesión a medias.
        val store = MemoryStore()
        val session = session(store, FakeNexora { _, _ -> respond("""{"accessToken":"a1"}""", HttpStatusCode.OK, json) })

        assertEquals(LoginResult.Unavailable, session.login(server, "p@example.com", "x"))
        assertNull(store.saved)
    }

    @Test
    fun erroresDeLogin() = runTest {
        var status = HttpStatusCode.Unauthorized
        val session = session(MemoryStore(), FakeNexora { _, _ -> respond("""{"error":"x"}""", status, json) })
        assertEquals(LoginResult.InvalidCredentials, session.login(server, "p@example.com", "x"))
        status = HttpStatusCode.BadRequest
        assertEquals(LoginResult.InvalidInput, session.login(server, "p@example.com", "x"))
        status = HttpStatusCode.InternalServerError
        assertEquals(LoginResult.Unavailable, session.login(server, "p@example.com", "x"))
        assertEquals(LoginResult.InsecureServer, session.login("http://nexora.example", "p@example.com", "x"))
    }

    @Test
    fun dosLlamadasConElTokenVencidoRenuevanUnaSolaVez() = runTest {
        val store = MemoryStore(StoredSession(server, "p@example.com", "r1"))
        val nexora = FakeNexora { request, body ->
            when (request.url.encodedPath) {
                "/api/v1/auth/refresh" -> {
                    // Nexora revoca el refresh token usado: reutilizarlo es un 401.
                    if ("\"r1\"" !in body) return@FakeNexora respond("""{"error":"revocado"}""", HttpStatusCode.Unauthorized, json)
                    delay(50)
                    respond(tokens("a2", "r2"), HttpStatusCode.OK, json)
                }
                "/api/v1/users/me" -> respond(me, HttpStatusCode.OK, json)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val session = session(store, nexora)
        session.restore()

        val results = withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                (1..3).map { async { session.call("nexora.userMe") { userMe() }.status } }.awaitAll()
            }
        }

        assertEquals(listOf(200, 200, 200), results)
        assertEquals(1, nexora.calls.count { it.startsWith("/auth/refresh") })
        assertEquals("r2", store.saved?.refreshToken)
    }

    @Test
    fun siNexoraRechazaElRefreshLaSesionTermina() = runTest {
        val store = MemoryStore(StoredSession(server, "p@example.com", "r1"))
        val session = session(store, FakeNexora { _, _ -> respond("""{"error":"x"}""", HttpStatusCode.Unauthorized, json) })
        session.restore()

        val e = runCatching { session.call("nexora.userMe") { userMe() } }.exceptionOrNull()

        assertTrue(e is SessionExpiredException)
        assertEquals(SessionState.SignedOut(SignOutReason.EXPIRED), session.state.value)
        assertNull(store.saved)
    }

    @Test
    fun cerrarSesionLaRevocaEnNexoraYLaBorraDelTelefono() = runTest {
        val store = MemoryStore()
        val nexora = FakeNexora { request, _ ->
            when (request.url.encodedPath) {
                "/api/v1/auth/login" -> respond(tokens("a1", "r1"), HttpStatusCode.OK, json)
                "/api/v1/auth/logout" -> respond("", HttpStatusCode.NoContent)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val session = session(store, nexora)
        session.login(server, "p@example.com", "contraseña")

        session.logout()

        assertEquals(SessionState.SignedOut(), session.state.value)
        assertNull(store.saved)
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (nexora.calls.none { it.startsWith("/auth/logout") }) delay(10) }
        }
        assertTrue(nexora.calls.any { it.startsWith("/auth/logout") && "\"refreshToken\":\"r1\"" in it })
    }

    @Test
    fun restaurarNoTocaLaRed() = runTest {
        val nexora = FakeNexora { _, _ -> error("sin red") }
        val session = session(MemoryStore(StoredSession(server, "p@example.com", "r1")), nexora)
        session.restore()
        assertEquals(SessionState.SignedIn(server, "p@example.com"), session.state.value)
        assertTrue(nexora.calls.isEmpty())
    }
}
