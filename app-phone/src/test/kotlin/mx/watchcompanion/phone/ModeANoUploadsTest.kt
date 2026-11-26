package mx.watchcompanion.phone

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import mx.watchcompanion.core.communication.ActivityStatus
import mx.watchcompanion.core.communication.HeartRateZones
import mx.watchcompanion.core.communication.HeartRateZonesStore
import mx.watchcompanion.core.communication.InMemoryNonceRegistry
import mx.watchcompanion.core.communication.LinkSession
import mx.watchcompanion.core.communication.LinkTransport
import mx.watchcompanion.core.communication.Peer
import mx.watchcompanion.core.communication.ProtocolCodec
import mx.watchcompanion.core.session.SessionStore
import mx.watchcompanion.core.session.StoredSession
import mx.watchcompanion.core.session.LoginResult
import mx.watchcompanion.integration.velora.VeloraApi
import mx.watchcompanion.integration.velora.VeloraSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WC-34, capa HTTP: corre los flujos reales de `app-phone` en el modo A (sesión, zonas, última actividad y cierre)
 * contra una Velora simulada que registra cada petición. Falla si alguno llama a un endpoint de importación o
 * escribe en Strava, o si aparece cualquier petición fuera de la lista de lecturas y sesión.
 */
class ModeANoUploadsTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    // El motor simulado responde en otro hilo.
    private val requests: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath.removePrefix("/api/v1")
        requests += "${request.method.value} $path"
        val body = when (path) {
            "/auth/login", "/auth/refresh" ->
                """{"tokenType":"Bearer","accessToken":"a","accessTokenExpiresIn":900,"refreshToken":"r","refreshTokenExpiresIn":2592000}"""
            "/auth/logout" -> return@MockEngine respond("", HttpStatusCode.NoContent)
            "/me" -> """{"id":"6f1c3c62-5a0e-4a4e-9a51-0b2c8f8d2f10","email":"p@example.com","preferences":{"locale":"es-MX",
                "timeZone":"America/Mexico_City","distanceUnit":"km","elevationUnit":"m","trackPrivacyMeters":0,"maxHeartRateBpm":185}}"""
            "/integrations/strava" -> """{"enabled":true,"connected":true,"athleteId":1,"connectedAt":"2026-11-01T10:00:00Z",
                "importedActivities":3,"backfillDone":true,"lastSyncAt":"2026-11-20T08:00:00Z","lastSyncError":null}"""
            "/activities" -> """{"items":[{"id":"a0000000-0000-4000-8000-000000000001","title":"Rodada","startedAt":"2026-11-20T07:00:00Z",
                "localStartDate":"2026-11-20","subType":"ROAD","distanceMeters":42300.0,"movingTimeSeconds":5400,"elapsedTimeSeconds":6000,
                "elevationGainMeters":310.0,"averageSpeedMps":7.8,"bikeId":null}],"nextCursor":null}"""
            else -> return@MockEngine respond("", HttpStatusCode.NotFound)
        }
        respond(body, HttpStatusCode.OK, json)
    }

    private class MemoryStore : SessionStore {
        var saved: StoredSession? = null
        override suspend fun read() = saved
        override suspend fun write(session: StoredSession) { saved = session }
        override suspend fun clear() { saved = null }
    }

    private class Reloj : LinkTransport {
        override suspend fun reachablePeers() = listOf(Peer("nodo-reloj", "Galaxy Watch Ultra", isNearby = true))
        override suspend fun send(peerId: String, bytes: ByteArray) = Unit
    }

    @Test
    fun losFlujosDelModoANoImportanNiEscribenEnStrava() = runTest {
        val velora = VeloraSession(MemoryStore(), backgroundScope, apiFor = { VeloraApi.create(it, engine = engine) })
        val link = LinkSession(Reloj(), ProtocolCodec(InMemoryNonceRegistry()), "dev", "0.1.0")
        var guardado: String? = null
        val zones = PhoneZones(HeartRateZonesStore({ guardado }, { guardado = it }), link) { veloraMaxHeartRate(velora) }
        val activity = PhoneActivity({ veloraStrava(velora) }, { veloraLatestActivity(velora) })

        assertEquals(LoginResult.Success, velora.login("https://velora.example", "p@example.com", "secreto"))
        assertEquals(VeloraMax.Value(185), zones.veloraMax())
        zones.save(HeartRateZones.fromMax(185, HeartRateZones.Source.VELORA, updatedAt = 1))
        assertEquals(ActivityStatus.State.SYNCED, activity.refresh().state)
        velora.logout()
        // El aviso de cierre a Velora sale en segundo plano: se espera a que llegue.
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while ("POST /auth/logout" !in requests) delay(10) }
        }

        // Se ejercitaron de verdad todos los flujos.
        assertTrue("Peticiones: $requests", requests.containsAll(listOf("POST /auth/login", "GET /me", "GET /integrations/strava", "GET /activities", "POST /auth/logout")))

        val snapshot = synchronized(requests) { requests.toList() }
        val prohibited = snapshot.filter { PROHIBITED.containsMatchIn(it) }
        assertTrue("El modo A llamó a importación o escribió en Strava: $prohibited", prohibited.isEmpty())
        val unexpected = snapshot.filterNot { it in ALLOWED }
        assertTrue("Peticiones fuera de la lista del modo A: $unexpected", unexpected.isEmpty())
    }

    private companion object {
        /** Lo único que el teléfono puede pedirle a Velora en el modo A: lecturas y su propia sesión. */
        val ALLOWED = setOf(
            "POST /auth/login", "POST /auth/refresh", "POST /auth/logout",
            "GET /me", "GET /integrations/strava", "GET /activities",
        )

        /** Importación de archivos, reproceso y cualquier escritura en el conector o el webhook de Strava. */
        val PROHIBITED = Regex("""/activities/imports|/reprocess|^(POST|PUT|PATCH|DELETE) /integrations/strava|/integrations/strava/webhook|^POST /activities""")
    }
}
