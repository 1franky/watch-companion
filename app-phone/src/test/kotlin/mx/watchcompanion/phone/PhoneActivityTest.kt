package mx.watchcompanion.phone

import java.io.IOException
import kotlinx.coroutines.test.runTest
import mx.watchcompanion.core.communication.ActivityStatus
import mx.watchcompanion.core.communication.ActivityStatus.State
import mx.watchcompanion.integration.velora.SessionExpiredException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneActivityTest {
    private val alDia = StravaInfo(enabled = true, connected = true, backfillDone = true, lastSyncError = null)
    private val rodada = LatestActivity(startedAt = 1_000, distanceMeters = 42_300.0)

    @Test
    fun conActividadYConectorAlDiaEstaSincronizada() {
        assertEquals(ActivityStatus(State.SYNCED, 9, 1_000, 42_300.0), activityStatus(alDia, rodada, now = 9))
    }

    @Test
    fun sinActividadesQuedaPendiente() {
        assertEquals(ActivityStatus(State.PENDING, 9), activityStatus(alDia, null, now = 9))
    }

    @Test
    fun mientrasCargaElHistorialEstaSincronizando() {
        assertEquals(State.SYNCING, activityStatus(alDia.copy(backfillDone = false), rodada, 9).state)
    }

    @Test
    fun unErrorDelConectorMandaAunqueHayaUnaActividadAnterior() {
        val estado = activityStatus(alDia.copy(lastSyncError = "AUTH_REVOKED"), rodada, now = 9)
        assertEquals(ActivityStatus(State.ERROR, 9, 1_000, 42_300.0, "AUTH_REVOKED"), estado)
        assertEquals("STRAVA_NOT_CONNECTED", activityStatus(alDia.copy(connected = false), rodada, 9).errorCode)
        assertEquals("STRAVA_DISABLED", activityStatus(alDia.copy(enabled = false), rodada, 9).errorCode)
    }

    @Test
    fun unFalloDeLaConsultaNuncaSeVeComoExito() = runTest {
        suspend fun con(e: Exception) = PhoneActivity({ throw e }, { rodada }, clock = { 7 }).refresh()
        assertEquals(ActivityStatus(State.ERROR, 7, errorCode = "SIGNED_OUT"), con(SessionExpiredException()))
        assertEquals(ActivityStatus(State.ERROR, 7, errorCode = "HTTP_503"), con(VeloraHttpException(503)))
        assertEquals(ActivityStatus(State.ERROR, 7, errorCode = "UNREACHABLE"), con(IOException("sin red")))
        assertEquals(
            ActivityStatus(State.ERROR, 7, errorCode = "UNREADABLE_RESPONSE"),
            con(kotlinx.serialization.SerializationException("enum desconocido")),
        )
    }

    @Test
    fun soloVuelveAConsultarCuandoLoGuardadoEsViejo() = runTest {
        var ahora = 0L
        var consultas = 0
        val actividad = PhoneActivity({ consultas++; alDia }, { rodada }, clock = { ahora }, maxAgeMs = 120_000)

        assertEquals(State.SYNCED, actividad.refreshIfStale()?.state)
        ahora = 60_000
        assertNull(actividad.refreshIfStale())
        ahora = 120_000
        assertEquals(120_000L, actividad.refreshIfStale()?.checkedAt)
        assertEquals(2, consultas)
    }
}
