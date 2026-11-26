package mx.watchcompanion.phone

import android.app.Application
import android.content.pm.PackageManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import mx.watchcompanion.core.communication.ActivityStatusReport
import mx.watchcompanion.core.communication.FinanceSnapshotReport
import mx.watchcompanion.core.communication.HeartRateZonesStore
import mx.watchcompanion.core.communication.InMemoryNonceRegistry
import mx.watchcompanion.core.communication.LinkSession
import mx.watchcompanion.core.communication.ProtocolCodec
import mx.watchcompanion.core.datalayer.Capabilities
import mx.watchcompanion.core.datalayer.DataLayerTransport
import mx.watchcompanion.core.datalayer.DeviceId
import mx.watchcompanion.core.datalayer.LinkSessionOwner
import mx.watchcompanion.core.datalayer.RecordingTransfer
import mx.watchcompanion.core.datalayer.RecordingTransferHandler
import mx.watchcompanion.core.diagnostics.DiagnosticEvent
import mx.watchcompanion.core.diagnostics.DiagnosticModule
import mx.watchcompanion.core.diagnostics.Outcome
import mx.watchcompanion.phone.recordings.RecordingInbox
import mx.watchcompanion.phone.recordings.RecordingUploadWorker
import mx.watchcompanion.phone.recordings.RecordingUploader
import mx.watchcompanion.phone.recordings.VeloraSecondaryRecordings
import com.google.android.gms.wearable.DataEvent
import mx.watchcompanion.core.diagnostics.Diagnostics
import mx.watchcompanion.core.diagnostics.FileDiagnosticsStorage
import mx.watchcompanion.core.session.Backend
import mx.watchcompanion.core.session.SessionState
import mx.watchcompanion.core.session.createSessionStore
import mx.watchcompanion.integration.nexora.NexoraClient
import mx.watchcompanion.integration.nexora.NexoraSession
import mx.watchcompanion.integration.velora.VeloraApi
import mx.watchcompanion.integration.velora.VeloraSession

/** Contenedor de dependencias del teléfono (inyección manual, como nexora-android). */
class PhoneCompanionApp : Application(), LinkSessionOwner, RecordingTransferHandler {
    /** Vive lo que el proceso: tareas que deben terminar aunque se cierre la pantalla (p. ej. avisar el logout). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Vacía si el sistema no da el paquete (pasa en las pruebas con Robolectric); nunca tumba un arranque. */
    private val appVersion: String by lazy {
        try {
            packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        } catch (_: PackageManager.NameNotFoundException) {
            ""
        }
    }

    /** Registro técnico local (WC-23), fuera del respaldo: no sale del teléfono. */
    val diagnostics: Diagnostics by lazy {
        Diagnostics(FileDiagnosticsStorage(File(noBackupFilesDir, "diagnostico.json")), appVersion)
    }

    override val linkSession: LinkSession by lazy {
        LinkSession(
            transport = DataLayerTransport(this, peerCapability = Capabilities.WATCH_APP),
            codec = ProtocolCodec(InMemoryNonceRegistry()),
            deviceId = DeviceId.get(this),
            appVersion = appVersion,
            diagnostics = diagnostics,
            // Lo que el reloj muestra en su pantalla Resumen (WC-29).
            syncSummary = { veloraSyncSummary(veloraSession.state.value, diagnostics.events.value) },
            // El reloj recibe las zonas vigentes en cada Pong (WC-32).
            zonesProvider = { zonesStore.zones.value },
            // Última actividad en Velora (WC-33): el Pong lleva lo último conocido y, si es viejo, se consulta aparte
            // y se reenvía al reloj al terminar.
            activityProvider = { activity.status.value },
            // Resumen financiero de Nexora (WC-38): igual que la actividad.
            financeProvider = { finance.snapshot.value },
            onPeerPing = {
                refreshActivityForWatch()
                refreshFinanceForWatch()
            },
        )
    }

    val activity: PhoneActivity by lazy {
        PhoneActivity(fetchStrava = { veloraStrava(veloraSession) }, fetchLatest = { veloraLatestActivity(veloraSession) })
    }

    /** Refresca el estado de la actividad si es viejo y lo manda al reloj; no bloquea a quien llama. */
    fun refreshActivityForWatch() {
        appScope.launch { activity.refreshIfStale()?.let { linkSession.sendToPeer(ActivityStatusReport(it)) } }
    }

