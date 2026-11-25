package mx.watchcompanion.phone.recordings

import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** WC-54: bandeja del teléfono y subida a Velora con reintentos, sin duplicar ni perder grabaciones. */
class RecordingUploaderTest {
    private val inbox = RecordingInbox(Files.createTempDirectory("inbox").toFile())
    private val id = "6f1c2b0e-8d0a-4c1e-9b0e-3a1f2d4c5b6a"
    private val gpx = "<gpx>ruta</gpx>".toByteArray()
    private val sha = MessageDigest.getInstance("SHA-256").digest(gpx).joinToString("") { "%02x".format(it) }

    private class FakeApi : SecondaryRecordingApi {
        val uploads = ArrayDeque<SecondaryRecordingApi.Upload>()
        val jobs = ArrayDeque<SecondaryRecordingApi.Job>()
        var uploadCalls = 0
        var failWith: Exception? = null

        override suspend fun upload(entry: RecordingInbox.Entry): SecondaryRecordingApi.Upload {
            failWith?.let { throw it }
            uploadCalls++
            return uploads.removeFirst()
        }

        override suspend fun job(jobId: String) = jobs.removeFirst()
    }

    private val api = FakeApi()
    private val dropped = mutableListOf<String>()
    private val uploader = RecordingUploader(inbox, api) { dropped += it }

    private fun receive() = inbox.receive(id, "COMPLETE", sha, gpx.inputStream())

    @Test
    fun guardaSoloSiElSha256Coincide() {
        assertFalse(inbox.receive(id, "COMPLETE", "0".repeat(64), gpx.inputStream()))
        assertEquals(0, inbox.pending.value)

        assertTrue(receive())
        assertTrue("Recibir dos veces lo mismo no cambia nada", receive())
        assertEquals(1, inbox.pending.value)
        assertEquals(listOf(id), inbox.entries().map { it.id })
    }

    @Test
    fun rechazaUnIdQueNoEsUuid() {
        val e = runCatching { inbox.receive("../fuera", "COMPLETE", sha, gpx.inputStream()) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }

    @Test
    fun subeConsultaElTrabajoYBorraCuandoVeloraLaTiene() = runTest {
        receive()
        api.uploads += SecondaryRecordingApi.Upload.Queued("job-1")
        api.jobs += SecondaryRecordingApi.Job.Pending

        assertEquals(RecordingUploader.Outcome.RETRY, uploader.uploadAll())
        assertEquals("job-1", inbox.entries().single().jobId)

        // En el reintento solo consulta el trabajo: no vuelve a subir el archivo.
        api.jobs += SecondaryRecordingApi.Job.Done
        assertEquals(RecordingUploader.Outcome.DONE, uploader.uploadAll())
        assertEquals(1, api.uploadCalls)
        assertEquals(0, inbox.pending.value)
    }

    @Test
    fun siYaExistiaEnVeloraSeBorraSinMas() = runTest {
        receive()
        api.uploads += SecondaryRecordingApi.Upload.Stored
        assertEquals(RecordingUploader.Outcome.DONE, uploader.uploadAll())
        assertTrue(inbox.entries().isEmpty())
    }

    @Test
    fun sinRedOSinSesionSeConservaYSeReintenta() = runTest {
        receive()
        api.failWith = IOException("sin red")
        assertEquals(RecordingUploader.Outcome.RETRY, uploader.uploadAll())

        api.failWith = null
        api.uploads += SecondaryRecordingApi.Upload.Later(503)
        assertEquals(RecordingUploader.Outcome.RETRY, uploader.uploadAll())
        assertEquals(1, inbox.pending.value)
    }

    @Test
    fun unTrabajoFallidoSeVuelveASubir() = runTest {
        receive()
        api.uploads += SecondaryRecordingApi.Upload.Queued("job-1")
        api.jobs += SecondaryRecordingApi.Job.Lost
        assertEquals(RecordingUploader.Outcome.RETRY, uploader.uploadAll())
        assertEquals(null, inbox.entries().single().jobId)

        api.uploads += SecondaryRecordingApi.Upload.Stored
        assertEquals(RecordingUploader.Outcome.DONE, uploader.uploadAll())
        assertEquals(2, api.uploadCalls)
    }

    @Test
    fun conflictosYRechazosSeDescartanConSuCodigo() = runTest {
        receive()
        api.uploads += SecondaryRecordingApi.Upload.Conflict("ALREADY_IMPORTED_AS_ACTIVITY")
        assertEquals(RecordingUploader.Outcome.DONE, uploader.uploadAll())

        receive()
        api.uploads += SecondaryRecordingApi.Upload.Queued("job-2")
        api.jobs += SecondaryRecordingApi.Job.Rejected("MALFORMED_FILE")
        assertEquals(RecordingUploader.Outcome.DONE, uploader.uploadAll())

        assertEquals(listOf("ALREADY_IMPORTED_AS_ACTIVITY", "MALFORMED_FILE"), dropped)
        assertTrue(inbox.entries().isEmpty())
    }
}
