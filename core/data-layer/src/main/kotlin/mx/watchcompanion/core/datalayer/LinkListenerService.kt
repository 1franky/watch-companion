package mx.watchcompanion.core.datalayer

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import mx.watchcompanion.core.communication.LinkSession

/** La implementa la clase `Application` de cada app para entregar su única [LinkSession]. */
interface LinkSessionOwner {
    val linkSession: LinkSession
}

/**
 * Entrega a la [LinkSession] los mensajes que llegan por la Data Layer, incluso con la app cerrada.
 *
 * Los callbacks de [WearableListenerService] corren en un hilo de fondo propio, así que se bloquea hasta terminar de
 * procesar (con límite) para que el sistema no destruya el servicio antes de que salga la respuesta.
 */
class LinkListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != DataLayerTransport.MESSAGE_PATH) return
        val session = (application as? LinkSessionOwner)?.linkSession ?: return
        runBlocking {
            withTimeoutOrNull(HANDLE_TIMEOUT_MS) { session.onMessage(event.sourceNodeId, event.data) }
        }
    }

    private companion object {
        const val HANDLE_TIMEOUT_MS = 5_000L
    }
}
