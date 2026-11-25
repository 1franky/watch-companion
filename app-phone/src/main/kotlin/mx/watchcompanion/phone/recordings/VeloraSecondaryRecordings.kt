package mx.watchcompanion.phone.recordings

import io.ktor.util.reflect.typeInfo
import java.util.UUID
import mx.watchcompanion.integration.velora.VeloraSession
import mx.watchcompanion.integration.velora.api.models.ImportAccepted
import mx.watchcompanion.integration.velora.api.models.ImportJob
import mx.watchcompanion.integration.velora.api.models.Problem

/** [SecondaryRecordingApi] con la sesión propia de Velora del teléfono (WC-ADR-013). */
class VeloraSecondaryRecordings(private val session: VeloraSession) : SecondaryRecordingApi {

    override suspend fun upload(entry: RecordingInbox.Entry): SecondaryRecordingApi.Upload {
        val response = session.call("velora.uploadSecondaryRecording") {
            uploadSecondaryRecording(
                file = entry.gpx.readBytes(),
                fileName = "${entry.id}.gpx",
                source = SOURCE,
                clientRecordingId = UUID.fromString(entry.id),
                completeness = entry.completeness,
            )
        }
        return when (response.status) {
            200 -> SecondaryRecordingApi.Upload.Stored
            202 -> SecondaryRecordingApi.Upload.Queued(response.typedBody<ImportAccepted>(typeInfo<ImportAccepted>()).jobId.toString())
            409 -> SecondaryRecordingApi.Upload.Conflict(
                runCatching { response.typedBody<Problem>(typeInfo<Problem>()).code }.getOrNull() ?: "CONFLICT",
            )
            400, 413 -> SecondaryRecordingApi.Upload.Refused(response.status)
            else -> SecondaryRecordingApi.Upload.Later(response.status)
        }
    }

    override suspend fun job(jobId: String): SecondaryRecordingApi.Job {
        val response = session.call("velora.getImportJob") { getImportJob(UUID.fromString(jobId)) }
        if (response.status == 404) return SecondaryRecordingApi.Job.Lost
        if (!response.success) return SecondaryRecordingApi.Job.Pending
        val job = response.body()
        return when (job.status) {
            ImportJob.Status.COMPLETED, ImportJob.Status.DUPLICATE -> SecondaryRecordingApi.Job.Done
            ImportJob.Status.REJECTED -> SecondaryRecordingApi.Job.Rejected(job.errorCode)
            ImportJob.Status.FAILED -> SecondaryRecordingApi.Job.Lost
            ImportJob.Status.QUEUED, ImportJob.Status.PROCESSING -> SecondaryRecordingApi.Job.Pending
        }
    }

    private companion object {
        const val SOURCE = "WATCH"
    }
}
