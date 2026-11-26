package mx.watchcompanion.watch.guardian

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import mx.watchcompanion.core.communication.GuardianCommand
import mx.watchcompanion.core.communication.GuardianCommandResult
import mx.watchcompanion.core.communication.GuardianCommandResult.Outcome

/** En qué va el bloqueo del teléfono pedido desde el reloj (WC-44). */
sealed interface PhoneLockState {
    data object Idle : PhoneLockState

    data class Sending(val commandId: String) : PhoneLockState

    /** Lo que respondió el teléfono, o lo que el reloj concluyó sin respuesta ([Outcome.EXPIRED], `NO_PHONE`). */
    data class Done(val result: GuardianCommandResult, val at: Long) : PhoneLockState
}

/**
 * Bloqueo manual del teléfono desde el reloj (WC-44, 04 §3). Manda una [GuardianCommand] sensible y espera la
 * respuesta real. **Nunca muestra «bloqueado» sin la confirmación del teléfono**: sin respuesta antes de que la orden
 * caduque, el resultado es [Outcome.EXPIRED] («sin confirmación»). Si la confirmación llega tarde, la reemplaza.
 *
 * @param send manda la orden con `nonce` y caducidad; `false` si no hay teléfono al alcance.
 */
class PhoneLock(
    private val send: suspend (GuardianCommand) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val waitMs: Long = GuardianCommand.TTL_MS + REPLY_MARGIN_MS,
) {
    private val _state = MutableStateFlow<PhoneLockState>(PhoneLockState.Idle)
    val state: StateFlow<PhoneLockState> = _state.asStateFlow()

    private var pending: Pair<String, CompletableDeferred<GuardianCommandResult>>? = null

    /** Pide el bloqueo y espera la respuesta. Un segundo toque mientras se espera no manda otra orden. */
    suspend fun lock() {
        val id = newId()
        val reply = CompletableDeferred<GuardianCommandResult>()
        synchronized(this) {
            if (_state.value is PhoneLockState.Sending) return
            pending = id to reply
            _state.value = PhoneLockState.Sending(id)
        }
        val sent = send(GuardianCommand(id, GuardianCommand.Action.LOCK))
        val result = if (!sent) {
            GuardianCommandResult(id, Outcome.UNAVAILABLE, NO_PHONE)
        } else {
            withTimeoutOrNull(waitMs) { reply.await() }
                // La respuesta pudo llegar justo al vencer el plazo.
                ?: (if (reply.isCompleted) reply.await() else null)
                ?: GuardianCommandResult(id, Outcome.EXPIRED)
        }
        _state.value = PhoneLockState.Done(result, clock())
    }

    /** Respuesta del teléfono. Solo cuenta la de la última orden; una confirmación tardía corrige el «sin confirmación». */
    fun onResult(result: GuardianCommandResult) {
        val (id, reply) = synchronized(this) { pending } ?: return
        if (result.commandId != id) return
        reply.complete(result)
        _state.update { current ->
            if (current is PhoneLockState.Done && current.result.commandId == id && current.result != result) PhoneLockState.Done(result, clock()) else current
        }
    }

    companion object {
        /** Margen para que la respuesta vuelva después de que la orden caduque en el teléfono. */
        const val REPLY_MARGIN_MS: Long = 5_000

        /** El reloj no encontró el teléfono: la orden ni siquiera salió. */
        const val NO_PHONE = "NO_PHONE"
    }
}
