package mx.watchcompanion.core.communication

import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Zonas de FC guardadas localmente (WC-32): en el teléfono, las que configuró el usuario; en el reloj, la última versión
 * recibida, para que las alertas funcionen sin teléfono ni internet (01 §3.1). Un archivo ilegible cuenta como vacío.
 */
class HeartRateZonesStore(
    private val read: suspend () -> String?,
    private val write: suspend (String) -> Unit,
) {
    private val mutex = Mutex()
    private var loaded = false
    private val _zones = MutableStateFlow<HeartRateZones?>(null)
    val zones: StateFlow<HeartRateZones?> = _zones.asStateFlow()

    suspend fun load() = mutex.withLock { loadLocked() }

    private suspend fun loadLocked() {
        loaded = true
        _zones.value = try {
            read()?.let { json.decodeFromString(HeartRateZones.serializer(), it) }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    /** Guarda [zones] siempre: es lo que el usuario acaba de decidir. */
    suspend fun save(zones: HeartRateZones) = mutex.withLock { persist(zones) }

    /** Guarda [zones] solo si es más reciente que la guardada; devuelve si la guardó. Lo usa el reloj al recibirlas. */
    suspend fun saveIfNewer(zones: HeartRateZones): Boolean = mutex.withLock {
        // Pueden llegar con la app recién arrancada: se compara contra lo guardado, no contra un estado vacío.
        if (!loaded) loadLocked()
        val current = _zones.value
        if (current != null && current.updatedAt >= zones.updatedAt) return@withLock false
        persist(zones)
        true
    }

    private suspend fun persist(zones: HeartRateZones) {
        write(json.encodeToString(HeartRateZones.serializer(), zones))
        _zones.value = zones
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** En [file], con escritura atómica (archivo temporal que reemplaza al anterior). */
        fun inFile(file: File) = HeartRateZonesStore(
            read = { withContext(Dispatchers.IO) { if (file.exists()) file.readText() else null } },
            write = { text ->
                withContext(Dispatchers.IO) {
                    val tmp = File(file.parentFile, "${file.name}.tmp")
                    tmp.writeText(text)
                    if (!tmp.renameTo(file)) throw IOException("No se pudo reemplazar ${file.name}")
                }
            },
        )
    }
}
