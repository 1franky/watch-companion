package mx.watchcompanion.core.datalayer

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class DeviceIdTest {
    @Test
    fun esUnUuidAleatorioEstableEntreLlamadas() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val primero = DeviceId.get(context)
        assertEquals(primero, DeviceId.get(context))
        assertEquals(primero, UUID.fromString(primero).toString())
    }
}
