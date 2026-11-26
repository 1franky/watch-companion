package mx.watchcompanion.phone.alerts

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import mx.watchcompanion.core.session.SessionState
import mx.watchcompanion.phone.PhoneCompanionApp

/**
 * Sondeo de la bandeja de Nexora (WC-41, A-09: Nexora no tiene push). Corre cada [INTERVAL_HOURS] h con red y sin
 * batería baja, aunque la app esté cerrada; solo mientras haya sesión de Nexora en el teléfono.
 */
class NexoraAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as PhoneCompanionApp
        // En un proceso recién creado la sesión aún se está leyendo.
        if (app.nexoraSession.state.first { it != SessionState.Restoring } !is SessionState.SignedIn) return Result.success()
        return when (app.nexoraAlerts.poll()) {
            NexoraAlertPoller.Outcome.DONE, NexoraAlertPoller.Outcome.SIGNED_OUT -> Result.success()
            NexoraAlertPoller.Outcome.RETRY -> Result.retry()
        }
    }

    companion object {
        private const val NAME = "avisos-nexora"
        const val INTERVAL_HOURS = 6L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<NexoraAlertWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
