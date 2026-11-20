package mx.watchcompanion.core.datalayer

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import mx.watchcompanion.core.communication.LinkTransport
import mx.watchcompanion.core.communication.Peer

/**
 * [LinkTransport] sobre la Data Layer.
 *
 * @param peerCapability capacidad que anuncia la otra app en su `wear.xml` ([Capabilities]).
 */
class DataLayerTransport(context: Context, private val peerCapability: String) : LinkTransport {
    private val capabilities = Wearable.getCapabilityClient(context.applicationContext)
    private val messages = Wearable.getMessageClient(context.applicationContext)

    override suspend fun reachablePeers(): List<Peer> =
        capabilities.getCapability(peerCapability, CapabilityClient.FILTER_REACHABLE).await().nodes
            .map { Peer(id = it.id, displayName = it.displayName, isNearby = it.isNearby) }
            .sortedByDescending { it.isNearby }

    override suspend fun send(peerId: String, bytes: ByteArray) {
        messages.sendMessage(peerId, MESSAGE_PATH, bytes).await()
    }

    companion object {
        const val MESSAGE_PATH = "/wc/message"
    }
}

/** Capacidades declaradas en `res/values/wear.xml` de cada app; cada una busca la de la otra. */
object Capabilities {
    const val WATCH_APP = "wc_watch_app"
    const val PHONE_APP = "wc_phone_app"
}
