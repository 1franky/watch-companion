package mx.watchcompanion.watch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.Text
import mx.watchcompanion.core.permissions.ModulePermissions
import mx.watchcompanion.core.permissions.ModuleStatus

enum class ModuleAction { ENABLE, REQUEST, OPEN_SETTINGS, DISABLE }

/** Estado de un módulo y la única acción que tiene sentido en ese estado. */
@Composable
fun ModuleRow(title: String, status: ModuleStatus, onAction: (ModuleAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$title · ${statusText(status)}", textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        when (status) {
            ModuleStatus.Disabled ->
                FilledTonalButton(onClick = { onAction(ModuleAction.ENABLE) }) { Text(stringResource(R.string.module_action_enable)) }
            ModuleStatus.Ready ->
                OutlinedButton(onClick = { onAction(ModuleAction.DISABLE) }) { Text(stringResource(R.string.module_action_disable)) }
            is ModuleStatus.NeedsPermissions ->
                if (status.permanentlyDenied) {
                    FilledTonalButton(onClick = { onAction(ModuleAction.OPEN_SETTINGS) }) {
                        Text(stringResource(R.string.module_action_settings))
                    }
                } else {
                    FilledTonalButton(onClick = { onAction(ModuleAction.REQUEST) }) {
                        Text(stringResource(R.string.module_action_grant))
                    }
                }
        }
    }
}

@Composable
private fun statusText(status: ModuleStatus): String = when (status) {
    ModuleStatus.Disabled -> stringResource(R.string.module_status_disabled)
    ModuleStatus.Ready -> stringResource(R.string.module_status_ready)
    is ModuleStatus.NeedsPermissions -> {
        val name = permissionName(status.missing.first())
        if (status.permanentlyDenied) stringResource(R.string.module_status_denied, name)
        else stringResource(R.string.module_status_missing, name)
    }
}

@Composable
private fun permissionName(permission: String): String = when (permission) {
    ModulePermissions.READ_HEART_RATE, ModulePermissions.BODY_SENSORS -> stringResource(R.string.permission_heart_rate)
    else -> stringResource(R.string.permission_other)
}
