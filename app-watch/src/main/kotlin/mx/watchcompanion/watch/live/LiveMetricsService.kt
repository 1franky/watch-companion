package mx.watchcompanion.watch.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import mx.watchcompanion.watch.MainActivity
import mx.watchcompanion.watch.R

/**
 * Foreground service de tipo `health` que mantiene vivo el proceso mientras dura la sesión de métricas en vivo, para
 * que las actualizaciones de Health Services sigan llegando con la pantalla apagada o en ambiente (WC-30).
 *
 * No hace nada más: el ejercicio corre en Health Services y el estado vive en [LiveMetricsSession].
 */
class LiveMetricsService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } catch (_: SecurityException) {
            // Sin el permiso de frecuencia cardíaca el sistema no lo admite. La sesión sigue con la pantalla encendida;
            // no se cierra la app por esto.
            stopSelf()
            return START_NOT_STICKY
        }
        // Si el sistema mata el proceso, el ejercicio sigue en Health Services y la app lo recupera al abrirse (attach).
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.live_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.live_notification_title))
            .setContentText(getString(R.string.live_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_WORKOUT)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "live_metrics"
        const val NOTIFICATION_ID = 30
    }
}

/** [ForegroundKeeper] con [LiveMetricsService]. Se adquiere con la app en primer plano (al tocar «Iniciar»). */
class ServiceForegroundKeeper(private val context: Context) : ForegroundKeeper {
    override fun acquire() {
        context.startForegroundService(Intent(context, LiveMetricsService::class.java))
    }

    override fun release() {
        context.stopService(Intent(context, LiveMetricsService::class.java))
    }
}
