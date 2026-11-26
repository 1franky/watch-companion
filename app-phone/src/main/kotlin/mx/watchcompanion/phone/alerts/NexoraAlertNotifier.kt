package mx.watchcompanion.phone.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import mx.watchcompanion.phone.MainActivity
import mx.watchcompanion.phone.R

/**
 * Publica un aviso de Nexora como notificación local del teléfono; Wear OS la puentea al reloj (03 §2). Es discreta:
 * texto propio por tipo, sin montos, tarjeta ni cuenta, y en la pantalla bloqueada solo «Nexora: tienes un aviso»
 * (07 §3). Tocarla abre la app del companion, nunca ejecuta una operación.
 */
class NexoraAlertNotifier(private val context: Context) {
    fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** `false` si no hay permiso para notificar: el aviso queda para la próxima pasada. */
    fun publish(alert: NexoraAlert): Boolean {
        if (!canNotify()) return false
        ensureChannel()
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        try {
            manager.notify(alert.id.hashCode(), notification(context, alert))
        } catch (_: SecurityException) {
            return false
        }
        return true
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.alerts_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.alerts_channel_description)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            },
        )
    }

    companion object {
        const val CHANNEL = "avisos-nexora"

        fun notification(context: Context, alert: NexoraAlert): android.app.Notification {
            val (title, text) = when (alert.kind) {
                AlertKind.PAYMENT_DUE_SOON -> R.string.alert_payment_due_soon_title to R.string.alert_payment_due_soon_text
                AlertKind.PAYMENT_DUE -> R.string.alert_payment_due_title to R.string.alert_payment_due_text
                AlertKind.INSTALLMENT_DUE -> R.string.alert_installment_due_title to R.string.alert_installment_due_text
                AlertKind.SCHEDULED_CHARGE_FAILED -> R.string.alert_charge_failed_title to R.string.alert_charge_failed_text
            }
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val public = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(context.getString(R.string.alert_public_title))
                .setContentText(context.getString(R.string.alert_public_text))
                .build()
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(context.getString(title))
                .setContentText(context.getString(text))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(public)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        }
    }
}
