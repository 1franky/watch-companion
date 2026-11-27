package mx.watchcompanion.phone.drafts

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
 * Aviso de borradores del reloj por confirmar (WC-62, 03 §2). Una sola notificación con cuántos hay:
 * - **Sin datos del borrador:** ni importe, ni cuenta, ni categoría, ni nota; en la pantalla bloqueada, texto genérico.
 * - **Solo en el teléfono** (`setLocalOnly`): el borrador salió del reloj, no tiene caso devolverle el aviso.
 * - Tocarla abre la app, donde está la lista; nunca confirma nada.
 * - Suena al llegar uno nuevo; si solo cambia el número, se actualiza en silencio, y se quita cuando no queda ninguno.
 */
class DraftNotifier(private val context: Context) {

    /** Llegó un borrador nuevo: muestra (o actualiza con sonido) el aviso. Sin permiso, no hace nada. */
    fun announce(pending: Int) {
        if (pending <= 0) return cancel()
        post(pending, alert = true)
    }

    /** Cambió cuántos quedan: actualiza el aviso solo si sigue a la vista, o lo quita si ya no hay. */
    fun refresh(pending: Int) {
        if (pending <= 0) return cancel()
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.activeNotifications.any { it.id == NOTIFICATION_ID }) post(pending, alert = false)
    }

    fun cancel() = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

    private fun post(pending: Int, alert: Boolean) {
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return
        ensureChannel()
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(NOTIFICATION_ID, notification(context, pending, alert))
        } catch (_: SecurityException) {
            // Se quitó el permiso entre la revisión y el aviso: el borrador sigue en la lista de la app.
        }
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.drafts_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.drafts_channel_description)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            },
        )
    }

    companion object {
        const val CHANNEL = "borradores-reloj"
        const val NOTIFICATION_ID = 6201

        fun notification(context: Context, pending: Int, alert: Boolean): android.app.Notification {
            val open = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val public = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(context.getString(R.string.drafts_notice_public_title))
                .setContentText(context.getString(R.string.drafts_notice_public_text))
                .build()
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(context.getString(R.string.drafts_notice_title))
                .setContentText(context.resources.getQuantityString(R.plurals.drafts_notice_text, pending, pending))
                .setNumber(pending)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(public)
                .setLocalOnly(true)
                .setOnlyAlertOnce(!alert)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        }
    }
}
