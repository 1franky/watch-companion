package mx.watchcompanion.phone

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
import mx.watchcompanion.core.diagnostics.Diagnostics
import mx.watchcompanion.core.diagnostics.FileDiagnosticsStorage
import mx.watchcompanion.core.session.Backend
import mx.watchcompanion.core.session.createSessionStore
import mx.watchcompanion.integration.velora.VeloraApi
import mx.watchcompanion.integration.velora.VeloraSession

/** Contenedor de dependencias del teléfono (inyección manual, como nexora-android). */
class PhoneCompanionApp : Application(), LinkSessionOwner {
    /** Vive lo que el proceso: tareas que deben terminar aunque se cierre la pantalla (p. ej. avisar el logout). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val appVersion: String by lazy { packageManager.getPackageInfo(packageName, 0).versionName.orEmpty() }

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
        )
    }

    /** Zonas de FC configuradas (WC-32). Fuera del respaldo, como el resto de los datos locales. */
    private val zonesStore: HeartRateZonesStore by lazy { HeartRateZonesStore.inFile(File(noBackupFilesDir, "zonas.json")) }

    val zones: PhoneZones by lazy { PhoneZones(zonesStore, linkSession) { veloraMaxHeartRate(veloraSession) } }

    /** Sesión propia de Velora (ADR-013): independiente de velora-android y de Nexora. */
    val veloraSession: VeloraSession by lazy {
        VeloraSession(
            store = createSessionStore(this, Backend.VELORA),
            appScope = appScope,
            apiFor = { VeloraApi.create(it, allowCleartext = BuildConfig.DEBUG) },
            diagnostics = diagnostics,
        )
    }

    override fun onCreate() {
        super.onCreate()
        appScope.launch { diagnostics.load() }
        appScope.launch { zonesStore.load() }
        appScope.launch { veloraSession.restore() }
    }
}
