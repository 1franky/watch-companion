package mx.watchcompanion.core.permissions

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit

/** [PermissionChecker] real; necesita la `Activity` para saber si aún se puede mostrar el diálogo. */
class ActivityPermissionChecker(private val activity: Activity) : PermissionChecker {
    override fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

    override fun shouldShowRationale(permission: String) =
        ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
}

/** [ModuleSettings] en SharedPreferences privadas (excluidas del respaldo por `data_extraction_rules`). */
class SharedPreferencesModuleSettings(context: Context) : ModuleSettings {
    private val prefs = context.applicationContext.getSharedPreferences("wc_modules", Context.MODE_PRIVATE)

    override fun isEnabled(module: CompanionModule) = prefs.getBoolean("enabled_${module.name}", false)
    override fun setEnabled(module: CompanionModule, enabled: Boolean) =
        prefs.edit { putBoolean("enabled_${module.name}", enabled) }

    override fun wasRequested(permission: String) = prefs.getBoolean("requested_$permission", false)
    override fun markRequested(permissions: Collection<String>) =
        prefs.edit { permissions.forEach { putBoolean("requested_$it", true) } }
}

object PermissionActions {
    /** Ajustes de la app, para conceder un permiso denegado de forma definitiva. */
    fun appSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Pide al sistema revocar [permissions] cuando termine el proceso (Android 13+). Así, desactivar un módulo
     * también retira sus permisos (07-security-and-privacy.md §1, privilegio mínimo).
     */
    fun revokeOnKill(context: Context, permissions: List<String>) {
        if (permissions.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.applicationContext.revokeSelfPermissionsOnKill(permissions)
        }
    }
}
