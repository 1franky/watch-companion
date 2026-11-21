package mx.watchcompanion.core.diagnostics

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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Lo que necesitan los módulos para registrar; [NONE] donde no hay registro (el reloj, las pruebas). */
interface DiagnosticsRecorder {
    suspend fun record(
        module: DiagnosticModule,
        operation: String,
        correlationId: String,
        outcome: Outcome,
        code: String,
        durationMs: Long,
    )

    companion object {
        val NONE: DiagnosticsRecorder = object : DiagnosticsRecorder {
            override suspend fun record(
                module: DiagnosticModule,
                operation: String,
                correlationId: String,
                outcome: Outcome,
                code: String,
                durationMs: Long,
            ) = Unit
        }
    }
}

/** Una operación en curso: mide la duración y lleva el correlation ID que viaja con la solicitud. */
class Span internal constructor(
    private val recorder: DiagnosticsRecorder,
    private val module: DiagnosticModule,
    private val operation: String,
    private val clock: () -> Long,
    val correlationId: String,
) {
    private val startedAt = clock()

    suspend fun ok(code: String = "OK") = end(Outcome.OK, code)

    suspend fun error(code: String) = end(Outcome.ERROR, code)

    private suspend fun end(outcome: Outcome, code: String) =
        recorder.record(module, operation, correlationId, outcome, code, (clock() - startedAt).coerceAtLeast(0))
}

fun DiagnosticsRecorder.start(
    module: DiagnosticModule,
    operation: String,
    clock: () -> Long = System::currentTimeMillis,
    correlationId: String = DiagnosticEvent.newCorrelationId(),
) = Span(this, module, operation, clock, correlationId)

/** Dónde se guarda el registro; en el teléfono, un archivo fuera del respaldo. */
interface DiagnosticsStorage {
    suspend fun read(): String?
    suspend fun write(text: String)
}

/** Escritura atómica: un archivo temporal que reemplaza al anterior, para no dejar el registro a medias. */
class FileDiagnosticsStorage(private val file: File) : DiagnosticsStorage {
    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        if (file.exists()) file.readText() else null
    }

    override suspend fun write(text: String) = withContext(Dispatchers.IO) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) throw IOException("No se pudo reemplazar ${file.name}")
    }
}

/**
 * Registro local de eventos técnicos (WC-23). Guarda los últimos [capacity] eventos de los últimos [retentionMs] y los
 * expone del más reciente al más antiguo. Los valores que no cumplen el formato de [DiagnosticEvent] se reemplazan por
 * marcadores (`INVALID_CODE`, `invalid.operation`): el registro nunca rompe la operación que lo llama.
 */
class Diagnostics(
    private val storage: DiagnosticsStorage,
    appVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val retentionMs: Long = DEFAULT_RETENTION_MS,
) : DiagnosticsRecorder {
    private val appVersion = appVersion.takeIf { DiagnosticEvent.APP_VERSION.matches(it) } ?: DiagnosticEvent.UNKNOWN_VERSION
    private val mutex = Mutex()
    private val _events = MutableStateFlow<List<DiagnosticEvent>>(emptyList())

    /** Del más reciente al más antiguo. */
    val events: StateFlow<List<DiagnosticEvent>> = _events.asStateFlow()

    /** Lee lo guardado y lo combina con lo registrado mientras tanto. Un archivo ilegible se descarta. */
    suspend fun load() = mutex.withLock {
        val saved = try {
            storage.read()?.let { json.decodeFromString(serializer, it) }.orEmpty()
        } catch (_: SerializationException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList() // un evento guardado que ya no cumple el formato
        } catch (_: IOException) {
            emptyList()
        }
        _events.value = trim(_events.value + saved)
    }

    override suspend fun record(
        module: DiagnosticModule,
        operation: String,
        correlationId: String,
        outcome: Outcome,
        code: String,
        durationMs: Long,
    ) {
        val event = DiagnosticEvent(
            at = clock(),
            module = module,
            appVersion = appVersion,
            operation = operation.takeIf { DiagnosticEvent.OPERATION.matches(it) } ?: DiagnosticEvent.INVALID_OPERATION,
            correlationId = correlationId.takeIf { DiagnosticEvent.CORRELATION_ID.matches(it) }
                ?: DiagnosticEvent.newCorrelationId(),
            outcome = outcome,
            code = code.takeIf { DiagnosticEvent.CODE.matches(it) } ?: DiagnosticEvent.INVALID_CODE,
            durationMs = durationMs.coerceAtLeast(0),
        )
        mutex.withLock {
            _events.value = trim(listOf(event) + _events.value)
            persist()
        }
    }

    /** Borra el registro local (07-security-and-privacy.md §5). */
    suspend fun clear() = mutex.withLock {
        _events.value = emptyList()
        persist()
    }

    private fun trim(events: List<DiagnosticEvent>): List<DiagnosticEvent> {
        val oldest = clock() - retentionMs
        return events.filter { it.at >= oldest }.sortedByDescending { it.at }.take(capacity)
    }

    private suspend fun persist() {
        try {
            storage.write(json.encodeToString(serializer, _events.value))
        } catch (_: IOException) {
            // Sin espacio o sin permiso: el registro sigue en memoria; perderlo no debe afectar a la app.
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 200
        const val DEFAULT_RETENTION_MS = 7L * 24 * 60 * 60 * 1000

        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = ListSerializer(DiagnosticEvent.serializer())
    }
}
