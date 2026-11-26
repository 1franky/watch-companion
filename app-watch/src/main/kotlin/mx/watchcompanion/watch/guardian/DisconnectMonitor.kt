package mx.watchcompanion.watch.guardian

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Desde cuándo el teléfono no está cerca y si ya se avisó. [since] es `null` mientras está cerca o con el aviso apagado.
 */
data class DisconnectState(val since: Long? = null, val alerted: Boolean = false)

/** Dónde se guardan el plazo elegido y el estado, para sobrevivir a que el sistema cierre el proceso. */
interface DisconnectStore {
    /** Minutos sin el teléfono antes de avisar, o `null` con el aviso apagado. */
    var delayMinutes: Int?
    var state: DisconnectState
}

/** Programa una revisión en segundo plano (WorkManager en la app). */
interface DisconnectScheduler {
    fun schedule(delayMs: Long)
    fun cancel()
}

/** El aviso: vibración y notificación. [clear] la retira cuando el teléfono vuelve. */
interface DisconnectAlerter {
    fun alert(since: Long)
    fun clear()
}

/**
 * Aviso de desconexión entre el reloj y el teléfono (WC-45, 04 §2, R-08). Solo usa lo que da la Data Layer: si el
 * teléfono con la app está **alcanzable y cerca** (`Node.isNearby`). Alcanzable solo por la nube (LTE o Wi-Fi lejos del
 * teléfono) cuenta como lejos. No usa ubicación ni intensidad de señal.
 *
 * Una desconexión no se interpreta como robo: el aviso solo dice desde cuándo no está el teléfono. Avisa una vez por
 * desconexión, tras [DisconnectStore.delayMinutes] seguidos sin él; si vuelve antes, no avisa.
 */
class DisconnectMonitor(
    private val store: DisconnectStore,
    private val scheduler: DisconnectScheduler,
    private val alerter: DisconnectAlerter,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Para el historial de Guardian (WC-47): el aviso y el regreso del teléfono tras un aviso. */
    private val onEvent: (GuardianEvent.Kind, Long) -> Unit = { _, _ -> },
) {
    private val _delayMinutes = MutableStateFlow(store.delayMinutes)
    val delayMinutes: StateFlow<Int?> = _delayMinutes.asStateFlow()

    private val _state = MutableStateFlow(store.state)
    val state: StateFlow<DisconnectState> = _state.asStateFlow()

    /** Cambio de la Data Layer: el teléfono está cerca o dejó de estarlo. */
    @Synchronized
    fun onReachability(nearby: Boolean) {
        if (nearby) {
            reset(back = true)
            return
        }
        val delay = store.delayMinutes ?: return
        if (store.state.since != null) return
        save(DisconnectState(since = clock()))
        scheduler.schedule(delay * MINUTE_MS)
    }

    /** Revisión programada: si sigue sin el teléfono y ya pasó el plazo, avisa; si no, vuelve a programarse. */
    @Synchronized
    fun onCheck(nearby: Boolean) {
        if (nearby) {
            reset(back = true)
            return
        }
        val delay = store.delayMinutes ?: return
        val current = store.state
        val since = current.since ?: run {
            // Se perdió el inicio (p. ej. se activó el aviso ya sin teléfono): cuenta desde ahora.
            onReachability(false)
            return
        }
        if (current.alerted) return
        val remaining = since + delay * MINUTE_MS - clock()
        if (remaining > 0) {
            scheduler.schedule(remaining)
            return
        }
        alerter.alert(since)
        save(current.copy(alerted = true))
        onEvent(GuardianEvent.Kind.PHONE_AWAY, clock())
    }

    /** Cambia el plazo; `null` apaga el aviso y retira el que estuviera a la vista. */
    @Synchronized
    fun setDelay(minutes: Int?) {
        require(minutes == null || minutes in OPTIONS) { "plazo no ofrecido" }
        store.delayMinutes = minutes
        _delayMinutes.value = minutes
        reset()
    }

    /** [back]: el teléfono volvió (no se apagó el aviso); solo entonces cuenta para el historial. */
    private fun reset(back: Boolean = false) {
        scheduler.cancel()
        if (store.state.alerted) {
            alerter.clear()
            if (back) onEvent(GuardianEvent.Kind.PHONE_BACK, clock())
        }
        if (store.state != DisconnectState()) save(DisconnectState())
    }

    private fun save(state: DisconnectState) {
        store.state = state
        _state.value = state
    }

    companion object {
        /** Plazos que ofrece el reloj, en minutos. */
        val OPTIONS = listOf(2, 5, 15, 30)
        const val MINUTE_MS = 60_000L

        /** El siguiente plazo al tocar el botón: apagado → 2 → 5 → 15 → 30 → apagado. */
        fun next(current: Int?): Int? = when (current) {
            null -> OPTIONS.first()
            OPTIONS.last() -> null
            else -> OPTIONS.getOrNull(OPTIONS.indexOf(current) + 1)
        }
    }
}
