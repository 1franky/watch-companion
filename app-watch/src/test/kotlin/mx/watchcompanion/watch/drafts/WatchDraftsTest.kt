package mx.watchcompanion.watch.drafts

import kotlinx.coroutines.test.runTest
import mx.watchcompanion.core.communication.DraftStatus
import mx.watchcompanion.core.communication.QuickTransactionDraft
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchDraftsTest {
    private val enviados = mutableListOf<String>()
    private var hayTelefono = true
    private val drafts = WatchDrafts(send = { enviados += it.idempotencyKey; hayTelefono }, keep = 2)

    private fun borrador(n: Int) = QuickTransactionDraft(
        "00000000-0000-0000-0000-00000000000$n", QuickTransactionDraft.Kind.EXPENSE, "10",
        "5b1d6a3e-0c1f-4a2b-9d3e-7f8a9b0c1d2e", date = "2026-11-25", capturedAt = n.toLong(),
    )

    private fun estado(n: Int, state: DraftStatus.State, at: Long) = DraftStatus(borrador(n).idempotencyKey, state, at)

    @Test
    fun seReenviaHastaQueElTelefonoAcusa() = runTest {
        hayTelefono = false
        drafts.submit(borrador(1))
        drafts.resendUnacknowledged()
        assertEquals(2, enviados.size)

        drafts.onStatus(listOf(estado(1, DraftStatus.State.RECEIVED, 5)))
        drafts.resendUnacknowledged()
        assertEquals(2, enviados.size)
    }

    @Test
    fun unEstadoViejoNoPisaUnoNuevo() = runTest {
        drafts.submit(borrador(1))
        drafts.onStatus(listOf(estado(1, DraftStatus.State.CREATED, 10)))
        drafts.onStatus(listOf(estado(1, DraftStatus.State.RECEIVED, 5)))
        assertEquals(DraftStatus.State.CREATED, drafts.drafts.value.single().status?.state)
    }

    @Test
    fun soloSeOlvidanLosTerminadosNuncaUnoPendiente() = runTest {
        drafts.submit(borrador(1))
        drafts.submit(borrador(2))
        drafts.submit(borrador(3))
        assertEquals("Ninguno terminado: se quedan todos", 3, drafts.drafts.value.size)

        drafts.onStatus(listOf(estado(1, DraftStatus.State.CREATED, 10)))
        drafts.submit(borrador(4))
        assertEquals(listOf(4, 3, 2).map { borrador(it).idempotencyKey }, drafts.drafts.value.map { it.draft.idempotencyKey })
    }
}
