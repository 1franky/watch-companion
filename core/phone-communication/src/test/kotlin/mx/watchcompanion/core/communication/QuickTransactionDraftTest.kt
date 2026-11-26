package mx.watchcompanion.core.communication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickTransactionDraftTest {
    private val borrador = QuickTransactionDraft(
        idempotencyKey = "3d6f0a7e-1b2c-4d5e-8f90-a1b2c3d4e5f6",
        kind = QuickTransactionDraft.Kind.EXPENSE,
        amount = "250.50",
        accountId = "5b1d6a3e-0c1f-4a2b-9d3e-7f8a9b0c1d2e",
        date = "2026-11-25",
        capturedAt = 0,
    )

    @Test
    fun soloImportesPositivosConDosDecimales() {
        for (malo in listOf("0", "0.00", "-5", "1.234", "1,000", "abc")) {
            assertThrows(malo, IllegalArgumentException::class.java) { borrador.copy(amount = malo) }
        }
        assertEquals("0.01", borrador.copy(amount = "0.01").amount)
    }

    @Test
    fun clavesYCuentasSonUuid() {
        assertThrows(IllegalArgumentException::class.java) { borrador.copy(idempotencyKey = "1") }
        assertThrows(IllegalArgumentException::class.java) { borrador.copy(categoryId = "comida") }
    }

    @Test
    fun laNotaTieneLimite() {
        assertThrows(IllegalArgumentException::class.java) { borrador.copy(note = "x".repeat(QuickTransactionDraft.MAX_NOTE + 1)) }
    }

    @Test
    fun soloCreadoYDescartadoSonFinales() {
        val finales = DraftStatus.State.entries.filter { it.final }
        assertEquals(listOf(DraftStatus.State.CREATED, DraftStatus.State.DISCARDED), finales)
        assertFalse(DraftStatus.State.UNKNOWN.final)
        assertTrue(DraftStatus.State.CREATED.final)
    }

    @Test
    fun suTextoNoRevelaElImporteNiLaNota() {
        val texto = borrador.copy(note = "Cena aniversario").toString()
        assertFalse(texto, texto.contains("250") || texto.contains("Cena"))
    }
}
