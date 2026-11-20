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
