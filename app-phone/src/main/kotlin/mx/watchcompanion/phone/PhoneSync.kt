package mx.watchcompanion.phone

import mx.watchcompanion.core.communication.IntegrationStatus
import mx.watchcompanion.core.communication.SyncSummary
import mx.watchcompanion.core.diagnostics.DiagnosticEvent
import mx.watchcompanion.core.diagnostics.DiagnosticModule
import mx.watchcompanion.core.diagnostics.SyncStatus
import mx.watchcompanion.integration.velora.SessionState
import mx.watchcompanion.integration.velora.SignOutReason

/**
 * Estado de sincronización de Velora que el teléfono informa al reloj en cada Pong (WC-29). Sale de la sesión propia
 * de app-phone (ADR-013) y del registro de diagnóstico (WC-23); por eso solo lleva el estado, instantes y códigos.
 *
 * Mientras se lee la sesión guardada no se informa nada: el reloj lo muestra como «sin datos», no como un error.
 */
fun veloraSyncSummary(session: SessionState, events: List<DiagnosticEvent>): List<SyncSummary> {
    val state = when (session) {
        SessionState.Restoring -> return emptyList()
        is SessionState.SignedOut ->
            if (session.reason == SignOutReason.EXPIRED) SyncSummary.State.SESSION_EXPIRED else SyncSummary.State.SIGNED_OUT
        is SessionState.SignedIn -> null
    }
    if (state != null) return listOf(SyncSummary(VELORA, state))

    val status = SyncStatus.from(events).firstOrNull { it.module == DiagnosticModule.VELORA }
    val summary = when {
        status == null || (status.lastSuccessAt == null && status.pendingError == null) ->
            SyncSummary(VELORA, SyncSummary.State.NEVER_SYNCED)
        status.pendingError != null ->
            SyncSummary(VELORA, SyncSummary.State.ERROR, status.lastSuccessAt, status.pendingError?.code)
        else -> SyncSummary(VELORA, SyncSummary.State.SYNCED, status.lastSuccessAt)
    }
    return listOf(summary)
}

private val VELORA = IntegrationStatus.Integration.VELORA
