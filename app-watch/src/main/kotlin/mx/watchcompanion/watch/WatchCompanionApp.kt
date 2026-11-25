package mx.watchcompanion.watch

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import mx.watchcompanion.core.communication.ActivityStatus
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
import mx.watchcompanion.watch.recording.RecordingOutbox
import mx.watchcompanion.watch.recording.TrackRecorder
import mx.watchcompanion.watch.live.LiveMetricsState
import mx.watchcompanion.core.datalayer.RecordingTransfer
import mx.watchcompanion.core.datalayer.RecordingTransferHandler
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/** Contenedor de dependencias del reloj (inyección manual, como nexora-android). */
class WatchCompanionApp : Application(), LinkSessionOwner, RecordingTransferHandler {
    override val linkSession: LinkSession by lazy {
        LinkSession(
            transport = DataLayerTransport(this, peerCapability = Capabilities.PHONE_APP),
            codec = ProtocolCodec(InMemoryNonceRegistry()),
            deviceId = DeviceId.get(this),
            appVersion = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty(),
            // Zonas del teléfono (WC-32): llegan al guardarlas allá o en cada Pong; se queda la más reciente.
            onZones = { zones.saveIfNewer(it) },
            // Última actividad en Velora (WC-33): solo en memoria; gana la consulta más reciente.
            onActivity = { status -> activity.update { current -> if (current == null || status.checkedAt >= current.checkedAt) status else current } },
        )
    }

    /** Último estado de la actividad en Velora que informó el teléfono (WC-33). */
    val activity = MutableStateFlow<ActivityStatus?>(null)

    /** Zonas de FC recibidas del teléfono, guardadas aquí para usarlas sin teléfono ni internet (01 §3.1). */
    val zones: HeartRateZonesStore by lazy { HeartRateZonesStore.inFile(File(noBackupFilesDir, "zonas.json")) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        appScope.launch { zones.load() }
        // Alertas hápticas por zona (WC-31): solo actúan con una sesión de métricas activa; todo es local.
        ZoneAlerts(liveMetrics.state, zones.zones, VibratorHaptics(this)).start(appScope)
        // Modo B (WC-54): al arrancar y cada vez que termina una sesión, se ofrecen al teléfono las grabaciones cerradas.
        appScope.launch {
            liveMetrics.state
                .map { it is LiveMetricsState.Ended || it == LiveMetricsState.Idle }
                .distinctUntilChanged()
                .filter { it }
                .collect { outbox.offerPending() }
        }
    }

    /** Grabaciones GPS del modo B (WC-53), en almacenamiento privado y fuera de los respaldos. */
    val recorder: TrackRecorder by lazy { TrackRecorder(File(noBackupFilesDir, "recordings")) }

    /** Grabaciones cerradas que esperan llegar al teléfono (WC-54). */
    val outbox: RecordingOutbox by lazy {
        RecordingOutbox(recorder) { id, completeness, sha256, gpx ->
            RecordingTransfer.offer(this, id, completeness, sha256, gpx)
        }
    }

    override suspend fun onRecordingAccepted(clientRecordingId: String) = outbox.accepted(clientRecordingId)

    /** Sesión de métricas en vivo (WC-30): una sola por proceso, la comparten la pantalla y el servicio. */
    val liveMetrics: LiveMetricsSession by lazy {
        LiveMetricsSession(
            backend = HealthServicesBackend(this),
            keeper = ServiceForegroundKeeper(this),
            recorder = recorder,
            hasLocationPermission = {
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            },
        )
    }
}
