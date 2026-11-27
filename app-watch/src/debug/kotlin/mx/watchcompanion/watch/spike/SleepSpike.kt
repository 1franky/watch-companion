package mx.watchcompanion.watch.spike

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.clearPassiveListenerService
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.data.UserActivityInfo
import androidx.health.services.client.getCapabilities
import androidx.health.services.client.setPassiveListenerService
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * Spike WC-46 (R-07), solo en debug. ¿Health Services da a una app de terceros el estado «dormido» en el reloj, y con
 * qué latencia? Registra un `PassiveListenerService` con `setShouldUserActivityInfoBeRequested(true)` y anota cada
 * cambio de estado (cuándo llega y cuándo dice Health Services que ocurrió) en `files/spike-sueno.txt`.
 */
object SleepSpike {
    private const val FILE = "spike-sueno.txt"
    private val TIME = DateTimeFormatter.ofPattern("dd HH:mm:ss").withZone(ZoneId.systemDefault())

    fun record(context: Context, line: String) {
        File(context.filesDir, FILE).appendText("${TIME.format(Instant.now())} $line\n")
    }

    fun history(context: Context): List<String> = File(context.filesDir, FILE).takeIf { it.exists() }?.readLines().orEmpty()

    fun describe(info: UserActivityInfo): String {
        val delayS = (System.currentTimeMillis() - info.stateChangeTime.toEpochMilli()) / 1000
        return "estado=${info.userActivityState.name} cambió=${TIME.format(info.stateChangeTime)} (hace ${delayS}s) ejercicio=${info.exerciseInfo?.exerciseType?.name}"
    }

    suspend fun capabilities(context: Context): String {
        val caps = HealthServices.getClient(context).passiveMonitoringClient.getCapabilities()
        return "dispositivo=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT} build=${Build.DISPLAY} " +
            "estados=${caps.supportedUserActivityStates.map { it.name }}"
    }

    suspend fun register(context: Context) {
        val config = PassiveListenerConfig.builder().setShouldUserActivityInfoBeRequested(true).build()
        HealthServices.getClient(context).passiveMonitoringClient.setPassiveListenerService(SleepSpikeService::class.java, config)
    }

    suspend fun unregister(context: Context) {
        HealthServices.getClient(context).passiveMonitoringClient.clearPassiveListenerService()
    }
}

class SleepSpikeService : PassiveListenerService() {
    override fun onUserActivityInfoReceived(info: UserActivityInfo) {
        SleepSpike.record(this, "recibido · ${SleepSpike.describe(info)}")
    }

    override fun onPermissionLost() {
        SleepSpike.record(this, "permiso perdido")
    }
}

class SleepSpikeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val scope = rememberCoroutineScope()
            var log by remember { mutableStateOf(SleepSpike.history(this)) }
            val refresh = { log = SleepSpike.history(this) }
            fun run(label: String, block: suspend () -> String) {
                scope.launch {
                    val line = try {
                        "$label · ${block()}"
                    } catch (e: Exception) {
                        "$label · ${e.javaClass.simpleName}: ${e.message}"
                    }
                    SleepSpike.record(this@SleepSpikeActivity, line)
                    refresh()
                }
            }
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                SleepSpike.record(this, "permiso ACTIVITY_RECOGNITION=${if (granted) "concedido" else "negado"}")
                refresh()
            }
            MaterialTheme {
                Column(
                    Modifier.fillMaxSize().background(Color.Black).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Spike sueño")
                    Button(onClick = { permission.launch(Manifest.permission.ACTIVITY_RECOGNITION) }) { Text("1. Permiso") }
                    Button(onClick = { run("capacidades") { SleepSpike.capabilities(this@SleepSpikeActivity) } }) { Text("2. Capacidades") }
                    Button(onClick = {
                        run("registrar") {
                            val granted = checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
                            SleepSpike.register(this@SleepSpikeActivity)
                            "ok (permiso=$granted)"
                        }
                    }) { Text("3. Escuchar") }
                    Button(onClick = { run("quitar") { SleepSpike.unregister(this@SleepSpikeActivity); "ok" } }) { Text("4. Dejar de escuchar") }
                    for (line in log.takeLast(12).reversed()) Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
