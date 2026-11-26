package mx.watchcompanion.phone

import java.io.IOException
import kotlinx.coroutines.test.runTest
import mx.watchcompanion.core.communication.DecodeResult
import mx.watchcompanion.core.communication.HeartRateZones
import mx.watchcompanion.core.communication.HeartRateZonesConfig
import mx.watchcompanion.core.communication.HeartRateZonesStore
import mx.watchcompanion.core.communication.InMemoryNonceRegistry
import mx.watchcompanion.core.communication.LinkSession
import mx.watchcompanion.core.communication.LinkTransport
import mx.watchcompanion.core.communication.Peer
import mx.watchcompanion.core.communication.ProtocolCodec
import mx.watchcompanion.core.session.SessionExpiredException
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneZonesTest {
    private val codec = ProtocolCodec(InMemoryNonceRegistry())

    private class Transporte(var peers: List<Peer>) : LinkTransport {
        val sent = mutableListOf<ByteArray>()
        override suspend fun reachablePeers() = peers
        override suspend fun send(peerId: String, bytes: ByteArray) { sent += bytes }
    }

    private var guardado: String? = null
    private val store = HeartRateZonesStore(read = { guardado }, write = { guardado = it })
    private val reloj = Peer("nodo-reloj", "Galaxy Watch Ultra", isNearby = true)

    private fun zones(transporte: LinkTransport = Transporte(emptyList()), max: suspend () -> Int? = { 185 }) =
        PhoneZones(store, LinkSession(transporte, codec, "dev", "0.1.0"), max)

    @Test
    fun laFcMaximaDeVelora() = runTest {
        assertEquals(VeloraMax.Value(185), zones().veloraMax())
        assertEquals(VeloraMax.NotSet, zones(max = { null }).veloraMax())
        assertEquals(VeloraMax.SignedOut, zones(max = { throw SessionExpiredException("Velora") }).veloraMax())
        assertEquals(VeloraMax.Failed("HTTP_503"), zones(max = { throw VeloraHttpException(503) }).veloraMax())
        assertEquals(VeloraMax.Failed("IOEXCEPTION"), zones(max = { throw IOException("sin red") }).veloraMax())
    }

    @Test
    fun guardarLasEnviaAlRelojSiEstaAlAlcance() = runTest {
        val transporte = Transporte(listOf(reloj))
        val z = HeartRateZones.fromMax(185, HeartRateZones.Source.VELORA, updatedAt = 1)

        assertEquals(ZonesDelivery.SENT, zones(transporte).save(z))

        assertEquals(z, store.zones.value)
        val enviado = (codec.decode(transporte.sent.single(), 0) as DecodeResult.Accepted).envelope.payload
        assertEquals(HeartRateZonesConfig(z), enviado)
    }

    @Test
    fun sinRelojSeGuardanYQuedanPendientes() = runTest {
        val z = HeartRateZones.fromMax(185, HeartRateZones.Source.VELORA, updatedAt = 1)
        assertEquals(ZonesDelivery.PENDING, zones().save(z))
        assertEquals(z, store.zones.value)
    }

    @Test
    fun validaLoCapturado() {
        val manual = HeartRateZones.Source.MANUAL
        assertEquals(
            ZonesInput.Valid(HeartRateZones(182, listOf(95, 112, 128, 147, 165), manual, 9)),
            parseZones(" 182 ", listOf("95", "112", "128", "147", "165"), manual, now = 9),
        )
        assertEquals(ZonesInput.Invalid(ZonesInput.Reason.MAX_OUT_OF_RANGE), parseZones("99", listOf("50", "60", "70", "80", "90"), manual, 0))
        assertEquals(ZonesInput.Invalid(ZonesInput.Reason.MAX_OUT_OF_RANGE), parseZones("", listOf("50", "60", "70", "80", "90"), manual, 0))
        assertEquals(ZonesInput.Invalid(ZonesInput.Reason.BOUND_MISSING), parseZones("180", listOf("90", "", "130", "150", "160"), manual, 0))
        assertEquals(ZonesInput.Invalid(ZonesInput.Reason.BOUND_TOO_LOW), parseZones("180", listOf("30", "110", "130", "150", "160"), manual, 0))
        assertEquals(ZonesInput.Invalid(ZonesInput.Reason.NOT_ASCENDING), parseZones("180", listOf("90", "130", "120", "150", "160"), manual, 0))
        assertEquals(ZonesInput.Invalid(ZonesInput.Reason.ABOVE_MAX), parseZones("150", listOf("90", "110", "120", "140", "160"), manual, 0))
    }
}
