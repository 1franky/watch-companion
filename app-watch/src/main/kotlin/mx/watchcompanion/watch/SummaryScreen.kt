package mx.watchcompanion.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.tooling.preview.devices.WearDevices
import mx.watchcompanion.core.communication.IntegrationStatus
import mx.watchcompanion.core.communication.LinkState
import mx.watchcompanion.core.communication.SyncSummary
import mx.watchcompanion.core.permissions.ModuleStatus
import mx.watchcompanion.watch.live.LiveMetricsState

/**
 * Pantalla Resumen (01 §5, WC-29): conexión con el teléfono, modo activo y estado de sincronización con Velora.
 *
 * En modo ambiente solo quedan los textos, en gris sobre negro y sin botones: es lo que admite una pantalla OLED
 * siempre encendida. El estado de sincronización es el que el teléfono informó en su último Pong; sin enlace no se
 * muestra ninguno, para no presentar como vigente un dato viejo.
 *
 * @param now instante actual (ms epoch), para el «hace N min»; inyectable para pruebas.
 */
@Composable
fun SummaryScreen(
    link: LinkState,
    now: Long,
    onCheck: () -> Unit,
    ambient: Boolean = false,
    velora: ModuleStatus = ModuleStatus.Disabled,
    onVeloraAction: (ModuleAction) -> Unit = {},
    live: LiveMetricsState = LiveMetricsState.Idle,
    onLive: () -> Unit = {},
) {
    MaterialTheme {
        val scroll = rememberScrollState()
        val focus = remember { FocusRequester() }
        val primary = if (ambient) AMBIENT_PRIMARY else MaterialTheme.colorScheme.onSurface
        val secondary = if (ambient) AMBIENT_SECONDARY else MaterialTheme.colorScheme.onSurfaceVariant
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                // Bisel giratorio del Galaxy Watch Ultra y corona: desplazan la pantalla.
                .rotaryScrollable(RotaryScrollableDefaults.behavior(scroll), focus)
                .focusRequester(focus)
                .focusable()
                .verticalScroll(scroll)
                .padding(horizontal = 24.dp, vertical = 36.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.summary_title),
                modifier = Modifier.semantics { heading() },
                color = secondary,
                style = MaterialTheme.typography.labelLarge,
            )
            SummaryItem(stringResource(R.string.summary_phone), linkTitle(link), primary, secondary)
            SummaryItem(stringResource(R.string.summary_mode), stringResource(R.string.summary_mode_a), primary, secondary)
            SummaryItem(stringResource(R.string.summary_velora), veloraSyncText(link, now), primary, secondary)
            if (live is LiveMetricsState.Active) {
                SummaryItem(stringResource(R.string.summary_live), liveSummary(live), primary, secondary)
            }
            if (!ambient) {
                // Las métricas no dependen del teléfono: solo de que Velora tenga su permiso (01 §3.1).
                if (velora == ModuleStatus.Ready || live != LiveMetricsState.Idle) {
                    Button(onClick = onLive) {
                        Text(stringResource(if (live == LiveMetricsState.Idle) R.string.live_start else R.string.live_open))
                    }
                }
                Button(onClick = onCheck, enabled = link != LinkState.Checking) {
                    Text(stringResource(R.string.link_check))
                }
                ModuleRow(title = stringResource(R.string.module_velora), status = velora, onAction = onVeloraAction)
            }
        }
        LaunchedEffect(ambient) { if (!ambient) focus.requestFocus() }
    }
}

/** Etiqueta y valor como un solo elemento para TalkBack («Teléfono, Conectado a …»). */
@Composable
private fun SummaryItem(label: String, value: String, primary: Color, secondary: Color) {
    Column(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = secondary, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
        Text(value, color = primary, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    }
}

@Composable
private fun liveSummary(live: LiveMetricsState.Active): String = when {
    live.paused -> stringResource(R.string.live_paused)
    live.heartRateBpm != null -> stringResource(R.string.live_bpm_value, live.heartRateBpm)
    else -> stringResource(R.string.live_running)
}

@Composable
private fun linkTitle(state: LinkState): String = when (state) {
    LinkState.Unknown, LinkState.Checking -> stringResource(R.string.link_checking)
    LinkState.NoPeer -> stringResource(R.string.link_no_peer)
    is LinkState.Unreachable -> stringResource(R.string.link_unreachable, state.peerName)
    is LinkState.Connected -> stringResource(R.string.link_connected, state.peerName)
    LinkState.Incompatible -> stringResource(R.string.link_incompatible)
}

@Composable
private fun veloraSyncText(link: LinkState, now: Long): String {
    val sync = (link as? LinkState.Connected)?.peerSync?.firstOrNull { it.integration == IntegrationStatus.Integration.VELORA }
        ?: return stringResource(R.string.sync_no_data)
    return when (sync.state) {
        SyncSummary.State.SIGNED_OUT -> stringResource(R.string.sync_signed_out)
        SyncSummary.State.SESSION_EXPIRED -> stringResource(R.string.sync_session_expired)
        SyncSummary.State.NEVER_SYNCED -> stringResource(R.string.sync_never)
        SyncSummary.State.SYNCED -> sync.lastSuccessAt?.let { stringResource(R.string.sync_ok, elapsed(now - it)) }
            ?: stringResource(R.string.sync_never)
        SyncSummary.State.ERROR -> stringResource(R.string.sync_error, sync.errorCode ?: "?")
    }
}

/** «hace un momento», «hace 5 min», «hace 2 h», «hace 3 días». Un reloj adelantado al teléfono no da negativos. */
@Composable
private fun elapsed(ms: Long): String {
    val minutes = ms.coerceAtLeast(0) / 60_000
    return when {
        minutes < 1 -> stringResource(R.string.elapsed_now)
        minutes < 60 -> stringResource(R.string.elapsed_minutes, minutes)
        minutes < 24 * 60 -> stringResource(R.string.elapsed_hours, minutes / 60)
        else -> (minutes / (24 * 60)).toInt().let { pluralStringResource(R.plurals.elapsed_days, it, it) }
    }
}

/** Gris claro en lugar de blanco puro: menos consumo y menos desgaste del OLED en ambiente. */
private val AMBIENT_PRIMARY = Color(0xFFD0D0D0)
private val AMBIENT_SECONDARY = Color(0xFF9E9E9E)

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
private fun SummaryScreenPreview() = SummaryScreen(
    link = LinkState.Connected(
        "Galaxy S26 Ultra", "0.1.0", roundTripMs = 42, checkedAt = 0,
        peerSync = listOf(SyncSummary(IntegrationStatus.Integration.VELORA, SyncSummary.State.SYNCED, lastSuccessAt = 0)),
    ),
    now = 5 * 60_000,
    onCheck = {},
    velora = ModuleStatus.Ready,
)

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
private fun SummaryScreenAmbientPreview() =
    SummaryScreen(link = LinkState.Unreachable("Galaxy S26 Ultra"), now = 0, onCheck = {}, ambient = true)
