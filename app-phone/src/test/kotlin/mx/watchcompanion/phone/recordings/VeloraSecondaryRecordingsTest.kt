package mx.watchcompanion.phone.recordings

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.headersOf
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import mx.watchcompanion.core.session.SessionStore
import mx.watchcompanion.core.session.StoredSession
import mx.watchcompanion.integration.velora.LoginResult
import mx.watchcompanion.integration.velora.VeloraApi
import mx.watchcompanion.integration.velora.VeloraSession
import org.junit.Assert.assertEquals
import org.junit.Test

/** WC-54: la subida sigue el contrato de Velora (ADR-032) y sus respuestas se traducen bien. */
class VeloraSecondaryRecordingsTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val problem = headersOf(HttpHeaders.ContentType, "application/problem+json")
    private val id = "6f1c2b0e-8d0a-4c1e-9b0e-3a1f2d4c5b6a"
    private val jobId = "a0000000-0000-4000-8000-000000000009"

    private var uploadStatus = HttpStatusCode.Accepted
    private var jobStatus = "QUEUED"
    private val fields = mutableMapOf<String, String>()
    private var fileBytes: ByteArray? = null

    private val engine = MockEngine { request ->
        when (val path = request.url.encodedPath.removePrefix("/api/v1")) {
            "/auth/login" -> respond(
                """{"tokenType":"Bearer","accessToken":"a","accessTokenExpiresIn":900,"refreshToken":"r","refreshTokenExpiresIn":2592000}""",
                HttpStatusCode.OK,
                json,
            )
            "/secondary-recordings" -> {
                // Se leen los campos del formulario tal como viajan.
                val raw = String(request.body.toByteArray(), Charsets.ISO_8859_1)
                for (name in listOf("source", "clientRecordingId", "completeness")) {
                    Regex("""name="?$name"?\r\n(?:[^\r\n]+\r\n)*\r\n([^\r\n]*)""").find(raw)?.let { fields[name] = it.groupValues[1] }
                }
                fileBytes = Regex("""filename="[^"]*"\r\n(?:[^\r\n]+\r\n)*\r\n(.*?)\r\n--""", RegexOption.DOT_MATCHES_ALL)
                    .find(raw)?.groupValues?.get(1)?.toByteArray(Charsets.ISO_8859_1)
                when (uploadStatus) {
                    HttpStatusCode.Accepted -> respond(
                        """{"jobId":"$jobId","status":"QUEUED","statusUrl":"/api/v1/imports/$jobId"}""", uploadStatus, json,
                    )
                    HttpStatusCode.Conflict -> respond(
                        """{"title":"Conflicto","status":409,"code":"RECORDING_CONFLICT"}""", uploadStatus, problem,
                    )
                    else -> respond("", uploadStatus)
                }
            }
            "/imports/$jobId" -> respond(
                """{"jobId":"$jobId","kind":"SECONDARY","status":"$jobStatus","activityId":null,"secondaryRecordingId":null,
                   "errorCode":${if (jobStatus == "REJECTED") "\"MALFORMED_FILE\"" else "null"},"errorDetail":null,
                   "warnings":[],"possibleDuplicateActivityIds":[]}""",
                HttpStatusCode.OK,
                json,
            )
            else -> error("Petición inesperada: $path")
        }
    }

    private class MemoryStore : SessionStore {
        var saved: StoredSession? = null
        override suspend fun read() = saved
        override suspend fun write(session: StoredSession) { saved = session }
        override suspend fun clear() { saved = null }
    }

    private val gpx = Files.createTempFile("track", ".gpx").toFile().apply { writeText("<gpx>ruta</gpx>") }
    private val entry = RecordingInbox.Entry(id, "PARTIAL", "sha", null, gpx)

    private suspend fun api(scope: kotlinx.coroutines.CoroutineScope): VeloraSecondaryRecordings {
        val session = VeloraSession(MemoryStore(), scope, apiFor = { VeloraApi.create(it, engine = engine) })
        assertEquals(LoginResult.Success, session.login("https://velora.example", "p@example.com", "secreto"))
        return VeloraSecondaryRecordings(session)
    }

    @Test
    fun subeElArchivoConLosCamposDelContrato() = runTest {
        val result = api(backgroundScope).upload(entry)

        assertEquals(SecondaryRecordingApi.Upload.Queued(jobId), result)
        assertEquals(mapOf("source" to "WATCH", "clientRecordingId" to id, "completeness" to "PARTIAL"), fields)
        assertEquals("<gpx>ruta</gpx>", fileBytes?.decodeToString())
    }

    @Test
    fun unConflictoTraeSuCodigo() = runTest {
        uploadStatus = HttpStatusCode.Conflict
        assertEquals(SecondaryRecordingApi.Upload.Conflict("RECORDING_CONFLICT"), api(backgroundScope).upload(entry))
    }

    @Test
    fun errorDelServidorSeReintentaYArchivoInvalidoNo() = runTest {
        val api = api(backgroundScope)
        uploadStatus = HttpStatusCode.ServiceUnavailable
        assertEquals(SecondaryRecordingApi.Upload.Later(503), api.upload(entry))
        uploadStatus = HttpStatusCode.PayloadTooLarge
        assertEquals(SecondaryRecordingApi.Upload.Refused(413), api.upload(entry))
    }

    @Test
    fun elEstadoDelTrabajoSeTraduce() = runTest {
        val api = api(backgroundScope)
        jobStatus = "PROCESSING"
        assertEquals(SecondaryRecordingApi.Job.Pending, api.job(jobId))
        jobStatus = "COMPLETED"
        assertEquals(SecondaryRecordingApi.Job.Done, api.job(jobId))
        jobStatus = "DUPLICATE"
        assertEquals(SecondaryRecordingApi.Job.Done, api.job(jobId))
        jobStatus = "REJECTED"
        assertEquals(SecondaryRecordingApi.Job.Rejected("MALFORMED_FILE"), api.job(jobId))
        jobStatus = "FAILED"
        assertEquals(SecondaryRecordingApi.Job.Lost, api.job(jobId))
    }
}
