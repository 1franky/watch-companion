package mx.watchcompanion.phone.spike

import android.app.KeyguardManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Spike WC-43 (R-06), solo en debug. Prueba `DevicePolicyManager.lockNow()` como administrador de dispositivo
 * «legacy» (sin ser Device Owner ni Profile Owner), en primer plano y en segundo plano. Cada intento queda en
 * `files/spike-locknow.txt` para leerlo con `adb shell run-as`.
 */
object LockSpike {
    fun admin(context: Context) = ComponentName(context, LockSpikeAdminReceiver::class.java)

    fun dpm(context: Context): DevicePolicyManager = context.getSystemService(DevicePolicyManager::class.java)

    fun isAdmin(context: Context) = dpm(context).isAdminActive(admin(context))

    fun describe(context: Context): String {
        val dpm = dpm(context)
        return "dispositivo=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT} " +
            "build=${Build.DISPLAY} admin=${dpm.isAdminActive(admin(context))} " +
            "deviceOwner=${dpm.isDeviceOwnerApp(context.packageName)} profileOwner=${dpm.isProfileOwnerApp(context.packageName)}"
    }

    /** Intenta bloquear y registra qué pasó: excepción, y si quedó bloqueado y con la pantalla apagada. */
    fun lock(context: Context, origin: String): String {
        val result = try {
            dpm(context).lockNow()
            "lockNow sin excepción"
        } catch (e: SecurityException) {
            "SecurityException: ${e.message}"
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message}"
        }
        // El efecto real se mide después del intento, no se supone.
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        return record(
            context,
            "$origin · $result · bloqueado=${keyguard.isKeyguardLocked} seguro=${keyguard.isDeviceSecure} pantallaEncendida=${power.isInteractive}",
        )
    }

    fun record(context: Context, line: String): String {
        val stamped = "${LocalTime.now().format(TIME)} $line"
        File(context.filesDir, FILE).appendText(stamped + "\n")
        return stamped
    }

    fun history(context: Context): List<String> = File(context.filesDir, FILE).takeIf { it.exists() }?.readLines().orEmpty()

    private const val FILE = "spike-locknow.txt"
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
}

class LockSpikeAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        LockSpike.record(context, "administrador activado · ${LockSpike.describe(context)}")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        LockSpike.record(context, "administrador desactivado")
    }
}

/** `adb shell am broadcast -n mx.watchcompanion/mx.watchcompanion.phone.spike.LockSpikeTrigger` con la app al fondo. */
class LockSpikeTrigger : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        LockSpike.lock(context, "segundo plano")
    }
}
