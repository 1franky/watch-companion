package mx.watchcompanion.phone

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import mx.watchcompanion.core.communication.HeartRateZones
import mx.watchcompanion.core.communication.HeartRateZonesConfig
import mx.watchcompanion.core.communication.HeartRateZonesStore
import mx.watchcompanion.core.communication.LinkSession
import mx.watchcompanion.core.session.SessionExpiredException
import mx.watchcompanion.integration.velora.VeloraSession

/** Resultado de pedir la FC máxima a Velora. */
sealed interface VeloraMax {
    data object Loading : VeloraMax

    data class Value(val bpm: Int) : VeloraMax

    /** El usuario no indicó FC máxima en Velora: se puede indicar aquí a mano. */
    data object NotSet : VeloraMax

    data object SignedOut : VeloraMax

    data class Failed(val code: String) : VeloraMax
}

/** Qué pasó con el envío al reloj al guardar. */
enum class ZonesDelivery {
    SENT,

    /** No había reloj alcanzable: llegarán con la próxima comprobación de enlace (Pong). */
    PENDING,
}

/**
 * Zonas de FC en el teléfono (WC-32): parten de la FC máxima de Velora, se pueden ajustar a mano, se guardan localmente
 * y se envían al reloj. El reloj también las recibe en cada Pong, así que un envío fallido no se pierde.
 */
class PhoneZones(
    private val store: HeartRateZonesStore,
    private val link: LinkSession,
    private val fetchVeloraMax: suspend () -> Int?,
) {
    val zones: StateFlow<HeartRateZones?> = store.zones

    suspend fun veloraMax(): VeloraMax = try {
        fetchVeloraMax()?.let { VeloraMax.Value(it) } ?: VeloraMax.NotSet
    } catch (e: CancellationException) {
        throw e
    } catch (_: SessionExpiredException) {
        VeloraMax.SignedOut
    } catch (e: Exception) {
        VeloraMax.Failed((e as? VeloraHttpException)?.code ?: e::class.simpleName.orEmpty().uppercase().take(32))
    }

    suspend fun save(zones: HeartRateZones): ZonesDelivery {
        store.save(zones)
        return if (link.sendToPeer(HeartRateZonesConfig(zones))) ZonesDelivery.SENT else ZonesDelivery.PENDING
    }
}

class VeloraHttpException(status: Int) : Exception("Velora respondió $status") {
    val code = "HTTP_$status"
}

/** FC máxima de las preferencias de Velora (`GET /me`, `preferences.maxHeartRateBpm`); `null` si no la indicó. */
suspend fun veloraMaxHeartRate(session: VeloraSession): Int? {
    val response = session.call("velora.getMe") { getMe() }
    if (!response.success) throw VeloraHttpException(response.status)
    return response.body().preferences.maxHeartRateBpm
}

/** Lo que el usuario capturó en la pantalla de zonas, ya validado. */
sealed interface ZonesInput {
    data class Valid(val zones: HeartRateZones) : ZonesInput

    data class Invalid(val reason: Reason) : ZonesInput

    enum class Reason { MAX_OUT_OF_RANGE, BOUND_MISSING, BOUND_TOO_LOW, NOT_ASCENDING, ABOVE_MAX }
}

/** Valida la FC máxima y los límites inferiores de Z1…Z5 tal como se escribieron. */
fun parseZones(maxText: String, boundTexts: List<String>, source: HeartRateZones.Source, now: Long): ZonesInput {
    val max = maxText.trim().toIntOrNull()
    if (max == null || max !in HeartRateZones.MAX_HR_RANGE) return ZonesInput.Invalid(ZonesInput.Reason.MAX_OUT_OF_RANGE)
    val bounds = boundTexts.map { it.trim().toIntOrNull() }
    if (bounds.size != HeartRateZones.ZONES || bounds.any { it == null }) return ZonesInput.Invalid(ZonesInput.Reason.BOUND_MISSING)
    val values = bounds.filterNotNull()
    return when {
        values.first() < HeartRateZones.MIN_BOUND_BPM -> ZonesInput.Invalid(ZonesInput.Reason.BOUND_TOO_LOW)
        values.zipWithNext().any { (a, b) -> a >= b } -> ZonesInput.Invalid(ZonesInput.Reason.NOT_ASCENDING)
        values.last() > max -> ZonesInput.Invalid(ZonesInput.Reason.ABOVE_MAX)
        else -> ZonesInput.Valid(HeartRateZones(max, values, source, now))
    }
}
