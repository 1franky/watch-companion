package mx.watchcompanion.core.communication

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Orden de Guardian del reloj al teléfono (WC-44, 04 §3). Es **sensible**: viaja con `nonce` y `expiresAt`, y el
 * teléfono rechaza repetidos y caducados sin ejecutarlos. No se reintenta sola: si no hay respuesta, el usuario vuelve
 * a pedirla y sale con otro [commandId] y otro `nonce`.
 *
 * @property commandId UUID que generó el reloj; la respuesta lo repite.
 */
@Serializable
@SerialName("GuardianCommand")
data class GuardianCommand(val commandId: String, val action: Action) : Payload {
    override val sensitive: Boolean get() = true

    @Serializable
    enum class Action {
        /** `DevicePolicyManager.lockNow()` con el administrador de dispositivo (WC-43). */
        LOCK,

        /** Quitar el administrador de dispositivo (WC-47): lo pide el reloj al revocar Guardian. Idempotente. */
        REVOKE,
    }

    companion object {
        /** Vida de la orden: si no llega y se ejecuta en este tiempo, ya no se ejecuta. */
        const val TTL_MS: Long = 15_000
    }
}

/**
 * Lo que de verdad pasó con una [GuardianCommand]. Solo [Outcome.LOCKED] significa «bloqueado», y el teléfono lo manda
 * solo después de comprobar con `KeyguardManager` que el bloqueo ocurrió.
 *
 * @property errorCode motivo técnico de [Outcome.REJECTED] o [Outcome.UNAVAILABLE]; formato de diagnóstico.
 */
@Serializable
@SerialName("GuardianCommandResult")
data class GuardianCommandResult(val commandId: String, val outcome: Outcome, val errorCode: String? = null) : Payload {
    init {
        require(errorCode == null || CODE.matches(errorCode)) { "código de error inválido" }
    }

    @Serializable
    enum class Outcome {
        /** El teléfono quedó bloqueado (comprobado después de `lockNow()`). */
        LOCKED,

        /** El teléfono no quiso: orden desconocida o el sistema la negó. */
        REJECTED,

        /** No se puede: falta el administrador, no hay bloqueo seguro o el bloqueo no se comprobó. */
        UNAVAILABLE,

        /** El administrador de dispositivo quedó quitado (o ya no estaba), en respuesta a [GuardianCommand.Action.REVOKE]. */
        REVOKED,

        /** La orden caducó antes de ejecutarse. El reloj también lo concluye si no llega respuesta a tiempo. */
        EXPIRED,
    }

    private companion object {
        val CODE = Regex("[A-Z][A-Z0-9_]{0,31}")
    }
}
