package mx.watchcompanion.core.communication

/** Estado del enlace con la otra app, tal como lo ve el usuario. Nunca se informa conexión sin un Pong real. */
sealed interface LinkState {
    /** Aún no se ha comprobado. */
    data object Unknown : LinkState

    data object Checking : LinkState

    /** No hay ningún dispositivo emparejado con la otra app instalada y alcanzable. */
    data object NoPeer : LinkState

    /** Hay dispositivo, pero no respondió a tiempo o el envío falló. */
    data class Unreachable(val peerName: String) : LinkState

    /** Respondió con un Pong válido. */
    data class Connected(val peerName: String, val peerAppVersion: String, val roundTripMs: Long, val checkedAt: Long) :
        LinkState

    /** La otra app habla una versión del protocolo fuera de rango: hay que actualizar una de las dos (R-05). */
    data object Incompatible : LinkState
}
