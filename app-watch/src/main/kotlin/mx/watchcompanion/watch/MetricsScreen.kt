package mx.watchcompanion.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.Text
import androidx.wear.tooling.preview.devices.WearDevices
import java.util.Locale
import mx.watchcompanion.core.communication.HeartRateZones
import mx.watchcompanion.watch.live.ActiveDuration
import mx.watchcompanion.watch.live.EndReason
import mx.watchcompanion.watch.live.HeartRateAvailability
import mx.watchcompanion.watch.live.LiveMetricsState
import mx.watchcompanion.watch.recording.RecordingProgress
import mx.watchcompanion.watch.recording.RecordingStatus
import mx.watchcompanion.watch.recording.RecordingSummary

/** Acciones de la pantalla Métricas; las ejecuta la sesión de métricas en vivo. */
enum class LiveAction { START, RECORD, PAUSE, RESUME, END, DISMISS, BACK }

/**
 * Pantalla Métricas (01 §5, WC-30): frecuencia cardíaca y duración de la sesión de métricas en vivo.
 *
 * Sin lectura válida se muestra «--» con el motivo (buscando pulso, reloj fuera de la muñeca, sensor no disponible),
 * nunca un 0. En ambiente solo quedan las cifras, sin segundos ni botones.
 *
 * @param now instante actual (ms epoch) para extrapolar la duración entre actualizaciones.
 * @param zones zonas recibidas del teléfono (WC-32); sin ellas no se muestra zona.
 */
@Composable
fun MetricsScreen(
    state: LiveMetricsState,
    now: Long,
    ambient: Boolean = false,
    zones: HeartRateZones? = null,
    onAction: (LiveAction) -> Unit,
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
                .rotaryScrollable(RotaryScrollableDefaults.behavior(scroll), focus)
                .focusRequester(focus)
                .focusable()
                .verticalScroll(scroll)
                .padding(horizontal = 24.dp, vertical = 36.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (state) {
                is LiveMetricsState.Active -> Active(state, now, ambient, zones, primary, secondary, onAction)
                LiveMetricsState.Idle -> {
                    Message(stringResource(R.string.live_idle), primary)
                    if (!ambient) {
                        Button(onClick = { onAction(LiveAction.START) }) { Text(stringResource(R.string.live_start)) }
                        FilledTonalButton(onClick = { onAction(LiveAction.RECORD) }) { Text(stringResource(R.string.record_start)) }
                        OutlinedButton(onClick = { onAction(LiveAction.BACK) }) { Text(stringResource(R.string.live_back)) }
                    }
                }
                LiveMetricsState.Starting -> Message(stringResource(R.string.live_starting), primary)
                LiveMetricsState.Unsupported -> Notice(stringResource(R.string.live_unsupported), primary, ambient, onAction)
                LiveMetricsState.OtherAppInProgress -> Notice(stringResource(R.string.live_other_app), primary, ambient, onAction)
                is LiveMetricsState.Failed -> Notice(stringResource(R.string.live_failed, state.code), primary, ambient, onAction)
                LiveMetricsState.LocationUnavailable ->
                    Notice(stringResource(R.string.record_location_unavailable), primary, ambient, onAction)
                is LiveMetricsState.Ended -> {
                    Message(stringResource(R.string.live_ended), primary)
                    Text(formatDuration(state.activeMs, seconds = true), color = primary, style = MaterialTheme.typography.displaySmall)
                    endReasonText(state.reason)?.let { Text(it, color = secondary, textAlign = TextAlign.Center) }
                    state.recording?.let { Text(recordingSaved(it), color = primary, textAlign = TextAlign.Center) }
                    if (!ambient) {
                        Button(onClick = { onAction(LiveAction.DISMISS) }) { Text(stringResource(R.string.live_ok)) }
                    }
                }
            }
        }
        LaunchedEffect(ambient) { if (!ambient) focus.requestFocus() }
    }
}

