package mx.watchcompanion.watch.recording

import java.io.File
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** WC-53: diario local de la grabación del modo B, recuperación y GPX para Velora. */
class TrackRecorderTest {
    private val root: File = Files.createTempDirectory("recordings").toFile()
    private var ids = 0
    private fun recorder() = TrackRecorder(root) { "rec-${++ids}" }

    private val t0 = 1_764_000_000_000L

    @Test
    fun guardaLosPuntosYAlTerminarGeneraUnGpxQueVeloraPuedeLeer() {
        val recorder = recorder()
        val id = recorder.begin(t0)
        recorder.append(
            locations = listOf(
                TrackPoint(t0 + 1_000, 19.4326077, -99.1332080, altitudeMeters = 2240.4),
                TrackPoint(t0 + 15_000, 19.4330000, -99.1332080, altitudeMeters = null),
            ),
            heartRates = listOf(HeartRateSample(t0 + 1_200, 128), HeartRateSample(t0 + 9_000, 150)),
        )

        val summary = recorder.finish(t0 + 16_000)!!

        assertEquals("rec-1", id)
        assertEquals(RecordingStatus.FINISHED, summary.status)
        assertEquals(2, summary.points)
        assertTrue(summary.distanceMeters in 40.0..50.0)
        assertEquals(64, summary.gpxSha256!!.length)
        assertNull(recorder.activeId)

        // Se lee con un parser XML con espacios de nombres, como el GpxParser de Velora (por nombre local).
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(summary.gpx)
        val points = doc.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "trkpt")
        assertEquals(2, points.length)
        val first = points.item(0) as Element
        assertEquals("19.4326077", first.getAttribute("lat"))
        assertEquals("-99.1332080", first.getAttribute("lon"))
        assertEquals("2240.4", first.getElementsByTagNameNS("*", "ele").item(0).textContent)
        assertEquals("2025-11-24T16:00:01Z", first.getElementsByTagNameNS("*", "time").item(0).textContent)
        assertEquals("128", first.getElementsByTagNameNS("*", "hr").item(0).textContent)
        // Lo que falta no se inventa: sin altitud no hay <ele>, y la FC más cercana (6 s antes) no se asocia (ventana de 5 s).
        val second = points.item(1) as Element
        assertEquals(0, second.getElementsByTagNameNS("*", "ele").length)
        assertEquals(0, second.getElementsByTagNameNS("*", "hr").length)
    }

    @Test
    fun elGpxNoLlevaDatosDelDispositivoNiDelUsuario() {
        val recorder = recorder()
        recorder.begin(t0)
        recorder.append(listOf(TrackPoint(t0 + 1_000, 19.4, -99.1)), emptyList())
        val gpx = recorder.finish(t0 + 2_000)!!.gpx!!.readText()

        assertFalse(gpx.contains("<name>"))
        assertFalse(gpx.contains("<author>"))
        assertTrue(gpx.contains("creator=\"Watch Companion\""))
    }

    @Test
    fun descartaCoordenadasInvalidasYPuntosRepetidos() {
        val recorder = recorder()
        recorder.begin(t0)
        val p1 = TrackPoint(t0 + 1_000, 19.4, -99.1)
        recorder.append(
            listOf(p1, TrackPoint(t0 + 2_000, Double.NaN, -99.1), TrackPoint(t0 + 3_000, 0.0, 0.0), TrackPoint(t0 + 4_000, 95.0, -99.1)),
            emptyList(),
        )
        // Health Services puede repetir un lote: no duplica el track.
        recorder.append(listOf(p1), emptyList())

        assertEquals(1, recorder.progress()!!.points)
    }

    @Test
    fun unaLineaCortadaPorUnApagonSeIgnoraYElRestoSeRecupera() {
        val recorder = recorder()
        recorder.begin(t0)
        recorder.append(listOf(TrackPoint(t0 + 1_000, 19.4, -99.1), TrackPoint(t0 + 2_000, 19.401, -99.1)), emptyList())
        // El reloj se apagó a media escritura del siguiente lote.
        File(root, "rec-1/points.csv").appendText("${t0 + 3_000},19.40")

        val recovered = TrackRecorder(root).recover().single()

        assertEquals(RecordingStatus.PARTIAL, recovered.status)
        assertEquals(2, recovered.points)
        assertEquals(t0 + 2_000, recovered.endedAtEpochMs)
        assertNotNull(recovered.gpx)
    }

    @Test
    fun resumeRetomaLaMismaGrabacionConSuIdYSusPuntos() {
        recorder().apply {
            begin(t0)
            append(listOf(TrackPoint(t0 + 1_000, 19.4, -99.1)), emptyList())
        }

        val again = recorder()
        assertEquals("rec-1", again.resume())
        again.append(listOf(TrackPoint(t0 + 1_000, 19.4, -99.1), TrackPoint(t0 + 2_000, 19.401, -99.1)), emptyList())

        assertEquals(2, again.progress()!!.points)
        assertEquals(1, root.listFiles()!!.size)
    }

    @Test
    fun recoverNoTocaGrabacionesTerminadas() {
        val recorder = recorder()
        recorder.begin(t0)
        recorder.append(listOf(TrackPoint(t0 + 1_000, 19.4, -99.1)), emptyList())
        recorder.finish(t0 + 2_000)

        assertTrue(TrackRecorder(root).recover().isEmpty())
        assertEquals(RecordingStatus.FINISHED, TrackRecorder(root).list().single().status)
    }

    @Test
    fun unaGrabacionSinPuntosNoGeneraGpx() {
        val recorder = recorder()
        recorder.begin(t0)
        val summary = recorder.finish(t0 + 60_000)!!
        assertEquals(0, summary.points)
        assertNull(summary.gpx)
    }

    @Test
    fun laFcSeAsociaALaLecturaMasCercanaDentroDeLaVentana() {
        val samples = listOf(HeartRateSample(1_000, 100), HeartRateSample(4_000, 110), HeartRateSample(20_000, 150))
        assertEquals(110, TrackRecorder.nearestHeartRate(samples, 3_000))
        assertEquals(100, TrackRecorder.nearestHeartRate(samples, 0))
        assertNull(TrackRecorder.nearestHeartRate(samples, 12_000))
    }
}
