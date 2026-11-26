package mx.watchcompanion.watch.guardian

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.Text
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import mx.watchcompanion.core.communication.GuardianCommandResult.Outcome
import mx.watchcompanion.watch.R

/**
 * Historial de Guardian y revocación del módulo (WC-47). Revocar pide confirmación porque borra el historial y quita el
 * permiso de bloqueo del teléfono; desactivar (en Resumen) no la necesita porque no borra nada.
 */
@Composable
fun GuardianScreen(
    events: List<GuardianEvent>,
    retentionDays: Int,
    onRetention: () -> Unit,
    revoke: RevokeState,
    onRevoke: () -> Unit,
    onBack: () -> Unit,
    zoneId: ZoneId = ZoneId.systemDefault(),
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    BackHandler { if (confirming) confirming = false else onBack() }
    MaterialTheme {
        val scroll = rememberScrollState()
        val focus = remember { FocusRequester() }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .rotaryScrollable(RotaryScrollableDefaults.behavior(scroll), focus)
                .focusRequester(focus)
                .focusable()
                .verticalScroll(scroll)
                .padding(horizontal = 24.dp, vertical = 36.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.guardian_history_title),
                modifier = Modifier.semantics { heading() },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
            )
            when {
                revoke is RevokeState.Done -> {
                    Text(
                        stringResource(if (revoke.phoneConfirmed) R.string.guardian_revoked else R.string.guardian_revoked_watch_only),
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = onBack) { Text(stringResource(R.string.draft_back)) }
                }
                revoke == RevokeState.Revoking -> Text(stringResource(R.string.guardian_revoking), textAlign = TextAlign.Center)
                confirming -> {
                    Text(stringResource(R.string.guardian_revoke_confirm), textAlign = TextAlign.Center)
                    Button(onClick = { confirming = false; onRevoke() }) { Text(stringResource(R.string.guardian_revoke)) }
                    OutlinedButton(onClick = { confirming = false }) { Text(stringResource(R.string.draft_cancel)) }
                }
                else -> {
                    if (events.isEmpty()) Text(stringResource(R.string.guardian_history_empty), textAlign = TextAlign.Center)
                    for (event in events) {
                        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                WHEN.format(Instant.ofEpochMilli(event.at).atZone(zoneId)),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Text(eventText(event), textAlign = TextAlign.Center)
                        }
                    }
                    OutlinedButton(onClick = onRetention) { Text(stringResource(R.string.guardian_retention, retentionDays)) }
                    OutlinedButton(onClick = { confirming = true }) { Text(stringResource(R.string.guardian_revoke)) }
                }
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
}

@Composable
private fun eventText(event: GuardianEvent): String = when (event.kind) {
    GuardianEvent.Kind.PHONE_AWAY -> stringResource(R.string.guardian_event_away)
    GuardianEvent.Kind.PHONE_BACK -> stringResource(R.string.guardian_event_back)
    GuardianEvent.Kind.LOCK -> when (event.outcome) {
        Outcome.LOCKED -> stringResource(R.string.guardian_event_locked)
        Outcome.EXPIRED -> stringResource(R.string.guardian_event_unconfirmed)
        else -> stringResource(R.string.guardian_event_lock_failed, event.code ?: event.outcome?.name ?: "?")
    }
}

private val WHEN = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.forLanguageTag("es-MX"))
