package mx.watchcompanion.watch.live

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Disponibilidad del sensor de frecuencia cardíaca, tal como la informa Health Services. */
enum class HeartRateAvailability { UNKNOWN, ACQUIRING, AVAILABLE, UNAVAILABLE, OFF_BODY }

/** Por qué terminó una sesión. Solo [USER] es un fin normal; los demás se explican en pantalla. */
enum class EndReason { USER, PERMISSION_LOST, SUPERSEDED, OTHER }

/**
 * Duración activa a partir del último checkpoint de Health Services: [activeMs] acumulados en [atEpochMs]. Mientras
 * corre, la pantalla la extrapola con su propio reloj para no esperar a la siguiente actualización.
 */
data class ActiveDuration(val atEpochMs: Long, val activeMs: Long, val running: Boolean) {
    fun at(nowEpochMs: Long): Long = if (running) activeMs + (nowEpochMs - atEpochMs).coerceAtLeast(0) else activeMs

    companion object {
        val ZERO = ActiveDuration(atEpochMs = 0, activeMs = 0, running = false)
    }
}

/** Estado de la sesión de métricas en vivo (01 §3.1). Vive solo en memoria: nada de esto se guarda ni se envía. */
sealed interface LiveMetricsState {
    data object Idle : LiveMetricsState

    data object Starting : LiveMetricsState

    /** [heartRateBpm] es `null` mientras no haya una lectura válida: nunca se muestra un 0 inventado. */
    data class Active(
        val heartRateBpm: Int?,
        val availability: HeartRateAvailability,
        val duration: ActiveDuration,
        val paused: Boolean,
    ) : LiveMetricsState

    /** El reloj no ofrece frecuencia cardíaca para ciclismo en Health Services. */
    data object Unsupported : LiveMetricsState

    /** Otra app (p. ej. Samsung Health) tiene un ejercicio en curso; no se le quita. */
    data object OtherAppInProgress : LiveMetricsState

    data class Failed(val code: String) : LiveMetricsState

    /** Terminó; se muestra la duración hasta que el usuario cierra el aviso. */
    data class Ended(val activeMs: Long, val reason: EndReason) : LiveMetricsState
}

/** Lo que [ExerciseBackend] informa en cada actualización de Health Services. */
data class ExerciseSnapshot(
    val phase: Phase,
    /** Última lectura de esta actualización; `null` si no traía ninguna. */
    val heartRateBpm: Double?,
    val duration: ActiveDuration?,
    val endReason: EndReason? = null,
) {
    enum class Phase { PREPARING, ACTIVE, PAUSED, ENDING, ENDED }
}

enum class TrackedStatus { NONE, OWNED, OTHER_APP }

interface ExerciseListener {
    fun onSnapshot(snapshot: ExerciseSnapshot)

    fun onAvailability(availability: HeartRateAvailability)

    fun onRegistrationFailed()
}

/** Acceso a Health Services `ExerciseClient`; separado para probar la sesión sin el reloj. */
interface ExerciseBackend {
    suspend fun supportsHeartRate(): Boolean

    suspend fun trackedStatus(): TrackedStatus

    /** Reemplaza al anterior: Health Services admite un solo callback por app. */
    fun setListener(listener: ExerciseListener)

    /** Ejercicio de ciclismo solo con frecuencia cardíaca: sin GPS ni ubicación (01 §3.1). */
    suspend fun start()

    suspend fun pause()

    suspend fun resume()

    suspend fun end()
}

/** Mantiene vivo el proceso mientras dura la sesión (foreground service de tipo `health`). */
interface ForegroundKeeper {
    fun acquire()

    fun release()
}

/**
 * Sesión de métricas en vivo del modo A (01 §3.1, WC-30): un ejercicio de Health Services que solo sirve para mostrar
 * frecuencia cardíaca y duración en el reloj.
 *
 * - No es una grabación: no persiste muestras, no sube nada y no habla con el teléfono.
 * - Sobrevive al modo ambiente y a perder el teléfono, porque el ejercicio corre en Health Services y el proceso lo
 *   mantiene vivo un foreground service ([ForegroundKeeper]).
 * - Si la app se reinicia con el ejercicio en curso, [attach] lo recupera.
 */
