package mx.watchcompanion.watch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.tooling.preview.devices.WearDevices
import kotlinx.coroutines.launch
import mx.watchcompanion.core.communication.LinkState
import mx.watchcompanion.core.datalayer.LinkSessionOwner

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = (application as LinkSessionOwner).linkSession
        setContent {
            val state by session.state.collectAsStateWithLifecycle()
            val scope = rememberCoroutineScope()
            LaunchedEffect(Unit) { session.refresh() }
            WatchHome(state = state, onCheck = { scope.launch { session.refresh() } })
        }
    }
}

/** Pantalla inicial de Fase 0: estado real del enlace con el teléfono. Se sustituye por Resumen en WC-29. */
@Composable
fun WatchHome(state: LinkState, onCheck: () -> Unit) {
    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = linkTitle(state), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
            if (state is LinkState.Connected) {
                Text(
                    text = stringResource(R.string.link_connected_detail, state.roundTripMs, state.peerAppVersion),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(onClick = onCheck, enabled = state != LinkState.Checking) {
                Text(stringResource(R.string.link_check))
            }
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
    WatchHome(LinkState.Connected("Galaxy S26 Ultra", "0.1.0", roundTripMs = 42, checkedAt = 0), onCheck = {})
