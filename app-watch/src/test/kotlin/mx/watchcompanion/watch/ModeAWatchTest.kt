package mx.watchcompanion.watch

import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * WC-34, capa del reloj: en el modo A el reloj no tiene red propia (WC-ADR-013) ni pide ubicación (01 §3.1), así que no
 * puede subir nada aunque alguien lo intente. Si una dependencia agrega INTERNET al manifiesto, esta prueba falla.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ModeAWatchTest {
    private val permissions: Set<String> by lazy {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
    }

    @Test
    fun elRelojNoTienePermisoDeInternet() {
        assertTrue("Se leyeron los permisos del manifiesto: $permissions", "android.permission.health.READ_HEART_RATE" in permissions)
        assertFalse("El reloj no debe tener red propia en el modo A", "android.permission.INTERNET" in permissions)
    }

    @Test
    fun elRelojNoPideUbicacion() {
        val location = permissions.filter { "LOCATION" in it }
        assertTrue("La sesión de métricas no usa ubicación: $location", location.isEmpty())
    }
}
