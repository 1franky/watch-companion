package mx.watchcompanion.watch.alerts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import mx.watchcompanion.core.communication.HeartRateZones
import mx.watchcompanion.watch.live.LiveMetricsState

/** Cambio de zona confirmado. Zona 0 = por debajo de Z1. */
data class ZoneAlert(val direction: Direction, val fromZone: Int, val toZone: Int) {
    enum class Direction { UP, DOWN }
}

/**
 * Decide cuándo avisar de un cambio de zona (WC-31). Solo usa la lectura y las zonas guardadas en el reloj: no
 * depende de internet ni del teléfono.
 *
 * Para no repetir la alerta cuando la FC oscila alrededor de un límite:
 * - **Histéresis:** se sube de zona al alcanzar su límite, pero solo se baja al quedar [hysteresisBpm] por debajo.
 * - **Permanencia:** el cambio se confirma cuando la nueva zona se mantiene [dwellMs]; un pico suelto no avisa.
 *
 * La primera lectura fija la zona sin avisar. Sin lectura o sin zonas no se evalúa nada.
 */
class ZoneAlertEvaluator(
    private val hysteresisBpm: Int = HYSTERESIS_BPM,
    private val dwellMs: Long = DWELL_MS,
) {
    private var confirmed: Int? = null
    private var pending: Pending? = null

    private data class Pending(val zone: Int, val direction: ZoneAlert.Direction, val sinceMs: Long)

    /** Zona confirmada actual, para pruebas y diagnóstico. */
    val currentZone: Int? get() = confirmed

    fun onReading(bpm: Int?, zones: HeartRateZones?, nowMs: Long): ZoneAlert? {
        if (bpm == null || zones == null) {
            pending = null
            return null
        }
        val raw = zones.zoneOf(bpm) ?: 0
        val current = confirmed ?: run {
            confirmed = raw
            return null
        }
        val candidate = when {
            raw > current -> raw
            // Al bajar, la lectura debe quedar por debajo del límite con margen: con el margen sumado, ¿sigue abajo?
            raw < current -> (zones.zoneOf(bpm + hysteresisBpm) ?: 0).takeIf { it < current }
            else -> null
        }
        if (candidate == null) {
            pending = null
            return null
        }
        val direction = if (candidate > current) ZoneAlert.Direction.UP else ZoneAlert.Direction.DOWN
        val waiting = pending?.takeIf { it.direction == direction }?.copy(zone = candidate)
            ?: Pending(candidate, direction, nowMs)
        if (nowMs - waiting.sinceMs < dwellMs) {
            pending = waiting
            return null
        }
        confirmed = candidate
        pending = null
        return ZoneAlert(direction, current, candidate)
    }

    /** Nueva sesión: se olvida la zona anterior. */
    fun reset() {
        confirmed = null
        pending = null
    }

    companion object {
        const val HYSTERESIS_BPM = 3
        const val DWELL_MS = 5_000L
    }
}

/** Vibración del reloj; separada para probar sin hardware. */
fun interface Haptics {
    fun play(alert: ZoneAlert)
}

/**
 * Une la sesión de métricas en vivo, las zonas guardadas y la vibración. Evalúa con cada actualización de la sesión y,
 * mientras está activa, una vez por segundo, para que la permanencia se cumpla aunque la lectura no cambie.
 */
class ZoneAlerts(
    private val session: StateFlow<LiveMetricsState>,
    private val zones: StateFlow<HeartRateZones?>,
    private val haptics: Haptics,
    private val clock: () -> Long = System::currentTimeMillis,
    private val evaluator: ZoneAlertEvaluator = ZoneAlertEvaluator(),
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope): Job = scope.launch {
        val ticks = session.transformLatest { state ->
            emit(state)
            while (state is LiveMetricsState.Active && !state.paused) {
                delay(TICK_MS)
                emit(state)
            }
        }
        combine(ticks, zones) { state, zones -> state to zones }.collect { (state, zones) ->
            when {
                state !is LiveMetricsState.Active -> evaluator.reset()
                // En pausa no se avisa; al reanudar, una lectura vuelve a pasar por la permanencia.
                state.paused -> evaluator.onReading(null, zones, clock())
                else -> evaluator.onReading(state.heartRateBpm, zones, clock())?.let(haptics::play)
            }
        }
    }

    private companion object {
        const val TICK_MS = 1_000L
    }
}
