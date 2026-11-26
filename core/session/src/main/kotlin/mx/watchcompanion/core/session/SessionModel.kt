package mx.watchcompanion.core.session

import java.io.IOException

// Estado y resultados de las sesiones propias de app-phone (WC-ADR-013), comunes a Velora y Nexora.

sealed interface SessionState {
    /** Leyendo la sesión guardada al abrir la app. */
    data object Restoring : SessionState

    data class SignedOut(val reason: SignOutReason? = null) : SessionState

    data class SignedIn(val serverUrl: String, val email: String) : SessionState
}

enum class SignOutReason {
    /** El backend rechazó el refresh token: venció, se cerró la sesión en otro lado o se reutilizó un token rotado. */
    EXPIRED,
}

sealed interface LoginResult {
    data object Success : LoginResult
    data object InvalidCredentials : LoginResult

    /** Límite de intentos de login del backend; [retryAfterSeconds] viene de `Retry-After`. */
    data class TooManyAttempts(val retryAfterSeconds: Long?) : LoginResult

    /** El backend rechazó el formato (p. ej. un correo inválido). */
    data object InvalidInput : LoginResult

    /** Servidor `http://` en una versión que exige HTTPS. */
    data object InsecureServer : LoginResult

    /** No hubo respuesta: sin red, servidor apagado o dirección equivocada. */
    data object Unreachable : LoginResult

    /** Respondió algo que no es el backend, o un error del servidor. */
    data object Unavailable : LoginResult
}

/** Ya no hay sesión; el estado cambió a [SessionState.SignedOut]. */
class SessionExpiredException(backend: String) : IOException("La sesión de $backend terminó")

/** El backend respondió un error al renovar; la sesión sigue y se puede reintentar. */
class ServiceUnavailableException(backend: String, status: Int) : IOException("$backend respondió $status al renovar la sesión")
