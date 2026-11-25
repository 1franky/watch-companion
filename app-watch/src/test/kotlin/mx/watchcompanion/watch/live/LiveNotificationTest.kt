package mx.watchcompanion.watch.live

import android.app.Application
import android.content.ComponentName
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.ongoing.SerializationHelper
import mx.watchcompanion.watch.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** WC-58: la notificación de la sesión lleva una Ongoing Activity que abre la app. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class LiveNotificationTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `la notificacion lleva una Ongoing Activity con su estado`() {
        val notification = liveNotification(context, NOTIFICATION_ID)

        assertTrue(SerializationHelper.hasOngoingActivity(notification))
        val ongoing = SerializationHelper.create(notification)
        assertNotNull(ongoing)
        ongoing!!
        assertEquals("Métricas en vivo", ongoing.status?.getText(context, 0).toString())
    }

    @Test
    fun `es fija, de entrenamiento y al tocarla abre MainActivity`() {
        val notification = liveNotification(context, NOTIFICATION_ID)

        assertTrue(notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(NotificationCompat.CATEGORY_WORKOUT, notification.category)
        assertEquals(LIVE_CHANNEL_ID, notification.channelId)
        val opened = shadowOf(notification.contentIntent).savedIntent
        assertEquals(ComponentName(context, MainActivity::class.java), opened.component)
    }

    private companion object {
        const val NOTIFICATION_ID = 30
    }
}
