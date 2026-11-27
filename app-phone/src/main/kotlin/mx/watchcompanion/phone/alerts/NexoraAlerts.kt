package mx.watchcompanion.phone.alerts

import java.io.File
import java.io.IOException
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import mx.watchcompanion.core.session.SessionExpiredException
import mx.watchcompanion.integration.nexora.NexoraClient
import mx.watchcompanion.integration.nexora.NexoraSession
import mx.watchcompanion.integration.nexora.api.models.NotificationResponse
import mx.watchcompanion.phone.NexoraHttpException

/**
 * Tipos de aviso de Nexora que llegan al reloj (03 §2, WC-41 y WC-61): solo los que `nexora-api` emite hoy y piden algo
 * del usuario. El texto es **propio**, nunca el `message` de Nexora: ese lleva montos y nombres de tarjeta o categoría.
 */
enum class AlertKind {
    PAYMENT_DUE_SOON,
    PAYMENT_DUE,
    INSTALLMENT_DUE,
    SCHEDULED_CHARGE_FAILED,

    /** Un presupuesto llegó al 80 % este mes (nexora-api B15). */
    BUDGET_NEAR_LIMIT,

    /** Un presupuesto se pasó este mes (nexora-api B15). */
    BUDGET_EXCEEDED,
}

/** Un aviso por mostrar: solo su id (para no repetirlo) y su tipo. */
data class NexoraAlert(val id: String, val kind: AlertKind)

/**
 * Lee la bandeja de Nexora aviso por aviso: uno que este contrato no entiende (un tipo nuevo, por ejemplo) se ignora
 * sin tumbar a los demás (03 §2). Se quedan los no leídos de los tipos de [AlertKind].
 */
fun parseAlerts(body: String): List<NexoraAlert> =
    NexoraClient.json.parseToJsonElement(body).jsonArray.mapNotNull { element ->
        val notice = try {
            NexoraClient.json.decodeFromJsonElement(NotificationResponse.serializer(), element)
        } catch (_: SerializationException) {
            return@mapNotNull null
        } catch (_: IllegalArgumentException) {
            return@mapNotNull null
        }
        val id = notice.id ?: return@mapNotNull null
        val kind = AlertKind.entries.firstOrNull { it.name == notice.type?.value } ?: return@mapNotNull null
        if (notice.status == NotificationResponse.Status.READ) return@mapNotNull null
        NexoraAlert(id.toString(), kind)
    }

/** `GET /api/v1/notifications?unreadOnly=true`, leído aviso por aviso. */
suspend fun fetchNexoraAlerts(session: NexoraSession): List<NexoraAlert> {
    val response = session.call("nexora.notificationList") { notificationList(unreadOnly = true) }
    if (!response.success) throw NexoraHttpException(response.status)
    return parseAlerts(response.response.bodyAsText())
}

/** Ids de los avisos ya mostrados, para no repetirlos (03 §2). No son datos personales; se guardan los últimos [max]. */
class ShownAlerts(private val file: File, private val max: Int = MAX) {
    private val mutex = Mutex()
    private val serializer = ListSerializer(String.serializer())

    suspend fun read(): List<String> = mutex.withLock { load() }

    suspend fun add(ids: List<String>) = mutex.withLock {
        if (ids.isEmpty()) return@withLock
        val all = (load() + ids).distinct().takeLast(max)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(Json.encodeToString(serializer, all))
        if (!tmp.renameTo(file)) throw IOException("No se pudo guardar los avisos mostrados")
    }

    private fun load(): List<String> = try {
        if (file.exists()) Json.decodeFromString(serializer, file.readText()) else emptyList()
    } catch (_: SerializationException) {
        emptyList()
    }

    private companion object {
        const val MAX = 200
    }
}

/**
 * Una pasada del sondeo (WC-41): trae los avisos no leídos, publica los que no se han mostrado y los recuerda. Si no
 * se pudo consultar, no recuerda nada: la próxima pasada lo intenta de nuevo.
 */
class NexoraAlertPoller(
    private val fetch: suspend () -> List<NexoraAlert>,
    private val shown: ShownAlerts,
    private val publish: (NexoraAlert) -> Boolean,
) {
    enum class Outcome { DONE, RETRY, SIGNED_OUT }

    suspend fun poll(): Outcome {
        val alerts = try {
            fetch()
        } catch (e: CancellationException) {
            throw e
        } catch (_: SessionExpiredException) {
            return Outcome.SIGNED_OUT
        } catch (_: SerializationException) {
            // La bandeja completa no se pudo leer: no es algo que un reintento arregle.
            return Outcome.DONE
        } catch (_: Exception) {
            // Sin red, Nexora caído (HTTP_n) o sin respuesta: WorkManager reintenta con espera creciente.
            return Outcome.RETRY
        }
        val already = shown.read().toSet()
        // Solo se recuerdan los que sí se publicaron: sin permiso de notificaciones, se mostrarán cuando lo haya.
        val published = alerts.filter { it.id !in already }.filter(publish)
        shown.add(published.map { it.id })
        return Outcome.DONE
    }
}
