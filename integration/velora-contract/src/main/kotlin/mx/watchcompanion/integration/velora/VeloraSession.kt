package mx.watchcompanion.integration.velora

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mx.watchcompanion.core.diagnostics.DiagnosticModule
import mx.watchcompanion.core.diagnostics.DiagnosticsRecorder
import mx.watchcompanion.core.diagnostics.Span
import mx.watchcompanion.core.diagnostics.start
import mx.watchcompanion.core.session.SessionStore
import mx.watchcompanion.core.session.StoredSession
import mx.watchcompanion.integration.velora.api.infrastructure.HttpResponse
import mx.watchcompanion.integration.velora.api.models.LoginRequest
import mx.watchcompanion.integration.velora.api.models.RefreshRequest
import mx.watchcompanion.integration.velora.api.models.TokenResponse

sealed interface SessionState {
    /** Leyendo la sesión guardada al abrir la app. */
    data object Restoring : SessionState

    data class SignedOut(val reason: SignOutReason? = null) : SessionState

    data class SignedIn(val serverUrl: String, val email: String) : SessionState
}

enum class SignOutReason {
    /** Velora rechazó el refresh token: venció, se cerró la sesión en otro lado o se reutilizó un token rotado. */
    EXPIRED,
}

sealed interface LoginResult {
    data object Success : LoginResult
    data object InvalidCredentials : LoginResult

    /** Límite de intentos de login de Velora; [retryAfterSeconds] viene de `Retry-After`. */
    data class TooManyAttempts(val retryAfterSeconds: Long?) : LoginResult

    /** Velora rechazó el formato (p. ej. un correo inválido). */
    data object InvalidInput : LoginResult

    /** Servidor `http://` en una versión que exige HTTPS. */
    data object InsecureServer : LoginResult

    /** No hubo respuesta: sin red, servidor apagado o dirección equivocada. */
    data object Unreachable : LoginResult

    /** Respondió algo que no es Velora, o un error del servidor. */
    data object Unavailable : LoginResult
}

/** Ya no hay sesión; el estado cambió a [SessionState.SignedOut]. */
class SessionExpiredException : IOException("La sesión de Velora terminó")

/** Velora respondió un error al renovar; la sesión sigue y se puede reintentar. */
class ServiceUnavailableException(status: Int) : IOException("Velora respondió $status al renovar la sesión")

/**
 * Sesión de `app-phone` en Velora (WC-ADR-013), independiente de la de velora-android y de la de Nexora.
 *
 * El access token vive solo en memoria y el refresh token se guarda cifrado ([SessionStore] con Keystore). Las
 * renovaciones se serializan: en Velora el refresh token es de un solo uso y reutilizarlo revoca toda la sesión, así
 * que dos llamadas que encuentran el token vencido a la vez no pueden renovar las dos. Basado en el `SessionManager` de
 * velora-android.
 *
 * Cada operación con Velora queda en [diagnostics] (WC-23) con un correlation ID que viaja como `X-Request-Id`. Solo se
 * registran la operación, el resultado y la duración: nunca tokens, correo ni contraseña.
 */
