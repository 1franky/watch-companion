package mx.watchcompanion.phone

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import mx.watchcompanion.core.communication.InMemoryNonceRegistry
import mx.watchcompanion.core.communication.LinkSession
import mx.watchcompanion.core.communication.ProtocolCodec
import mx.watchcompanion.core.datalayer.Capabilities
import mx.watchcompanion.core.datalayer.DataLayerTransport
import mx.watchcompanion.core.datalayer.DeviceId
import mx.watchcompanion.core.datalayer.LinkSessionOwner
import mx.watchcompanion.core.session.Backend
import mx.watchcompanion.core.session.createSessionStore
import mx.watchcompanion.integration.velora.VeloraApi
import mx.watchcompanion.integration.velora.VeloraSession

/** Contenedor de dependencias del teléfono (inyección manual, como nexora-android). */
class PhoneCompanionApp : Application(), LinkSessionOwner {
    /** Vive lo que el proceso: tareas que deben terminar aunque se cierre la pantalla (p. ej. avisar el logout). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val linkSession: LinkSession by lazy {
        LinkSession(
            transport = DataLayerTransport(this, peerCapability = Capabilities.WATCH_APP),
            codec = ProtocolCodec(InMemoryNonceRegistry()),
            deviceId = DeviceId.get(this),
            appVersion = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty(),
        )
    }

    /** Sesión propia de Velora (ADR-013): independiente de velora-android y de Nexora. */
    val veloraSession: VeloraSession by lazy {
        VeloraSession(
            store = createSessionStore(this, Backend.VELORA),
            appScope = appScope,
            apiFor = { VeloraApi.create(it, allowCleartext = BuildConfig.DEBUG) },
        )
    }

    override fun onCreate() {
        super.onCreate()
        appScope.launch { veloraSession.restore() }
    }
}
