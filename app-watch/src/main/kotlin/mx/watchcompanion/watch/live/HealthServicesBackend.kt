package mx.watchcompanion.watch.live

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.content.ContextCompat
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

    // Falso positivo de lint: ExerciseTrackedStatus/ExerciseEndReason son constantes públicas documentadas, pero su
    // Companion lleva @RestrictTo en health-services-client 1.1.0.
    @SuppressLint("RestrictedApi")
    override suspend fun trackedStatus(): TrackedStatus = when (client.getCurrentExerciseInfo().exerciseTrackedStatus) {
        ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS -> TrackedStatus.OWNED
        ExerciseTrackedStatus.OTHER_APP_IN_PROGRESS -> TrackedStatus.OTHER_APP
        else -> TrackedStatus.NONE
    }

    override fun setListener(listener: ExerciseListener) {
        client.setUpdateCallback(ContextCompat.getMainExecutor(context), Callback(listener))
    }

    override suspend fun start() {
        val config = ExerciseConfig.builder(EXERCISE)
            .setDataTypes(setOf(DataType.HEART_RATE_BPM))
            // Sin GPS: en el modo A el BSC100S graba la ruta y el reloj no pide ubicación (01 §3.1).
            .setIsGpsEnabled(false)
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
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) = listener.onSnapshot(update.toSnapshot())

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

internal fun ExerciseUpdate.toSnapshot(): ExerciseSnapshot {
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
    return ExerciseSnapshot(
        phase = phase,
        heartRateBpm = latestMetrics.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value,
        duration = duration,
        endReason = if (phase == ExerciseSnapshot.Phase.ENDED) endReason(exerciseStateInfo.endReason) else null,
    )
}

@SuppressLint("RestrictedApi") // ver trackedStatus()
private fun endReason(reason: Int): EndReason = when (reason) {
    ExerciseEndReason.USER_END -> EndReason.USER
    ExerciseEndReason.AUTO_END_PERMISSION_LOST -> EndReason.PERMISSION_LOST
    ExerciseEndReason.AUTO_END_SUPERSEDED -> EndReason.SUPERSEDED
    else -> EndReason.OTHER
}
