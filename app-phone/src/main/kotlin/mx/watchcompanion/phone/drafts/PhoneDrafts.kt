package mx.watchcompanion.phone.drafts

import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import mx.watchcompanion.core.communication.DraftStatus
import mx.watchcompanion.core.communication.DraftStatus.State
import mx.watchcompanion.core.communication.QuickTransactionDraft
import mx.watchcompanion.core.session.SecretCipher
import mx.watchcompanion.core.session.SessionExpiredException

/**
 * Un borrador del reloj tal como lo guarda el teléfono. [requestBody] es el `CreateTransactionRequest` exacto que se
 * mandó la primera vez: los reintentos lo reenvían igual, byte a byte, porque el `IdempotencyFilter` de Nexora
 * compara la huella del cuerpo.
 */
@Serializable
data class StoredDraft(
    val draft: QuickTransactionDraft,
    val state: State,
    val updatedAt: Long,
    val errorCode: String? = null,
    val requestBody: String? = null,
) {
    val status: DraftStatus get() = DraftStatus(draft.idempotencyKey, state, updatedAt, errorCode)

    /** Descartar solo cuando es seguro que nada quedó en Nexora: nunca tras un envío sin respuesta. */
    val discardable: Boolean get() = state == State.RECEIVED || state == State.REJECTED || state == State.CONFLICT

    /** Confirmar (o reintentar, con la misma clave y el mismo cuerpo). */
    val confirmable: Boolean get() = state == State.RECEIVED || state == State.UNKNOWN
}

/** Dónde viven los borradores: cifrados, fuera del respaldo (03 §2). */
interface DraftStore {
    suspend fun read(): List<StoredDraft>
    suspend fun write(drafts: List<StoredDraft>)
}

/** Archivo completo cifrado con una llave propia del Keystore (`KeystoreCipher`). Se reescribe entero en cada cambio. */
class EncryptedFileDraftStore(private val file: File, private val cipher: SecretCipher) : DraftStore {
    private val serializer = ListSerializer(StoredDraft.serializer())

    override suspend fun read(): List<StoredDraft> {
        if (!file.exists()) return emptyList()
        return try {
            Json.decodeFromString(serializer, cipher.decrypt(file.readBytes()).decodeToString())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Llave perdida o archivo dañado: no se puede leer, y ninguno se mandó a Nexora sin una confirmación.
            emptyList()
        }
    }

    override suspend fun write(drafts: List<StoredDraft>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(cipher.encrypt(Json.encodeToString(serializer, drafts).encodeToByteArray()))
        if (!tmp.renameTo(file)) throw IOException("No se pudo guardar los borradores")
    }
}

/**
 * Borradores de gasto o ingreso que llegan del reloj (WC-40, 03 §2). Nada va a Nexora sin que el usuario lo confirme
 * en el teléfono; la confirmación es `POST /api/v1/transactions` con la `Idempotency-Key` que generó el reloj.
 *
 * - 2xx → [State.CREATED]. 409 → [State.CONFLICT]. Otro 4xx → [State.REJECTED] con `HTTP_n`.
 * - Sin respuesta (sin red, 5xx) → [State.UNKNOWN]: pudo registrarse. Ya no se puede descartar, solo reintentar; el
 *   reintento con la misma clave y el mismo cuerpo devuelve la respuesta original si ya se aplicó.
 * - Sesión vencida antes de que Nexora lo aceptara → el estado no avanza y queda el código `SIGNED_OUT`.
 *
 * Los registrados y descartados se olvidan pasado [keepFinalMs].
 *
 * @param buildBody el `CreateTransactionRequest` en JSON; se llama una sola vez por borrador.
 * @param post manda el cuerpo con su clave y devuelve el código HTTP; lanza si no hubo respuesta.
 * @param onChange avisa al reloj de cada cambio de estado.
 */
