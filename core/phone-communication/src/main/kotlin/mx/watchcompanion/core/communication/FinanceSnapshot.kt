package mx.watchcompanion.core.communication

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Resumen financiero de solo lectura de Nexora (WC-38, 03 §2), tal como lo consultó el teléfono en
 * `GET /api/v1/dashboard`. El reloj solo lo muestra: no calcula ninguna cifra (03 §1).
 *
 * Si una consulta falla, [figures] conserva las últimas cifras buenas con su [FinanceFigures.fetchedAt] y el estado es
 * [State.ERROR]: el reloj las marca como desactualizadas, nunca como vigentes. Sin sesión en Nexora no viaja ninguna
 * cifra, así que cerrar sesión en el teléfono las borra del reloj.
 */
@Serializable
data class FinanceSnapshot(
    val state: State,
    /** Cuándo intentó el teléfono la última consulta (ms epoch UTC), haya salido bien o no. */
    val checkedAt: Long,
    val figures: FinanceFigures? = null,
    /** Motivo de [State.ERROR], con el formato de los códigos de diagnóstico. */
    val errorCode: String? = null,
) {
    init {
        require(errorCode == null || CODE.matches(errorCode)) { "código de error inválido" }
        require(state != State.SIGNED_OUT || figures == null) { "sin sesión no viajan cifras" }
    }

    /** Desactualizado: la última consulta falló o las cifras tienen más de [STALE_AFTER_MS]. */
    fun isStale(now: Long): Boolean {
        val fetchedAt = figures?.fetchedAt ?: return true
        return state != State.OK || now - fetchedAt > STALE_AFTER_MS
    }

    @Serializable
    enum class State {
        /** La última consulta salió bien: [figures] es de [checkedAt]. */
        OK,

        /** No hay sesión de Nexora en el teléfono. */
        SIGNED_OUT,
        ERROR,
    }

    companion object {
        /** Pasado este tiempo, una cifra se marca como desactualizada aunque la última consulta saliera bien. */
        const val STALE_AFTER_MS: Long = 15 * 60_000L
        private val CODE = Regex("[A-Z][A-Z0-9_]{0,31}")
    }
}

/**
 * Las cifras del dashboard que muestra el reloj, tal como las da Nexora (ya convertidas a MXN). Los importes viajan
 * como texto decimal (`"1234.50"`) para que nadie los recalcule ni pierda precisión por el camino.
 */
@Serializable
data class FinanceFigures(
    /** Cuándo respondió Nexora (ms epoch UTC). */
    val fetchedAt: Long,
    /** Mes de los gastos, `yyyy-MM`. */
    val month: String,
    val availableBalance: String,
    /** Deuda total de tarjetas (no lo que toca pagar este corte). */
    val creditCardDebt: String,
    val expenseThisMonth: String,
    val currency: String = "MXN",
) {
    init {
        require(MONTH.matches(month)) { "mes inválido" }
        require(listOf(availableBalance, creditCardDebt, expenseThisMonth).all { AMOUNT.matches(it) }) { "importe inválido" }
        require(CURRENCY.matches(currency)) { "moneda inválida" }
    }

    private companion object {
        val MONTH = Regex("\\d{4}-\\d{2}")
        val AMOUNT = Regex("-?\\d{1,15}(\\.\\d{1,4})?")
        val CURRENCY = Regex("[A-Z]{3}")
    }
}

/** El teléfono envía el resumen en cuanto termina de consultarlo o cambia la sesión de Nexora; el Pong lleva el último. */
@Serializable
@SerialName("FinanceSnapshotReport")
data class FinanceSnapshotReport(val snapshot: FinanceSnapshot) : Payload
