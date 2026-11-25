package mx.watchcompanion.phone.recordings

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import mx.watchcompanion.phone.PhoneCompanionApp

/**
 * Sube las grabaciones del reloj cuando hay red (WC-54). Si alguna tiene que esperar (sin red, sin sesión, Velora
 * procesándola), WorkManager reintenta con espera creciente, aunque la app esté cerrada o el teléfono se reinicie.
 */
class RecordingUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as PhoneCompanionApp
        return when (app.recordingUploader.uploadAll()) {
            RecordingUploader.Outcome.DONE -> Result.success()
            RecordingUploader.Outcome.RETRY -> Result.retry()
        }
    }

    companion object {
        private const val NAME = "subir-grabaciones-del-reloj"

        /** Programa una pasada; si ya hay una en curso, otra la sigue para no perder lo que llegó mientras tanto. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<RecordingUploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
