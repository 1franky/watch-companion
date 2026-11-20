package mx.watchcompanion.core.permissions

/** Consulta al sistema si un permiso está concedido y si todavía puede mostrar el diálogo. */
interface PermissionChecker {
    fun isGranted(permission: String): Boolean

    /** `shouldShowRequestPermissionRationale`: `false` tras un rechazo definitivo (o antes de pedirlo nunca). */
    fun shouldShowRationale(permission: String): Boolean
}

/** Preferencias del usuario por módulo y qué permisos ya se pidieron alguna vez. */
interface ModuleSettings {
    fun isEnabled(module: CompanionModule): Boolean
    fun setEnabled(module: CompanionModule, enabled: Boolean)
    fun wasRequested(permission: String): Boolean
    fun markRequested(permissions: Collection<String>)
}

/**
 * Combina la preferencia del usuario con el estado real de los permisos. Se vuelve a consultar en cada `onResume`,
 * así que un permiso revocado desde Ajustes desactiva la función sin cerrar la app.
 */
class ModuleGate(
    private val settings: ModuleSettings,
    private val checker: PermissionChecker,
    private val device: DeviceKind,
    private val sdkInt: Int,
) {
    fun required(module: CompanionModule): List<String> = ModulePermissions.required(module, device, sdkInt)

    fun status(module: CompanionModule): ModuleStatus {
        if (!settings.isEnabled(module)) return ModuleStatus.Disabled
        val missing = required(module).filterNot(checker::isGranted)
        if (missing.isEmpty()) return ModuleStatus.Ready
        // Pedido antes y sin rationale = el usuario eligió «No volver a preguntar» (o el sistema lo bloqueó).
        val permanentlyDenied = missing.all { settings.wasRequested(it) && !checker.shouldShowRationale(it) }
        return ModuleStatus.NeedsPermissions(missing, permanentlyDenied)
    }

    fun enable(module: CompanionModule): ModuleStatus {
        settings.setEnabled(module, true)
        return status(module)
    }

    /** Desactiva el módulo y devuelve los permisos que ya no usa ningún otro módulo activo, para revocarlos. */
    fun disable(module: CompanionModule): List<String> {
        settings.setEnabled(module, false)
        val stillUsed = CompanionModule.entries.filter(settings::isEnabled).flatMap(::required).toSet()
        return required(module).filterNot { it in stillUsed }
    }

    fun onPermissionsRequested(permissions: Collection<String>) = settings.markRequested(permissions)
}
