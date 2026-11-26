package mx.watchcompanion.watch.guardian

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisconnectMonitorTest {
    private class MemoriaStore : DisconnectStore {
        override var delayMinutes: Int? = 5
        override var state = DisconnectState()
    }

    private val store = MemoriaStore()
    private val programadas = mutableListOf<Long>()
    private var canceladas = 0
    private val avisos = mutableListOf<Long>()
    private var retirados = 0
    private var ahora = 1_000_000L
    private val eventos = mutableListOf<kotlin.Pair<GuardianEvent.Kind, Long>>()

    private val monitor = DisconnectMonitor(
        store,
        object : DisconnectScheduler {
            override fun schedule(delayMs: Long) { programadas += delayMs }
            override fun cancel() { canceladas++ }
        },
        object : DisconnectAlerter {
            override fun alert(since: Long) { avisos += since }
            override fun clear() { retirados++ }
        },
        clock = { ahora },
        onEvent = { kind, at -> eventos += kind to at },
    )

    private val cincoMin = 5 * DisconnectMonitor.MINUTE_MS

    @Test
    fun avisaUnaSolaVezTrasElPlazoSinTelefono() {
        val inicio = ahora
        monitor.onReachability(nearby = false)
        assertEquals(listOf(cincoMin), programadas)
        ahora += cincoMin
        monitor.onCheck(nearby = false)
        assertEquals(listOf(inicio), avisos)
        assertTrue(monitor.state.value.alerted)
        monitor.onReachability(nearby = false)
        monitor.onCheck(nearby = false)
        assertEquals("No repite el aviso en la misma desconexión", 1, avisos.size)
    }

    @Test
    fun siVuelveAntesDelPlazoNoAvisa() {
        monitor.onReachability(nearby = false)
        ahora += cincoMin / 2
        monitor.onReachability(nearby = true)
        assertEquals(DisconnectState(), monitor.state.value)
        assertTrue(canceladas > 0)
        ahora += cincoMin
        monitor.onCheck(nearby = true)
        assertTrue(avisos.isEmpty())
    }

    @Test
    fun unaRevisionTempranaSeReprogramaPorLoQueFalta() {
        monitor.onReachability(nearby = false)
        ahora += 2 * DisconnectMonitor.MINUTE_MS
        monitor.onCheck(nearby = false)
        assertTrue(avisos.isEmpty())
        assertEquals(3 * DisconnectMonitor.MINUTE_MS, programadas.last())
    }

    @Test
    fun alVolverElTelefonoSeRetiraElAvisoYLaSiguienteDesconexionAvisaDeNuevo() {
        monitor.onReachability(nearby = false)
        ahora += cincoMin
        monitor.onCheck(nearby = false)
        monitor.onReachability(nearby = true)
        assertEquals(1, retirados)
        monitor.onReachability(nearby = false)
        ahora += cincoMin
        monitor.onCheck(nearby = false)
        assertEquals(2, avisos.size)
    }

    @Test
    fun apagadoNoCuentaNiAvisaYApagarloRetiraElAviso() {
        monitor.onReachability(nearby = false)
        ahora += cincoMin
        monitor.onCheck(nearby = false)
        monitor.setDelay(null)
        assertEquals(1, retirados)
        assertNull(store.delayMinutes)
        monitor.onReachability(nearby = false)
        monitor.onCheck(nearby = false)
        assertEquals(DisconnectState(), monitor.state.value)
        assertEquals(1, avisos.size)
    }

    @Test
    fun siSeActivaYaSinTelefonoCuentaDesdeLaRevision() {
        store.delayMinutes = null
        monitor.onReachability(nearby = false)
        monitor.setDelay(2)
        monitor.onCheck(nearby = false)
        assertEquals(ahora, monitor.state.value.since)
        assertEquals(2 * DisconnectMonitor.MINUTE_MS, programadas.last())
    }

    @Test
    fun elBotonRecorreLosPlazos() {
        val vistos = mutableListOf<Int?>(null)
        repeat(5) { vistos += DisconnectMonitor.next(vistos.last()) }
        assertEquals(listOf(null, 2, 5, 15, 30, null), vistos)
    }

    @Test
    fun elHistorialRegistraElAvisoYElRegresoPeroNoElApagado() {
        monitor.onReachability(nearby = false)
        ahora += cincoMin
        monitor.onCheck(nearby = false)
        val avisado = ahora
        ahora += 60_000
        monitor.onReachability(nearby = true)
        assertEquals(listOf(GuardianEvent.Kind.PHONE_AWAY to avisado, GuardianEvent.Kind.PHONE_BACK to ahora), eventos)

        // Apagar el aviso con uno a la vista no es «el teléfono volvió».
        monitor.onReachability(nearby = false)
        ahora += cincoMin
        monitor.onCheck(nearby = false)
        monitor.setDelay(null)
        assertEquals(listOf(GuardianEvent.Kind.PHONE_AWAY, GuardianEvent.Kind.PHONE_BACK, GuardianEvent.Kind.PHONE_AWAY), eventos.map { it.first })
    }

    @Test
    fun sinAvisoPrevioElRegresoNoSeRegistra() {
        monitor.onReachability(nearby = false)
        monitor.onReachability(nearby = true)
        assertTrue(eventos.isEmpty())
    }
}
