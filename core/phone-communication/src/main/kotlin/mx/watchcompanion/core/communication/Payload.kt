package mx.watchcompanion.core.communication

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Contenido de un [Envelope]. El nombre serializado (`type`) es parte del contrato: no se cambia, se agrega uno nuevo.
 *
 * El catálogo crece con cada tarjeta que lo necesita; el listado completo previsto está en 00-architecture.md §5.
 */
@Serializable
sealed interface Payload {
    /** Los mensajes sensibles (comandos con efecto) exigen `nonce` y `expiresAt` y se rechazan si se repiten. */
    val sensitive: Boolean get() = false
}

/** Comprobación de enlace de ida y vuelta (WC-19). */
@Serializable
@SerialName("Ping")
data object Ping : Payload

/** Respuesta a [Ping]; viaja con el `correlationId` del ping. */
@Serializable
@SerialName("Pong")
data class Pong(val appVersion: String) : Payload

/** Estado de una integración visto por quien envía el mensaje. */
@Serializable
@SerialName("IntegrationStatus")
data class IntegrationStatus(val integration: Integration, val state: State, val detailCode: String? = null) : Payload {
    @Serializable
    enum class Integration { PHONE_LINK, VELORA, NEXORA }

    @Serializable
    enum class State {
        AVAILABLE,
        UNAVAILABLE,
        /** La otra app usa una versión del protocolo fuera del rango aceptado (R-05). */
        INCOMPATIBLE,
    }
}
