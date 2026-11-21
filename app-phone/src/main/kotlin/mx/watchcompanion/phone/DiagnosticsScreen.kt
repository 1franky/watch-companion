package mx.watchcompanion.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import mx.watchcompanion.core.diagnostics.DiagnosticEvent
import mx.watchcompanion.core.diagnostics.DiagnosticModule
import mx.watchcompanion.core.diagnostics.Outcome
import mx.watchcompanion.core.diagnostics.SyncStatus

private val timeFormat = DateTimeFormatter.ofPattern("dd/MM HH:mm:ss")

private fun localTime(epochMs: Long): String =
    timeFormat.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

/**
 * Diagnóstico (WC-23): estado de sincronización por módulo y los últimos eventos técnicos. El correlation ID completo
 * es el `X-Request-Id` con el que se busca la solicitud en los logs de Velora.
 */
@Composable
fun DiagnosticsScreen(
    events: List<DiagnosticEvent>,
    onClear: () -> Unit,
    onBack: () -> Unit,
    formatTime: (Long) -> String = ::localTime,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row {
            TextButton(onClick = onBack) { Text(stringResource(R.string.diagnostics_back)) }
        }
        Text(stringResource(R.string.diagnostics_title), style = MaterialTheme.typography.headlineMedium)
        if (events.isEmpty()) {
            Text(stringResource(R.string.diagnostics_empty))
            return@Column
        }

        Text(stringResource(R.string.diagnostics_sync), style = MaterialTheme.typography.titleMedium)
        for (status in SyncStatus.from(events)) {
            Column {
                Text(moduleName(status.module), style = MaterialTheme.typography.titleSmall)
                Text(
                    status.lastSuccessAt?.let { stringResource(R.string.diagnostics_last_success, formatTime(it)) }
                        ?: stringResource(R.string.diagnostics_no_success),
                )
                status.pendingError?.let {
                    Text(
                        stringResource(R.string.diagnostics_pending_error, it.code, formatTime(it.at)),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        HorizontalDivider()
        Text(stringResource(R.string.diagnostics_events, events.size), style = MaterialTheme.typography.titleMedium)
        for (event in events) {
            Column {
                Text(
                    "${formatTime(event.at)} · ${event.operation}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.diagnostics_event_detail, event.code, event.durationMs, event.correlationId),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (event.outcome == Outcome.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OutlinedButton(onClick = onClear) { Text(stringResource(R.string.diagnostics_clear)) }
    }
}

@Composable
private fun moduleName(module: DiagnosticModule): String = when (module) {
    DiagnosticModule.LINK -> stringResource(R.string.diagnostics_module_link)
    DiagnosticModule.VELORA -> stringResource(R.string.velora_section)
    DiagnosticModule.NEXORA -> stringResource(R.string.diagnostics_module_nexora)
}

@Preview(showBackground = true)
@Composable
private fun DiagnosticsScreenPreview() = MaterialTheme {
    val id = "6f1c3c62-5a0e-4a4e-9a51-0b2c8f8d2f10"
    DiagnosticsScreen(
        events = listOf(
            DiagnosticEvent(2_000, DiagnosticModule.VELORA, "0.1.0", "velora.refresh", id, Outcome.ERROR, "UNREACHABLE", 10_000),
            DiagnosticEvent(1_000, DiagnosticModule.LINK, "0.1.0", "link.ping", id, Outcome.OK, "CONNECTED", 42),
        ),
        onClear = {},
        onBack = {},
    )
}
