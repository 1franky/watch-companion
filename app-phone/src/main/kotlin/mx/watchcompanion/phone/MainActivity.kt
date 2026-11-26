package mx.watchcompanion.phone

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import mx.watchcompanion.phone.alerts.NexoraAlertNotifier
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import mx.watchcompanion.core.communication.DraftOptions
import mx.watchcompanion.core.communication.LinkState
import mx.watchcompanion.phone.drafts.DraftsSection
import mx.watchcompanion.phone.drafts.StoredDraft
import mx.watchcompanion.core.session.Backend
import mx.watchcompanion.core.session.LoginResult
import mx.watchcompanion.core.session.SessionState
import mx.watchcompanion.core.session.SignOutReason
import mx.watchcompanion.integration.nexora.NexoraClient
import mx.watchcompanion.integration.velora.VeloraApi

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as PhoneCompanionApp
        setContent {
            val link by app.linkSession.state.collectAsStateWithLifecycle()
            val velora by app.veloraSession.state.collectAsStateWithLifecycle()
            val nexora by app.nexoraSession.state.collectAsStateWithLifecycle()
            val events by app.diagnostics.events.collectAsStateWithLifecycle()
            val scope = rememberCoroutineScope()
            // Backend cuyo login está abierto, o null (WC-37: Velora y Nexora tienen sesiones independientes).
            var loginFor by rememberSaveable { mutableStateOf<Backend?>(null) }
            var showDiagnostics by rememberSaveable { mutableStateOf(false) }
            var showZones by rememberSaveable { mutableStateOf(false) }
            val zones by app.zones.zones.collectAsStateWithLifecycle()
            val pendingRecordings by app.recordingInbox.pending.collectAsStateWithLifecycle()
            val drafts by app.drafts.drafts.collectAsStateWithLifecycle()
            val draftsSending by app.drafts.sending.collectAsStateWithLifecycle()
            val finance by app.finance.snapshot.collectAsStateWithLifecycle()
            var veloraMax by remember { mutableStateOf<VeloraMax?>(null) }
            var zonesDelivery by remember { mutableStateOf<ZonesDelivery?>(null) }
            var busy by remember { mutableStateOf(false) }
            var loginError by remember { mutableStateOf<LoginResult?>(null) }
            LaunchedEffect(Unit) { app.linkSession.refresh() }
            // Avisos de Nexora (WC-41): al conceder el permiso se revisa la bandeja enseguida.
            val notifier = remember { NexoraAlertNotifier(this@MainActivity) }
            var alertsAllowed by remember { mutableStateOf(notifier.canNotify()) }
            val requestAlerts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                alertsAllowed = granted
                if (granted) scope.launch { app.nexoraAlerts.poll() }
            }
            // Al iniciar sesión (o abrir la app con sesión) se consulta la última actividad para el reloj (WC-33).
            LaunchedEffect(velora is SessionState.SignedIn) { if (velora is SessionState.SignedIn) app.refreshActivityForWatch() }
            // Con sesión de Nexora se traen las cuentas y categorías, para mostrar los borradores con sus nombres (WC-40).
            LaunchedEffect(nexora is SessionState.SignedIn) { if (nexora is SessionState.SignedIn) app.refreshFinanceForWatch() }

            MaterialTheme {
                Scaffold { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).padding(24.dp).verticalScroll(rememberScrollState())) {
                        if (showZones) {
                            ZonesScreen(
                                saved = zones,
                                veloraMax = veloraMax,
                                delivery = zonesDelivery,
                                now = System::currentTimeMillis,
                                onUseVelora = {
                                    veloraMax = VeloraMax.Loading
                                    scope.launch { veloraMax = app.zones.veloraMax() }
                                },
                                onSave = { scope.launch { zonesDelivery = app.zones.save(it) } },
                                onBack = {
                                    showZones = false
                                    veloraMax = null
                                    zonesDelivery = null
                                },
                            )
                        } else if (showDiagnostics) {
                            DiagnosticsScreen(
                                events = events,
                                onClear = { scope.launch { app.diagnostics.clear() } },
                                onBack = { showDiagnostics = false },
                            )
                        } else if (loginFor != null && (if (loginFor == Backend.NEXORA) nexora else velora) !is SessionState.SignedIn) {
                            val backend = checkNotNull(loginFor)
                            BackendLogin(
                                backend = if (backend == Backend.NEXORA) "Nexora" else "Velora",
                                defaultServer = if (backend == Backend.NEXORA) NexoraClient.DEFAULT_SERVER else VeloraApi.DEFAULT_SERVER,
                                showServerField = BuildConfig.DEBUG,
                                busy = busy,
                                error = loginError,
                                onSubmit = { server, email, password ->
                                    busy = true
                                    scope.launch {
                                        val result = if (backend == Backend.NEXORA) {
                                            app.nexoraSession.login(server, email, password)
                                        } else {
                                            app.veloraSession.login(server, email, password)
                                        }
                                        busy = false
                                        loginError = result.takeUnless { it == LoginResult.Success }
                                        if (result == LoginResult.Success) loginFor = null
                                    }
                                },
                                onCancel = {
                                    loginFor = null
                                    loginError = null
                                },
                            )
                        } else {
                            PhoneHome(
                                link = link,
                                onCheck = { scope.launch { app.linkSession.refresh() } },
                                velora = velora,
                                onVeloraLogin = {
                                    loginError = null
                                    loginFor = Backend.VELORA
                                },
                                nexora = nexora,
                                onNexoraLogin = {
                                    loginError = null
                                    loginFor = Backend.NEXORA
                                },
                                onNexoraLogout = { scope.launch { app.nexoraSession.logout() } },
                                onVeloraLogout = { scope.launch { app.veloraSession.logout() } },
                                onDiagnostics = { showDiagnostics = true },
                                onZones = { showZones = true },
                                pendingRecordings = pendingRecordings,
                                drafts = drafts,
                                draftsSending = draftsSending,
                                draftOptions = finance?.options,
                                onDraftConfirm = { key -> scope.launch { app.drafts.confirm(key) } },
                                onDraftDiscard = { key -> scope.launch { app.drafts.discard(key) } },
                                alertsAllowed = alertsAllowed,
                                onAllowAlerts = {
                                    if (Build.VERSION.SDK_INT >= 33) requestAlerts.launch(Manifest.permission.POST_NOTIFICATIONS)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Pantalla inicial: estado del enlace con el reloj y de la sesión de Velora. */
@Composable
fun PhoneHome(
    link: LinkState,
    onCheck: () -> Unit,
    velora: SessionState = SessionState.SignedOut(),
    onVeloraLogin: () -> Unit = {},
    onVeloraLogout: () -> Unit = {},
    onDiagnostics: () -> Unit = {},
    onZones: () -> Unit = {},
    pendingRecordings: Int = 0,
    nexora: SessionState = SessionState.SignedOut(),
    onNexoraLogin: () -> Unit = {},
    onNexoraLogout: () -> Unit = {},
    drafts: List<StoredDraft> = emptyList(),
    draftsSending: Set<String> = emptySet(),
    draftOptions: DraftOptions? = null,
    onDraftConfirm: (String) -> Unit = {},
    onDraftDiscard: (String) -> Unit = {},
    alertsAllowed: Boolean = true,
    onAllowAlerts: () -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineMedium)
        Text(linkTitle(link), style = MaterialTheme.typography.titleMedium)
        if (link is LinkState.Connected) {
            Text(
                stringResource(R.string.link_connected_detail, link.roundTripMs, link.peerAppVersion),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Button(onClick = onCheck, enabled = link != LinkState.Checking) {
            Text(stringResource(R.string.link_check))
        }
        HorizontalDivider()
        Text(stringResource(R.string.velora_section), style = MaterialTheme.typography.titleMedium)
        when (velora) {
            SessionState.Restoring -> Text(stringResource(R.string.velora_restoring))
            is SessionState.SignedOut -> {
                Text(
                    stringResource(
                        if (velora.reason == SignOutReason.EXPIRED) R.string.velora_expired else R.string.velora_signed_out,
                    ),
                )
                Button(onClick = onVeloraLogin) { Text(stringResource(R.string.velora_login)) }
            }
            is SessionState.SignedIn -> {
                Text(stringResource(R.string.velora_signed_in, velora.email))
                OutlinedButton(onClick = onVeloraLogout) { Text(stringResource(R.string.velora_logout)) }
            }
        }
        // Modo B (WC-54): rutas del reloj que esperan a Velora (sin red o sin sesión); se suben solas.
        if (pendingRecordings > 0) {
            Text(
                pluralStringResource(R.plurals.recordings_pending, pendingRecordings, pendingRecordings),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        OutlinedButton(onClick = onZones) { Text(stringResource(R.string.zones_open)) }
        HorizontalDivider()
        // Sesión propia de Nexora (WC-37): independiente de la de Velora y de nexora-android; cerrar la revoca.
        Text(stringResource(R.string.nexora_section), style = MaterialTheme.typography.titleMedium)
        when (nexora) {
            SessionState.Restoring -> Text(stringResource(R.string.velora_restoring))
            is SessionState.SignedOut -> {
                Text(
                    stringResource(
                        if (nexora.reason == SignOutReason.EXPIRED) R.string.nexora_expired else R.string.nexora_signed_out,
                    ),
                )
                Button(onClick = onNexoraLogin) { Text(stringResource(R.string.nexora_login)) }
            }
            is SessionState.SignedIn -> {
                Text(stringResource(R.string.velora_signed_in, nexora.email))
                // Avisos de Nexora (WC-41): el permiso se pide aquí, solo con sesión; sin él no se pierde ningún aviso.
                if (!alertsAllowed) {
                    Text(stringResource(R.string.alerts_permission), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onAllowAlerts) { Text(stringResource(R.string.alerts_allow)) }
                }
                OutlinedButton(onClick = onNexoraLogout) { Text(stringResource(R.string.nexora_logout)) }
            }
        }
        // Borradores del reloj (WC-40): se ven aunque no haya sesión; confirmarlos la necesita.
        DraftsSection(drafts, draftsSending, draftOptions, onDraftConfirm, onDraftDiscard)
        HorizontalDivider()
        TextButton(onClick = onDiagnostics) { Text(stringResource(R.string.diagnostics_open)) }
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

@Preview(showBackground = true)
@Composable
private fun PhoneHomePreview() = MaterialTheme {
    PhoneHome(
        LinkState.Connected("Galaxy Watch Ultra", "0.1.0", roundTripMs = 42, checkedAt = 0),
        onCheck = {},
        velora = SessionState.SignedIn("https://velora.example", "propietario@example.com"),
    )
}
