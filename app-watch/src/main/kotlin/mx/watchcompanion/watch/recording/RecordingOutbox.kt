package mx.watchcompanion.watch.recording

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bandeja de salida de las grabaciones del modo B (WC-54): ofrece al teléfono cada grabación cerrada y la borra del
 * reloj cuando el teléfono confirma que la tiene. El track crudo no se queda en el reloj más de lo necesario (07 §2).
 *
 * Ofrecer es idempotente (el data item no cambia si el archivo es el mismo), así que se puede llamar cada vez que la app
 * arranca o termina una sesión: si una entrega anterior no llegó, la Data Layer la reintenta sola.
 *
 * @param offer publica la grabación en la Data Layer (`RecordingTransfer.offer`).
 */
class RecordingOutbox(
    private val recorder: TrackRecorder,
    private val offer: suspend (id: String, completeness: String, sha256: String, gpx: File) -> Unit,
) {
    private val _pending = MutableStateFlow(0)

    /** Grabaciones cerradas que siguen en el reloj, esperando que el teléfono las confirme. */
    val pending: StateFlow<Int> = _pending.asStateFlow()

    /** Ofrece todas las grabaciones cerradas. Las que no tienen ningún punto no hay qué enviar: se borran. */
    suspend fun offerPending() {
        var count = 0
        for (recording in recorder.list()) {
            if (recording.status == RecordingStatus.RECORDING) continue
            val gpx = recording.gpx
            val sha = recording.gpxSha256
            if (gpx == null || sha == null) {
                recorder.delete(recording.id)
                continue
            }
            count++
            try {
                offer(recording.id, completeness(recording.status), sha, gpx)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Sin Play services o sin espacio: se vuelve a ofrecer la próxima vez.
            }
        }
        _pending.value = count
    }

    /** El teléfono ya la tiene: se borra del reloj. */
    fun accepted(id: String) {
        if (recorder.delete(id)) _pending.value = (_pending.value - 1).coerceAtLeast(0)
    }

    companion object {
        /** Cómo lo nombra el contrato de Velora (`completeness`). */
        fun completeness(status: RecordingStatus): String =
            if (status == RecordingStatus.PARTIAL) "PARTIAL" else "COMPLETE"
    }
}
