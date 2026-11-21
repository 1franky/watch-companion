package mx.watchcompanion.watch.live

import java.io.IOException
import kotlinx.coroutines.test.runTest
import mx.watchcompanion.watch.live.ExerciseSnapshot.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveMetricsSessionTest {
    private class FakeBackend(
        var supports: Boolean = true,
        var status: TrackedStatus = TrackedStatus.NONE,
        var failStart: Exception? = null,
    ) : ExerciseBackend {
        var registered: ExerciseListener? = null
        val calls = mutableListOf<String>()
        override suspend fun supportsHeartRate() = supports
        override suspend fun trackedStatus() = status
        override fun setListener(listener: ExerciseListener) {
            registered = listener
        }
        override suspend fun start() {
            failStart?.let { throw it }
            calls += "start"
        }
        override suspend fun pause() { calls += "pause" }
        override suspend fun resume() { calls += "resume" }
        override suspend fun end() { calls += "end" }

        fun emit(phase: Phase, hr: Double? = null, duration: ActiveDuration? = null, reason: EndReason? = null) =
            registered!!.onSnapshot(ExerciseSnapshot(phase, hr, duration, reason))
    }

    private class FakeKeeper : ForegroundKeeper {
        var held = false
        override fun acquire() { held = true }
        override fun release() { held = false }
    }

    private val backend = FakeBackend()
    private val keeper = FakeKeeper()
    private val session = LiveMetricsSession(backend, keeper)

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
}
