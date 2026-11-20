package mx.watchcompanion.watch

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.tooling.preview.devices.WearDevices
import kotlinx.coroutines.launch
import mx.watchcompanion.core.communication.LinkState
import mx.watchcompanion.core.datalayer.LinkSessionOwner
import mx.watchcompanion.core.permissions.ActivityPermissionChecker
import mx.watchcompanion.core.permissions.CompanionModule
import mx.watchcompanion.core.permissions.DeviceKind
import mx.watchcompanion.core.permissions.ModuleGate
import mx.watchcompanion.core.permissions.ModuleStatus
import mx.watchcompanion.core.permissions.PermissionActions
import mx.watchcompanion.core.permissions.SharedPreferencesModuleSettings

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            // Un permiso revocado desde Ajustes se refleja al volver a la app, sin reiniciarla.
            LifecycleResumeEffect(Unit) {
                velora = gate.status(CompanionModule.VELORA)
                onPauseOrDispose {}
            }
            WatchHome(
                link = link,
                onCheck = { scope.launch { session.refresh() } },
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
}

/**
 * Pantalla inicial de Fase 0: estado real del enlace con el teléfono y de los módulos activables. Se sustituye por la
 * pantalla Resumen en WC-29.
 */
@Composable
fun WatchHome(
    link: LinkState,
    onCheck: () -> Unit,
    velora: ModuleStatus = ModuleStatus.Disabled,
    onVeloraAction: (ModuleAction) -> Unit = {},
) {
    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = linkTitle(link), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
            if (link is LinkState.Connected) {
                Text(
                    text = stringResource(R.string.link_connected_detail, link.roundTripMs, link.peerAppVersion),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(onClick = onCheck, enabled = link != LinkState.Checking) {
                Text(stringResource(R.string.link_check))
            }
            ModuleRow(title = stringResource(R.string.module_velora), status = velora, onAction = onVeloraAction)
        }
    }
}

@Composable
private fun linkTitle(state: LinkState): String = when (state) {
    LinkState.Unknown, LinkState.Checking -> stringResource(R.string.link_checking)
    LinkState.NoPeer -> stringResource(R.string.link_no_peer)
    is LinkState.Unreachable -> stringResource(R.string.link_unreachable, state.peerName)
    is LinkState.Connected -> stringResource(R.string.link_connected, state.peerName)
    LinkState.Incompatible -> stringResource(R.string.link_incompatible)
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
private fun WatchHomePreview() =
    WatchHome(LinkState.Connected("Galaxy S26 Ultra", "0.1.0", roundTripMs = 42, checkedAt = 0), onCheck = {}, velora = ModuleStatus.Ready)
