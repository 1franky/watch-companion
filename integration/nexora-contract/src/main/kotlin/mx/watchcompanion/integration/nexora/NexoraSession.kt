package mx.watchcompanion.integration.nexora

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mx.watchcompanion.core.diagnostics.DiagnosticModule
import mx.watchcompanion.core.diagnostics.DiagnosticsRecorder
import mx.watchcompanion.core.diagnostics.start
import mx.watchcompanion.core.session.LoginResult
import mx.watchcompanion.core.session.ServiceUnavailableException
import mx.watchcompanion.core.session.SessionExpiredException
import mx.watchcompanion.core.session.SessionState
import mx.watchcompanion.core.session.SessionStore
import mx.watchcompanion.core.session.SignOutReason
import mx.watchcompanion.core.session.StoredSession
import mx.watchcompanion.integration.nexora.api.apis.NexoraApi
import mx.watchcompanion.integration.nexora.api.infrastructure.HttpResponse
import mx.watchcompanion.integration.nexora.api.models.LoginRequest
import mx.watchcompanion.integration.nexora.api.models.RefreshRequest
import mx.watchcompanion.integration.nexora.api.models.TokenResponse

/**
 * Sesión de `app-phone` en Nexora (WC-37, WC-ADR-013): independiente de la de nexora-android y de la de Velora. Su
 * refresh token se guarda cifrado con su propia llave del Keystore (`Backend.NEXORA`), nunca junto al de Velora.
 *
 * Nexora rota el refresh token en cada uso y revoca el anterior (`AuthService.refresh`), así que las renovaciones se
 * serializan: dos llamadas que encuentran el access token vencido a la vez no pueden renovar las dos, o la segunda
 * usaría un token ya revocado y cerraría la sesión. El access token vive solo en memoria.
 *
 * Cada operación queda en [diagnostics] (WC-23) en el módulo NEXORA: solo operación, resultado y duración; nunca tokens,
 * correo, contraseña ni importes.
 */
