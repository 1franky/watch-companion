package mx.watchcompanion.watch.guardian

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import mx.watchcompanion.core.communication.GuardianCommand
import mx.watchcompanion.core.communication.GuardianCommandResult

/** Preferencias del módulo; en la app, `SharedPreferences` (no son datos personales). */
interface GuardianSettingsStore {
    var enabled: Boolean
    var retentionDays: Int
}

/** En qué va la revocación pedida desde el reloj. */
sealed interface RevokeState {
    data object Idle : RevokeState
    data object Revoking : RevokeState

    /** Hecha en el reloj. [phoneConfirmed]: el teléfono confirmó que quitó el administrador. */
    data class Done(val phoneConfirmed: Boolean) : RevokeState
}

/**
 * Guardian como módulo del reloj (WC-47, 04 §2):
 * - **Desactivar** (un toque) apaga el aviso de desconexión y esconde el bloqueo; el historial se conserva.
 * - **Revocar** además pide al teléfono que quite el administrador de dispositivo y borra el historial y el estado
 *   local. Se hace en el reloj aunque el teléfono no conteste; entonces se dice que falta quitarlo allá.
 *
 * @param requestRevoke manda `GuardianCommand(REVOKE)` y devuelve la respuesta, o `null` si no hubo.
 */
class GuardianModule(
    private val settings: GuardianSettingsStore,
    private val history: GuardianHistory,
    private val monitor: DisconnectMonitor,
    private val requestRevoke: suspend () -> GuardianCommandResult?,
) {
    private val _enabled = MutableStateFlow(settings.enabled)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _retentionDays = MutableStateFlow(settings.retentionDays)
    val retentionDays: StateFlow<Int> = _retentionDays.asStateFlow()

    private val _revoke = MutableStateFlow<RevokeState>(RevokeState.Idle)
    val revoke: StateFlow<RevokeState> = _revoke.asStateFlow()

    fun setEnabled(on: Boolean) {
        settings.enabled = on
        _enabled.value = on
        if (on) _revoke.value = RevokeState.Idle else monitor.setDelay(null)
    }

    suspend fun setRetention(days: Int) {
        require(days in GuardianHistory.RETENTION_OPTIONS) { "retención no ofrecida" }
        settings.retentionDays = days
        _retentionDays.value = days
        history.prune()
    }

    /** Solo se guarda lo que pasa con el módulo activo. */
    suspend fun record(event: GuardianEvent) {
        if (_enabled.value) history.record(event)
    }

    suspend fun revoke() {
        if (_revoke.value == RevokeState.Revoking) return
        _revoke.value = RevokeState.Revoking
        val confirmed = requestRevoke()?.outcome == GuardianCommandResult.Outcome.REVOKED
        setEnabled(false)
        history.clear()
        _revoke.value = RevokeState.Done(confirmed)
    }
}

/**
 * Espera la respuesta de una orden de Guardian por su `commandId` (la de revocar; el bloqueo tiene la suya en
 * [PhoneLock]). Sin teléfono o sin respuesta a tiempo devuelve `null`.
 */
class GuardianCommandWaiter(
    private val send: suspend (GuardianCommand) -> Boolean,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val waitMs: Long = GuardianCommand.TTL_MS + PhoneLock.REPLY_MARGIN_MS,
) {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<GuardianCommandResult>>()

    suspend fun request(action: GuardianCommand.Action): GuardianCommandResult? {
        val id = newId()
        val reply = CompletableDeferred<GuardianCommandResult>()
        pending[id] = reply
        try {
            if (!send(GuardianCommand(id, action))) return null
            return withTimeoutOrNull(waitMs) { reply.await() }
        } finally {
            pending.remove(id)
        }
    }

    fun onResult(result: GuardianCommandResult) {
        pending[result.commandId]?.complete(result)
    }
}