class VeloraSession(
    private val store: SessionStore,
    /** Alcance de la app: avisar a Velora del cierre de sesión sigue aunque la pantalla ya no exista. */
    private val appScope: CoroutineScope,
    private val apiFor: (serverUrl: String) -> VeloraClient = { VeloraApi.create(it) },
    private val now: () -> Long = System::currentTimeMillis,
    private val diagnostics: DiagnosticsRecorder = DiagnosticsRecorder.NONE,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Restoring)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var api: VeloraClient? = null
    private var serverUrl: String? = null
    private var email: String? = null
    private var refreshToken: String? = null

    @Volatile private var access: AccessToken? = null

    private class AccessToken(val value: String, val expiresAt: Long)

    /** Recupera la sesión guardada sin tocar la red: la app abre aunque no haya conexión. */
    suspend fun restore() {
        mutex.withLock {
            val saved = store.read()
            val api = saved?.let { runCatching { apiFor(it.serverUrl) }.getOrNull() }
            if (saved == null || api == null) {
                _state.value = SessionState.SignedOut()
                return
            }
            this.api = api
            serverUrl = saved.serverUrl
            email = saved.email
            refreshToken = saved.refreshToken
            access = null
            _state.value = SessionState.SignedIn(saved.serverUrl, saved.email)
        }
    }

    suspend fun login(serverUrl: String, email: String, password: String): LoginResult {
        val span = span("velora.login")
        val (result, code) = attemptLogin(span, serverUrl, email, password)
        if (result == LoginResult.Success) span.ok() else span.error(code)
        return result
    }

    private suspend fun attemptLogin(
        span: Span,
        serverUrl: String,
        email: String,
        password: String,
    ): Pair<LoginResult, String> {
        val api = try {
            apiFor(serverUrl)
        } catch (_: IllegalArgumentException) {
            return LoginResult.InsecureServer to "INSECURE_SERVER"
        }
        val response = try {
            traced(span) { api.login(LoginRequest(email = email, password = password)) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return LoginResult.Unreachable to UNREACHABLE
        }
        return when (response.status) {
            200 -> {
                val tokens = bodyOrNull(response) ?: return LoginResult.Unavailable to INVALID_BODY
                mutex.withLock {
                    store.write(StoredSession(serverUrl, email, tokens.refreshToken))
                    this.api = api
                    this.serverUrl = serverUrl
                    this.email = email
                    adopt(api, tokens)
                    _state.value = SessionState.SignedIn(serverUrl, email)
                }
                LoginResult.Success to "OK"
            }
            401 -> LoginResult.InvalidCredentials to "INVALID_CREDENTIALS"
            429 -> LoginResult.TooManyAttempts(response.response.headers["Retry-After"]?.trim()?.toLongOrNull()) to
                "TOO_MANY_ATTEMPTS"
            400 -> LoginResult.InvalidInput to "INVALID_INPUT"
            else -> LoginResult.Unavailable to http(response.status)
        }
    }

    /**
     * Ejecuta una llamada autenticada. Renueva el access token si venció o está por vencer, y una vez más si Velora
     * responde 401. Lanza [SessionExpiredException] si ya no hay sesión, o [IOException] si no hay red.
     *
     * @param operation nombre para el registro, p. ej. `velora.listActivities`.
     */
    suspend fun <T : Any> call(
        operation: String = "velora.call",
        request: suspend VeloraClient.() -> HttpResponse<T>,
    ): HttpResponse<T> {
        val span = span(operation)
        try {
            val token = validAccessToken()
            val api = api ?: throw SessionExpiredException()
            var response = traced(span) { api.request() }
            if (response.status == 401) {
                refresh(stale = token)
                response = traced(span) { api.request() }
            }
            if (response.success) span.ok() else span.error(http(response.status))
            return response
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            span.error(codeFor(e))
            throw e
        }
    }

    /** Cierra la sesión en el teléfono de inmediato y avisa a Velora cuando pueda (sin red, el token caduca solo). */
    suspend fun logout() {
        val (api, token) = mutex.withLock {
            val pending = api to refreshToken
            endLocked(reason = null)
            pending
        }
        if (api != null && token != null) {
            val span = span("velora.logout")
            appScope.launch {
                try {
                    val response = traced(span) { api.logout(RefreshRequest(token)) }
                    if (response.success) span.ok() else span.error(http(response.status))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Sin conexión: la sesión ya no existe en el teléfono y el refresh token vence en 30 días.
                    span.error(UNREACHABLE)
                }
            }
        }
    }

    private suspend fun validAccessToken(): String {
        val current = access
        if (current != null && current.isFresh()) return current.value
        return refresh(stale = current?.value)
    }

    /** Renueva salvo que otra llamada ya lo haya hecho mientras esta esperaba el candado. */
    private suspend fun refresh(stale: String?): String = mutex.withLock {
        access?.let { if (it.value != stale && it.isFresh()) return it.value }
        val api = api ?: throw SessionExpiredException()
        val token = refreshToken ?: throw SessionExpiredException()
        val span = span("velora.refresh")
        val response = try {
            traced(span) { api.refreshTokens(RefreshRequest(token)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            span.error(UNREACHABLE)
            throw e
        }
        if (response.status == 401) {
            span.error(SESSION_EXPIRED)
            endLocked(SignOutReason.EXPIRED)
            throw SessionExpiredException()
        }
        if (!response.success) {
            span.error(http(response.status))
            throw ServiceUnavailableException(response.status)
        }
        val tokens = bodyOrNull(response) ?: run {
            span.error(INVALID_BODY)
            throw ServiceUnavailableException(response.status)
        }
        // Se guarda antes de usarlo: el anterior ya no sirve.
        store.write(StoredSession(checkNotNull(serverUrl), checkNotNull(email), tokens.refreshToken))
        adopt(api, tokens)
        span.ok()
        tokens.accessToken
    }

    private fun adopt(api: VeloraClient, tokens: TokenResponse) {
        refreshToken = tokens.refreshToken
        access = AccessToken(tokens.accessToken, now() + tokens.accessTokenExpiresIn * 1_000L)
        api.setBearerToken(tokens.accessToken)
    }

    private suspend fun endLocked(reason: SignOutReason?) {
        store.clear()
        api = null
        serverUrl = null
        email = null
        refreshToken = null
        access = null
        _state.value = SessionState.SignedOut(reason)
    }

    private fun span(operation: String) = diagnostics.start(DiagnosticModule.VELORA, operation, now)

    /** La solicitud lleva el correlation ID del evento como `X-Request-Id`. */
    private suspend fun <T> traced(span: Span, block: suspend () -> T): T =
        withContext(RequestId(span.correlationId)) { block() }

    private fun AccessToken.isFresh() = expiresAt - now() > EXPIRY_MARGIN_MS

    private suspend fun bodyOrNull(response: HttpResponse<TokenResponse>): TokenResponse? =
        try {
            response.body()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private companion object {
        const val UNREACHABLE = "UNREACHABLE"
        const val INVALID_BODY = "INVALID_BODY"
        const val SESSION_EXPIRED = "SESSION_EXPIRED"

        fun http(status: Int) = "HTTP_$status"

        fun codeFor(e: Exception) = when (e) {
            is SessionExpiredException -> SESSION_EXPIRED
            is ServiceUnavailableException -> "REFRESH_FAILED"
            is IOException -> UNREACHABLE
            else -> "CLIENT_ERROR" // p. ej. un enum nuevo que el cliente generado no sabe leer
        }

        /** Renovar un poco antes de que venza evita un 401 seguro en la siguiente llamada. */
        const val EXPIRY_MARGIN_MS = 30_000L
    }
}
