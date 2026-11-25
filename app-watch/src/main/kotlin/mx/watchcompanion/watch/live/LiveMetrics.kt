package mx.watchcompanion.watch.live

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mx.watchcompanion.watch.recording.HeartRateSample
import mx.watchcompanion.watch.recording.RecordingProgress
import mx.watchcompanion.watch.recording.RecordingSummary
import mx.watchcompanion.watch.recording.TrackPoint
import mx.watchcompanion.watch.recording.TrackRecorder

/** Disponibilidad del sensor de frecuencia cardíaca, tal como la informa Health Services. */
enum class HeartRateAvailability { UNKNOWN, ACQUIRING, AVAILABLE, UNAVAILABLE, OFF_BODY }

/**
 * Qué hace la sesión: [METRICS] es el modo A (solo métricas en vivo, nada se guarda); [RECORDING] es el modo B (además
 * graba el track GPS en el reloj, WC-53). Las métricas y las alertas son las mismas en los dos.
 */
enum class SessionMode { METRICS, RECORDING }

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

/**
 * Estado de la sesión de métricas en vivo (01 §3.1). Vive solo en memoria: nada de esto se guarda ni se envía. En el
 * modo B el track se guarda aparte, en [TrackRecorder].
 */
sealed interface LiveMetricsState {
    data object Idle : LiveMetricsState

    data object Starting : LiveMetricsState

    /** [heartRateBpm] es `null` mientras no haya una lectura válida: nunca se muestra un 0 inventado. */
    data class Active(
        val heartRateBpm: Int?,
        val availability: HeartRateAvailability,
        val duration: ActiveDuration,
        val paused: Boolean,
        /** Solo en el modo B: puntos GPS guardados y distancia. `null` en el modo A. */
        val recording: RecordingProgress? = null,
    ) : LiveMetricsState

    /** El reloj no ofrece frecuencia cardíaca para ciclismo en Health Services. */
    data object Unsupported : LiveMetricsState

    /** Otra app (p. ej. Samsung Health) tiene un ejercicio en curso; no se le quita. */
    data object OtherAppInProgress : LiveMetricsState

    data class Failed(val code: String) : LiveMetricsState

    /** Se pidió grabar la ruta, pero el reloj no ofrece ubicación en Health Services o no hay permiso. */
    data object LocationUnavailable : LiveMetricsState

    /** Terminó; se muestra la duración (y la ruta guardada, en el modo B) hasta que el usuario cierra el aviso. */
    data class Ended(val activeMs: Long, val reason: EndReason, val recording: RecordingSummary? = null) : LiveMetricsState
}

/** Lo que [ExerciseBackend] informa en cada actualización de Health Services. */
data class ExerciseSnapshot(
    val phase: Phase,
    /** Última lectura de esta actualización; `null` si no traía ninguna. */
    val heartRateBpm: Double?,
    val duration: ActiveDuration?,
    val endReason: EndReason? = null,
    /** Puntos GPS del lote, con su hora; vacío si el ejercicio no usa ubicación. */
    val locations: List<TrackPoint> = emptyList(),
    /** Todas las lecturas de FC del lote, con su hora, para asociarlas a los puntos GPS. */
    val heartRates: List<HeartRateSample> = emptyList(),
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

    /** Si el ejercicio de ciclismo admite ubicación (GPS del reloj) en Health Services. */
    suspend fun supportsLocation(): Boolean

    suspend fun trackedStatus(): TrackedStatus

    /** Reemplaza al anterior: Health Services admite un solo callback por app. */
    fun setListener(listener: ExerciseListener)

    /**
     * Ejercicio de ciclismo con frecuencia cardíaca. Sin [withLocation] no hay GPS ni ubicación (modo A, 01 §3.1); con
     * él, el GPS del reloj se enciende para grabar el track (modo B).
     */
    suspend fun start(withLocation: Boolean)

    suspend fun pause()

    suspend fun resume()

    suspend fun end()
}

/** Mantiene vivo el proceso mientras dura la sesión (foreground service de tipo `health`, y `location` en el modo B). */
interface ForegroundKeeper {
    fun acquire(withLocation: Boolean = false)

    fun release()
}

