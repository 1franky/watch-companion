package mx.watchcompanion.core.communication

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.elementDescriptors
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WC-34, capa de protocolo: en el modo A el reloj no manda muestras, recorridos ni archivos al teléfono (01 §3.1). El
 * catálogo de mensajes es una lista cerrada; agregar uno obliga a revisar esta prueba y a justificar en el PR que no
 * transporta datos de una grabación (eso es del modo B, Fase 5).
 */
class ModeAProtocolTest {
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun elCatalogoDeMensajesEsElRevisado() {
        // El descriptor polimórfico sellado tiene [tipo, valor]; el valor enumera las subclases por su nombre serializado.
        val payloads = Payload.serializer().descriptor.elementDescriptors.last().elementDescriptors.map { it.serialName }.toSet()
        assertEquals(
            "Mensaje nuevo en el protocolo: confirma que no transporta muestras ni archivos y actualiza esta lista",
            setOf("Ping", "Pong", "IntegrationStatus", "HeartRateZonesConfig", "ActivityStatusReport"),
            payloads,
        )
    }
}
