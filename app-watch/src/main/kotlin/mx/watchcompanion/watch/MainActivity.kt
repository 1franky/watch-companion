package mx.watchcompanion.watch

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import mx.watchcompanion.watch.live.LiveMetricsState

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
        val live = (application as WatchCompanionApp).liveMetrics
        val gate = ModuleGate(
            SharedPreferencesModuleSettings(this),
            ActivityPermissionChecker(this),
            DeviceKind.WATCH,
            Build.VERSION.SDK_INT,
        )
        setContent {
            val link by session.state.collectAsStateWithLifecycle()
            val liveState by live.state.collectAsStateWithLifecycle()
            val zones by (application as WatchCompanionApp).zones.zones.collectAsStateWithLifecycle()
            val activity by (application as WatchCompanionApp).activity.collectAsStateWithLifecycle()
            // Con una sesión en curso, la app abre directamente en Métricas.
            var screen by rememberSaveable { mutableStateOf(Screen.SUMMARY) }
            val scope = rememberCoroutineScope()
            var velora by remember { mutableStateOf(gate.status(CompanionModule.VELORA)) }
            val requestPermissions = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { result ->
                gate.onPermissionsRequested(result.keys)
                velora = gate.status(CompanionModule.VELORA)
            }
            // El ícono de la sesión en la esfera (Ongoing Activity, WC-58) viaja en la notificación del servicio, y sin
            // este permiso el sistema la bloquea. Es opcional: si se niega, la sesión arranca igual, sin ícono.
            val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                scope.launch { live.start() }
            }
            val startLive = {
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    scope.launch { live.start() }
                } else {
                    requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            LaunchedEffect(Unit) { session.refresh() }
            LaunchedEffect(Unit) {
                live.attach()
                if (live.state.value is LiveMetricsState.Active) screen = Screen.METRICS
            }
            // Fuera de ambiente: cada segundo en Métricas (duración), cada 30 s en Resumen; en ambiente, onUpdateAmbient.
            val tick = if (screen == Screen.METRICS) METRICS_TICK_MS else SUMMARY_TICK_MS
            LaunchedEffect(ambient, tick) {
                while (!ambient) {
                    now = System.currentTimeMillis()
                    delay(tick)
                }
            }
            BackHandler(enabled = screen == Screen.METRICS) { screen = Screen.SUMMARY }
            // Un permiso revocado desde Ajustes se refleja al volver a la app, sin reiniciarla.
            LifecycleResumeEffect(Unit) {
                velora = gate.status(CompanionModule.VELORA)
                onPauseOrDispose {}
            }
            if (screen == Screen.METRICS) {
                MetricsScreen(state = liveState, now = now, ambient = ambient, zones = zones) { action ->
                    when (action) {
                        LiveAction.START -> startLive()
                        LiveAction.PAUSE -> scope.launch { live.pause() }
                        LiveAction.RESUME -> scope.launch { live.resume() }
                        LiveAction.END -> scope.launch { live.end() }
                        LiveAction.DISMISS -> {
                            live.dismiss()
                            screen = Screen.SUMMARY
                        }
                        LiveAction.BACK -> screen = Screen.SUMMARY
                    }
                }
                return@setContent
            }
            SummaryScreen(
                activity = activity,
                live = liveState,
                onLive = {
                    if (liveState == LiveMetricsState.Idle) startLive()
                    screen = Screen.METRICS
                },
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
                            // Sin el permiso la sesión no puede seguir: se termina antes de revocarlo.
                            scope.launch { live.end() }
                            PermissionActions.revokeOnKill(this, gate.disable(CompanionModule.VELORA))
                            velora = gate.status(CompanionModule.VELORA)
                        }
                    }
                },
            )
        }
    }

    private enum class Screen { SUMMARY, METRICS }

    private companion object {
        const val SUMMARY_TICK_MS = 30_000L
        const val METRICS_TICK_MS = 1_000L
    }
}
