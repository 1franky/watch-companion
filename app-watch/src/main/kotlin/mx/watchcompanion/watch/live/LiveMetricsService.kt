package mx.watchcompanion.watch.live

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
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
            // En el modo B el GPS del reloj necesita además el tipo location; solo se declara si hay permiso, o el
            // sistema rechaza el servicio entero.
            val withLocation = intent?.getBooleanExtra(EXTRA_LOCATION, false) == true &&
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or
                (if (withLocation) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
            startForeground(NOTIFICATION_ID, liveNotification(this, NOTIFICATION_ID, recording = withLocation), types)
        } catch (_: SecurityException) {
            // Sin el permiso de frecuencia cardíaca el sistema no lo admite. La sesión sigue con la pantalla encendida;
            // no se cierra la app por esto.
            stopSelf()
            return START_NOT_STICKY
        }
        // Si el sistema mata el proceso, el ejercicio sigue en Health Services y la app lo recupera al abrirse (attach).
        return START_NOT_STICKY
    }

    internal companion object {
        const val NOTIFICATION_ID = 30
        const val EXTRA_LOCATION = "location"
    }
}

/**
 * Notificación del foreground service con su *Ongoing Activity* (WC-58): el ícono en la esfera y en recientes lleva de
 * vuelta a la app con un toque mientras dura la sesión, sin pasar por el menú de apps. Desaparece con el servicio.
 *
 * El estado es un texto fijo: actualizar la FC en la esfera obligaría a repostear la notificación cada segundo.
 */
internal fun liveNotification(context: Context, notificationId: Int, recording: Boolean = false): Notification {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(LIVE_CHANNEL_ID, context.getString(R.string.live_channel), NotificationManager.IMPORTANCE_LOW),
    )
    // Con una sesión activa, MainActivity abre en Métricas.
    val open = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val builder = NotificationCompat.Builder(context, LIVE_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle(context.getString(R.string.live_notification_title))
        .setContentText(context.getString(R.string.live_notification_text))
        .setContentIntent(open)
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_WORKOUT)
    OngoingActivity.Builder(context, notificationId, builder)
        .setStaticIcon(R.drawable.ic_launcher)
        .setTouchIntent(open)
        .setStatus(Status.Builder().addTemplate(context.getString(if (recording) R.string.record_ongoing_status else R.string.live_ongoing_status)).build())
        .build()
        .apply(context)
    return builder.build()
}

internal const val LIVE_CHANNEL_ID = "live_metrics"

/**
 * [ForegroundKeeper] con [LiveMetricsService]. Se adquiere con la app en primer plano (al tocar «Iniciar» o «Grabar»):
 * un servicio de tipo location no puede arrancar desde segundo plano.
 */
class ServiceForegroundKeeper(private val context: Context) : ForegroundKeeper {
    override fun acquire(withLocation: Boolean) {
        context.startForegroundService(
            Intent(context, LiveMetricsService::class.java).putExtra(LiveMetricsService.EXTRA_LOCATION, withLocation),
        )
    }

    override fun release() {
        context.stopService(Intent(context, LiveMetricsService::class.java))
    }
}
