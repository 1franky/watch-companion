package mx.watchcompanion.watch.recording

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WC-54: el reloj ofrece sus grabaciones cerradas y las borra solo cuando el teléfono las confirma. */
class RecordingOutboxTest {
    private val root: File = Files.createTempDirectory("recordings").toFile()
    private val recorder = TrackRecorder(root)
    private val offered = mutableListOf<Triple<String, String, String>>()
    private val outbox = RecordingOutbox(recorder) { id, completeness, sha, gpx ->
        assertTrue(gpx.isFile)
        offered += Triple(id, completeness, sha)
    }
    private val t0 = 1_764_000_000_000L

    private fun finished(): RecordingSummary {
        recorder.begin(t0)
        recorder.append(listOf(TrackPoint(t0 + 1_000, 19.4, -99.1), TrackPoint(t0 + 2_000, 19.401, -99.1)), emptyList())
        return recorder.finish(t0 + 3_000)!!
    }

    @Test
    fun ofreceLasCerradasConSuShaYNoLaQueSigueGrabando() = runTest {
        val done = finished()
        recorder.begin(t0 + 10_000) // en curso

        outbox.offerPending()

        assertEquals(listOf(Triple(done.id, "COMPLETE", done.gpxSha256!!)), offered)
        assertEquals(1, outbox.pending.value)
    }

    @Test
    fun lasParcialesViajanComoParciales() = runTest {
        recorder.begin(t0)
        recorder.append(listOf(TrackPoint(t0 + 1_000, 19.4, -99.1)), emptyList())
        TrackRecorder(root).recover()

        outbox.offerPending()

        assertEquals("PARTIAL", offered.single().second)
    }

    @Test
    fun alConfirmarlaElTelefonoSeBorraDelReloj() = runTest {
        val done = finished()
        outbox.offerPending()

        outbox.accepted(done.id)

        assertEquals(0, outbox.pending.value)
        assertTrue(recorder.list().isEmpty())
    }

    @Test
    fun unAcuseNoBorraLaGrabacionEnCurso() = runTest {
        val id = recorder.begin(t0)
        outbox.accepted(id)
        assertEquals(id, recorder.activeId)
        assertTrue(File(root, id).isDirectory)
    }

    @Test
    fun unaGrabacionSinPuntosNoSeOfreceYSeBorra() = runTest {
        recorder.begin(t0)
        recorder.finish(t0 + 60_000)

        outbox.offerPending()

        assertTrue(offered.isEmpty())
        assertTrue(recorder.list().isEmpty())
    }

    @Test
    fun unFalloAlOfrecerNoPierdeLaGrabacion() = runTest {
        finished()
        val failing = RecordingOutbox(recorder) { _, _, _, _ -> error("sin Play services") }

        failing.offerPending()

        assertEquals(1, failing.pending.value)
        assertEquals(1, recorder.list().size)
    }
}
