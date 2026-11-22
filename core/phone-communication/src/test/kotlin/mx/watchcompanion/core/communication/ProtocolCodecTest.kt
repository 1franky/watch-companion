package mx.watchcompanion.core.communication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolCodecTest {
    private val now = 1_800_000_000_000L
    private val codec = ProtocolCodec(InMemoryNonceRegistry())

    private fun envelope(payload: Payload = Ping, version: Int = ProtocolVersion.CURRENT, nonce: String? = null, expiresAt: Long? = null) =
        Envelope(
            messageId = "6f1c2c1e-0000-4000-8000-000000000001",
            schemaVersion = version,
            createdAt = now,
            deviceId = "watch-seudonimo",
            payload = payload,
            correlationId = "corr-1",
            nonce = nonce,
            expiresAt = expiresAt,
        )

    @Test
    fun idaYVueltaConservaElMensaje() {
        val original = envelope(Pong(appVersion = "0.1.0"))
        assertEquals(DecodeResult.Accepted(original), codec.decode(codec.encode(original), now))
    }

    @Test
    fun elTipoViajaComoDiscriminadorEstable() {
        val texto = codec.encode(envelope(Ping)).decodeToString()
        assertTrue(texto, texto.contains("\"type\":\"Ping\""))
    }

    @Test
    fun aceptaLaVersionAnteriorYRechazaLasFueraDeRango() {
        val futura = ProtocolVersion.CURRENT + 1
        val bytes = """{"messageId":"m","schemaVersion":$futura,"createdAt":$now,"deviceId":"d","payload":{"type":"Nuevo"}}"""
            .encodeToByteArray()
        assertEquals(DecodeResult.Incompatible(futura), codec.decode(bytes, now))
        assertEquals(DecodeResult.Incompatible(0), codec.decode(codec.encode(envelope(version = 0)), now))
        assertTrue(ProtocolVersion.isSupported(ProtocolVersion.MIN_SUPPORTED))
    }

    @Test
    fun ignoraCamposDesconocidosDeUnaVersionAceptada() {
        val bytes = """{"messageId":"m","schemaVersion":1,"createdAt":$now,"deviceId":"d","campoNuevo":true,"payload":{"type":"Ping"}}"""
            .encodeToByteArray()
        assertTrue(codec.decode(bytes, now) is DecodeResult.Accepted)
    }

    @Test
    fun rechazaBasuraYTiposDesconocidosSinFiltrarContenido() {
        assertEquals(DecodeResult.Malformed, codec.decode("no es json".encodeToByteArray(), now))
        val desconocido = """{"messageId":"m","schemaVersion":1,"createdAt":$now,"deviceId":"d","payload":{"type":"Desconocido"}}"""
        assertEquals(DecodeResult.Malformed, codec.decode(desconocido.encodeToByteArray(), now))
    }

    @Test
    fun unPongDeLaVersionAnteriorSinSyncSeLeeConLaListaVacia() {
        val anterior = """{"messageId":"m","schemaVersion":1,"createdAt":0,"deviceId":"d","payload":{"type":"Pong","appVersion":"0.1.0"}}"""
        val leido = codec.decode(anterior.encodeToByteArray(), now = 0) as DecodeResult.Accepted
        assertEquals(Pong("0.1.0", sync = emptyList()), leido.envelope.payload)
    }

    @Test
    fun elEstadoDeSincronizacionViajaCompleto() {
        val sync = SyncSummary(IntegrationStatus.Integration.VELORA, SyncSummary.State.ERROR, lastSuccessAt = 5, errorCode = "HTTP_503")
        val original = envelope(Pong("0.1.0", listOf(sync)))
        assertEquals(DecodeResult.Accepted(original), codec.decode(codec.encode(original), now = 0))
    }

    @Test
    fun unCodigoDeErrorConTextoLibreSeRechaza() {
        val conCorreo = """{"messageId":"m","schemaVersion":1,"createdAt":0,"deviceId":"d","payload":{"type":"Pong","appVersion":"0.1.0",""" +
            """"sync":[{"integration":"VELORA","state":"ERROR","errorCode":"user@example.com"}]}}"""
        assertEquals(DecodeResult.Malformed, codec.decode(conCorreo.encodeToByteArray(), now = 0))
    }

    @Test
    fun lasZonasViajanCompletas() {
        val zonas = HeartRateZones(182, listOf(95, 112, 128, 147, 165), HeartRateZones.Source.MANUAL, updatedAt = 9)
        val original = envelope(HeartRateZonesConfig(zonas))
        assertEquals(DecodeResult.Accepted(original), codec.decode(codec.encode(original), now = 0))
    }

    @Test
    fun unasZonasInvalidasHacenIlegibleElMensaje() {
        val malas = """{"messageId":"m","schemaVersion":1,"createdAt":0,"deviceId":"d","payload":{"type":"HeartRateZonesConfig",""" +
            """"zones":{"maxHeartRateBpm":180,"lowerBoundsBpm":[150,140,130,120,110],"source":"MANUAL","updatedAt":1}}}"""
        assertEquals(DecodeResult.Malformed, codec.decode(malas.encodeToByteArray(), now = 0))
    }

    @Test
    fun elEstadoDeActividadViajaCompletoYRechazaCodigosLibres() {
        val estado = ActivityStatus(ActivityStatus.State.ERROR, checkedAt = 5, errorCode = "STRAVA_UNAVAILABLE")
        val original = envelope(ActivityStatusReport(estado))
        assertEquals(DecodeResult.Accepted(original), codec.decode(codec.encode(original), now = 0))
        val libre = """{"messageId":"m","schemaVersion":1,"createdAt":0,"deviceId":"d","payload":{"type":"ActivityStatusReport",""" +
            """"status":{"state":"ERROR","checkedAt":1,"errorCode":"Falló en Calle 5"}}}"""
        assertEquals(DecodeResult.Malformed, codec.decode(libre.encodeToByteArray(), now = 0))
    }

    @Test
    fun rechazaMensajesCaducados() {
        assertEquals(DecodeResult.Expired, codec.decode(codec.encode(envelope(expiresAt = now)), now))
    }

    /** Aún no hay comandos sensibles reales (llegan con Guardian y Smart Hub): aquí `Pong` hace de comando. */
    private val codecSensible = ProtocolCodec(InMemoryNonceRegistry()) { it is Pong }
    private val comando = Pong(appVersion = "0.1.0")

    @Test
    fun unMensajeSensibleExigeNonceYNoSePuedeRepetir() {
        val mensaje = envelope(comando, nonce = "n-1", expiresAt = now + 10_000)
        val bytes = codecSensible.encode(mensaje)
        assertEquals(DecodeResult.Accepted(mensaje), codecSensible.decode(bytes, now))
        assertEquals(DecodeResult.Replayed, codecSensible.decode(bytes, now + 1))

        val sinNonce = codec.encode(envelope(comando))
        assertEquals(DecodeResult.MissingSecurityFields, codecSensible.decode(sinNonce, now))
    }

    @Test(expected = IllegalArgumentException::class)
    fun noSeCodificaUnMensajeSensibleSinNonce() {
        codecSensible.encode(envelope(comando))
    }

    @Test
    fun losMensajesActualesNoSonSensibles() {
        listOf(Ping, Pong("0.1.0"), IntegrationStatus(IntegrationStatus.Integration.VELORA, IntegrationStatus.State.AVAILABLE))
            .forEach { assertTrue(!it.sensitive) }
    }

    @Test
    fun unNonceCaducadoPuedeReutilizarse() {
        val registry = InMemoryNonceRegistry()
        assertTrue(registry.register("n", expiresAt = now + 5, now = now))
        assertTrue(registry.register("n", expiresAt = now + 20, now = now + 10))
    }
}
