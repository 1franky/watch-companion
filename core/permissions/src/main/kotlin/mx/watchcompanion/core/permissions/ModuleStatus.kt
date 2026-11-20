package mx.watchcompanion.core.permissions

/** Estado de un módulo para el usuario. Un módulo sin sus permisos nunca se muestra como listo. */
sealed interface ModuleStatus {
    /** El usuario no lo ha activado: no se pide ningún permiso. */
    data object Disabled : ModuleStatus

    data object Ready : ModuleStatus

    /**
     * Activado, pero faltan permisos (nunca concedidos o revocados después).
     *
     * @property permanentlyDenied el sistema ya no mostrará el diálogo: solo se pueden conceder desde Ajustes.
     */
    data class NeedsPermissions(val missing: List<String>, val permanentlyDenied: Boolean) : ModuleStatus
}
