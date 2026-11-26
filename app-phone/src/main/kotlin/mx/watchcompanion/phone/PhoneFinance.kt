package mx.watchcompanion.phone

import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.SerializationException
import mx.watchcompanion.core.communication.DraftOptions
import mx.watchcompanion.core.communication.FinanceFigures
import mx.watchcompanion.core.communication.FinanceSnapshot
import mx.watchcompanion.core.session.SessionExpiredException
import mx.watchcompanion.core.session.SessionState
import mx.watchcompanion.integration.nexora.NexoraSession
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import mx.watchcompanion.core.communication.RecentTransaction
import mx.watchcompanion.core.communication.UpcomingPayment
import mx.watchcompanion.integration.nexora.NexoraClient
import mx.watchcompanion.integration.nexora.api.models.DashboardResponse
import mx.watchcompanion.integration.nexora.api.models.TransactionResponse

class NexoraHttpException(status: Int) : Exception("Nexora respondió $status") {
    val code = "HTTP_$status"
}

/** Nexora respondió 2xx sin alguna de las cifras que el reloj muestra: no se inventa un cero. */
class IncompleteDashboardException : Exception("Al dashboard le faltan cifras")

/**
 * Resumen financiero de Nexora para el reloj (WC-38). Como la actividad de Velora (WC-33): el Pong lleva el último
 * conocido y, si tiene más de [maxAgeMs], se consulta aparte y se reenvía.
 *
 * Solo vive en memoria (03 §6: no persistir snapshots más de lo necesario). Un fallo conserva las últimas cifras con
 * su hora original y estado [FinanceSnapshot.State.ERROR]; sin sesión no queda ninguna cifra.
 */
