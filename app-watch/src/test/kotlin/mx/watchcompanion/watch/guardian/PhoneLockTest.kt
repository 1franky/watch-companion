package mx.watchcompanion.watch.guardian

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mx.watchcompanion.core.communication.GuardianCommand
import mx.watchcompanion.core.communication.GuardianCommandResult
import mx.watchcompanion.core.communication.GuardianCommandResult.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WC-44: el reloj nunca dice «bloqueado» sin la confirmación del teléfono. */
class PhoneLockTest {
    private val ids = ArrayDeque(listOf("0b9f2c4e-5d6a-4f1b-8c7d-9e0f1a2b3c4d", "1c0a3d5f-6e7b-4a2c-9d8e-0f1a2b3c4d5e"))
    private val sent = mutableListOf<GuardianCommand>()

    private fun lock(reachable: Boolean = true, clock: () -> Long) = PhoneLock(
        send = { sent += it; reachable },
        clock = clock,
        newId = { ids.removeFirst() },
    )

    @Test
    fun conLaConfirmacionQuedaBloqueado() = runTest {
        val lock = lock { testScheduler.currentTime }
        val job = launch { lock.lock() }
        runCurrent()
        assertEquals(PhoneLockState.Sending(sent.single().commandId), lock.state.value)

        lock.onResult(GuardianCommandResult(sent.single().commandId, Outcome.LOCKED))
        job.join()

        assertEquals(Outcome.LOCKED, (lock.state.value as PhoneLockState.Done).result.outcome)
    }

    @Test
    fun sinRespuestaAntesDeCaducarEsSinConfirmacion() = runTest {
        val lock = lock { testScheduler.currentTime }
        launch { lock.lock() }
        advanceTimeBy(GuardianCommand.TTL_MS + PhoneLock.REPLY_MARGIN_MS - 1)
        assertTrue(lock.state.value is PhoneLockState.Sending)

        advanceTimeBy(2)

        assertEquals(Outcome.EXPIRED, (lock.state.value as PhoneLockState.Done).result.outcome)
    }

    @Test
    fun unaConfirmacionTardiaCorrigeElSinConfirmacion() = runTest {
        val lock = lock { testScheduler.currentTime }
        lock.lock()
        val id = sent.single().commandId
        assertEquals(Outcome.EXPIRED, (lock.state.value as PhoneLockState.Done).result.outcome)

        lock.onResult(GuardianCommandResult(id, Outcome.LOCKED))

        assertEquals(Outcome.LOCKED, (lock.state.value as PhoneLockState.Done).result.outcome)
    }

    @Test
    fun sinTelefonoNoSeBloqueaYLoDice() = runTest {
        val lock = lock(reachable = false) { testScheduler.currentTime }

        lock.lock()

        assertEquals(GuardianCommandResult(sent.single().commandId, Outcome.UNAVAILABLE, PhoneLock.NO_PHONE), (lock.state.value as PhoneLockState.Done).result)
    }

    @Test
    fun unSegundoToqueMientrasEsperaNoMandaOtraOrden() = runTest {
        val lock = lock { testScheduler.currentTime }
        val first = launch { lock.lock() }
        runCurrent()

        lock.lock()

        assertEquals(1, sent.size)
        lock.onResult(GuardianCommandResult(sent.single().commandId, Outcome.LOCKED))
        first.join()
    }

    @Test
    fun soloCuentaLaRespuestaDeLaUltimaOrden() = runTest {
        val lock = lock { testScheduler.currentTime }
        lock.lock() // la primera caduca sin respuesta
        val viejo = sent.single().commandId
        val job = launch { lock.lock() }
        runCurrent()

        lock.onResult(GuardianCommandResult(viejo, Outcome.LOCKED))
        assertTrue(lock.state.value is PhoneLockState.Sending)

        lock.onResult(GuardianCommandResult(sent.last().commandId, Outcome.UNAVAILABLE, "ADMIN_INACTIVE"))
        job.join()
        assertEquals(Outcome.UNAVAILABLE, (lock.state.value as PhoneLockState.Done).result.outcome)
    }
}
