package mx.watchcompanion.phone

import android.os.Bundle
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import mx.watchcompanion.core.communication.LinkState
import mx.watchcompanion.integration.velora.LoginResult
import mx.watchcompanion.integration.velora.SessionState
import mx.watchcompanion.integration.velora.SignOutReason

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as PhoneCompanionApp
        setContent {
            val link by app.linkSession.state.collectAsStateWithLifecycle()
            val velora by app.veloraSession.state.collectAsStateWithLifecycle()
            val events by app.diagnostics.events.collectAsStateWithLifecycle()
            val scope = rememberCoroutineScope()
            var showLogin by rememberSaveable { mutableStateOf(false) }
            var showDiagnostics by rememberSaveable { mutableStateOf(false) }
            var showZones by rememberSaveable { mutableStateOf(false) }
            val zones by app.zones.zones.collectAsStateWithLifecycle()
            var veloraMax by remember { mutableStateOf<VeloraMax?>(null) }
            var zonesDelivery by remember { mutableStateOf<ZonesDelivery?>(null) }
            var busy by remember { mutableStateOf(false) }
            var loginError by remember { mutableStateOf<LoginResult?>(null) }
            LaunchedEffect(Unit) { app.linkSession.refresh() }
            // Al iniciar sesión (o abrir la app con sesión) se consulta la última actividad para el reloj (WC-33).
            LaunchedEffect(velora is SessionState.SignedIn) { if (velora is SessionState.SignedIn) app.refreshActivityForWatch() }

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
                        } else if (showLogin && velora !is SessionState.SignedIn) {
                            VeloraLogin(
                                showServerField = BuildConfig.DEBUG,
                                busy = busy,
                                error = loginError,
                                onSubmit = { server, email, password ->
                                    busy = true
                                    scope.launch {
                                        val result = app.veloraSession.login(server, email, password)
                                        busy = false
                                        loginError = result.takeUnless { it == LoginResult.Success }
                                        if (result == LoginResult.Success) showLogin = false
                                    }
                                },
                                onCancel = {
                                    showLogin = false
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
                                    showLogin = true
                                },
                                onVeloraLogout = { scope.launch { app.veloraSession.logout() } },
                                onDiagnostics = { showDiagnostics = true },
                                onZones = { showZones = true },
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
        OutlinedButton(onClick = onZones) { Text(stringResource(R.string.zones_open)) }
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
