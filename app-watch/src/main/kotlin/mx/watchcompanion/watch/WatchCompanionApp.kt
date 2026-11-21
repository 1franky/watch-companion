package mx.watchcompanion.watch

import android.app.Application
import mx.watchcompanion.core.communication.InMemoryNonceRegistry
import mx.watchcompanion.core.communication.LinkSession
import mx.watchcompanion.core.communication.ProtocolCodec
import mx.watchcompanion.core.datalayer.Capabilities
import mx.watchcompanion.core.datalayer.DataLayerTransport
import mx.watchcompanion.core.datalayer.DeviceId
import mx.watchcompanion.core.datalayer.LinkSessionOwner
import mx.watchcompanion.watch.live.HealthServicesBackend
import mx.watchcompanion.watch.live.LiveMetricsSession
import mx.watchcompanion.watch.live.ServiceForegroundKeeper

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

    /** Sesión de métricas en vivo (WC-30): una sola por proceso, la comparten la pantalla y el servicio. */
    val liveMetrics: LiveMetricsSession by lazy {
        LiveMetricsSession(HealthServicesBackend(this), ServiceForegroundKeeper(this))
    }
}
