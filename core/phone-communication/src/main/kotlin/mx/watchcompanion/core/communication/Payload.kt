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

/**
 * Respuesta a [Ping]; viaja con el `correlationId` del ping.
 *
 * @property sync estado de sincronización de cada integración del emisor (WC-29). Solo el teléfono lo llena, porque es
 *   el único que habla con los backends (ADR-011). Es opcional: un emisor sin él manda la lista vacía.
 *   Las zonas (WC-32) viajan igual: solo las manda el teléfono.
 */
@Serializable
@SerialName("Pong")
data class Pong(
    val appVersion: String,
    val sync: List<SyncSummary> = emptyList(),
    /** Zonas vigentes del teléfono (WC-32): el reloj las recibe en cada comprobación aunque se perdiera el envío directo. */
    val zones: HeartRateZones? = null,
    /** Último estado conocido de la actividad en Velora (WC-33); el teléfono lo actualiza aparte y lo reenvía. */
    val activity: ActivityStatus? = null,
) : Payload

/** El teléfono envía las zonas en cuanto el usuario las guarda (WC-32). Si el reloj no está, llegan con el próximo Pong. */
@Serializable
@SerialName("HeartRateZonesConfig")
data class HeartRateZonesConfig(val zones: HeartRateZones) : Payload

/**
 * Estado de sincronización de una integración con su backend, tal como lo ve el teléfono. Sin datos personales: solo
 * el estado, el instante del último éxito y un código técnico del registro de diagnóstico (WC-23).
 */
@Serializable
data class SyncSummary(
    val integration: IntegrationStatus.Integration,
    val state: State,
    /** Último contacto correcto con el backend (ms epoch UTC); `null` si nunca lo hubo. */
    val lastSuccessAt: Long? = null,
    /** Código del último error, solo con [State.ERROR]; mismo formato que los códigos de diagnóstico. */
    val errorCode: String? = null,
) {
    init {
        require(errorCode == null || ERROR_CODE.matches(errorCode)) { "código de error inválido" }
    }

    @Serializable
    enum class State {
        /** No hay sesión en el teléfono. */
        SIGNED_OUT,

        /** El backend cerró la sesión: hay que volver a entrar en el teléfono. */
        SESSION_EXPIRED,

        /** Hay sesión, pero aún no hubo ningún contacto con el backend. */
        NEVER_SYNCED,
        SYNCED,

        /** El último intento falló después del último éxito. */
        ERROR,
    }

    private companion object {
        val ERROR_CODE = Regex("[A-Z][A-Z0-9_]{0,31}")
    }
}

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
