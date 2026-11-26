package mx.watchcompanion.watch.guardian

import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mx.watchcompanion.core.communication.GuardianCommand
import mx.watchcompanion.core.communication.GuardianCommandResult
import mx.watchcompanion.core.communication.GuardianCommandResult.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GuardianModuleTest {
    @get:Rule val tmp = TemporaryFolder()

    private class MemoriaSettings : GuardianSettingsStore {
        override var enabled = true
        override var retentionDays = 7
    }

    private class MemoriaStore : DisconnectStore {
        override var delayMinutes: Int? = 5
        override var state = DisconnectState()
    }

    private val settings = MemoriaSettings()
    private val disconnectStore = MemoriaStore()
    private val monitor = DisconnectMonitor(
        disconnectStore,
        object : DisconnectScheduler {
            override fun schedule(delayMs: Long) {}
            override fun cancel() {}
        },
        object : DisconnectAlerter {
            override fun alert(since: Long) {}
            override fun clear() {}
        },
    )
    private val history by lazy { GuardianHistory(File(tmp.root, "h.json"), retentionDays = { settings.retentionDays }) }

    private fun module(revoke: suspend () -> GuardianCommandResult? = { null }) = GuardianModule(settings, history, monitor, revoke)

    private val evento get() = GuardianEvent(System.currentTimeMillis(), GuardianEvent.Kind.PHONE_AWAY)

    @Test
    fun desactivarConUnToqueApagaElAvisoYConservaElHistorial() = runTest {
        val m = module()
        m.record(evento)

        m.setEnabled(false)

        assertFalse(settings.enabled)
        assertNull(disconnectStore.delayMinutes)
        assertEquals(1, history.events.value.size)
    }

    @Test
    fun desactivadoNoRegistra() = runTest {
        val m = module()
        m.setEnabled(false)
        m.record(evento)
        assertTrue(history.events.value.isEmpty())
    }

    @Test
    fun revocarConConfirmacionDelTelefono() = runTest {
        val m = module { GuardianCommandResult("id", Outcome.REVOKED) }
        m.record(evento)

        m.revoke()

        assertEquals(RevokeState.Done(phoneConfirmed = true), m.revoke.value)
        assertFalse(m.enabled.value)
        assertTrue(history.events.value.isEmpty())
        assertNull(disconnectStore.delayMinutes)
    }

    @Test
    fun sinRespuestaDelTelefonoSeRevocaIgualEnElRelojYLoDice() = runTest {
        val m = module { null }
        m.record(evento)

        m.revoke()

        assertEquals(RevokeState.Done(phoneConfirmed = false), m.revoke.value)
        assertTrue(history.events.value.isEmpty())
    }

    @Test
    fun cambiarLaRetencionRecorta() = runTest {
        val m = module()
        m.record(GuardianEvent(System.currentTimeMillis() - 3 * GuardianHistory.DAY_MS, GuardianEvent.Kind.PHONE_AWAY))

        m.setRetention(1)

        assertEquals(1, settings.retentionDays)
        assertTrue(history.events.value.isEmpty())
    }

    @Test
    fun elEsperadorDevuelveLaRespuestaDeSuOrden() = runTest {
        val enviadas = mutableListOf<GuardianCommand>()
        val waiter = GuardianCommandWaiter(send = { enviadas += it; true }, newId = { "orden-1" })
        var resultado: GuardianCommandResult? = null
        val job = launch { resultado = waiter.request(GuardianCommand.Action.REVOKE) }
        runCurrent()

        waiter.onResult(GuardianCommandResult("otra", Outcome.REVOKED))
        waiter.onResult(GuardianCommandResult("orden-1", Outcome.REVOKED))
        job.join()

        assertEquals(GuardianCommand.Action.REVOKE, enviadas.single().action)
        assertEquals("orden-1", resultado?.commandId)
    }

    @Test
    fun elEsperadorSinTelefonoOSinRespuestaDevuelveNull() = runTest {
        assertNull(GuardianCommandWaiter(send = { false }).request(GuardianCommand.Action.REVOKE))

        var resultado: GuardianCommandResult? = GuardianCommandResult("x", Outcome.REVOKED)
        launch { resultado = GuardianCommandWaiter(send = { true }).request(GuardianCommand.Action.REVOKE) }
        advanceTimeBy(GuardianCommand.TTL_MS + PhoneLock.REPLY_MARGIN_MS + 1)
        assertNull(resultado)
    }
}
