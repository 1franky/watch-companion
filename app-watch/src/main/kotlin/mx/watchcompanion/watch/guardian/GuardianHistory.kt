package mx.watchcompanion.watch.guardian

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import mx.watchcompanion.core.communication.GuardianCommandResult

/**
 * Un evento de Guardian (WC-47, 04 §2). Solo qué pasó y cuándo: **sin datos de salud, ubicación ni contenido**. Los
 * campos son cerrados a propósito; `GuardianHistoryTest` falla si alguien agrega uno.
 *
 * @property ref une un evento con su orden (el `commandId`): una confirmación tardía reemplaza el evento, no lo duplica.
 */
@Serializable
data class GuardianEvent(
    val at: Long,
    val kind: Kind,
    val outcome: GuardianCommandResult.Outcome? = null,
    val code: String? = null,
    val ref: String? = null,
) {
    @Serializable
    enum class Kind {
        /** Bloqueo pedido desde el reloj, con su resultado real. */
        LOCK,

        /** Se avisó que el teléfono no estaba cerca. */
        PHONE_AWAY,

        /** El teléfono volvió después de un aviso. */
        PHONE_BACK,
    }
}

/**
 * Historial local de Guardian en el reloj, fuera del respaldo. Se recorta a [retentionDays] y a [MAX_EVENTS] en cada
 * cambio y al cambiar la retención; revocar Guardian lo borra con [clear].
 */
class GuardianHistory(
    private val file: File,
    private val retentionDays: () -> Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val serializer = ListSerializer(GuardianEvent.serializer())
    private val _events = MutableStateFlow<List<GuardianEvent>>(emptyList())

    /** Del más reciente al más antiguo. */
    val events: StateFlow<List<GuardianEvent>> = _events.asStateFlow()

    suspend fun load() = mutex.withLock {
        val stored = try {
            if (file.exists()) Json.decodeFromString(serializer, file.readText()) else emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Archivo dañado: el historial es informativo, se empieza de nuevo.
            emptyList()
        }
        _events.value = trim(stored)
    }

    /** Agrega [event]; si ya hay uno con el mismo [GuardianEvent.ref], lo reemplaza. */
    suspend fun record(event: GuardianEvent) = mutex.withLock {
        val others = if (event.ref == null) _events.value else _events.value.filterNot { it.ref == event.ref }
        save(trim(others + event))
    }

    /** Aplica la retención actual (al cambiarla). */
    suspend fun prune() = mutex.withLock { save(trim(_events.value)) }

    suspend fun clear() = mutex.withLock {
        file.delete()
        _events.value = emptyList()
    }

    private fun trim(events: List<GuardianEvent>): List<GuardianEvent> {
        val since = clock() - retentionDays() * DAY_MS
        return events.filter { it.at >= since }.sortedByDescending { it.at }.take(MAX_EVENTS)
    }

    private fun save(events: List<GuardianEvent>) {
        if (events == _events.value && file.exists()) return
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(Json.encodeToString(serializer, events))
        if (!tmp.renameTo(file)) throw IOException("No se pudo guardar el historial")
        _events.value = events
    }

    companion object {
        const val DAY_MS = 24 * 60 * 60_000L
        const val MAX_EVENTS = 200

        /** Retenciones que ofrece el reloj, en días. */
        val RETENTION_OPTIONS = listOf(1, 7, 30)
        const val DEFAULT_RETENTION_DAYS = 7

        fun nextRetention(current: Int): Int =
            RETENTION_OPTIONS.getOrNull(RETENTION_OPTIONS.indexOf(current) + 1) ?: RETENTION_OPTIONS.first()
    }
}
