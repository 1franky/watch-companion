package mx.watchcompanion.core.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleGateTest {
    private class FakeSettings : ModuleSettings {
        val enabled = mutableSetOf<CompanionModule>()
        val requested = mutableSetOf<String>()
        override fun isEnabled(module: CompanionModule) = module in enabled
        override fun setEnabled(module: CompanionModule, enabled: Boolean) {
            if (enabled) this.enabled += module else this.enabled -= module
        }
        override fun wasRequested(permission: String) = permission in requested
        override fun markRequested(permissions: Collection<String>) { requested += permissions }
    }

    private class FakeChecker : PermissionChecker {
        val granted = mutableSetOf<String>()
        val rationale = mutableSetOf<String>()
        override fun isGranted(permission: String) = permission in granted
        override fun shouldShowRationale(permission: String) = permission in rationale
    }

    private val settings = FakeSettings()
    private val checker = FakeChecker()
    private val gate = ModuleGate(settings, checker, DeviceKind.WATCH, sdkInt = 36)
    private val hr = ModulePermissions.READ_HEART_RATE

    @Test
    fun unModuloNoActivadoNoPideNada() {
        assertEquals(ModuleStatus.Disabled, gate.status(CompanionModule.VELORA))
    }

    @Test
    fun alActivarloPideSusPermisosYQuedaListoAlConcederlos() {
        assertEquals(ModuleStatus.NeedsPermissions(listOf(hr), permanentlyDenied = false), gate.enable(CompanionModule.VELORA))
        checker.granted += hr
        assertEquals(ModuleStatus.Ready, gate.status(CompanionModule.VELORA))
    }

    @Test
    fun unPermisoRevocadoDespuesVuelveAPedirse() {
        settings.enabled += CompanionModule.VELORA
        checker.granted += hr
        assertEquals(ModuleStatus.Ready, gate.status(CompanionModule.VELORA))
        checker.granted -= hr
        assertTrue(gate.status(CompanionModule.VELORA) is ModuleStatus.NeedsPermissions)
    }

    @Test
    fun distingueUnRechazoDefinitivoDeUnoQueAunSePuedePreguntar() {
        gate.enable(CompanionModule.VELORA)
        gate.onPermissionsRequested(listOf(hr))
        checker.rationale += hr // rechazó una vez: el sistema aún puede preguntar
        assertEquals(ModuleStatus.NeedsPermissions(listOf(hr), false), gate.status(CompanionModule.VELORA))
        checker.rationale -= hr // «No volver a preguntar»
        assertEquals(ModuleStatus.NeedsPermissions(listOf(hr), true), gate.status(CompanionModule.VELORA))
    }

    @Test
    fun desactivarDevuelveLosPermisosQueYaNoSeUsan() {
        gate.enable(CompanionModule.VELORA)
        assertEquals(listOf(hr), gate.disable(CompanionModule.VELORA))
        assertEquals(ModuleStatus.Disabled, gate.status(CompanionModule.VELORA))
    }

    @Test
    fun unModuloSinPermisosQuedaListoAlActivarse() {
        assertEquals(ModuleStatus.Ready, gate.enable(CompanionModule.NEXORA))
    }

    @Test
    fun antesDeAndroid16SeUsaBodySensors() {
        assertEquals(
            listOf(ModulePermissions.BODY_SENSORS),
            ModulePermissions.required(CompanionModule.VELORA, DeviceKind.WATCH, sdkInt = 35),
        )
        assertTrue(ModulePermissions.required(CompanionModule.VELORA, DeviceKind.PHONE, 36).isEmpty())
    }
}
