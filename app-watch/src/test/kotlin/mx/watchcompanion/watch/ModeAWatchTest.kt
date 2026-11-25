package mx.watchcompanion.watch

import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import mx.watchcompanion.core.permissions.CompanionModule
import mx.watchcompanion.core.permissions.DeviceKind
import mx.watchcompanion.core.permissions.ModulePermissions
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * WC-34, capa del reloj: el reloj no tiene red propia (WC-ADR-013) y el modo A no pide ubicación (01 §3.1), así que no
 * puede subir nada aunque alguien lo intente. Si una dependencia agrega INTERNET al manifiesto, esta prueba falla. La
 * grabación del modo B (WC-53) tampoco puede subirse sola: queda en el reloj hasta que el teléfono la lleve (WC-54).
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

    /**
     * Desde WC-53 el manifiesto declara ubicación para el modo B, pero el modo A sigue sin pedirla: activar Velora solo
     * pide la frecuencia cardíaca, y la ubicación se pide únicamente al tocar «Grabar ruta». Que la sesión de métricas
     * no encienda el GPS ni guarde nada lo prueba `LiveMetricsSessionTest.elModoANoEnciendeElGpsNiGuardaNada`.
     */
    @Test
    fun activarVeloraNoPideUbicacion() {
        val required = ModulePermissions.required(CompanionModule.VELORA, DeviceKind.WATCH, 36)
        assertTrue("El modo A no necesita ubicación: $required", required.none { "LOCATION" in it })
    }
}
