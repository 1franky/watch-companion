package mx.watchcompanion.watch

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mx.watchcompanion.core.session.KeystoreCipher
import mx.watchcompanion.watch.finance.FinanceCache
import mx.watchcompanion.watch.finance.FinancePrivacy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import mx.watchcompanion.core.communication.ActivityStatus
import mx.watchcompanion.core.communication.FinanceSnapshot
import mx.watchcompanion.core.communication.LinkState
import mx.watchcompanion.watch.drafts.WatchDrafts
import mx.watchcompanion.core.communication.HeartRateZonesStore
import mx.watchcompanion.core.communication.InMemoryNonceRegistry
import mx.watchcompanion.core.communication.LinkSession
import mx.watchcompanion.core.communication.ProtocolCodec
import mx.watchcompanion.core.datalayer.Capabilities
import mx.watchcompanion.core.datalayer.DataLayerTransport
import mx.watchcompanion.core.datalayer.DeviceId
import mx.watchcompanion.core.datalayer.LinkSessionOwner
import mx.watchcompanion.core.datalayer.PeerReachabilityHandler
import mx.watchcompanion.watch.guardian.DisconnectMonitor
import mx.watchcompanion.watch.guardian.PhoneLock
import mx.watchcompanion.core.communication.GuardianCommand
import mx.watchcompanion.watch.guardian.NotificationDisconnectAlerter
import mx.watchcompanion.watch.guardian.SharedPreferencesDisconnectStore
import mx.watchcompanion.watch.guardian.WorkDisconnectScheduler
import mx.watchcompanion.watch.guardian.phoneNearby
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
class WatchCompanionApp : Application(), LinkSessionOwner, RecordingTransferHandler, PeerReachabilityHandler {
    override val linkSession: LinkSession by lazy {
        LinkSession(
            transport = DataLayerTransport(this, peerCapability = Capabilities.PHONE_APP),
            codec = ProtocolCodec(InMemoryNonceRegistry()),
            deviceId = DeviceId.get(this),
            appVersion = appVersion,
            // Zonas del teléfono (WC-32): llegan al guardarlas allá o en cada Pong; se queda la más reciente.
            onZones = { zones.saveIfNewer(it) },
            // Última actividad en Velora (WC-33): solo en memoria; gana la consulta más reciente.
            onActivity = { status -> activity.update { current -> if (current == null || status.checkedAt >= current.checkedAt) status else current } },
            // Resumen financiero de Nexora (WC-38): gana la consulta más reciente; se guarda cifrado (WC-42).
            onFinance = { snapshot -> receiveFinance(snapshot) },
            // Borradores de Nexora (WC-40): el teléfono contesta cada uno y el Pong repite todos.
            onDraftStatus = { drafts.onStatus(it) },
            // Bloqueo desde el reloj (WC-44): la respuesta real del teléfono.
            onGuardianResult = { phoneLock.onResult(it) },
        )
    }

    /** Vacía si el sistema no da el paquete (pasa en las pruebas con Robolectric); nunca tumba un arranque. */
    private val appVersion: String by lazy {
        try {
            packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        } catch (_: PackageManager.NameNotFoundException) {
            ""
        }
    }

    /** Último estado de la actividad en Velora que informó el teléfono (WC-33). */
    val activity = MutableStateFlow<ActivityStatus?>(null)

    /**
     * Último resumen financiero de Nexora que informó el teléfono (WC-38). Al arrancar sale de la caché cifrada (WC-42)
     * y se vacía solo al caducar ([FinanceSnapshot.EXPIRE_AFTER_MS]).
     */
    val finance = MutableStateFlow<FinanceSnapshot?>(null)

    /** Copia cifrada del resumen (WC-42), fuera del respaldo y con su propia llave del Keystore. */
    private val financeCache: FinanceCache by lazy {
        FinanceCache(File(noBackupFilesDir, "finanzas-nexora.bin"), KeystoreCipher("wc-finanzas-nexora"))
    }

