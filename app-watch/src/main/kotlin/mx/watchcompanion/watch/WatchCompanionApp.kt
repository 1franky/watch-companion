package mx.watchcompanion.watch

import android.app.Application
import mx.watchcompanion.core.communication.InMemoryNonceRegistry
import mx.watchcompanion.core.communication.LinkSession
import mx.watchcompanion.core.communication.ProtocolCodec
import mx.watchcompanion.core.datalayer.Capabilities
import mx.watchcompanion.core.datalayer.DataLayerTransport
import mx.watchcompanion.core.datalayer.DeviceId
import mx.watchcompanion.core.datalayer.LinkSessionOwner

/** Contenedor de dependencias del reloj (inyección manual, como nexora-android). */
class WatchCompanionApp : Application(), LinkSessionOwner {
    override val linkSession: LinkSession by lazy {
        LinkSession(
            transport = DataLayerTransport(this, peerCapability = Capabilities.PHONE_APP),
            codec = ProtocolCodec(InMemoryNonceRegistry()),
            deviceId = DeviceId.get(this),
            appVersion = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty(),
        )
    }
}
