package mx.watchcompanion.watch.drafts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import mx.watchcompanion.core.communication.DraftStatus
import mx.watchcompanion.core.communication.QuickTransactionDraft

/** Un borrador capturado en este reloj y lo último que el teléfono dijo de él (`null`: aún no lo acusa). */
data class WatchDraft(val draft: QuickTransactionDraft, val status: DraftStatus? = null)

/**
 * Borradores de gasto o ingreso capturados en el reloj (WC-40). Solo viven en memoria: el reloj no guarda importes en
 * disco (03 §6). Mientras el teléfono no acuse uno, se reenvía en cada enlace; como lleva su `Idempotency-Key`, el
 * teléfono no lo duplica. El reloj nunca lo da por registrado: solo [DraftStatus.State.CREATED] lo es.
 *
 * @param send manda un borrador al teléfono; `false` si no hay a quién.
 */
class WatchDrafts(private val send: suspend (QuickTransactionDraft) -> Boolean, private val keep: Int = KEEP) {
    private val _drafts = MutableStateFlow<List<WatchDraft>>(emptyList())
    val drafts: StateFlow<List<WatchDraft>> = _drafts.asStateFlow()

    suspend fun submit(draft: QuickTransactionDraft) {
        _drafts.update { trim(listOf(WatchDraft(draft)) + it) }
        send(draft)
    }

    /** Estados del teléfono, sueltos o del Pong. Solo se aplica uno más reciente que el conocido. */
    fun onStatus(statuses: List<DraftStatus>) {
        val byKey = statuses.associateBy { it.idempotencyKey }
        _drafts.update { list ->
            list.map { current ->
                val incoming = byKey[current.draft.idempotencyKey] ?: return@map current
                val known = current.status
                if (known == null || incoming.updatedAt >= known.updatedAt) current.copy(status = incoming) else current
            }
        }
    }

    /** Reenvía los que el teléfono aún no acusa (se llama al recuperar el enlace). */
    suspend fun resendUnacknowledged() {
        for (pending in _drafts.value.filter { it.status == null }) send(pending.draft)
    }

    /** Se olvidan los terminados más viejos; uno sin terminar nunca se pierde por espacio. */
    private fun trim(list: List<WatchDraft>): List<WatchDraft> {
        var excess = list.size - keep
        return list.asReversed().filter { entry ->
            val drop = excess > 0 && entry.status?.state?.final == true
            if (drop) excess--
            !drop
        }.asReversed()
    }

    private companion object {
        const val KEEP = 10
    }
}
