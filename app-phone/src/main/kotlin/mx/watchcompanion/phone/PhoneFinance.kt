package mx.watchcompanion.phone

import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.SerializationException
import mx.watchcompanion.core.communication.FinanceFigures
import mx.watchcompanion.core.communication.FinanceSnapshot
import mx.watchcompanion.core.session.SessionExpiredException
import mx.watchcompanion.core.session.SessionState
import mx.watchcompanion.integration.nexora.NexoraSession
import mx.watchcompanion.integration.nexora.api.models.DashboardResponse

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
        fun failed(code: String) = FinanceSnapshot(FinanceSnapshot.State.ERROR, now, _snapshot.value?.figures, code)
        return try {
            FinanceSnapshot(FinanceSnapshot.State.OK, now, fetch(now))
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
 * Solo las tres cifras que muestra el reloj, tal como las da Nexora; el teléfono tampoco las recalcula. Se normalizan a
 * dos decimales (MXN) solo para su presentación.
 */
fun financeFigures(body: DashboardResponse, fetchedAt: Long): FinanceFigures {
    fun amount(value: Double?) = value?.let { BigDecimal.valueOf(it).setScale(2, RoundingMode.HALF_EVEN).toPlainString() }
        ?: throw IncompleteDashboardException()
    return FinanceFigures(
        fetchedAt = fetchedAt,
        month = body.month ?: throw IncompleteDashboardException(),
        availableBalance = amount(body.availableBalance),
        creditCardDebt = amount(body.creditCardDebt),
        expenseThisMonth = amount(body.expenseThisMonth),
    )
}

suspend fun nexoraFinance(session: NexoraSession, now: Long): FinanceFigures {
    // Mes actual según Nexora; el mínimo de movimientos recientes es 1 y el reloj no los usa.
    val response = session.call("nexora.dashboardGet") { dashboardGet(month = null, recentTransactionsLimit = 1) }
    if (!response.success) throw NexoraHttpException(response.status)
    return financeFigures(response.body(), now)
}
