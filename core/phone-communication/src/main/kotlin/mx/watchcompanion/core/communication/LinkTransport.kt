package mx.watchcompanion.core.communication

/** Otro dispositivo con la app del companion instalada (el teléfono visto desde el reloj, o al revés). */
data class Peer(val id: String, val displayName: String, val isNearby: Boolean)

/** Transporte de bytes entre reloj y teléfono. En producción es la Data Layer (`core:data-layer`). */
interface LinkTransport {
    /** Dispositivos alcanzables ahora que tienen instalada la otra app; los cercanos primero. */
    suspend fun reachablePeers(): List<Peer>

    /** Envía [bytes] a [peerId]. Lanza una excepción si no se pudo entregar al sistema. */
    suspend fun send(peerId: String, bytes: ByteArray)
}