/**
 * Sesión de métricas en vivo (01 §3.1, WC-30): un ejercicio de Health Services que muestra frecuencia cardíaca y
 * duración en el reloj.
 *
 * - En el modo A ([SessionMode.METRICS]) no es una grabación: no persiste muestras, no sube nada y no habla con el
 *   teléfono. En el modo B ([SessionMode.RECORDING], WC-53) además enciende el GPS y guarda el track en [recorder].
 * - Sobrevive al modo ambiente y a perder el teléfono, porque el ejercicio corre en Health Services y el proceso lo
 *   mantiene vivo un foreground service ([ForegroundKeeper]).
 * - Si la app se reinicia con el ejercicio en curso, [attach] lo recupera.
 */
class LiveMetricsSession(
    private val backend: ExerciseBackend,
    private val keeper: ForegroundKeeper,
    /** Grabación del modo B; solo se toca en [SessionMode.RECORDING]. */
    private val recorder: TrackRecorder? = null,
    /** Si la app tiene el permiso de ubicación precisa; sin él no se graba (modo A no lo necesita). */
    private val hasLocationPermission: () -> Boolean = { recorder != null },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<LiveMetricsState>(LiveMetricsState.Idle)
    val state: StateFlow<LiveMetricsState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var listening = false

    /** Modo de la sesión en curso (o de la última). */
    @Volatile
    var mode: SessionMode = SessionMode.METRICS
        private set

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

    /**
     * Recupera un ejercicio propio que siguió corriendo mientras la app no estaba (p. ej. tras cerrarla). Si era una
     * grabación, la retoma con el mismo id. Si el ejercicio ya no existe (el reloj se apagó), cierra como parcial la
     * grabación que quedó abierta.
     */
    suspend fun attach() = mutex.withLock {
        if (_state.value != LiveMetricsState.Idle) return@withLock
        val status = guarded { backend.trackedStatus() } ?: return@withLock
        if (status == TrackedStatus.OWNED) {
            val resumed = recorder?.resume()
            mode = if (resumed != null) SessionMode.RECORDING else SessionMode.METRICS
            listen()
            keeper.acquire(withLocation = mode == SessionMode.RECORDING)
            _state.value = active(HeartRateAvailability.UNKNOWN, ActiveDuration.ZERO, paused = false)
        } else {
            recorder?.recover()
        }
    }

    suspend fun start(mode: SessionMode = SessionMode.METRICS) = mutex.withLock {
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
        val recording = mode == SessionMode.RECORDING
        if (recording && (recorder == null || !hasLocationPermission() || guarded { backend.supportsLocation() } != true)) {
            if (_state.value == LiveMetricsState.Starting) _state.value = LiveMetricsState.LocationUnavailable
            return@withLock
        }
        when (guarded { backend.trackedStatus() } ?: return@withLock) {
            TrackedStatus.OTHER_APP -> {
                _state.value = LiveMetricsState.OtherAppInProgress
                return@withLock
            }
            TrackedStatus.OWNED, TrackedStatus.NONE -> Unit
        }
        this.mode = mode
        listen()
        keeper.acquire(withLocation = recording)
        if (recording) recorder?.begin(clock())
        if (guarded { backend.start(withLocation = recording) } == null) {
            if (recording) recorder?.discard()
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
        if (mode == SessionMode.RECORDING) recorder?.append(snapshot.locations, snapshot.heartRates)
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
                recording = if (mode == SessionMode.RECORDING) recorder?.progress() ?: base.recording else null,
            )
        }
    }

    private fun finish(snapshot: ExerciseSnapshot) {
        val last = _state.value as? LiveMetricsState.Active
        val activeMs = snapshot.duration?.activeMs ?: last?.duration?.activeMs ?: 0
        val saved = if (mode == SessionMode.RECORDING) {
            // Los últimos puntos pueden venir en la misma actualización que el fin.
            recorder?.append(snapshot.locations, snapshot.heartRates)
            recorder?.finish(clock())
        } else {
            null
        }
        _state.value = LiveMetricsState.Ended(activeMs, snapshot.endReason ?: EndReason.OTHER, saved)
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
        LiveMetricsState.Active(
            heartRateBpm = null,
            availability = availability,
            duration = duration,
            paused = paused,
            recording = if (mode == SessionMode.RECORDING) recorder?.progress() ?: RecordingProgress(0, 0.0) else null,
        )

    companion object {
        /** Solo el tipo de la excepción, en el formato de los códigos de diagnóstico: nunca su mensaje. */
        fun codeFor(e: Exception): String =
            e::class.simpleName.orEmpty().replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()
                .filter { it.isLetterOrDigit() || it == '_' }.take(32).ifEmpty { "UNKNOWN" }
    }
}
