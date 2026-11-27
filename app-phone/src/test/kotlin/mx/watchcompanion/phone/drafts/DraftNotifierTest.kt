package mx.watchcompanion.phone.drafts

import android.app.Notification
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DraftNotifierTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun texto(n: Notification) =
        "${n.extras.getCharSequence(Notification.EXTRA_TITLE)} ${n.extras.getCharSequence(Notification.EXTRA_TEXT)}"

    @Test
    fun soloDiceCuantosHaySinDatosDelBorradorYNoVaAlReloj() {
        val uno = DraftNotifier.notification(context, pending = 1, alert = true)
        assertFalse(texto(uno), texto(uno).contains("$") || texto(uno).any { it.isDigit() })
        assertTrue(texto(DraftNotifier.notification(context, pending = 3, alert = true)).contains("3 gastos o ingresos"))

        assertEquals(Notification.VISIBILITY_PRIVATE, uno.visibility)
        assertEquals("Watch Companion", uno.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertTrue("solo en el teléfono", NotificationCompat.getLocalOnly(uno))
        assertEquals(DraftNotifier.CHANNEL, uno.channelId)
    }

    @Test
    fun soloSuenaAlLlegarUnoNuevo() {
        val nuevo = DraftNotifier.notification(context, pending = 2, alert = true)
        val cambio = DraftNotifier.notification(context, pending = 1, alert = false)
        assertEquals(0, nuevo.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        assertEquals(Notification.FLAG_ONLY_ALERT_ONCE, cambio.flags and Notification.FLAG_ONLY_ALERT_ONCE)
    }
}
