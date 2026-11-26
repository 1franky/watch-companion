package mx.watchcompanion.core.datalayer

import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.DataEventBuffer
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

    /**
     * Grabaciones del modo B (WC-54): el teléfono recibe las ofrecidas y el reloj, los acuses ([RecordingTransfer]).
     * El buffer solo es válido durante esta llamada, así que se procesa aquí, con un límite más amplio (copia un archivo).
     */
    override fun onDataChanged(events: DataEventBuffer) {
        val handler = application as? RecordingTransferHandler ?: return
        runBlocking {
            withTimeoutOrNull(TRANSFER_TIMEOUT_MS) {
                for (event in events) {
                    val path = event.dataItem.uri.path ?: continue
                    if (!path.startsWith(RecordingTransfer.PATH_PREFIX)) continue
                    RecordingTransfer.acceptedId(event)?.let { handler.onRecordingAccepted(it) }
                        ?: handler.onRecordingOffered(event)
                }
            }
        }
    }

    /** Aviso de desconexión (WC-45): la otra app dejó de estar cerca o volvió. */
    override fun onCapabilityChanged(info: CapabilityInfo) {
        val handler = application as? PeerReachabilityHandler ?: return
        handler.onPeerReachability(info.name, info.nodes.any { it.isNearby })
    }

    private companion object {
        const val HANDLE_TIMEOUT_MS = 5_000L
        const val TRANSFER_TIMEOUT_MS = 30_000L
    }
}