class NexoraSession(
    private val store: SessionStore,
    /** Alcance de la app: avisar a Nexora del cierre de sesión sigue aunque la pantalla ya no exista. */
    private val appScope: CoroutineScope,
    private val apiFor: (serverUrl: String) -> NexoraApi = { NexoraClient.create(it) },
    private val now: () -> Long = System::currentTimeMillis,
    private val diagnostics: DiagnosticsRecorder = DiagnosticsRecorder.NONE,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Restoring)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var api: NexoraApi? = null
    private var serverUrl: String? = null
    private var email: String? = null
    private var refreshToken: String? = null

    @Volatile private var access: AccessToken? = null

    private class AccessToken(val value: String, val expiresAt: Long)

    /** Tokens válidos de una respuesta: el contrato no marca campos obligatorios, así que se comprueban aquí. */
    private class Tokens(val access: String, val refresh: String, val expiresInSeconds: Long)

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
        val span = diagnostics.start(DiagnosticModule.NEXORA, "nexora.login", now)
        val api = try {
            apiFor(serverUrl)
        } catch (_: IllegalArgumentException) {
            span.error("INSECURE_SERVER")
            return LoginResult.InsecureServer
        }
        val response = try {
            api.authLogin(LoginRequest(email = email, password = password))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            span.error(UNREACHABLE)
            return LoginResult.Unreachable
        }
        return when (response.status) {
            200 -> {
                val tokens = tokensOrNull(response) ?: run {
                    span.error(INVALID_BODY)
                    return LoginResult.Unavailable
                }
                mutex.withLock {
                    store.write(StoredSession(serverUrl, email, tokens.refresh))
                    this.api = api
                    this.serverUrl = serverUrl
                    this.email = email
                    adopt(api, tokens)
                    _state.value = SessionState.SignedIn(serverUrl, email)
                }
                span.ok()
                LoginResult.Success
            }
            401 -> LoginResult.InvalidCredentials.also { span.error("INVALID_CREDENTIALS") }
            429 -> LoginResult.TooManyAttempts(response.response.headers["Retry-After"]?.trim()?.toLongOrNull())
                .also { span.error("TOO_MANY_ATTEMPTS") }
            400 -> LoginResult.InvalidInput.also { span.error("INVALID_INPUT") }
            else -> LoginResult.Unavailable.also { span.error(http(response.status)) }
        }
    }

    /**
     * Ejecuta una llamada autenticada. Renueva el access token si venció o está por vencer, y una vez más si Nexora
     * responde 401. Lanza [SessionExpiredException] si ya no hay sesión, o [IOException] si no hay red.
     *
     * @param operation nombre para el registro, p. ej. `nexora.dashboardGet`.
     */
    suspend fun <T : Any> call(operation: String, request: suspend NexoraApi.() -> HttpResponse<T>): HttpResponse<T> {
        val span = diagnostics.start(DiagnosticModule.NEXORA, operation, now)
        try {
            val token = validAccessToken()
            val api = api ?: throw SessionExpiredException(BACKEND)
            var response = api.request()
            if (response.status == 401) {
                refresh(stale = token)
                response = api.request()
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

    /**
     * Revoca la sesión desde el teléfono: se cierra aquí de inmediato y se avisa a Nexora para que revoque el refresh
     * token. Sin red, la sesión ya no existe en el teléfono y el token vence solo.
     */
    suspend fun logout() {
        val (api, token) = mutex.withLock {
            val pending = api to refreshToken
            endLocked(reason = null)
            pending
        }
        if (api != null && token != null) {
            val span = diagnostics.start(DiagnosticModule.NEXORA, "nexora.logout", now)
            appScope.launch {
                try {
                    val response = api.authLogout(RefreshRequest(refreshToken = token))
                    if (response.success) span.ok() else span.error(http(response.status))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
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
        val api = api ?: throw SessionExpiredException(BACKEND)
        val token = refreshToken ?: throw SessionExpiredException(BACKEND)
        val span = diagnostics.start(DiagnosticModule.NEXORA, "nexora.refresh", now)
        val response = try {
            api.authRefresh(RefreshRequest(refreshToken = token))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            span.error(UNREACHABLE)
            throw e
        }
        if (response.status == 401) {
            span.error(SESSION_EXPIRED)
            endLocked(SignOutReason.EXPIRED)
            throw SessionExpiredException(BACKEND)
        }
        if (!response.success) {
            span.error(http(response.status))
            throw ServiceUnavailableException(BACKEND, response.status)
        }
        val tokens = tokensOrNull(response) ?: run {
            span.error(INVALID_BODY)
            throw ServiceUnavailableException(BACKEND, response.status)
        }
        // Se guarda antes de usarlo: el anterior ya está revocado.
        store.write(StoredSession(checkNotNull(serverUrl), checkNotNull(email), tokens.refresh))
        adopt(api, tokens)
        span.ok()
        tokens.access
    }

    private fun adopt(api: NexoraApi, tokens: Tokens) {
        refreshToken = tokens.refresh
        access = AccessToken(tokens.access, now() + tokens.expiresInSeconds * 1_000L)
        api.setBearerToken(tokens.access)
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

    private fun AccessToken.isFresh() = expiresAt - now() > EXPIRY_MARGIN_MS

    private suspend fun tokensOrNull(response: HttpResponse<TokenResponse>): Tokens? {
        val body = try {
            response.body()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        val access = body.accessToken?.takeIf { it.isNotBlank() } ?: return null
        val refresh = body.refreshToken?.takeIf { it.isNotBlank() } ?: return null
        // Sin vigencia declarada se asume corta: se renueva antes y nunca se usa un token vencido.
        return Tokens(access, refresh, body.expiresInSeconds?.takeIf { it > 0 } ?: DEFAULT_EXPIRES_IN_SECONDS)
    }

    private companion object {
        const val BACKEND = "Nexora"
        const val UNREACHABLE = "UNREACHABLE"
        const val INVALID_BODY = "INVALID_BODY"
        const val SESSION_EXPIRED = "SESSION_EXPIRED"
        const val DEFAULT_EXPIRES_IN_SECONDS = 300L

        fun http(status: Int) = "HTTP_$status"

        fun codeFor(e: Exception) = when (e) {
            is SessionExpiredException -> SESSION_EXPIRED
            is ServiceUnavailableException -> "REFRESH_FAILED"
            is IOException -> UNREACHABLE
            else -> "CLIENT_ERROR"
        }

        /** Renovar un poco antes de que venza evita un 401 seguro en la siguiente llamada. */
        const val EXPIRY_MARGIN_MS = 30_000L
    }
}
