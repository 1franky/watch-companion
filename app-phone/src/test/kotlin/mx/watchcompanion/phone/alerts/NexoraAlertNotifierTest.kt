package mx.watchcompanion.phone.alerts

import android.app.Notification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NexoraAlertNotifierTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun cadaTipoTieneTextoPropioSinMontosYEnLaPantallaBloqueadaSoloElAviso() {
        for (kind in AlertKind.entries) {
            val n = NexoraAlertNotifier.notification(context, NexoraAlert("00000000-0000-0000-0000-000000000001", kind))
            val texto = "${n.extras.getCharSequence(Notification.EXTRA_TITLE)} ${n.extras.getCharSequence(Notification.EXTRA_TEXT)}"
            assertFalse("$kind: $texto", texto.contains("$") || texto.any { it.isDigit() })
            assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
            assertNotNull(n.publicVersion)
            val publica = n.publicVersion
            assertEquals("Nexora", publica.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals(NexoraAlertNotifier.CHANNEL, n.channelId)
        }
    }
}
