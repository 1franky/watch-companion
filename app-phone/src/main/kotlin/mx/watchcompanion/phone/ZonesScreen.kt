package mx.watchcompanion.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import mx.watchcompanion.core.communication.HeartRateZones

/**
 * Zonas de frecuencia cardíaca (WC-32). Parten de la FC máxima de Velora con la misma regla que sus analíticas
 * (`hr-zones/1`) y se pueden ajustar a mano. Al guardar se envían al reloj.
 *
 * @param veloraMax resultado de la última consulta a Velora; al llegar un valor, rellena los campos.
 * @param delivery qué pasó con el último envío al reloj.
 * @param now instante para fechar la versión guardada.
 */
@Composable
fun ZonesScreen(
    saved: HeartRateZones?,
    veloraMax: VeloraMax?,
    delivery: ZonesDelivery?,
    now: () -> Long,
    onUseVelora: () -> Unit,
    onSave: (HeartRateZones) -> Unit,
    onBack: () -> Unit,
) {
    var maxText by rememberSaveable { mutableStateOf(saved?.maxHeartRateBpm?.toString().orEmpty()) }
    val bounds = rememberSaveable(saver = boundsSaver) {
        mutableStateListOf(*Array(HeartRateZones.ZONES) { saved?.lowerBoundsBpm?.get(it)?.toString().orEmpty() })
    }
    var source by rememberSaveable { mutableStateOf(saved?.source ?: HeartRateZones.Source.MANUAL) }
    var error by rememberSaveable { mutableStateOf<ZonesInput.Reason?>(null) }

    fun fill(max: Int, from: HeartRateZones.Source) {
        val zones = HeartRateZones.fromMax(max, from, now())
        maxText = max.toString()
        zones.lowerBoundsBpm.forEachIndexed { i, bpm -> bounds[i] = bpm.toString() }
        source = from
        error = null
    }

    LaunchedEffect(veloraMax) { (veloraMax as? VeloraMax.Value)?.let { fill(it.bpm, HeartRateZones.Source.VELORA) } }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.zones_back)) }
        Text(
            stringResource(R.string.zones_title),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(stringResource(R.string.zones_explain), style = MaterialTheme.typography.bodyMedium)
        Text(
            when {
                saved == null -> stringResource(R.string.zones_none)
                saved.source == HeartRateZones.Source.VELORA -> stringResource(R.string.zones_saved_velora, saved.maxHeartRateBpm)
                else -> stringResource(R.string.zones_saved_manual, saved.maxHeartRateBpm)
            },
            style = MaterialTheme.typography.titleMedium,
        )

        OutlinedButton(onClick = onUseVelora, enabled = veloraMax != VeloraMax.Loading) {
            Text(stringResource(R.string.zones_use_velora))
        }
        veloraMessage(veloraMax)?.let {
            Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
        }
        HorizontalDivider()

        OutlinedTextField(
            value = maxText,
            onValueChange = {
                maxText = it.filter(Char::isDigit).take(3)
                source = HeartRateZones.Source.MANUAL
            },
            label = { Text(stringResource(R.string.zones_max_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            onClick = {
                val max = maxText.toIntOrNull()?.takeIf { it in HeartRateZones.MAX_HR_RANGE }
                if (max != null) fill(max, HeartRateZones.Source.MANUAL) else error = ZonesInput.Reason.MAX_OUT_OF_RANGE
            },
        ) { Text(stringResource(R.string.zones_recalculate)) }
        for (i in 0 until HeartRateZones.ZONES) {
            OutlinedTextField(
                value = bounds[i],
                onValueChange = {
                    bounds[i] = it.filter(Char::isDigit).take(3)
                    source = HeartRateZones.Source.MANUAL
                },
                label = { Text(stringResource(R.string.zones_bound_label, i + 1)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        error?.let {
            Text(
                errorMessage(it),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(
            onClick = {
                when (val input = parseZones(maxText, bounds, source, now())) {
                    is ZonesInput.Valid -> {
                        error = null
                        onSave(input.zones)
                    }
                    is ZonesInput.Invalid -> error = input.reason
                }
            },
        ) { Text(stringResource(R.string.zones_save)) }
        delivery?.let {
            Text(
                stringResource(if (it == ZonesDelivery.SENT) R.string.zones_sent else R.string.zones_pending),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun veloraMessage(result: VeloraMax?): String? = when (result) {
    null -> null
    VeloraMax.Loading -> stringResource(R.string.zones_velora_loading)
    is VeloraMax.Value -> stringResource(R.string.zones_velora_value, result.bpm)
    VeloraMax.NotSet -> stringResource(R.string.zones_velora_not_set)
    VeloraMax.SignedOut -> stringResource(R.string.zones_velora_signed_out)
    is VeloraMax.Failed -> stringResource(R.string.zones_velora_failed, result.code)
}

@Composable
private fun errorMessage(reason: ZonesInput.Reason): String = when (reason) {
    ZonesInput.Reason.MAX_OUT_OF_RANGE -> stringResource(R.string.zones_error_max)
    ZonesInput.Reason.BOUND_MISSING -> stringResource(R.string.zones_error_missing)
    ZonesInput.Reason.BOUND_TOO_LOW -> stringResource(R.string.zones_error_low, HeartRateZones.MIN_BOUND_BPM)
    ZonesInput.Reason.NOT_ASCENDING -> stringResource(R.string.zones_error_order)
    ZonesInput.Reason.ABOVE_MAX -> stringResource(R.string.zones_error_above_max)
}

private val boundsSaver = listSaver<SnapshotStateList<String>, String>(
    save = { it.toList() },
    restore = { mutableStateListOf(*it.toTypedArray()) },
)

@Preview(showBackground = true)
@Composable
private fun ZonesScreenPreview() = MaterialTheme {
    ZonesScreen(
        saved = HeartRateZones.fromMax(185, HeartRateZones.Source.VELORA, 0),
        veloraMax = null,
        delivery = ZonesDelivery.SENT,
        now = { 0 },
        onUseVelora = {},
        onSave = {},
        onBack = {},
    )
}
