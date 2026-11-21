package mx.watchcompanion.core.diagnostics

import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
enum class DiagnosticModule { LINK, VELORA, NEXORA }

@Serializable
enum class Outcome { OK, ERROR }

/**
 * Un evento técnico (00-architecture.md §8). Solo tiene los campos que permite 07-security-and-privacy.md §4, y
 * ninguno acepta texto libre: la operación son palabras en minúsculas, el código es MAYÚSCULAS con dígitos y el
 * correlation ID es un UUID generado aquí. Un token, un correo, unas coordenadas o un importe no caben en ningún campo.
 *
 * Agregar un campo es cambiar la política de privacidad: `DiagnosticEventTest` falla a propósito.
 */
@Serializable
data class DiagnosticEvent(
    val at: Long,
    val module: DiagnosticModule,
    val appVersion: String,
    val operation: String,
    val correlationId: String,
    val outcome: Outcome,
    val code: String,
    val durationMs: Long,
) {
    init {
        require(OPERATION.matches(operation)) { "operación inválida" }
        require(CODE.matches(code)) { "código inválido" }
        require(CORRELATION_ID.matches(correlationId)) { "correlation ID inválido" }
        require(APP_VERSION.matches(appVersion)) { "versión inválida" }
        require(durationMs >= 0)
    }

    companion object {
        /** `velora.login`, `link.ping`, `velora.listActivities`: módulo y operación, sin dígitos. */
        val OPERATION = Regex("[a-z]{1,16}(\\.[a-zA-Z]{1,32}){1,2}")

        /** `OK`, `HTTP_503`, `UNREACHABLE`. */
        val CODE = Regex("[A-Z][A-Z0-9_]{0,31}")

        /** UUID en minúsculas; también es válido como `X-Request-Id` de Velora (8–64 caracteres). */
        val CORRELATION_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        /** `versionName` de la app: `0.1.0`, `0.1.0-debug`. */
        val APP_VERSION = Regex("[0-9A-Za-z.+_-]{1,32}")

        const val INVALID_CODE = "INVALID_CODE"
        const val INVALID_OPERATION = "invalid.operation"
        const val UNKNOWN_VERSION = "unknown"

        fun newCorrelationId(): String = UUID.randomUUID().toString()
    }
}

/** Estado de sincronización de un módulo, derivado de sus eventos. */
data class SyncStatus(
    val module: DiagnosticModule,
    val lastAttemptAt: Long,
    val lastSuccessAt: Long?,
    /** Último error si ocurrió después del último éxito; `null` si el módulo está al día. */
    val pendingError: DiagnosticEvent?,
) {
    companion object {
        /** Un estado por módulo con eventos, en el orden de [DiagnosticModule]. [events] puede venir en cualquier orden. */
        fun from(events: List<DiagnosticEvent>): List<SyncStatus> =
            events.groupBy { it.module }.toSortedMap().map { (module, moduleEvents) ->
                val lastSuccess = moduleEvents.filter { it.outcome == Outcome.OK }.maxOfOrNull { it.at }
                val lastError = moduleEvents.filter { it.outcome == Outcome.ERROR }.maxByOrNull { it.at }
                SyncStatus(
                    module = module,
                    lastAttemptAt = moduleEvents.maxOf { it.at },
                    lastSuccessAt = lastSuccess,
                    pendingError = lastError?.takeIf { lastSuccess == null || it.at > lastSuccess },
                )
            }
    }
}