class PhoneFinance(
    private val session: () -> SessionState,
    private val fetch: suspend (now: Long) -> FinanceFigures,
    /** Cuentas y categorías para el borrador del reloj (WC-40). Si fallan, se quedan las anteriores: no tumban las cifras. */
    private val fetchOptions: suspend () -> DraftOptions? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAgeMs: Long = MAX_AGE_MS,
) {
    private val _snapshot = MutableStateFlow<FinanceSnapshot?>(null)
    val snapshot: StateFlow<FinanceSnapshot?> = _snapshot.asStateFlow()
    private val refreshing = Mutex()

    /** Consulta ya. Mientras se lee la sesión guardada no se informa nada (el reloj muestra «sin datos»). */
    suspend fun refresh(): FinanceSnapshot? {
        val now = clock()
        val result = when (session()) {
            SessionState.Restoring -> return _snapshot.value
            is SessionState.SignedOut -> FinanceSnapshot(FinanceSnapshot.State.SIGNED_OUT, now)
            is SessionState.SignedIn -> query(now)
        }
        _snapshot.value = result
        return result
    }

    private suspend fun query(now: Long): FinanceSnapshot {
        val previous = _snapshot.value
        fun failed(code: String) = FinanceSnapshot(FinanceSnapshot.State.ERROR, now, previous?.figures, code, previous?.options)
        return try {
            val figures = fetch(now)
            val options = try {
                fetchOptions()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: previous?.options
            FinanceSnapshot(FinanceSnapshot.State.OK, now, figures, options = options)
        } catch (e: CancellationException) {
            throw e
        } catch (_: SessionExpiredException) {
            FinanceSnapshot(FinanceSnapshot.State.SIGNED_OUT, now)
        } catch (e: NexoraHttpException) {
            failed(e.code)
        } catch (_: SerializationException) {
            // Nexora respondió algo que este contrato no conoce: se actualiza el contrato (WC-36).
            failed("UNREADABLE_RESPONSE")
        } catch (_: IncompleteDashboardException) {
            failed("INCOMPLETE_RESPONSE")
        } catch (_: IllegalArgumentException) {
            failed("INCOMPLETE_RESPONSE")
        } catch (_: Exception) {
            failed("UNREACHABLE")
        }
    }

    /** Consulta solo si lo guardado es viejo y no hay otra consulta en curso; devuelve el nuevo resumen o `null`. */
    suspend fun refreshIfStale(): FinanceSnapshot? {
        val current = _snapshot.value
        if (current != null && clock() - current.checkedAt < maxAgeMs) return null
        if (!refreshing.tryLock()) return null
        return try {
            refresh()
        } finally {
            refreshing.unlock()
        }
    }

    companion object {
        const val MAX_AGE_MS = 5 * 60_000L
    }
}

/**
 * Lo que muestra el reloj, tal como lo da Nexora; el teléfono tampoco recalcula. Los importes se normalizan a dos
 * decimales (MXN) solo para su presentación.
 *
 * - Tres cifras (WC-38): si falta una, es un error, no un cero.
 * - Próximos pagos y últimos movimientos (WC-39): un elemento incompleto se omite. Del movimiento solo viaja la fecha,
 *   el comercio o la descripción (enmascarados y recortados), el `balanceEffect` y el tipo; la referencia bancaria no.
 *   Los anulados (`VOIDED`) no se muestran.
 *
 * @param recent movimientos leídos uno por uno (ver [parseDashboard]).
 */
fun financeFigures(body: DashboardResponse, fetchedAt: Long, recent: List<TransactionResponse> = emptyList()): FinanceFigures {
    fun amount(value: Double?) = value?.let(::decimal) ?: throw IncompleteDashboardException()
    return FinanceFigures(
        fetchedAt = fetchedAt,
        month = body.month ?: throw IncompleteDashboardException(),
        availableBalance = amount(body.availableBalance),
        creditCardDebt = amount(body.creditCardDebt),
        expenseThisMonth = amount(body.expenseThisMonth),
        upcomingPayments = body.upcomingPayments.orEmpty().mapNotNull { p ->
            UpcomingPayment(
                cardName = label(p.creditCardName ?: return@mapNotNull null),
                dueDate = (p.dueDate ?: return@mapNotNull null).toString(),
                expectedPayment = decimal(p.expectedPayment ?: return@mapNotNull null),
            )
        }.sortedBy { it.dueDate }.take(FinanceFigures.MAX_ITEMS),
        recentTransactions = recent.mapNotNull { t ->
            if (t.status == TransactionResponse.Status.VOIDED) return@mapNotNull null
            RecentTransaction(
                date = (t.date ?: return@mapNotNull null).toString(),
                label = label(t.merchant?.takeIf { it.isNotBlank() } ?: t.description.orEmpty()),
                signedAmount = decimal(t.balanceEffect ?: return@mapNotNull null),
                type = (t.type ?: return@mapNotNull null).value,
            )
        }.take(FinanceFigures.MAX_ITEMS),
    )
}

private fun decimal(value: Double) = BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_EVEN).toPlainString()

/** Sin números de cuenta o tarjeta, sin saltos de línea y del largo que cabe en el reloj. */
private fun label(text: String): String =
    FinanceFigures.mask(text.replace(Regex("\\s+"), " ").trim()).take(FinanceFigures.MAX_LABEL).trim()

/**
 * Lee el dashboard tolerando movimientos que el contrato fijado no entiende (un tipo nuevo, por ejemplo): se decodifican
 * uno por uno y el ilegible se omite, en lugar de tumbar las cifras.
 */
fun parseDashboard(body: String): Pair<DashboardResponse, List<TransactionResponse>> {
    val json = NexoraClient.json
    val obj = json.parseToJsonElement(body).jsonObject
    val recent = (obj["recentTransactions"] as? JsonArray).orEmpty().mapNotNull { element ->
        try {
            json.decodeFromJsonElement(TransactionResponse.serializer(), element)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
    val rest = JsonObject(obj - "recentTransactions")
    return json.decodeFromJsonElement(DashboardResponse.serializer(), rest) to recent
}

suspend fun nexoraFinance(session: NexoraSession, now: Long): FinanceFigures {
    // Mes actual según Nexora, con los últimos movimientos para el reloj (WC-39).
    val response = session.call("nexora.dashboardGet") {
        dashboardGet(month = null, recentTransactionsLimit = FinanceFigures.MAX_ITEMS)
    }
    if (!response.success) throw NexoraHttpException(response.status)
    val (dashboard, recent) = parseDashboard(response.response.bodyAsText())
    return financeFigures(dashboard, now, recent)
}
