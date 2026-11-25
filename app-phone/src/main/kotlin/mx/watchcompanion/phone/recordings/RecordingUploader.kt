package mx.watchcompanion.phone.recordings

import kotlinx.coroutines.CancellationException

/** Lo que el teléfono necesita de Velora para subir una grabación secundaria (ADR-032 de Velora). */
interface SecondaryRecordingApi {
    sealed interface Upload {
        /** Ya existía igual en Velora (200). */
        data object Stored : Upload

        /** Aceptada y en cola (202). */
        data class Queued(val jobId: String) : Upload

        /** 409: `RECORDING_CONFLICT` o `ALREADY_IMPORTED_AS_ACTIVITY`. No tiene caso reintentar. */
        data class Conflict(val code: String) : Upload

        /** 400 o 413: el archivo o la solicitud no sirven; reintentar no cambia nada. */
        data class Refused(val status: Int) : Upload

        /** Sin sesión, 401, 5xx…: se reintenta más tarde. */
        data class Later(val status: Int) : Upload
    }

    sealed interface Job {
        /** COMPLETED o DUPLICATE: Velora la tiene. */
        data object Done : Job

        /** REJECTED: el archivo no se pudo leer; volver a subirlo daría lo mismo. */
        data class Rejected(val code: String?) : Job

        /** QUEUED o PROCESSING. */
        data object Pending : Job

        /** FAILED o el trabajo ya no existe: hay que volver a subirla. */
        data object Lost : Job
    }

    suspend fun upload(entry: RecordingInbox.Entry): Upload

    suspend fun job(jobId: String): Job
}

/**
 * Sube a Velora las grabaciones que el reloj entregó al teléfono (WC-54) y las borra del teléfono cuando Velora las
 * tiene. La subida es idempotente del lado de Velora (`clientRecordingId` + SHA-256), así que reintentar nunca duplica.
 *
 * @param onDropped una grabación que se descarta sin llegar a Velora (conflicto o rechazo), con su código técnico; va
 *   al registro de diagnóstico.
 */
class RecordingUploader(
    private val inbox: RecordingInbox,
    private val api: SecondaryRecordingApi,
    private val onDropped: (code: String) -> Unit = {},
) {
    enum class Outcome { DONE, RETRY }

    /** Procesa todo lo pendiente. Devuelve [Outcome.RETRY] si alguna grabación tiene que esperar. */
    suspend fun uploadAll(): Outcome {
        var outcome = Outcome.DONE
        for (entry in inbox.entries()) {
            if (process(entry) == Outcome.RETRY) outcome = Outcome.RETRY
        }
        return outcome
    }

    suspend fun process(entry: RecordingInbox.Entry): Outcome = try {
        val jobId = entry.jobId ?: when (val upload = api.upload(entry)) {
            SecondaryRecordingApi.Upload.Stored -> return done(entry)
            is SecondaryRecordingApi.Upload.Queued -> upload.jobId.also { inbox.setJob(entry.id, it) }
            is SecondaryRecordingApi.Upload.Conflict -> return dropped(entry, upload.code)
            is SecondaryRecordingApi.Upload.Refused -> return dropped(entry, "HTTP_${upload.status}")
            is SecondaryRecordingApi.Upload.Later -> return Outcome.RETRY
        }
        when (val job = api.job(jobId)) {
            SecondaryRecordingApi.Job.Done -> done(entry)
            is SecondaryRecordingApi.Job.Rejected -> dropped(entry, job.code ?: "REJECTED")
            SecondaryRecordingApi.Job.Pending -> Outcome.RETRY
            SecondaryRecordingApi.Job.Lost -> {
                // Se vuelve a subir en el próximo intento; Velora no la duplica.
                inbox.setJob(entry.id, null)
                Outcome.RETRY
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Sin red o sin sesión: WorkManager reintenta con espera creciente.
        Outcome.RETRY
    }

    private fun done(entry: RecordingInbox.Entry): Outcome {
        inbox.remove(entry.id)
        return Outcome.DONE
    }

    private fun dropped(entry: RecordingInbox.Entry, code: String): Outcome {
        onDropped(code)
        inbox.remove(entry.id)
        return Outcome.DONE
    }
}
