package mx.watchcompanion.core.communication

import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementDescriptors
import org.junit.Assert.assertTrue
import org.junit.Test

/** WC-ADR-013 y 07-security-and-privacy.md §3: ningún mensaje de la Data Layer puede tener campos de credenciales. */
class NoCredentialsInProtocolTest {
    private val prohibidos = listOf("token", "password", "secret", "authorization", "bearer", "cookie", "credential")

    @Test
    fun ningunCampoDelProtocoloPareceUnaCredencial() {
        val nombres = mutableSetOf<String>()
        recorrer(Envelope.serializer().descriptor, nombres, mutableSetOf())
        // El descriptor polimórfico de Payload contiene a todas sus subclases selladas.
        val sospechosos = nombres.filter { nombre -> prohibidos.any { nombre.contains(it, ignoreCase = true) } }
        assertTrue("Campos con forma de credencial en el protocolo: $sospechosos", sospechosos.isEmpty())
        assertTrue("El recorrido debe llegar a los payloads", "appVersion" in nombres)
    }

    private fun recorrer(descriptor: SerialDescriptor, nombres: MutableSet<String>, visitados: MutableSet<String>) {
        if (!visitados.add(descriptor.serialName)) return
        nombres += descriptor.serialName
        for (i in 0 until descriptor.elementsCount) nombres += descriptor.getElementName(i)
        descriptor.elementDescriptors.forEach { recorrer(it, nombres, visitados) }
    }
}
