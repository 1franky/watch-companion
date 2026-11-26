package mx.watchcompanion.phone.spike

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Pantalla del spike WC-43 (solo debug, se abre con
 * `adb shell am start -n mx.watchcompanion/mx.watchcompanion.phone.spike.LockSpikeActivity`).
 */
class LockSpikeActivity : ComponentActivity() {
    private var refresh: () -> Unit = {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var info by remember { mutableStateOf(LockSpike.describe(this)) }
                var history by remember { mutableStateOf(LockSpike.history(this)) }
                refresh = {
                    info = LockSpike.describe(this)
                    history = LockSpike.history(this)
                }
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Spike WC-43 · lockNow()", style = MaterialTheme.typography.titleLarge)
                    Text(info)
                    Button(onClick = {
                        startActivity(
                            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, LockSpike.admin(this@LockSpikeActivity))
                                .putExtra(
                                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                    "Prueba: solo permite que Watch Companion bloquee la pantalla. Se desactiva al terminar.",
                                ),
                        )
                    }) { Text("1. Activar administrador") }
                    Button(onClick = {
                        LockSpike.lock(this@LockSpikeActivity, "primer plano")
                        refresh()
                    }) { Text("2. Bloquear ahora") }
                    OutlinedButton(onClick = {
                        LockSpike.dpm(this@LockSpikeActivity).removeActiveAdmin(LockSpike.admin(this@LockSpikeActivity))
                        refresh()
                    }) { Text("3. Desactivar administrador") }
                    history.asReversed().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }
}
