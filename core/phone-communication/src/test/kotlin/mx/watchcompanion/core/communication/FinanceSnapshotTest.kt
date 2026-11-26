package mx.watchcompanion.core.communication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceSnapshotTest {
    private val cifras = FinanceFigures(fetchedAt = 0, month = "2026-11", availableBalance = "15230.50", creditCardDebt = "4800.00", expenseThisMonth = "9120.75")

    @Test
    fun recienConsultadoNoEstaDesactualizado() {
        val snapshot = FinanceSnapshot(FinanceSnapshot.State.OK, checkedAt = 0, figures = cifras)
        assertFalse(snapshot.isStale(now = FinanceSnapshot.STALE_AFTER_MS))
        assertTrue(snapshot.isStale(now = FinanceSnapshot.STALE_AFTER_MS + 1))
    }

    @Test
    fun siLaUltimaConsultaFallaLasCifrasAnterioresQuedanDesactualizadas() {
        val snapshot = FinanceSnapshot(FinanceSnapshot.State.ERROR, checkedAt = 60_000, figures = cifras, errorCode = "UNREACHABLE")
        assertTrue(snapshot.isStale(now = 60_000))
    }

    @Test
    fun sinSesionNoViajanCifras() {
        assertThrows(IllegalArgumentException::class.java) {
            FinanceSnapshot(FinanceSnapshot.State.SIGNED_OUT, checkedAt = 0, figures = cifras)
        }
        assertTrue(FinanceSnapshot(FinanceSnapshot.State.SIGNED_OUT, checkedAt = 0).isStale(now = 0))
    }

    @Test
    fun soloAceptaImportesDecimales() {
        assertThrows(IllegalArgumentException::class.java) { cifras.copy(availableBalance = "1,234.00") }
        assertThrows(IllegalArgumentException::class.java) { cifras.copy(creditCardDebt = "NaN") }
        assertThrows(IllegalArgumentException::class.java) { cifras.copy(month = "noviembre") }
        cifras.copy(availableBalance = "-120.5")
    }
}

class FinanceListsTest {
    @Test
    fun losNumerosLargosSeEnmascaranYLosCortosNo() {
        assertEquals("Pago ••5678", FinanceFigures.mask("Pago 1234 5678"))
        assertEquals("Transferencia ••9012", FinanceFigures.mask("Transferencia 012180001234569012"))
        assertEquals("Oxxo 24", FinanceFigures.mask("Oxxo 24"))
        assertEquals("Mensualidad 3/12", FinanceFigures.mask("Mensualidad 3/12"))
    }

    @Test
    fun unaEtiquetaSinEnmascararNoPuedeViajar() {
        assertThrows(IllegalArgumentException::class.java) {
            RecentTransaction("2026-11-24", "Tarjeta 4152313412345678", "-250.00", "EXPENSE")
        }
        assertThrows(IllegalArgumentException::class.java) { UpcomingPayment("Oro 4152313412345678", "2026-11-28", "1500.00") }
        RecentTransaction("2026-11-24", "Oxxo", "-250.00", "EXPENSE")
    }
}