    /** Si el usuario pidió ocultar las cifras de Nexora (WC-42). */
    val financePrivacy: FinancePrivacy by lazy { FinancePrivacy(this) }

    private suspend fun receiveFinance(snapshot: FinanceSnapshot) {
        var accepted = false
        finance.update { current ->
            if (current == null || snapshot.checkedAt >= current.checkedAt) snapshot.also { accepted = true } else current
        }
        if (accepted) withContext(Dispatchers.IO) { financeCache.save(snapshot) }
    }

    /** Borradores de gasto o ingreso capturados aquí (WC-40); solo en memoria. */
    val drafts: WatchDrafts by lazy { WatchDrafts(send = { linkSession.sendToPeer(it) }) }

    /** Zonas de FC recibidas del teléfono, guardadas aquí para usarlas sin teléfono ni internet (01 §3.1). */
    val zones: HeartRateZonesStore by lazy { HeartRateZonesStore.inFile(File(noBackupFilesDir, "zonas.json")) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Bloqueo manual del teléfono (WC-44): orden sensible con nonce y caducidad; solo «bloqueado» si el teléfono lo confirma. */
    val phoneLock: PhoneLock by lazy { PhoneLock(send = { linkSession.sendCommand(it, GuardianCommand.TTL_MS) }) }

    /** Lo lanza la pantalla, pero vive en la app: girar la muñeca no corta la espera de la respuesta. */
    fun lockPhone() {
        appScope.launch { phoneLock.lock() }
    }

    /** Aviso de desconexión del teléfono (WC-45, Guardian). Apagado hasta que el usuario elige un plazo. */
    val disconnectMonitor: DisconnectMonitor by lazy {
        DisconnectMonitor(SharedPreferencesDisconnectStore(this), WorkDisconnectScheduler(this), NotificationDisconnectAlerter(this))
    }

    override fun onPeerReachability(capability: String, nearby: Boolean) {
        if (capability == Capabilities.PHONE_APP) disconnectMonitor.onReachability(nearby)
    }

    /** Cambia el plazo del aviso y, si quedó activo, mira enseguida si el teléfono está cerca. */
    fun setDisconnectDelay(minutes: Int?) {
        disconnectMonitor.setDelay(minutes)
        if (minutes != null) appScope.launch { phoneNearby(this@WatchCompanionApp)?.let { disconnectMonitor.onReachability(it) } }
    }

    override fun onCreate() {
        super.onCreate()
        appScope.launch { zones.load() }
        // Aviso de desconexión (WC-45): al arrancar se pone al día con el estado real del teléfono.
        appScope.launch {
            if (disconnectMonitor.delayMinutes.value != null) phoneNearby(this@WatchCompanionApp)?.let { disconnectMonitor.onReachability(it) }
        }
        // Resumen de Nexora guardado (WC-42): solo si sigue vigente y no llegó ya uno del teléfono.
        appScope.launch {
            val cached = withContext(Dispatchers.IO) { financeCache.load() } ?: return@launch
            finance.update { current -> if (current == null || cached.checkedAt > current.checkedAt) cached else current }
        }
        // Caducidad (WC-42): las cifras se borran a su hora aunque no llegue nada nuevo del teléfono.
        appScope.launch {
            finance.collectLatest { snapshot ->
                val expiresAt = snapshot?.expiresAt ?: return@collectLatest
                delay((expiresAt - System.currentTimeMillis()).coerceAtLeast(0) + 1)
                if (finance.compareAndSet(snapshot, null)) withContext(Dispatchers.IO) { financeCache.clear() }
            }
        }
        // Al recuperar el enlace se reenvían los borradores que el teléfono aún no acusa (WC-40).
        appScope.launch {
            linkSession.state.filter { it is LinkState.Connected }.collect { drafts.resendUnacknowledged() }
        }
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
