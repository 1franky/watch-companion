package mx.watchcompanion.phone.guardian

import android.app.KeyguardManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mx.watchcompanion.phone.R

/**
 * Administrador de dispositivo de Guardian (WC-43, 04 §3): solo la política `force-lock`. Lo activa el usuario desde la
 * app; al activarlo o quitarlo (también desde Ajustes) la pantalla se entera por [GuardianAdmin.active].
 */
class GuardianAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) = GuardianAdmin.refresh(context)

    override fun onDisabled(context: Context, intent: Intent) = GuardianAdmin.refresh(context, active = false)
}

/** Activar, quitar y consultar el administrador de dispositivo. */
object GuardianAdmin {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun component(context: Context) = ComponentName(context, GuardianAdminReceiver::class.java)

    private fun dpm(context: Context): DevicePolicyManager = context.getSystemService(DevicePolicyManager::class.java)

    fun isActive(context: Context): Boolean = dpm(context).isAdminActive(component(context))

    /** `onDisabled` llega antes de que el sistema lo quite del todo; por eso puede forzarse el valor. */
    fun refresh(context: Context, active: Boolean = isActive(context)) {
        _active.value = active
    }

    /** El diálogo del sistema, con la explicación de para qué se usa. */
    fun activationIntent(context: Context): Intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
        .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, context.getString(R.string.guardian_admin_explanation))

    fun deactivate(context: Context) {
        dpm(context).removeActiveAdmin(component(context))
        refresh(context, active = false)
    }
}

class AndroidDeviceLocker(private val context: Context) : DeviceLocker {
    private val keyguard get() = context.getSystemService(KeyguardManager::class.java)

    override fun isAdminActive() = GuardianAdmin.isActive(context)

    override fun isDeviceSecure() = keyguard.isDeviceSecure

    override fun lockNow() = context.getSystemService(DevicePolicyManager::class.java).lockNow()

    override fun isLocked() = keyguard.isKeyguardLocked
}
