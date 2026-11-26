package mx.watchcompanion.core.communication

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** WC-44: la orden de bloqueo es sensible (nonce y caducidad) y su respuesta vuelve con lo que de verdad pasó. */
class GuardianCommandTest {
    private val commandId = "0b9f2c4e-5d6a-4f1b-8c7d-9e0f1a2b3c4d"
    private val lock = GuardianCommand(commandId, GuardianCommand.Action.LOCK)
    private val telefono = Peer("nodo-telefono", "Galaxy S26 Ultra", true)
    private val reloj = Peer("nodo-reloj", "Galaxy Watch Ultra", true)

    /** Lo que una sesión envía llega a la otra; guarda los bytes para repetirlos. */
    private class Loopback(private val peers: List<Peer>) : LinkTransport {
        var other: LinkSession? = null
        val sent = mutableListOf<ByteArray>()
        override suspend fun reachablePeers() = peers
        override suspend fun send(peerId: String, bytes: ByteArray) {
            sent += bytes
            other?.onMessage("self", bytes)
        }
    }

    private class Pair(val watch: LinkSession, val phone: LinkSession, val toPhone: Loopback, val results: MutableList<GuardianCommandResult>, val executed: MutableList<Long>)

    private fun TestScope.pair(
        execute: (suspend (GuardianCommand, Long) -> GuardianCommandResult?)? = { c, _ -> GuardianCommandResult(c.commandId, GuardianCommandResult.Outcome.LOCKED) },
        phoneClockOffset: Long = 0,
    ): Pair {
        val toPhone = Loopback(listOf(telefono))
        val toWatch = Loopback(listOf(reloj))
        val results = mutableListOf<GuardianCommandResult>()
        val executed = mutableListOf<Long>()
        val watch = LinkSession(
            toPhone, ProtocolCodec(InMemoryNonceRegistry()), "dev-reloj", "0.1.0",
            clock = { testScheduler.currentTime }, onGuardianResult = { results += it },
        )
        val phone = if (execute == null) {
            LinkSession(toWatch, ProtocolCodec(InMemoryNonceRegistry()), "dev-tel", "0.1.0", clock = { testScheduler.currentTime + phoneClockOffset })
        } else {
            LinkSession(
                toWatch, ProtocolCodec(InMemoryNonceRegistry()), "dev-tel", "0.1.0",
                clock = { testScheduler.currentTime + phoneClockOffset },
                onGuardianCommand = { c, expiresAt -> executed += expiresAt; execute(c, expiresAt) },
            )
        }
        toPhone.other = phone
        toWatch.other = watch
        return Pair(watch, phone, toPhone, results, executed)
    }

    @Test
    fun laOrdenViajaConNonceYCaducidadYLaRespuestaVuelve() = runTest {
        val p = pair()

        assertTrue(p.watch.sendCommand(lock, GuardianCommand.TTL_MS))

        val sobre = ProtocolCodec(InMemoryNonceRegistry()).decode(p.toPhone.sent.single(), testScheduler.currentTime)
        val envelope = (sobre as DecodeResult.Accepted).envelope
        assertNotNull(envelope.nonce)
        assertEquals(testScheduler.currentTime + GuardianCommand.TTL_MS, envelope.expiresAt)
        assertEquals(listOf(testScheduler.currentTime + GuardianCommand.TTL_MS), p.executed)
        assertEquals(listOf(GuardianCommandResult(commandId, GuardianCommandResult.Outcome.LOCKED)), p.results)
    }

    @Test
    fun unaOrdenRepetidaNoSeEjecutaDosVeces() = runTest {
        val p = pair()
        p.watch.sendCommand(lock, GuardianCommand.TTL_MS)

        p.phone.onMessage("nodo-reloj", p.toPhone.sent.single())

        assertEquals(1, p.executed.size)
        assertEquals(1, p.results.size)
    }

    @Test
    fun unaOrdenCaducadaNoSeEjecuta() = runTest {
        // El teléfono la recibe cuando, para él, ya caducó.
        val p = pair(phoneClockOffset = GuardianCommand.TTL_MS)

        p.watch.sendCommand(lock, GuardianCommand.TTL_MS)

        assertTrue(p.executed.isEmpty())
        assertTrue(p.results.isEmpty())
    }

    @Test
    fun sinNonceNoSePuedeCodificar() {
        val sinNonce = Envelope("m", ProtocolVersion.CURRENT, 0, "dev", lock)
        val error = runCatching { ProtocolCodec(InMemoryNonceRegistry()).encode(sinNonce) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun sinEjecutorSeContestaNoDisponibleEnLugarDeCallar() = runTest {
        val p = pair(execute = null)

        p.watch.sendCommand(lock, GuardianCommand.TTL_MS)

        assertEquals(listOf(GuardianCommandResult(commandId, GuardianCommandResult.Outcome.UNAVAILABLE, "NOT_SUPPORTED")), p.results)
    }

    @Test
    fun siElEjecutorFallaSeContestaNoDisponible() = runTest {
        val p = pair(execute = { _, _ -> error("DevicePolicyManager no disponible") })

        p.watch.sendCommand(lock, GuardianCommand.TTL_MS)

        assertEquals(GuardianCommandResult.Outcome.UNAVAILABLE, p.results.single().outcome)
    }

    @Test
    fun sinTelefonoNoSeEnvia() = runTest {
        val watch = LinkSession(Loopback(emptyList()), ProtocolCodec(InMemoryNonceRegistry()), "dev", "0.1.0")

        assertEquals(false, watch.sendCommand(lock, GuardianCommand.TTL_MS))
    }

    @Test
    fun elResultadoValidaSuCodigo() {
        assertNull(runCatching { GuardianCommandResult(commandId, GuardianCommandResult.Outcome.REJECTED, "SECURITY_EXCEPTION") }.exceptionOrNull())
        assertTrue(runCatching { GuardianCommandResult(commandId, GuardianCommandResult.Outcome.REJECTED, "texto libre") }.isFailure)
    }
}