class LiveMetricsSession(
    private val backend: ExerciseBackend,
    private val keeper: ForegroundKeeper,
) {
    private val _state = MutableStateFlow<LiveMetricsState>(LiveMetricsState.Idle)
    val state: StateFlow<LiveMetricsState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var listening = false

    private val listener = object : ExerciseListener {
        override fun onSnapshot(snapshot: ExerciseSnapshot) = onUpdate(snapshot)

        override fun onAvailability(availability: HeartRateAvailability) = _state.update { current ->
            if (current !is LiveMetricsState.Active) return@update current
            // Sin sensor disponible no hay lectura vigente: la última no se sigue mostrando como actual.
            val hr = if (availability == HeartRateAvailability.AVAILABLE) current.heartRateBpm else null
            current.copy(availability = availability, heartRateBpm = hr)
        }

        override fun onRegistrationFailed() {
            _state.value = LiveMetricsState.Failed("REGISTRATION_FAILED")
        }
    }

    /** Recupera un ejercicio propio que siguió corriendo mientras la app no estaba (p. ej. tras cerrarla). */
    suspend fun attach() = mutex.withLock {
        if (_state.value != LiveMetricsState.Idle) return@withLock
        val status = guarded { backend.trackedStatus() } ?: return@withLock
        if (status == TrackedStatus.OWNED) {
            listen()
            keeper.acquire()
            _state.value = active(HeartRateAvailability.UNKNOWN, ActiveDuration.ZERO, paused = false)
        }
    }

    suspend fun start() = mutex.withLock {
        when (_state.value) {
            is LiveMetricsState.Active, LiveMetricsState.Starting -> return@withLock
            else -> Unit
        }
        _state.value = LiveMetricsState.Starting
        val supported = guarded { backend.supportsHeartRate() } ?: return@withLock
        if (!supported) {
            _state.value = LiveMetricsState.Unsupported
            return@withLock
        }
        when (guarded { backend.trackedStatus() } ?: return@withLock) {
            TrackedStatus.OTHER_APP -> {
                _state.value = LiveMetricsState.OtherAppInProgress
                return@withLock
            }
            TrackedStatus.OWNED, TrackedStatus.NONE -> Unit
        }
        listen()
        keeper.acquire()
        if (guarded { backend.start() } == null) {
            keeper.release()
            return@withLock
        }
        _state.value = active(HeartRateAvailability.ACQUIRING, ActiveDuration.ZERO, paused = false)
    }

    suspend fun pause() = command { backend.pause() }

    suspend fun resume() = command { backend.resume() }

    /** Pide el fin; el estado [LiveMetricsState.Ended] llega con la actualización ENDED de Health Services. */
    suspend fun end() = command { backend.end() }

    /** Cierra el aviso de fin o de error y vuelve al inicio. Una sesión activa no se descarta así. */
    fun dismiss() = _state.update { if (it is LiveMetricsState.Active || it == LiveMetricsState.Starting) it else LiveMetricsState.Idle }

    private suspend fun command(block: suspend () -> Unit) = mutex.withLock {
        if (_state.value !is LiveMetricsState.Active) return@withLock
        guarded { block() }
    }

    private fun onUpdate(snapshot: ExerciseSnapshot) {
        if (snapshot.phase == ExerciseSnapshot.Phase.ENDED) {
            finish(snapshot)
            return
        }
        _state.update { current ->
            val base = current as? LiveMetricsState.Active
                ?: active(HeartRateAvailability.UNKNOWN, ActiveDuration.ZERO, paused = false)
            val reading = snapshot.heartRateBpm?.takeIf { it > 0 }?.let { Math.round(it).toInt() }
            base.copy(
                // Una actualización sin lectura conserva la anterior; una lectura de 0 no es una lectura.
                heartRateBpm = reading ?: base.heartRateBpm,
                availability = if (reading != null) HeartRateAvailability.AVAILABLE else base.availability,
                duration = snapshot.duration ?: base.duration,
                paused = snapshot.phase == ExerciseSnapshot.Phase.PAUSED,
            )
        }
    }

    private fun finish(snapshot: ExerciseSnapshot) {
        val last = _state.value as? LiveMetricsState.Active
        val activeMs = snapshot.duration?.activeMs ?: last?.duration?.activeMs ?: 0
        _state.value = LiveMetricsState.Ended(activeMs, snapshot.endReason ?: EndReason.OTHER)
        keeper.release()
        listening = false
    }

    private fun listen() {
        if (listening) return
        backend.setListener(listener)
        listening = true
    }

    /** Ejecuta [block]; si falla, deja el estado en [LiveMetricsState.Failed] con un código técnico y devuelve `null`. */
    private suspend fun <T> guarded(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        _state.value = LiveMetricsState.Failed(codeFor(e))
        null
    }

    private fun active(availability: HeartRateAvailability, duration: ActiveDuration, paused: Boolean) =
        LiveMetricsState.Active(heartRateBpm = null, availability = availability, duration = duration, paused = paused)

    companion object {
        /** Solo el tipo de la excepción, en el formato de los códigos de diagnóstico: nunca su mensaje. */
        fun codeFor(e: Exception): String =
            e::class.simpleName.orEmpty().replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()
                .filter { it.isLetterOrDigit() || it == '_' }.take(32).ifEmpty { "UNKNOWN" }
    }
}
