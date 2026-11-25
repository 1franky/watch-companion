package mx.watchcompanion.watch.live

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import mx.watchcompanion.watch.live.ExerciseSnapshot.Phase
import mx.watchcompanion.watch.recording.HeartRateSample
import mx.watchcompanion.watch.recording.RecordingProgress
import mx.watchcompanion.watch.recording.RecordingStatus
import mx.watchcompanion.watch.recording.TrackPoint
import mx.watchcompanion.watch.recording.TrackRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveMetricsSessionTest {
    private class FakeBackend(
        var supports: Boolean = true,
        var supportsGps: Boolean = true,
        var status: TrackedStatus = TrackedStatus.NONE,
        var failStart: Exception? = null,
    ) : ExerciseBackend {
        var registered: ExerciseListener? = null
        val calls = mutableListOf<String>()
        override suspend fun supportsHeartRate() = supports
        override suspend fun supportsLocation() = supportsGps
        override suspend fun trackedStatus() = status
        override fun setListener(listener: ExerciseListener) {
            registered = listener
        }
        override suspend fun start(withLocation: Boolean) {
            failStart?.let { throw it }
            calls += if (withLocation) "start+gps" else "start"
        }
        override suspend fun pause() { calls += "pause" }
        override suspend fun resume() { calls += "resume" }
        override suspend fun end() { calls += "end" }

        fun emit(
            phase: Phase,
            hr: Double? = null,
            duration: ActiveDuration? = null,
            reason: EndReason? = null,
            locations: List<TrackPoint> = emptyList(),
            heartRates: List<HeartRateSample> = emptyList(),
        ) = registered!!.onSnapshot(ExerciseSnapshot(phase, hr, duration, reason, locations, heartRates))
    }

    private class FakeKeeper : ForegroundKeeper {
        var held = false
        var withLocation = false
        override fun acquire(withLocation: Boolean) {
            held = true
            this.withLocation = withLocation
        }
        override fun release() { held = false }
    }

    private val backend = FakeBackend()
    private val keeper = FakeKeeper()
    private val root: File = Files.createTempDirectory("recordings").toFile()
    private val recorder = TrackRecorder(root)
    private var locationGranted = true
    private var now = 1_000_000L
    private val session = LiveMetricsSession(backend, keeper, recorder, { locationGranted }, { now })

    private val active get() = session.state.value as LiveMetricsState.Active

    @Test
    fun iniciaUnEjercicioYMantieneVivoElProceso() = runTest {
        session.start()

        assertEquals(listOf("start"), backend.calls)
        assertTrue(keeper.held)
        assertEquals(null, active.heartRateBpm)
        assertEquals(HeartRateAvailability.ACQUIRING, active.availability)
    }

    @Test
    fun sinLecturaLaFrecuenciaEsNullNuncaCero() = runTest {
        session.start()
        backend.emit(Phase.ACTIVE, hr = 0.0)
        assertEquals(null, active.heartRateBpm)

        backend.emit(Phase.ACTIVE, hr = 131.6)
        assertEquals(132, active.heartRateBpm)
        assertEquals(HeartRateAvailability.AVAILABLE, active.availability)

        // Una actualización sin muestra (p. ej. solo duración) conserva la última lectura.
        backend.emit(Phase.ACTIVE, hr = null, duration = ActiveDuration(1_000, 60_000, running = true))
        assertEquals(132, active.heartRateBpm)
    }

    @Test
    fun siElSensorDejaDeEstarDisponibleLaLecturaVieneNullConSuMotivo() = runTest {
        session.start()
        backend.emit(Phase.ACTIVE, hr = 120.0)

        backend.registered!!.onAvailability(HeartRateAvailability.OFF_BODY)

        assertEquals(null, active.heartRateBpm)
        assertEquals(HeartRateAvailability.OFF_BODY, active.availability)
    }

    @Test
    fun laDuracionSigueElCheckpointYSeExtrapolaSoloMientrasCorre() = runTest {
        session.start()
        backend.emit(Phase.ACTIVE, duration = ActiveDuration(atEpochMs = 10_000, activeMs = 60_000, running = true))
        assertEquals(65_000, active.duration.at(15_000))

        backend.emit(Phase.PAUSED, duration = ActiveDuration(atEpochMs = 20_000, activeMs = 70_000, running = false))
        assertTrue(active.paused)
        assertEquals(70_000, active.duration.at(99_000))
    }

    @Test
    fun pausarReanudarYTerminarVanAHealthServices() = runTest {
        session.start()
        session.pause()
        session.resume()
        session.end()
        assertEquals(listOf("start", "pause", "resume", "end"), backend.calls)
        // El fin real llega con la actualización ENDED; hasta entonces sigue activa.
        assertTrue(session.state.value is LiveMetricsState.Active)
        assertTrue(keeper.held)
    }

    @Test
    fun alTerminarMuestraLaDuracionYLiberaElProceso() = runTest {
        session.start()
        backend.emit(Phase.ENDED, duration = ActiveDuration(0, 3_600_000, running = false), reason = EndReason.USER)

        assertEquals(LiveMetricsState.Ended(3_600_000, EndReason.USER), session.state.value)
        assertFalse(keeper.held)

        session.dismiss()
        assertEquals(LiveMetricsState.Idle, session.state.value)
    }

    @Test
    fun siOtraAppLaTerminaSeExplica() = runTest {
        session.start()
        backend.emit(Phase.ACTIVE, duration = ActiveDuration(0, 5_000, running = true))
        backend.emit(Phase.ENDED, reason = EndReason.SUPERSEDED)
        assertEquals(LiveMetricsState.Ended(5_000, EndReason.SUPERSEDED), session.state.value)
    }

    @Test
    fun sinFrecuenciaCardiacaEnElRelojNoInicia() = runTest {
        backend.supports = false
        session.start()
        assertEquals(LiveMetricsState.Unsupported, session.state.value)
        assertEquals(emptyList<String>(), backend.calls)
        assertFalse(keeper.held)
    }

    @Test
    fun noLeQuitaElEjercicioAOtraApp() = runTest {
        backend.status = TrackedStatus.OTHER_APP
        session.start()
        assertEquals(LiveMetricsState.OtherAppInProgress, session.state.value)
        assertEquals(emptyList<String>(), backend.calls)
    }

    @Test
    fun unFalloAlIniciarSeMuestraConSoloElTipoDeError() = runTest {
        backend.failStart = IOException("detalle que no debe verse")
        session.start()
        assertEquals(LiveMetricsState.Failed("IOEXCEPTION"), session.state.value)
        assertFalse(keeper.held)
    }

    @Test
    fun recuperaUnEjercicioPropioQueSiguioCorriendo() = runTest {
        backend.status = TrackedStatus.OWNED
        session.attach()
        assertTrue(keeper.held)
        backend.emit(Phase.ACTIVE, hr = 98.0, duration = ActiveDuration(0, 600_000, running = true))
        assertEquals(98, active.heartRateBpm)
        assertEquals(emptyList<String>(), backend.calls) // no se inicia otro
    }

    @Test
    fun sinEjercicioEnCursoAttachNoHaceNada() = runTest {
        session.attach()
        assertEquals(LiveMetricsState.Idle, session.state.value)
        assertFalse(keeper.held)
    }

    @Test
    fun iniciarDosVecesNoCreaDosEjercicios() = runTest {
        session.start()
        session.start()
        assertEquals(listOf("start"), backend.calls)
    }

    @Test
    fun dismissNoDescartaUnaSesionActiva() = runTest {
        session.start()
        session.dismiss()
        assertTrue(session.state.value is LiveMetricsState.Active)
    }

    @Test
    fun codigosDeErrorConFormatoDeDiagnostico() {
        assertEquals("ILLEGAL_STATE_EXCEPTION", LiveMetricsSession.codeFor(IllegalStateException("x")))
        assertTrue(Regex("[A-Z][A-Z0-9_]{0,31}").matches(LiveMetricsSession.codeFor(IOException())))
    }

    // --- Modo A: la garantía de WC-34 sigue en pie con el modo B en la app ---

    @Test
    fun elModoANoEnciendeElGpsNiGuardaNada() = runTest {
        session.start()
        backend.emit(Phase.ACTIVE, hr = 120.0, locations = listOf(TrackPoint(now, 19.4, -99.1)))
        backend.emit(Phase.ENDED, reason = EndReason.USER)

        assertEquals(listOf("start"), backend.calls)
        assertFalse(keeper.withLocation)
        assertEquals(null, (session.state.value as LiveMetricsState.Ended).recording)
        assertTrue("El modo A no escribe archivos: ${root.list().orEmpty().toList()}", root.list().orEmpty().isEmpty())
    }

    // --- Modo B (WC-53) ---

    @Test
    fun elModoBEnciendeElGpsYGuardaLosPuntos() = runTest {
        session.start(SessionMode.RECORDING)
        assertEquals(listOf("start+gps"), backend.calls)
        assertTrue(keeper.withLocation)
        assertEquals(RecordingProgress(0, 0.0), active.recording)

        backend.emit(
            Phase.ACTIVE,
            hr = 130.0,
            locations = listOf(TrackPoint(now, 19.4000, -99.1000), TrackPoint(now + 1_000, 19.4010, -99.1000)),
            heartRates = listOf(HeartRateSample(now, 130)),
        )
        assertEquals(2, active.recording!!.points)
        assertTrue(active.recording!!.distanceMeters in 100.0..120.0)

        now += 60_000
        backend.emit(Phase.ENDED, duration = ActiveDuration(0, 60_000, running = false), reason = EndReason.USER)
        val saved = (session.state.value as LiveMetricsState.Ended).recording!!
        assertEquals(RecordingStatus.FINISHED, saved.status)
        assertEquals(2, saved.points)
        assertTrue(saved.gpx!!.readText().contains("<gpxtpx:hr>130</gpxtpx:hr>"))
        assertFalse(keeper.held)
    }

    @Test
    fun sinPermisoDeUbicacionNoGrabaYLoExplica() = runTest {
        locationGranted = false
        session.start(SessionMode.RECORDING)
        assertEquals(LiveMetricsState.LocationUnavailable, session.state.value)
        assertEquals(emptyList<String>(), backend.calls)
        assertFalse(keeper.held)
    }

    @Test
    fun sinGpsEnElRelojNoGraba() = runTest {
        backend.supportsGps = false
        session.start(SessionMode.RECORDING)
        assertEquals(LiveMetricsState.LocationUnavailable, session.state.value)
        assertEquals(emptyList<String>(), backend.calls)
    }

    @Test
    fun siElEjercicioNoArrancaNoQuedaUnaGrabacionVacia() = runTest {
        backend.failStart = IOException()
        session.start(SessionMode.RECORDING)
        assertTrue(session.state.value is LiveMetricsState.Failed)
        assertTrue(root.list().orEmpty().isEmpty())
    }

    @Test
    fun alReabrirLaAppConElEjercicioEnCursoSigueLaMismaGrabacion() = runTest {
        session.start(SessionMode.RECORDING)
        backend.emit(Phase.ACTIVE, locations = listOf(TrackPoint(now, 19.4, -99.1)))
        val id = recorder.activeId!!

        // La app se reinicia: proceso nuevo, mismo almacenamiento, el ejercicio sigue en Health Services.
        val backend2 = FakeBackend(status = TrackedStatus.OWNED)
        val recorder2 = TrackRecorder(root)
        val session2 = LiveMetricsSession(backend2, FakeKeeper(), recorder2, { true }, { now })
        session2.attach()

        assertEquals(id, recorder2.activeId)
        assertEquals(SessionMode.RECORDING, session2.mode)
        backend2.emit(Phase.ACTIVE, locations = listOf(TrackPoint(now + 1_000, 19.401, -99.1)))
        assertEquals(2, (session2.state.value as LiveMetricsState.Active).recording!!.points)
    }

    @Test
    fun siElRelojSeApagoLaGrabacionQuedaComoParcial() = runTest {
        session.start(SessionMode.RECORDING)
        backend.emit(Phase.ACTIVE, locations = listOf(TrackPoint(now, 19.4, -99.1), TrackPoint(now + 1_000, 19.401, -99.1)))

        // Al volver a abrir la app, Health Services ya no tiene el ejercicio.
        val session2 = LiveMetricsSession(FakeBackend(status = TrackedStatus.NONE), FakeKeeper(), TrackRecorder(root), { true }, { now })
        session2.attach()

        val saved = TrackRecorder(root).list().single()
        assertEquals(RecordingStatus.PARTIAL, saved.status)
        assertEquals(2, saved.points)
        assertNotNull(saved.gpx)
        assertEquals(LiveMetricsState.Idle, session2.state.value)
    }
}
