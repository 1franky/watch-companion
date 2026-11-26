package mx.watchcompanion.phone.guardian

import kotlinx.coroutines.delay
import mx.watchcompanion.core.communication.GuardianCommand
import mx.watchcompanion.core.communication.GuardianCommandResult
import mx.watchcompanion.core.communication.GuardianCommandResult.Outcome

/** Lo que Guardian necesita del sistema para bloquear (04 §3); en producción, `DevicePolicyManager` y `KeyguardManager`. */
interface DeviceLocker {
    /** El usuario activó a la app como administrador de dispositivo (solo `force-lock`). */
    fun isAdminActive(): Boolean

    /** Hay PIN, patrón o contraseña: sin eso, `lockNow()` solo apaga la pantalla. */
    fun isDeviceSecure(): Boolean

    /** `DevicePolicyManager.lockNow()`. Lanza `SecurityException` si el administrador ya no está activo. */
    fun lockNow()

    fun isLocked(): Boolean
}

/**
 * Ejecuta en el teléfono las órdenes de Guardian que llegan del reloj (WC-44). El nonce y la caducidad ya los validó el
 * codec; aquí se comprueba de nuevo la caducidad (el mensaje pudo esperar) y, sobre todo, que el bloqueo **ocurrió**:
 * [Outcome.LOCKED] solo sale si `KeyguardManager` lo confirma.
 *
 * @param confirmTimeoutMs cuánto esperar a que el keyguard aparezca tras `lockNow()`.
 */
class PhoneGuardian(
    private val locker: DeviceLocker,
    private val clock: () -> Long = System::currentTimeMillis,
    private val confirmTimeoutMs: Long = CONFIRM_TIMEOUT_MS,
    private val pollMs: Long = POLL_MS,
) {
    suspend fun execute(command: GuardianCommand, expiresAt: Long): GuardianCommandResult {
        fun result(outcome: Outcome, code: String? = null) = GuardianCommandResult(command.commandId, outcome, code)

        if (!UUID.matches(command.commandId)) return result(Outcome.REJECTED, "INVALID_COMMAND")
        if (clock() >= expiresAt) return result(Outcome.EXPIRED)
        return when (command.action) {
            GuardianCommand.Action.LOCK -> lock(::result)
        }
    }

    private suspend fun lock(result: (Outcome, String?) -> GuardianCommandResult): GuardianCommandResult {
        if (!locker.isAdminActive()) return result(Outcome.UNAVAILABLE, "ADMIN_INACTIVE")
        // Sin bloqueo seguro, «bloquear» solo apagaría la pantalla: no se promete lo que no protege.
        if (!locker.isDeviceSecure()) return result(Outcome.UNAVAILABLE, "NO_SECURE_LOCK")
        try {
            locker.lockNow()
        } catch (_: SecurityException) {
            return result(Outcome.REJECTED, "SECURITY_EXCEPTION")
        }
        val deadline = clock() + confirmTimeoutMs
        while (true) {
            if (locker.isLocked()) return result(Outcome.LOCKED, null)
            if (clock() >= deadline) return result(Outcome.UNAVAILABLE, "NOT_CONFIRMED")
            delay(pollMs)
        }
    }

    companion object {
        const val CONFIRM_TIMEOUT_MS: Long = 2_000
        const val POLL_MS: Long = 100
        private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