@Composable
private fun Active(
    state: LiveMetricsState.Active,
    now: Long,
    ambient: Boolean,
    zones: HeartRateZones?,
    primary: Color,
    secondary: Color,
    onAction: (LiveAction) -> Unit,
) {
    val hrLabel = stringResource(R.string.live_heart_rate)
    val zone = zones?.zoneOf(state.heartRateBpm)
    val hrValue = state.heartRateBpm?.let { bpm ->
        zone?.let { stringResource(R.string.live_bpm_zone_spoken, bpm, it) } ?: stringResource(R.string.live_bpm_spoken, bpm)
    } ?: heartRateStatus(state.availability)
    Column(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$hrLabel, $hrValue" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(hrLabel, color = secondary, style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(state.heartRateBpm?.toString() ?: "--", color = primary, style = MaterialTheme.typography.displayLarge)
            Text(stringResource(R.string.live_bpm), color = secondary, style = MaterialTheme.typography.labelLarge)
        }
        if (state.heartRateBpm == null) {
            Text(heartRateStatus(state.availability), color = secondary, textAlign = TextAlign.Center)
        } else if (zone != null) {
            Text(stringResource(R.string.live_zone, zone), color = primary, style = MaterialTheme.typography.titleLarge)
        } else if (zones == null && !ambient) {
            Text(stringResource(R.string.live_no_zones), color = secondary, textAlign = TextAlign.Center)
        }
    }
    val durationText = formatDuration(state.duration.at(now), seconds = !ambient)
    Column(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.live_duration), color = secondary, style = MaterialTheme.typography.labelMedium)
        Text(durationText, color = primary, style = MaterialTheme.typography.displaySmall)
        if (state.paused) Text(stringResource(R.string.live_paused), color = secondary)
        state.recording?.let { Text(recordingProgress(it), color = primary, textAlign = TextAlign.Center) }
    }
    if (ambient) return

    var confirmEnd by rememberSaveable { mutableStateOf(false) }
    if (confirmEnd) {
        Text(stringResource(R.string.live_end_confirm), color = primary, textAlign = TextAlign.Center)
        Button(onClick = { confirmEnd = false; onAction(LiveAction.END) }) { Text(stringResource(R.string.live_end_yes)) }
        OutlinedButton(onClick = { confirmEnd = false }) { Text(stringResource(R.string.live_end_no)) }
    } else {
        if (state.paused) {
            Button(onClick = { onAction(LiveAction.RESUME) }) { Text(stringResource(R.string.live_resume)) }
        } else {
            FilledTonalButton(onClick = { onAction(LiveAction.PAUSE) }) { Text(stringResource(R.string.live_pause)) }
        }
        OutlinedButton(onClick = { confirmEnd = true }) { Text(stringResource(R.string.live_end)) }
        OutlinedButton(onClick = { onAction(LiveAction.BACK) }) { Text(stringResource(R.string.live_back)) }
    }
}

@Composable
private fun Message(text: String, color: Color) =
    Text(text, color = color, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)

/** Aviso que no es una sesión: se lee y se cierra. */
@Composable
private fun Notice(text: String, color: Color, ambient: Boolean, onAction: (LiveAction) -> Unit) {
    Message(text, color)
    if (!ambient) Button(onClick = { onAction(LiveAction.DISMISS) }) { Text(stringResource(R.string.live_ok)) }
}

@Composable
private fun recordingProgress(progress: RecordingProgress): String =
    if (progress.points == 0) stringResource(R.string.record_searching_gps)
    else stringResource(R.string.record_active, progress.distanceMeters / 1000)

@Composable
private fun recordingSaved(summary: RecordingSummary): String = when {
    summary.points == 0 -> stringResource(R.string.record_empty)
    summary.status == RecordingStatus.PARTIAL -> stringResource(R.string.record_partial, summary.distanceMeters / 1000, summary.points)
    else -> stringResource(R.string.record_saved, summary.distanceMeters / 1000, summary.points)
}

@Composable
private fun heartRateStatus(availability: HeartRateAvailability): String = when (availability) {
    HeartRateAvailability.ACQUIRING, HeartRateAvailability.AVAILABLE -> stringResource(R.string.live_hr_acquiring)
    HeartRateAvailability.OFF_BODY -> stringResource(R.string.live_hr_off_body)
    HeartRateAvailability.UNAVAILABLE -> stringResource(R.string.live_hr_unavailable)
    HeartRateAvailability.UNKNOWN -> stringResource(R.string.live_hr_unknown)
}

@Composable
private fun endReasonText(reason: EndReason): String? = when (reason) {
    EndReason.USER -> null
    EndReason.PERMISSION_LOST -> stringResource(R.string.live_end_permission_lost)
    EndReason.SUPERSEDED -> stringResource(R.string.live_end_superseded)
    EndReason.OTHER -> stringResource(R.string.live_end_other)
}

/** `0:05:07`, `1:02:33`; sin segundos en ambiente: `1:02`. */
internal fun formatDuration(ms: Long, seconds: Boolean): String {
    val total = ms.coerceAtLeast(0) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (seconds) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%d:%02d".format(Locale.ROOT, h, m)
}

private val AMBIENT_PRIMARY = Color(0xFFD0D0D0)
private val AMBIENT_SECONDARY = Color(0xFF9E9E9E)

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
private fun MetricsScreenPreview() = MetricsScreen(
    state = LiveMetricsState.Active(132, HeartRateAvailability.AVAILABLE, ActiveDuration(0, 3_753_000, running = false), paused = false),
    now = 0,
    onAction = {},
)

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
private fun MetricsScreenNoReadingPreview() = MetricsScreen(
    state = LiveMetricsState.Active(null, HeartRateAvailability.OFF_BODY, ActiveDuration.ZERO, paused = true),
    now = 0,
    onAction = {},
)
