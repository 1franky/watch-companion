package mx.watchcompanion.phone

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.SerializationException
import mx.watchcompanion.core.communication.ActivityStatus
import mx.watchcompanion.integration.velora.SessionExpiredException
import mx.watchcompanion.integration.velora.VeloraSession

/** Lo que interesa del conector de Strava de Velora (`GET /integrations/strava`). */
data class StravaInfo(val enabled: Boolean, val connected: Boolean, val backfillDone: Boolean, val lastSyncError: String?)

/** Lo que viaja al reloj de la última actividad: solo inicio y distancia. */
data class LatestActivity(val startedAt: Long, val distanceMeters: Double?)

/**
 * Estado de la última actividad a partir del conector de Strava y de la actividad más reciente (WC-33).
 *
 * El error del conector manda sobre todo lo demás, aunque haya una actividad anterior: así no parece al día algo que
 * no se está sincronizando.
 */
fun activityStatus(strava: StravaInfo, latest: LatestActivity?, now: Long): ActivityStatus {
    fun status(state: ActivityStatus.State, code: String? = null) =
        ActivityStatus(state, now, latest?.startedAt, latest?.distanceMeters, code)
    return when {
        !strava.enabled -> status(ActivityStatus.State.ERROR, "STRAVA_DISABLED")
        !strava.connected -> status(ActivityStatus.State.ERROR, "STRAVA_NOT_CONNECTED")
        strava.lastSyncError != null -> status(ActivityStatus.State.ERROR, strava.lastSyncError)
        !strava.backfillDone -> status(ActivityStatus.State.SYNCING)
        latest == null -> status(ActivityStatus.State.PENDING)
        else -> status(ActivityStatus.State.SYNCED)
    }
}

/**
 * Consulta a Velora el estado de la última actividad y lo guarda para el Pong. El reloj lo pide con cada ping, pero
 * dentro del ping no hay tiempo de ir a la red: se responde con el último conocido y se refresca aparte si tiene más
 * de [maxAgeMs].
 */
class PhoneActivity(
    private val fetchStrava: suspend () -> StravaInfo,
    private val fetchLatest: suspend () -> LatestActivity?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAgeMs: Long = MAX_AGE_MS,
) {
    private val _status = MutableStateFlow<ActivityStatus?>(null)
    val status: StateFlow<ActivityStatus?> = _status.asStateFlow()
    private val refreshing = Mutex()

    /** Consulta ya. Un fallo queda como [ActivityStatus.State.ERROR] con su código: nunca se simula un éxito. */
    suspend fun refresh(): ActivityStatus {
        val now = clock()
        val result = try {
            activityStatus(fetchStrava(), fetchLatest(), now)
        } catch (e: CancellationException) {
            throw e
        } catch (_: SessionExpiredException) {
            ActivityStatus(ActivityStatus.State.ERROR, now, errorCode = "SIGNED_OUT")
        } catch (e: VeloraHttpException) {
            ActivityStatus(ActivityStatus.State.ERROR, now, errorCode = e.code)
        } catch (_: SerializationException) {
            // Velora respondió algo que este contrato no conoce (p. ej. un lastSyncError nuevo): se actualiza el contrato.
            ActivityStatus(ActivityStatus.State.ERROR, now, errorCode = "UNREADABLE_RESPONSE")
        } catch (_: Exception) {
            ActivityStatus(ActivityStatus.State.ERROR, now, errorCode = "UNREACHABLE")
        }
        _status.value = result
        return result
    }

    /** Consulta solo si lo guardado es viejo y no hay otra consulta en curso; devuelve el nuevo estado o `null`. */
    suspend fun refreshIfStale(): ActivityStatus? {
        val current = _status.value
        if (current != null && clock() - current.checkedAt < maxAgeMs) return null
        if (!refreshing.tryLock()) return null
        return try {
            refresh()
        } finally {
            refreshing.unlock()
        }
    }

    companion object {
        const val MAX_AGE_MS = 2 * 60_000L
    }
}

suspend fun veloraStrava(session: VeloraSession): StravaInfo {
    val response = session.call("velora.getStravaStatus") { getStravaStatus() }
    if (!response.success) throw VeloraHttpException(response.status)
    val body = response.body()
    return StravaInfo(body.enabled, body.connected, body.backfillDone, body.lastSyncError?.value)
}

suspend fun veloraLatestActivity(session: VeloraSession): LatestActivity? {
    val response = session.call("velora.listActivities") {
        listActivities(from = null, to = null, bikeId = null, subType = null, minDistanceM = null, maxDistanceM = null, cursor = null, limit = 1)
    }
    if (!response.success) throw VeloraHttpException(response.status)
    return response.body().items.firstOrNull()?.let { LatestActivity(it.startedAt.toInstant().toEpochMilli(), it.distanceMeters) }
}
