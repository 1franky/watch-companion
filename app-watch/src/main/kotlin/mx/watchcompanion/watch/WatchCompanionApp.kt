package mx.watchcompanion.watch

import android.app.Application
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import mx.watchcompanion.core.communication.HeartRateZonesStore
import mx.watchcompanion.core.communication.InMemoryNonceRegistry
import mx.watchcompanion.core.communication.LinkSession
import mx.watchcompanion.core.communication.ProtocolCodec
import mx.watchcompanion.core.datalayer.Capabilities
import mx.watchcompanion.core.datalayer.DataLayerTransport
import mx.watchcompanion.core.datalayer.DeviceId
import mx.watchcompanion.core.datalayer.LinkSessionOwner
import mx.watchcompanion.watch.alerts.VibratorHaptics
import mx.watchcompanion.watch.alerts.ZoneAlerts
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
            // Zonas del teléfono (WC-32): llegan al guardarlas allá o en cada Pong; se queda la más reciente.
            onZones = { zones.saveIfNewer(it) },
        )
    }

    /** Zonas de FC recibidas del teléfono, guardadas aquí para usarlas sin teléfono ni internet (01 §3.1). */
    val zones: HeartRateZonesStore by lazy { HeartRateZonesStore.inFile(File(noBackupFilesDir, "zonas.json")) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        appScope.launch { zones.load() }
        // Alertas hápticas por zona (WC-31): solo actúan con una sesión de métricas activa; todo es local.
        ZoneAlerts(liveMetrics.state, zones.zones, VibratorHaptics(this)).start(appScope)
    }

    /** Sesión de métricas en vivo (WC-30): una sola por proceso, la comparten la pantalla y el servicio. */
    val liveMetrics: LiveMetricsSession by lazy {
        LiveMetricsSession(HealthServicesBackend(this), ServiceForegroundKeeper(this))
    }
}