class PhoneDrafts(
    private val store: DraftStore,
    private val buildBody: (QuickTransactionDraft) -> String,
    private val post: suspend (key: UUID, body: String) -> Int,
    private val onChange: suspend (DraftStatus) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val keepFinalMs: Long = KEEP_FINAL_MS,
) {
    private val mutex = Mutex()
    private val _drafts = MutableStateFlow<List<StoredDraft>>(emptyList())
    val drafts: StateFlow<List<StoredDraft>> = _drafts.asStateFlow()

    /** Claves con un envío en curso: un doble toque no manda dos veces el mismo borrador. */
    private val _sending = MutableStateFlow<Set<String>>(emptySet())
    val sending: StateFlow<Set<String>> = _sending.asStateFlow()

    /** Los que siguen sin resolver en el teléfono (para el aviso de la pantalla principal). */
    val pending: List<StoredDraft> get() = _drafts.value.filter { !it.state.final }

    suspend fun load() = mutex.withLock { _drafts.value = prune(store.read()) }

    /** Estado de cada borrador conocido, para el Pong. */
    fun statuses(): List<DraftStatus> = _drafts.value.map { it.status }

    /** Guarda un borrador nuevo; si la clave ya se conocía (el reloj reenvía), solo devuelve su estado. */
    suspend fun receive(draft: QuickTransactionDraft): DraftStatus = mutex.withLock {
        _drafts.value.firstOrNull { it.draft.idempotencyKey == draft.idempotencyKey }?.let { return it.status }
        val stored = StoredDraft(draft, State.RECEIVED, clock())
        save(prune(_drafts.value) + stored)
        stored.status
    }

    /** Confirma (o reintenta) el borrador [key]. Devuelve su estado nuevo, o `null` si no existe o no se puede. */
    suspend fun confirm(key: String): DraftStatus? {
        val sending = mutex.withLock {
            val current = find(key)?.takeIf { it.confirmable && key !in _sending.value } ?: return null
            _sending.value += key
            // El cuerpo se congela en el primer envío: cada reintento manda exactamente el mismo.
            val frozen = current.copy(requestBody = current.requestBody ?: buildBody(current.draft))
            replace(frozen)
            frozen
        }
        try {
            val (state, code) = try {
                when (val status = post(UUID.fromString(key), sending.requestBody!!)) {
                    in 200..299 -> State.CREATED to null
                    409 -> State.CONFLICT to "HTTP_409"
                    in 400..499 -> State.REJECTED to "HTTP_$status"
                    else -> State.UNKNOWN to "HTTP_$status"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: SessionExpiredException) {
                // Nexora no lo aceptó (no hubo sesión o respondió 401): el estado no avanza.
                sending.state to "SIGNED_OUT"
            } catch (_: Exception) {
                State.UNKNOWN to "UNREACHABLE"
            }
            return update(key) { it.copy(state = state, errorCode = code, updatedAt = clock()) }
        } finally {
            _sending.value -= key
        }
    }

    /** Descarta un borrador que no llegó a Nexora; tras un envío sin respuesta no se puede (hay que reintentar). */
    suspend fun discard(key: String): DraftStatus? = update(key, only = { it.discardable }) {
        it.copy(state = State.DISCARDED, errorCode = null, updatedAt = clock())
    }

    private suspend fun update(key: String, only: (StoredDraft) -> Boolean = { true }, change: (StoredDraft) -> StoredDraft): DraftStatus? {
        val updated = mutex.withLock {
            val current = find(key)?.takeIf(only) ?: return null
            change(current).also { replace(it) }
        }
        onChange(updated.status)
        return updated.status
    }

    private fun find(key: String) = _drafts.value.firstOrNull { it.draft.idempotencyKey == key }

    private suspend fun replace(draft: StoredDraft) =
        save(_drafts.value.map { if (it.draft.idempotencyKey == draft.draft.idempotencyKey) draft else it })

    private suspend fun save(drafts: List<StoredDraft>) {
        store.write(drafts)
        _drafts.value = drafts
    }

    private fun prune(drafts: List<StoredDraft>): List<StoredDraft> {
        val now = clock()
        return drafts.filterNot { it.state.final && now - it.updatedAt > keepFinalMs }
    }

    companion object {
        const val KEEP_FINAL_MS = 24 * 60 * 60_000L
    }
}