    /** Resumen financiero de Nexora para el reloj (WC-38); solo en memoria. */
    val finance: PhoneFinance by lazy {
        PhoneFinance(session = { nexoraSession.state.value }, fetch = { now -> nexoraFinance(nexoraSession, now) })
    }

    /** Refresca el resumen financiero si es viejo y lo manda al reloj; no bloquea a quien llama. */
    fun refreshFinanceForWatch() {
        appScope.launch { finance.refreshIfStale()?.let { linkSession.sendToPeer(FinanceSnapshotReport(it)) } }
    }

    /** Zonas de FC configuradas (WC-32). Fuera del respaldo, como el resto de los datos locales. */
    private val zonesStore: HeartRateZonesStore by lazy { HeartRateZonesStore.inFile(File(noBackupFilesDir, "zonas.json")) }

    val zones: PhoneZones by lazy { PhoneZones(zonesStore, linkSession) { veloraMaxHeartRate(veloraSession) } }

    /** Sesión propia de Velora (WC-ADR-013): independiente de velora-android y de Nexora. */
    val veloraSession: VeloraSession by lazy {
        VeloraSession(
            store = createSessionStore(this, Backend.VELORA),
            appScope = appScope,
            apiFor = { VeloraApi.create(it, allowCleartext = BuildConfig.DEBUG) },
            diagnostics = diagnostics,
        )
    }

    /** Grabaciones del reloj recibidas y aún no confirmadas por Velora (WC-54); fuera del respaldo. */
    val recordingInbox: RecordingInbox by lazy { RecordingInbox(File(noBackupFilesDir, "grabaciones-del-reloj")) }

    val recordingUploader: RecordingUploader by lazy {
        RecordingUploader(recordingInbox, VeloraSecondaryRecordings(veloraSession)) { code ->
            appScope.launch { diagnostics.record(DiagnosticModule.VELORA, "recording.dropped", DiagnosticEvent.newCorrelationId(), Outcome.ERROR, code, 0) }
        }
    }

    /**
     * El reloj ofrece una grabación (modo B): se guarda, se verifica su SHA-256 y solo entonces se da el acuse, que le
     * permite al reloj borrar su copia. La subida a Velora va aparte, con WorkManager.
     */
    override suspend fun onRecordingOffered(event: DataEvent) {
        val offered = RecordingTransfer.parse(event) ?: return
        val saved = RecordingTransfer.open(this, event).use {
            recordingInbox.receive(offered.clientRecordingId, offered.completeness, offered.sha256, it)
        }
        if (!saved) {
            diagnostics.record(DiagnosticModule.LINK, "recording.receive", DiagnosticEvent.newCorrelationId(), Outcome.ERROR, "SHA256_MISMATCH", 0)
            return
        }
        RecordingTransfer.accept(this, offered.uri)
        RecordingUploadWorker.enqueue(this)
    }

    /** Sesión propia de Nexora (WC-37): su refresh token va cifrado con su propia llave, aparte del de Velora. */
    val nexoraSession: NexoraSession by lazy {
        NexoraSession(
            store = createSessionStore(this, Backend.NEXORA),
            appScope = appScope,
            apiFor = { NexoraClient.create(it, allowCleartext = BuildConfig.DEBUG) },
            diagnostics = diagnostics,
        )
    }

    override fun onCreate() {
        super.onCreate()
        appScope.launch { diagnostics.load() }
        appScope.launch { zonesStore.load() }
        appScope.launch { veloraSession.restore() }
        appScope.launch { nexoraSession.restore() }
        // Al entrar o salir de Nexora el reloj se entera enseguida: al cerrar sesión, sus cifras se borran de él (WC-38).
        appScope.launch {
            nexoraSession.state
                .map { it is SessionState.SignedIn }
                .distinctUntilChanged()
                .drop(1)
                .collect { finance.refresh()?.let { linkSession.sendToPeer(FinanceSnapshotReport(it)) } }
        }
        appScope.launch {
            recordingInbox.refresh()
            if (recordingInbox.pending.value > 0) RecordingUploadWorker.enqueue(this@PhoneCompanionApp)
        }
    }
}
