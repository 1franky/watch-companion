package mx.watchcompanion.core.communication

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
