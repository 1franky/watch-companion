package mx.watchcompanion.phone

import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import mx.watchcompanion.core.communication.FinanceFigures
import mx.watchcompanion.core.communication.FinanceSnapshot
import mx.watchcompanion.core.communication.RecentTransaction
import mx.watchcompanion.core.communication.UpcomingPayment
import mx.watchcompanion.core.session.SessionExpiredException
import mx.watchcompanion.core.session.SessionState
import mx.watchcompanion.integration.nexora.api.models.DashboardResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PhoneFinanceTest {
    private val conSesion = SessionState.SignedIn("https://nexora.example", "usuario@example.com")
    private var now = 1_000L
    private var session: SessionState = conSesion
    private var fetch: suspend (Long) -> FinanceFigures = { cifras(it) }

    private fun cifras(at: Long) = FinanceFigures(at, "2026-11", "15230.50", "4800.00", "9120.75")

    private fun finance() = PhoneFinance(session = { session }, fetch = { fetch(it) }, clock = { now })

    @Test
    fun conSesionConsultaYGuardaLasCifrasConSuHora() = runTest {
        val finance = finance()
        val snapshot = finance.refresh()!!
        assertEquals(FinanceSnapshot.State.OK, snapshot.state)
        assertEquals(cifras(1_000), snapshot.figures)
        assertEquals(snapshot, finance.snapshot.value)
    }

    @Test
    fun unFalloConservaLasUltimasCifrasConSuHoraOriginalYNoSimulaExito() = runTest {
        val finance = finance()
        finance.refresh()
        now = 600_000
        fetch = { throw IOException("sin red") }
        val snapshot = finance.refresh()!!
        assertEquals(FinanceSnapshot.State.ERROR, snapshot.state)
        assertEquals("UNREACHABLE", snapshot.errorCode)
        assertEquals(1_000L, snapshot.figures?.fetchedAt)
        assertEquals(600_000L, snapshot.checkedAt)
        assert(snapshot.isStale(now))
    }

    @Test
    fun cadaFalloTieneSuCodigo() = runTest {
        val casos = mapOf<Exception, String>(
            NexoraHttpException(503) to "HTTP_503",
            SerializationException("enum nuevo") to "UNREADABLE_RESPONSE",
            IncompleteDashboardException() to "INCOMPLETE_RESPONSE",
        )
        for ((error, codigo) in casos) {
            fetch = { throw error }
            assertEquals(codigo, finance().refresh()?.errorCode)
        }
    }

    @Test
    fun sinSesionNoHayCifrasNiConsulta() = runTest {
        var consultas = 0
        fetch = { consultas++; cifras(it) }
        val finance = finance()
        finance.refresh()
        session = SessionState.SignedOut()
        val snapshot = finance.refresh()!!
        assertEquals(FinanceSnapshot.State.SIGNED_OUT, snapshot.state)
        assertNull(snapshot.figures)
        assertEquals(1, consultas)
    }

    @Test
    fun siLaSesionVencioDuranteLaConsultaQuedaSinSesion() = runTest {
        fetch = { throw SessionExpiredException("Nexora") }
        assertEquals(FinanceSnapshot.State.SIGNED_OUT, finance().refresh()?.state)
    }

    @Test
    fun mientrasSeRestauraLaSesionNoInformaNada() = runTest {
        session = SessionState.Restoring
        assertNull(finance().refresh())
    }

    @Test
    fun soloRefrescaSiLoGuardadoEsViejo() = runTest {
        val finance = finance()
        assertEquals(FinanceSnapshot.State.OK, finance.refreshIfStale()?.state)
        now += PhoneFinance.MAX_AGE_MS - 1
        assertNull(finance.refreshIfStale())
        now += 1
        assertEquals(now, finance.refreshIfStale()?.checkedAt)
    }

    @Test
    fun tomaLasCifrasTalComoVienenDeNexora() {
        val body = DashboardResponse(month = "2026-11", availableBalance = 15230.5, creditCardDebt = 4800.0, expenseThisMonth = 9120.75)
        assertEquals(cifras(5), financeFigures(body, fetchedAt = 5))
    }

    @Test
    fun unaCifraQueFaltaEsUnErrorNoUnCero() {
        val body = DashboardResponse(month = "2026-11", availableBalance = 15230.5, expenseThisMonth = 9120.75)
        assertThrows(IncompleteDashboardException::class.java) { financeFigures(body, fetchedAt = 5) }
    }

    private val dashboard = """
        {"month":"2026-11","availableBalance":15230.5,"creditCardDebt":4800,"expenseThisMonth":9120.75,
         "upcomingPayments":[
           {"creditCardId":"00000000-0000-0000-0000-000000000009","creditCardName":"Oro","dueDate":"2026-12-02","expectedPayment":900},
           {"creditCardName":"Azul 4152313412345678","dueDate":"2026-11-28","expectedPayment":1500.5},
           {"creditCardName":"Sin fecha"}],
         "recentTransactions":[
           {"date":"2026-11-24","merchant":"Oxxo","description":"Botana","amount":85,"balanceEffect":-85,"type":"EXPENSE",
            "status":"POSTED","reference":"REF-99887766"},
           {"date":"2026-11-23","description":"Transferencia a 012180001234569012","amount":500,"balanceEffect":-500,
            "type":"TRANSFER","status":"POSTED"},
           {"date":"2026-11-22","description":"Algo","amount":10,"balanceEffect":-10,"type":"TIPO_NUEVO","status":"POSTED"},
           {"date":"2026-11-21","merchant":"Anulado","amount":10,"balanceEffect":-10,"type":"EXPENSE","status":"VOIDED"},
           {"date":"2026-11-20","description":"Sueldo","amount":20000,"balanceEffect":20000,"type":"INCOME","status":"POSTED"}],
         "campoNuevo":true}
    """.trimIndent()

    @Test
    fun proximosPagosYMovimientosSinNumerosNiReferenciasYUnTipoNuevoNoTumbaLasCifras() {
        val (body, recent) = parseDashboard(dashboard)
        val figures = financeFigures(body, fetchedAt = 5, recent = recent)

        assertEquals("15230.50", figures.availableBalance)
        assertEquals(
            listOf(UpcomingPayment("Azul ••5678", "2026-11-28", "1500.50"), UpcomingPayment("Oro", "2026-12-02", "900.00")),
            figures.upcomingPayments,
        )
        assertEquals(
            listOf(
                RecentTransaction("2026-11-24", "Oxxo", "-85.00", "EXPENSE"),
                RecentTransaction("2026-11-23", "Transferencia a ••9012", "-500.00", "TRANSFER"),
                RecentTransaction("2026-11-20", "Sueldo", "20000.00", "INCOME"),
            ),
            figures.recentTransactions,
        )
        assert(!figures.toString().contains("REF-99887766"))
    }
}
