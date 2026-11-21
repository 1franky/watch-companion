package mx.watchcompanion.core.communication

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Estado de la última actividad en Velora (WC-33), tal como lo consultó el teléfono. En el modo A las rodadas llegan
 * por Strava, así que combina la última actividad (`GET /activities?limit=1`) con el estado del conector
 * (`GET /integrations/strava`).
 *
 * Nunca simula un éxito: si la consulta falla, el estado es [State.ERROR] con su código, y [checkedAt] dice cuándo se
 * consultó. Solo viaja el inicio y la distancia de la actividad, no su título ni su recorrido.
 */
@Serializable
data class ActivityStatus(
    val state: State,
    /** Cuándo consultó el teléfono a Velora (ms epoch UTC). */
    val checkedAt: Long,
    val activityStartedAt: Long? = null,
    val distanceMeters: Double? = null,
    /** Motivo de [State.ERROR], con el formato de los códigos de diagnóstico. */
    val errorCode: String? = null,
) {
    init {
        require(errorCode == null || CODE.matches(errorCode)) { "código de error inválido" }
        require(distanceMeters == null || distanceMeters >= 0) { "distancia negativa" }
    }

    @Serializable
    enum class State {
        /** Conector al día y sin error, pero aún sin actividades en Velora. */
        PENDING,

        /** Strava sigue cargando el historial (`backfillDone = false`). */
        SYNCING,

        /** Hay una actividad en Velora: la más reciente es la de [activityStartedAt]. */
        SYNCED,
        ERROR,
    }

    private companion object {
        val CODE = Regex("[A-Z][A-Z0-9_]{0,31}")
    }
}

/** El teléfono envía el estado en cuanto termina de consultarlo (WC-33); el Pong lleva el último conocido. */
@SerialName("ActivityStatusReport")
@Serializable
data class ActivityStatusReport(val status: ActivityStatus) : Payload
