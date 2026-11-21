package mx.watchcompanion.watch.alerts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mx.watchcompanion.core.communication.HeartRateZones
import mx.watchcompanion.watch.live.ActiveDuration
import mx.watchcompanion.watch.live.HeartRateAvailability
import mx.watchcompanion.watch.live.LiveMetricsState
import org.junit.Assert.assertEquals
import org.junit.Test

class ZoneAlertsTest {
    private val zonas = MutableStateFlow<HeartRateZones?>(HeartRateZones.fromMax(200, HeartRateZones.Source.VELORA, 0))

    private fun activa(bpm: Int?, paused: Boolean = false) =
        LiveMetricsState.Active(bpm, HeartRateAvailability.AVAILABLE, ActiveDuration.ZERO, paused)

    @Test
    fun conLaFcEstableLaPermanenciaSeCumpleSinNuevasActualizaciones() = runTest {
        val sesion = MutableStateFlow<LiveMetricsState>(LiveMetricsState.Idle)
        val vibraciones = mutableListOf<ZoneAlert>()
        ZoneAlerts(sesion, zonas, { vibraciones += it }, clock = { testScheduler.currentTime }).start(backgroundScope)
        runCurrent()

        sesion.value = activa(130)
        runCurrent()
        sesion.value = activa(150) // sube a Z3 y se queda así: la sesión no vuelve a emitir
        advanceTimeBy(6_000)
        runCurrent()

        assertEquals(listOf(ZoneAlert(ZoneAlert.Direction.UP, 2, 3)), vibraciones)
    }

    @Test
    fun enPausaNoAvisa() = runTest {
        val sesion = MutableStateFlow<LiveMetricsState>(activa(130))
        val vibraciones = mutableListOf<ZoneAlert>()
        ZoneAlerts(sesion, zonas, { vibraciones += it }, clock = { testScheduler.currentTime }).start(backgroundScope)
        runCurrent()

        sesion.value = activa(150, paused = true)
        advanceTimeBy(30_000)
        runCurrent()

        assertEquals(emptyList<ZoneAlert>(), vibraciones)
    }

    @Test
    fun unaSesionNuevaNoAvisaPorLaZonaDeLaAnterior() = runTest {
        val sesion = MutableStateFlow<LiveMetricsState>(activa(170))
        val vibraciones = mutableListOf<ZoneAlert>()
        ZoneAlerts(sesion, zonas, { vibraciones += it }, clock = { testScheduler.currentTime }).start(backgroundScope)
        runCurrent()

        sesion.value = LiveMetricsState.Ended(60_000, mx.watchcompanion.watch.live.EndReason.USER)
        runCurrent()
        sesion.value = activa(110)
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(emptyList<ZoneAlert>(), vibraciones)
    }

    @Test
    fun sinZonasGuardadasNoVibra() = runTest {
        zonas.value = null
        val sesion = MutableStateFlow<LiveMetricsState>(activa(100))
        val vibraciones = mutableListOf<ZoneAlert>()
        ZoneAlerts(sesion, zonas, { vibraciones += it }, clock = { testScheduler.currentTime }).start(backgroundScope)
        sesion.value = activa(190)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(emptyList<ZoneAlert>(), vibraciones)
    }
}
