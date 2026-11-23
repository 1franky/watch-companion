package mx.watchcompanion.core.communication

import kotlinx.serialization.Serializable

/**
 * Sobre común de todo mensaje entre reloj y teléfono (00-architecture.md §5).
 *
 * Nunca transporta credenciales: el reloj no tiene tokens de backend (WC-ADR-013).
 *
 * @property messageId identificador único (UUID) del mensaje.
 * @property schemaVersion versión del protocolo con que se escribió; ver [ProtocolVersion].
 * @property createdAt instante de creación en milisegundos epoch UTC.
 * @property deviceId identificador seudónimo del dispositivo emisor; no es el número de serie ni el IMEI.
 * @property correlationId relaciona una respuesta con su solicitud.
 * @property nonce valor aleatorio de un solo uso; obligatorio en mensajes [Payload.sensitive].
 * @property expiresAt instante (ms epoch) a partir del cual el mensaje ya no se ejecuta; obligatorio en sensibles.
 */
@Serializable
data class Envelope(
    val messageId: String,
    val schemaVersion: Int,
    val createdAt: Long,
    val deviceId: String,
    val payload: Payload,
    val correlationId: String? = null,
    val nonce: String? = null,
    val expiresAt: Long? = null,
)
