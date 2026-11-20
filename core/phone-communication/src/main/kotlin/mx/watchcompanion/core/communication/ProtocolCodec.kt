package mx.watchcompanion.core.communication

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Resultado de leer un mensaje recibido. Nada se ejecuta salvo [Accepted]. */
sealed interface DecodeResult {
    data class Accepted(val envelope: Envelope) : DecodeResult

    /** Versión fuera de [ProtocolVersion]; se responde con [IntegrationStatus.State.INCOMPATIBLE]. */
    data class Incompatible(val schemaVersion: Int) : DecodeResult

    data object Expired : DecodeResult

    /** Mensaje sensible sin `nonce`/`expiresAt`, o con un nonce ya usado. */
    data object Replayed : DecodeResult

    data object MissingSecurityFields : DecodeResult

    /** No se pudo leer (JSON inválido o tipo desconocido). Nunca incluye el contenido recibido. */
    data object Malformed : DecodeResult
}

/**
 * Serializa y valida mensajes. Los bytes van tal cual por la Data Layer (`MessageClient`/`DataClient`).
 *
 * El orden de validación importa: primero la versión (para responder INCOMPATIBLE aunque el resto no se entienda),
 * luego el formato, la caducidad y por último el nonce, para no gastar un nonce en un mensaje ya caducado.
 */
class ProtocolCodec(
    private val nonces: NonceRegistry,
    /** Qué mensajes exigen nonce y expiración; por omisión, [Payload.sensitive]. Inyectable para pruebas. */
    private val isSensitive: (Payload) -> Boolean = { it.sensitive },
) {
    private val json = Json {
        // Una versión aceptada (N-1) puede no conocer campos opcionales nuevos.
        ignoreUnknownKeys = true
        encodeDefaults = false
        classDiscriminator = "type"
    }

    fun encode(envelope: Envelope): ByteArray {
        require(!isSensitive(envelope.payload) || (envelope.nonce != null && envelope.expiresAt != null)) {
            "Un mensaje sensible requiere nonce y expiresAt"
        }
        return json.encodeToString(Envelope.serializer(), envelope).encodeToByteArray()
    }

    fun decode(bytes: ByteArray, now: Long): DecodeResult {
        val text = bytes.decodeToString()
        val version = try {
            json.parseToJsonElement(text).jsonObject["schemaVersion"]?.jsonPrimitive?.int
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: return DecodeResult.Malformed
        if (!ProtocolVersion.isSupported(version)) return DecodeResult.Incompatible(version)

        val envelope = try {
            json.decodeFromString(Envelope.serializer(), text)
        } catch (_: SerializationException) {
            return DecodeResult.Malformed
        } catch (_: IllegalArgumentException) {
            return DecodeResult.Malformed
        }

        val expiresAt = envelope.expiresAt
        if (expiresAt != null && expiresAt <= now) return DecodeResult.Expired
        if (isSensitive(envelope.payload)) {
            val nonce = envelope.nonce
            if (nonce == null || expiresAt == null) return DecodeResult.MissingSecurityFields
            if (!nonces.register(nonce, expiresAt, now)) return DecodeResult.Replayed
        }
        return DecodeResult.Accepted(envelope)
    }
}
