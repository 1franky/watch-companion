package mx.watchcompanion.core.datalayer

/**
 * La implementa la clase `Application` que quiere saber cuándo la otra app deja de estar cerca o vuelve (WC-45). Solo
 * llegan las capacidades que la app declara en el filtro `CAPABILITY_CHANGED` de su manifiesto.
 *
 * @param nearby algún nodo con [capability] está alcanzable y cerca (`Node.isNearby`); alcanzable solo por la nube no
 *   cuenta.
 */
interface PeerReachabilityHandler {
    fun onPeerReachability(capability: String, nearby: Boolean)
}
