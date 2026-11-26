package mx.watchcompanion.phone

import mx.watchcompanion.core.communication.IntegrationStatus.Integration.VELORA
import mx.watchcompanion.core.communication.SyncSummary
import mx.watchcompanion.core.diagnostics.DiagnosticEvent
import mx.watchcompanion.core.diagnostics.DiagnosticModule
import mx.watchcompanion.core.diagnostics.Outcome
import mx.watchcompanion.core.session.SessionState
import mx.watchcompanion.core.session.SignOutReason
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneSyncTest {
    private val id = "6f1c3c62-5a0e-4a4e-9a51-0b2c8f8d2f10"
    private val conSesion = SessionState.SignedIn("https://velora.example", "ciclista@example.com")

    private fun event(at: Long, module: DiagnosticModule, outcome: Outcome, code: String) =
        DiagnosticEvent(at, module, "0.1.0", "velora.refresh", id, outcome, code, durationMs = 10)

    @Test
    fun mientrasRestauraNoInformaNada() {
        assertEquals(emptyList<SyncSummary>(), veloraSyncSummary(SessionState.Restoring, emptyList()))
    }

    @Test
    fun sinSesionLoDice() {
        assertEquals(listOf(SyncSummary(VELORA, SyncSummary.State.SIGNED_OUT)), veloraSyncSummary(SessionState.SignedOut(), emptyList()))
    }

    @Test
    fun conLaSesionVencidaPideVolverAEntrar() {
        assertEquals(
            listOf(SyncSummary(VELORA, SyncSummary.State.SESSION_EXPIRED)),
            veloraSyncSummary(SessionState.SignedOut(SignOutReason.EXPIRED), emptyList()),
        )
    }

    @Test
    fun conSesionYSinContactoAunNoHaSincronizado() {
        val soloEnlace = listOf(event(1, DiagnosticModule.LINK, Outcome.OK, "CONNECTED"))
        assertEquals(listOf(SyncSummary(VELORA, SyncSummary.State.NEVER_SYNCED)), veloraSyncSummary(conSesion, soloEnlace))
    }

    @Test
    fun elUltimoExitoMarcaSincronizado() {
        val eventos = listOf(event(5, DiagnosticModule.VELORA, Outcome.OK, "OK"), event(3, DiagnosticModule.VELORA, Outcome.ERROR, "UNREACHABLE"))
        assertEquals(listOf(SyncSummary(VELORA, SyncSummary.State.SYNCED, lastSuccessAt = 5)), veloraSyncSummary(conSesion, eventos))
    }

    @Test
    fun unErrorPosteriorAlUltimoExitoSeInformaConSuCodigo() {
        val eventos = listOf(event(7, DiagnosticModule.VELORA, Outcome.ERROR, "HTTP_503"), event(5, DiagnosticModule.VELORA, Outcome.OK, "OK"))
        assertEquals(
            listOf(SyncSummary(VELORA, SyncSummary.State.ERROR, lastSuccessAt = 5, errorCode = "HTTP_503")),
            veloraSyncSummary(conSesion, eventos),
        )
    }
}
