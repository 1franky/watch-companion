package mx.watchcompanion.watch.guardian

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import mx.watchcompanion.core.datalayer.Capabilities
import mx.watchcompanion.watch.MainActivity
import mx.watchcompanion.watch.R
import mx.watchcompanion.watch.WatchCompanionApp

/** ¿Está el teléfono con la app alcanzable y cerca? `null` si la Data Layer no respondió. */
suspend fun phoneNearby(context: Context): Boolean? = try {
    Wearable.getCapabilityClient(context).getCapability(Capabilities.PHONE_APP, CapabilityClient.FILTER_REACHABLE).await()
        .nodes.any { it.isNearby }
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

/** Plazo y estado en `SharedPreferences`: no son datos personales, solo una preferencia y una marca de tiempo. */
class SharedPreferencesDisconnectStore(context: Context) : DisconnectStore {
    private val prefs = context.getSharedPreferences("guardian", Context.MODE_PRIVATE)

    override var delayMinutes: Int?
        get() = prefs.getInt(KEY_DELAY, 0).takeIf { it > 0 }
        set(value) = prefs.edit { putInt(KEY_DELAY, value ?: 0) }

    override var state: DisconnectState
        get() = DisconnectState(prefs.getLong(KEY_SINCE, 0).takeIf { it > 0 }, prefs.getBoolean(KEY_ALERTED, false))
        set(value) = prefs.edit {
            putLong(KEY_SINCE, value.since ?: 0)
            putBoolean(KEY_ALERTED, value.alerted)
        }

    private companion object {
        const val KEY_DELAY = "aviso_desconexion_min"
        const val KEY_SINCE = "desconectado_desde"
        const val KEY_ALERTED = "desconexion_avisada"
    }
}

/** Una sola revisión pendiente a la vez; reprogramar reemplaza la anterior. */
class WorkDisconnectScheduler(private val context: Context) : DisconnectScheduler {
    override fun schedule(delayMs: Long) = guarded {
        val request = OneTimeWorkRequestBuilder<DisconnectCheckWorker>().setInitialDelay(delayMs, TimeUnit.MILLISECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancel() = guarded { WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME) }

    /** WorkManager no está inicializado en las pruebas con Robolectric. */
    private fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalStateException) {
        }
    }

    private companion object {
        const val WORK_NAME = "aviso-desconexion"
    }
}

/** Revisa si el teléfono volvió; si la Data Layer no responde, reintenta en vez de avisar sin saber. */
class DisconnectCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val nearby = phoneNearby(applicationContext) ?: return Result.retry()
        (applicationContext as WatchCompanionApp).disconnectMonitor.onCheck(nearby)
        return Result.success()
    }
}

/**
 * El aviso: una vibración propia (uso de alarma, para que se sienta aunque la app esté en segundo plano) y, si hay
 * permiso, una notificación que dice desde cuándo no está el teléfono. No habla de robo ni de pérdida.
 */
class NotificationDisconnectAlerter(private val context: Context) : DisconnectAlerter {
    private val manager = context.getSystemService(NotificationManager::class.java)

    override fun alert(since: Long) {
        val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
        if (vibrator.hasVibrator()) {
            vibrator.vibrate(
                VibrationEffect.createWaveform(TIMINGS, AMPLITUDES, -1),
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
            )
        }
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.guardian_channel), NotificationManager.IMPORTANCE_HIGH)
                .apply { enableVibration(false) },
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val time = TIME.format(Instant.ofEpochMilli(since).atZone(ZoneId.systemDefault()))
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(context.getString(R.string.guardian_alert_title))
            .setContentText(context.getString(R.string.guardian_alert_text, time))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun clear() = manager.cancel(NOTIFICATION_ID)

    private companion object {
        const val CHANNEL_ID = "guardian-desconexion"
        const val NOTIFICATION_ID = 45
        val TIMINGS = longArrayOf(0, 250, 150, 250, 150, 600)
        val AMPLITUDES = intArrayOf(0, 255, 0, 255, 0, 255)
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
