package mx.watchcompanion.phone.alerts

import java.io.IOException
import kotlinx.coroutines.test.runTest
import mx.watchcompanion.core.session.SessionExpiredException
import mx.watchcompanion.phone.NexoraHttpException
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NexoraAlertsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun aviso(id: Int, type: String, status: String = "UNREAD") =
        """{"id":"00000000-0000-0000-0000-00000000000$id","type":"$type","status":"$status","title":"Pago de tarjeta",
           "message":"Tu tarjeta Oro vence hoy. Pago estimado: $1234.50.","createdAt":"2026-11-25T10:00:00Z"}"""

    @Test
    fun soloLosTiposDelRelojYUnTipoNuevoNoTumbaALosDemas() {
        val bandeja = "[" + listOf(
            aviso(1, "PAYMENT_DUE"),
            aviso(2, "PAYMENT_DUE_SOON"),
            aviso(3, "INSTALLMENT_DUE"),
            aviso(4, "SCHEDULED_CHARGE_FAILED"),
            aviso(5, "BUDGET_NEAR_LIMIT"),
            aviso(6, "BUDGET_EXCEEDED"),
            aviso(7, "UNUSUAL_EXPENSE"),
            aviso(8, "TIPO_QUE_AUN_NO_EXISTE"),
            aviso(9, "PAYMENT_DUE", status = "READ"),
        ).joinToString(",") + "]"
        assertEquals(
            listOf(
                AlertKind.PAYMENT_DUE,
                AlertKind.PAYMENT_DUE_SOON,
                AlertKind.INSTALLMENT_DUE,
                AlertKind.SCHEDULED_CHARGE_FAILED,
                AlertKind.BUDGET_NEAR_LIMIT,
                AlertKind.BUDGET_EXCEEDED,
            ),
            parseAlerts(bandeja).map { it.kind },
        )
    }

    private val uno = NexoraAlert("00000000-0000-0000-0000-000000000001", AlertKind.PAYMENT_DUE)
    private val dos = NexoraAlert("00000000-0000-0000-0000-000000000002", AlertKind.INSTALLMENT_DUE)

    @Test
    fun unAvisoYaMostradoNoSeRepite() = runTest {
        val publicados = mutableListOf<NexoraAlert>()
        val shown = ShownAlerts(tmp.newFile("mostrados.json").also { it.delete() })
        var bandeja = listOf(uno)
        val poller = NexoraAlertPoller({ bandeja }, shown) { publicados += it; true }

        assertEquals(NexoraAlertPoller.Outcome.DONE, poller.poll())
        bandeja = listOf(uno, dos)
        poller.poll()
        assertEquals(listOf(uno, dos), publicados)
    }

    @Test
    fun sinPermisoNoSeDaPorMostradoYSaleEnLaProximaPasada() = runTest {
        val publicados = mutableListOf<NexoraAlert>()
        var permiso = false
        val poller = NexoraAlertPoller({ listOf(uno) }, ShownAlerts(tmp.root.resolve("m.json"))) {
            if (permiso) publicados += it
            permiso
        }
        poller.poll()
        permiso = true
        poller.poll()
        poller.poll()
        assertEquals(listOf(uno), publicados)
    }

    @Test
    fun cadaFalloTieneSuSalida() = runTest {
        val shown = ShownAlerts(tmp.root.resolve("m.json"))
        val casos = mapOf<Exception, NexoraAlertPoller.Outcome>(
            IOException("sin red") to NexoraAlertPoller.Outcome.RETRY,
            NexoraHttpException(503) to NexoraAlertPoller.Outcome.RETRY,
            SessionExpiredException("Nexora") to NexoraAlertPoller.Outcome.SIGNED_OUT,
        )
        for ((error, salida) in casos) {
            assertEquals(salida, NexoraAlertPoller({ throw error }, shown) { true }.poll())
        }
    }

    @Test
    fun soloSeRecuerdanLosUltimos() = runTest {
        val shown = ShownAlerts(tmp.root.resolve("m.json"), max = 3)
        shown.add(listOf("a", "b"))
        shown.add(listOf("b", "c", "d"))
        assertEquals(listOf("b", "c", "d"), shown.read())
    }
}
