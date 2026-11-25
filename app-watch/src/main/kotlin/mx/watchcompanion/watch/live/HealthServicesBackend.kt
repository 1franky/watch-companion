package mx.watchcompanion.watch.live

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import androidx.health.services.client.ExerciseClient
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseEndReason
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseState
import androidx.health.services.client.data.ExerciseTrackedStatus
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.endExercise
import androidx.health.services.client.getCapabilities
import androidx.health.services.client.getCurrentExerciseInfo
import androidx.health.services.client.pauseExercise
import androidx.health.services.client.resumeExercise
import androidx.health.services.client.startExercise
import java.time.Instant
import java.util.concurrent.Executors
import mx.watchcompanion.watch.recording.HeartRateSample
import mx.watchcompanion.watch.recording.TrackPoint

/** [ExerciseBackend] sobre Health Services `ExerciseClient` (no `MeasureClient`: 01 §4). */
class HealthServicesBackend(
    private val context: Context,
    private val client: ExerciseClient = HealthServices.getClient(context).exerciseClient,
) : ExerciseBackend {

    override suspend fun supportsHeartRate(): Boolean {
        val capabilities = client.getCapabilities()
        return EXERCISE in capabilities.supportedExerciseTypes &&
            DataType.HEART_RATE_BPM in capabilities.getExerciseTypeCapabilities(EXERCISE).supportedDataTypes
    }

    override suspend fun supportsLocation(): Boolean {
        val capabilities = client.getCapabilities()
        return EXERCISE in capabilities.supportedExerciseTypes &&
            DataType.LOCATION in capabilities.getExerciseTypeCapabilities(EXERCISE).supportedDataTypes
    }

    // Falso positivo de lint: ExerciseTrackedStatus/ExerciseEndReason son constantes públicas documentadas, pero su
    // Companion lleva @RestrictTo en health-services-client 1.1.0.
    @SuppressLint("RestrictedApi")
    override suspend fun trackedStatus(): TrackedStatus = when (client.getCurrentExerciseInfo().exerciseTrackedStatus) {
        ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS -> TrackedStatus.OWNED
        ExerciseTrackedStatus.OTHER_APP_IN_PROGRESS -> TrackedStatus.OTHER_APP
        else -> TrackedStatus.NONE
    }

    /**
     * Un solo hilo propio, no el principal: en el modo B cada actualización escribe y sincroniza el diario a disco
     * (WC-53). Un solo hilo conserva el orden de las actualizaciones.
     */
    private val updates = Executors.newSingleThreadExecutor { Thread(it, "exercise-updates") }

    override fun setListener(listener: ExerciseListener) {
        client.setUpdateCallback(updates, Callback(listener))
    }

    override suspend fun start(withLocation: Boolean) {
        val config = ExerciseConfig.builder(EXERCISE)
            // Modo A: sin GPS; el BSC100S graba la ruta y el reloj no pide ubicación (01 §3.1).
            // Modo B: GPS del reloj para grabar su propio track (WC-53).
            .setDataTypes(if (withLocation) setOf(DataType.HEART_RATE_BPM, DataType.LOCATION) else setOf(DataType.HEART_RATE_BPM))
            .setIsGpsEnabled(withLocation)
            // Sin auto-pausa en ningún modo por ahora (WC-59): en el modo B se decidirá con los datos de WC-57.
            .setIsAutoPauseAndResumeEnabled(false)
            .build()
        client.startExercise(config)
    }

    override suspend fun pause() {
        client.pauseExercise()
    }

    override suspend fun resume() {
        client.resumeExercise()
    }

    override suspend fun end() {
        client.endExercise()
    }

    private class Callback(private val listener: ExerciseListener) : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) = listener.onSnapshot(update.toSnapshot(bootInstant()))

        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {
            if (dataType != DataType.HEART_RATE_BPM || availability !is DataTypeAvailability) return
            listener.onAvailability(
                when (availability) {
                    DataTypeAvailability.AVAILABLE -> HeartRateAvailability.AVAILABLE
                    DataTypeAvailability.ACQUIRING -> HeartRateAvailability.ACQUIRING
                    DataTypeAvailability.UNAVAILABLE -> HeartRateAvailability.UNAVAILABLE
                    DataTypeAvailability.UNAVAILABLE_DEVICE_OFF_BODY -> HeartRateAvailability.OFF_BODY
                    else -> HeartRateAvailability.UNKNOWN
                },
            )
        }

        override fun onRegistered() = Unit

        override fun onRegistrationFailed(throwable: Throwable) = listener.onRegistrationFailed()

        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) = Unit
    }

    private companion object {
        val EXERCISE = ExerciseType.BIKING
    }
}

/** Instante del arranque del reloj: Health Services fecha las muestras en tiempo desde el arranque. */
private fun bootInstant(): Instant = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())

internal fun ExerciseUpdate.toSnapshot(boot: Instant): ExerciseSnapshot {
    val state = exerciseStateInfo.state
    val phase = when {
        state.isEnded -> ExerciseSnapshot.Phase.ENDED
        state.isEnding -> ExerciseSnapshot.Phase.ENDING
        state.isPaused -> ExerciseSnapshot.Phase.PAUSED
        state == ExerciseState.PREPARING -> ExerciseSnapshot.Phase.PREPARING
        else -> ExerciseSnapshot.Phase.ACTIVE
    }
    val duration = activeDurationCheckpoint?.let {
        ActiveDuration(it.time.toEpochMilli(), it.activeDuration.toMillis(), running = phase == ExerciseSnapshot.Phase.ACTIVE)
    }
    val heartRates = latestMetrics.getData(DataType.HEART_RATE_BPM)
    return ExerciseSnapshot(
        phase = phase,
        heartRateBpm = heartRates.lastOrNull()?.value,
        duration = duration,
        endReason = if (phase == ExerciseSnapshot.Phase.ENDED) endReason(exerciseStateInfo.endReason) else null,
        locations = latestMetrics.getData(DataType.LOCATION).map { point ->
            TrackPoint(
                timeEpochMs = point.getTimeInstant(boot).toEpochMilli(),
                latitude = point.value.latitude,
                longitude = point.value.longitude,
                altitudeMeters = point.value.altitude.takeUnless { it.isNaN() },
            )
        },
        heartRates = heartRates.mapNotNull { point ->
            Math.round(point.value).toInt().takeIf { it > 0 }?.let { HeartRateSample(point.getTimeInstant(boot).toEpochMilli(), it) }
        },
    )
}

@SuppressLint("RestrictedApi") // ver trackedStatus()
private fun endReason(reason: Int): EndReason = when (reason) {
    ExerciseEndReason.USER_END -> EndReason.USER
    ExerciseEndReason.AUTO_END_PERMISSION_LOST -> EndReason.PERMISSION_LOST
    ExerciseEndReason.AUTO_END_SUPERSEDED -> EndReason.SUPERSEDED
    else -> EndReason.OTHER
}
