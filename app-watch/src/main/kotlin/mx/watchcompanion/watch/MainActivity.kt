package mx.watchcompanion.watch

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.ambient.AmbientLifecycleObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mx.watchcompanion.core.datalayer.LinkSessionOwner
import mx.watchcompanion.core.permissions.ActivityPermissionChecker
import mx.watchcompanion.core.permissions.CompanionModule
import mx.watchcompanion.core.permissions.DeviceKind
import mx.watchcompanion.core.permissions.ModuleGate
import mx.watchcompanion.core.permissions.ModuleStatus
import mx.watchcompanion.core.permissions.PermissionActions
import mx.watchcompanion.core.permissions.SharedPreferencesModuleSettings

class MainActivity : ComponentActivity() {
    private var ambient by mutableStateOf(false)
    private var now by mutableLongStateOf(System.currentTimeMillis())

    /** Modo ambiente (WC-29): la app sigue visible con la pantalla atenuada; el sistema la actualiza cada minuto. */
    private val ambientObserver = AmbientLifecycleObserver(
        this,
        object : AmbientLifecycleObserver.AmbientLifecycleCallback {
            override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                ambient = true
                now = System.currentTimeMillis()
            }

            override fun onUpdateAmbient() {
                now = System.currentTimeMillis()
            }

            override fun onExitAmbient() {
                ambient = false
                now = System.currentTimeMillis()
            }
        },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(ambientObserver)
        val session = (application as LinkSessionOwner).linkSession
        val gate = ModuleGate(
            SharedPreferencesModuleSettings(this),
            ActivityPermissionChecker(this),
            DeviceKind.WATCH,
            Build.VERSION.SDK_INT,
        )
        setContent {
            val link by session.state.collectAsStateWithLifecycle()
            val scope = rememberCoroutineScope()
            var velora by remember { mutableStateOf(gate.status(CompanionModule.VELORA)) }
            val requestPermissions = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { result ->
                gate.onPermissionsRequested(result.keys)
                velora = gate.status(CompanionModule.VELORA)
            }
            LaunchedEffect(Unit) { session.refresh() }
            // Fuera de ambiente, el «hace N min» se actualiza cada 30 s; en ambiente lo hace onUpdateAmbient.
            LaunchedEffect(ambient) {
                while (!ambient) {
                    now = System.currentTimeMillis()
                    delay(TICK_MS)
                }
            }
            // Un permiso revocado desde Ajustes se refleja al volver a la app, sin reiniciarla.
            LifecycleResumeEffect(Unit) {
                velora = gate.status(CompanionModule.VELORA)
                onPauseOrDispose {}
            }
            SummaryScreen(
                link = link,
                now = now,
                onCheck = { scope.launch { session.refresh() } },
                ambient = ambient,
                velora = velora,
                onVeloraAction = { action ->
                    when (action) {
                        ModuleAction.ENABLE -> {
                            velora = gate.enable(CompanionModule.VELORA)
                            (velora as? ModuleStatus.NeedsPermissions)
                                ?.takeUnless { it.permanentlyDenied }
                                ?.let { requestPermissions.launch(it.missing.toTypedArray()) }
                        }
                        ModuleAction.REQUEST ->
                            (velora as? ModuleStatus.NeedsPermissions)?.let { requestPermissions.launch(it.missing.toTypedArray()) }
                        ModuleAction.OPEN_SETTINGS -> startActivity(PermissionActions.appSettingsIntent(this))
                        ModuleAction.DISABLE -> {
                            PermissionActions.revokeOnKill(this, gate.disable(CompanionModule.VELORA))
                            velora = gate.status(CompanionModule.VELORA)
                        }
                    }
                },
            )
        }
    }

    private companion object {
        const val TICK_MS = 30_000L
    }
}
